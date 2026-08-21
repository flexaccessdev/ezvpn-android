package dev.flexaccess.ezvpn.tunnelcore

/**
 * Tunnel DNS validation and the split-DNS decision.
 *
 * Android's `VpnService.Builder` has no per-domain (split) DNS: `addDnsServer`
 * replaces the resolvers for every app the VPN applies to, for all names, and
 * an app cannot bind port 53. So when a profile names match domains, the app
 * does what Tailscale's MagicDNS does: it points the VPN's DNS at a proxy
 * address routed into the tunnel interface ([DnsProxy]) and the Rust core
 * answers those packets itself — names under the match domains go to the
 * profile's DNS servers through the tunnel, everything else to the underlying
 * network's resolvers through `protect()`ed sockets. Without match domains the
 * servers are handed to the OS directly and answer every name, as before.
 */
object SplitDns {
    /**
     * Why a profile's DNS settings are unusable, as a message for the editor;
     * null when acceptable. Servers must be IP literals (they must be reachable
     * without resolution); match domains need servers to forward to, and must
     * look like domain names.
     */
    fun validationError(servers: List<String>, matchDomains: List<String> = emptyList()): String? {
        servers.firstOrNull { !IpLiteral.isAddress(it) }?.let { return "DNS server is not an IP address: $it" }
        if (matchDomains.isNotEmpty() && servers.isEmpty()) {
            return "Match domains need at least one DNS server to forward to."
        }
        matchDomains.firstOrNull { !isDomain(normalizeDomain(it)) }?.let { return "Not a domain name: $it" }
        return null
    }

    /** Lowercase, no surrounding dots/whitespace — the form the forwarder matches on. */
    fun normalizeDomain(raw: String): String = raw.trim().trim('.').lowercase()

    private val labelPattern = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

    fun isDomain(normalized: String): Boolean =
        normalized.isNotEmpty() && normalized.length <= 253 &&
            normalized.split('.').all { labelPattern.matches(it) }

    /**
     * The DNS servers not covered by any same-family applied route — queries
     * to them will not ride the tunnel, which for a private resolver means they
     * silently go to the underlying network and fail. Unparseable servers are
     * reported too: they are certainly not reachable.
     */
    fun serversOutsideRoutes(servers: List<String>, routes: List<IpPrefix>): List<String> =
        servers.filter { server ->
            val host = IpPrefix.host(server) ?: return@filter true
            routes.none { it.contains(host) }
        }
}

/**
 * The in-tunnel DNS forwarder's fixed addresses: where the OS is told its DNS
 * server lives while split DNS is on. Both are routed as host routes into the
 * tunnel interface for whichever families the server assigned; the Rust core
 * intercepts UDP/53 to them before anything reaches the server. 198.18.0.0/15
 * (benchmarking, RFC 2544) and a ULA are used so they never collide with a
 * real network.
 */
object DnsProxy {
    const val ADDRESS4 = "198.18.0.53"
    const val ADDRESS6 = "fd7e:7a00:d45::53"

    /** Whether the profile's DNS settings call for the forwarder. */
    fun isEnabled(profile: TunnelProfile): Boolean =
        profile.dnsServers.isNotEmpty() && profile.dnsMatchDomains.isNotEmpty()
}
