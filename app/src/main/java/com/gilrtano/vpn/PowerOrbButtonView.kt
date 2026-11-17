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
        pulse?.cancel()
        morphAnimator?.cancel()
        if (animationsAllowed) {
            morph = 0f
            morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300L
                interpolator = PathInterpolator(0.42f, 0f, 0.58f, 1f)
                addUpdateListener { morph = it.animatedValue as Float; invalidate() }
                start()
            }
            when (next) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED, State.SUSPENDED -> {
                    phase = 0f
                    animate().cancel()
                    animate().scaleX(1f).scaleY(1f).setDuration(220L).start()
                }
            }
        } else {
            morph = 1f
            phase = 0f
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
            startIdleBreath()
            when (state) {
                State.CONNECTING -> startMotion(1000L, true)
                State.CONNECTED -> startMotion(1100L, false)
                State.DISCONNECTED, State.SUSPENDED -> Unit
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
                pressAnimator?.cancel()
                pressFeedback?.cancel()
                pressFeedback = ValueAnimator.ofFloat(pressAmount, 1f).apply {
                    duration = 110L
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
                    start()
                }
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 0.925f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 0.925f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, -3f * d),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_X, rotationX, 2.2f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_Y, rotationY, -1.5f)
                    )
                    duration = 105L
                    interpolator = DecelerateInterpolator(2f)
                    start()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelRisingHold()
                pressAnimator?.cancel()
                pressFeedback?.cancel()
                pressFeedback = ValueAnimator.ofFloat(pressAmount, 0f).apply {
                    duration = 360L
                    interpolator = OvershootInterpolator(1.8f)
                    addUpdateListener { pressAmount = it.animatedValue as Float; invalidate() }
                    start()
                }
                pressAnimator = AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_X, scaleX, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.SCALE_Y, scaleY, 1f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.TRANSLATION_Z, translationZ, 0f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_X, rotationX, 0f),
                        ObjectAnimator.ofFloat(this@PowerOrbButtonView, View.ROTATION_Y, rotationY, 0f)
                    )
                    duration = 360L
                    interpolator = OvershootInterpolator(1.35f)
                    start()
                }
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
            State.CONNECTED -> 4 + (phase * 5).toInt().coerceIn(0, 4)
            State.SUSPENDED -> 0
        }
        val frame = buttonFrames[frameIndex]
        val dst = RectF(left, top, left + side, top + side)
        framePaint.alpha = 255
        canvas.drawBitmap(frame, null, dst, framePaint)

        // Simplify the busy center of the source artwork while preserving
        // the animated outer flame ring and all nine original frames.
        val cx = width / 2f
        val cy = top + side * 0.445f
        val orbRadius = side * 0.292f
        canvas.drawCircle(cx, cy, orbRadius, orbDiskPaint)
        // A single, clear power glyph replaces the multiple competing rings
        // baked into the artwork. Its color follows the connection state.
        powerGlyphPaint.color = when (state) {
            State.DISCONNECTED, State.CONNECTED -> Color.rgb(105, 255, 120)
            State.CONNECTING -> Color.rgb(255, 205, 105)
            State.SUSPENDED -> Color.rgb(155, 160, 155)
        }
        val glyph = Path().apply {
            moveTo(cx, cy - side * 0.155f)
            lineTo(cx, cy + side * 0.005f)
        }
        canvas.drawPath(glyph, powerGlyphPaint)
        val powerArc = Path().apply {
            moveTo(cx - side * 0.105f, cy - side * 0.070f)
            cubicTo(cx - side * 0.205f, cy + side * 0.015f,
                cx - side * 0.175f, cy + side * 0.155f, cx, cy + side * 0.155f)
            cubicTo(cx + side * 0.175f, cy + side * 0.155f,
                cx + side * 0.205f, cy + side * 0.015f, cx + side * 0.105f, cy - side * 0.070f)
        }
        canvas.drawPath(powerArc, powerGlyphPaint)

        // The status label is kept separate from the power symbol and centered
        // in a clean text zone below it.
        captionPaint.textAlign = Paint.Align.CENTER
        captionPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        captionPaint.textSize = side * 0.066f
        val label = caption
        val maxWidth = side * 0.70f
        var size = captionPaint.textSize
        while (captionPaint.measureText(label) > maxWidth && size > side * 0.040f) {
            size -= 0.5f * d
            captionPaint.textSize = size
        }
        val baseline = top + side * 0.594f - (captionPaint.ascent() + captionPaint.descent()) / 2f
        val textWidth = captionPaint.measureText(label)
        val plate = RectF(
            width / 2f - textWidth / 2f - 12f * d,
            baseline + captionPaint.ascent() - 6f * d,
            width / 2f + textWidth / 2f + 12f * d,
            baseline + captionPaint.descent() + 6f * d
        )
        val labelColor = when (state) {
            State.DISCONNECTED -> Color.WHITE
            State.CONNECTING -> Color.rgb(255, 211, 130)
            State.CONNECTED -> Color.rgb(145, 255, 160)
            State.SUSPENDED -> Color.rgb(205, 210, 205)
        }
        captionPlatePaint.color = Color.argb(235, 0, 0, 0)
        captionPlatePaint.strokeWidth = 1.2f * d
        canvas.drawRoundRect(plate, 9f * d, 9f * d, fillPaint.apply { color = Color.argb(235, 0, 0, 0); style = Paint.Style.FILL })
        captionPlatePaint.color = when (state) {
            State.DISCONNECTED -> Color.argb(180, 130, 255, 140)
            State.CONNECTING -> Color.argb(190, 255, 195, 90)
            State.CONNECTED -> Color.argb(190, 105, 255, 130)
            State.SUSPENDED -> Color.argb(150, 180, 185, 180)
        }
        canvas.drawRoundRect(plate, 9f * d, 9f * d, captionPlatePaint)
        captionPaint.color = labelColor
        captionPaint.setShadowLayer(4f * d, 0f, 0f, Color.BLACK)
        canvas.drawText(label, width / 2f, baseline, captionPaint)
        captionPaint.clearShadowLayer()

        // Subtle breathing halo and animated contact ripple stay independent
        // of the static artwork.
        if (pressAmount > 0.01f) {
            val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = if (connecting) Color.rgb(255, 190, 70) else Color.rgb(90, 255, 78)
                alpha = (150f * pressAmount).toInt()
                strokeWidth = (2f + 2f * pressAmount) * d
            }
            canvas.drawCircle(width / 2f, height / 2f, side * (0.43f + pressAmount * 0.035f), ripplePaint)
        }
        if (breath > 0f && state != State.CONNECTED) {
            val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.argb((25 + breath * 35).toInt(), 95, 255, 85)
                strokeWidth = 1.2f * d
            }
            canvas.drawCircle(width / 2f, height / 2f, side * (0.49f + breath * 0.006f), halo)
        }
    }
}