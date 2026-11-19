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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.FrameLayout
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
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var servers: Spinner
    private lateinit var quattroTab: Button
    private lateinit var portalTab: Button
    private var buttonShellAnimator: ValueAnimator? = null
    private var activeMode: String = "quattro"
    private var activePortal = false
    private var portalNodes: List<ImportedNode> = emptyList()
    private lateinit var checkPing: Button
    private lateinit var updateButton: Button
    private val releaseUpdater by lazy { ReleaseUpdater(this) }
    private val updateExecutor = Executors.newSingleThreadExecutor()
    private lateinit var modeSelectorRow: LinearLayout
    private lateinit var pingMetric: TextView
    private lateinit var protocolMetric: TextView
    private lateinit var connectButton: PowerOrbButtonView
    private var settingsExpanded = false
    private var quattroNodes: List<ImportedNode> = emptyList()
    private var pendingConfig: String? = null
    private var pendingAutomatic = false
    private val requestCode = 4101
    private val docxRequestCode = 4102
    private val portalRequestCode = 4103

    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val activeNodes: List<ImportedNode>
        get() = if (activeMode == "portal") portalNodes else quattroNodes

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(GilrTanoVpnService.EXTRA_STATUS) ?: "—"
            status.text = when {
                message.startsWith("Подключено") || message.startsWith("Возобновлено") || message.startsWith("Сервер переключён") -> "●  Подключено"
                message == "Отключено" -> "●  Отключено"
                else -> message
            }
            if (message.startsWith("Подключено") || message.startsWith("Возобновлено") || message.startsWith("Сервер переключён")) {
                val resumed = message.startsWith("Возобновлено")
                val switched = message.startsWith("Сервер переключён")
                refreshMode()
                status.setTextColor(Color.rgb(36, 255, 72))
                updatePowerOrb(true)
                if (::connectionTimer.isInitialized) {
                    if (!resumed && !switched) connectionTimer.base = SystemClock.elapsedRealtime()
                    connectionTimer.visibility = View.VISIBLE
                    connectionTimer.start()
                }
                updateServerCard(true)
                if (switched) serverCardSubtitle.text = message.removePrefix("Сервер переключён:").trim()
                if (!switched) igniteConnectButton()
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
            } else if (message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Meow") || message.startsWith("Переключаю")) {
                status.setTextColor(Color.rgb(255, 166, 55))
                connectButton.setState(PowerOrbButtonView.State.CONNECTING)
                // Keep the ignition animation visible while the tunnel is starting.
            }
            val busy = message.startsWith("Подключаю") || message.startsWith("Запускаю") || message.startsWith("Запуск Meow") || message.startsWith("Переключаю") || message.startsWith("Приостанавливаю") || message.startsWith("Возобновляю")
            // The single power button remains usable while connecting so it can cancel,
            // and while connected so it can disconnect.
            connectButton.isEnabled = true
            setPowerButtonLabel(
                if (message.startsWith("Подключено") || message.startsWith("Возобновлено") || message.startsWith("Сервер переключён")) "Ⅱ  ПАУЗА"
                else if (message == "Приостановлено") "Ⅱ  ВОЗОБНОВИТЬ"
                else if (message.startsWith("Отключаю")) "ОТКЛЮЧЕНИЕ…"
                else if (message.startsWith("Переключаю")) "ПЕРЕКЛЮЧЕНИЕ…"
                else if (message.startsWith("Приостанавливаю")) "ПАУЗА…"
                else if (message.startsWith("Возобновляю")) "ВОЗОБНОВЛЕНИЕ…"
                else if (busy) "ПОДКЛЮЧЕНИЕ…"
                else "▶ ПОДКЛЮЧИТЬ"
            )
            connectButton.contentDescription = when {
                message.startsWith("Подключено") || message.startsWith("Возобновлено") || message.startsWith("Сервер переключён") -> "Приостановить VPN"
                message == "Приостановлено" -> "Возобновить VPN"
                busy -> "Отменить подключение"
                else -> "Подключить VPN"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        quattroNodes = restoreNodes("quattro_nodes")

        status = TextView(this).apply { text = "Отключено"; textSize = 14f; maxLines = 2; gravity = android.view.Gravity.CENTER; setPadding(dp(18), dp(4), dp(18), dp(4)) }
        pingStatus = TextView(this).apply { text = "Пинг: —"; textSize = 15f }
        primarySource = EditText(this).apply {
            hint = "Ссылка подписки или Clash YAML"
            minLines = 2
            maxLines = 5
            setText(prefs.getString("subscription_url", ""))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        servers = Spinner(this).apply {
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    val key = activeSelectionKey()
                    prefs.edit().putInt(key, position).apply()
                    pingStatus.text = "Пинг: —"
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        quattroTab = Button(this).apply {
            text = "ПОДПИСКА"
            setOnClickListener { switchMode("quattro") }
        }
        portalTab = Button(this).apply {
            text = "FREE"
            setOnClickListener { switchMode("portal") }
        }

        val importPrimary = Button(this).apply {
            text = "Импортировать подписку"
            setOnClickListener { importFrom(primarySource.text.toString().trim()) }
        }
        val importDocx = Button(this).apply {
            text = "Выбрать DOCX подписки"
            setOnClickListener {
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                }
                startActivityForResult(pick, docxRequestCode)
            }
        }
        connectButton = PowerOrbButtonView(this).apply {
            // The power glyph is drawn by PowerOrbButtonView; the caption lives only in the foreground overlay.
            text = ""
            setOnClickListener {
                val currentStatus = status.text.toString()
                val isConnected = currentStatus.contains("Подключено")
                val isSuspended = currentStatus.contains("Приостановлено")
                val isConnecting = currentStatus.startsWith("Подключаю") ||
                    currentStatus.startsWith("Запускаю") || currentStatus.startsWith("Запуск Meow") ||
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
                if (activeNodes.isEmpty()) {
                    Toast.makeText(this@MainActivity, "В режиме " + activeMode.uppercase() + " нет профиля. Импортируй конфигурацию.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val node = activeNodes.getOrNull(servers.selectedItemPosition)
                if (node == null) {
                    Toast.makeText(this@MainActivity, "В этом режиме пока нет серверов", Toast.LENGTH_LONG).show()
                } else {
                    try {
                        val config = GilrTanoVpnService.meowConfigForNode(node)
                                                pendingConfig = config
                        // Quattro may optimize servers inside its own profile.
                        // Cross-mode fallback is disabled; Quattro optimization stays within its own profile.
                        pendingAutomatic = false // Automatic server selection is deferred by request.
                        connectButton.isEnabled = true
                        status.text = "Подключаю: ${node.name}…"
                        val permission = VpnService.prepare(this@MainActivity)
                        if (permission != null) startActivityForResult(permission, requestCode)
                        else startVpn(config, automatic = pendingAutomatic)
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
                            text = "Выбранный сервер"; textSize = 16f
                            setTextColor(Color.WHITE)
                            setOnLongClickListener {
                                showMeowGroupsDialog()
                                true
                            }
                        }
                        serverCardSubtitle = TextView(this@MainActivity).apply {
                            text = "Выбор сервера"; textSize = 12f
                            setTextColor(Color.LTGRAY)
                        }
                        addView(serverCardTitle)
                        addView(serverCardSubtitle)
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(TextView(this@MainActivity).apply {
                        text = "ПОДПИСКА / FREE"
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
                addView(checkPing, LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                checkPing.visibility = View.GONE
                modeSelectorRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER
                    addView(quattroTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(4) })
                    addView(portalTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
                    visibility = View.VISIBLE
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
            serverState.text = "Режим: " + modeDisplayName()
            serverRows.removeAllViews()
            val query = proxySearch.text.toString().trim().lowercase()

            if (activeMode == "quattro") {
                val connected = status.text.toString().contains("Подключено")
                if (!connected) {
                    serverRows.addView(TextView(this).apply {
                        text = if (quattroNodes.isEmpty()) "Сначала добавьте подписку во вкладке «Профили»."
                        else "Подключите подписку, чтобы увидеть группы и серверы."
                        textSize = 14f
                        setTextColor(Color.LTGRAY)
                        setPadding(dp(4), dp(18), dp(4), dp(18))
                    })
                    return
                }

                serverState.text = "Загружаю группы подписки…"
                Thread({
                    val result = runCatching { FlClashXEngine.getProxyGroups() }
                    runOnUiThread {
                        if (activeMode != "quattro") return@runOnUiThread
                        val data = result.getOrElse {
                            serverState.text = "Не удалось загрузить серверы"
                            serverRows.removeAllViews()
                            serverRows.addView(TextView(this).apply {
                                text = it.message ?: "Ошибка ядра"
                                setTextColor(Color.LTGRAY)
                            })
                            serverRows.addView(actionButton("Повторить") { updateServerPage() })
                            return@runOnUiThread
                        }

                        val proxyMap = data.optJSONObject("proxies") ?: JSONObject()
                        val names = data.optJSONArray("all") ?: JSONArray()
                        val groups = mutableListOf<Pair<String, JSONObject>>()
                        for (i in 0 until names.length()) {
                            val name = names.optString(i)
                            val group = proxyMap.optJSONObject(name) ?: continue
                            if (group.optBoolean("hidden", false)) continue
                            val members = group.optJSONArray("all") ?: continue
                            if (members.length() == 0) continue
                            if (query.isNotBlank()) {
                                val matches = (0 until members.length()).any { n ->
                                    val item = members.optJSONObject(n)
                                    val memberName = item?.optString("name").orEmpty()
                                        .ifBlank { members.optString(n) }
                                    val type = item?.optString("type").orEmpty()
                                    val description = item?.optString("serverDescription").orEmpty()
                                    listOf(memberName, type, description).any { it.contains(query, true) }
                                }
                                if (!matches && !name.contains(query, true)) continue
                            }
                            groups.add(name to group)
                        }

                        serverRows.removeAllViews()
                        if (groups.isEmpty()) {
                            serverState.text = "Группы и серверы не найдены"
                            serverRows.addView(TextView(this).apply {
                                text = if (query.isBlank()) "В подписке нет доступных групп." else "Ничего не найдено"
                                setTextColor(Color.LTGRAY)
                                setPadding(dp(4), dp(20), dp(4), dp(20))
                            })
                            return@runOnUiThread
                        }

                        val savedGroup = prefs.getString("subscription_current_group", null)
                        var selectedGroupIndex = groups.indexOfFirst { it.first == savedGroup }
                            .takeIf { it >= 0 } ?: 0

                        val tabsScroll = HorizontalScrollView(this).apply {
                            isHorizontalScrollBarEnabled = false
                            clipToPadding = false
                            setPadding(dp(12), dp(5), dp(12), dp(12))
                        }
                        val tabs = LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        }
                        tabsScroll.addView(tabs)
                        serverRows.addView(tabsScroll, LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dp(54)
                        ))

                        val cardsHost = LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(2), dp(12), dp(12))
                        }
                        serverRows.addView(cardsHost)

                        fun renderGroup(index: Int) {
                            selectedGroupIndex = index.coerceIn(0, groups.lastIndex)
                            val (groupName, groupObject) = groups[selectedGroupIndex]
                            prefs.edit().putString("subscription_current_group", groupName).apply()
                            tabs.removeAllViews()
                            groups.forEachIndexed { tabIndex, entry ->
                                val active = tabIndex == selectedGroupIndex
                                val chip = TextView(this).apply {
                                    text = entry.first
                                    textSize = 13f
                                    maxLines = 1
                                    gravity = android.view.Gravity.CENTER
                                    setPadding(dp(16), dp(9), dp(16), dp(9))
                                    setTextColor(if (active) Color.WHITE else Color.LTGRAY)
                                    background = GradientDrawable().apply {
                                        setColor(if (active) Color.rgb(48, 65, 52) else Color.rgb(35, 36, 40))
                                        cornerRadius = dp(30).toFloat()
                                        if (active) setStroke(dp(1), Color.rgb(54, 225, 92))
                                    }
                                    setOnClickListener { renderGroup(tabIndex) }
                                }
                                tabs.addView(chip, LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)
                                ).apply { rightMargin = dp(8) })
                            }

                            cardsHost.removeAllViews()
                            val members = groupObject.optJSONArray("all") ?: JSONArray()
                            val current = groupObject.optString("now")
                            val fixed = groupObject.optString("fixed")
                            val selected = fixed.ifBlank { current }
                            val groupType = groupObject.optString("type")
                            // Mihomo's computed groups choose their active member automatically;
                            // Selector groups allow a direct manual choice.
                            val computedSelection = groupType.equals("URLTest", true) ||
                                groupType.equals("Fallback", true) ||
                                groupType.equals("LoadBalance", true)
                            val allowsManualSelection = groupType.equals("Selector", true) || computedSelection
                            val filtered = (0 until members.length()).mapNotNull { n ->
                                val item = members.optJSONObject(n)
                                val name = item?.optString("name").orEmpty().ifBlank {
                                    members.optString(n)
                                }
                                if (name.isBlank() || !name.contains(query, true)) null
                                else Triple(name, item?.optString("type").orEmpty(),
                                    item?.optString("serverDescription").orEmpty())
                            }
                            if (filtered.isEmpty()) {
                                cardsHost.addView(TextView(this).apply {
                                    text = "В этой группе нет серверов"
                                    setTextColor(Color.LTGRAY)
                                    setPadding(0, dp(20), 0, dp(20))
                                })
                                return
                            }

                            filtered.chunked(2).forEach { pair ->
                                val row = LinearLayout(this).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                }
                                pair.forEach { (nodeName, nodeType, description) ->
                                    val inUse = nodeName == selected
                                    val card = LinearLayout(this).apply {
                                        orientation = LinearLayout.VERTICAL
                                        gravity = android.view.Gravity.CENTER_VERTICAL
                                        setPadding(dp(12), dp(10), dp(12), dp(9))
                                        background = GradientDrawable().apply {
                                            setColor(if (inUse) Color.rgb(23, 43, 29) else Color.rgb(35, 36, 40))
                                            cornerRadius = dp(16).toFloat()
                                            setStroke(dp(1), if (inUse) Color.rgb(57, 220, 94) else Color.rgb(57, 58, 63))
                                        }
                                        setOnClickListener {
                                            if (!allowsManualSelection) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Эта группа управляет выбором автоматически",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                return@setOnClickListener
                                            }
                                            val requestedNode = if (computedSelection && nodeName == selected) "" else nodeName
                                            serverState.text = if (requestedNode.isBlank()) "Сбрасываю предпочтение…" else "Переключаю: $nodeName"
                                            Thread({
                                                val changed = runCatching {
                                                    FlClashXEngine.selectProxy(groupName, requestedNode)
                                                }
                                                runOnUiThread {
                                                    val message = changed.getOrElse { it.message ?: "Ошибка выбора" }
                                                    if (message.isBlank()) {
                                                        serverCardTitle.text = nodeName
                                                        serverCardSubtitle.text = "ПОДПИСКА · $groupName"
                                                        serverState.text = "Выбран: $nodeName"
                                                        updateServerPage()
                                                    } else {
                                                        serverState.text = message
                                                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }, "GilrTano-select-proxy").start()
                                        }
                                        addView(TextView(this@MainActivity).apply {
                                            text = nodeName
                                            textSize = 14f
                                            maxLines = 2
                                            ellipsize = android.text.TextUtils.TruncateAt.END
                                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            setTextColor(Color.WHITE)
                                        })
                                        addView(TextView(this@MainActivity).apply {
                                            text = description.ifBlank { nodeType }
                                            textSize = 11f
                                            maxLines = 1
                                            ellipsize = android.text.TextUtils.TruncateAt.END
                                            setTextColor(Color.rgb(173, 176, 185))
                                            setPadding(0, dp(5), 0, 0)
                                        })
                                        val delay = TextView(this@MainActivity).apply {
                                            text = "ϟ  Проверить задержку"
                                            textSize = 10f
                                            setTextColor(Color.rgb(169, 184, 255))
                                            setPadding(0, dp(7), 0, 0)
                                            setOnClickListener {
                                                text = "Проверяю…"
                                                Thread({
                                                    val ping = runCatching {
                                                        FlClashXEngine.testProxyDelay(nodeName)
                                                    }.getOrDefault(-1)
                                                    runOnUiThread {
                                                        text = if (ping > 0) "$ping ms" else "Timeout"
                                                        setTextColor(if (ping > 0) Color.rgb(75, 220, 112) else Color.rgb(235, 110, 100))
                                                    }
                                                }, "GilrTano-proxy-delay").start()
                                            }
                                        }
                                        addView(delay)
                                    }
                                    row.addView(card, LinearLayout.LayoutParams(0, dp(112), 1f).apply {
                                        rightMargin = dp(4)
                                        leftMargin = dp(4)
                                        bottomMargin = dp(8)
                                    })
                                }
                                if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, dp(112), 1f))
                                cardsHost.addView(row)
                            }
                            serverState.text = "$groupName · ${filtered.size} серверов"
                        }

                        renderGroup(selectedGroupIndex)
                    }
                }, "GilrTano-load-flclashx-groups").start()
                return
            }

            val nodes = activeNodes.mapIndexed { index, node -> index to node }
                .filter { it.second.name.lowercase().contains(query) }
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
                            prefs.edit().putInt(activeSelectionKey(), index).apply()
                            serverCardTitle.text = node.name
                            serverCardSubtitle.text = modeDisplayName()
                            serverCardPing.text = ""
                            pingStatus.text = "Пинг: —"
                        }
                        addView(TextView(this@MainActivity).apply {
                            text = (if (selected) "✓  " else "") + node.name
                            textSize = 14f; maxLines = 2; setTextColor(Color.WHITE)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        })
                        addView(TextView(this@MainActivity).apply {
                            text = if (node.yamlConfig != null) "CLASH / MIHOMO" else node.uri.substringBefore("://").uppercase()
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
        serverModes.addView(actionButton("ПОДПИСКА") { switchMode("quattro"); updateServerPage() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        serverModes.addView(actionButton("FREE") { switchMode("portal"); updateServerPage() },
            LinearLayout.LayoutParams(0, dp(48), 1f))
        serverPage.addView(serverModes)
        // Server-group selection for subscription profiles will be wired to FlClashX, not Meow.
        serverPage.addView(actionButton("Проверить пинг") { measureSelectedPing() })
        // First-class profiles tab backed by the existing Quattro/YAML/DOCX import logic.
        val profilesPage = page("Профили")
        val profileActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        profileActions.addView(actionButton("↻ Обновить") {
            val url = prefs.getString("subscription_url", "").orEmpty()
            if (url.isNotBlank()) importFrom(url)
            else Toast.makeText(this@MainActivity, "Сначала добавьте ссылку подписки", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(5) })
        profileActions.addView(actionButton("＋ Добавить") {
            val field = EditText(this@MainActivity).apply {
                hint = "Ссылка подписки или Clash YAML"
                setText(prefs.getString("subscription_url", ""))
                setSingleLine(true)
            }
            AlertDialog.Builder(this@MainActivity).setTitle("Добавить профиль").setView(field)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Импортировать") { _, _ ->
                    val value = field.text.toString().trim()
                    prefs.edit().putString("subscription_url", value).apply()
                    importFrom(value)
                }.show()
        }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(5) })
        profilesPage.addView(profileActions)
        val profileCard = card()
        profileCard.background = GradientDrawable().apply {
            setColor(Color.rgb(31, 32, 36)); cornerRadius = dp(24).toFloat()
            setStroke(dp(1), Color.rgb(62, 65, 76))
        }
        val profileTitle = TextView(this).apply {
            text = "🔥 Подписка VPN"; textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
        }
        val profileUsage = TextView(this).apply { textSize = 13f; setTextColor(Color.LTGRAY); setPadding(0, dp(8), 0, 0) }
        val profileState = TextView(this).apply { textSize = 12f; setTextColor(Color.rgb(169, 184, 255)); setPadding(0, dp(5), 0, 0) }
        profileCard.addView(profileTitle); profileCard.addView(profileUsage); profileCard.addView(profileState)
        profilesPage.addView(profileCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(18); bottomMargin = dp(14)
        })
        fun refreshProfileCard() {
            val serverCount = quattroNodes.sumOf { node ->
                val yaml = node.yamlConfig
                if (yaml.isNullOrBlank()) 1 else {
                    val proxySection = yaml.substringAfter("proxies:", "").substringBefore("proxy-groups:")
                    proxySection.lineSequence().count { it.trimStart().startsWith("- name:") }
                }
            }
            profileUsage.text = "Серверов в профиле: $serverCount"
            profileState.text = if (quattroNodes.isNotEmpty()) "✓ Подписка доступна" else "Профиль ещё не импортирован"
        }
        refreshProfileCard()
        profilesPage.addView(actionButton("Импортировать подписку") {
            val value = prefs.getString("subscription_url", "").orEmpty()
            if (value.isNotBlank()) importFrom(value)
            else Toast.makeText(this@MainActivity, "Добавьте ссылку через кнопку «Добавить»", Toast.LENGTH_LONG).show()
        })
        profilesPage.addView(actionButton("Импортировать Clash YAML") {
            val value = prefs.getString("subscription_url", "").orEmpty()
            if (value.isNotBlank()) importFrom(value)
            else Toast.makeText(this@MainActivity, "Добавьте ссылку YAML через кнопку «Добавить»", Toast.LENGTH_LONG).show()
        })
        profilesPage.addView(actionButton("Выбрать DOCX подписки") {
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            }
            startActivityForResult(pick, docxRequestCode)
        })
        profilesPage.addView(actionButton("Импортировать FREE YAML по ссылке") { showPortalImportDialog() })
        profilesPage.addView(actionButton("Выбрать файл FREE YAML") { pickPortalYaml() })
        profilesPage.addView(actionButton("Обновить список профилей") { refreshProfileCard(); updateServerPage() })
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
            if (index == 2) refreshProfileCard()
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
        activeMode = prefs.getString("connection_mode", "quattro") ?: "quattro"
        if (activeMode !in setOf("quattro", "portal")) activeMode = "quattro"
        prefs.edit().putString("connection_mode", activeMode).putBoolean("active_free_mode", false).apply()
        activePortal = activeMode == "portal"
        portalNodes = restoreNodes("portal_nodes")
        refreshMode()
        updatePowerOrb(false)
        updateServerCard(false)
        handleSubscriptionIntent(intent)
    }

    private fun showServerPicker() {
        val selectedNode = activeNodes.getOrNull(servers.selectedItemPosition)
        if (selectedNode?.yamlConfig != null) {
            Toast.makeText(this, "Список серверов подписки пока недоступен", Toast.LENGTH_LONG).show()
            return
        }
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
                val key = activeSelectionKey()
                prefs.edit().putInt(key, which).apply()
                serverCardTitle.text = nodes[which].name
                serverCardSubtitle.text = modeDisplayName()
                serverCardPing.text = ""
                pingStatus.text = "Пинг: —"
                dialog.dismiss()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /**
     * Long-press the selected profile title to switch a proxy inside a
     * Clash/Mihomo profile without adding another visible control.
     */
    private fun showMeowGroupsDialog() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (node?.yamlConfig == null) {
            Toast.makeText(this, "Сначала выберите Clash/Mihomo профиль", Toast.LENGTH_SHORT).show()
            return
        }
        if (!status.text.toString().contains("Подключено")) {
            Toast.makeText(this, "Сначала подключите VPN", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Загружаю прокси-группы…", Toast.LENGTH_SHORT).show()
        Thread({
            val result = runCatching { MeowController.listGroups(this) }
            runOnUiThread {
                val groups = result.getOrElse {
                    Toast.makeText(this, "Meow API: ${it.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (groups.isEmpty()) {
                    Toast.makeText(this, "В профиле нет переключаемых прокси-групп", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (groups.size == 1) {
                    showMeowGroupMembers(groups.first())
                } else {
                    val preferred = groups.sortedBy { if (it.name.equals("Quattro VPN", true)) 0 else 1 }
                    val labels = preferred.map { "${it.name} · ${it.type}" }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("Прокси-группы")
                        .setItems(labels) { _, index -> showMeowGroupMembers(preferred[index]) }
                        .setNegativeButton("Закрыть", null)
                        .show()
                }
            }
        }, "GilrTano-Meow-groups").start()
    }

    private fun showMeowGroupMembers(group: MeowProxyGroup) {
        if (group.members.isEmpty()) {
            Toast.makeText(this, "В группе нет доступных серверов", Toast.LENGTH_SHORT).show()
            return
        }
        val selected = group.members.indexOf(group.selected).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(group.name)
            .setSingleChoiceItems(group.members.toTypedArray(), selected) { dialog, which ->
                val member = group.members[which]
                dialog.dismiss()
                Thread({
                    val result = runCatching { MeowController.selectMember(this, group.name, member) }
                    runOnUiThread {
                        if (result.isSuccess) {
                            Toast.makeText(this, "Выбран: $member", Toast.LENGTH_SHORT).show()
                            serverCardSubtitle.text = "${group.name}: $member"
                        } else {
                            Toast.makeText(this, "Не удалось переключить: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }, "GilrTano-Meow-select").start()
            }
            .setNeutralButton("⚡ Авто | Самый быстрый") { dialog, _ ->
                dialog.dismiss()
                startFastestServerScan(group)
            }
            .setNegativeButton("Назад", null)
            .show()
    }

    /**
     * Tests every member of the selected Quattro group through Meow itself,
     * then selects the member with the lowest successful HTTP delay.
     * Probes run in bounded parallel batches so 563 nodes do not overload the phone.
     */
    private fun startFastestServerScan(group: MeowProxyGroup) {
        val members = group.members.distinct()
        if (members.isEmpty()) {
            Toast.makeText(this, "В группе нет серверов для проверки", Toast.LENGTH_LONG).show()
            return
        }

        val executor = Executors.newFixedThreadPool(16)
        val completion = ExecutorCompletionService<Pair<String, Int?>>(executor)
        val cancelled = AtomicBoolean(false)
        var scanThread: Thread? = null
        val progress = ProgressDialog(this).apply {
            setTitle("Авто | Самый быстрый")
            setMessage("Подготовка проверки серверов…")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            max = members.size
            progress = 0
            setCancelable(true)
            setOnCancelListener {
                cancelled.set(true)
                scanThread?.interrupt()
                executor.shutdownNow()
            }
        }
        progress.show()

        scanThread = Thread({
            var completed = 0
            val successful = ArrayList<Pair<String, Int>>()
            try {
                members.forEach { member ->
                    if (cancelled.get()) return@forEach
                    completion.submit(Callable {
                        val delay = runCatching {
                            MeowController.probeMember(this, member)
                        }.getOrNull()
                        member to delay
                    })
                }

                while (completed < members.size && !cancelled.get()) {
                    val (member, delay) = completion.take().get()
                    completed++
                    if (delay != null) successful.add(member to delay)
                    val checked = completed
                    val reachable = successful.size
                    runOnUiThread {
                        if (progress.isShowing && !cancelled.get()) {
                            progress.progress = checked
                            progress.setMessage(
                                "Проверено: $checked из ${members.size}\nДоступно: $reachable"
                            )
                        }
                    }
                }
                if (cancelled.get()) return@Thread

                executor.shutdown()
                val fastest = successful.minByOrNull { it.second }
                if (fastest == null) {
                    runOnUiThread {
                        if (progress.isShowing) progress.dismiss()
                        AlertDialog.Builder(this)
                            .setTitle("Проверка завершена")
                            .setMessage(
                                "Проверено серверов: $completed из ${members.size}\n" +
                                    "Доступных серверов: 0\n\n" +
                                    "Ни один сервер не прошёл проверку соединения с интернетом."
                            )
                            .setPositiveButton("ОК", null)
                            .show()
                    }
                    return@Thread
                }

                val selection = runCatching {
                    MeowController.selectMember(this, group.name, fastest.first)
                }
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (selection.isSuccess) {
                        if (::serverCardTitle.isInitialized) serverCardTitle.text = fastest.first
                        if (::serverCardSubtitle.isInitialized) {
                            serverCardSubtitle.text = "${group.name} · выбран автоматически"
                        }
                        if (::serverCardPing.isInitialized) serverCardPing.text = "${fastest.second} мс"
                        if (::pingStatus.isInitialized) pingStatus.text = "Пинг: ${fastest.second} мс"
                        AlertDialog.Builder(this)
                            .setTitle("Авто | Самый быстрый")
                            .setMessage(
                                "Проверено серверов: $completed из ${members.size}\n" +
                                    "Доступно: ${successful.size}\n" +
                                    "Недоступно: ${members.size - successful.size}\n\n" +
                                    "Выбран: ${fastest.first}\n" +
                                    "Задержка: ${fastest.second} мс"
                            )
                            .setPositiveButton("ОК", null)
                            .show()
                    } else {
                        Toast.makeText(
                            this,
                            "Сервер найден (${fastest.second} мс), но выбрать его не удалось: ${selection.exceptionOrNull()?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                runOnUiThread {
                    if (progress.isShowing) progress.dismiss()
                    if (!cancelled.get()) {
                        Toast.makeText(this, "Ошибка проверки серверов: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }, "GilrTano-fastest-quattro")
        scanThread?.start()
    }

    private fun updateServerCard(connected: Boolean) {
        if (!::serverCardTitle.isInitialized) return
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (connected && node != null) {
            serverCardTitle.text = node.name
            serverCardSubtitle.text = modeDisplayName()
            val ping = Regex("(\\d+)\\s*мс").find(pingStatus.text.toString())?.groupValues?.get(1)
            serverCardPing.text = ping?.let { "$it мс" } ?: ""
        } else {
            serverCardTitle.text = if (activeNodes.isEmpty()) "Сервер не добавлен" else "Выбранный сервер"
            serverCardSubtitle.text = if (activeNodes.isEmpty()) "Добавьте профиль" else modeDisplayName()
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
        if (scheme == null || scheme !in setOf("clash", "clashmeta", "flclash", "flclashx")) return
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
        primarySource.setText(incoming)
        Toast.makeText(this, "Получена ссылка подписки. Загружаю профиль…", Toast.LENGTH_LONG).show()
        importFrom(incoming)
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

    private fun switchMode(mode: String) {
        if (mode !in setOf("quattro", "portal")) return
        val nodes = if (mode == "portal") portalNodes else quattroNodes
        if (nodes.isEmpty()) {
            val message = if (mode == "portal") "Импортируй FREE YAML" else "Импортируй профиль подписки"
            Toast.makeText(this, message, Toast.LENGTH_LONG).show(); return
        }
        if (activeMode == mode) return
        val current = status.text.toString()
        val active = current.contains("Подключено") || current.contains("Приостановлено") || current.startsWith("Подключаю") || current.startsWith("Запускаю") || current.startsWith("Запуск Meow") || current.startsWith("Переключаю") || current.startsWith("Приостанавливаю") || current.startsWith("Возобновляю")
        if (active) {
            pendingConfig = null; pendingAutomatic = false
            status.text = "Отключаю VPN для смены режима…"
            startService(Intent(this, GilrTanoVpnService::class.java).setAction(GilrTanoVpnService.ACTION_STOP))
        }
        activeMode = mode; activePortal = mode == "portal"
        prefs.edit().putString("connection_mode", mode).putBoolean("active_free_mode", false).apply()
        refreshMode(); updateServerCard(false)
        if (active) status.text = "Отключаю VPN… выбран режим " + mode.uppercase()
        else Toast.makeText(this, "Выбран режим " + if (mode == "quattro") "ПОДПИСКА" else "FREE", Toast.LENGTH_SHORT).show()
    }

    private fun activeSelectionKey(): String = if (activeMode == "portal") "portal_selected" else "quattro_selected"
    private fun modeDisplayName(): String = if (activeMode == "quattro") "ПОДПИСКА" else "FREE"

    private fun pickPortalYaml() {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
        startActivityForResult(pick, portalRequestCode)
    }

    private fun importPortalYamlText(yaml: String) {
        val imported = runCatching { SubscriptionImporter.parse(yaml) }.getOrElse {
            Toast.makeText(this, "Ошибка чтения FREE YAML: " + it.message, Toast.LENGTH_LONG).show(); return
        }
        if (imported.isEmpty()) { Toast.makeText(this, "В YAML не найдены поддерживаемые прокси", Toast.LENGTH_LONG).show(); return }
        portalNodes = imported
        saveNodes("portal_nodes", portalNodes)
        switchMode("portal")
        Toast.makeText(this, "FREE: импортировано профилей " + imported.size, Toast.LENGTH_LONG).show()
    }

    private fun showPortalImportDialog() {
        val field = EditText(this).apply { hint = "HTTPS-ссылка на FREE Clash/Mihomo YAML"; isSingleLine = true; setText(prefs.getString("portal_subscription_url", "")) }
        AlertDialog.Builder(this).setTitle("Импорт FREE")
            .setMessage("Укажи HTTPS-ссылку на отдельную конфигурацию FREE/WARP. Она будет сохранена локально.")
            .setView(field).setNegativeButton("Отмена", null)
            .setPositiveButton("Импортировать") { _, _ ->
                val source = field.text.toString().trim()
                if (source.isBlank()) Toast.makeText(this, "Укажи ссылку на FREE YAML", Toast.LENGTH_LONG).show()
                else Thread {
                    val result = runCatching { SubscriptionImporter.load(this@MainActivity, source) }
                    runOnUiThread {
                        val imported = result.getOrNull().orEmpty()
                        if (imported.isEmpty()) Toast.makeText(this, "В FREE YAML не найдены поддерживаемые прокси", Toast.LENGTH_LONG).show()
                        else { portalNodes = imported; prefs.edit().putString("portal_subscription_url", source).apply(); saveNodes("portal_nodes", portalNodes); switchMode("portal"); Toast.makeText(this, "FREE: импортировано профилей " + imported.size, Toast.LENGTH_LONG).show() }
                    }
                }.start()
            }.show()
    }

    private fun refreshMode() {
        activePortal = activeMode == "portal"
        val q = activeMode == "quattro"; val p = activeMode == "portal"
        fun bg(selected: Boolean) = GradientDrawable().apply { setColor(if (selected) Color.rgb(24, 45, 28) else Color.rgb(13, 13, 15)); cornerRadius = dp(14).toFloat(); setStroke(dp(if (selected) 2 else 1), if (selected) Color.rgb(36, 220, 78) else Color.rgb(58, 58, 62)) }
        quattroTab.isEnabled = !q; portalTab.isEnabled = !p
        quattroTab.alpha = if (q) 1f else 0.72f; portalTab.alpha = if (p) 1f else 0.72f
        quattroTab.background = bg(q); portalTab.background = bg(p)
        quattroTab.setTextColor(if (q) Color.WHITE else Color.LTGRAY); portalTab.setTextColor(if (p) Color.WHITE else Color.LTGRAY)
        if (::protocolMetric.isInitialized) protocolMetric.text = modeDisplayName()
        primarySource.visibility = if (q) View.VISIBLE else View.GONE
        findImportButtonsVisibility()
        val list = activeNodes
        servers.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        val selected = prefs.getInt(activeSelectionKey(), 0).coerceIn(0, (list.size - 1).coerceAtLeast(0))
        if (list.isNotEmpty()) servers.setSelection(selected)
        pingStatus.text = "Пинг: —"
        if (!status.text.toString().contains("Подключено") && !status.text.toString().contains("Приостановлено") && !status.text.toString().startsWith("Отключаю")) { status.text = "●  Отключено"; status.setTextColor(Color.LTGRAY) }
    }
    private fun findImportButtonsVisibility() {
        val content = findViewById<ViewGroup>(android.R.id.content)
        val root = content.getChildAt(0) as? LinearLayout ?: return
        val scroll = root.getChildAt(0) as? android.widget.ScrollView ?: return
        val page = scroll.getChildAt(0) as? ViewGroup ?: return
        settingsHeader.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        modeSelectorRow.visibility = View.VISIBLE
        primarySource.visibility = if (settingsExpanded && !activePortal) View.VISIBLE else View.GONE
        checkPing.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        for (i in 0 until page.childCount) {
            val child = page.getChildAt(i)
            if (child is Button) {
                val label = child.text.toString()
                val show = settingsExpanded && !activePortal && (label == "Импортировать подписку" || label == "Выбрать DOCX подписки")
                if (label.startsWith("Импортировать") || label == "Выбрать DOCX подписки") {
                    child.visibility = if (show) View.VISIBLE else View.GONE
                }
            }
        }
        pingStatus.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
        updateButton.visibility = if (settingsExpanded) View.VISIBLE else View.GONE
    }


    private fun importFrom(source: String) {
        if (source.isBlank()) {
            Toast.makeText(this, "Укажи ссылку или конфигурацию", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Загружаю подписку…"
        Thread {
            try {
                val imported = SubscriptionImporter.load(this@MainActivity, source)
                runOnUiThread {
                    if (imported.isEmpty()) {
                        status.text = "Не найдено поддерживаемых серверов или Clash/Mihomo-профилей"
                        return@runOnUiThread
                    }
                    quattroNodes = imported
                    prefs.edit().putString("subscription_url", source).putBoolean("active_free_mode", false).apply()
                    saveNodes("quattro_nodes", quattroNodes)
                    refreshMode()
                    settingsExpanded = false
                    settingsHeader.text = "Настройки"
                    findImportButtonsVisibility()
                    status.text = "Профиль подписки добавлен"
                    measureSelectedPing()
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Ошибка импорта: ${e.message}" }
            }
        }.start()
    }

    private fun saveNodes(key: String, nodes: List<ImportedNode>) {
        val array = JSONArray()
        nodes.forEachIndexed { index, node ->
            val yamlPath = node.yamlConfig?.takeIf { it.isNotBlank() }?.let { yaml ->
                val fileName = "${key}_profile_${index}.yaml"
                java.io.File(filesDir, fileName).writeText(yaml)
                fileName
            }
            array.put(JSONObject()
                .put("name", node.name)
                .put("uri", node.uri)
                .put("yamlPath", yamlPath))
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun restoreNodes(key: String): List<ImportedNode> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val yamlPath = item.optString("yamlPath").takeIf { it.isNotBlank() && it != "null" }
                val yamlConfig = yamlPath?.let { path ->
                    java.io.File(filesDir, path).takeIf { it.isFile }?.readText()
                } ?: if (!item.isNull("yamlConfig")) item.optString("yamlConfig") else null
                ImportedNode(item.getString("name"), item.optString("uri", ""), yamlConfig)
            }
        }.getOrDefault(emptyList())
    }

    private fun measureSelectedPing() {
        val node = activeNodes.getOrNull(servers.selectedItemPosition)
        if (node == null) {
            pingStatus.text = "Пинг: нет сервера"
            return
        }
        if (node.uri.isBlank()) {
            pingStatus.text = "Пинг: для профиля определяется при подключении"
            if (::pingMetric.isInitialized) pingMetric.text = "— мс"
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

    private fun storeEngineConfig(config: String, slot: String): String {
        val directory = java.io.File(filesDir, "engine-configs").apply { mkdirs() }
        val file = java.io.File(directory, "$slot.yaml")
        file.writeText(config)
        return file.absolutePath
    }

    private fun startVpn(config: String, automatic: Boolean = false) {
        connectButton.isEnabled = true
        val intent = Intent(this, GilrTanoVpnService::class.java)
            .setAction(GilrTanoVpnService.ACTION_START)
            .putExtra(GilrTanoVpnService.EXTRA_CONFIG, storeEngineConfig(config, "active"))
            .putExtra(GilrTanoVpnService.EXTRA_AUTO, automatic)
            .putExtra(GilrTanoVpnService.EXTRA_ENGINE, if (activeMode == "quattro") GilrTanoVpnService.ENGINE_FLCLASHX else GilrTanoVpnService.ENGINE_FREE)
        startForegroundService(intent)
        status.text = "Запускаю VPN…"
        connectButton.setState(PowerOrbButtonView.State.CONNECTING)
    }

    @Deprecated("Deprecated by Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == this.requestCode && resultCode == RESULT_OK) {
            pendingConfig?.let {
                startVpn(it, automatic = pendingAutomatic)
                pendingConfig = null
                pendingAutomatic = false
            } ?: run { status.text = "Подключение отменено" }
        } else if (requestCode == this.requestCode) {
            pendingConfig = null
            connectButton.isEnabled = true
            status.text = "Разрешение VPN не предоставлено"
        } else if (requestCode == portalRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            status.text = "Читаю FREE YAML…"
            Thread {
                val result = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("Не удалось открыть файл") }
                runOnUiThread {
                    result.onSuccess { importPortalYamlText(it) }
                        .onFailure { Toast.makeText(this, "Ошибка чтения файла: " + it.message, Toast.LENGTH_LONG).show() }
                }
            }.start()
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
                            prefs.edit().putBoolean("active_free_mode", false).apply()
                            refreshMode()
                            val serverCount = imported.sumOf { node ->
                                val yaml = node.yamlConfig
                                if (yaml.isNullOrBlank()) 1 else {
                                    val proxySection = yaml.substringAfter("proxies:", "").substringBefore("proxy-groups:")
                                    proxySection.lineSequence().count { it.trimStart().startsWith("- name:") }
                                }
                            }
                            status.text = "Подписка сохранена: $serverCount серверов. Импортировать снова не нужно."
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