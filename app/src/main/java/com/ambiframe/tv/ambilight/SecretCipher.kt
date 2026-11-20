package com.ambiframe.tv.ambilight

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts small secrets (the JointSpace `auth_key`) before they are written to disk. */
interface SecretCipher {
    fun encrypt(plaintext: String): String

    /** @throws GeneralSecurityException if the value can't be decrypted (for example, key lost). */
    fun decrypt(ciphertext: String): String
}

/**
 * AES-256/GCM with a non-exportable key in the Android Keystore. Output is
 * `v1:<base64 iv>:<base64 ciphertext+tag>`.
 */
class KeystoreSecretCipher(private val alias: String = DEFAULT_ALIAS) : SecretCipher {

    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(VERSION, b64(cipher.iv), b64(sealed)).joinToString(":")
    }

    override fun decrypt(ciphertext: String): String {
        val parts = ciphertext.split(':')
        if (parts.size != 3 || parts[0] != VERSION) throw GeneralSecurityException("Unknown secret format")
        val decoder = Base64.getDecoder()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, decoder.decode(parts[1])))
        return String(cipher.doFinal(decoder.decode(parts[2])), Charsets.UTF_8)
    }

    private fun key(): SecretKey = synchronized(lock) {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        generator.generateKey()
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val DEFAULT_ALIAS = "ambiframe_jointspace_auth"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val VERSION = "v1"
        private val lock = Any()
    }
}
