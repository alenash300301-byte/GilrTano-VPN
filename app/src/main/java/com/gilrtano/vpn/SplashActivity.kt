package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.LinearGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import kotlin.math.min

/**
 * GilrTano cinematic launch scene.
 * The uploaded PNG remains the hero artwork; lightweight Canvas layers animate
 * eye light and a restrained metallic-logo glint without a heavy video.
 */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false
    private var eyePulse: ValueAnimator? = null
    private var logoGlint: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.setBackgroundDrawableResource(android.R.color.black)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val artwork = ImageView(this).apply {
            setImageResource(R.drawable.gilrtano_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            scaleX = 1.025f
            scaleY = 1.025f
            contentDescription = "GilrTano"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.addView(artwork, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val effects = CinematicEffectsView()
        root.addView(effects, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)
        // Tap anywhere on the splash to enter the app immediately.
        root.isClickable = true
        root.setOnClickListener { openMain() }

        artwork.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(1450L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()

        effects.start()
        handler.postDelayed({ openMain() }, 3000L)
    }

    private fun openMain() {
        if (launched || isFinishing) return
        launched = true
        handler.removeCallbacksAndMessages(null)
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onPause() {
        eyePulse?.pause()
        logoGlint?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        eyePulse?.resume()
        logoGlint?.resume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        eyePulse?.cancel()
        logoGlint?.cancel()
        super.onDestroy()
    }

    private inner class CinematicEffectsView : View(this@SplashActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var eyeIntensity = 0.25f
        private var glintPosition = -0.2f
        private val imageRect = RectF()
        private val drawable = resources.getDrawable(R.drawable.gilrtano_splash, theme)
        private val imageWidth = drawable.intrinsicWidth.toFloat().coerceAtLeast(1f)
        private val imageHeight = drawable.intrinsicHeight.toFloat().coerceAtLeast(1f)

        fun start() {
            eyePulse = ValueAnimator.ofFloat(0.18f, 0.78f).apply {
                duration = 1350L
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    eyeIntensity = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            logoGlint = ValueAnimator.ofFloat(-0.25f, 1.25f).apply {
                duration = 2300L
                startDelay = 900L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    glintPosition = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val scale = min(width / imageWidth, height / imageHeight)
            val drawW = imageWidth * scale
            val drawH = imageHeight * scale
            val left = (width - drawW) / 2f
            val top = (height - drawH) / 2f
            imageRect.set(left, top, left + drawW, top + drawH)

            // Eye centers are mapped to the actual FIT_CENTER image bounds,
            // so the green glow stays aligned on different phone aspect ratios.
            val eyeY = top + drawH * 0.285f
            val eyeRadius = drawW * 0.085f
            drawEyeGlow(canvas, left + drawW * 0.335f, eyeY, eyeRadius)
            drawEyeGlow(canvas, left + drawW * 0.665f, eyeY, eyeRadius)

            // A fine red-white reflection travels across the metallic wordmark.
            // It is intentionally subtle so the original lettering remains legible.
            val bandTop = top + drawH * 0.735f
            val bandBottom = top + drawH * 0.865f
            val sweepX = left + drawW * glintPosition
            val halfWidth = drawW * 0.11f
            paint.shader = LinearGradient(
                sweepX - halfWidth, 0f, sweepX + halfWidth, 0f,
                intArrayOf(Color.TRANSPARENT, Color.argb(34, 255, 55, 55), Color.argb(72, 255, 235, 225), Color.argb(34, 255, 55, 55), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawRect(left, bandTop, left + drawW, bandBottom, paint)
            paint.shader = null
        }

        private fun drawEyeGlow(canvas: Canvas, x: Float, y: Float, radius: Float) {
            paint.alpha = (eyeIntensity * 150f).toInt().coerceIn(0, 150)
            paint.shader = RadialGradient(
                x, y, radius,
                intArrayOf(Color.argb(185, 20, 255, 75), Color.argb(75, 0, 255, 45), Color.TRANSPARENT),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
            paint.alpha = 255
        }
    }
}
