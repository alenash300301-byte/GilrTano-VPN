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
import android.view.View
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
            if (message.startsWith("Подключено")) igniteConnectButton()
            else if (message == "Отключено" || message.startsWith("Ошибка")) resetConnectButtonGlow()
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(5, 5, 7))
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(12), dp(18), dp(18))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    addView(TextView(this@MainActivity).apply {
                        text = "☰"; textSize = 27f; setTextColor(Color.rgb(255, 112, 18))
                        setPadding(dp(4), 0, dp(12), 0)
                    })
                    addView(TigerMarkView(this@MainActivity), LinearLayout.LayoutParams(dp(48), dp(48)))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(10), 0, 0, 0)
                        addView(TextView(this@MainActivity).apply {
                            text = "GilrTano VPN"; textSize = 25f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = "БЕЗОПАСНОЕ VPN-СОЕДИНЕНИЕ"; textSize = 10f
                            setTextColor(Color.LTGRAY)
                        })
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "ⓘ"; textSize = 26f; setTextColor(Color.rgb(255, 112, 18))
                    })
                })
                addView(View(this@MainActivity).apply {
                    setBackgroundColor(Color.rgb(190, 61, 10))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(8); bottomMargin = dp(8)
                })
                addView(TigerMarkView(this@MainActivity), LinearLayout.LayoutParams(dp(270), dp(270)).apply {
                    topMargin = dp(4); bottomMargin = dp(2)
                })
                addView(status.apply {
                    text = "● Отключено"; textSize = 18f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.rgb(255, 92, 45))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(25, 10, 9)); cornerRadius = dp(28).toFloat()
                        setStroke(dp(1), Color.rgb(205, 43, 24))
                    }
                    setPadding(dp(22), dp(9), dp(22), dp(9))
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Готово к подключению  🇪🇺"; textSize = 16f
                    setTextColor(Color.LTGRAY); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(8), 0, dp(4))
                })
                addView(connectButton, LinearLayout.LayoutParams(dp(280), dp(280)).apply {
                    gravity = android.view.Gravity.CENTER
                    topMargin = dp(8); bottomMargin = dp(12)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(quattroTab, LinearLayout.LayoutParams(0, dp(54), 1f).apply { rightMargin = dp(4) })
                    addView(freeTab, LinearLayout.LayoutParams(0, dp(54), 1f).apply { leftMargin = dp(4) })
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Выбор сервера  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(2))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(24, 13, 10)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(230, 83, 12))
                    }
                    setOnClickListener { servers.performClick() }
                })
                addView(TextView(this@MainActivity).apply {
                    text = "Выбери сервер ниже или включи автоматический подбор"; textSize = 12f
                    setTextColor(Color.LTGRAY); setPadding(dp(16), dp(3), dp(12), 0)
                })
                addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(34))
                addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(44))
                addView(TextView(this@MainActivity).apply {
                    text = "Настройки и импорт  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(24, 13, 10)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(230, 83, 12))
                    }
                    setOnClickListener {
                        val show = primarySource.visibility != View.VISIBLE
                        primarySource.visibility = if (show && !activeFree) View.VISIBLE else View.GONE
                        freeSource.visibility = if (show && activeFree) View.VISIBLE else View.GONE
                        findImportButtonsVisibility()
                    }
                })
                addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importPrimary)
                addView(importDocx)
                addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importFree)
                addView(cancelConnectButton)
                addView(disconnect)
                addView(status.apply { textSize = 13f; setPadding(dp(8), dp(8), dp(8), dp(8)) })
                addView(TextView(this@MainActivity).apply {
                    text = "Бесплатные серверы предоставляются сторонними операторами."
                    textSize = 10f; setTextColor(Color.GRAY); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(14), 0, dp(8))
                    listOf("⌂  Главная", "▤  Серверы", "⚙  Настройки").forEach { label ->
                        addView(TextView(this@MainActivity).apply {
                            text = label; textSize = 13f; gravity = android.view.Gravity.CENTER
                            setTextColor(Color.rgb(255, 132, 24)); setPadding(dp(8), dp(10), dp(8), dp(10))
                        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                })
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        styleDarkInterface()
        installButtonFeedback()
        connectButton.apply {
            textSize = 23f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                (250 * resources.displayMetrics.density).toInt(),
                (250 * resources.displayMetrics.density).toInt()
            ).apply { gravity = android.view.Gravity.CENTER; topMargin = (12 * resources.displayMetrics.density).toInt(); bottomMargin = (12 * resources.displayMetrics.density).toInt() }
            background = fireDrawable
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

    // Flame tongues move continuously only while VPN is genuinely connected.
    private fun igniteConnectButton() {
        fireDrawable.setIgnited(true)
        if (fireAnimator?.isRunning == true) return
        fireAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 1250L
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { fireDrawable.setPhase(it.animatedValue as Float) }
            start()
        }
        connectButton.elevation = dp(16).toFloat()
    }

    private fun resetConnectButtonGlow() {
        fireAnimator?.cancel()
        fireAnimator = null
        fireDrawable.setIgnited(false)
        fireDrawable.setPhase(0f)
        connectButton.animate().cancel()
        connectButton.scaleX = 1f
        connectButton.scaleY = 1f
        connectButton.elevation = dp(10).toFloat()
    }

    private fun installButtonFeedback() {
        fun visit(group: ViewGroup) {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                if (child is Button && child !== connectButton) {
                    child.setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                view.animate().cancel()
                                view.animate().scaleX(0.965f).scaleY(0.965f)
                                    .translationZ(dp(4).toFloat()).setDuration(75L)
                                    .setInterpolator(DecelerateInterpolator()).start()
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                view.animate().scaleX(1f).scaleY(1f).translationZ(0f)
                                    .setDuration(170L).setInterpolator(DecelerateInterpolator()).start()
                            }
                        }
                        false
                    }
                }
                if (child is ViewGroup) visit(child)
            }
        }
        visit(findViewById(android.R.id.content))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
        status.text = "Быстрый поиск: проверяю первые серверы параллельно…"
        // A short first-pass scan is preferable to making the user wait minutes.
        // Keep a small warm set; the fastest working profiles are saved for next launch.
        val candidates = freeNodes.take(80)
        Thread {
            val pool = Executors.newFixedThreadPool(6)
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<ImportedNode, Long>?>(pool)
            try {
                candidates.forEach { node ->
                    completion.submit(Callable<Pair<ImportedNode, Long>?> {
                        val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }
                            .getOrNull() ?: return@Callable null
                        runCatching {
                            val delayMs = teapodcore.Teapodcore.measureOutboundDelay(
                                config, "https://www.gstatic.com/generate_204"
                            )
                            if (delayMs <= 0L) null else node to delayMs
                        }.getOrNull()
                    })
                }
                val checked = mutableListOf<Pair<ImportedNode, Long>>()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
                var completed = 0
                while (completed < candidates.size && System.nanoTime() < deadline && checked.size < 8) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    completed++
                    runCatching { future.get() }.getOrNull()?.let { checked.add(it) }
                    if (checked.size >= 3) {
                        // Three real working routes are enough for a useful quick shortlist.
                        // Do not keep the connect button waiting for a full catalogue scan.
                        break
                    }
                }
                val fastest = checked.sortedBy { it.second }
                runOnUiThread {
                    if (fastest.isEmpty()) {
                        status.text = "За 25 секунд рабочий сервер не найден. Список можно обновить и попробовать снова."
                    } else {
                        freeNodes = fastest.map { it.first }
                        saveNodes("free_nodes", freeNodes)
                        prefs.edit().putInt("free_selected", 0).apply()
                        val labels = fastest.map { pair -> "${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${fastest.first().second} мс"
                        status.text = "Быстрый подбор готов: ${fastest.size} рабочих сервер(а). Лучший выбран."
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