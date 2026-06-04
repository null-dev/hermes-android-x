package com.hermesandroid.bridge.lifecycle

import java.net.Inet4Address
import java.net.NetworkInterface

object LocalIp {
    /** Returns the first site-local IPv4 (e.g. 192.168.x.x / 10.x / Tailscale 100.x), or "0.0.0.0". */
    fun best(): String {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
