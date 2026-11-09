package com.phlox.tvwebbrowser.send

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * What the TV keeps for sending to it: the inbox of its QR code (made once, the same each time until the
 * user makes a new link), the port of its server in the local network (the same each time when it can),
 * its name and addresses.
 */
class SendSettings(private val context: Context, private val prefs: SharedPreferences) {
    companion object {
        private const val INBOX_KEY = "send_inbox"
        private const val SERVER_PORT_KEY = "send_server_port"
        /** The phone sync of 3.0 betas */
        private val LEGACY_KEYS = listOf("sync_device_id", "sync_server_port", "sync_used_pairing")
    }

    val inbox: String
        get() = prefs.getString(INBOX_KEY, null) ?: newInbox()

    /** A new link: devices that know the old one can not send any more */
    fun newInbox(): String = SendLinks.newInbox().also { prefs.edit().putString(INBOX_KEY, it).apply() }

    /** 0 until the server got one */
    var serverPort: Int
        get() = prefs.getInt(SERVER_PORT_KEY, 0)
        set(value) {
            prefs.edit().putInt(SERVER_PORT_KEY, value).apply()
        }

    /** The name the user gave the device, else its model */
    val deviceName: String
        get() {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            } else {
                null
            }
            return (name?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV").take(SendRules.MAX_FROM_LENGTH)
        }

    /**
     * The IPv4 addresses of this device in its local networks (Ethernet and Wi-Fi may both be up), those of the
     * default network first: the page of the site tries them in this order, and browsers that can not try them
     * open the first one. Interfaces that are no network of the system are left out (the Wi-Fi Direct group a
     * Shield keeps for its remote, `p2p-wlan0-0`), unless the system tells no networks.
     */
    fun localAddresses(): List<String> {
        val fromSystem = try {
            networkAddresses()
        } catch (e: Exception) {
            emptyList()
        }
        if (fromSystem.isNotEmpty()) return fromSystem
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("p2p") }
                .flatMap { it.inetAddresses.toList() }
                .let(::localIpv4)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun networkAddresses(): List<String> {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val active = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) connectivity.activeNetwork else null
        @Suppress("DEPRECATION")
        val networks = connectivity.allNetworks.sortedBy { if (it == active) 0 else 1 }
        return localIpv4(networks.flatMap { network ->
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
        })
    }

    private fun localIpv4(addresses: List<InetAddress>): List<String> = addresses
        .filterIsInstance<Inet4Address>()
        .filter { it.isSiteLocalAddress || it.isLinkLocalAddress }
        //link-local addresses (169.254.*) only if there is nothing else; the order is kept otherwise
        .sortedBy { if (it.isSiteLocalAddress) 0 else 1 }
        .mapNotNull { it.hostAddress }
        .distinct()

    /** Drops what the phone sync of 3.0 betas kept in the prefs */
    fun dropLegacy() {
        if (LEGACY_KEYS.none(prefs::contains)) return
        prefs.edit().apply { LEGACY_KEYS.forEach(::remove) }.apply()
    }
}
