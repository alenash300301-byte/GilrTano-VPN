package com.gilrtano.vpn

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import kotlin.math.cos
import kotlin.math.sin

/** FlClashX-inspired three-state power control with an animated heartbeat trace. */
class PowerOrbButtonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextView(context, attrs) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED, SUSPENDED }

    private val d = resources.displayMetrics.density
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var state = State.DISCONNECTED
    private var caption = "ПОДКЛЮЧИТЬ"
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val captionPlatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val orbDiskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(2, 9, 5)
        style = Paint.Style.FILL
    }
    private val powerGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(112, 255, 125)
        style = Paint.Style.STROKE
        strokeWidth = 7.5f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var phase = 0f
    private var morph = 1f
    private var pulse: ValueAnimator? = null
    private var morphAnimator: ValueAnimator? = null
    private var pressAnimator: AnimatorSet? = null
    private var pressFeedback: ValueAnimator? = null
    private var idleBreath: ValueAnimator? = null
    private var pressAmount = 0f
    private var breath = 0f
    private var animationsAllowed = true
    private val holdHandler = Handler(Looper.getMainLooper())
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private var holdTriggered = false
    private var holdRunnable: Runnable? = null
    var useGlArtwork: Boolean = false

    init {
        // Ensure the circular bitmap clip is applied to the artwork's square
        // bitmap bounds on Android devices with problematic HW path clipping.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.TRANSPARENT)
        includeFontPadding = false
    }


    fun setCaption(value: String) {
        caption = value
        text = ""
        invalidate()
    }

    fun setConnected(connected: Boolean) {
        setState(if (connected) State.CONNECTED else State.DISCONNECTED)
    }

    fun setConnecting(connecting: Boolean) {
        if (connecting) setState(State.CONNECTING)
        else if (state == State.CONNECTING) setState(State.DISCONNECTED)
    }

    fun setState(next: State) {
        if (state == next) return
        state = next
        // State transitions take priority over decorative and touch animations.
        pulse?.cancel()
        morphAnimator?.cancel()
        idleBreath?.cancel()
        pressAnimator?.cancel()
        pressFeedback?.cancel()
        pressAmount = 0f
        animate().cancel()
        scaleX = 1f
        scaleY = 1f
        translationZ = 0f
        rotationX = 0f
        rotationY = 0f

        if (animationsAllowed) {
            morph = 0f
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (state != next) return
                        when (next) {
                            State.CONNECTING -> startMotion(1000L, true)
                            State.CONNECTED -> { phase = 0f; invalidate() }
                            State.DISCONNECTED -> { phase = 0f; breath = 0f; invalidate() }
                            State.SUSPENDED -> { phase = 0f; invalidate() }
                        }
                    }
                })
                start()
            }
        } else {
            morph = 1f
            phase = 0f
            if (next == State.DISCONNECTED) { breath = 0f; phase = 0f }
        }
        glyphPaint.color = when (next) {
            State.DISCONNECTED -> Color.WHITE
            State.CONNECTING -> Color.rgb(255, 184, 74)
            State.CONNECTED -> Color.rgb(85, 255, 125)
            State.SUSPENDED -> Color.rgb(150, 150, 150)
        }
        contentDescription = when (next) {
            State.DISCONNECTED -> "VPN отключён. Нажмите, чтобы подключиться"
            State.CONNECTING -> "VPN подключается"
            State.CONNECTED -> "VPN подключён. Нажмите, чтобы отключиться"
            State.SUSPENDED -> "VPN приостановлен"
        }
        invalidate()
    }

    private fun startIdleBreath() {
        idleBreath?.cancel()
        idleBreath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { breath = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startMotion(durationMs: Long, continuous: Boolean) {
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animationsAllowed = runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f
            ) > 0f
        }.getOrDefault(true)
        if (animationsAllowed) {
            when (state) {
                State.DISCONNECTED -> Unit
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> Unit
                State.SUSPENDED -> Unit
            }
        }
    }

    override fun onDetachedFromWindow() {
        holdHandler.removeCallbacksAndMessages(null)
        vibrator?.cancel()
        pulse?.cancel()
        morphAnimator?.cancel()
        pressAnimator?.cancel()
        pressFeedback?.cancel()
        idleBreath?.cancel()
        super.onDetachedFromWindow()
    }

    private fun startRisingHold() {
        holdTriggered = false
        holdRunnable?.let(holdHandler::removeCallbacks)
        val timings = longArrayOf(0, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150, 350, 150)
        val amplitudes = intArrayOf(0, 35, 0, 55, 0, 75, 0, 95, 0, 115, 0, 135, 0, 155, 0, 180, 0, 205, 0, 230, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(5000L)
        }
        holdRunnable = Runnable {
            holdTriggered = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            performLongClick()
        }.also { holdHandler.postDelayed(it, 5000L) }
    }

    private fun cancelRisingHold() {
        holdRunnable?.let(holdHandler::removeCallbacks)
        holdRunnable = null
        vibrator?.cancel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startRisingHold()
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                // Keep the button and its power emblem geometrically fixed on touch.
                // Haptic feedback remains, but no scale, rotation, or translation animation is applied.
                pressAnimator?.cancel()
                pressFeedback?.cancel()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelRisingHold()
                // No rebound animation: the power emblem stays at the same size and position.
                pressAnimator?.cancel()
                pressFeedback?.cancel()
                pressAmount = 0f
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP && holdTriggered) {
            setPressed(false)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) holdTriggered = false
        return super.onTouchEvent(event)
    }

    private val buttonFrames: List<Bitmap> by lazy {
        listOf(
            R.drawable.gilrtano_frame_01,
            R.drawable.gilrtano_frame_02,
            R.drawable.gilrtano_frame_03,
            R.drawable.gilrtano_frame_04,
            R.drawable.gilrtano_frame_05,
            R.drawable.gilrtano_frame_06,
            R.drawable.gilrtano_frame_07,
            R.drawable.gilrtano_frame_08,
            R.drawable.gilrtano_frame_09
        ).map { resourceId ->
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeResource(resources, resourceId, options)
        }
    }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    // Static transparent power emblem layered above the animated flame artwork.
    private val powerIconBitmap: Bitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.gilrtano_power_icon_transparent_1)
    }

    override fun onDraw(canvas: Canvas) {
        // Use the exact artwork uploaded to the repository. Clip its black
        // square corners so the tiger background remains visible around the orb.
        super.onDraw(canvas)
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val left = (width - side) / 2f
        val top = (height - side) / 2f

        val connecting = state == State.CONNECTING
        val frameIndex = when (state) {
            State.DISCONNECTED -> 0
            State.CONNECTING -> (phase * buttonFrames.size).toInt().coerceIn(0, buttonFrames.lastIndex)
            State.CONNECTED -> 8
            State.SUSPENDED -> 0
        }
        val frame = buttonFrames[frameIndex]
        val dst = RectF(left, top, left + side, top + side)
        framePaint.alpha = 255
        canvas.drawBitmap(frame, null, dst, framePaint)
        // Keep the power symbol visually fixed while the nine-frame fire artwork animates beneath it.
        if (!powerIconBitmap.isRecycled) canvas.drawBitmap(powerIconBitmap, null, dst, framePaint)

        // Draw the state label INSIDE the capsule embedded in the artwork.
        // For pause/resume states, render a separate pause glyph and the word as one centered group.
        val captionColor = when (state) {
            State.DISCONNECTED, State.CONNECTED -> Color.rgb(125, 255, 95)
            State.CONNECTING -> Color.rgb(255, 205, 90)
            State.SUSPENDED -> Color.LTGRAY
        }
        captionPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        captionPaint.color = captionColor
        captionPaint.textAlign = Paint.Align.LEFT
        captionPaint.textSize = side * 0.062f
        captionPaint.setShadowLayer(4f * d, 0f, 0f, captionColor)

        val capsuleCenterY = top + side * 0.724f
        val isPauseAction = caption.startsWith("Ⅱ")
        val visibleCaption = if (isPauseAction) caption.substring(1).trim() else caption
        val maxTextWidth = side * if (isPauseAction) 0.47f else 0.53f
        while (captionPaint.measureText(visibleCaption) > maxTextWidth && captionPaint.textSize > side * 0.047f) {
            captionPaint.textSize -= 0.5f * d
        }
        val textWidth = captionPaint.measureText(visibleCaption)
        val pauseWidth = if (isPauseAction) side * 0.045f else 0f
        val gap = if (isPauseAction) side * 0.018f else 0f
        val groupWidth = pauseWidth + gap + textWidth
        val groupLeft = width / 2f - groupWidth / 2f
        val textBaseline = capsuleCenterY - (captionPaint.ascent() + captionPaint.descent()) / 2f

        if (isPauseAction) {
            val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = captionColor
                style = Paint.Style.FILL
                setShadowLayer(4f * d, 0f, 0f, captionColor)
            }
            val barWidth = side * 0.012f
            val barHeight = side * 0.060f
            val barGap = side * 0.010f
            val barsLeft = groupLeft + (pauseWidth - (barWidth * 2f + barGap)) / 2f
            val barsTop = capsuleCenterY - barHeight / 2f
            canvas.drawRoundRect(
                RectF(barsLeft, barsTop, barsLeft + barWidth, barsTop + barHeight),
                barWidth / 2f, barWidth / 2f, barPaint
            )
            canvas.drawRoundRect(
                RectF(barsLeft + barWidth + barGap, barsTop,
                    barsLeft + barWidth * 2f + barGap, barsTop + barHeight),
                barWidth / 2f, barWidth / 2f, barPaint
            )
            barPaint.clearShadowLayer()
        }

        canvas.drawText(visibleCaption, groupLeft + pauseWidth + gap, textBaseline, captionPaint)
        captionPaint.clearShadowLayer()

        // No additional outer rings or ripple circles; preserve the nine PNG frames as-is.
    }
}