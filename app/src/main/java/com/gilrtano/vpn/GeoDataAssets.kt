package com.gilrtano.vpn

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Installs the Xray geodata files into filesDir before InitCoreEnv().
 *
 * Xray reads geoip.dat/geosite.dat from xray.location.asset. The ASN MMDB and
 * Mihomo MRS bundle are deliberately not downloaded here: the bundled Xray
 * engine does not consume those formats directly.
 */
internal object GeoDataAssets {
    private const val TAG = "GilrTanoGeoData"
    private const val MIN_VALID_BYTES = 32 * 1024L
    private const val MAX_FILE_BYTES = 64L * 1024L * 1024L
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 45_000

    private data class Asset(val name: String, val urls: List<String>)

    private val assets = listOf(
        Asset(
            "geosite.dat",
            listOf(
                "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
                "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geosite.dat"
            )
        ),
        Asset(
            "geoip.dat",
            listOf(
                "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip.dat",
                "https://cdn.jsdelivr.net/gh/MetaCubeX/meta-rules-dat@release/geoip.dat"
            )
        )
    )

    fun ensureInstalled(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            Log.w(TAG, "Cannot create Xray asset directory: $directory")
            return
        }
        assets.forEach { asset ->
            val destination = File(directory, asset.name)
            if (isValid(destination)) return@forEach
            var installed = false
            var lastError: Exception? = null
            for (source in asset.urls) {
                try {
                    download(source, destination)
                    installed = true
                    Log.i(TAG, "Installed ${asset.name} (${destination.length()} bytes)")
                    break
                } catch (error: Exception) {
                    lastError = error
                    Log.w(TAG, "Could not download ${asset.name} from $source: ${error.message}")
                }
            }
            if (!installed) {
                Log.w(TAG, "Xray geodata ${asset.name} unavailable; continuing without this optional asset", lastError)
            }
        }
    }

    private fun isValid(file: File): Boolean =
        file.isFile && file.length() in MIN_VALID_BYTES..MAX_FILE_BYTES

    private fun download(source: String, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".download")
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(source).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "GilrTanoVPN/0.9.10")
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = connection.responseCode
            check(code in 200..299) { "HTTP $code" }
            val declaredLength = connection.contentLengthLong
            check(declaredLength <= MAX_FILE_BYTES) { "Asset exceeds 64 MiB limit" }

            var total = 0L
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_FILE_BYTES) { "Asset exceeds 64 MiB limit" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(total >= MIN_VALID_BYTES) { "Downloaded asset is too small ($total bytes)" }
            check(!looksLikeHtml(temporary)) { "Server returned an HTML page instead of geodata" }
            if (destination.exists() && !destination.delete()) {
                error("Cannot replace old asset")
            }
            check(temporary.renameTo(destination)) { "Cannot move downloaded asset into place" }
        } finally {
            connection?.disconnect()
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun looksLikeHtml(file: File): Boolean {
        val prefix = ByteArray(256)
        val count = file.inputStream().use { it.read(prefix) }
        if (count <= 0) return true
        val sample = String(prefix, 0, count, Charsets.UTF_8).trimStart().lowercase()
        return sample.startsWith("<!doctype html") || sample.startsWith("<html")
    }
}
