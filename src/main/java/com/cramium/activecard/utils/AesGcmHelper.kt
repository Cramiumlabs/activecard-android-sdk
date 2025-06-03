package com.cramium.activecard.utils

import android.os.Build
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcmHelper {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE_BYTES = 12          // recommended for GCM
    private const val TAG_SIZE_BITS = 128         // 16-byte tag
    private val secureRandom = SecureRandom()
    private const val KEY = "R4I2mxBPKIeFdh/V7knb07LAnCIuGkNxPukQNkxRgus="
    private const val KEY_ALGORITHM = "AES"
    private val key = SecretKeySpec(decodeFromBase64(KEY), KEY_ALGORITHM)
    private val cipher = Cipher.getInstance(TRANSFORMATION)
    private fun encodeToBase64(bytes: ByteArray): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Java 8 Base64
            java.util.Base64.getEncoder().encodeToString(bytes)
        } else {
            // android.util.Base64 for older APIs
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }
    }

    private fun decodeFromBase64(b64: String): ByteArray {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            java.util.Base64.getDecoder().decode(b64)
        } else {
            android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        }
    }

    fun encrypt(plain: ByteArray): EncryptionResult {
        val iv = ByteArray(IV_SIZE_BYTES).apply { secureRandom.nextBytes(this) }
        val spec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val cipherOutput = cipher.doFinal(plain)
        val tag = cipherOutput.takeLast(TAG_SIZE_BITS / 8).toByteArray()
        val cipherText = cipherOutput.dropLast(TAG_SIZE_BITS / 8).toByteArray()
        return EncryptionResult(iv, tag, cipherText)
    }

    fun decrypt(iv: ByteArray, tag: ByteArray, cipherText: ByteArray): ByteArray {
        val cipherInput = cipherText + tag
        val spec = GCMParameterSpec(TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(cipherInput)
    }
}


data class EncryptionResult(
    val iv: ByteArray,
    val tag: ByteArray,
    val cipherText: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EncryptionResult

        if (!iv.contentEquals(other.iv)) return false
        if (!tag.contentEquals(other.tag)) return false
        if (!cipherText.contentEquals(other.cipherText)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + tag.contentHashCode()
        result = 31 * result + cipherText.contentHashCode()
        return result
    }
}
