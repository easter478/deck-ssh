package com.deckssh.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.deckssh.app.R
import com.deckssh.app.input.Spec
import com.deckssh.app.ssh.SshTab
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import kotlin.math.abs
import kotlin.math.floor

/** Points the emulator's default colours at the screen theme so programs that reset colours stay on-theme. */
fun applyTerminalColors(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color, cursor: androidx.compose.ui.graphics.Color, tabs: List<SshTab>) {
    val d = TerminalColors.COLOR_SCHEME.mDefaultColors
    d[TextStyle.COLOR_INDEX_FOREGROUND] = fg.toArgb()
    d[TextStyle.COLOR_INDEX_BACKGROUND] = bg.toArgb()
    d[TextStyle.COLOR_INDEX_CURSOR] = cursor.toArgb()
    tabs.forEach { it.emulator.mColors.reset() }
}

@Composable
fun TerminalCanvas(
    tab: SshTab,
    fontSp: Float,
    onFontSize: (Float) -> Unit,
    onArrow: (Spec) -> Unit,
    onLongPress: () -> Unit,
    drawBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val regular = remember { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono) ?: Typeface.MONOSPACE }
    val bold = remember { ResourcesCompat.getFont(ctx, R.font.jetbrains_mono_bold) ?: Typeface.DEFAULT_BOLD }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = regular; fontFeatureSettings = "'liga' 0, 'calt' 0" }
    }
    val textPx = with(density) { fontSp * this.fontScale * this.density }
    paint.textSize = textPx
    val cellW = paint.measureText("M")
    val fm = paint.fontMetrics
    val cellH = (fm.descent - fm.ascent).let { kotlin.math.ceil(it) }
    val baseline = -fm.ascent
    var size by remember { mutableStateOf(IntSize.Zero) }
    val font by rememberUpdatedState(fontSp)
    val ch by rememberUpdatedState(cellH)
    val pad = with(density) { 6.dp.toPx() }

    LaunchedEffect(size, cellW, cellH, tab) {
        if (size.width > 0) {
            val cols = floor((size.width - pad * 2) / cellW).toInt()
            val rows = floor((size.height - pad) / cellH).toInt()
            tab.resize(cols, rows, size.width, size.height)
        }
    }

    Box(
        modifier
            .onSizeChanged { size = it }
            .systemGestureExclusion { Rect(0f, 0f, with(density) { 28.dp.toPx() }, it.size.height.toFloat()) }
            .pointerInput(tab) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var f = font
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.size >= 2) {
                            val z = e.calculateZoom()
                            if (z != 1f) { f = (f * z).coerceIn(8f, 28f); onFontSize(f) }
                            e.changes.forEach { it.consume() }
                        }
                    } while (e.changes.any { it.pressed })
                }
            }
            .pointerInput(tab) {
                var acc = 0f
                detectVerticalDragGestures(onDragStart = { acc = 0f }) { change, dy ->
                    acc += dy
                    val steps = (acc / ch).toInt()
                    if (steps != 0) {
                        acc -= steps * ch
                        val e = tab.emulator
                        if (e.isAlternateBufferActive) {
                            repeat(abs(steps)) { onArrow(if (steps > 0) Spec.Up else Spec.Down) }
                        } else {
                            tab.scroll = (tab.scroll + steps).coerceIn(0, e.screen.activeTranscriptRows)
                        }
                    }
                    change.consume()
                }
            }
            .pointerInput(tab) { detectTapGestures(onLongPress = { onLongPress() }) }
            .drawBehind {
                @Suppress("UNUSED_VARIABLE") val v = tab.version
                val e = tab.emulator
                val colors = e.mColors.mCurrentColors
                val screen = e.screen
                val scroll = tab.scroll.coerceAtMost(screen.activeTranscriptRows)
                fun argb(i: Int) = if (i and 0xff000000.toInt() == 0xff000000.toInt()) i else colors[i]
                val defBg = colors[TextStyle.COLOR_INDEX_BACKGROUND]
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    if (drawBackground) nc.drawColor(defBg)
                    val cols = e.mColumns
                    val sb = StringBuilder()
                    for (r in 0 until e.mRows) {
                        val ext = r - scroll
                        val line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(ext))
                        val y = pad / 2 + r * cellH
                        var col = 0
                        while (col < cols) {
                            val style = line.getStyle(col)
                            val start = line.findStartOfColumn(col)
                            val cp = if (start < line.spaceUsed) Character.codePointAt(line.mText, start) else ' '.code
                            val w = WcWidth.width(cp).coerceAtLeast(1).coerceAtMost(cols - col)
                            val end = if (col + w >= cols) line.spaceUsed else line.findStartOfColumn(col + w)
                            sb.setLength(0)
                            if (end > start) sb.appendRange(line.mText, start, end)
                            val effect = TextStyle.decodeEffect(style)
                            var fg = TextStyle.decodeForeColor(style)
                            var bg = TextStyle.decodeBackColor(style)
                            if (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0 && fg in 0..7) fg += 8
                            var fgc = argb(fg); var bgc = argb(bg)
                            if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) { val t = fgc; fgc = bgc; bgc = t }
                            if (e.isReverseVideo) { val t = fgc; fgc = bgc; bgc = t }
                            val x = pad + col * cellW
                            if (bgc != defBg) {
                                paint.color = bgc
                                nc.drawRect(x, y, x + w * cellW, y + cellH, paint)
                            }
                            val isCursor = scroll == 0 && r == e.cursorRow && col == e.cursorCol && e.shouldCursorBeVisible()
                            if (isCursor) {
                                paint.color = colors[TextStyle.COLOR_INDEX_CURSOR]
                                nc.drawRect(x, y, x + w * cellW, y + cellH, paint)
                                fgc = defBg
                            }
                            val txt = sb.toString()
                            if (txt.isNotBlank() && effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                                paint.color = fgc
                                if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) paint.alpha = 160
                                paint.typeface = if (effect and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0) bold else regular
                                paint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.2f else 0f
                                paint.isUnderlineText = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
                                paint.isStrikeThruText = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
                                nc.drawText(txt, x, y + baseline, paint)
                                paint.typeface = regular; paint.textSkewX = 0f; paint.isUnderlineText = false; paint.isStrikeThruText = false
                            }
                            col += w
                        }
                    }
                }
            },
    )
}
