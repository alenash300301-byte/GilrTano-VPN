package com.gilrtano.vpn

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import java.io.File

class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var crashReport: File? = null

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

        val report = File(noBackupFilesDir, "last_crash.txt")
        if (report.isFile && report.length() > 0L) {
            crashReport = report
            handler.postDelayed({ showCrashReport(report) }, 500L)
        } else {
            scheduleMainActivity()
        }
    }

    private fun scheduleMainActivity() {
        handler.postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            overridePendingTransition(R.anim.gilrtano_fade_in, R.anim.gilrtano_fade_out)
            finish()
        }, 1100L)
    }

    private fun showCrashReport(report: File) {
        if (isFinishing || isDestroyed) return
        val details = runCatching { report.readText().take(12000) }
            .getOrDefault("Не удалось прочитать журнал сбоя.")
        AlertDialog.Builder(this)
            .setTitle("Найден отчёт о предыдущем сбое")
            .setMessage("Отправь этот отчёт в ChatGPT — по нему можно определить причину вылета.\n\n" +
                details.take(1800))
            .setPositiveButton("Отправить отчёт") { _, _ ->
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "GilrTano VPN — отчёт о сбое")
                    putExtra(Intent.EXTRA_TEXT, details)
                }
                startActivity(Intent.createChooser(share, "Отправить отчёт"))
            }
            .setNegativeButton("Продолжить запуск") { _, _ ->
                report.delete()
                crashReport = null
                scheduleMainActivity()
            }
            .setNeutralButton("Закрыть", null)
            .setOnCancelListener { }
            .show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
