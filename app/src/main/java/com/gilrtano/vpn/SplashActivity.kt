package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * Branded launch reveal. Uses the uploaded high-resolution final GilrTano tiger artwork,
 * fades it in, gently pulses the eye highlights, then crossfades to the app.
 */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.setBackgroundDrawableResource(android.R.color.black)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val artwork = ImageView(this).apply {
            setImageResource(R.drawable.gilrtano_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            alpha = 0f
            contentDescription = "GilrTano tiger"
        }
        root.addView(artwork, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val eyes = EyePulseView()
        root.addView(eyes, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        setContentView(root)

        artwork.animate()
            .alpha(1f)
            .setDuration(1150L)
            .setInterpolator(DecelerateInterpolator())
            .start()
        eyes.startPulse()
        handler.postDelayed({ openMain() }, 2350L)
    }

    private fun openMain() {
        if (launched || isFinishing) return
        launched = true
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private inner class EyePulseView : View(this@SplashActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private val pulse = android.animation.ValueAnimator.ofFloat(0.22f, 0.72f).apply {
            duration = 1250L
            repeatMode = android.animation.ValueAnimator.REVERSE
            repeatCount = android.animation.ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }

        fun startPulse() { pulse.start() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // Subtle green halo aligned with the tiger eyes in the centered artwork.
            val radius = (w * 0.075f).coerceAtLeast(24f)
            val cy = h * 0.365f
            drawGlow(canvas, w * 0.365f, cy, radius)
            drawGlow(canvas, w * 0.635f, cy, radius)
        }

        private fun drawGlow(canvas: Canvas, x: Float, y: Float, radius: Float) {
            paint.alpha = (phase * 115f).toInt().coerceIn(0, 115)
            paint.shader = RadialGradient(
                x, y, radius,
                intArrayOf(Color.argb(170, 20, 255, 70), Color.TRANSPARENT),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x, y, radius, paint)
            paint.shader = null
        }
    }
}
