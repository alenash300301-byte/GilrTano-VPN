package com.gilrtano.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var profile: EditText
    private lateinit var servers: Spinner
    private val requestCode = 4101
    private var nodes: List<ImportedNode> = emptyList()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Отключено"; textSize = 17f }
        profile = EditText(this).apply {
            hint = "Ссылка Quattro, VLESS или подписка"
            minLines = 2
            maxLines = 4
            setText(getPreferences(MODE_PRIVATE).getString("subscription_url", ""))
        }
        servers = Spinner(this)
        val import = Button(this).apply {
            text = "Импортировать Quattro / подписку"
            setOnClickListener { importFrom(profile.text.toString().trim(), false) }
        }
        val free = Button(this).apply {
            text = "Загрузить бесплатный резерв"
            setOnClickListener { importFrom(FREE_VLESS_URL, true) }
        }
        val connect = Button(this).apply {
            text = "Подключить выбранный сервер"
            setOnClickListener {
                val node = nodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "Сначала импортируйте серверы", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        pendingConfig = config
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config)
                    } catch (e: Exception) {
                        status.text = "Ошибка профиля: ${e.message}"
                    }
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
            addView(TextView(this@MainActivity).apply { text = "GilrTano VPN"; textSize = 25f })
            addView(TextView(this@MainActivity).apply {
                text = "Основной профиль Quattro и бесплатный резерв"
                textSize = 14f
            })
            addView(profile, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(import)
            addView(free)
            addView(TextView(this@MainActivity).apply { text = "Сервер:"; textSize = 15f })
            addView(servers)
            addView(connect)
            addView(disconnect)
            addView(status)
            addView(TextView(this@MainActivity).apply {
                text = "Бесплатные публичные узлы принадлежат неизвестным операторам. Не используйте их для банков и важных аккаунтов."
                textSize = 12f
            })
        })
    }

    private fun importFrom(source: String, isFree: Boolean) {
        if (source.isBlank()) {
            Toast.makeText(this, "Вставьте ссылку Quattro или подписки", Toast.LENGTH_LONG).show()
            return
        }
        status.text = if (isFree) "Загружаю бесплатный резерв…" else "Загружаю Quattro…"
        Thread {
            try {
                val imported = SubscriptionImporter.load(source)
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено поддерживаемых VLESS-серверов"
                        return@runOnUiThread
                    }
                    nodes = imported
                    servers.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        nodes.map { it.name }
                    )
                    if (!isFree) {
                        profile.setText(source)
                        getPreferences(MODE_PRIVATE).edit().putString("subscription_url", source).apply()
                    }
                    status.text = "Импортировано серверов: ${nodes.size}. Hysteria2 пока пропускается."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка импорта: ${e.message}" }
            }
        }.start()
    }

    private var pendingConfig: String? = null

    private fun startVpn(config: String) {
        pendingConfig = config
        startForegroundService(
            Intent(this, GilrTanoVpnService::class.java)
                .setAction(GilrTanoVpnService.ACTION_START)
                .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
        )
        status.text = "Запускаю VPN…"
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let { startVpn(it) }
        } else if (requestCode == this.requestCode) {
            status.text = "Разрешение VPN не предоставлено"
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

    companion object {
        private const val FREE_VLESS_URL =
            "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"
    }
}
