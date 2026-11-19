package com.gilrtano.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device-local encrypted storage for the Consumer WARP identity returned by Usque.
 * The plaintext secret is never written to preferences or logs.
 */
internal class UsqueIdentityStore(context: Context) {
    private val preferences = context.getSharedPreferences("usque_identity_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun save(secret: ByteArray) {
        require(secret.isNotEmpty() && secret.size <= 64 * 1024)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(secret)
        val payload = byteArrayOf(1) + cipher.iv + encrypted
        check(preferences.edit().putString(KEY_SECRET, android.util.Base64.encodeToString(payload, android.util.Base64.NO_WRAP)).commit())
        encrypted.fill(0)
        payload.fill(0)
    }

    @Synchronized
    fun load(): ByteArray? {
        val encoded = preferences.getString(KEY_SECRET, null) ?: return null
        val payload = try {
            android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (payload.size < 30 || payload[0] != 1.toByte()) {
            payload.fill(0)
            return null
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 1, 12))
            cipher.doFinal(payload, 13, payload.size - 13)
        } finally {
            payload.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_SECRET).commit()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "gilrtano.usque.consumer-warp.v1"
        const val KEY_SECRET = "consumer_warp_secret"
    }
}
