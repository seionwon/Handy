package com.pais.handy.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64
import java.net.URI
import java.security.KeyStore
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun providerOrigin(address: String): String? = runCatching {
    val uri = URI(address.trim())
    val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
    if (scheme != "https" && !(scheme == "http" && host in setOf("localhost", "127.0.0.1", "[::1]"))) return null
    val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
    if (port !in 1..65535) return null
    "$scheme://$host:$port"
}.getOrNull()

internal fun credentialScope(providerId: String, url: String): String? =
    providerOrigin(url)?.let { "$providerId@$it" }

internal object ProviderCredentials {
    private const val ALIAS = "handy.llm.credentials.v1"
    private const val PREFIX = "encrypted_api_key."

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Android Keystore key is unavailable" }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build())
        return generator.generateKey()
    }

    fun read(prefs: SharedPreferences, scope: String?): String {
        if (scope == null) return ""
        val value = prefs.getString(PREFIX + scope, null) ?: return ""
        return runCatching { decrypt(scope, value, key(false)) }.getOrDefault("")
    }

    internal fun decrypt(scope: String, value: String, key: SecretKey): String {
        val (iv, data) = value.split(".", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(iv)))
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.getDecoder().decode(data)), Charsets.UTF_8)
    }

    internal fun encrypt(scope: String, value: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(scope.toByteArray(Charsets.UTF_8))
        val data = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv) + "." + Base64.getEncoder().encodeToString(data)
    }

    fun write(editor: SharedPreferences.Editor, scope: String, value: String) {
        if (value.isBlank()) {
            editor.remove(PREFIX + scope)
            return
        }
        editor.putString(PREFIX + scope, encrypt(scope, value, key(true)))
    }
}
