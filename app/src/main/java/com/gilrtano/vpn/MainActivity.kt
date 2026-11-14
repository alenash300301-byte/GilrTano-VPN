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
import android.widget.Chronometer
import android.os.SystemClock
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
    private lateinit var powerOrb: TextView
    private lateinit var connectionTimer: Chronometer
    private lateinit var serverCardTitle: TextView
    private lateinit var serverCardSubtitle: TextView
    private lateinit var serverCardPing: TextView
    private lateinit var settingsHeader: TextView
    private lateinit var pingStatus: TextView
    private lateinit var primarySource: EditText
    private lateinit var freeSource: EditText
    private lateinit var servers: Spinner
    private lateinit var quattroTab: Button
    private lateinit var freeTab: Button
    private lateinit var bestFreeButton: Button
    private lateinit var connectButton: Button
    private val fireDrawable = FireButtonDrawable()
    private var fireAnimator: ValueAnimator? = null
    @Volatile private var scanningFree = false
    private var activeFree = false
    private var settingsExpanded = false
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
            status.text = when {
                message.startsWith("Подключено") -> "●  Подключено"
                message == "Отключено" -> "●  Отключено"
                else -> message
            }
            if (message.startsWith("Подключено")) {
                status.setTextColor(Color.rgb(36, 255, 72))
                updatePowerOrb(true)
                if (::connectionTimer.isInitialized) {
                    connectionTimer.base = SystemClock.elapsedRealtime()
                    connectionTimer.visibility = View.VISIBLE
                    connectionTimer.start()
                }
                updateServerCard(true)
                igniteConnectButton()
            } else if (message == "Отключено" || message.startsWith("Ошибка")) {
                status.setTextColor(Color.LTGRAY)
                updatePowerOrb(false)
                if (::connectionTimer.isInitialized) {
                    connectionTimer.stop()
                    connectionTimer.visibility = View.GONE
                }
                updateServerCard(false)
                resetConnectButtonGlow()
            } else if (message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray")) {
                status.setTextColor(Color.rgb(255, 166, 55))
                updatePowerOrb(false)
            }
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray")
            // The single power button remains usable while connecting so it can cancel,
            // and while connected so it can disconnect.
            connectButton.isEnabled = true
            setPowerButtonLabel(
                if (message.startsWith("Подключено") || message.startsWith("Отключаю")) "ОТКЛЮЧИТЬ"
                else if (busy) "ПОДКЛЮЧЕНИЕ…"
                else "ПОДКЛЮЧИТЬ"
            )
            connectButton.contentDescription = when {
                message.startsWith("Подключено") -> "Отключить VPN"
                busy -> "Отменить подключение"
                else -> "Подключить VPN"
            }
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
                val currentStatus = status.text.toString()
                val isConnected = currentStatus.startsWith("Подключено")
                val isConnecting = currentStatus.startsWith("Подключаю") ||
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Xray")
                if (isConnected || isConnecting) {
                    pendingConfig = null
                    status.text = if (isConnected) "Отключаю VPN…" else "Отменяю подключение…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_STOP))
                    return@setOnClickListener
                }
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        pendingConfig = config
                        connectButton.isEnabled = true
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config)
                    } catch (e: Exception) {
                        pendingConfig = null
                        connectButton.isEnabled = true
                        status.text = "Профиль не поддерживается: ${e.message}"
                    }
                }
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
                        text = "☰"; textSize = 27f; setTextColor(Color.WHITE)
                        setPadding(dp(4), 0, dp(10), 0)
                        setOnClickListener {
                            settingsExpanded = !settingsExpanded
                            settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
                            findImportButtonsVisibility()
                        }
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER
                        val title = TextView(this@MainActivity).apply {
                            text = "GilrTano"; textSize = 25f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.WHITE)
                        }
                        val vpn = TextView(this@MainActivity).apply {
                            text = " VPN"; textSize = 25f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            setTextColor(Color.rgb(255, 112, 18))
                        }
                        addView(title); addView(vpn)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "♛"; textSize = 29f; setTextColor(Color.rgb(255, 170, 20))
                    })
                })
                // Approved home composition: large tiger artwork, then one wide action button.
                addView(ImageView(this@MainActivity).apply {
                    setImageResource(R.drawable.tiger_hero)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.BLACK)
                    contentDescription = "Реалистичный тигр с зелёными глазами и огнём"
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(286)
                ).apply { topMargin = dp(0); bottomMargin = dp(0) })
                powerOrb = TextView(this@MainActivity).apply {
                    text = "⏻"; textSize = 55f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.rgb(8, 8, 9))
                        setStroke(dp(3), Color.rgb(255, 112, 18))
                    }
                    elevation = dp(14).toFloat()
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    paint.setShadowLayer(dp(12).toFloat(), 0f, 0f, Color.rgb(255, 112, 18))
                }
                addView(powerOrb, LinearLayout.LayoutParams(dp(104), dp(104)).apply {
                    gravity = android.view.Gravity.CENTER
                    topMargin = dp(-36); bottomMargin = dp(0)
                })
                addView(status.apply {
                    text = "●  Отключено"; textSize = 17f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.LTGRAY)
                    setPadding(dp(8), dp(5), dp(8), dp(0))
                })
                connectionTimer = Chronometer(this@MainActivity).apply {
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(Color.WHITE)
                    format = "%s"
                    visibility = View.GONE
                }
                addView(connectionTimer, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(22)
                ))
                addView(connectButton, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(68)
                ).apply {
                    gravity = android.view.Gravity.CENTER
                    topMargin = dp(10); bottomMargin = dp(18)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(10, 10, 12)); cornerRadius = dp(28).toFloat()
                        setStroke(dp(1), Color.rgb(45, 45, 48))
                    }
                    setPadding(dp(16), dp(10), dp(14), dp(10))
                    setOnClickListener {
                        if (activeFree) findBestFreeServers() else servers.performClick()
                    }
                    addView(TextView(this@MainActivity).apply {
                        text = "◎"; textSize = 31f; setTextColor(Color.WHITE)
                        setPadding(0, 0, dp(14), 0)
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        serverCardTitle = TextView(this@MainActivity).apply {
                            text = "Автоматический сервер"; textSize = 16f
                            setTextColor(Color.WHITE)
                        }
                        serverCardSubtitle = TextView(this@MainActivity).apply {
                            text = "Лучший и самый быстрый"; textSize = 12f
                            setTextColor(Color.LTGRAY)
                        }
                        addView(serverCardTitle)
                        addView(serverCardSubtitle)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        serverCardPing = TextView(this@MainActivity).apply {
                            text = ""; textSize = 13f; setTextColor(Color.rgb(0, 230, 55))
                        }
                        addView(serverCardPing)
                        addView(TextView(this@MainActivity).apply {
                            text = "  ›"; textSize = 27f; setTextColor(Color.WHITE)
                        })
                    })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply {
                    topMargin = dp(8); bottomMargin = dp(14)
                })
                addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                servers.visibility = View.GONE
                addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                pingStatus.visibility = View.GONE
                addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                bestFreeButton.visibility = View.GONE
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                checkPing.visibility = View.GONE
                addView(quattroTab, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                quattroTab.visibility = View.GONE
                addView(freeTab, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                freeTab.visibility = View.GONE
                settingsHeader = TextView(this@MainActivity).apply {
                    text = "Настройки и импорт  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(24, 13, 10)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(230, 83, 12))
                    }
                    visibility = View.GONE
                    setOnClickListener {
                        settingsExpanded = !settingsExpanded
                        text = if (settingsExpanded) "Настройки и импорт  ⌄" else "Настройки и импорт  ›"
                        findImportButtonsVisibility()
                    }
                }
                addView(settingsHeader)
                addView(primarySource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importPrimary)
                addView(importDocx)
                addView(freeSource, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(importFree)
                addView(TextView(this@MainActivity).apply {
                    text = "Бесплатные серверы предоставляются сторонними операторами."
                    textSize = 10f; setTextColor(Color.GRAY); gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(12), 0, dp(8))
                })
            })
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.rgb(5, 5, 6))
            fun navItem(symbol: String, label: String, action: () -> Unit, selected: Boolean = false) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    addView(TextView(this@MainActivity).apply {
                        text = symbol; textSize = 23f; gravity = android.view.Gravity.CENTER
                        setTextColor(if (selected) Color.rgb(255, 112, 18) else Color.LTGRAY)
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = label; textSize = 11f; gravity = android.view.Gravity.CENTER
                        setTextColor(if (selected) Color.rgb(255, 112, 18) else Color.LTGRAY)
                    })
                    setOnClickListener { action() }
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            navItem("⌂", "Главная", { scroll.smoothScrollTo(0, 0) }, true)
            navItem("▤", "Серверы", { scroll.post { scroll.smoothScrollTo(0, servers.top) }; servers.performClick() })
            navItem("⚙", "Настройки", {
                settingsExpanded = true
                settingsHeader.visibility = View.VISIBLE
                findImportButtonsVisibility()
                scroll.post { scroll.smoothScrollTo(0, root.height) }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(66)))
        setContentView(root)
        styleDarkInterface()
        installButtonFeedback()
        setPowerButtonLabel("ПОДКЛЮЧИТЬ")
        connectButton.apply {
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = fireDrawable
            elevation = 12f
            // Subtle fire pulse only while the user presses the power button.
            setOnTouchListener { view, event ->
                val pressed = event.actionMasked == MotionEvent.ACTION_DOWN
                val released = event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                if (pressed || released) {
                    if (pressed && !status.text.toString().startsWith("Подключено")) {
                        fireDrawable.setPressed(true)
                        if (fireAnimator?.isRunning != true) {
                            fireAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
                                duration = 900L
                                repeatCount = ValueAnimator.INFINITE
                                addUpdateListener { fireDrawable.setPhase(it.animatedValue as Float) }
                                start()
                            }
                        }
                    } else if (released && !status.text.toString().startsWith("Подключено")) {
                        fireAnimator?.cancel()
                        fireAnimator = null
                        fireDrawable.setPressed(false)
                        fireDrawable.setPhase(0f)
                    }
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
                    fireDrawable.invalidateSelf()
                    view.elevation = targetElevation
                }
                false
            }
        }
        activeFree = prefs.getBoolean("active_free_mode", false)
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
    }

    private fun updateServerCard(connected: Boolean) {
        if (!::serverCardTitle.isInitialized) return
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (connected && node != null) {
            serverCardTitle.text = node.name
            serverCardSubtitle.text = if (activeFree) "Бесплатный резерв" else "Защищённое соединение"
            val ping = Regex("(\\d+)\\s*мс").find(pingStatus.text.toString())?.groupValues?.get(1)
            serverCardPing.text = ping?.let { "$it мс" } ?: ""
        } else {
            serverCardTitle.text = "Автоматический сервер"
            serverCardSubtitle.text = "Лучший и самый быстрый"
            serverCardPing.text = ""
        }
    }

    private fun updatePowerOrb(connected: Boolean) {
        if (!::powerOrb.isInitialized) return
        val accent = if (connected) Color.rgb(0, 255, 48) else Color.rgb(255, 112, 18)
        powerOrb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (connected) Color.rgb(0, 34, 10) else Color.rgb(8, 8, 9))
            setStroke(dp(3), accent)
        }
        powerOrb.paint.setShadowLayer(dp(if (connected) 20 else 10).toFloat(), 0f, 0f, accent)
        powerOrb.elevation = dp(if (connected) 20 else 12).toFloat()
        powerOrb.invalidate()
    }

    private fun setPowerButtonLabel(label: String) {
        val full = "⏻   $label"
        val styled = SpannableString(full)
        styled.setSpan(
            ForegroundColorSpan(Color.rgb(255, 112, 18)),
            0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        connectButton.text = styled
    }

    // Flame tongues move continuously only while VPN is genuinely connected.
    private fun igniteConnectButton() {
        fireDrawable.setPressed(false)
        fireDrawable.setConnected(true)
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
        fireDrawable.setPressed(false)
        fireDrawable.setConnected(false)
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
        val scroll = content.getChildAt(0) as? android.widget.ScrollView ?: return
        val page = scroll.getChildAt(0) as? ViewGroup ?: return
        primarySource.visibility = if (settingsExpanded && !activeFree) View.VISIBLE else View.GONE
        freeSource.visibility = if (settingsExpanded && activeFree) View.VISIBLE else View.GONE
        for (i in 0 until page.childCount) {
            val child = page.getChildAt(i)
            if (child is Button) {
                val label = child.text.toString()
                val show = settingsExpanded && (
                    (!activeFree && (label == "Импортировать Quattro" || label == "Выбрать DOCX Quattro")) ||
                    (activeFree && label == "Импортировать бесплатные серверы")
                )
                if (label.startsWith("Импортировать") || label == "Выбрать DOCX Quattro") {
                    child.visibility = if (show) View.VISIBLE else View.GONE
                }
                if (label == "Проверить пинг") child.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
            }
        }
        pingStatus.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
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
        status.text = "Автопоиск: проверяю до 300 серверов…"
        // Public free nodes expire frequently. Check a much larger portion of the
        // imported catalogue and allow slow handshakes, rather than rejecting all
        // candidates after one short 25-second window.
        val candidates = freeNodes.take(300)
        Thread {
            val pool = Executors.newFixedThreadPool(8)
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
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
                var completed = 0
                while (completed < candidates.size && System.nanoTime() < deadline && checked.size < 8) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    completed++
                    runCatching { future.get() }.getOrNull()?.let { checked.add(it) }
                    if (completed % 20 == 0) {
                        val done = completed
                        runOnUiThread {
                            if (scanningFree) status.text = "Автопоиск: проверено $done из ${candidates.size}…"
                        }
                    }
                    if (checked.size >= 5) break
                }
                val fastest = checked.sortedBy { it.second }
                runOnUiThread {
                    if (fastest.isEmpty()) {
                        status.text = "Не найдено рабочих серверов: проверено $completed из ${candidates.size}. Попробуй обновить список позже."
                    } else {
                        freeNodes = fastest.map { it.first }
                        saveNodes("free_nodes", freeNodes)
                        prefs.edit().putInt("free_selected", 0).apply()
                        val labels = fastest.map { pair -> "${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${fastest.first().second} мс"
                        status.text = "Найдено рабочих серверов: ${fastest.size}. Лучший выбран."
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
        connectButton.isEnabled = true
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