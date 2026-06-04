package com.hermesandroid.bridge.lifecycle

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIp {
    private fun candidates(): List<Inet4Address> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
    } catch (e: Exception) {
        emptyList()
    }

    /** First non-loopback non-link-local IPv4, or "0.0.0.0". */
    fun best(): String = candidates().firstOrNull()?.hostAddress ?: "0.0.0.0"

    /** All non-loopback non-link-local IPv4 addresses (covers multi-homed / VPN). */
    fun all(): List<String> = candidates().mapNotNull { it.hostAddress }.ifEmpty { listOf("0.0.0.0") }
}
