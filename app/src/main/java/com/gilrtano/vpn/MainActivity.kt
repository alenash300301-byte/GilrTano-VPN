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
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pingStatus: TextView
    private lateinit var primarySource: EditText
    private lateinit var freeSource: EditText
    private lateinit var servers: Spinner
    private lateinit var quattroTab: Button
    private lateinit var freeTab: Button
    private var activeFree = false
    private var quattroNodes: List<ImportedNode> = emptyList()
    private var freeNodes: List<ImportedNode> = emptyList()
    private var pendingConfig: String? = null
    private val requestCode = 4101
    private val docxRequestCode = 4102

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val activeNodes: List<ImportedNode>
        get() = if (activeFree) freeNodes else quattroNodes

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            status.text = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quattroNodes = restoreNodes("quattro_nodes")
        freeNodes = restoreNodes("free_nodes")

        status = TextView(this).apply { text = "Отключено"; textSize = 16f }
        pingStatus = TextView(this).apply { text = "Пинг: —"; textSize = 15f }
        primarySource = EditText(this).apply {
            hint = "Ссылка Quattro или Clash YAML"
            minLines = 2
            maxLines = 5
            setText(prefs.getString("subscription_url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        freeSource = EditText(this).apply {
            hint = "Ссылка бесплатного резерва"
            minLines = 2
            maxLines = 3
            setText(prefs.getString("free_subscription_url", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        servers = Spinner(this).apply {
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val key = if (activeFree) "free_selected" else "quattro_selected"
                    prefs.edit().putInt(key, position).apply()
                    pingStatus.text = "Пинг: —"
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        quattroTab = Button(this).apply {
            text = "QUATTRO"
            setOnClickListener { switchMode(false) }
        }
        freeTab = Button(this).apply {
            text = "БЕСПЛАТНЫЙ РЕЗЕРВ"
            setOnClickListener { switchMode(true) }
        }

        val importPrimary = Button(this).apply {
            text = "Импортировать Quattro"
            setOnClickListener { importFrom(primarySource.text.toString().trim(), false) }
        }
        val importDocx = Button(this).apply {
            text = "Выбрать DOCX Quattro"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                }
                startActivityForResult(pick, docxRequestCode)
            }
        }
        val importFree = Button(this).apply {
            text = "Импортировать бесплатные серверы"
            setOnClickListener { importFrom(freeSource.text.toString().trim(), true) }
        }
        val connect = Button(this).apply {
            text = "Подключить выбранный сервер"
            setOnClickListener {
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        pendingConfig = config
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config)
                    } catch (e: Exception) {
                        status.text = "Профиль не поддерживается: ${e.message}"
                    }
                }
            }
        }
        val disconnect = Button(this).apply {
            text = "Отключить VPN"
            setOnClickListener {
                startService(Intent(this@MainActivity, GilrTanoVpnService::class.java).setAction(GilrTanoVpnService.ACTION_STOP))
            }
        }
        val checkPing = Button(this).apply {
            text = "Проверить пинг"
            setOnClickListener { measureSelectedPing() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            addView(TextView(this@MainActivity).apply { text = "GilrTano VPN"; textSize = 26f })
            addView(TextView(this@MainActivity).apply {
                text = "Выбери режим подключения"
                textSize = 14f
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(quattroTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(freeTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
            addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(importPrimary)
            addView(importDocx)
            addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(importFree)
            addView(TextView(this@MainActivity).apply { text = "Сервер выбранного режима:"; textSize = 15f })
            addView(servers)
            addView(pingStatus)
            addView(checkPing)
            addView(connect)
            addView(disconnect)
            addView(status)
            addView(TextView(this@MainActivity).apply {
                text = "Бесплатные узлы принадлежат сторонним операторам; не используйте их для банков и важных аккаунтов."
                textSize = 12f
            })
        })

        activeFree = prefs.getBoolean("active_free_mode", false)
        refreshMode()
    }

    private fun switchMode(free: Boolean) {
        activeFree = free
        prefs.edit().putBoolean("active_free_mode", free).apply()
        refreshMode()
    }

    private fun refreshMode() {
        quattroTab.isEnabled = activeFree
        freeTab.isEnabled = !activeFree
        primarySource.visibility = if (activeFree) ViewGroup.GONE else ViewGroup.VISIBLE
        freeSource.visibility = if (activeFree) ViewGroup.VISIBLE else ViewGroup.GONE
        findImportButtonsVisibility()
        val list = activeNodes
        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        val selectedKey = if (activeFree) "free_selected" else "quattro_selected"
        val selected = prefs.getInt(selectedKey, 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isNotEmpty()) servers.setSelection(selected)
        pingStatus.text = "Пинг: —"
        status.text = if (activeFree) "Режим: бесплатный резерв" else "Режим: Quattro"
    }

    private fun findImportButtonsVisibility() {
        val root = (servers.parent as? LinearLayout)?.parent as? LinearLayout ?: return
        // Source controls remain in the layout; only the active source is editable.
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is Button) {
                when (child.text.toString()) {
                    "Импортировать Quattro" , "Выбрать DOCX Quattro" -> child.visibility = if (activeFree) ViewGroup.GONE else ViewGroup.VISIBLE
                    "Импортировать бесплатные серверы" -> child.visibility = if (activeFree) ViewGroup.VISIBLE else ViewGroup.GONE
                }
            }
        }
    }

    private fun importFrom(source: String, isFree: Boolean) {
        if (source.isBlank()) {
            Toast.makeText(this, "Укажи ссылку или конфигурацию", Toast.LENGTH_LONG).show()
            return
        }
        status.text = if (isFree) "Загружаю резерв…" else "Загружаю Quattro…"
        Thread {
            try {
                val imported = SubscriptionImporter.load(source)
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено поддерживаемых VLESS-серверов"
                        return@runOnUiThread
                    }
                    if (isFree) {
                        freeNodes = imported
                        prefs.edit().putString("free_subscription_url", source).apply()
                        saveNodes("free_nodes", freeNodes)
                    } else {
                        quattroNodes = imported
                        prefs.edit().putString("subscription_url", source).apply()
                        saveNodes("quattro_nodes", quattroNodes)
                    }
                    activeFree = isFree
                    prefs.edit().putBoolean("active_free_mode", activeFree).apply()
                    refreshMode()
                    status.text = "Сохранено серверов: ${imported.size}. Повторный импорт не нужен."
                    measureSelectedPing()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка импорта: ${e.message}" }
            }
        }.start()
    }

    private fun saveNodes(key: String, nodes: List<ImportedNode>) {
        val array = JSONArray()
        nodes.forEach { array.put(JSONObject().put("name", it.name).put("uri", it.uri)) }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun restoreNodes(key: String): List<ImportedNode> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                ImportedNode(item.getString("name"), item.getString("uri"))
            }
        }.getOrDefault(emptyList())
    }

    private fun measureSelectedPing() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (node == null) {
            pingStatus.text = "Пинг: нет сервера"
            return
        }
        val parsed = Uri.parse(node.uri)
        val host = parsed.host
        val port = if (parsed.port > 0) parsed.port else 443
        if (host.isNullOrBlank()) {
            pingStatus.text = "Пинг: адрес не распознан"
            return
        }
        pingStatus.text = "Пинг: проверяю…"
        Thread {
            val started = System.nanoTime()
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), 5000)
                }
                ((System.nanoTime() - started) / 1_000_000L)
            }
            runOnUiThread {
                pingStatus.text = if (result.isSuccess) "Пинг до сервера: ${result.getOrThrow()} мс (TCP)" else "Сервер не отвечает на проверку"
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
                            quattroNodes = imported
                            saveNodes("quattro_nodes", quattroNodes)
                            activeFree = false
                            prefs.edit().putBoolean("active_free_mode", false).apply()
                            refreshMode()
                            status.text = "Quattro сохранён: ${imported.size} серверов. Импортировать снова не нужно."
                            measureSelectedPing()
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