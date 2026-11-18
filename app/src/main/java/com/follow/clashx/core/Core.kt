package com.follow.clashx.core

import com.follow.clashx.core.mobile.Mobile
import com.follow.clashx.core.mobile.ResultHandler
import com.follow.clashx.core.mobile.TunHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/**
 * Thin Android bridge matching FlClashX v0.5.7.
 * The gomobile-generated Mobile class and native libclash are supplied by
 * the ARM64 core AAR built from the pinned upstream tag.
 */
object Core {
    fun load() = Mobile.touch()
    fun forceGC() = Mobile.forceGC()
    fun updateDNS(dns: String) = Mobile.updateDns(dns)
    fun resetNetwork() = Mobile.resetNetwork()

    private fun parseAddress(value: String): InetSocketAddress {
        val uri = URI("tcp://$value")
        val host = requireNotNull(uri.host) { "Missing host in address: $value" }
        require(uri.port >= 0) { "Missing port in address: $value" }
        return InetSocketAddress(InetAddress.getByName(host), uri.port)
    }

    fun startTun(
        fd: Int,
        protect: (Int) -> Boolean,
        resolveUid: (Int, InetSocketAddress, InetSocketAddress) -> Int,
        resolvePackage: (Int) -> String,
        stack: String,
        address: String,
        dns: String,
        options: String,
    ): Boolean = Mobile.startTun(
        fd,
        object : TunHandler {
            override fun protect(fd: Int): Boolean = protect(fd)
            override fun resolveUid(protocol: Int, source: String, target: String): Int =
                resolveUid(protocol, parseAddress(source), parseAddress(target))
            override fun resolvePackage(uid: Int): String = resolvePackage(uid)
        },
        stack, address, dns, options,
    )

    fun invokeMethod(data: String, callback: (ByteArray?) -> Unit) {
        Mobile.invokeMethod(data, ResultHandler { result -> callback(result) })
    }

    fun updateEventListener(callback: ((ByteArray) -> Unit)?) {
        Mobile.setEventListener(callback?.let { listener ->
            ResultHandler { result -> listener(result ?: ByteArray(0)) }
        })
    }

    fun quickSetup(initParams: String, setupParams: String, callback: (String?) -> Unit) {
        Mobile.quickSetup(initParams, setupParams, ResultHandler { result ->
            callback(result?.decodeToString())
        })
    }

    fun stopTun() = Mobile.stopTun()
    fun startListener() = Mobile.startListener()
    fun stopListener() = Mobile.stopListener()
}
