package com.esp32phone.agent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the user's model Key encrypted with an Android Keystore key (AES-256-GCM).
 *
 * The Keystore key is non-exportable and bound to this app + device; the ciphertext alone is
 * useless elsewhere. The plaintext never touches logs, preferences in cleartext, screenshots
 * or exports.
 *
 * NOTE (honesty): this class is real code but has NOT been exercised on a device in this
 * checkpoint. `docs/implementation-status.md` records that fact; do not read it as verified.
 */
class SecretStore(context: Context) {

    private val prefs = context.getSharedPreferences("agent.secrets", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(KEY_API_KEY)

    /** @return the masked form used in the UI, or null when nothing is stored. */
    fun maskedApiKey(): String? {
        val plain = read(KEY_API_KEY) ?: return null
        return if (plain.length <= 8) {
            "••••"
        } else {
            plain.take(4) + "••••" + plain.takeLast(4)
        }
    }

    fun saveApiKey(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        return write(KEY_API_KEY, trimmed)
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /**
     * The device binding secret is delivered per board (QR/USB) and must never be shared
     * between devices. Stored separately from the model Key (baseline §8).
     */
    fun hasBindingSecret(): Boolean = prefs.contains(KEY_BINDING)

    fun saveBindingSecret(secret: ByteArray): Boolean {
        if (secret.size != 32) return false
        return write(KEY_BINDING, Base64.encodeToString(secret, Base64.NO_WRAP))
    }

    fun bindingSecret(): ByteArray? {
        val encoded = read(KEY_BINDING) ?: return null
        return runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    fun clearBindingSecret() {
        prefs.edit().remove(KEY_BINDING).apply()
    }

    private fun write(alias: String, plaintext: String): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(alias, payload).commit()
    }.getOrDefault(false)

    private fun read(alias: String): String? = runCatching {
        val payload = prefs.getString(alias, null) ?: return null
        val raw = Base64.decode(payload, Base64.NO_WRAP)
        if (raw.size <= IV_LENGTH) return null
        val iv = raw.copyOfRange(0, IV_LENGTH)
        val ciphertext = raw.copyOfRange(IV_LENGTH, raw.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "esp32phone.agent.secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val KEY_API_KEY = "gateway_api_key"
        const val KEY_BINDING = "device_binding_secret"
    }
}
