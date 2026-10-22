package com.mediavault.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secrets (passwords, private keys, passphrases) are encrypted with an AES-256-GCM key that lives in the
 * Android Keystore and never leaves it. Only ciphertext is written to disk. Values are never logged.
 */
@Singleton
class CredentialStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(destinationId: String, name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("$destinationId.$name", Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    /** Returns null when absent or when the key was invalidated (e.g. lock-screen reset); caller must ask again. */
    fun get(destinationId: String, name: String): String? {
        val raw = prefs.getString("$destinationId.$name", null) ?: return null
        return try {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun has(destinationId: String, name: String) = prefs.contains("$destinationId.$name")

    fun clear(destinationId: String) {
        val e = prefs.edit()
        prefs.all.keys.filter { it.startsWith("$destinationId.") }.forEach { e.remove(it) }
        e.apply()
    }

    fun copy(fromId: String, toId: String) {
        for (n in listOf("password", "privateKey", "passphrase")) get(fromId, n)?.let { put(toId, n, it) }
    }

    private companion object {
        const val ALIAS = "mediavault_credentials"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
