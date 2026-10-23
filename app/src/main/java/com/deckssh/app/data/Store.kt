package com.deckssh.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Host(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val user: String = "",
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
) {
    val label get() = name.ifBlank { host }
    val authLabel get() = if (privateKey.isNotBlank()) "key" else "password"
}

private fun String.toMap(): Map<String, String> = runCatching {
    val o = JSONObject(ifBlank { "{}" }); o.keys().asSequence().associateWith { o.getString(it) }
}.getOrDefault(emptyMap())

private fun Map<String, String>.encode() = JSONObject(this).toString()

/** Encrypts secrets with an AES key that never leaves the Android Keystore. */
private object Vault {
    private const val ALIAS = "deckssh-secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray())
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String {
        if (sealed.isEmpty()) return ""
        return runCatching {
            val raw = Base64.decode(sealed, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        }.getOrDefault("")
    }
}

class Store(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("deckssh", Context.MODE_PRIVATE)

    val hosts = mutableStateListOf<Host>().apply { addAll(loadHosts()) }

    /** Terminal colours: "skin" follows the device skin, anything else is a ThemePack id. */
    var themeId by mutableStateOf(prefs.getString("screenTheme", "skin")!!)
        private set
    var skinId by mutableStateOf(prefs.getString("skin", "bb")!!)
        private set
    var keyHeight by mutableStateOf(prefs.getInt("keySize", 44))
        private set
    var glint by mutableStateOf(prefs.getBoolean("glint", true))
        private set
    var rain by mutableStateOf(prefs.getBoolean("rain", false))
        private set
    /** Per-skin logo glyph and brand text; missing entries fall back to the skin's own. */
    var logos by mutableStateOf(prefs.getString("logos", "")!!.toMap())
        private set
    var brandTexts by mutableStateOf(prefs.getString("brands", "")!!.toMap())
        private set
    var fontSize by mutableStateOf(prefs.getFloat("fontSize", 13f))
        private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set
    /** Key click sound; off unless the user turns it on. */
    var click by mutableStateOf(prefs.getBoolean("click", false))
        private set
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keepOn", true))
        private set

    fun setTheme(id: String) { themeId = id; prefs.edit().putString("screenTheme", id).apply() }
    fun changeSkin(id: String) { skinId = id; prefs.edit().putString("skin", id).apply() }
    fun changeKeyHeight(h: Int) { keyHeight = h; prefs.edit().putInt("keySize", h).apply() }
    fun changeGlint(on: Boolean) { glint = on; prefs.edit().putBoolean("glint", on).apply() }
    fun changeRain(on: Boolean) { rain = on; prefs.edit().putBoolean("rain", on).apply() }
    fun changeLogo(skin: String, glyph: String) { logos = logos + (skin to glyph); prefs.edit().putString("logos", logos.encode()).apply() }
    fun changeBrandText(skin: String, text: String) { brandTexts = brandTexts + (skin to text); prefs.edit().putString("brands", brandTexts.encode()).apply() }
    fun changeFontSize(s: Float) { fontSize = s; prefs.edit().putFloat("fontSize", s).apply() }
    fun changeHaptics(on: Boolean) { haptics = on; prefs.edit().putBoolean("haptics", on).apply() }
    fun changeClick(on: Boolean) { click = on; prefs.edit().putBoolean("click", on).apply() }
    fun changeKeepScreenOn(on: Boolean) { keepScreenOn = on; prefs.edit().putBoolean("keepOn", on).apply() }

    fun save(h: Host) {
        val i = hosts.indexOfFirst { it.id == h.id }
        if (i >= 0) hosts[i] = h else hosts.add(h)
        persist()
    }

    fun delete(id: String) { hosts.removeAll { it.id == id }; persist() }

    private fun persist() {
        val arr = JSONArray()
        hosts.forEach { h ->
            arr.put(JSONObject().apply {
                put("id", h.id); put("name", h.name); put("host", h.host); put("port", h.port); put("user", h.user)
                put("pw", Vault.seal(h.password)); put("key", Vault.seal(h.privateKey)); put("pp", Vault.seal(h.passphrase))
            })
        }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }

    private fun loadHosts(): List<Host> = runCatching {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Host(
                id = o.getString("id"), name = o.optString("name"), host = o.optString("host"), port = o.optInt("port", 22),
                user = o.optString("user"), password = Vault.open(o.optString("pw")),
                privateKey = Vault.open(o.optString("key")), passphrase = Vault.open(o.optString("pp")),
            )
        }
    }.getOrDefault(emptyList())
}
