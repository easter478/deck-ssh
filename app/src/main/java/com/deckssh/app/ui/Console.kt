package com.deckssh.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deckssh.app.data.Host
import com.deckssh.app.data.Store
import com.deckssh.app.input.FieldTarget

/** What the console can reach outside itself; set by the device screen on every composition. */
class ConsoleEnv(
    val store: Store,
    val connect: (Host) -> Unit,
    val pickKeyFile: () -> Unit,
    val clipboard: () -> String,
)

enum class LineKind { Out, Head, Dim, Err, Ok, Echo }

class Line(val text: String, val kind: LineKind)

/**
 * The local shell shown when no SSH session is open. Everything the app can set up is a typed
 * command with a numbered menu: /theme, /host, /setup… There are no drawers or settings screens.
 */
class Console {
    val lines = mutableStateListOf<Line>()
    var input by mutableStateOf("")
    var prompt by mutableStateOf(HOME)
        private set
    var secret by mutableStateOf(false)
        private set
    lateinit var env: ConsoleEnv
    private val store get() = env.store
    private var step: ((String) -> Unit)? = null
    private var pendingKey: ((String) -> Unit)? = null

    /** The deck keyboard types into this whenever no SSH tab is in front. */
    val target = FieldTarget("console", { input }, { input = it.replace("\n", " ") }, { submit() }, { cancel() })

    val busy get() = step != null

    fun boot() {
        if (lines.isNotEmpty()) return
        head("DECK SSH 2.0 · ${Skins.byId(store.skinId).name}")
        dim("no session. type a command + ↵")
        commandList()
    }

    private fun out(s: String, k: LineKind = LineKind.Out) {
        lines.add(Line(s, k))
        if (lines.size > 500) lines.removeRange(0, lines.size - 500)
    }
    private fun head(s: String) = out(s, LineKind.Head)
    private fun dim(s: String) = out(s, LineKind.Dim)
    private fun err(s: String) = out(s, LineKind.Err)
    private fun ok(s: String) = out(s, LineKind.Ok)

    private fun ask(p: String, hidden: Boolean = false, handler: (String) -> Unit) {
        prompt = p; secret = hidden; step = handler
    }

    private fun home() { prompt = HOME; secret = false; step = null }

    fun cancel() {
        if (step == null && input.isEmpty()) return
        out(prompt + input + "^C", LineKind.Echo)
        input = ""
        home()
    }

    fun submit() {
        val raw = input
        input = ""
        val hidden = secret
        out(prompt + if (hidden) "•".repeat(raw.length) else raw, LineKind.Echo)
        val h = step
        home()
        if (h != null) h(if (hidden) raw else raw.trim()) else run(raw.trim())
    }

    /** Runs a command as if it was typed, dropping any half-answered question. */
    fun exec(cmd: String) {
        if (step != null) { out("$prompt^C", LineKind.Echo); home() }
        out(HOME + cmd, LineKind.Echo)
        input = ""
        run(cmd)
    }

    /** Shows a numbered list; the user types a number. Blank or q backs out. */
    private fun choose(title: String, options: List<String>, preset: String? = null, onPick: (Int) -> Unit) {
        fun pick(a: String) {
            val n = a.toIntOrNull()
            when {
                a.isEmpty() || a.equals("q", true) -> dim("cancelled")
                n == null || n !in 1..options.size -> { err("type a number from 1 to ${options.size}"); ask("select [1-${options.size}]: ", handler = ::pick) }
                else -> onPick(n)
            }
        }
        if (preset != null) { pick(preset); return }
        head(title)
        val w = options.size.toString().length
        options.forEachIndexed { i, o -> out("  ${(i + 1).toString().padStart(w)}  $o") }
        ask("select [1-${options.size}]: ", handler = ::pick)
    }

    private fun yes(a: String) = a.isEmpty() || a.startsWith("y", true)

    private fun run(line: String) {
        if (line.isEmpty()) return
        val parts = line.split(Regex("\\s+"), limit = 2)
        val cmd = parts[0].lowercase().removePrefix("/")
        val arg = parts.getOrNull(1)?.trim()?.ifEmpty { null }
        when (cmd) {
            "", "help", "?" -> commandList()
            "theme", "skin", "device" -> themeMenu(arg)
            "host", "hosts", "connect" -> hostMenu(arg)
            "ssh" -> if (arg == null) hostMenu(null) else quickSsh(arg)
            "setup", "settings" -> setupMenu(arg)
            "logo" -> logoMenu(arg)
            "brand", "name" -> brandName(arg)
            "colors", "colours", "screen" -> colourMenu(arg)
            "glint" -> toggle("logo glint", store.glint, store::changeGlint)
            "rain" -> toggle("code rain", store.rain, store::changeRain)
            "haptics" -> toggle("haptics", store.haptics, store::changeHaptics)
            "awake" -> toggle("keep screen on", store.keepScreenOn, store::changeKeepScreenOn)
            "keys" -> number("key size", 36, 60, store.keyHeight, arg) { store.changeKeyHeight(it) }
            "font", "text" -> number("text size", 8, 24, store.fontSize.toInt(), arg) { store.changeFontSize(it.toFloat()) }
            "clear", "cls" -> lines.clear()
            else -> err("$cmd: unknown command. type /help")
        }
    }

    private fun commandList() {
        head("commands")
        listOf(
            "/theme" to "device skin",
            "/host" to "hosts and keys",
            "ssh u@host" to "quick connect",
            "/setup" to "all settings",
            "/logo" to "logo glyph",
            "/brand" to "brand name",
            "/colors" to "screen colours",
            "/clear" to "clear screen",
        ).forEach { (c, d) -> out("  ${c.padEnd(12)}$d") }
        dim("menus: type a number · Esc = back")
    }

    // ---- device ---------------------------------------------------------------------------

    private fun themeMenu(arg: String?) {
        val list = Skins.all
        choose("device theme", list.map { if (it.id == store.skinId) "${it.name}  ◂ now" else it.name }, arg) { n ->
            val s = list[n - 1]
            store.changeSkin(s.id)
            ok("device is now ${s.name}")
        }
    }

    private fun logoMenu(arg: String?) {
        val skin = Skins.byId(store.skinId)
        val now = store.logos[skin.id] ?: skin.logo
        val names = mapOf("bb" to "BlackBerry pills", "term" to "terminal >_", "skull" to "skull", "hex" to "hexagon", "gear" to "gear", "flag" to "Windows flag", "none" to "no logo")
        choose("logo glyph · ${skin.name}", Skins.glyphs.map { (names[it] ?: it) + if (it == now) "  ◂ now" else "" }, arg) { n ->
            store.changeLogo(skin.id, Skins.glyphs[n - 1])
            ok("logo set")
        }
    }

    private fun brandName(arg: String?) {
        val skin = Skins.byId(store.skinId)
        val now = store.brandTexts[skin.id] ?: skin.brandText
        fun set(a: String) {
            if (a.isEmpty()) { dim("kept \"$now\""); return }
            val v = if (a == "-") skin.brandText else a.take(18)
            store.changeBrandText(skin.id, v)
            ok("brand name is now \"$v\"")
        }
        if (arg != null) set(arg) else ask("brand name [$now] (- resets): ", handler = ::set)
    }

    private fun colourMenu(arg: String?) {
        val all = ThemePack.all
        val opts = listOf("match the device skin" + if (store.themeId == "skin") "  ◂ now" else "") +
            all.map { "${it.name}  · ${it.desc}" + if (it.id == store.themeId) "  ◂ now" else "" }
        choose("screen colours", opts, arg) { n ->
            if (n == 1) { store.setTheme("skin"); ok("screen follows the device skin") }
            else { val p = all[n - 2]; store.setTheme(p.id); ok("screen colours: ${p.name}") }
        }
    }

    private fun toggle(name: String, now: Boolean, set: (Boolean) -> Unit) {
        set(!now)
        ok("$name ${if (!now) "on" else "off"}")
    }

    private fun number(name: String, min: Int, max: Int, now: Int, arg: String?, set: (Int) -> Unit) {
        fun apply(a: String) {
            if (a.isEmpty()) { dim("kept $now"); return }
            val v = a.toIntOrNull()
            if (v == null || v !in min..max) { err("$name is $min to $max"); ask("$name $min-$max [$now]: ", handler = ::apply); return }
            set(v); ok("$name $v")
        }
        if (arg != null) apply(arg) else ask("$name $min-$max [$now]: ", handler = ::apply)
    }

    private fun setupMenu(arg: String?) {
        val skin = Skins.byId(store.skinId)
        fun on(b: Boolean) = if (b) "on" else "off"
        val opts = listOf(
            "theme" to skin.name,
            "logo glyph" to "",
            "brand name" to (store.brandTexts[skin.id] ?: skin.brandText),
            "logo glint" to on(store.glint),
            "code rain" to if (skin.rain) "always" else on(store.rain),
            "colours" to if (store.themeId == "skin") "skin" else ThemePack.byId(store.themeId).name,
            "key size" to "${store.keyHeight}",
            "text size" to "${store.fontSize.toInt()}",
            "haptics" to on(store.haptics),
            "stay awake" to on(store.keepScreenOn),
        ).map { (k, v) -> k.padEnd(12) + v }
        choose("setup", opts, arg) { n ->
            when (n) {
                1 -> themeMenu(null)
                2 -> logoMenu(null)
                3 -> brandName(null)
                4 -> run("/glint")
                5 -> run("/rain")
                6 -> colourMenu(null)
                7 -> run("/keys")
                8 -> run("/font")
                9 -> run("/haptics")
                10 -> run("/awake")
            }
        }
    }

    // ---- hosts ----------------------------------------------------------------------------

    private fun hostMenu(arg: String?) {
        choose(
            "hosts",
            listOf("saved hosts (${store.hosts.size})", "new connection", "import key file", "paste key"),
            arg,
        ) { n ->
            when (n) {
                1 -> savedHosts()
                2 -> wizard(Host(), editing = false)
                3 -> { pendingKey = ::keyLoaded; dim("pick the key file…"); env.pickKeyFile() }
                4 -> {
                    val t = env.clipboard()
                    if (t.contains("PRIVATE KEY")) keyLoaded(t.trim()) else err("the clipboard doesn't hold a private key")
                }
            }
        }
    }

    /** Called with the file picker's result. */
    fun keyFile(text: String?) {
        val k = pendingKey ?: return
        pendingKey = null
        if (text == null) dim("no file picked")
        else if (!text.contains("PRIVATE KEY")) err("that file isn't a private key")
        else k(text.trim())
    }

    private fun keyKind(key: String) =
        key.lineSequence().firstOrNull { it.startsWith("-----BEGIN") }?.removePrefix("-----BEGIN ")?.removeSuffix("-----")?.lowercase() ?: "key"

    private fun keyLoaded(key: String) {
        ok("loaded ${keyKind(key)}")
        val saved = store.hosts.toList()
        choose("use this key for", listOf("a new connection") + saved.map { "${it.label}  ${it.user}@${it.host}" }) { n ->
            if (n == 1) wizard(Host(privateKey = key), editing = false)
            else {
                val h = saved[n - 2]
                ask("key passphrase (blank for none): ", hidden = true) { pp ->
                    store.save(h.copy(privateKey = key, passphrase = pp))
                    ok("key added to ${h.label}")
                }
            }
        }
    }

    private fun savedHosts() {
        val saved = store.hosts.toList()
        if (saved.isEmpty()) {
            dim("no saved hosts yet")
            ask("make a new connection? [Y/n]: ") { if (yes(it)) wizard(Host(), editing = false) }
            return
        }
        choose("saved hosts", saved.map { "${it.label.padEnd(12)} ${it.user}@${it.host}:${it.port} · ${it.authLabel}" }) { n ->
            val h = saved[n - 1]
            choose(h.label, listOf("connect", "edit", "delete")) { a ->
                when (a) {
                    1 -> env.connect(h)
                    2 -> wizard(h, editing = true)
                    3 -> ask("delete ${h.label}? [y/N]: ") {
                        if (it.startsWith("y", true)) { store.delete(h.id); ok("deleted ${h.label}") } else dim("kept")
                    }
                }
            }
        }
    }

    /** "ssh user@host -p 2222" or "ssh user@host:2222". */
    private fun quickSsh(arg: String) {
        val m = Regex("""^(?:(\S+)@)?([^\s:]+)(?::(\d+))?(?:\s+-p\s*(\d+))?$""").find(arg.trim())
        if (m == null) { err("usage: ssh user@host [-p port]"); return }
        val (user, host, p1, p2) = m.destructured
        val port = (p2.ifEmpty { p1 }).toIntOrNull() ?: 22
        val known = store.hosts.firstOrNull { it.host == host && it.port == port && (user.isEmpty() || it.user == user) && (it.password.isNotEmpty() || it.privateKey.isNotEmpty()) }
        if (known != null) { env.connect(known); return }
        wizard(Host(host = host, user = user, port = port), editing = false, quick = true)
    }

    /** Asks for each field in turn; blank keeps the value in brackets. */
    private fun wizard(start: Host, editing: Boolean, quick: Boolean = false) {
        var h = start
        head(if (editing) "edit ${h.label}" else "new connection")
        if (!editing && !quick) dim("blank keeps [value] · Esc cancels")

        fun finish() {
            dim("${h.user}@${h.host}:${h.port} · ${h.authLabel}")
            val opts = if (editing) listOf("save", "save & connect", "discard changes")
            else listOf("save & connect", "connect only", "save only", "cancel")
            choose("ready", opts) { n ->
                val label = opts[n - 1]
                if (label.startsWith("save")) { store.save(h); ok("saved ${h.label}") }
                if (label.contains("connect")) env.connect(h)
                if (label == "cancel" || label == "discard changes") dim("nothing saved")
            }
        }
        fun secretStep() {
            if (h.privateKey.isNotBlank()) {
                ask("key passphrase (blank for none): ", hidden = true) { h = h.copy(passphrase = it); finish() }
            } else {
                val keep = if (editing && h.password.isNotEmpty()) " [keep]" else ""
                ask("password$keep: ", hidden = true) { pw ->
                    if (pw.isEmpty() && h.password.isEmpty()) { err("a password is needed (or use /host → paste key)"); secretStep(); return@ask }
                    if (pw.isNotEmpty()) h = h.copy(password = pw)
                    finish()
                }
            }
        }
        fun portStep() {
            if (quick) { secretStep(); return }
            ask("port [${h.port}]: ") { a ->
                if (a.isNotEmpty()) {
                    val p = a.toIntOrNull()
                    if (p == null || p !in 1..65535) { err("port is 1 to 65535"); portStep(); return@ask }
                    h = h.copy(port = p)
                }
                secretStep()
            }
        }
        fun userStep() {
            if (quick && h.user.isNotEmpty()) { portStep(); return }
            ask(if (h.user.isEmpty()) "username: " else "username [${h.user}]: ") { a ->
                if (a.isNotEmpty()) h = h.copy(user = a)
                if (h.user.isEmpty()) { err("a username is needed"); userStep() } else portStep()
            }
        }
        fun hostStep() {
            if (quick) { userStep(); return }
            ask(if (h.host.isEmpty()) "host or ip: " else "host [${h.host}]: ") { a ->
                if (a.isNotEmpty()) h = h.copy(host = a)
                if (h.host.isEmpty()) { err("a host is needed"); hostStep() } else userStep()
            }
        }
        if (quick) hostStep()
        else ask(if (h.name.isEmpty()) "name (optional): " else "name [${h.name}]: ") { a ->
            if (a.isNotEmpty()) h = h.copy(name = a)
            hostStep()
        }
    }

    companion object { const val HOME = "deck:~$ " }
}

/** Draws the console: scrollback, then the live prompt with a blinking block caret. */
@Composable
fun ConsoleScreen(console: Console, fg: Color, accent: Color, danger: Color, fontSp: Float, typing: Boolean, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val n = console.lines.size
    LaunchedEffect(n) { list.scrollToItem(n) }
    val blink by rememberInfiniteTransition(label = "caret").animateFloat(
        1f, 0f, infiniteRepeatable(tween(530), RepeatMode.Reverse), label = "caret",
    )
    val size = fontSp.sp
    LazyColumn(modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(10.dp)) {
        items(console.lines) { l ->
            val (c, w) = when (l.kind) {
                LineKind.Head -> accent to FontWeight.Bold
                LineKind.Dim -> fg.copy(alpha = 0.6f) to FontWeight.Normal
                LineKind.Err -> danger to FontWeight.Normal
                LineKind.Ok -> accent to FontWeight.Normal
                LineKind.Echo -> fg.copy(alpha = 0.85f) to FontWeight.Normal
                LineKind.Out -> fg to FontWeight.Normal
            }
            Text(l.text, color = c, fontWeight = w, fontFamily = Mono, fontSize = size, lineHeight = size * 1.3f)
        }
        item {
            val shown = if (console.secret) "•".repeat(console.input.length) else console.input
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(console.prompt) }
                    withStyle(SpanStyle(color = fg)) { append(shown) }
                    withStyle(SpanStyle(color = fg.copy(alpha = if (typing) blink else 0.35f))) { append("█") }
                },
                fontFamily = Mono, fontSize = size, lineHeight = size * 1.3f,
            )
        }
    }
}
