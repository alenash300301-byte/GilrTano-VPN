package com.gilrtano.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.view.ViewGroup
import android.view.MotionEvent
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.animation.ArgbEvaluator
import android.view.animation.DecelerateInterpolator
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var pingStatus: TextView
    private lateinit var primarySource: EditText
    private lateinit var freeSource: EditText
    private lateinit var servers: Spinner
    private lateinit var quattroTab: Button
    private lateinit var freeTab: Button
    private lateinit var bestFreeButton: Button
    private lateinit var connectButton: Button
    private lateinit var cancelConnectButton: Button
    @Volatile private var scanningFree = false
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
            val message = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
            status.text = message
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray")
            cancelConnectButton.isEnabled = busy
            connectButton.isEnabled = !busy
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
        connectButton = Button(this).apply {
            text = "⏻\nПОДКЛЮЧИТЬ"
            setOnClickListener {
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        igniteConnectButton()
                        val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        pendingConfig = config
                        connectButton.isEnabled = false
                        cancelConnectButton.isEnabled = true
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config)
                    } catch (e: Exception) {
                        pendingConfig = null
                        connectButton.isEnabled = true
                        cancelConnectButton.isEnabled = false
                        status.text = "Профиль не поддерживается: ${e.message}"
                    }
                }
            }
        }
        cancelConnectButton = Button(this).apply {
            text = "Отменить подключение"
            isEnabled = false
            setOnClickListener {
                pendingConfig = null
                cancelConnectButton.isEnabled = false
                connectButton.isEnabled = true
                status.text = "Отменяю подключение…"
                startService(Intent(this@MainActivity, GilrTanoVpnService::class.java).setAction(GilrTanoVpnService.ACTION_STOP))
            }
        }
        val disconnect = Button(this).apply {
            text = "Отключить VPN"
            setOnClickListener {
                startService(Intent(this@MainActivity, GilrTanoVpnService::class.java).setAction(GilrTanoVpnService.ACTION_STOP))
            }
        }
        bestFreeButton = Button(this).apply {
            text = "Найти лучший бесплатный сервер (проверка интернета)"
            setOnClickListener { findBestFreeServers() }
        }
        val checkPing = Button(this).apply {
            text = "Проверить пинг"
            setOnClickListener { measureSelectedPing() }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TigerMarkView(this@MainActivity), LinearLayout.LayoutParams(56, 56))
                addView(TextView(this@MainActivity).apply {
                    text = "GilrTano VPN"; textSize = 25f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.rgb(255, 125, 24))
                })
            })
            addView(TextView(this@MainActivity).apply {
                text = "🔥 ОГОНЬ ВНУТРИ. СВОБОДА В СЕТИ."
                textSize = 11f; setTextColor(Color.rgb(190, 125, 90))
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
            addView(TextView(this@MainActivity).apply { text = "СЕРВЕР"; textSize = 11f; setTextColor(Color.LTGRAY) })
            addView(servers)
            addView(pingStatus)
            addView(bestFreeButton)
            addView(checkPing)
            addView(connectButton)
            addView(cancelConnectButton)
            addView(disconnect)
            addView(status)
            addView(TextView(this@MainActivity).apply {
                text = "Бесплатные серверы принадлежат сторонним операторам."
                textSize = 10f; setTextColor(Color.GRAY)
            })
        })

        styleDarkInterface()
        connectButton.apply {
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                (184 * resources.displayMetrics.density).toInt(),
                (184 * resources.displayMetrics.density).toInt()
            ).apply { gravity = android.view.Gravity.CENTER; topMargin = (12 * resources.displayMetrics.density).toInt(); bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(42, 15, 7), Color.rgb(8, 7, 7))).apply {
                shape = GradientDrawable.OVAL
                setStroke((4 * resources.displayMetrics.density).toInt(), Color.rgb(255, 105, 12))
            }
            elevation = 12f
            // Subtle fire pulse only while the user presses the power button.
            setOnTouchListener { view, event ->
                val pressed = event.actionMasked == MotionEvent.ACTION_DOWN
                val released = event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                if (pressed || released) {
                    val targetScale = if (pressed) 0.975f else 1f
                    val targetElevation = if (pressed) dp(18).toFloat() else dp(12).toFloat()
                    AnimatorSet().apply {
                        playTogether(
                            ObjectAnimator.ofFloat(view, View.SCALE_X, targetScale),
                            ObjectAnimator.ofFloat(view, View.SCALE_Y, targetScale),
                            ObjectAnimator.ofFloat(view, View.TRANSLATION_Z, if (pressed) dp(6).toFloat() else 0f)
                        )
                        duration = if (pressed) 85L else 150L
                        interpolator = DecelerateInterpolator()
                        start()
                    }
                    (view.background as? GradientDrawable)?.setStroke(
                        dp(if (pressed) 6 else 4),
                        if (pressed) Color.rgb(255, 174, 55) else Color.rgb(255, 105, 12)
                    )
                    view.elevation = targetElevation
                }
                false
            }
        }
        activeFree = prefs.getBoolean("active_free_mode", false)
        refreshMode()
    }

    // A short ember-to-flame ignition when the user starts connecting.
    private fun igniteConnectButton() {
        val bg = connectButton.background as? GradientDrawable ?: return
        val dim = Color.rgb(42, 15, 7)
        val hot = Color.rgb(112, 27, 5)
        val ember = Color.rgb(255, 105, 12)
        val bright = Color.rgb(255, 190, 65)
        val animator = ValueAnimator.ofFloat(0f, 1f, 0.72f, 1f, 0.88f)
        animator.duration = 1050L
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val t = animation.animatedValue as Float
            val color = ArgbEvaluator().evaluate(t, dim, hot) as Int
            val edge = ArgbEvaluator().evaluate(t, ember, bright) as Int
            bg.setColor(color)
            bg.setStroke(dp(4 + (4 * t).toInt()), edge)
            connectButton.elevation = dp(12 + (10 * t).toInt()).toFloat()
            connectButton.scaleX = 1f + 0.025f * t
            connectButton.scaleY = 1f + 0.025f * t
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                connectButton.animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                bg.setColor(dim)
                bg.setStroke(dp(4), ember)
                connectButton.elevation = dp(12).toFloat()
            }
        })
        animator.start()
    }

    private fun styleDarkInterface() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? LinearLayout ?: return
        root.setBackgroundColor(Color.rgb(8, 8, 10))
        fun style(view: android.view.View) {
            when (view) {
                is Button -> {
                    view.setTextColor(Color.WHITE)
                    view.textSize = 13f
                    view.isAllCaps = false
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(24, 13, 10))
                        cornerRadius = 18f
                        setStroke(2, Color.rgb(220, 78, 12))
                    }
                    view.setPadding(16, 10, 16, 10)
                    val lp = view.layoutParams
                    if (lp != null) { lp.height = (44 * resources.displayMetrics.density).toInt(); view.layoutParams = lp }
                }
                is EditText -> {
                    view.setTextColor(Color.WHITE)
                    view.setHintTextColor(Color.GRAY)
                    view.textSize = 14f
                    view.setPadding(14, 10, 14, 10)
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(18, 12, 10))
                        cornerRadius = 14f
                        setStroke(1, Color.rgb(120, 48, 18))
                    }
                }
                is TextView -> {
                    if (view.currentTextColor != Color.WHITE) view.setTextColor(Color.LTGRAY)
                }
                is Spinner -> {
                    view.setBackgroundColor(Color.rgb(24, 25, 28))
                }
                is ViewGroup -> for (i in 0 until view.childCount) style(view.getChildAt(i))
            }
        }
        style(root)
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
        bestFreeButton.visibility = if (activeFree) ViewGroup.VISIBLE else ViewGroup.GONE
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
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
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
                    if (isFree) findBestFreeServers() else measureSelectedPing()
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

    private fun findBestFreeServers() {
        if (scanningFree) return
        if (freeNodes.isEmpty()) {
            status.text = "Сначала импортируй бесплатный список серверов"
            return
        }
        scanningFree = true
        bestFreeButton.isEnabled = false
        status.text = "Проверяю до 30 серверов через Xray и реальный HTTPS-запрос…"
        val candidates = freeNodes.take(30)
        Thread {
            val pool = Executors.newFixedThreadPool(3)
            try {
                val tasks = candidates.map { node ->
                    Callable<Pair<ImportedNode, Long>?> {
                        // A TCP port-open check is not proof that VLESS authentication,
                        // TLS/REALITY and the proxy data path work. Probe through a
                        // temporary Xray instance and an actual HTTPS request instead.
                        val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }
                            .getOrNull() ?: return@Callable null
                        runCatching {
                            val delayMs = teapodcore.Teapodcore.measureOutboundDelay(
                                config, "https://www.gstatic.com/generate_204"
                            )
                            if (delayMs <= 0L) null else node to delayMs
                        }.getOrNull()
                    }
                }
                // Keep native Xray probes bounded: many simultaneous cores can exhaust
                // memory on a phone, even though this scan runs off the UI thread.
                val checked = pool.invokeAll(tasks, 120, TimeUnit.SECONDS)
                    .mapNotNull { future -> if (future.isCancelled) null else runCatching { future.get() }.getOrNull() }
                    .sortedBy { it.second }
                runOnUiThread {
                    if (checked.isEmpty()) {
                        status.text = "Не найдено доступных поддерживаемых VLESS-серверов. Попробуй обновить список позже."
                    } else {
                        freeNodes = checked.map { it.first }
                        saveNodes("free_nodes", freeNodes)
                        prefs.edit().putInt("free_selected", 0).apply()
                        val labels = checked.map { pair -> "${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${checked.first().second} мс"
                        status.text = "Реальный интернет-тест пройден: ${checked.size} из ${candidates.size}. Выбран лучший сервер."
                    }
                    scanningFree = false
                    bestFreeButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    scanningFree = false
                    bestFreeButton.isEnabled = true
                    status.text = "Не удалось проверить серверы: ${e.message ?: "ошибка проверки"}"
                }
            } finally {
                pool.shutdownNow()
            }
        }.start()
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
        connectButton.isEnabled = false
        cancelConnectButton.isEnabled = true
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
            pendingConfig?.let { startVpn(it) } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            pendingConfig = null
            cancelConnectButton.isEnabled = false
            connectButton.isEnabled = true
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