package com.esp32phone.agent.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Thin wrappers over platform cryptography (JCE on Android/JVM). No primitive is
 * implemented here: HMAC-SHA256, SHA-256 and AES/GCM come from the platform, and HKDF is
 * the RFC 5869 composition of HMAC-SHA256 (extract + expand), which is also how the
 * ESP32 side composes mbedTLS primitives.
 */
public object Crypto {

    private const val HMAC_SHA256 = "HmacSHA256"
    private const val AES_GCM = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    private val random: SecureRandom by lazy { SecureRandom() }

    public fun randomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        random.nextBytes(out)
        return out
    }

    public fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    public fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(key, HMAC_SHA256))
        return mac.doFinal(data)
    }

    /** RFC 5869 HKDF-SHA256. */
    public fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "invalid HKDF length $length" }
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var produced = 0
        var counter = 1
        var previous = ByteArray(0)
        while (produced < length) {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(prk, HMAC_SHA256))
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - produced)
            previous.copyInto(out, produced, 0, take)
            produced += take
            counter += 1
        }
        return out
    }

    /** AES-256-GCM seal; returns ciphertext || 16-byte tag. */
    public fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
        require(nonce.size == 12) { "GCM nonce must be 12 bytes, got ${nonce.size}" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    /** @return plaintext, or null when authentication fails (never a partial result). */
    public fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? {
        if (key.size != 32 || nonce.size != 12 || sealed.size < WireFormat.GCM_TAG_LEN) return null
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(sealed)
        } catch (e: Exception) {
            null
        }
    }

    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
