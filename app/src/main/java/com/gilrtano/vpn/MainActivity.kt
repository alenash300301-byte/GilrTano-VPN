package com.gilrtano.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var profile: EditText
    private val requestCode = 4101
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Отключено"; textSize = 18f }
        profile = EditText(this).apply {
            hint = "Вставьте VLESS-ссылку или ссылку подписки"
            minLines = 3
            maxLines = 5
            setText(getPreferences(MODE_PRIVATE).getString("vless_uri", ""))
        }
        val connect = Button(this).apply {
            text = "Подключить"
            setOnClickListener {
                val uri = profile.text.toString().trim()
                if (uri.isBlank()) {
                    Toast.makeText(this@MainActivity, "Вставьте ссылку профиля", Toast.LENGTH_LONG).show()
                } else {
                    getPreferences(MODE_PRIVATE).edit().putString("vless_uri", uri).apply()
                    status.text = "Загружаю профиль…"
                    Thread {
                        try {
                            val vless = resolveVless(uri)
                            val config = GilrTanoVpnService.xrayConfigFromVless(vless)
                            runOnUiThread {
                                pendingConfig = config
                                val permission = VpnService.prepare(this@MainActivity)
                                if (permission != null) startActivityForResult(permission, requestCode)
                                else startVpn(config)
                            }
                        } catch (e: Exception) {
                            runOnUiThread { status.text = "Профиль: " + e.message }
                        }
                    }.start()
                }
            }
        }
        val disconnect = Button(this).apply {
            text = "Отключить"
            setOnClickListener {
                startService(Intent(this@MainActivity, GilrTanoVpnService::class.java).setAction(GilrTanoVpnService.ACTION_STOP))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            addView(TextView(this@MainActivity).apply { text = "GilrTano VPN"; textSize = 24f })
            addView(profile, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(connect)
            addView(disconnect)
            addView(status)
        })
    }

    private fun resolveVless(input: String): String {
        if (input.startsWith("vless://")) return input
        val connection = (URL(input).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 20000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "GilrTanoVPN/0.7.6")
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
            val decoded = if (body.startsWith("vless://")) body else try {
                String(Base64.decode(body.replace("\\n", "").trim(), Base64.DEFAULT), Charsets.UTF_8)
            } catch (_: Exception) { body }
            return decoded.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("vless://") }
                ?: throw IllegalArgumentException("В подписке не найдена VLESS-конфигурация")
        } finally {
            connection.disconnect()
        }
    }

    private var pendingConfig: String? = null
    private fun startVpn(config: String) {
        pendingConfig = config
        startForegroundService(Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config))
        status.text = "Запускаю VPN…"
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let { startVpn(it) }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(GilrTanoVpnService.ACTION_STATUS)
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onStop() {
        runCatching { unregisterReceiver(receiver) }
        super.onStop()
    }
}
