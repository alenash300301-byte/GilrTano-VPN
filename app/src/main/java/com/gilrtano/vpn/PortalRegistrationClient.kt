package com.gilrtano.vpn

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Creates a fresh consumer WARP registration and enrolls a P-256 MASQUE key.
 *
 * Cloudflare's consumer registration endpoint is not a documented public API.
 * The request format follows the MIT-licensed usque implementation; keep this
 * client isolated so the protocol can be updated without touching VPN routing.
 */
object PortalRegistrationClient {
    private const val API = "https://api.cloudflareclient.com"
    private const val API_VERSION = "v0a4471"
    private const val USER_AGENT = "WARP for Android"
    private const val CLIENT_VERSION = "a-6.35-4471"

    fun registerAndSave(context: Context): PortalWarpCredentials {
        val initialKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date())

        val registration = JSONObject()
            .put("key", Base64.encodeToString(initialKey, Base64.NO_WRAP))
            .put("install_id", "")
            .put("fcm_token", "")
            .put("tos", tos)
            .put("model", "PC")
            .put("serial_number", serial)
            .put("os_version", "")
            .put("key_type", "curve25519")
            .put("tunnel_type", "wireguard")
            .put("locale", Locale.getDefault().toString().ifBlank { "en_US" })

        val registered = request("POST", "$API/$API_VERSION/reg", registration, null)
        val deviceId = registered.optString("id")
        val token = registered.optString("token")
        check(deviceId.isNotBlank() && token.isNotBlank()) {
            "Cloudflare не вернул данные регистрации"
        }

        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = generator.generateKeyPair()
        val privateKey = Base64.encodeToString(keyPair.private.encoded, Base64.NO_WRAP)
        val publicKey = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

        val enrollment = JSONObject()
            .put("key", publicKey)
            .put("key_type", "secp256r1")
            .put("tunnel_type", "masque")
            .put("name", "GilrTano VPN")

        val enrolled = request(
            "PATCH", "$API/$API_VERSION/reg/$deviceId", enrollment, token
        )
        val config = enrolled.optJSONObject("config")
            ?: throw IllegalStateException("Cloudflare не вернул MASQUE-конфигурацию")
        val iface = config.optJSONObject("interface")
            ?: throw IllegalStateException("В ответе нет сетевого интерфейса WARP")
        val addresses = iface.optJSONObject("addresses")
            ?: throw IllegalStateException("В ответе нет IP-адресов WARP")
        val peers = config.optJSONArray("peers")
            ?: throw IllegalStateException("В ответе нет MASQUE-сервера")
        check(peers.length() > 0) { "Cloudflare не вернул ключ сервера" }
        val peer = peers.getJSONObject(0)
        val serverPublicKey = peer.optString("public_key")
        val ipv4 = addresses.optString("v4")
        val ipv6 = addresses.optString("v6")
        check(serverPublicKey.isNotBlank() && ipv4.isNotBlank() && ipv6.isNotBlank()) {
            "Ответ регистрации WARP неполный"
        }

        return PortalWarpCredentials(
            privateKey = privateKey,
            publicKey = serverPublicKey,
            ipv4 = ipv4,
            ipv6 = ipv6
        ).also { PortalCredentialStore.save(context, it) }
    }

    private fun request(
        method: String,
        endpoint: String,
        body: JSONObject,
        bearer: String?
    ): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("CF-Client-Version", CLIENT_VERSION)
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Connection", "Keep-Alive")
            if (!bearer.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $bearer")
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("Регистрация WARP: HTTP $code")
            }
            return JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}
