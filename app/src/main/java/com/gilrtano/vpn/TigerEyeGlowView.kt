package com.gilrtano.vpn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * Animates only the naturally green pixels of the tiger's original eyes.
 * The mask is pixel-derived from the supplied tiger artwork: no ellipses,
 * rings, drawn outlines or broad glow shapes are rendered.
 */
class TigerEyeGlowView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val mask: Bitmap? = BitmapFactory.decodeResource(resources, R.drawable.tiger_eye_mask)
    private val destination = RectF()
    private var connected = false
    private var strength = 0f
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

        transition = ValueAnimator.ofFloat(strength, if (value) 1f else 0f).apply {
            duration = if (value) 900L else 700L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                strength = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        if (value) {
            pulseAnimator = ValueAnimator.ofFloat(0.55f, 1f).apply {
                duration = 1550L
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
        val bitmap = mask ?: return
        if (strength <= 0.005f || width == 0 || height == 0) return

        // Mask crop is aligned to the 681x1536 TECNO CM7 portrait screenshot.
        // Scale proportionally with the full-screen tiger background.
        val sx = width / 681f
        val sy = height / 1536f
        destination.set(140f * sx, 345f * sy, 540f * sx, 415f * sy)
        paint.alpha = (175f * strength * pulse).toInt().coerceIn(0, 175)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onDetachedFromWindow() {
        transition?.cancel()
        pulseAnimator?.cancel()
        transition = null
        pulseAnimator = null
        super.onDetachedFromWindow()
    }
}
