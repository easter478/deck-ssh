package com.deckssh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

enum class CapStyle { BlackBerry, Flat, Bevel, Mech, Neon }

/** A device the phone pretends to be: chassis, screen, keys and branding. */
class Skin(
    val id: String, val name: String,
    val body: Brush, val edge: Color, val bezel: Color, val bezelPad: Dp,
    val screenBg: Color, val screenFg: Color,
    val key: Brush, val keyFg: Color, val alt: Color, val glow: Color, val numKey: Color,
    val ctl: Color, val ctlFg: Color, val brand: Color,
    val logo: String, val brandText: String, val brandSerif: Boolean,
    val cap: CapStyle, val keyRadius: Dp,
    val drawerBg: Color, val drawerFg: Color, val dark: Boolean,
    val rain: Boolean = false, val scanlines: Boolean = false, val screws: Boolean = false, val trackball: Boolean = false,
) {
    /** Palette for drawers and panels so the shared UI pieces follow the device. */
    val ui: Palette by lazy {
        val d = drawerBg
        fun l(c: Color) = c.toArgb().toLong() and 0xFFFFFFFFL
        val s2 = if (dark) androidx.compose.ui.graphics.lerp(d, Color.White, 0.08f) else androidx.compose.ui.graphics.lerp(d, Color.Black, 0.06f)
        Palette(
            "skin-$id", name, "device", name, dark,
            l(d), l(d), l(s2), l(drawerFg), l(androidx.compose.ui.graphics.lerp(drawerFg, d, 0.35f)), l(alt.copy(alpha = 0.4f).compositeOverSafe(d)),
            l(glow), l(if (glow.luminanceSafe() > 0.4f) Color.Black else Color.White), l(alt), 0xFFFF6B6B, 0xFFFFB547,
        )
    }
}

private fun Color.compositeOverSafe(bg: Color) = this.compositeOver(bg)
private fun Color.luminanceSafe() = this.luminance()

private fun v(vararg c: Long) = Brush.verticalGradient(c.map { Color(it) })
private fun s(c: Long) = androidx.compose.ui.graphics.SolidColor(Color(c))

object Skins {
    val all = listOf(
        Skin(
            "bb", "BlackBerry Bold", v(0xFF1B1B1E, 0xFF060607, 0xFF121214), Color(0xFFB9BCC0), Color.Black, 6.dp,
            Color(0xFF06080C), Color(0xFFE6EBF2), v(0xFF2C2C30, 0xFF0C0C0E), Color(0xFFF2F2F2), Color(0xFF5FB0FF), Color(0xFF5FB0FF), Color(0xFF16202C),
            Color(0xFF1C1C1F), Color(0xFFDADADA), Color(0xFFD9DBDE), "bb", "BlackBerry", true,
            CapStyle.BlackBerry, 4.dp, Color(0xFF0E1117), Color(0xFFE6EBF2), true,
        ),
        Skin(
            "uc", "uConsole", s(0xFF9BA1A7), Color(0xFF6C7176), Color(0xFF15171A), 12.dp,
            Color(0xFF0A0E0B), Color(0xFF9CF0A8), s(0xFF2A2D31), Color(0xFFE9EDF1), Color(0xFFF2B84B), Color(0xFFF2B84B), Color(0xFF3A3226),
            Color(0xFF2A2D31), Color(0xFFE9EDF1), Color(0xFF2B2F33), "gear", "CLOCKWORK·DECK", false,
            CapStyle.Flat, 6.dp, Color(0xFF14171A), Color(0xFFE9EDF1), true, trackball = true,
        ),
        Skin(
            "w95", "Deck 95", s(0xFFD6CFBD), Color(0xFFA59D88), Color(0xFF3B3A36), 6.dp,
            Color(0xFF081509), Color(0xFF7DFF9A), s(0xFFE9E3D3), Color(0xFF1D1B16), Color(0xFF000080), Color(0xFF000080), Color(0xFFD9E3F0),
            Color(0xFFC9C1AD), Color(0xFF1D1B16), Color(0xFF3B3A36), "flag", "DECK 95", false,
            CapStyle.Bevel, 2.dp, Color(0xFFECE6D6), Color(0xFF1D1B16), false, scanlines = true,
        ),
        Skin(
            "rg", "Field deck", s(0xFF2F3326), Color(0xFFE3B21C), Color(0xFF0F100C), 6.dp,
            Color(0xFF06090A), Color(0xFFFFCF4A), s(0xFF1B1D18), Color(0xFFFFCF4A), Color(0xFF9FD36B), Color(0xFFFFCF4A), Color(0xFF2A2A12),
            Color(0xFF1B1D18), Color(0xFFFFCF4A), Color(0xFFE3B21C), "hex", "FIELD-DECK MK2", false,
            CapStyle.Mech, 7.dp, Color(0xFF15170F), Color(0xFFF2E6C0), true, screws = true,
        ),
        Skin(
            "mx", "Matrix", s(0xFF020402), Color(0xFF0F3D1A), Color.Black, 6.dp,
            Color.Black, Color(0xFFB6FFBF), s(0xFF030A04), Color(0xFF00FF41), Color(0xFFB6FFB8), Color(0xFF00FF41), Color(0xFF061A09),
            Color(0xFF030A04), Color(0xFF00FF41), Color(0xFF00FF41), "term", "ZION·DECK", false,
            CapStyle.Neon, 5.dp, Color.Black, Color(0xFF00FF41), true, rain = true,
        ),
    )

    fun byId(id: String) = all.firstOrNull { it.id == id } ?: all.first()
    val glyphs = listOf("bb", "term", "skull", "hex", "gear", "flag", "none")
}

/** Draws one of the logo glyphs in a box of the given size. */
fun DrawScope.logoGlyph(id: String, c: Color) {
    val w = size.width; val h = size.height
    when (id) {
        "bb" -> {
            // Seven pills in the BlackBerry pattern, on a 44 x 28 grid.
            val sx = w / 44f; val sy = h / 28f
            listOf(10 to 0, 23 to 0, 6 to 10, 19 to 10, 33 to 6, 2 to 20, 15 to 20).forEach { (x, y) ->
                drawRoundRect(c, Offset(x * sx, y * sy), Size(10 * sx, 7 * sy), CornerRadius(3.5f * sx))
            }
        }
        "term" -> {
            val st = Stroke(w * 0.08f)
            drawRoundRect(c, Offset(w * .04f, h * .05f), Size(w * .92f, h * .9f), CornerRadius(w * .12f), style = st)
            val p = Path().apply { moveTo(w * .24f, h * .3f); lineTo(w * .44f, h * .5f); lineTo(w * .24f, h * .7f) }
            drawPath(p, c, style = st)
            drawLine(c, Offset(w * .52f, h * .72f), Offset(w * .78f, h * .72f), w * .08f)
        }
        "skull" -> {
            drawOval(c, Offset(w * .08f, 0f), Size(w * .84f, h * .72f))
            drawRect(c, Offset(w * .26f, h * .55f), Size(w * .48f, h * .4f))
            drawCircle(Color.Black, w * .12f, Offset(w * .34f, h * .42f))
            drawCircle(Color.Black, w * .12f, Offset(w * .66f, h * .42f))
            drawLine(Color.Black, Offset(w * .42f, h * .78f), Offset(w * .42f, h * .95f), w * .06f)
            drawLine(Color.Black, Offset(w * .58f, h * .78f), Offset(w * .58f, h * .95f), w * .06f)
        }
        "hex" -> {
            val p = Path().apply {
                moveTo(w * .5f, h * .03f); lineTo(w * .93f, h * .27f); lineTo(w * .93f, h * .73f)
                lineTo(w * .5f, h * .97f); lineTo(w * .07f, h * .73f); lineTo(w * .07f, h * .27f); close()
            }
            drawPath(p, c, style = Stroke(w * .09f))
            drawCircle(c, w * .16f, Offset(w * .5f, h * .5f))
        }
        "gear" -> {
            val ctr = Offset(w / 2, h / 2)
            repeat(8) { i -> rotate(i * 45f, ctr) { drawRect(c, Offset(w * .43f, 0f), Size(w * .14f, h * .22f)) } }
            drawCircle(c, w * .33f, ctr, style = Stroke(w * .14f))
            drawCircle(c, w * .1f, ctr)
        }
        "flag" -> {
            val g = w * .08f; val cw = (w - g) / 2; val ch = (h - g) / 2
            drawRect(Color(0xFFEE3333), Offset(0f, 0f), Size(cw, ch))
            drawRect(Color(0xFF33AA33), Offset(cw + g, 0f), Size(cw, ch))
            drawRect(Color(0xFF3366CC), Offset(0f, ch + g), Size(cw, ch))
            drawRect(Color(0xFFEECC22), Offset(cw + g, ch + g), Size(cw, ch))
        }
    }
}

/** The badge under the screen: glyph plus name, with a light sweep that glints across it. */
@Composable
fun BrandBadge(skin: Skin, glyph: String, text: String, glint: Boolean, caret: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "glint")
    val sweep by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(keyframes { durationMillis = 3600; 0f at 0; 0f at 2200 using LinearEasing; 1f at 3200; 1f at 3600 }, RepeatMode.Restart),
        label = "sweep",
    )
    Row(
        modifier
            .clipToBounds()
            .drawWithContent {
                drawContent()
                if (glint && sweep in 0.001f..0.999f) {
                    val x = -60f + sweep * (size.width + 120f)
                    rotate(20f, Offset(x, size.height / 2)) {
                        drawRect(
                            Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.8f), Color.Transparent), startX = x - 22f, endX = x + 22f),
                            Offset(x - 22f, -size.height), Size(44f, size.height * 3),
                        )
                    }
                }
            }
            .padding(horizontal = Tokens.s2, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != "none") {
            Canvas(Modifier.size(if (glyph == "bb") 30.dp else 20.dp, if (glyph == "bb") 19.dp else 20.dp)) { logoGlyph(glyph, skin.brand) }
            Spacer(Modifier.width(Tokens.s2))
        }
        Text(
            text + if (caret) "▌" else "",
            color = skin.brand,
            style = TextStyle(
                fontFamily = if (skin.brandSerif) FontFamily.SansSerif else Mono,
                fontWeight = if (skin.brandSerif) FontWeight.SemiBold else FontWeight.Bold,
                fontStyle = if (skin.id == "w95") FontStyle.Italic else FontStyle.Normal,
                fontSize = if (skin.brandSerif) 17.sp else 12.sp,
                letterSpacing = if (skin.brandSerif) 0.3.sp else 3.sp,
            ),
            maxLines = 1, overflow = TextOverflow.Clip,
        )
    }
}

/** Falling code drawn behind the terminal text. */
@Composable
fun CodeRain(color: Color, modifier: Modifier = Modifier) {
    val glyphs = "アイウエオカキクケコサシスセソタチツテトハヒフヘホ0123456789$#@*+=<>"
    val measurer = rememberTextMeasurer()
    var tick by remember { mutableIntStateOf(0) }
    val drops = remember { FloatArray(80) { Random.nextFloat() * -40f } }
    val chars = remember { CharArray(80 * 60) { glyphs.random() } }
    LaunchedEffect(Unit) { while (true) { delay(70); tick++ } }
    val style = TextStyle(fontFamily = Mono, fontSize = 11.sp)
    Canvas(modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = tick
        val cell = 11.sp.toPx()
        val cols = (size.width / cell).toInt().coerceAtMost(drops.size)
        val rows = (size.height / cell).toInt().coerceAtMost(60)
        for (i in 0 until cols) {
            val head = drops[i]
            for (k in 0 until 14) {
                val r = (head - k).toInt()
                if (r < 0 || r >= rows) continue
                val idx = i * 60 + r
                if (Random.nextFloat() < 0.04f) chars[idx] = glyphs.random()
                val a = if (k == 0) 1f else (0.75f - k * 0.05f).coerceAtLeast(0.05f)
                val col = if (k == 0) Color(0xFFD8FFD8) else color.copy(alpha = a)
                drawText(measurer, chars[idx].toString(), Offset(i * cell, r * cell), style.copy(color = col))
            }
            drops[i] = if (head - 14 > rows && Random.nextFloat() > 0.9f) 0f else head + 0.5f + (i % 3) * 0.15f
        }
    }
}
