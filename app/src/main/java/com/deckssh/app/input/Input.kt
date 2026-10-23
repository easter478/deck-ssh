package com.deckssh.app.input

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.deckssh.app.ssh.SshTab
import com.termux.terminal.KeyHandler

enum class Spec(val keyCode: Int) {
    Enter(KeyEvent.KEYCODE_ENTER), Back(KeyEvent.KEYCODE_DEL), Tab(KeyEvent.KEYCODE_TAB), Esc(KeyEvent.KEYCODE_ESCAPE),
    Up(KeyEvent.KEYCODE_DPAD_UP), Down(KeyEvent.KEYCODE_DPAD_DOWN), Left(KeyEvent.KEYCODE_DPAD_LEFT), Right(KeyEvent.KEYCODE_DPAD_RIGHT),
    Home(KeyEvent.KEYCODE_MOVE_HOME), End(KeyEvent.KEYCODE_MOVE_END), PgUp(KeyEvent.KEYCODE_PAGE_UP), PgDn(KeyEvent.KEYCODE_PAGE_DOWN),
    Ins(KeyEvent.KEYCODE_INSERT), Del(KeyEvent.KEYCODE_FORWARD_DEL),
    F1(KeyEvent.KEYCODE_F1), F2(KeyEvent.KEYCODE_F2), F3(KeyEvent.KEYCODE_F3), F4(KeyEvent.KEYCODE_F4),
    F5(KeyEvent.KEYCODE_F5), F6(KeyEvent.KEYCODE_F6), F7(KeyEvent.KEYCODE_F7), F8(KeyEvent.KEYCODE_F8),
    F9(KeyEvent.KEYCODE_F9), F10(KeyEvent.KEYCODE_F10), F11(KeyEvent.KEYCODE_F11), F12(KeyEvent.KEYCODE_F12),
}

/** A text field in the app that the built-in keyboard is typing into. */
class FieldTarget(
    val id: String,
    val get: () -> String,
    val set: (String) -> Unit,
    val onEnter: () -> Unit = {},
    val onEsc: () -> Unit = {},
)

/** Sends keyboard output either to the focused app field or to the active SSH tab. The system IME is never used. */
class InputRouter {
    var field by mutableStateOf<FieldTarget?>(null)
    var tab: SshTab? = null
    /** Takes the keys when no field is focused and no SSH tab is in front: the local console. */
    var idle: FieldTarget? = null

    fun focus(t: FieldTarget?) { field = t }

    private fun target() = field ?: if (tab == null) idle else null

    fun text(s: String, ctrl: Boolean) {
        target()?.let { it.set(it.get() + s); return }
        val t = tab ?: return
        if (ctrl && s.length == 1) {
            val c = s[0].lowercaseChar()
            val code = when (c) {
                in 'a'..'z' -> c - 'a' + 1
                ' ', '@', '2' -> 0
                '[', '3' -> 27
                '\\', '4' -> 28
                ']', '5' -> 29
                '^', '6' -> 30
                '_', '-', '7' -> 31
                '?', '8' -> 127
                else -> -1
            }
            if (code >= 0) { t.send(byteArrayOf(code.toByte())); return }
        }
        t.send(s)
    }

    fun special(k: Spec, shift: Boolean, ctrl: Boolean) {
        target()?.let { f ->
            when (k) {
                Spec.Back -> f.set(f.get().dropLast(1))
                Spec.Enter, Spec.Tab -> f.onEnter()
                Spec.Esc -> f.onEsc()
                else -> {}
            }
            return
        }
        val t = tab ?: return
        when (k) {
            Spec.Enter -> t.send("\r")
            Spec.Back -> t.send(byteArrayOf(if (ctrl) 0x08 else 0x7f))
            Spec.Esc -> t.send(byteArrayOf(0x1b))
            Spec.Tab -> if (!shift) t.send("\t") else t.send("\u001b[Z")
            else -> {
                var mod = 0
                if (shift) mod = mod or KeyHandler.KEYMOD_SHIFT
                if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
                val e = t.emulator
                KeyHandler.getCode(k.keyCode, mod, e.isCursorKeysApplicationMode, e.isKeypadApplicationMode)?.let { t.send(it) }
            }
        }
    }

    fun raw(s: String) {
        target()?.let { if (s == "\u0003") it.onEsc(); return }
        tab?.send(s)
    }

    fun paste(s: String) {
        target()?.let { it.set(it.get() + s.trim()); return }
        tab?.emulator?.paste(s)
    }
}
