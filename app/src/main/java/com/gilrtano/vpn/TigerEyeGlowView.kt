package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Subtle connection-state light confined to the natural almond-shaped eye openings.
 * The original tiger artwork remains untouched; no drawn rings, ellipses or broad
 * cheek/eyebrow halos are used.
 */
class TigerEyeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val additive = PorterDuffXfermode(PorterDuff.Mode.ADD)
    private var connected = false
    private var level = 0f
    private var pulse = 0.5f
    private var transition: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    fun setConnected(value: Boolean) {
        if (connected == value) return
        connected = value
        transition?.cancel()
        pulseAnimator?.cancel()
        pulseAnimator = null

        transition = ValueAnimator.ofFloat(level, if (value) 1f else 0f).apply {
            duration = if (value) 850L else 700L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                level = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (value) {
            pulseAnimator = ValueAnimator.ofFloat(0.38f, 0.88f).apply {
                duration = 1700L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    pulse = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pulse = 0.5f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (level <= 0.005f || width <= 0 || height <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()
        // The mascot image is center-cropped to the whole portrait window.
        // Coordinates are normalized to the eye positions in the supplied screenshot.
        val cy = h * 0.235f
        val rx = w * 0.083f
        val ry = h * 0.0205f
        val strength = level * pulse
        drawEye(canvas, w * 0.285f, cy, rx, ry, strength, true)
        drawEye(canvas, w * 0.715f, cy, rx, ry, strength, false)
    }

    private fun drawEye(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        strength: Float,
        left: Boolean
    ) {
        val eye = Path().apply {
            // A tapered upper/lower lid silhouette follows the real eye opening.
            moveTo(cx - rx, cy)
            if (left) {
                cubicTo(cx - rx * 0.62f, cy - ry * 1.05f, cx + rx * 0.40f, cy - ry * 0.92f, cx + rx, cy - ry * 0.08f)
                cubicTo(cx + rx * 0.50f, cy + ry * 0.93f, cx - rx * 0.48f, cy + ry * 0.90f, cx - rx, cy)
            } else {
                cubicTo(cx - rx * 0.40f, cy - ry * 0.92f, cx + rx * 0.62f, cy - ry * 1.05f, cx + rx, cy)
                cubicTo(cx + rx * 0.48f, cy + ry * 0.90f, cx - rx * 0.50f, cy + ry * 0.93f, cx - rx, cy)
            }
            close()
        }

        val save = canvas.save()
        canvas.clipPath(eye)
        paint.xfermode = additive
        paint.shader = RadialGradient(
            cx, cy, rx * 1.10f,
            intArrayOf(
                Color.argb((78 * strength).toInt(), 110, 255, 135),
                Color.argb((42 * strength).toInt(), 50, 255, 95),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        paint.shader = null
        paint.xfermode = null
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        pulseAnimator?.cancel()
        transition = null
        pulseAnimator = null
        super.onDetachedFromWindow()
    }
}
