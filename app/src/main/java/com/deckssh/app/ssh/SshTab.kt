package com.deckssh.app.ssh

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deckssh.app.data.Host
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

enum class Status { Connecting, Connected, Failed, Closed }

/** One SSH shell: a JSch channel feeding a Termux terminal emulator. Emulator access stays on the main thread. */
class SshTab(val host: Host, private val knownHosts: File, cols: Int = 80, rows: Int = 24) {
    val id: String = UUID.randomUUID().toString()
    var status by mutableStateOf(Status.Connecting)
        private set
    var title by mutableStateOf(host.label)
        private set
    /** Bumped whenever the screen changes, so the canvas redraws. */
    var version by mutableIntStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var out: OutputStream? = null
    var onClipboardCopy: (String) -> Unit = {}

    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = send(data.copyOfRange(offset, offset + count))
        override fun titleChanged(oldTitle: String?, newTitle: String?) { if (!newTitle.isNullOrBlank()) title = newTitle }
        override fun onCopyTextToClipboard(text: String) = onClipboardCopy(text)
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() { version++ }
    }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) {}
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) {}
        override fun onCopyTextToClipboard(s: TerminalSession, text: String) {}
        override fun onPasteTextFromClipboard(s: TerminalSession?) {}
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }

    val emulator = TerminalEmulator(output, cols, rows, 3000, client)

    init { emulator.mColors.reset(); connect() }

    private fun local(text: String) = main.post {
        val b = text.replace("\n", "\r\n").toByteArray()
        emulator.append(b, b.size)
        version++
    }

    fun connect() {
        status = Status.Connecting
        local("\u001b[2mConnecting to ${host.user}@${host.host}:${host.port}…\u001b[0m\n")
        Thread {
            try {
                val jsch = JSch()
                runCatching { knownHosts.createNewFile(); jsch.setKnownHosts(knownHosts.absolutePath) }
                if (host.privateKey.isNotBlank()) {
                    jsch.addIdentity(host.label, host.privateKey.toByteArray(), null, host.passphrase.ifEmpty { null }?.toByteArray())
                }
                val s = jsch.getSession(host.user, host.host, host.port)
                if (host.password.isNotEmpty()) s.setPassword(host.password)
                s.setConfig("StrictHostKeyChecking", "no")
                s.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
                s.serverAliveInterval = 30_000
                s.connect(15_000)
                val ch = s.openChannel("shell") as ChannelShell
                ch.setPtyType("xterm-256color")
                ch.setPtySize(emulator.mColumns, emulator.mRows, 0, 0)
                ch.setEnv("COLORTERM", "truecolor")
                val input = ch.inputStream
                out = ch.outputStream
                ch.connect(10_000)
                session = s; channel = ch
                main.post { status = Status.Connected }
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    val chunk = buf.copyOf(n)
                    main.post { emulator.append(chunk, chunk.size); version++ }
                }
                main.post { status = Status.Closed }
                local("\n\u001b[2m[connection closed]\u001b[0m\n")
            } catch (e: Exception) {
                main.post { status = Status.Failed }
                local("\n\u001b[31m${e.message ?: e.javaClass.simpleName}\u001b[0m\n")
            } finally {
                runCatching { channel?.disconnect(); session?.disconnect() }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Rows scrolled back into the transcript; 0 shows the live screen. */
    var scroll by mutableIntStateOf(0)

    fun send(bytes: ByteArray) {
        if (status != Status.Connected) return
        scroll = 0
        writer.execute { runCatching { out?.write(bytes); out?.flush() } }
    }

    fun send(text: String) = send(text.toByteArray())

    fun resize(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        if (cols < 4 || rows < 2) return
        if (cols == emulator.mColumns && rows == emulator.mRows) return
        emulator.resize(cols, rows)
        version++
        val ch = channel
        writer.execute { runCatching { ch?.setPtySize(cols, rows, widthPx, heightPx) } }
    }

    fun redraw() { version++ }

    fun close() {
        writer.execute { runCatching { channel?.disconnect(); session?.disconnect() } }
        writer.shutdown()
    }
}
