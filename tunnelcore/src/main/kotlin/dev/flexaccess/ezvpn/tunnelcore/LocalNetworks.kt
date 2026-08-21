package dev.flexaccess.ezvpn.tunnelcore

/**
 * One network the device is attached to: the on-link subnet of an up,
 * non-loopback broadcast interface (Wi-Fi, Ethernet). Point-to-point links
 * (cellular) carry no on-link subnet to conflict with, and the app skips them
 * when enumerating — the Android enumeration itself lives in the app module
 * (it reads `ConnectivityManager` link properties), this type and the check
 * stay pure so the logic is testable with fixtures.
 */
data class LocalNetwork(
    /** Interface name (e.g. "wlan0"), for the refusal message. */
    val interfaceName: String,
    /** The on-link subnet, host bits zeroed. */
    val prefix: IpPrefix,
)

object LocalNetworks {
    /**
     * The first configured split-tunnel prefix that overlaps a network the
     * device is currently on, rendered as a refusal message; null when clear.
     * Malformed CIDRs are skipped here — the caller drops them from the
     * applied route set anyway. Routing the local subnet into the tunnel would
     * cut off on-link hosts, including the gateway carrying the tunnel's own
     * underlay traffic, so the service refuses to start on a conflict (same
     * policy as ezvpn-apple).
     */
    fun splitTunnelConflict(
        routes: List<String>,
        routes6: List<String>,
        locals: List<LocalNetwork>,
    ): String? {
        for (cidr in routes + routes6) {
            val prefix = IpPrefix.parse(cidr) ?: continue
            for (local in locals) {
                if (prefix.overlaps(local.prefix)) {
                    return "refusing to start: split-tunnel route $cidr overlaps " +
                        "current network ${local.prefix} on ${local.interfaceName}"
                }
            }
        }
        return null
    }

    /** IPv6 link-local (fe80::/10) lives on every interface and never routes. */
    fun isLinkLocalV6(bytes: ByteArray): Boolean =
        bytes.size == 16 && (bytes[0].toInt() and 0xff) == 0xfe && (bytes[1].toInt() and 0xc0) == 0x80
}
