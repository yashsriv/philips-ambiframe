package com.ambiframe.tv.setup

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Where the JointSpace API might be when this app runs on the TV itself: loopback first, then the
 * TV's own Wi-Fi/Ethernet address (some firmwares only listen on the LAN interface).
 */
internal fun candidateHosts(context: Context): List<String> =
    (listOf(LOOPBACK) + localIpv4Addresses(context)).distinct()

internal const val LOOPBACK = "127.0.0.1"

private fun localIpv4Addresses(context: Context): List<String> {
    val fromActiveNetwork = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.getLinkProperties(connectivity.activeNetwork)?.linkAddresses.orEmpty()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
    if (fromActiveNetwork.isNotEmpty()) return fromActiveNetwork

    return runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}

private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

internal fun isValidIpv4(value: String): Boolean = IPV4.matches(value)
