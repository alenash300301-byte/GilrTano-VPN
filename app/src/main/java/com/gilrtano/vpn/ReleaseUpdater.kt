package com.gilrtano.vpn

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val tag: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val sha256: String
)

class ReleaseUpdater(private val context: Context) {
    companion object {
        private const val RELEASE_API =
            "https://api.github.com/repos/alenash300301-byte/GilrTano-VPN/releases/latest"
        private const val USER_AGENT = "GilrTano-VPN-Updater"
    }

    fun latestRelease(): AppRelease? {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val json = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val release = JSONObject(json)
            val tag = release.optString("tag_name")
            val assets = release.optJSONArray("assets") ?: return null
            val apkAssets = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk", ignoreCase = true) }
            if (tag.isBlank() || apkAssets.isEmpty()) return null

            val selected = apkAssets.firstOrNull {
                it.optString("name").contains("universal", true)
            } ?: apkAssets.firstOrNull {
                it.optString("name").contains("release", true)
            } ?: apkAssets.firstOrNull {
                it.optString("name").contains("app-debug", true)
            } ?: apkAssets.singleOrNull()
                ?: throw IllegalStateException("В релизе несколько APK, но нет универсального файла")

            val apkName = selected.optString("name")
            val apkUrl = selected.optString("browser_download_url")
            if (!apkUrl.startsWith("https://github.com/")) {
                throw IllegalStateException("Недопустимый адрес APK в релизе")
            }

            var digest = selected.optString("digest")
            if (digest.startsWith("sha256:", true)) digest = digest.substringAfter(':')
            if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) {
                val checksumAsset = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull {
                        val name = it.optString("name").lowercase()
                        name == "sha256sums.txt" || name == "checksums.txt"
                    }
                if (checksumAsset != null) {
                    val checksumUrl = checksumAsset.optString("browser_download_url")
                    if (checksumUrl.startsWith("https://github.com/")) {
                        val text = getText(checksumUrl)
                        val line = text.lineSequence().firstOrNull {
                            it.contains(apkName)
                        }
                        digest = line?.trim()?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
                    }
                }
            }
            if (!digest.matches(Regex("[a-fA-F0-9]{64}"))) {
                throw IllegalStateException("В релизе нет SHA-256 для проверки APK")
            }
            return AppRelease(tag, release.optString("body"), apkName, apkUrl, digest.lowercase())
        } finally {
            connection.disconnect()
        }
    }

    private fun getText(address: String): String {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    fun isNewer(remoteTag: String, installedVersion: String): Boolean {
        fun parts(value: String) = Regex("\\d+").findAll(value).map { it.value.toLong() }.toList()
        val remote = parts(remoteTag)
        val local = parts(installedVersion)
        for (i in 0 until maxOf(remote.size, local.size)) {
            val a = remote.getOrElse(i) { 0L }
            val b = local.getOrElse(i) { 0L }
            if (a != b) return a > b
        }
        return false
    }

    fun download(release: AppRelease, destination: File, progress: (Int) -> Unit) {
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    val total = connection.contentLengthLong
                    var received = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        if (total > 0) progress(((received * 100) / total).toInt().coerceIn(0, 100))
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = MessageDigest.getInstance("SHA-256").digest(destination.readBytes())
            .joinToString("") { "%02x".format(it) }
        if (!actual.equals(release.sha256, ignoreCase = true)) {
            destination.delete()
            throw SecurityException("Контрольная сумма APK не совпала. Файл удалён.")
        }
        if (!destination.isFile || destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException("Загруженный APK пустой")
        }
    }

    fun install(destination: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        val sessionId = packageInstaller.createSession(params)
        try {
            packageInstaller.openSession(sessionId).use { session ->
                destination.inputStream().use { input ->
                    session.openWrite(destination.name, 0, destination.length()).use { output ->
                        input.copyTo(output, 64 * 1024)
                        session.fsync(output)
                    }
                }
                val intent = android.content.Intent(context, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_INSTALL_STATUS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                val pendingFlags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
                val pending = android.app.PendingIntent.getActivity(
                    context, sessionId, intent, pendingFlags
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { packageInstaller.abandonSession(sessionId) }
            throw error
        }
    }
}
