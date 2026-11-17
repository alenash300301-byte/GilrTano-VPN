package io.github.madeye.meow.core

/**
 * Minimal JNI bridge to the Meow Android ARM64 native engine.
 *
 * JNI symbol names intentionally match the upstream meow-android MeowCore
 * object. The native library is built from the pinned upstream source in CI.
 */
object MeowCore {
    init {
        System.loadLibrary("meow_android_ffi")
        nativeInit()
    }

    external fun nativeInit()
    external fun nativeSetHomeDir(dir: String)
    external fun nativeStartEngine(addr: String, secret: String, mode: String, builtinDns: Boolean): Int
    external fun nativeStopEngine()
    external fun nativeStartTun2Socks(vpnService: Any, fd: Int, dnsPort: Int): Int
    external fun nativeIsRunning(): Boolean
    external fun nativeTunIpv6(): Boolean
    external fun nativeGetUploadTraffic(): Long
    external fun nativeGetDownloadTraffic(): Long
    external fun nativeGetLastError(): String
    external fun nativeVersion(): String
    external fun nativeGetLogs(): String
    external fun nativeValidateConfig(yaml: String): Int
}
