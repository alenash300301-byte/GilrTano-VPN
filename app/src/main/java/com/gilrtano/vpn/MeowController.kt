package com.gilrtano.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class MeowProxyGroup(
    val name: String,
    val type: String,
    val members: List<String>,
    val selected: String?,
)

/**
 * Local-only controller for the embedded Meow engine.
 * The bearer secret is stored in app-private noBackupFilesDir and never
 * exported through an intent or log.
 */
object MeowController {
    private const val BASE_URL = "http://127.0.0.1:9090"

    fun listGroups(context: Context): List<MeowProxyGroup> {
        val response = request(context, "GET", "/api/proxy-groups")
        val array = JSONArray(response)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val members = item.optJSONArray("proxies") ?: JSONArray()
            MeowProxyGroup(
                name = item.optString("name"),
                type = item.optString("type"),
                members = (0 until members.length()).mapNotNull { members.optString(it).takeIf(String::isNotBlank) },
                selected = item.optString("now").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }

    /**
     * Measures a real proxy through Meow's delay endpoint. A successful
     * result means the proxy reached the HTTP health-check URL and received
     * the expected status, not merely that its TCP port was open.
     */
    fun probeMember(
        context: Context,
        memberName: String,
        url: String = "https://cp.cloudflare.com/generate_204",
        timeoutMs: Int = 5000,
    ): Int {
        require(memberName.isNotBlank())
        require(timeoutMs in 1..65535)
        val path = "/proxies/${encode(memberName)}/delay" +
            "?url=${encode(url)}&timeout=$timeoutMs&expected=204"
        val response = request(context, "GET", path)
        val delay = JSONObject(response).optInt("delay", -1)
        if (delay < 0) throw IllegalStateException("Meow вернул некорректную задержку для $memberName")
        return delay
    }

    fun selectMember(context: Context, groupName: String, memberName: String) {
        require(groupName.isNotBlank() && memberName.isNotBlank())
        val path = "/api/proxy-groups/${encode(groupName)}/select"
        request(
            context,
            "PUT",
            path,
            JSONObject().put("name", memberName).toString(),
        )
    }

    private fun request(
        context: Context,
        method: String,
        path: String,
        body: String? = null,
    ): String {
        val secretFile = File(File(context.noBackupFilesDir, "meow"), "controller.secret")
        val secret = secretFile.takeIf { it.isFile }?.readText()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Meow controller secret is unavailable")
        val connection = (URL(BASE_URL + path).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = method
            connection.connectTimeout = 4000
            connection.readTimeout = 6000
            connection.setRequestProperty("Authorization", "Bearer $secret")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    (connection.errorStream ?: connection.inputStream)
                        .bufferedReader().use { it.readText().take(300) }
                }.getOrNull().orEmpty()
                throw IllegalStateException("Meow API $method $path: HTTP $code ${detail}".trim())
            }
            return if (code == HttpURLConnection.HTTP_NO_CONTENT) "" else
                connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
