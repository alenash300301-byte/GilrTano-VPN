package com.gilrtano.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import teapodcore.Teapodcore
import teapodcore.VpnProtector
import teapodcore.XrayCallback
import org.json.JSONObject
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

class GilrTanoVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopping = false

    // Core environment and socket protection are process-wide setup; keep them
    // ready across reconnects instead of repeating native initialization.
    @Volatile private var coreInitialized = false
    @Volatile private var activeConfig: String? = null
    @Volatile private var fallbackConfig: String? = null
    @Volatile private var preferredConfig: String? = null
    @Volatile private var automaticMode = false
    @Volatile private var monitorRunning = false
    @Volatile private var consecutiveProbeFailures = 0
    @Volatile private var lastAutomaticSwitchAt = 0L
    @Volatile private var usingFallback = false
    @Volatile private var boxMode = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START, ACTION_SWITCH, ACTION_START_PORTAL -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    broadcastStatus("Добавьте VLESS-ссылку")
                    stopSelf()
                } else if (intent.action == ACTION_SWITCH) {
                    automaticMode = false
                    switchTunnel(config)
                } else {
                    boxMode = intent.action == ACTION_START_PORTAL || intent.getBooleanExtra(EXTRA_ENGINE_BOX, false)
                    automaticMode = if (boxMode) false else intent.getBooleanExtra(EXTRA_AUTO, false)
                    fallbackConfig = intent.getStringExtra(EXTRA_FALLBACK_CONFIG)
                    preferredConfig = intent.getStringExtra(EXTRA_PREFERRED_CONFIG)
                    usingFallback = false
                    consecutiveProbeFailures = 0
                    startTunnel(config)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun switchTunnel(config: String) {
        stopping = true
        broadcastStatus("Переключаю сервер…")
        Thread({
            synchronized(this) {
                runCatching { Teapodcore.stopTun2Socks() }
                runCatching { Teapodcore.stopXray() }
                runCatching { Teapodcore.stopBox() }
                runCatching { tun?.close() }
                tun = null
            }
            stopping = false
            startTunnel(config)
        }, "GilrTano-Xray-switch").start()
    }

    private fun startTunnel(config: String) {
        stopping = false
        activeConfig = config
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification(if (boxMode) "Запуск PORTAL GATE…" else "Запуск Quattro…"))
        // Native core initialization/start can block; never run it on Android's service main thread.
        Thread({
            try {
                if (!coreInitialized) {
                    synchronized(this@GilrTanoVpnService) {
                        if (!coreInitialized) {
                            Teapodcore.initCoreEnv(filesDir.absolutePath, "")
                            Teapodcore.registerVpnProtector(object : VpnProtector {
                                override fun protect(fd: Long): Boolean =
                                    this@GilrTanoVpnService.protect(fd.toInt())
                            })
                            coreInitialized = true
                        }
                    }
                }
                if (stopping) return@Thread
                val callback = object : XrayCallback {
                    override fun onStatus(status: Long, message: String) {
                        Log.i(TAG, "Xray status=$status $message")
                        if (status == 0L && !stopping) establishTun()
                        else if (status < 0L && !stopping) {
                            if (automaticMode && !usingFallback && !fallbackConfig.isNullOrBlank()) {
                                usingFallback = true
                                lastAutomaticSwitchAt = System.currentTimeMillis()
                                broadcastStatus("Quattro недоступен. Запускаю бесплатный резерв…")
                                switchTunnel(fallbackConfig!!)
                            } else {
                                broadcastStatus("Ошибка Xray: $message")
                                stopTunnel()
                            }
                        }
                    }
                }
                if (boxMode) Teapodcore.startBox(config, filesDir.absolutePath, callback)
                else Teapodcore.startXray(config, callback)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Xray", e)
                if (!stopping) {
                    if (automaticMode && !usingFallback && !fallbackConfig.isNullOrBlank()) {
                        usingFallback = true
                        lastAutomaticSwitchAt = System.currentTimeMillis()
                        broadcastStatus("Не удалось запустить Quattro. Пробую бесплатный резерв…")
                        switchTunnel(fallbackConfig!!)
                    } else {
                        broadcastStatus("Ошибка запуска: ${e.message}")
                        stopTunnel()
                    }
                }
            }
        }, "GilrTano-Xray-start").start()
    }

    @Synchronized private fun establishTun() {
        if (stopping || tun != null) return
        try {
            val pfd = Builder()
                .setSession("GilrTano VPN")
                .setMtu(1280)
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")
            tun = pfd
            // Force HTTP/3 apps to fall back to HTTPS/TCP because many public
            // free SOCKS nodes do not relay UDP reliably. This avoids apps
            // hanging on a broken QUIC path; ICMP is not needed here.
            val error = Teapodcore.startTun2Socks(
                pfd.fd.toLong(), 1280L, SOCKS_PORT.toLong(),
                "", "", false, true, null
            )
            if (error.isNotEmpty()) throw IllegalStateException("tun2socks: $error")

            // StartTun2Socks returns after scheduling its engine. Do not tell the
            // user VPN is connected until the native bridge reports it running.
            var running = false
            for (attempt in 0 until 20) {
                if (Teapodcore.isTunRunning()) {
                    running = true
                    break
                }
                Thread.sleep(100)
            }
            if (!running) throw IllegalStateException("TUN-мост не запустился")

            // Verify actual HTTPS traffic through this profile before showing
            // a successful connection; a running TUN bridge alone is insufficient.
            var internetReady = false
            repeat(3) { attempt ->
                if (!internetReady && !stopping) {
                    internetReady = probeTunnel() >= 0L
                    if (!internetReady && attempt < 2) Thread.sleep(650)
                }
            }
            if (!internetReady) {
                throw IllegalStateException("Сервер ответил, но интернет через VPN не проходит")
            }
            if (!verifyRequiredServices()) {
                throw IllegalStateException("Не все обязательные сервисы доступны через VPN")
            }

            broadcastStatus(
                if (automaticMode) {
                    if (usingFallback) "Подключено · бесплатный резерв" else "Подключено · Quattro"
                } else "Подключено · Xray и TUN работают"
            )
            updateNotification("Подключено · защищённый трафик")
            startConnectionMonitor()
        } catch (e: Exception) {
            Log.w(TAG, "VPN tunnel did not pass internet verification", e)
            if (automaticMode && !usingFallback && !fallbackConfig.isNullOrBlank() && !stopping) {
                usingFallback = true
                lastAutomaticSwitchAt = System.currentTimeMillis()
                broadcastStatus("У выбранного сервера нет интернета. Пробую бесплатный резерв…")
                switchTunnel(fallbackConfig!!)
            } else {
                broadcastStatus("Не удалось подключиться: ${e.message}")
                stopTunnel()
            }
        }
    }


    /**
     * End-to-end health check through the running local SOCKS inbound. This does
     * not start a second Xray core and therefore cannot contend with the active tunnel.
     */
    private fun startConnectionMonitor() {
        if (!automaticMode || monitorRunning) return
        monitorRunning = true
        Thread({
            while (monitorRunning && automaticMode && !stopping) {
                try {
                    Thread.sleep(MONITOR_INTERVAL_MS)
                    if (!monitorRunning || !automaticMode || stopping) break
                    val latency = probeTunnel()
                    if (latency < 0L || latency > POOR_LATENCY_MS) consecutiveProbeFailures++
                    else consecutiveProbeFailures = 0

                    if (consecutiveProbeFailures >= FAILURE_LIMIT &&
                        System.currentTimeMillis() - lastAutomaticSwitchAt > SWITCH_COOLDOWN_MS) {
                        if (!usingFallback && !fallbackConfig.isNullOrBlank()) {
                            val target = fallbackConfig!!
                            usingFallback = true
                            lastAutomaticSwitchAt = System.currentTimeMillis()
                            consecutiveProbeFailures = 0
                            broadcastStatus("Связь ухудшилась. Переключаю на бесплатный резерв…")
                            switchTunnel(target)
                        } else if (usingFallback && !preferredConfig.isNullOrBlank() &&
                            System.currentTimeMillis() - lastAutomaticSwitchAt >= RECOVERY_INTERVAL_MS) {
                            val target = preferredConfig!!
                            usingFallback = false
                            lastAutomaticSwitchAt = System.currentTimeMillis()
                            consecutiveProbeFailures = 0
                            broadcastStatus("Проверяю восстановление Quattro…")
                            switchTunnel(target)
                        }
                    }

                    // When using the backup, periodically give the preferred Quattro
                    // profile another chance. A failed trial is automatically rolled back.
                    if (usingFallback && !preferredConfig.isNullOrBlank() &&
                        System.currentTimeMillis() - lastAutomaticSwitchAt >= RECOVERY_INTERVAL_MS &&
                        consecutiveProbeFailures == 0) {
                        lastAutomaticSwitchAt = System.currentTimeMillis()
                        usingFallback = false
                        broadcastStatus("Проверяю, восстановился ли Quattro…")
                        switchTunnel(preferredConfig!!)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Connection monitor error", e)
                }
            }
            monitorRunning = false
        }, "GilrTano-connection-monitor").apply { isDaemon = true }.start()
    }

    private fun probeTunnel(): Long = probeTunnelHost("www.gstatic.com", "/generate_204", 204)

    private fun verifyRequiredServices(): Boolean {
        val hosts = listOf(
            "www.youtube.com",
            "www.instagram.com",
            "api.telegram.org"
        )
        val pool = java.util.concurrent.Executors.newFixedThreadPool(hosts.size)
        return try {
            val checks = hosts.map { host ->
                pool.submit<Boolean> {
                    probeTunnelHost(host, "/", null) >= 0L
                }
            }
            checks.all { runCatching { it.get(4, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun probeTunnelHost(hostName: String, path: String, expectedCode: Int?): Long {
        val started = System.nanoTime()
        val socket = Socket()
        try {
            socket.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), PROBE_TIMEOUT_MS)
            socket.soTimeout = PROBE_TIMEOUT_MS
            val out = socket.getOutputStream()
            val input = socket.getInputStream()

            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting)
            if (greeting[0].toInt() != 5 || greeting[1].toInt() != 0) return -1L

            val host = hostName.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, host.size.toByte()) + host +
                byteArrayOf(0x01, 0xbb.toByte()))
            out.flush()
            val reply = ByteArray(4)
            readFully(input, reply)
            if (reply[1].toInt() != 0) return -1L
            val addressLength = when (reply[3].toInt() and 0xff) {
                1 -> 4
                3 -> input.read()
                4 -> 16
                else -> return -1L
            }
            if (addressLength <= 0) return -1L
            readFully(input, ByteArray(addressLength + 2))

            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, hostName, 443, true) as javax.net.ssl.SSLSocket
            tls.soTimeout = PROBE_TIMEOUT_MS
            tls.startHandshake()
            tls.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: $hostName\r\nUser-Agent: Mozilla/5.0 GilrTanoVPN\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            tls.getOutputStream().flush()
            val line = tls.getInputStream().bufferedReader().readLine() ?: return -1L
            tls.close()
            val code = Regex("HTTP/\\d(?:\\.\\d)?\\s+(\\d{3})").find(line)?.groupValues?.get(1)?.toIntOrNull()
                ?: return -1L
            if (expectedCode != null && code != expectedCode) return -1L
            if (expectedCode == null && code !in 200..499) return -1L
            return (System.nanoTime() - started) / 1_000_000L
        } catch (_: Exception) {
            return -1L
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw java.io.EOFException("SOCKS probe ended early")
            offset += count
        }
    }

    private fun stopTunnel() {
        stopping = true
        monitorRunning = false
        automaticMode = false
        broadcastStatus("Отменяю подключение…")
        // Native shutdown may block; run it away from Android's main thread.
        Thread({
            synchronized(this) {
                runCatching { Teapodcore.stopTun2Socks() }
                runCatching { Teapodcore.stopXray() }
                runCatching { Teapodcore.stopBox() }
                runCatching { tun?.close() }
                tun = null
            }
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
            broadcastStatus("Отключено")
            stopSelf()
        }, "GilrTano-Xray-stop").start()
    }

    private fun broadcastStatus(value: String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS, value))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "GilrTano VPN", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnect = PendingIntent.getService(
            this, 2, Intent(this, GilrTanoVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("GilrTano VPN")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", disconnect)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilrtano.vpn.START"
        const val ACTION_SWITCH = "com.gilrtano.vpn.SWITCH"
        const val ACTION_STOP = "com.gilrtano.vpn.STOP"
        const val ACTION_START_PORTAL = "com.gilrtano.vpn.START_PORTAL"
        const val ACTION_STATUS = "com.gilrtano.vpn.STATUS"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_ENGINE_BOX = "engine_box"
        const val EXTRA_STATUS = "status"
        const val EXTRA_AUTO = "automatic_mode"
        const val EXTRA_FALLBACK_CONFIG = "fallback_config"
        const val EXTRA_PREFERRED_CONFIG = "preferred_config"
        private const val TAG = "GilrTanoVPN"
        private const val CHANNEL_ID = "gilrtano_vpn"
        private const val NOTIFICATION_ID = 76
        private const val SOCKS_PORT = 10808
        private const val MONITOR_INTERVAL_MS = 15_000L
        private const val PROBE_TIMEOUT_MS = 3_500
        private const val POOR_LATENCY_MS = 500L
        private const val FAILURE_LIMIT = 2
        private const val SWITCH_COOLDOWN_MS = 60_000L
        private const val RECOVERY_INTERVAL_MS = 5 * 60_000L

        fun xrayConfigFromVless(uri: String): String {
            val parsed = android.net.Uri.parse(uri.trim())
            require(parsed.scheme == "vless" && !parsed.host.isNullOrBlank()) { "Нужна ссылка vless://" }
            val uuid = parsed.userInfo ?: parsed.encodedUserInfo ?: error("В ссылке нет UUID")
            val port = if (parsed.port > 0) parsed.port else 443
            val q = parsed
            val security = q.getQueryParameter("security") ?: "none"
            val network = q.getQueryParameter("type") ?: "tcp"
            // Xray supports VLESS with TLS, REALITY, and plaintext transport.
            // Public free lists often contain security=none; rejecting it here
            // discarded otherwise valid VLESS profiles before Xray could test them.
            require(security in setOf("reality", "tls", "none")) {
                "Неизвестный тип защиты VLESS: $security"
            }
            require(network in setOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")) {
                "Неподдерживаемый транспорт VLESS: $network"
            }
            val stream = JSONObject().put("network", network).put("security", security)
            if (security == "reality") {
                val serverName = q.getQueryParameter("sni") ?: q.getQueryParameter("serverName") ?: ""
                val publicKey = q.getQueryParameter("pbk") ?: ""
                val shortId = q.getQueryParameter("sid") ?: ""
                require(serverName.isNotBlank() && publicKey.isNotBlank()) {
                    "В REALITY-профиле отсутствует SNI или public key"
                }
                // REALITY shortId is optional in Xray; an empty value is valid.
                val reality = JSONObject()
                    .put("show", false)
                    .put("serverName", serverName)
                    .put("fingerprint", q.getQueryParameter("fp") ?: "chrome")
                    .put("publicKey", publicKey)
                    .put("shortId", shortId)
                    .put("spiderX", q.getQueryParameter("spx") ?: "")
                stream.put("realitySettings", reality)
            } else if (security == "tls") {
                val tls = JSONObject()
                    .put("serverName", q.getQueryParameter("sni") ?: q.getQueryParameter("serverName") ?: parsed.host)
                q.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { tls.put("fingerprint", it) }
                q.getQueryParameter("alpn")?.takeIf { it.isNotBlank() }?.let { value ->
                    tls.put("alpn", org.json.JSONArray(value.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
                }
                stream.put("tlsSettings", tls)
            }
            if (network == "ws" || network == "httpupgrade") {
                val ws = JSONObject().put("path", q.getQueryParameter("path") ?: "/")
                q.getQueryParameter("host")?.takeIf { it.isNotBlank() }?.let {
                    ws.put("headers", JSONObject().put("Host", it))
                }
                stream.put(if (network == "ws") "wsSettings" else "httpupgradeSettings", ws)
            } else if (network == "grpc") {
                stream.put("grpcSettings", JSONObject()
                    .put("serviceName", q.getQueryParameter("serviceName") ?: "")
                    .put("multiMode", q.getQueryParameter("mode") == "multi"))
            } else if (network == "xhttp") {
                stream.put("xhttpSettings", JSONObject()
                    .put("path", q.getQueryParameter("path") ?: "/")
                    .put("mode", q.getQueryParameter("mode") ?: "auto"))
            }
            val outbound = JSONObject()
                .put("tag", "proxy").put("protocol", "vless")
                .put("settings", JSONObject().put("vnext", org.json.JSONArray().put(
                    JSONObject().put("address", parsed.host).put("port", port)
                        .put("users", org.json.JSONArray().put(JSONObject().put("id", uuid).put("encryption", "none").put("flow", q.getQueryParameter("flow") ?: "")))
                )))
                .put("streamSettings", stream)
            val inbound = JSONObject().put("tag", "socks-in").put("listen", "127.0.0.1").put("port", SOCKS_PORT)
                .put("protocol", "socks").put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            return JSONObject().put("log", JSONObject().put("loglevel", "warning"))
                .put("inbounds", org.json.JSONArray().put(inbound))
                .put("outbounds", org.json.JSONArray().put(outbound).put(JSONObject().put("tag", "direct").put("protocol", "freedom")))
                .put("routing", JSONObject().put("rules", org.json.JSONArray().put(JSONObject().put("type", "field").put("inboundTag", org.json.JSONArray().put("socks-in")).put("outboundTag", "proxy"))))
                .toString()
        }
    }
}
