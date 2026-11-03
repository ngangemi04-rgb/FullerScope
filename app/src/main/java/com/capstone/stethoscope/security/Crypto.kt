package com.capstone.stethoscope.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for patient data (DB fields and WAV recordings).
 *
 * Uses an AES-256-GCM key held in the hardware-backed **Android Keystore**, so the
 * key never leaves secure storage and is not extractable. Nothing is stored in
 * plaintext on disk: sensitive DB columns and the audio files are all encrypted
 * with this key.
 *
 * Format for every blob: [12-byte IV][ciphertext + 16-byte GCM tag].
 */
object Crypto {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "stethoscope_patient_data_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain)
        return iv + ciphertext
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ciphertext = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Encrypt a string to a Base64 blob for storing in a DB column. */
    fun encryptString(value: String): String =
        Base64.getEncoder().encodeToString(encrypt(value.toByteArray(Charsets.UTF_8)))

    fun decryptString(encoded: String): String =
        String(decrypt(Base64.getDecoder().decode(encoded)), Charsets.UTF_8)

    /** Encrypt raw bytes (e.g. a WAV file) to [dst]. */
    fun encryptToFile(plain: ByteArray, dst: File) {
        dst.writeBytes(encrypt(plain))
    }

    /** Decrypt an encrypted file back to raw bytes. */
    fun decryptFile(src: File): ByteArray = decrypt(src.readBytes())
}
