package com.deckssh.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.deckssh.app.data.Store
import com.deckssh.app.input.Spec
import com.deckssh.app.ui.App
import com.deckssh.app.ui.Sessions
import java.security.Security

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Full Bouncy Castle so JSch gets ed25519 and the modern ciphers Android's stripped provider lacks.
        Security.removeProvider("BC")
        Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        // The whole screen is the device, so hide the phone's own bars; a swipe from the edge brings them back.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContent {
            val store = remember { Store(applicationContext) }
            LaunchedEffect(store.keepScreenOn) {
                if (store.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            App(store)
        }
    }

    private val specials = mapOf(
        KeyEvent.KEYCODE_ENTER to Spec.Enter, KeyEvent.KEYCODE_DEL to Spec.Back, KeyEvent.KEYCODE_TAB to Spec.Tab,
        KeyEvent.KEYCODE_ESCAPE to Spec.Esc, KeyEvent.KEYCODE_DPAD_UP to Spec.Up, KeyEvent.KEYCODE_DPAD_DOWN to Spec.Down,
        KeyEvent.KEYCODE_DPAD_LEFT to Spec.Left, KeyEvent.KEYCODE_DPAD_RIGHT to Spec.Right, KeyEvent.KEYCODE_MOVE_HOME to Spec.Home,
        KeyEvent.KEYCODE_MOVE_END to Spec.End, KeyEvent.KEYCODE_PAGE_UP to Spec.PgUp, KeyEvent.KEYCODE_PAGE_DOWN to Spec.PgDn,
        KeyEvent.KEYCODE_FORWARD_DEL to Spec.Del, KeyEvent.KEYCODE_INSERT to Spec.Ins,
    )

    /** A paired Bluetooth or USB keyboard types straight into the terminal too. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dev = event.device
        if (dev == null || dev.isVirtual || event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val r = Sessions.router
        specials[event.keyCode]?.let { r.special(it, event.isShiftPressed, event.isCtrlPressed); return true }
        val c = event.getUnicodeChar(event.metaState and KeyEvent.META_CTRL_MASK.inv())
        if (c != 0) { r.text(String(Character.toChars(c)), event.isCtrlPressed); return true }
        return super.dispatchKeyEvent(event)
    }
}
