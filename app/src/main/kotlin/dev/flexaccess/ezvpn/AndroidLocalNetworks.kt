package dev.flexaccess.ezvpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dev.flexaccess.ezvpn.tunnelcore.IpPrefix
import dev.flexaccess.ezvpn.tunnelcore.LocalNetwork
import dev.flexaccess.ezvpn.tunnelcore.LocalNetworks

/**
 * The on-link subnets of the networks the device is attached to, for the
 * split-tunnel conflict check ([LocalNetworks.splitTunnelConflict]). Only
 * broadcast networks (Wi-Fi, Ethernet) carry an on-link subnet; cellular is
 * point-to-point and our own VPN interface must not count. Host addresses
 * (/32, /128) and IPv6 link-local are skipped as they never conflict.
 */
object AndroidLocalNetworks {
    fun current(cm: ConnectivityManager): List<LocalNetwork> {
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        return networks.flatMap { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@flatMap emptyList()
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@flatMap emptyList()
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                return@flatMap emptyList()
            }
            val link = cm.getLinkProperties(network) ?: return@flatMap emptyList()
            val name = link.interfaceName ?: "?"
            link.linkAddresses.mapNotNull { la ->
                val bytes = la.address.address
                when {
                    bytes.size == 4 && la.prefixLength >= 32 -> null
                    bytes.size == 16 && (la.prefixLength >= 128 || LocalNetworks.isLinkLocalV6(bytes)) -> null
                    else -> IpPrefix.of(bytes, la.prefixLength)?.let { LocalNetwork(name, it) }
                }
            }
        }
    }
}
