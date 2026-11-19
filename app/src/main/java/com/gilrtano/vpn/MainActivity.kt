package com.gilrtano.vpn

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.PackageInstaller
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
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.net.Uri
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var powerOrb: PowerOrbButtonView
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
    private lateinit var autoTab: Button
    private var autoMode = true
    private var buttonShellAnimator: ValueAnimator? = null
    private var activeMode: String = "auto"
    private lateinit var bestFreeButton: Button
    private lateinit var checkPing: Button
    private lateinit var updateButton: Button
    private val releaseUpdater by lazy { ReleaseUpdater(this) }
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private lateinit var modeSelectorRow: LinearLayout
    private lateinit var pingMetric: TextView
    private lateinit var protocolMetric: TextView
    private lateinit var connectButton: PowerOrbButtonView
    @Volatile private var scanningFree = false
    private var activeFree = false
    private var settingsExpanded = false
    private var quattroNodes: List<ImportedNode> = emptyList()
    private var freeNodes: List<ImportedNode> = emptyList()
    private var pendingConfig: String? = null
    private var pendingFallbackConfig: String? = null
    private var pendingPreferredConfig: String? = null
    private var pendingAutomatic = false
    private val requestCode = 4101
    private val docxRequestCode = 4102
    private val documentRequestCode = 4104

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val activeNodes: List<ImportedNode>
        get() = if (activeFree) freeNodes else quattroNodes

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
            status.text = when {
                message.startsWith("Подключено") || message.startsWith("Возобновлено") -> "●  Подключено"
                message == "Отключено" -> "●  Отключено"
                else -> message
            }
            if (message.startsWith("Подключено") || message.startsWith("Возобновлено")) {
                val resumed = message.startsWith("Возобновлено")
                if (message.contains("бесплатный резерв")) activeFree = true
                else if (message.contains("Quattro")) activeFree = false
                refreshMode()
                status.setTextColor(Color.rgb(36, 255, 72))
                updatePowerOrb(true)
                if (::connectionTimer.isInitialized) {
                    if (!resumed) connectionTimer.base = SystemClock.elapsedRealtime()
                    connectionTimer.visibility = View.VISIBLE
                    connectionTimer.start()
                }
                updateServerCard(true)
                igniteConnectButton()
            } else if (message == "Приостановлено") {
                status.text = "Ⅱ  Приостановлено"
                status.setTextColor(Color.LTGRAY)
                updatePowerOrb(PowerOrbButtonView.State.SUSPENDED)
                if (::connectionTimer.isInitialized) connectionTimer.stop()
                setPowerButtonLabel("Ⅱ  ВОЗОБНОВИТЬ")
                connectButton.contentDescription = "Возобновить VPN"
            } else if (message == "Отключено" || message.startsWith("Ошибка")) {
                status.setTextColor(Color.LTGRAY)
                updatePowerOrb(false)
                if (::connectionTimer.isInitialized) {
                    connectionTimer.stop()
                    connectionTimer.visibility = View.GONE
                }
                updateServerCard(false)
                resetConnectButtonGlow()
            } else if (message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray") || message.startsWith("Переключаю")) {
                status.setTextColor(Color.rgb(255, 166, 55))
                connectButton.setState(PowerOrbButtonView.State.CONNECTING)
                // Keep the ignition animation visible while the tunnel is starting.
            }
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Xray") || message.startsWith("Переключаю") || message.startsWith("Приостанавливаю") || message.startsWith("Возобновляю")
            // The single power button remains usable while connecting so it can cancel,
            // and while connected so it can disconnect.
            connectButton.isEnabled = true
            setPowerButtonLabel(
                if (message.startsWith("Подключено") || message.startsWith("Возобновлено")) "Ⅱ  ПАУЗА"
                else if (message == "Приостановлено") "Ⅱ  ВОЗОБНОВИТЬ"
                else if (message.startsWith("Отключаю")) "ОТКЛЮЧЕНИЕ…"
                else if (message.startsWith("Переключаю")) "ПЕРЕКЛЮЧЕНИЕ…"
                else if (message.startsWith("Приостанавливаю")) "ПАУЗА…"
                else if (message.startsWith("Возобновляю")) "ВОЗОБНОВЛЕНИЕ…"
                else if (busy) "ПОДКЛЮЧЕНИЕ…"
                else "▶ ПОДКЛЮЧИТЬ"
            )
            connectButton.contentDescription = when {
                message.startsWith("Подключено") || message.startsWith("Возобновлено") -> "Приостановить VPN"
                message == "Приостановлено" -> "Возобновить VPN"
                busy -> "Отменить подключение"
                else -> "Подключить VPN"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quattroNodes = restoreNodes("quattro_nodes")
        freeNodes = restoreNodes("free_nodes")

        status = TextView(this).apply { text = "Отключено"; textSize = 14f; maxLines = 2; gravity = android.view.Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(4)) }
        pingStatus = TextView(this).apply { text = "Пинг: —"; textSize = 15f }
        primarySource = EditText(this).apply {
            hint = "Ссылка на подписку"
            minLines = 2
            maxLines = 5
            setText(prefs.getString("subscription_url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        freeSource = EditText(this).apply {
            hint = "Ссылка бесплатного резерва"
            minLines = 2
            maxLines = 3
            setText(prefs.getString("free_subscription_url", "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"))
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
            text = "ПОДПИСКА"
            setOnClickListener { switchMode(false) }
        }
        freeTab = Button(this).apply {
            text = "FREE"
            setOnClickListener { switchMode(true) }
        }
        autoTab = Button(this).apply {
            text = "АВТО"
            setOnClickListener { switchToAuto() }
        }

        val importPrimary = Button(this).apply {
            text = "Добавить подписку"
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
        connectButton = PowerOrbButtonView(this).apply {
            // The power glyph is drawn by PowerOrbButtonView; the caption lives only in the foreground overlay.
            text = ""
            setOnClickListener {
                val currentStatus = status.text.toString()
                val isConnected = currentStatus.contains("Подключено")
                val isSuspended = currentStatus.contains("Приостановлено")
                val isConnecting = currentStatus.startsWith("Подключаю") ||
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Xray") ||
                    currentStatus.startsWith("Переключаю") || currentStatus.startsWith("Приостанавливаю") ||
                    currentStatus.startsWith("Возобновляю")
                if (isConnected) {
                    status.text = "Приостанавливаю VPN…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_PAUSE))
                    return@setOnClickListener
                }
                if (isSuspended) {
                    status.text = "Возобновляю VPN…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_RESUME))
                    return@setOnClickListener
                }
                if (isConnecting) {
                    pendingConfig = null
                    status.text = "Отменяю подключение…"
                    startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                        .setAction(GilrTanoVpnService.ACTION_STOP))
                    return@setOnClickListener
                }
                if (autoMode) {
                    activeFree = quattroNodes.isEmpty() && freeNodes.isNotEmpty()
                    prefs.edit().putBoolean("active_free_mode", activeFree).apply()
                    refreshMode()
                    if (quattroNodes.isEmpty() && freeNodes.isEmpty()) {
                        status.text = "Ищу бесплатный резерв…"
                        refreshFreeServersAndFindBest(autoConnectWhenReady = true)
                        return@setOnClickListener
                    }
                }
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                        val fallbackConfig = if (autoMode) {
                            (if (activeFree) quattroNodes.firstOrNull() else freeNodes.firstOrNull())
                                ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        } else null
                        val preferredConfig = if (autoMode) {
                            quattroNodes.firstOrNull()?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
                        } else null
                        pendingConfig = config
                        pendingFallbackConfig = fallbackConfig
                        pendingPreferredConfig = preferredConfig
                        pendingAutomatic = autoMode
                        connectButton.isEnabled = true
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config, fallbackConfig, preferredConfig, autoMode)
                    } catch (e: Exception) {
                        pendingConfig = null
                        connectButton.isEnabled = true
                        status.text = "Профиль не поддерживается: ${e.message}"
                    }
                }
            }
        }
        connectButton.setOnLongClickListener {
            val currentStatus = status.text.toString()
            if (currentStatus.contains("Подключено") || currentStatus.contains("Приостановлено") ||
                currentStatus.contains("Приостанавливаю") || currentStatus.contains("Возобновляю")) {
                status.text = "Отключаю VPN…"
                startService(Intent(this@MainActivity, GilrTanoVpnService::class.java)
                    .setAction(GilrTanoVpnService.ACTION_STOP))
                true
            } else false
        }
        bestFreeButton = Button(this).apply {
            text = "Найти лучший бесплатный сервер (проверка интернета)"
            setOnClickListener { refreshFreeServersAndFindBest() }
        }
        checkPing = Button(this).apply {
            text = "Проверить пинг"
            setOnClickListener { measureSelectedPing() }
        }
        updateButton = Button(this).apply {
            text = "Проверить обновления"
            setOnClickListener { checkForAppUpdate() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setPadding(dp(18), dp(12), dp(18), dp(18))
                // Верхняя зона: только кнопка уведомлений, без логотипа и короны.
                addView(FrameLayout(this@MainActivity).apply {
                    val notificationButton = TextView(this@MainActivity).apply {
                        text = "🔔"
                        textSize = 25f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.WHITE)
                        contentDescription = "Уведомления"
                        background = GradientDrawable().apply {
                            setColor(Color.argb(145, 10, 10, 13))
                            cornerRadius = dp(24).toFloat()
                            setStroke(dp(1), Color.rgb(125, 34, 39))
                        }
                        setPadding(dp(14), dp(7), dp(14), dp(7))
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Уведомления")
                                .setMessage("Новых уведомлений нет")
                                .setPositiveButton("ОК", null)
                                .show()
                        }
                    }
                    addView(notificationButton, FrameLayout.LayoutParams(dp(52), dp(46),
                        android.view.Gravity.TOP or android.view.Gravity.END))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
                // Hero composition: full-width tiger artwork with the live VPN power control layered over it.
                val heroFrame = FrameLayout(this@MainActivity).apply {
                    clipChildren = false
                    clipToPadding = false
                }
                // Flame and press effects are drawn in the button view itself; no SurfaceView overlay.
                // Keep the original artwork visible and interactive above the flame.
                connectButton.useGlArtwork = false
                connectButton.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
                heroFrame.addView(connectButton, FrameLayout.LayoutParams(dp(174), dp(174),
                    android.view.Gravity.CENTER).apply {
                    topMargin = dp(150)
                })
                addView(heroFrame, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(500)
                ).apply { topMargin = dp(2); bottomMargin = dp(4) })
                powerOrb = connectButton
                connectButton.contentDescription = "Кнопка питания VPN"
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
                    visibility = View.VISIBLE
                    onChronometerTickListener = Chronometer.OnChronometerTickListener { timer ->
                        if (status.text.toString().contains("Подключено")) {
                            setPowerButtonLabel("■  ${timer.text}")
                        }
                    }
                }
                // Connection duration is displayed in the lower live-statistics panel.
                // The timer remains driven by the existing VPN status receiver.
                // The central glowing orb is the primary connect/disconnect control.
                // Keep the accessible action button as the click target, but remove its
                // duplicate pill from the visual layout to match the approved reference.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        intArrayOf(
                            Color.rgb(72, 12, 18),
                            Color.rgb(20, 10, 14),
                            Color.rgb(7, 13, 10),
                            Color.rgb(10, 48, 20)
                        )
                    ).apply {
                        cornerRadius = dp(24).toFloat()
                        setStroke(dp(1), Color.rgb(115, 190, 82))
                    }
                    // Raised glass-like server card with warm red and cool green edge lighting.
                    elevation = dp(16).toFloat()
                    setPadding(dp(16), dp(10), dp(14), dp(10))
                    setOnClickListener { showServerPicker() }
                    addView(TextView(this@MainActivity).apply {
                        text = "🌐"; textSize = 25f; setTextColor(Color.WHITE)
                        setPadding(0, 0, dp(14), 0)
                    })
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        serverCardTitle = TextView(this@MainActivity).apply {
                            text = "Автоматический сервер"; textSize = 16f
                            setTextColor(Color.WHITE)
                        }
                        serverCardSubtitle = TextView(this@MainActivity).apply {
                            text = "Автовыбор"; textSize = 12f
                            setTextColor(Color.LTGRAY)
                        }
                        addView(serverCardTitle)
                        addView(serverCardSubtitle)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "AUTO PORTAL"
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.rgb(75, 255, 105))
                        setPadding(dp(10), dp(7), dp(10), dp(7))
                        background = GradientDrawable().apply {
                            setColor(Color.rgb(5, 28, 12))
                            cornerRadius = dp(18).toFloat()
                            setStroke(dp(1), Color.rgb(45, 225, 78))
                        }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        leftMargin = dp(8); rightMargin = dp(6)
                    })
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
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(70)).apply {
                    topMargin = dp(8); bottomMargin = dp(14)
                })
                // Secondary live panel, matching FlClashX's modular dashboard-card approach.
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(9, 9, 12))
                        cornerRadius = dp(22).toFloat()
                        setStroke(dp(1), Color.rgb(105, 28, 34))
                    }
                    setPadding(dp(6), dp(12), dp(6), dp(12))
                    fun infoCell(label: String, value: View): LinearLayout =
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER
                            addView(TextView(this@MainActivity).apply {
                                text = label; textSize = 11f; setTextColor(Color.LTGRAY)
                            })
                            addView(value)
                        }
                    val durationValue = connectionTimer.apply {
                        textSize = 15f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(Color.WHITE)
                        format = "%s"
                    }
                    val protocolValue = TextView(this@MainActivity).apply {
                        text = "ПОДПИСКА"; textSize = 15f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        setTextColor(Color.rgb(55, 255, 95))
                        protocolMetric = this
                    }
                    addView(infoCell("Время соединения", durationValue),
                        LinearLayout.LayoutParams(0, dp(48), 1f))
                    addView(View(this@MainActivity).apply {
                        setBackgroundColor(Color.rgb(65, 55, 58))
                    }, LinearLayout.LayoutParams(dp(1), dp(42)))
                    addView(infoCell("Протокол", protocolValue),
                        LinearLayout.LayoutParams(0, dp(48), 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82)).apply {
                    topMargin = dp(10); bottomMargin = dp(10)
                })
                addView(servers, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
                servers.visibility = View.GONE
                addView(pingStatus.apply { gravity = android.view.Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                pingStatus.visibility = View.GONE
                addView(bestFreeButton, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                bestFreeButton.visibility = View.GONE
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                checkPing.visibility = View.GONE
                modeSelectorRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    addView(quattroTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
                    addView(freeTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    addView(autoTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
                    visibility = View.GONE
                }
                settingsHeader = TextView(this@MainActivity).apply {
                    text = "Настройки и импорт  ›"; textSize = 20f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(Color.WHITE); setPadding(dp(16), dp(14), dp(12), dp(12))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(14, 8, 9)); cornerRadius = dp(18).toFloat()
                        setStroke(dp(1), Color.rgb(150, 24, 24))
                    }
                    visibility = View.GONE
                    setOnClickListener {
                        settingsExpanded = !settingsExpanded
                        text = if (settingsExpanded) "Настройки и импорт  ⌄" else "Настройки и импорт  ›"
                        findImportButtonsVisibility()
                    }
                }
                // Subscription management and imports live exclusively in the
                // dedicated «Профили» and «Настройки» tabs. Keep the dashboard
                // focused on connection status and live network information.
            })
        }
        // Respect Android system bars so the brand header and bottom navigation
        // never sit underneath the clock or gesture handle.
        // Draw the tiger artwork behind both Android system bars as well as app content.
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(android.view.WindowInsets.Type.systemBars())
            } else {
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val pageHost = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val serverPageScroll = android.widget.ScrollView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val settingsPageScroll = android.widget.ScrollView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        fun page(title: String): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(18), dp(22), dp(18), dp(28))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 25f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(18))
            })
        }
        fun card(): LinearLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(Color.rgb(13, 13, 17))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(90, 28, 34))
            }
        }
        fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        val serverPage = page("Прокси")
        val serverState = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY) }
        serverPage.addView(serverState)
        val serverDelays = mutableMapOf<String, Long>()
        var testingAllServers = false
        val testAllButton = actionButton("↻  ПРОВЕРИТЬ ВСЕ СЕРВЕРЫ") { testAllServers(serverDelays, { testingAllServers }, { testingAllServers = it; testAllButton.text = if (it) "ПРОВЕРКА… " else "↻  ПРОВЕРИТЬ ВСЕ СЕРВЕРЫ"; testAllButton.isEnabled = !it }, { updateServerPage() }) }
        serverPage.addView(testAllButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { bottomMargin = dp(10) })
        val proxySearch = EditText(this).apply {
            hint = "Поиск серверов"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 31, 35)); cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(65, 67, 76))
            }
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        serverPage.addView(proxySearch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(12) })
        val serverRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        serverPage.addView(serverRows)
        fun updateServerPage() {
            serverState.text = "Режим: " + if (autoMode) "AUTO · Quattro с бесплатным резервом" else if (activeFree) "FREE" else "Подписка"
            serverRows.removeAllViews()
            val query = proxySearch.text.toString().trim().lowercase()
            val nodes = activeNodes.mapIndexed { index, node -> index to node }.filter { it.second.name.lowercase().contains(query) }
            if (nodes.isEmpty()) {
                serverRows.addView(TextView(this).apply {
                    text = if (activeNodes.isEmpty()) "Нет серверов. Добавьте профиль во вкладке «Профили»." else "Ничего не найдено"
                    setTextColor(Color.LTGRAY); setPadding(0, dp(18), 0, dp(18))
                })
            } else nodes.chunked(2).forEach { pair ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                pair.forEach { (index, node) ->
                    val selected = index == servers.selectedItemPosition
                    val tile = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(dp(13), dp(13), dp(10), dp(13))
                        background = GradientDrawable().apply {
                            setColor(if (selected) Color.rgb(8, 35, 17) else Color.rgb(34, 35, 39))
                            cornerRadius = dp(20).toFloat()
                            setStroke(dp(1), if (selected) Color.rgb(42, 235, 78) else Color.rgb(61, 43, 48))
                        }
                        setOnClickListener {
                            servers.setSelection(index)
                            prefs.edit().putInt(if (activeFree) "free_selected" else "quattro_selected", index).apply()
                            serverCardTitle.text = node.name
                            serverCardSubtitle.text = if (activeFree) "FREE" else "Подписка"
                            serverCardPing.text = ""
                            pingStatus.text = "Пинг: —"
                            updateServerPage()
                        }
                        addView(TextView(this@MainActivity).apply {
                            val delay = serverDelays[node.uri]
                            text = (if (selected) "✓  " else "") + node.name + when {
                                delay == null -> ""
                                delay > 0L -> "   ·   ${delay} мс"
                                else -> "   ·   тайм-аут"
                            }
                            textSize = 14f; maxLines = 3; setTextColor(if (delay != null && delay > 0L) Color.rgb(65, 235, 105) else Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = node.uri.substringBefore("://").uppercase()
                            textSize = 11f; setTextColor(Color.rgb(169, 184, 255)); setPadding(0, dp(8), 0, 0)
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = if (selected) "ВЫБРАН" else "Нажмите для выбора"
                            textSize = 10f; setTextColor(if (selected) Color.rgb(55, 245, 90) else Color.LTGRAY)
                            setPadding(0, dp(7), 0, 0)
                        })
                    }
                    row.addView(tile, LinearLayout.LayoutParams(0, dp(116), 1f).apply {
                        leftMargin = dp(3); rightMargin = dp(3); topMargin = dp(4); bottomMargin = dp(4)
                    })
                }
                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(116), 1f))
                serverRows.addView(row)
            }
        }
        proxySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateServerPage() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        val serverModes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        serverModes.addView(actionButton("ПОДПИСКА") { switchMode(false); updateServerPage() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        serverModes.addView(actionButton("FREE") { switchMode(true); updateServerPage() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        serverPage.addView(serverModes)
        serverPage.addView(actionButton("Найти лучший бесплатный сервер") { refreshFreeServersAndFindBest() })
        val profilesPage = page("Профили")
        fun importRow(title: String, subtitle: String, symbol: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(18), dp(10), dp(18))
                background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(32, 32, 32)) }
                isClickable = true; isFocusable = true; setOnClickListener { action() }
            }
            row.addView(TextView(this).apply { text = symbol; textSize = 31f; gravity = android.view.Gravity.CENTER; setTextColor(Color.WHITE); background = GradientDrawable().apply { setColor(Color.rgb(37,37,37)); cornerRadius = dp(18).toFloat() } }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { rightMargin = dp(18) })
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            labels.addView(TextView(this).apply { text = title; textSize = 20f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE) })
            labels.addView(TextView(this).apply { text = subtitle; textSize = 15f; setTextColor(Color.LTGRAY); setPadding(0, dp(5), 0, 0) })
            row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply { text = "›"; textSize = 34f; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT))
            profilesPage.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(1) })
            profilesPage.addView(View(this).apply { setBackgroundColor(Color.rgb(28,28,28)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
        importRow("Ссылка на подписку", "Вставить URL подписки", "🔗") {
            val field = EditText(this@MainActivity).apply { hint = "https://…"; setSingleLine(true); setText(prefs.getString("subscription_url", "")) }
            AlertDialog.Builder(this@MainActivity).setTitle("Ссылка на подписку").setView(field).setNegativeButton("Отмена", null).setPositiveButton("Добавить") { _, _ ->
                val value = field.text.toString().trim(); prefs.edit().putString("subscription_url", value).apply(); importFrom(value, false)
            }.show()
        }
        importRow("Сканировать QR-код", "Открыть камеру телефона и считать код", "▣") {
            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 4105)
            } else {
                startQrScanner()
            }
        }
        importRow("Документ", "Выбрать файл из памяти телефона", "▤") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"; putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "application/yaml", "text/yaml", "application/x-yaml", "application/octet-stream", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }
            startActivityForResult(pick, documentRequestCode)
        }
        profilesPage.addView(actionButton("Обновить подписку") { val value = prefs.getString("subscription_url", "").orEmpty(); if (value.isNotBlank()) importFrom(value, false) else Toast.makeText(this@MainActivity, "Сначала добавьте ссылку на подписку", Toast.LENGTH_LONG).show() })
        val settingsPage = page("Настройки")
        settingsPage.addView(actionButton("Проверить обновления") { checkForAppUpdate() })
        settingsPage.addView(TextView(this).apply {
            text = "Управление профилями, подписками и импортом находится во вкладке «Профили»."
            textSize = 12f; setTextColor(Color.LTGRAY); setPadding(0, dp(18), 0, 0)
        })
        fun wrapPage(content: View): android.widget.ScrollView =
            android.widget.ScrollView(this).apply {
                isFillViewport = true
                addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        // Only the Home page is visible on first launch. Keep the other tab
        // roots hidden so their profile/import controls cannot bleed through
        // the transparent tiger dashboard before the user opens that tab.
        val proxyPageView = wrapPage(serverPage).apply { visibility = View.GONE }
        val profilesPageView = wrapPage(profilesPage).apply { visibility = View.GONE }
        val settingsPageView = wrapPage(settingsPage).apply { visibility = View.GONE }
        pageHost.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageHost.addView(proxyPageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageHost.addView(profilesPageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageHost.addView(settingsPageView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        var currentPageIndex = 0
        var pageTransitionGeneration = 0
        fun showPage(index: Int) {
            if (index !in 0 until pageHost.childCount || index == currentPageIndex) return
            val generation = ++pageTransitionGeneration
            val previousIndex = currentPageIndex
            val direction = if (index > previousIndex) 1f else -1f
            val outgoing = pageHost.getChildAt(previousIndex)
            val incoming = pageHost.getChildAt(index)
            outgoing.animate().cancel()
            incoming.animate().cancel()
            incoming.visibility = View.VISIBLE
            val travel = (pageHost.width.takeIf { it > 0 }
                ?: resources.displayMetrics.widthPixels).toFloat() * 0.25f
            incoming.translationX = direction * travel
            incoming.alpha = 0f
            outgoing.animate().translationX(-direction * travel).alpha(0f)
                .setDuration(450L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .withEndAction {
                    if (generation == pageTransitionGeneration) {
                        outgoing.visibility = View.GONE
                        outgoing.translationX = 0f
                        outgoing.alpha = 1f
                    }
                }.start()
            incoming.animate().translationX(0f).alpha(1f)
                .setDuration(450L)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()
            currentPageIndex = index
            if (index == 1) updateServerPage()
        }
        root.addView(pageHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val navigationDock = FlClashNavigationDock(this) { destination ->
            showPage(destination)
        }
        navigationDock.setSelectedIndex(0, false)
        root.addView(navigationDock, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(74)
        ).apply {
            leftMargin = dp(14); rightMargin = dp(14); bottomMargin = dp(8)
        })
        // Полноэкранный фон тигра. CENTER_CROP сохраняет пропорции без чёрных полос.
        val screenFrame = FrameLayout(this).apply {
            // Background is the bottom-most full-window layer; CENTER_CROP preserves
            // the source aspect ratio and fills the screen without letterboxing.
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.tiger_hero)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "Полноэкранный фон GilrTano"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(screenFrame)
        styleDarkInterface()
        installButtonFeedback()
        setPowerButtonLabel("ПОДКЛЮЧИТЬ")
        connectButton.apply {
            // Icon is drawn in the upper half by PowerOrbButtonView; keep a
            // compact, centered caption in its own lower zone.
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            setPadding(dp(10), dp(8), dp(10), dp(24))
            includeFontPadding = false
            maxLines = 1
            setTextColor(Color.WHITE)
            isAllCaps = false
            // The PNG frames provide the complete button artwork; no extra oval border.
            background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            elevation = dp(18).toFloat()
            minWidth = dp(174)
            minHeight = dp(174)
        }
        activeMode = prefs.getString("connection_mode", "auto") ?: "auto"
        autoMode = false
        activeMode = if (activeFree) "free" else "subscription"
        activeFree = prefs.getBoolean("active_free_mode", false)
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
        handleSubscriptionIntent(intent)
    }

    private fun showServerPicker() {
        val nodes = activeNodes
        if (nodes.isEmpty()) {
            settingsExpanded = true
            findImportButtonsVisibility()
            val content = findViewById<ViewGroup>(android.R.id.content)
            val root = content.getChildAt(0) as? ViewGroup
            val scroll = root?.getChildAt(0) as? android.widget.ScrollView
            scroll?.post { scroll.smoothScrollTo(0, settingsHeader.top) }
            Toast.makeText(this, "Добавь сервер в настройках", Toast.LENGTH_LONG).show()
            return
        }
        val names = nodes.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Выбор сервера")
            .setSingleChoiceItems(names, servers.selectedItemPosition) { dialog, which ->
                servers.setSelection(which)
                val key = if (activeFree) "free_selected" else "quattro_selected"
                prefs.edit().putInt(key, which).apply()
                serverCardTitle.text = nodes[which].name
                serverCardSubtitle.text = if (activeFree) "Бесплатный" else "Quattro"
                serverCardPing.text = ""
                pingStatus.text = "Пинг: —"
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun updateServerCard(connected: Boolean) {
        if (!::serverCardTitle.isInitialized) return
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (connected && node != null) {
            serverCardTitle.text = node.name
            serverCardSubtitle.text = if (activeFree) "Бесплатный резерв" else "Подключено"
            val ping = Regex("(\\d+)\\s*мс").find(pingStatus.text.toString())?.groupValues?.get(1)
            serverCardPing.text = ping?.let { "$it мс" } ?: ""
        } else {
            serverCardTitle.text = if (quattroNodes.isEmpty() && freeNodes.isEmpty()) "Сервер не добавлен" else "Автоматический сервер"
            serverCardSubtitle.text = if (quattroNodes.isEmpty() && freeNodes.isEmpty()) "Добавьте подписку в настройках" else "Автовыбор"
            serverCardPing.text = ""
        }
    }

    private fun updatePowerOrb(connected: Boolean) {
        updatePowerOrb(
            if (connected) PowerOrbButtonView.State.CONNECTED
            else PowerOrbButtonView.State.DISCONNECTED
        )
    }

    private fun updatePowerOrb(next: PowerOrbButtonView.State) {
        if (!::powerOrb.isInitialized) return
        val connected = next == PowerOrbButtonView.State.CONNECTED
        val suspended = next == PowerOrbButtonView.State.SUSPENDED
        powerOrb.setState(next)
        powerOrb.setTextColor(if (connected) Color.rgb(90, 255, 120) else if (suspended) Color.LTGRAY else Color.WHITE)
        powerOrb.elevation = dp(if (connected) 20 else 12).toFloat()

        // Keep the button background transparent. The artwork in the nine PNG
        // frames is the only source of rings and borders.
        powerOrb.background = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        powerOrb.invalidate()
    }

    private fun setPowerButtonLabel(label: String) {
        if (!::connectButton.isInitialized) return
        connectButton.setCaption(label)
    }

    private fun igniteConnectButton() { connectButton.elevation = dp(12).toFloat() }
    private fun resetConnectButtonGlow() { connectButton.elevation = dp(10).toFloat() }
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

    private fun checkForAppUpdate() {
        updateButton.isEnabled = false
        updateButton.text = "Проверяем обновления…"
        updateExecutor.execute {
            try {
                val release = releaseUpdater.latestRelease()
                val installed = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Проверить обновления"
                    if (release == null || !releaseUpdater.isNewer(release.tag, installed)) {
                        AlertDialog.Builder(this)
                            .setTitle("Обновления")
                            .setMessage("Установлена последняя версия GilrTano VPN ($installed).")
                            .setPositiveButton("Понятно", null)
                            .show()
                    } else {
                        val notes = release.notes.trim().ifBlank { "Исправления ошибок и улучшения стабильности." }
                        AlertDialog.Builder(this)
                            .setTitle("Доступна новая версия ${release.tag}")
                            .setMessage("Установлена версия $installed\n\nЧто нового:\n$notes\n\nAPK будет проверен по SHA-256 перед установкой.")
                            .setPositiveButton("Скачать и установить") { _, _ ->
                                downloadAndInstallUpdate(release)
                            }
                            .setNegativeButton("Позже", null)
                            .show()
                    }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    updateButton.isEnabled = true
                    updateButton.text = "Проверить обновления"
                    AlertDialog.Builder(this)
                        .setTitle("Не удалось проверить обновления")
                        .setMessage("Проверьте подключение к интернету и попробуйте ещё раз.\n\nПричина: ${error.message ?: "неизвестная ошибка"}")
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
        }
    }

    private fun downloadAndInstallUpdate(release: AppRelease) {
        val progress = ProgressDialog(this).apply {
            setTitle("Загрузка обновления")
            setMessage("Загружаем новую версию GilrTano VPN…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            progress = 0
            setCancelable(false)
            show()
        }
        val apk = java.io.File(cacheDir, "GilrTano-${release.tag}.apk")
        updateExecutor.execute {
            try {
                releaseUpdater.download(release, apk) { value ->
                    runOnUiThread { if (!isFinishing) progress.progress = value }
                }
                runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Обновление загружено")
                        .setMessage("Файл успешно загружен и проверен. Установить версию ${release.tag}?")
                        .setPositiveButton("Установить") { _, _ ->
                            if (android.os.Build.VERSION.SDK_INT >= 26 &&
                                !packageManager.canRequestPackageInstalls()) {
                                AlertDialog.Builder(this)
                                    .setTitle("Разрешение на установку")
                                    .setMessage("Разрешите GilrTano VPN устанавливать обновления в настройках Android, затем вернитесь сюда и нажмите «Установить» ещё раз.")
                                    .setPositiveButton("Открыть настройки") { _, _ ->
                                        startActivity(Intent(
                                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                            Uri.parse("package:$packageName")
                                        ))
                                    }
                                    .setNegativeButton("Отмена", null)
                                    .show()
                            } else {
                                runCatching { releaseUpdater.install(apk) }.onFailure {
                                    Toast.makeText(this, "Не удалось запустить установку: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton("Позже", null)
                        .show()
                }
            } catch (error: Exception) {
                apk.delete()
                runOnUiThread {
                    progress.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Ошибка обновления")
                        .setMessage("Не удалось загрузить или проверить APK.\n\n${error.message ?: "Неизвестная ошибка"}")
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
        }
    }

    /**
     * Accept FlClash-compatible profile links:
     * clash://install-config?url=<encoded-http(s)-subscription>
     * clashmeta:// and flclash:// use the same format.
     */
    private fun handleSubscriptionIntent(sourceIntent: Intent?) {
        val data = sourceIntent?.data ?: return
        val scheme = data.scheme?.lowercase()
        if (scheme == null || scheme !in setOf("clash", "clashmeta", "flclash")) return
        if (data.host != "install-config") {
            Toast.makeText(this, "Неизвестный формат ссылки профиля", Toast.LENGTH_LONG).show()
            return
        }
        val incoming = data.getQueryParameter("url")?.trim().orEmpty()
        if (incoming.isBlank() || incoming.length > 8192) {
            Toast.makeText(this, "В ссылке нет корректного адреса подписки", Toast.LENGTH_LONG).show()
            return
        }
        val subscriptionUri = runCatching { Uri.parse(incoming) }.getOrNull()
        val subscriptionScheme = subscriptionUri?.scheme?.lowercase()
        if (subscriptionUri == null || subscriptionScheme == null ||
            subscriptionScheme !in setOf("http", "https") || subscriptionUri.host.isNullOrBlank()) {
            Toast.makeText(this, "Подписка должна иметь HTTP- или HTTPS-адрес", Toast.LENGTH_LONG).show()
            return
        }
        activeFree = false
        primarySource.setText(incoming)
        Toast.makeText(this, "Получена ссылка подписки. Загружаю профиль…", Toast.LENGTH_LONG).show()
        importFrom(incoming, false)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        if (intent.action != ACTION_INSTALL_STATUS) {
            handleSubscriptionIntent(intent)
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) startActivity(confirmation)
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(this, "GilrTano VPN успешно обновлён", Toast.LENGTH_LONG).show()
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(this, "Установка обновления не выполнена: ${message ?: "отменено"}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        updateExecutor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.gilrtano.vpn.UPDATE_INSTALL_STATUS"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun styleDarkInterface() {
        val root = findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as? LinearLayout ?: return
        root.setBackgroundColor(Color.rgb(8, 8, 10))
        fun style(view: android.view.View) {
            when (view) {
                is FlClashNavigationDock -> return
                is Button -> {
                    // Preserve the circular hero power control; the other buttons use compact styling.
                    if (view === connectButton || view.contentDescription == "dashboard-nav") return
                    view.setTextColor(Color.WHITE)
                    view.textSize = 13f
                    view.isAllCaps = false
                    view.background = GradientDrawable().apply {
                        setColor(Color.rgb(14, 8, 9))
                        cornerRadius = 18f
                        setStroke(2, Color.rgb(150, 24, 24))
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
                    if (!(::pingMetric.isInitialized && view === pingMetric) &&
                        view.currentTextColor != Color.WHITE) view.setTextColor(Color.LTGRAY)
                }
                is Spinner -> {
                    view.setBackgroundColor(Color.rgb(24, 25, 28))
                }
                is ViewGroup -> for (i in 0 until view.childCount) style(view.getChildAt(i))
            }
        }
        style(root)
    }

    private fun switchToAuto() {
        autoMode = true
        activeMode = "auto"
        prefs.edit().putString("connection_mode", "auto").apply()
        activeFree = quattroNodes.isEmpty() && freeNodes.isNotEmpty()
        refreshMode()
        Toast.makeText(this, "Автовыбор включён: Quattro в приоритете, бесплатный резерв", Toast.LENGTH_SHORT).show()
    }

    private fun switchMode(free: Boolean) {
        autoMode = false
        activeMode = if (free) "free" else "quattro"
        prefs.edit().putString("connection_mode", activeMode).apply()
        if (activeFree == free) { refreshMode(); return }
        val targetNodes = if (free) freeNodes else quattroNodes
        if (targetNodes.isEmpty()) {
            Toast.makeText(
                this,
                if (free) "Сначала найди бесплатные серверы" else "Сначала импортируй Quattro",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val wasConnected = status.text.toString().contains("Подключено")
        activeFree = free
        prefs.edit().putBoolean("active_free_mode", free).apply()
        refreshMode()
        updateServerCard(wasConnected)

        if (wasConnected) {
            val selectedKey = if (free) "free_selected" else "quattro_selected"
            val index = prefs.getInt(selectedKey, 0).coerceIn(0, targetNodes.lastIndex)
            servers.setSelection(index)
            val node = targetNodes[index]
            val config = runCatching { GilrTanoVpnService.xrayConfigFromVless(node.uri) }.getOrElse {
                Toast.makeText(this, "Не удалось подготовить сервер: ${it.message}", Toast.LENGTH_LONG).show()
                return
            }
            status.text = "Переключаю на ${if (free) "бесплатный сервер" else "Quattro"}…"
            startService(Intent(this, GilrTanoVpnService::class.java)
                .setAction(GilrTanoVpnService.ACTION_SWITCH)
                .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config))
        } else {
            Toast.makeText(this, if (free) "Выбран бесплатный резерв" else "Выбран Quattro", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshMode() {
        autoTab.isEnabled = !autoMode
        quattroTab.isEnabled = autoMode || activeFree
        freeTab.isEnabled = autoMode || !activeFree
        autoTab.alpha = if (autoMode) 1f else 0.72f
        quattroTab.alpha = if (!autoMode && !activeFree) 1f else 0.72f
        freeTab.alpha = if (!autoMode && activeFree) 1f else 0.72f
        fun modeBackground(selected: Boolean) = GradientDrawable().apply {
            setColor(if (selected) Color.rgb(54, 24, 12) else Color.rgb(13, 13, 15))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(if (selected) 2 else 1),
                if (selected) Color.rgb(220, 32, 32) else Color.rgb(58, 58, 62))
        }
        quattroTab.background = modeBackground(!autoMode && !activeFree)
        freeTab.background = modeBackground(!autoMode && activeFree)
        autoTab.background = modeBackground(autoMode)
        quattroTab.setTextColor(if (!autoMode && !activeFree) Color.WHITE else Color.LTGRAY)
        freeTab.setTextColor(if (!autoMode && activeFree) Color.WHITE else Color.LTGRAY)
        autoTab.setTextColor(if (autoMode) Color.WHITE else Color.LTGRAY)
        if (::protocolMetric.isInitialized) protocolMetric.text = if (activeFree) "FREE" else "ПОДПИСКА"
        primarySource.visibility = if (activeFree) ViewGroup.GONE else ViewGroup.VISIBLE
        freeSource.visibility = if (activeFree) ViewGroup.VISIBLE else ViewGroup.GONE
        findImportButtonsVisibility()
        val list = activeNodes
        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        val selectedKey = if (activeFree) "free_selected" else "quattro_selected"
        val selected = prefs.getInt(selectedKey, 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isNotEmpty()) servers.setSelection(selected)
        pingStatus.text = "Пинг: —"
        if (!status.text.toString().contains("Подключено")) {
            status.text = "●  Отключено"
            status.setTextColor(Color.LTGRAY)
        }
    }

    private fun findImportButtonsVisibility() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val scroll = root.getChildAt(0) as? android.widget.ScrollView ?: return
        val page = scroll.getChildAt(0) as? ViewGroup ?: return
        settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        modeSelectorRow.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        primarySource.visibility = if (settingsExpanded && !activeFree) View.VISIBLE else View.GONE
        freeSource.visibility = if (settingsExpanded && activeFree) View.VISIBLE else View.GONE
        bestFreeButton.visibility = if (settingsExpanded && activeFree) View.VISIBLE else View.GONE
        checkPing.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
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
            }
        }
        pingStatus.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        updateButton.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
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
                    settingsExpanded = false
                    settingsHeader.text = "Настройки"
                    findImportButtonsVisibility()
                    status.text = "Серверы добавлены"
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

    /**
     * Always refresh public nodes before measuring them. Saved free nodes are only
     * a fallback cache; public endpoints expire and must not be trusted as current.
     */
    private fun refreshFreeServersAndFindBest(autoConnectWhenReady: Boolean = false, retryRound: Int = 0) {
        if (scanningFree) return
        scanningFree = true
        bestFreeButton.isEnabled = false
        status.text = "Обновляю бесплатные серверы…"
        val configured = freeSource.text.toString().trim().ifBlank {
            "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"
        }
        val primaryVless = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/vless.txt"
        val legacyDefault = "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt"
        val sources = if (configured.isBlank() || configured == legacyDefault) {
            listOf(primaryVless, "https://radarhub.top/v2ray.txt",
                "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/vless.txt")
        } else {
            listOf(configured, primaryVless, "https://radarhub.top/v2ray.txt",
                "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/vless.txt")
        }.distinct()
        Thread {
            var imported: List<ImportedNode> = emptyList()
            var successfulSource: String? = null
            // Fetch mirrors simultaneously: a slow/dead feed must not block the
            // other public lists one after another.
            val fetchPool = Executors.newFixedThreadPool(sources.size.coerceAtMost(4))
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, List<ImportedNode>>>(fetchPool)
            try {
                sources.forEach { source ->
                    completion.submit(Callable {
                        source to SubscriptionImporter.load(source)
                    })
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16)
                var finished = 0
                while (finished < sources.size && System.nanoTime() < deadline && imported.isEmpty()) {
                    val remaining = deadline - System.nanoTime()
                    val future = completion.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    finished++
                    val result = runCatching { future.get() }.getOrNull() ?: continue
                    if (result.second.isNotEmpty()) {
                        successfulSource = result.first
                        imported = result.second
                    }
                }
            } finally {
                fetchPool.shutdownNow()
            }
            runOnUiThread {
                scanningFree = false
                bestFreeButton.isEnabled = true
                if (imported.isEmpty()) {
                    val cached = freeNodes
                    if (cached.isEmpty()) {
                        if (autoConnectWhenReady && retryRound < 5) {
                            status.text = "Источники временно недоступны. Повторяю поиск…"
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinishing && !isDestroyed) refreshFreeServersAndFindBest(true, retryRound + 1)
                            }, 2500L)
                        } else {
                            status.text = "Не удалось обновить серверы. Попробуй позже."
                        }
                    } else {
                        status.text = "Источники недоступны. Проверяю сохранённые серверы…"
                        findBestFreeServers(autoConnectWhenReady, retryRound)
                    }
                    return@runOnUiThread
                }
                freeNodes = imported
                activeFree = true
                prefs.edit()
                    .putBoolean("active_free_mode", true)
                    .putString("free_subscription_url", successfulSource)
                    .putInt("free_selected", 0)
                    .apply()
                saveNodes("free_nodes", freeNodes)
                freeSource.setText(successfulSource)
                refreshMode()
                status.text = "Обновлено ${imported.size} серверов. Быстро проверяю доступ…"
                findBestFreeServers(autoConnectWhenReady, retryRound)
            }
        }.start()
    }

    private fun findBestFreeServers(autoConnectWhenReady: Boolean = false, retryRound: Int = 0) {
        if (scanningFree) return
        if (freeNodes.isEmpty()) {
            status.text = "Сначала обнови бесплатный список серверов"
            return
        }
        scanningFree = true
        bestFreeButton.isEnabled = false
        status.text = "Быстрый поиск: проверяю лучшие серверы…"
        // Public free nodes expire frequently. Check a much larger portion of the
        // imported catalogue and allow slow handshakes, rather than rejecting all
        // candidates after one short 25-second window.
        val candidates = freeNodes.take(80)
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
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18)
                var completed = 0
                while (completed < candidates.size && System.nanoTime() < deadline && checked.size < 2) {
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
                    if (checked.size >= 2) break
                }
                val fastest = checked.sortedBy { it.second }
                runOnUiThread {
                    if (fastest.isEmpty()) {
                        // A failed scan is never a connected state: restore every
                        // visual control so the screen cannot remain falsely green.
                        if (autoConnectWhenReady && retryRound < 5) {
                            status.text = "Рабочий сервер пока не найден. Повторяю поиск…"
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!isFinishing && !isDestroyed) refreshFreeServersAndFindBest(true, retryRound + 1)
                            }, 2500L)
                        } else {
                            status.text = "Проверено серверов: $completed из ${candidates.size}. Рабочие пока не ответили."
                        }
                        status.setTextColor(Color.LTGRAY)
                        updatePowerOrb(false)
                        if (::connectionTimer.isInitialized) {
                            connectionTimer.stop()
                            connectionTimer.visibility = View.GONE
                        }
                        updateServerCard(false)
                        resetConnectButtonGlow()
                        setPowerButtonLabel("ПОДКЛЮЧИТЬ")
                        connectButton.isEnabled = true
                    } else {
                        freeNodes = fastest.map { it.first }
                        saveNodes("free_nodes", freeNodes)
                        prefs.edit().putInt("free_selected", 0).apply()
                        val labels = fastest.map { pair -> "${pair.first.name} · ${pair.second} мс" }
                        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
                        servers.setSelection(0)
                        pingStatus.text = "Проверка через Xray: ${fastest.first().second} мс"
                        status.text = "Найдено рабочих серверов: ${fastest.size}. Лучший выбран."
                        if (autoConnectWhenReady) {
                            activeFree = true
                            autoMode = true
                            activeMode = "auto"
                            prefs.edit().putString("connection_mode", "auto").putBoolean("active_free_mode", true).apply()
                            refreshMode()
                            connectAutomaticallySelected()
                        }
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
    private fun connectAutomaticallySelected() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition) ?: return
        try {
            val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
            val fallbackNode = if (activeFree) freeNodes.getOrNull(1) else freeNodes.firstOrNull()
            val fallback = fallbackNode
                ?.let { runCatching { GilrTanoVpnService.xrayConfigFromVless(it.uri) }.getOrNull() }
            pendingConfig = config
            pendingFallbackConfig = fallback
            pendingPreferredConfig = config
            pendingAutomatic = true
            status.text = "Подключаю лучший доступный сервер…"
            val permission = VpnService.prepare(this)
            if (permission != null) startActivityForResult(permission, requestCode)
            else startVpn(config, fallback, config, true)
        } catch (e: Exception) {
            status.text = "Не удалось подготовить сервер: ${e.message}"
        }
    }

    private fun testAllServers(
        results: MutableMap<String, Long>,
        isRunning: () -> Boolean,
        setRunning: (Boolean) -> Unit,
        refreshUi: () -> Unit
    ) {
        if (isRunning()) return
        val nodes = activeNodes.distinctBy { it.uri }
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Сначала импортируй серверы в выбранный режим", Toast.LENGTH_LONG).show()
            return
        }
        setRunning(true)
        results.clear()
        refreshUi()
        status.text = "Проверяю все серверы: 0 из ${nodes.size}…"
        Thread {
            val pool = Executors.newFixedThreadPool(10)
            val completion = java.util.concurrent.ExecutorCompletionService<Pair<String, Long?>>(pool)
            try {
                nodes.forEach { node ->
                    completion.submit(Callable {
                        val delay = runCatching {
                            val config = GilrTanoVpnService.xrayConfigFromVless(node.uri)
                            teapodcore.Teapodcore.measureOutboundDelay(config, "https://www.gstatic.com/generate_204")
                        }.getOrNull()?.takeIf { it > 0L }
                        node.uri to delay
                    })
                }
                var done = 0
                while (done < nodes.size) {
                    val result = completion.take().get()
                    done++
                    runOnUiThread {
                        results[result.first] = result.second ?: -1L
                        status.text = "Проверка серверов: $done из ${nodes.size}"
                        refreshUi()
                    }
                }
                runOnUiThread {
                    val working = results.values.count { it > 0L }
                    status.text = "Проверка завершена: работает $working из ${nodes.size}"
                    setRunning(false)
                    refreshUi()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка проверки серверов: ${e.message ?: "неизвестная ошибка"}"
                    setRunning(false)
                    refreshUi()
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
                if (result.isSuccess) {
                    val ms = result.getOrThrow()
                    pingStatus.text = "Пинг до сервера: ${ms} мс (TCP)"
                    if (::pingMetric.isInitialized) pingMetric.text = "$ms мс"
                    if (::serverCardPing.isInitialized) serverCardPing.text = "$ms мс"
                } else {
                    pingStatus.text = "Сервер не отвечает на проверку"
                    if (::pingMetric.isInitialized) pingMetric.text = "— мс"
                }
            }
        }.start()
    }

    private fun startVpn(
        config: String,
        fallbackConfig: String? = null,
        preferredConfig: String? = null,
        automatic: Boolean = false
    ) {
        connectButton.isEnabled = true
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, config)
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
        fallbackConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_FALLBACK_CONFIG, it) }
        preferredConfig?.let { intent.putExtra(GilrTanoVpnService.EXTRA_PREFERRED_CONFIG, it) }
        startForegroundService(intent)
        status.text = "Запускаю VPN…"
        connectButton.setState(PowerOrbButtonView.State.CONNECTING)
    }

    private fun startQrScanner() {
        runCatching {
            IntentIntegrator(this)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Наведите камеру на QR-код")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
                .initiateScan()
        }.onFailure {
            Toast.makeText(this, "Не удалось открыть сканер: ${it.message ?: "ошибка камеры"}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 4105) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startQrScanner()
            } else {
                Toast.makeText(this, "Для сканирования QR-кода разреши доступ к камере", Toast.LENGTH_LONG).show()
            }
        }
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val qrResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (qrResult != null) {
            val value = qrResult.contents?.trim().orEmpty()
            if (value.isNotBlank()) importFrom(value, false)
            return
        }
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let {
                startVpn(it, pendingFallbackConfig, pendingPreferredConfig, pendingAutomatic)
                pendingConfig = null
                pendingFallbackConfig = null
                pendingPreferredConfig = null
                pendingAutomatic = false
            } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            pendingConfig = null
            connectButton.isEnabled = true
            status.text = "Разрешение VPN не предоставлено"
        } else if ((requestCode == documentRequestCode || requestCode == docxRequestCode) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Читаю документ…"
            Thread {
                try {
                    val mime = contentResolver.getType(uri).orEmpty()
                    val raw = if (mime.contains("wordprocessingml") || uri.toString().substringBefore('?').endsWith(".docx", true)) {
                        DocxTextExtractor.read(this, uri)
                    } else {
                        contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("Не удалось открыть файл")
                    }
                    val imported = SubscriptionImporter.load(raw)
                    runOnUiThread {
                        if (imported.isEmpty()) status.text = "В документе не найдены поддерживаемые VPN-профили"
                        else {
                            quattroNodes = imported; saveNodes("quattro_nodes", quattroNodes)
                            activeFree = false; autoMode = false; activeMode = "subscription"
                            prefs.edit().putBoolean("active_free_mode", false).putString("connection_mode", "subscription").apply()
                            refreshMode(); status.text = "Добавлено профилей: ${imported.size}"; measureSelectedPing()
                        }
                    }
                } catch (e: Exception) { runOnUiThread { status.text = "Ошибка чтения документа: ${e.message}" } }
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