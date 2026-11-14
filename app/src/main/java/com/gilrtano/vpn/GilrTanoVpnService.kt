package com.gilrtano.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import teapodcore.Teapodcore
import teapodcore.VpnProtector
import teapodcore.XrayCallback
import org.json.JSONObject

class GilrTanoVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    @Volatile private var stopping = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> {
                val config = intent.getStringExtra(EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    broadcastStatus("Добавьте VLESS-ссылку")
                    stopSelf()
                } else startTunnel(config)
            }
        }
        return START_NOT_STICKY
    }

    private fun startTunnel(config: String) {
        stopping = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Запуск Xray…"))
        try {
            Teapodcore.initCoreEnv(filesDir.absolutePath, "")
            Teapodcore.registerVpnProtector(object : VpnProtector {
                override fun protect(fd: Long): Boolean = this@GilrTanoVpnService.protect(fd.toInt())
            })
            Teapodcore.startXray(config, object : XrayCallback {
                override fun onStatus(status: Long, message: String) {
                    Log.i(TAG, "Xray status=$status $message")
                    if (status == 0L && !stopping) establishTun()
                    else if (status < 0L && !stopping) {
                        broadcastStatus("Ошибка Xray: $message")
                        stopTunnel()
                    }
                }
            })
        } catch (e: Exception) {
            broadcastStatus("Ошибка запуска: ${e.message}")
            stopTunnel()
        }
    }

    @Synchronized private fun establishTun() {
        if (stopping || tun != null) return
        try {
            val pfd = Builder()
                .setSession("GilrTano VPN")
                .setMtu(1500)
                .addAddress("10.77.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс")
            tun = pfd
            val error = Teapodcore.startTun2Socks(
                pfd.fd.toLong(), 1500L, SOCKS_PORT.toLong(),
                "", "", true, true, null
            )
            if (error.isNotEmpty()) throw IllegalStateException(error)
            broadcastStatus("Подключено · Xray работает")
            updateNotification("Подключено")
        } catch (e: Exception) {
            broadcastStatus("Ошибка TUN: ${e.message}")
            stopTunnel()
        }
    }

    @Synchronized private fun stopTunnel() {
        stopping = true
        runCatching { Teapodcore.stopTun2Socks() }
        runCatching { Teapodcore.stopXray() }
        runCatching { Teapodcore.registerVpnProtector(null) }
        runCatching { tun?.close() }
        tun = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        broadcastStatus("Отключено")
        stopSelf()
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

    private fun notification(text: String): Notification =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("GilrTano VPN").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setOngoing(true).build()
        else @Suppress("DEPRECATION") Notification.Builder(this)
            .setContentTitle("GilrTano VPN").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning).setOngoing(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.gilrtano.vpn.START"
        const val ACTION_STOP = "com.gilrtano.vpn.STOP"
        const val ACTION_STATUS = "com.gilrtano.vpn.STATUS"
        const val EXTRA_CONFIG = "xray_config"
        const val EXTRA_STATUS = "status"
        private const val TAG = "GilrTanoVPN"
        private const val CHANNEL_ID = "gilrtano_vpn"
        private const val NOTIFICATION_ID = 76
        private const val SOCKS_PORT = 10808

        fun xrayConfigFromVless(uri: String): String {
            val parsed = android.net.Uri.parse(uri.trim())
            require(parsed.scheme == "vless" && !parsed.host.isNullOrBlank()) { "Нужна ссылка vless://" }
            val uuid = parsed.userInfo ?: parsed.encodedUserInfo ?: error("В ссылке нет UUID")
            val port = if (parsed.port > 0) parsed.port else 443
            val q = parsed
            val security = q.getQueryParameter("security") ?: "none"
            val network = q.getQueryParameter("type") ?: "tcp"
            require(security == "reality" || security == "tls" || security == "none") { "Неподдерживаемый security=$security" }
            val stream = JSONObject().put("network", network).put("security", security)
            if (security == "reality") {
                val reality = JSONObject()
                    .put("show", false)
                    .put("serverName", q.getQueryParameter("sni") ?: q.getQueryParameter("serverName") ?: "")
                    .put("fingerprint", q.getQueryParameter("fp") ?: "chrome")
                    .put("publicKey", q.getQueryParameter("pbk") ?: "")
                    .put("shortId", q.getQueryParameter("sid") ?: "")
                stream.put("realitySettings", reality)
            } else if (security == "tls") {
                stream.put("tlsSettings", JSONObject().put("serverName", q.getQueryParameter("sni") ?: parsed.host))
            }
            if (network == "ws") {
                stream.put("wsSettings", JSONObject().put("path", q.getQueryParameter("path") ?: "/"))
            } else if (network == "grpc") {
                stream.put("grpcSettings", JSONObject().put("serviceName", q.getQueryParameter("serviceName") ?: ""))
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
