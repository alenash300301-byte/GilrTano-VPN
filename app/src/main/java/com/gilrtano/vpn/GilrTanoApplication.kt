package com.gilrtano.vpn

import android.app.Application
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GilrTanoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val version = runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
                }.getOrDefault("unknown")
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
                val report = buildString {
                    appendLine("GilrTano VPN crash report")
                    appendLine("Time: $stamp")
                    appendLine("Version: $version")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(writer.toString())
                }
                File(noBackupFilesDir, "last_crash.txt").writeText(report)
                Log.e("GilrTanoCrash", "Uncaught exception saved to last_crash.txt", error)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
