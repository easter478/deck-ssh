package com.deckssh.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.deckssh.app.input.InputRouter
import com.deckssh.app.input.Spec
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class Mod { Shift, Ctrl, Alt, Sym, Num, Fn, Menu, Abc }
private enum class Kind { Key, Mod, Num, Nav, Cmd }

/** Key definitions. Every row adds up to 10 units, so key edges sit on one shared grid. */
private sealed class K(val w: Float, val kind: Kind) {
    class Ch(val c: String, val alt: String? = null, w: Float = 1f, kind: Kind = Kind.Key) : K(w, kind)
    class Sp(val s: Spec, val label: String, w: Float = 1f, val repeat: Boolean = false, kind: Kind = Kind.Mod) : K(w, kind)
    class M(val m: Mod, val label: String, w: Float = 1f) : K(w, Kind.Mod)
    class Raw(val label: String, val bytes: String, w: Float = 1f) : K(w, Kind.Mod)
    class Cmd(val cmd: String, val label: String, w: Float = 2f) : K(w, Kind.Cmd)
}

private val altMap = mapOf(
    "q" to "#", "w" to "1", "e" to "2", "r" to "3", "t" to "(", "y" to ")", "u" to "_", "i" to "-", "o" to "+", "p" to "@",
    "a" to "*", "s" to "4", "d" to "5", "f" to "6", "g" to "/", "h" to ":", "j" to ";", "k" to "'", "l" to "\"",
    "z" to "7", "x" to "8", "c" to "9", "v" to "?", "b" to "!", "n" to ",", "m" to ".", "-" to "=", "." to ",", ";" to ":",
)

private fun letters(s: String) = s.map { K.Ch(it.toString(), altMap[it.toString()]) }
private fun syms(s: String) = s.map { K.Ch(it.toString()) }
private fun nums(s: String) = s.map { K.Ch(it.toString(), kind = Kind.Num) }

private val back = K.Sp(Spec.Back, "⌫", repeat = true)
private val enter = K.Sp(Spec.Enter, "↵")

private class Layers {
    var shift by mutableIntStateOf(0)
    var ctrl by mutableIntStateOf(0)
    var alt by mutableIntStateOf(0)
    var layer by mutableStateOf("abc")
    var fn by mutableStateOf(false)
    var menu by mutableStateOf(false)

    fun level(m: Mod) = when (m) {
        Mod.Shift -> shift; Mod.Ctrl -> ctrl; Mod.Alt -> alt
        Mod.Sym -> if (layer == "sym") 2 else 0
        Mod.Num -> if (layer == "num") 2 else 0
        Mod.Fn -> if (fn) 2 else 0
        Mod.Menu -> if (menu) 2 else 0
        Mod.Abc -> 0
    }

    fun tap(m: Mod) {
        fun next(v: Int) = (v + 1) % 3
        when (m) {
            Mod.Shift -> shift = next(shift)
            Mod.Ctrl -> ctrl = next(ctrl)
            Mod.Alt -> alt = next(alt)
            Mod.Sym -> layer = if (layer == "sym") "abc" else "sym"
            Mod.Num -> layer = if (layer == "num") "abc" else "num"
            Mod.Abc -> layer = "abc"
            Mod.Fn -> { fn = !fn; if (fn) menu = false }
            Mod.Menu -> { menu = !menu; if (menu) fn = false }
        }
    }

    fun consume() {
        if (shift == 1) shift = 0
        if (ctrl == 1) ctrl = 0
        if (alt == 1) alt = 0
    }

    fun topRow(): List<K> = when {
        menu -> listOf(K.Cmd("themes", "Theme"), K.Cmd("skin", "Skin"), K.Cmd("logo", "Logo"), K.Cmd("newtab", "+Tab"), K.Cmd("closetab", "×Tab"))
        fn -> listOf(Spec.F1, Spec.F2, Spec.F3, Spec.F4, Spec.F5, Spec.F6, Spec.F7, Spec.F8, Spec.F9, Spec.F10, Spec.F11, Spec.F12)
            .map { K.Sp(it, it.name, w = 10f / 12f) }
        else -> listOf(
            K.Sp(Spec.Esc, "Esc"), K.Sp(Spec.Tab, "Tab"), K.M(Mod.Ctrl, "Ctrl"), K.M(Mod.Fn, "Fn"), K.Ch("|", "\\"), K.Ch("/", "~"),
            K.Sp(Spec.Left, "←", repeat = true), K.Sp(Spec.Up, "↑", repeat = true), K.Sp(Spec.Down, "↓", repeat = true), K.Sp(Spec.Right, "→", repeat = true),
        )
    }

    private val bottom = listOf(
        K.M(Mod.Shift, "⇧"), K.M(Mod.Num, "123"), K.M(Mod.Sym, "sym"), K.Ch(" ", w = 4f), K.Ch(".", ","), K.Ch(";", ":"), K.M(Mod.Shift, "⇧"),
    )

    /** Samsung-style number row over the letters; hold a digit for its shifted symbol. */
    private val digits = "1234567890".zip("!@#$%^&*()").map { (d, a) -> K.Ch(d.toString(), a.toString()) }

    /** Rows drawn short: the function row and the number row. */
    fun short(i: Int) = i == 0 || (layer != "num" && i == 1)

    fun rows(): List<List<K>> = when (layer) {
        "num" -> listOf(
            nums("789/") + listOf(K.Sp(Spec.Home, "Home", kind = Kind.Nav), K.Sp(Spec.Up, "↑", repeat = true, kind = Kind.Nav), K.Sp(Spec.PgUp, "PgU", repeat = true, kind = Kind.Nav)) + syms("()") + back,
            nums("456*") + listOf(K.Sp(Spec.Left, "←", repeat = true, kind = Kind.Nav), K.Sp(Spec.Down, "↓", repeat = true, kind = Kind.Nav), K.Sp(Spec.Right, "→", repeat = true, kind = Kind.Nav)) + syms("[]") + K.Sp(Spec.Tab, "Tab"),
            nums("123-") + listOf(K.Sp(Spec.End, "End", kind = Kind.Nav), K.Sp(Spec.Del, "Del", repeat = true, kind = Kind.Nav), K.Sp(Spec.PgDn, "PgD", repeat = true, kind = Kind.Nav)) + syms("{}") + enter,
            listOf(K.Ch("0", w = 2f, kind = Kind.Num)) + nums(".+") + listOf(K.M(Mod.Abc, "abc"), K.Ch(" ", w = 3f)) + nums("=") + syms("$"),
        )
        "sym" -> listOf(
            digits,
            syms("`~|\\{}[]<>"),
            syms("=&%^#@*()") + back,
            listOf(K.M(Mod.Alt, "alt")) + syms("!?;:'\",+") + enter,
            bottom,
        )
        else -> listOf(
            digits,
            letters("qwertyuiop"),
            letters("asdfghjkl") + back,
            listOf(K.M(Mod.Alt, "alt")) + letters("zxcvbnm") + K.Ch("-", "=") + enter,
            bottom,
        )
    }
}

/**
 * The built-in keyboard: a control strip (menu, paste, hosts, trackpad, setup, ^C), a function/command row
 * a number row and four rows of keys. It is always on screen and the system keyboard is never used.
 */
@Composable
fun DeckKeyboard(
    router: InputRouter,
    skin: Skin,
    keyHeight: Dp,
    haptics: Boolean,
    onCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val st = remember { Layers() }
    val view = LocalView.current
    val buzz = { if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    val bb = skin.cap == CapStyle.BlackBerry

    fun fire(k: K, long: Boolean) {
        when (k) {
            is K.Ch -> {
                val useAlt = (st.alt > 0) xor long
                var s = if (useAlt && k.alt != null) k.alt else k.c
                if (st.shift > 0 && !useAlt) s = s.uppercase()
                router.text(s, st.ctrl > 0)
                st.consume()
            }
            is K.Sp -> { router.special(k.s, st.shift > 0, st.ctrl > 0); st.consume() }
            is K.Raw -> { router.raw(k.bytes); st.consume() }
            is K.M -> st.tap(k.m)
            is K.Cmd -> { st.menu = false; onCommand(k.cmd) }
        }
    }

    fun display(k: K.Ch): String = when {
        k.c == " " -> ""
        st.alt > 0 && k.alt != null -> k.alt
        st.shift > 0 -> k.c.uppercase()
        else -> k.c
    }

    Column(modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(if (bb) 0.dp else 5.dp)) {
        ControlStrip(skin, st.menu, buzz, onMenu = { st.tap(Mod.Menu) }, onCommand = onCommand, onArrow = { router.special(it, false, false) }, onRaw = { router.raw(it) })
        val all = listOf(st.topRow()) + st.rows()
        all.forEachIndexed { i, row ->
            if (bb) Box(Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp).height(3.dp).background(Fret, RoundedCornerShape(2.dp)))
            // The numpad has no number row, so its four rows share that row's height and the deck never jumps.
            val h = when {
                st.short(i) -> keyHeight * 0.74f
                st.layer == "num" -> keyHeight * 1.185f
                else -> keyHeight
            }
            Row(Modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(if (bb) 3.dp else 5.dp)) {
                row.forEach { k ->
                    val level = (k as? K.M)?.let { st.level(it.m) } ?: 0
                    val label = when (k) {
                        is K.Ch -> display(k)
                        is K.Sp -> k.label
                        is K.M -> if (k.m == Mod.Shift && st.shift == 2) "⇪" else k.label
                        is K.Raw -> k.label
                        is K.Cmd -> k.label
                    }
                    val sup = (k as? K.Ch)?.alt?.let { if (st.alt > 0) k.c else it }?.takeIf { i > 0 }
                    KeyCap(
                        k = k, label = label, sup = sup, level = level, small = i == 0 || label.length > 1,
                        bubble = if (k is K.Ch) (if (k.c == " ") "␣" else label) else null,
                        skin = skin, buzz = buzz, onFire = { long -> fire(k, long) },
                    )
                }
            }
        }
    }
}

private val Fret = Brush.verticalGradient(listOf(Color(0xFFF4F4F4), Color(0xFF8A8D91), Color(0xFFD8DADD)))

@Composable
private fun ControlStrip(
    skin: Skin, menuOn: Boolean, buzz: () -> Unit,
    onMenu: () -> Unit, onCommand: (String) -> Unit, onArrow: (Spec) -> Unit, onRaw: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(36.dp).padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        StripButton(skin, "≡", menuOn, buzz, onClick = onMenu)
        StripButton(skin, "Paste", false, buzz) { onCommand("paste") }
        StripButton(skin, "◂Hosts", false, buzz) { onCommand("hosts") }
        Trackpad(skin, onArrow)
        StripButton(skin, "Setup▸", false, buzz) { onCommand("setup") }
        StripButton(skin, "^C", false, buzz, danger = true) { onRaw("\u0003") }
    }
}

@Composable
private fun RowScope.StripButton(skin: Skin, label: String, on: Boolean, buzz: () -> Unit, danger: Boolean = false, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(70), label = "s")
    val tap by rememberUpdatedState(onClick)
    Box(
        Modifier
            .weight(1f)
            .fillMaxSize()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(if (on) skin.alt else skin.ctl, RoundedCornerShape(50))
            .then(
                when (skin.cap) {
                    CapStyle.Bevel -> Modifier.bevel(pressed)
                    CapStyle.Neon -> Modifier.border(1.dp, if (on) skin.glow else Color(0xFF0D4A1B), RoundedCornerShape(50))
                    CapStyle.Mech -> Modifier.border(1.dp, Color(0xFF3D4232), RoundedCornerShape(50))
                    else -> Modifier
                }
            )
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false); pressed = true; buzz()
                    val up = waitForUpOrCancellation(); pressed = false
                    if (up != null) tap()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.Black else if (danger) (if (skin.dark) Color(0xFFFF7B7B) else Color(0xFFB00020)) else skin.ctlFg, fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** BlackBerry-style trackpad (a trackball on uConsole): swipe to send arrow keys. */
@Composable
private fun Trackpad(skin: Skin, onArrow: (Spec) -> Unit) {
    val arrow by rememberUpdatedState(onArrow)
    val step = 22f
    val m = Modifier.pointerInput(Unit) {
        var ax = 0f; var ay = 0f
        detectDragGestures(onDragStart = { ax = 0f; ay = 0f }) { ch, d ->
            ch.consume(); ax += d.x; ay += d.y
            while (abs(ax) > step) { arrow(if (ax > 0) Spec.Right else Spec.Left); ax -= step * kotlin.math.sign(ax) }
            while (abs(ay) > step) { arrow(if (ay > 0) Spec.Down else Spec.Up); ay -= step * kotlin.math.sign(ay) }
        }
    }.semantics { contentDescription = "Trackpad: swipe for arrow keys" }
    if (skin.trackball) {
        Box(m.size(32.dp).background(Brush.radialGradient(listOf(Color(0xFF7A8086), Color(0xFF1D1F22))), CircleShape))
    } else {
        val outline = if (skin.cap == CapStyle.BlackBerry) Color(0xFF8A8D91) else skin.glow
        Box(
            m.width(58.dp).height(30.dp)
                .background(if (skin.cap == CapStyle.BlackBerry) Brush.verticalGradient(listOf(Color(0xFF3A3B3F), Color(0xFF151517))) else skin.key, RoundedCornerShape(9.dp))
                .border(2.dp, outline, RoundedCornerShape(9.dp)),
        )
    }
}

private fun Modifier.bevel(inverted: Boolean): Modifier = drawBehind {
    val hi = Color.White; val lo = Color(0xFF8A8473); val w = 2.dp.toPx()
    val (a, b) = if (inverted) lo to hi else hi to lo
    drawRect(a, Offset.Zero, Size(size.width, w)); drawRect(a, Offset.Zero, Size(w, size.height))
    drawRect(b, Offset(0f, size.height - w), Size(size.width, w)); drawRect(b, Offset(size.width - w, 0f), Size(w, size.height))
}

@Composable
private fun RowScope.KeyCap(
    k: K, label: String, sup: String?, level: Int, small: Boolean, bubble: String?,
    skin: Skin, buzz: () -> Unit, onFire: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pressed by remember { mutableStateOf(false) }
    val fire by rememberUpdatedState(onFire)
    val bubbleText by rememberUpdatedState(bubble)
    val ring = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    var shownBubble by remember { mutableStateOf("") }
    val press by animateFloatAsState(if (pressed) 1f else 0f, tween(if (pressed) 40 else 110), label = "press")
    val measurer = rememberTextMeasurer()
    val repeat = (k as? K.Sp)?.repeat == true || (k is K.Ch && k.c == " ")
    val hasLong = k is K.Ch && k.alt != null

    val face: Brush = when {
        level == 2 && skin.cap != CapStyle.Neon -> SolidColor(lerp(skin.ctl, skin.alt, 0.35f))
        k.kind == Kind.Num -> SolidColor(skin.numKey)
        else -> skin.key
    }
    val ink = when {
        level > 0 -> skin.alt
        k.kind == Kind.Cmd -> skin.alt
        k.kind == Kind.Nav -> skin.glow
        else -> skin.keyFg
    }
    val shape = if (skin.cap == CapStyle.BlackBerry) RoundedCornerShape(4.dp, 4.dp, 11.dp, 11.dp) else RoundedCornerShape(skin.keyRadius)

    Box(
        Modifier
            .weight(k.w)
            .fillMaxSize()
            .zIndex(if (pressed || pop.value < 1f) 1f else 0f)
            .semantics { contentDescription = if (k is K.Ch && k.c == " ") "space" else label }
            .drawWithContent {
                drawContent()
                // Glow ring that spreads out from the key on every press.
                if (ring.value < 1f) {
                    val g = ring.value * 8.dp.toPx()
                    drawRoundRect(
                        skin.glow.copy(alpha = (1f - ring.value) * 0.9f), Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g),
                        CornerRadius(skin.keyRadius.toPx() + g), style = Stroke(2.dp.toPx()),
                    )
                }
                // Character bubble that pops up above the finger.
                if (pop.value < 1f && shownBubble.isNotEmpty()) {
                    val p = pop.value
                    val a = if (p < 0.25f) p / 0.25f else 1f - (p - 0.25f) / 0.75f
                    val bw = maxOf(size.width * 1.3f, 34.dp.toPx()); val bh = 42.dp.toPx()
                    val x = (size.width - bw) / 2; val y = -bh - 6.dp.toPx() - p * 10.dp.toPx()
                    drawRoundRect(lerp(skin.drawerBg, Color.Black, 0.2f).copy(alpha = a), Offset(x, y), Size(bw, bh), CornerRadius(8.dp.toPx()))
                    drawRoundRect(skin.glow.copy(alpha = a), Offset(x, y), Size(bw, bh), CornerRadius(8.dp.toPx()), style = Stroke(1.dp.toPx()))
                    val tl = measurer.measure(shownBubble, TextStyle(fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = skin.alt.copy(alpha = a)))
                    drawText(tl, topLeft = Offset(x + (bw - tl.size.width) / 2, y + (bh - tl.size.height) / 2))
                }
            }
            .pointerInput(k) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    buzz()
                    scope.launch { ring.snapTo(0f); ring.animateTo(1f, tween(320, easing = FastOutSlowInEasing)) }
                    bubbleText?.takeIf { it.isNotEmpty() }?.let { b ->
                        shownBubble = b
                        scope.launch { pop.snapTo(0f); pop.animateTo(1f, tween(380)) }
                    }
                    var handled = false
                    var job: Job? = null
                    if (k is K.M || k is K.Cmd) { fire(false); handled = true }
                    else if (repeat) {
                        fire(false); handled = true
                        job = scope.launch { delay(380); while (true) { fire(false); delay(45) } }
                    } else if (hasLong) {
                        job = scope.launch {
                            delay(330); handled = true; buzz()
                            shownBubble = (k as K.Ch).alt ?: ""
                            pop.snapTo(0f); launch { pop.animateTo(1f, tween(380)) }
                            fire(true)
                        }
                    }
                    val up = waitForUpOrCancellation()
                    job?.cancel()
                    pressed = false
                    if (up != null && !handled) fire(false)
                }
            }
            .graphicsLayer {
                translationY = press * 2.dp.toPx()
                scaleX = 1f - press * 0.06f; scaleY = 1f - press * 0.06f
            },
    ) {
        when (skin.cap) {
            CapStyle.Mech -> {
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0C09), shape).border(1.dp, if (level > 0) skin.glow else Color(0xFF3D4232), shape))
                Box(Modifier.fillMaxSize().padding(start = 1.dp, end = 1.dp, top = 1.dp, bottom = (5 - 3 * press).dp).background(face, shape)) { Legend(label, sup, ink, small, skin) }
            }
            CapStyle.Bevel -> Box(Modifier.fillMaxSize().background(face, shape).bevel(pressed || level == 2)) { Legend(label, sup, ink, small, skin) }
            CapStyle.Neon -> Box(
                Modifier.fillMaxSize().background(face, shape)
                    .border(1.dp, if (level > 0 || pressed) skin.glow else Color(0xFF0D4A1B), shape)
                    .drawBehind { if (level > 0 || press > 0f) drawRoundRect(skin.glow.copy(alpha = 0.18f + press * 0.2f), cornerRadius = CornerRadius(skin.keyRadius.toPx())) },
            ) { Legend(label, sup, ink, small, skin) }
            CapStyle.BlackBerry -> Box(
                Modifier.fillMaxSize().background(face, shape)
                    .drawBehind { drawLine(Color.White.copy(alpha = 0.16f), Offset(6f, 1f), Offset(size.width - 6f, 1f), 1.dp.toPx()) }
                    .then(if (level > 0) Modifier.border(1.dp, skin.alt, shape) else Modifier),
            ) { Legend(label, sup, ink, small, skin) }
            CapStyle.Flat -> Box(
                Modifier.fillMaxSize().background(face, shape).then(if (level > 0) Modifier.border(1.dp, skin.alt, shape) else Modifier),
            ) { Legend(label, sup, ink, small, skin) }
        }
        if (press > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = press * 0.14f), shape))
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.Legend(label: String, sup: String?, ink: Color, small: Boolean, skin: Skin) {
    if (label.isEmpty()) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.6f).height(2.dp).background(skin.keyFg.copy(alpha = 0.35f)))
        return
    }
    // The return arrow is tiny in JetBrains Mono, so it uses the system font at a larger size.
    val ret = label == "↵"
    Text(
        label, Modifier.align(Alignment.Center), color = ink, fontFamily = if (ret) androidx.compose.ui.text.font.FontFamily.Default else Mono,
        maxLines = 1, softWrap = false, fontSize = if (ret) 20.sp else if (small) 10.5.sp else 15.sp, fontWeight = FontWeight.SemiBold,
    )
    if (sup != null) {
        Text(sup, Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 3.dp), color = skin.alt, fontFamily = Mono, fontSize = 9.5.sp, maxLines = 1)
    }
}
