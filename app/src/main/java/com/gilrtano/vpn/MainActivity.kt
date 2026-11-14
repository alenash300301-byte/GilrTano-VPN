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
    private lateinit var primarySource: EditText
    private lateinit var freeSource: EditText
    private lateinit var servers: Spinner
    private val requestCode = 4101\n    private val docxRequestCode = 4102
    private var nodes: List<ImportedNode> = emptyList()
    private var pendingConfig: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { text = "Отключено"; textSize = 17f }
        primarySource = EditText(this).apply {
            hint = "Quattro: ссылка подписки или вставленный YAML"
            minLines = 3
            maxLines = 8
            setText(getPreferences(MODE_PRIVATE).getString("subscription_url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        freeSource = EditText(this).apply {
            hint = "Резерв: ссылка бесплатной подписки VLESS (добавьте проверенный источник)"
            minLines = 2
            maxLines = 4
            setText(getPreferences(MODE_PRIVATE).getString("free_subscription_url", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"))
        }
        servers = Spinner(this)
        val importPrimary = Button(this).apply {
            text = "Импортировать Quattro по ссылке / YAML"
            setOnClickListener { importFrom(primarySource.text.toString().trim(), false) }
        }
        val importDocx = Button(this).apply {
            text = "Выбрать DOCX с конфигурацией Quattro"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                }
                startActivityForResult(intent, docxRequestCode)
            }
        }
        val importFree = Button(this).apply {
            text = "Импортировать бесплатный резерв"
            setOnClickListener { importFrom(freeSource.text.toString().trim(), true) }
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
                        status.text = "Этот профиль пока не поддерживается: ${e.message}"
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
                text = "Quattro — основной профиль. Бесплатный резерв подключается отдельно."
                textSize = 14f
            })
            addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(importPrimary)
            addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(importFree)
            addView(TextView(this@MainActivity).apply { text = "Сервер:"; textSize = 15f })
            addView(servers)
            addView(connect)
            addView(disconnect)
            addView(status)
            addView(TextView(this@MainActivity).apply {
                text = "Публичные бесплатные узлы контролируются неизвестными операторами. Не используйте их для банков, паролей и важных аккаунтов."
                textSize = 12f
            })
        })
    }

    private fun importFrom(source: String, isFree: Boolean) {
        if (source.isBlank()) {
            Toast.makeText(this, "Вставьте ссылку подписки или конфигурацию YAML", Toast.LENGTH_LONG).show()
            return
        }
        status.text = if (isFree) "Загружаю бесплатный резерв…" else "Загружаю Quattro…"
        Thread {
            try {
                val imported = SubscriptionImporter.load(source)
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено поддерживаемых VLESS-серверов. Hysteria2 пока не поддерживается."
                        return@runOnUiThread
                    }
                    nodes = imported
                    servers.adapter = ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_dropdown_item,
                        nodes.map { it.name }
                    )
                    val key = if (isFree) "free_subscription_url" else "subscription_url"
                    getPreferences(MODE_PRIVATE).edit().putString(key, source).apply()
                    status.text = "Импортировано VLESS-серверов: ${nodes.size}. Выберите сервер."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка импорта: ${e.message}" }
            }
        }.start()
    }

    private fun startVpn(config: String) {
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
        } else if (requestCode == docxRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Читаю DOCX локально…"
            Thread {
                try {
                    val yaml = DocxTextExtractor.read(this, uri)
                    val imported = SubscriptionImporter.parse(yaml)
                    runOnUiThread {
                        if (imported.isEmpty()) {
                            status.text = "В DOCX не найдены поддерживаемые VLESS-профили"
                        } else {
                            nodes = imported
                            servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nodes.map { it.name })
                            status.text = "Из DOCX импортировано VLESS-серверов: ${nodes.size}"
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "Ошибка чтения DOCX: ${e.message}" }
                }
            }.start()
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
