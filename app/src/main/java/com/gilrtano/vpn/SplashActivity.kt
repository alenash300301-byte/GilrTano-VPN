package com.gilrtano.vpn

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView

class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        val artwork = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            setImageResource(R.drawable.gilrtano_splash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "GilrTano"
            alpha = 0f
        }
        setContentView(artwork)

        artwork.animate()
            .alpha(1f)
            .setDuration(450L)
            .setInterpolator(PathInterpolator(0.2f, 0.0f, 0.0f, 1.0f))
            .start()

        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.gilrtano_fade_in, R.anim.gilrtano_fade_out)
            finish()
        }, 1100L)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}