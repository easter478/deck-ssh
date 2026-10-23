package com.deckssh.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deckssh.app.data.Host
import com.deckssh.app.data.Store
import com.deckssh.app.input.FieldTarget
import com.deckssh.app.input.InputRouter
import com.deckssh.app.ssh.SshTab
import com.deckssh.app.ssh.Status
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Open shells outlive the activity so recreating the UI keeps them connected. */
object Sessions {
    val tabs = mutableStateListOf<SshTab>()
    /** The tab in front; null shows the local console. */
    var active by mutableStateOf<String?>(null)
    val router = InputRouter()
    val console = Console()
}

@Composable
fun App(store: Store) {
    val skin = Skins.byId(store.skinId)
    val screen = if (store.themeId == "skin") null else ThemePack.byId(store.themeId)
    val fg = screen?.fg ?: skin.screenFg
    val bg = screen?.bg ?: skin.screenBg
    val cursor = screen?.primary ?: skin.glow
    LaunchedEffect(fg, bg, cursor) {
        applyTerminalColors(fg, bg, cursor, Sessions.tabs)
        Sessions.tabs.forEach { it.redraw() }
    }
    val activity = LocalContext.current as Activity
    LaunchedEffect(Unit) { activity.window.decorView.setBackgroundColor(Color.Black.toArgb()) }
    // Console headings and errors must read on the screen, whatever colour the skin's status bar is.
    val accent = readable(screen?.primary ?: statusColor(skin), bg)
    val danger = readable(screen?.danger ?: skin.ui.danger, bg)
    DeckTheme(skin.ui) { Device(store, skin, fg, bg, accent, danger) }
}

private fun statusColor(skin: Skin) = if (skin.cap == CapStyle.Neon) skin.glow else skin.alt

@Composable
private fun Device(store: Store, skin: Skin, screenFg: Color, screenBg: Color, accent: Color, danger: Color) {
    val ctx = LocalContext.current
    val router = Sessions.router
    val console = Sessions.console
    var menu by remember { mutableStateOf(false) }
    val active = Sessions.tabs.firstOrNull { it.id == Sessions.active }
    router.tab = active
    val rainOn = store.rain || skin.rain
    val brandText = store.brandTexts[skin.id] ?: skin.brandText
    val glyph = store.logos[skin.id] ?: skin.logo
    val shape = RoundedCornerShape(28.dp)

    fun clipboard() = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    fun clipText() = clipboard().primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
    fun paste() { clipText().takeIf { it.isNotEmpty() }?.let { router.paste(it) } }
    fun connect(h: Host) {
        val t = SshTab(h, File(ctx.filesDir, "known_hosts"))
        t.onClipboardCopy = { clipboard().setPrimaryClip(ClipData.newPlainText("terminal", it)) }
        Sessions.tabs.add(t)
        Sessions.active = t.id
    }
    fun closeTab(t: SshTab) {
        t.close()
        Sessions.tabs.remove(t)
        if (Sessions.active == t.id) Sessions.active = Sessions.tabs.lastOrNull()?.id
    }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        console.keyFile(uri?.let { u -> runCatching { ctx.contentResolver.openInputStream(u)?.use { it.readBytes().decodeToString() } }.getOrNull() })
    }
    console.env = ConsoleEnv(store, ::connect, { pickKey.launch(arrayOf("*/*")) }, ::clipText)
    router.idle = console.target
    LaunchedEffect(Unit) { console.boot() }

    /** Brings the console to the front and runs a command in it. */
    fun consoleRun(cmd: String) { Sessions.active = null; router.focus(null); console.exec(cmd) }
    fun command(c: String) {
        when (c) {
            "paste" -> paste()
            "hosts" -> consoleRun("/host")
            "newtab" -> { Sessions.active = null; router.focus(null) }
            "setup" -> consoleRun("/setup")
            "skin" -> consoleRun("/theme")
            "logo" -> consoleRun("/logo")
            "themes" -> consoleRun("/colors")
            "closetab" -> active?.let { closeTab(it) }
        }
    }

    BackHandler {
        when {
            active == null && console.busy -> console.cancel()
            active != null -> Sessions.active = null
            else -> (ctx as Activity).moveTaskToBack(true)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .background(skin.body, shape)
            .border(3.dp, skin.edge, shape),
    ) {
        // The deck (logo strip and keyboard) always takes at least 45% of the screen.
        val fixed = 38f + 36f + 14f + if (skin.cap == CapStyle.BlackBerry) 42f else 30f
        val minKey = (maxHeight.value * 0.45f - fixed) / 5.48f
        val keyH = maxOf(store.keyHeight.toFloat(), minKey).coerceAtMost(64f).dp
        val bezelShape = RoundedCornerShape(if (skin.cap == CapStyle.Bevel) 6.dp else 14.dp)

        Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
            TopBezel(skin)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(skin.bezel, bezelShape)
                    .then(if (skin.cap == CapStyle.Neon) Modifier.border(1.dp, Color(0xFF0F3D1A), bezelShape) else Modifier)
                    .padding(skin.bezelPad),
            ) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(if (skin.cap == CapStyle.Bevel) 10.dp else 6.dp)).background(screenBg)) {
                    if (rainOn) Box(Modifier.fillMaxSize().padding(bottom = 18.dp)) { CodeRain(if (skin.rain) skin.glow else skin.screenFg) }
                    Box(Modifier.fillMaxSize().padding(bottom = 18.dp)) {
                        if (active == null) {
                            ConsoleScreen(
                                console, screenFg, accent, danger, store.fontSize, typing = router.field == null,
                                modifier = Modifier.clickable(interactionSource = null, indication = null) { router.focus(null) },
                            )
                        } else {
                            TerminalCanvas(
                                tab = active,
                                fontSp = store.fontSize,
                                onFontSize = store::changeFontSize,
                                onArrow = { router.special(it, shift = false, ctrl = false) },
                                onLongPress = { menu = true },
                                drawBackground = !rainOn,
                                modifier = Modifier.fillMaxSize(),
                            )
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Paste") }, { menu = false; paste() })
                                DropdownMenuItem({ Text("Copy screen") }, {
                                    menu = false
                                    clipboard().setPrimaryClip(ClipData.newPlainText("terminal", active.emulator.screen.transcriptText))
                                })
                                if (active.status == Status.Failed || active.status == Status.Closed) {
                                    DropdownMenuItem({ Text("Reconnect") }, { menu = false; active.connect() })
                                }
                                DropdownMenuItem({ Text("Console") }, { menu = false; Sessions.active = null })
                                DropdownMenuItem({ Text("Close tab") }, { menu = false; closeTab(active) })
                            }
                        }
                    }
                    if (skin.scanlines) Scanlines()
                    StatusLine(
                        skin, active, Modifier.align(Alignment.BottomCenter),
                        onConsole = { Sessions.active = null }, onHosts = { consoleRun("/host") }, onSetup = { consoleRun("/setup") },
                    )

                    // Swiping in from either edge runs the matching console command.
                    EdgeGrip(Alignment.CenterStart) { dx -> if (dx > 0) consoleRun("/host") }
                    EdgeGrip(Alignment.CenterEnd) { dx -> if (dx < 0) consoleRun("/setup") }
                }
            }
            Box(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.Center) {
                BrandBadge(
                    skin, glyph, brandText, store.glint, caret = false,
                    modifier = Modifier.clickable { consoleRun("/brand") },
                )
            }
            DeckKeyboard(router, skin, keyH, store.haptics, ::command)
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun TopBezel(skin: Skin) {
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
        if (skin.screws) Box(Modifier.size(9.dp).background(Color(0xFF6B6F5C), CircleShape))
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(56.dp).height(5.dp).background(if (skin.cap == CapStyle.Bevel) Color(0xFF9A937F) else Color(0xFF2A2A2D), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(if (skin.cap == CapStyle.Neon) Color(0xFF00FF41) else Color(0xFF3FBF5A), CircleShape))
        Spacer(Modifier.weight(1f))
        if (skin.screws) Box(Modifier.size(9.dp).background(Color(0xFF6B6F5C), CircleShape))
    }
}

@Composable
private fun Scanlines() {
    Canvas(Modifier.fillMaxSize()) {
        var y = 0f
        val step = 3.dp.toPx()
        while (y < size.height) { drawLine(Color.Black.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
}

@Composable
private fun StatusLine(skin: Skin, active: SshTab?, modifier: Modifier, onConsole: () -> Unit, onHosts: () -> Unit, onSetup: () -> Unit) {
    val bar = statusColor(skin)
    val ink = if (bar.luminance() > 0.4f) Color.Black else Color.White
    var clock by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()); delay(15_000) } }
    Row(
        modifier.fillMaxWidth().height(18.dp).background(bar).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (active == null) "[deck]*" else "[deck]", color = ink, fontFamily = Mono, fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onConsole),
        )
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sessions.tabs.forEachIndexed { i, t ->
                val on = t == active
                val mark = when (t.status) { Status.Connected -> if (on) "*" else ""; Status.Connecting -> "…"; Status.Failed -> "!"; Status.Closed -> "-" }
                Text(
                    "$i:${t.host.label}$mark", color = ink, fontFamily = Mono, fontSize = 10.5.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.clickable { Sessions.active = t.id },
                )
            }
        }
        Text("/host", color = ink, fontFamily = Mono, fontSize = 10.5.sp, modifier = Modifier.clickable(onClick = onHosts))
        Text("/setup", color = ink, fontFamily = Mono, fontSize = 10.5.sp, modifier = Modifier.clickable(onClick = onSetup))
        Text(clock, color = ink, fontFamily = Mono, fontSize = 10.5.sp)
    }
}

private fun Modifier.horizontalDrag(onStart: () -> Unit, onDelta: (Float) -> Unit): Modifier = pointerInput(Unit) {
    detectHorizontalDragGestures(onDragStart = { onStart() }) { ch, dx -> ch.consume(); onDelta(dx) }
}

/** Invisible strip on a screen edge: a horizontal drag fires once per swipe. */
@Composable
private fun BoxScope.EdgeGrip(align: Alignment, onDrag: (Float) -> Unit) {
    val d = LocalDensity.current
    var acc by remember { mutableFloatStateOf(0f) }
    var fired by remember { mutableStateOf(false) }
    val trigger = with(d) { 24.dp.toPx() }
    val cap = with(d) { 199.dp.toPx() }
    Box(
        Modifier
            .align(align)
            .width(22.dp)
            .fillMaxHeight(0.7f)
            .systemGestureExclusion { Rect(0f, 0f, it.size.width.toFloat(), minOf(it.size.height.toFloat(), cap)) }
            .horizontalDrag({ acc = 0f; fired = false }) { dx ->
                acc += dx
                if (!fired && abs(acc) > trigger) { onDrag(acc); fired = true }
            },
    )
}
