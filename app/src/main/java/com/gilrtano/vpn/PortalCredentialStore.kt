package com.gilrtano.vpn

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts PORTAL WARP credentials with an AES-256-GCM key held by Android Keystore.
 * The preference file contains ciphertext only; the key is non-exportable.
 */
object PortalCredentialStore {
    private const val PREFS = "portal_secure_credentials"
    private const val CIPHERTEXT = "credentials_v1"
    private const val KEY_ALIAS = "com.gilrtano.vpn.portal.credentials.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ANDROID_KEY_STORE = "AndroidKeyStore"

    @Synchronized
    fun save(context: Context, credentials: PortalWarpCredentials) {
        require(credentials.privateKey.isNotBlank()) { "Не задан WARP private key" }
        require(credentials.publicKey.isNotBlank()) { "Не задан WARP public key" }
        require(credentials.ipv4.isNotBlank() && credentials.ipv6.isNotBlank()) {
            "Не заданы адреса WARP"
        }

        val payload = JSONObject()
            .put("privateKey", credentials.privateKey)
            .put("publicKey", credentials.publicKey)
            .put("ipv4", credentials.ipv4)
            .put("ipv6", credentials.ipv6)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(payload)
        val packed = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()

        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CIPHERTEXT, packed).commit()) {
            "Не удалось сохранить зашифрованные настройки PORTAL"
        }
    }

    @Synchronized
    fun load(context: Context): PortalWarpCredentials? {
        val packed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CIPHERTEXT, null) ?: return null
        val envelope = JSONObject(packed)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val encrypted = Base64.decode(envelope.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val clear = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        return PortalWarpCredentials(
            privateKey = clear.getString("privateKey"),
            publicKey = clear.getString("publicKey"),
            ipv4 = clear.getString("ipv4"),
            ipv6 = clear.getString("ipv6")
        )
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(CIPHERTEXT).commit()
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
