package io.github.georgexie2333.usque

/**
 * Narrow JNI entry point for GilrTano's FREE/Usque onboarding.
 * The complete upstream engine remains pinned to GeorgeXie2333/usque-app v0.3.4.
 */
internal object NativeEngine {
    private val linked: Boolean = try {
        System.loadLibrary("usque_android")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun isLinked(): Boolean = linked

    fun registerConsumerWarp(locale: String): ByteArray? {
        require(locale.isNotBlank() && locale.length <= 32)
        if (!linked) return null
        return nativeRegisterConsumerWarp(locale)
    }

    fun validateWarpSecret(secret: ByteArray): Int {
        if (!linked) return ERROR_NOT_LINKED
        return nativeValidateWarpSecret(secret)
    }

    fun inspectWarpSecret(secret: ByteArray): String? {
        if (!linked) return null
        return nativeInspectWarpSecret(secret)
    }

    private external fun nativeRegisterConsumerWarp(locale: String): ByteArray?
    private external fun nativeValidateWarpSecret(secret: ByteArray): Int
    private external fun nativeInspectWarpSecret(secret: ByteArray): String?

    const val OK = 0
    const val ERROR_NOT_LINKED = -2
    const val ERROR_INVALID_WARP_SECRET = -3
}
