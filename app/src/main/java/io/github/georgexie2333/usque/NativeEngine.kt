package io.github.georgexie2333.usque

import com.gilrtano.vpn.GilrTanoVpnService
import java.io.File

/**
 * Narrow JNI adapter for the upstream Usque v0.3.4 Android ABI.
 * Kept separate from GilrTano's existing FREE backend until profile/identity
 * provisioning is wired and verified.
 */
internal object NativeEngine {
    private val loaded: Boolean = try {
        System.loadLibrary("usque_android")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isLinked(): Boolean = loaded

    fun isReady(): Boolean = loaded && runCatching { nativeIsReady() }.getOrDefault(false)

    fun start(
        tunFd: Int,
        profileJson: String,
        warpSecret: ByteArray,
        service: GilrTanoVpnService,
    ): Int {
        if (!loaded) return ERROR_NOT_LINKED
        val emptyPassword = ByteArray(0)
        return try {
            nativeStart(
                tunFd,
                profileJson,
                warpSecret,
                emptyPassword,
                File(service.noBackupFilesDir, "usque_config").absolutePath,
                service,
            )
        } finally {
            emptyPassword.fill(0)
        }
    }

    fun stop(): Boolean =
        !loaded || runCatching { nativeStopConfirmed() }.getOrDefault(false)

    fun cancel() {
        if (loaded) runCatching { nativeCancel() }
    }

    fun notifyNetworkChanged(generation: Long) {
        if (loaded) runCatching { nativeNotifyNetworkChanged(generation) }
    }

    fun snapshot(): String? =
        if (loaded) runCatching { nativeSnapshot() }.getOrNull() else null

    private external fun nativeIsReady(): Boolean
    private external fun nativeStart(
        tunFileDescriptor: Int,
        profileJson: String,
        warpSecret: ByteArray,
        proxyPassword: ByteArray,
        geoCacheDirectory: String,
        vpnService: GilrTanoVpnService,
    ): Int
    private external fun nativeStopConfirmed(): Boolean
    private external fun nativeCancel()
    private external fun nativeNotifyNetworkChanged(generation: Long)
    private external fun nativeSnapshot(): String?

    const val ERROR_NOT_LINKED = -2
}
