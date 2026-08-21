package dev.flexaccess.ezvpn.tunnelcore

import org.json.JSONArray
import org.json.JSONObject

/**
 * The FFI boundary, as data: the config JSON handed to the core's `connect`,
 * the network-config JSON it returns, and the interface plan the service
 * derives from that for `VpnService.Builder`. All pure, so the whole
 * connect-time decision (what gets routed, what gets carved out, which
 * families are blocked or allowed) is unit-testable without a device.
 */
object TunnelConfigJson {
    /**
     * The `connect` config document (see ezvpn `ios/ezvpn.h`). routes/routes6
     * are forwarded so the core can compute which server underlay addresses
     * overlap and must be excluded. The relay token is only valid with custom
     * relays; it is forwarded only then (the core rejects it otherwise).
     * `dnsProxy` (the service's fallback resolvers + protected sockets) is
     * emitted as the Android-only `dns_proxy` object when the profile uses
     * split DNS ([DnsProxy.isEnabled]); it is ignored otherwise.
     */
    fun build(
        profile: TunnelProfile,
        authKey: String,
        relayAuthToken: String?,
        dnsProxy: DnsProxyRequest? = null,
    ): String =
        JSONObject().apply {
            put("server_node_id", profile.serverNodeId)
            put("auth_key", authKey)
            put("relay_urls", JSONArray(profile.relayUrls))
            put("routes", JSONArray(profile.routes))
            put("routes6", JSONArray(profile.routes6))
            if (!relayAuthToken.isNullOrEmpty() && profile.relayUrls.isNotEmpty()) {
                put("relay_auth_token", relayAuthToken)
            }
            if (dnsProxy != null && DnsProxy.isEnabled(profile)) {
                put(
                    "dns_proxy",
                    JSONObject().apply {
                        put("addresses", JSONArray(listOf(DnsProxy.ADDRESS4, DnsProxy.ADDRESS6)))
                        put("match_domains", JSONArray(profile.dnsMatchDomains))
                        put("servers", JSONArray(profile.dnsServers))
                        put("fallback_servers", JSONArray(dnsProxy.fallbackServers))
                        put("fallback_fds", JSONArray(dnsProxy.fallbackFds))
                    },
                )
            }
        }.toString()
}

/**
 * What only the service knows at connect time for the in-tunnel forwarder:
 * the underlying network's resolvers (IPv6 link-local ones as
 * `fe80::1%<ifindex>`) and the fds of the UDP sockets it `protect()`ed for
 * reaching them, at most one per family.
 */
data class DnsProxyRequest(
    val fallbackServers: List<String>,
    val fallbackFds: List<Int>,
)

/** The core's network-config reply to `connect`; per-family fields are null when not assigned. */
data class NetworkConfig(
    val assignedIp: String?,
    val netmask: String?,
    val gateway: String?,
    val assignedIp6: String?,
    val prefixLen6: Int?,
    val gateway6: String?,
    val mtu: Int,
    /** Global-scope server underlay /32s a routed prefix would capture. */
    val excludedRoutes: List<String>,
    /** Same for IPv6 (/128s). */
    val excludedRoutes6: List<String>,
) {
    companion object {
        /**
         * Parse the reply; null when it is not the expected document (no mtu,
         * a non-numeric mtu or prefix_len6, or a prefix_len6 outside 0..128).
         */
        fun parse(json: String): NetworkConfig? = runCatching {
            val obj = JSONObject(json)
            if (!obj.has("mtu") || obj.isNull("mtu")) return null
            fun str(key: String) = if (obj.isNull(key)) null else obj.optString(key, null)
            val prefixLen6 = if (obj.isNull("prefix_len6")) null else obj.getInt("prefix_len6")
            if (prefixLen6 != null && prefixLen6 !in 0..128) return null
            NetworkConfig(
                assignedIp = str("assigned_ip"),
                netmask = str("netmask"),
                gateway = str("gateway"),
                assignedIp6 = str("assigned_ip6"),
                prefixLen6 = prefixLen6,
                gateway6 = str("gateway6"),
                mtu = obj.getInt("mtu"),
                excludedRoutes = obj.optJSONArray("excluded_routes").toStringList(),
                excludedRoutes6 = obj.optJSONArray("excluded_routes6").toStringList(),
            )
        }.getOrNull()
    }
}

/**
 * What the service will program into `VpnService.Builder`, per family. Routes
 * are the interface routes + the profile's split-tunnel prefixes; the core's
 * bypass set (server/relay underlay addresses those prefixes would capture) is
 * kept out of the tunnel in one of two ways, chosen by [bypassExcluded]:
 * `Builder.excludeRoute` where the platform has it (API 33+), else by
 * subtracting the bypass hosts from the routes (a /128 carved out of a /56 is
 * 72 prefixes, which is why the newer API is preferred when available).
 */
data class TunnelPlan(
    val mtu: Int,
    val address4: IpPrefix?,
    val address6: IpPrefix?,
    /** Routes to `addRoute`, sorted: the full set when [bypassExcluded], else the remainder after subtraction. */
    val routes4: List<IpPrefix>,
    val routes6: List<IpPrefix>,
    /** The bypass set: what to `excludeRoute` when [bypassExcluded], otherwise what was carved out (readout only). */
    val bypass4: List<IpPrefix>,
    val bypass6: List<IpPrefix>,
    /** True when the bypass set is to be installed with `excludeRoute` rather than subtracted from the routes. */
    val bypassExcluded: Boolean,
    /** What `addDnsServer` gets: the proxy addresses under split DNS, else the profile's servers. */
    val dnsServers: List<String>,
    val dnsMatchDomains: List<String>,
    /** Set when the forwarder is in use; the routed proxy addresses. */
    val dnsProxyAddresses: List<String>,
    /**
     * A remote-address label for the session (any assigned gateway). null is
     * the one fatal shape: the server assigned nothing usable.
     */
    val remoteAddress: String?,
    /** Warnings worth logging but not refusing over. */
    val warnings: List<String>,
    /** The profile's own resolvers (what the forwarder sends matched names to). */
    private val profileDnsServers: List<String> = dnsServers,
) {
    /** The applied state as the app's debug UI shows it. */
    fun runtimeInfo(): TunnelRuntimeInfo = TunnelRuntimeInfo(
        assignedIp = address4?.address,
        assignedIp6 = address6?.address,
        mtu = mtu,
        includedRoutes = routes4.map { it.toString() },
        includedRoutes6 = routes6.map { it.toString() },
        bypassRoutes = bypass4.map { it.toString() },
        bypassRoutes6 = bypass6.map { it.toString() },
        dnsServers = if (dnsProxyAddresses.isEmpty()) dnsServers else profileDnsServers,
        dnsMatchDomains = dnsMatchDomains,
        dnsProxyAddresses = dnsProxyAddresses,
    )

    companion object {
        /**
         * Derive the plan from the handshake result and the profile.
         * `excludeRoutes` says whether the platform offers
         * `Builder.excludeRoute` (API 33+); without it the bypass set is
         * subtracted from the routes. Routes for a family the server did not
         * assign can't be applied and are reported as warnings; DNS servers no
         * route covers are a warning too (usually a misconfiguration for a
         * private resolver, but legitimate for a public one, so warn instead
         * of refusing).
         */
        fun from(net: NetworkConfig, profile: TunnelProfile, excludeRoutes: Boolean = false): TunnelPlan {
            val warnings = ArrayList<String>()
            // What the tunnel effectively covers either way; only the install
            // shape differs.
            fun install(included: List<IpPrefix>, bypass: List<IpPrefix>): List<IpPrefix> =
                if (excludeRoutes) included.toSortedSet().toList() else RouteMath.subtract(included, bypass)
            val userRoutes4 = profile.routes.mapNotNull { IpPrefix.parse(it)?.takeIf { p -> p.isIpv4 } }
            val userRoutes6 = profile.routes6.mapNotNull { IpPrefix.parse(it)?.takeIf { p -> !p.isIpv4 } }

            var address4: IpPrefix? = null
            var routes4: List<IpPrefix> = emptyList()
            var bypass4: List<IpPrefix> = emptyList()
            val assigned4 = net.assignedIp?.let { ip ->
                val len = net.netmask?.let { IpPrefix.prefixLengthOfMask(it) } ?: 32
                IpLiteral.parse(ip)?.let { IpPrefix.of(it, len) }
            }
            if (assigned4 != null) {
                address4 = IpPrefix.host(net.assignedIp!!)
                val gateway = net.gateway?.let { IpPrefix.host(it) }
                val included = RouteMath.interfaceRoutes(assigned4, gateway) + userRoutes4
                bypass4 = net.excludedRoutes.mapNotNull { IpPrefix.parse(it) }.filter { it.isIpv4 }
                routes4 = install(included, bypass4)
            } else if (profile.routes.isNotEmpty()) {
                warnings += "ignoring ${profile.routes.size} IPv4 route(s): server assigned no IPv4 address"
            }

            var address6: IpPrefix? = null
            var routes6: List<IpPrefix> = emptyList()
            var bypass6: List<IpPrefix> = emptyList()
            val assigned6 = net.assignedIp6?.let { ip ->
                IpLiteral.parse(ip)?.let { IpPrefix.of(it, net.prefixLen6 ?: 128) }
            }
            if (assigned6 != null) {
                address6 = IpPrefix.host(net.assignedIp6!!)
                val gateway6 = net.gateway6?.let { IpPrefix.host(it) }
                val included = RouteMath.interfaceRoutes(assigned6, gateway6) + userRoutes6
                bypass6 = net.excludedRoutes6.mapNotNull { IpPrefix.parse(it) }.filter { !it.isIpv4 }
                routes6 = install(included, bypass6)
            } else if (profile.routes6.isNotEmpty()) {
                warnings += "ignoring ${profile.routes6.size} IPv6 route(s): server assigned no IPv6 address"
            }

            // Coverage is judged on what the tunnel really carries, so a resolver
            // sitting on a bypassed address warns under both install shapes.
            val effective = RouteMath.subtract(routes4, bypass4) + RouteMath.subtract(routes6, bypass6)
            val outside = SplitDns.serversOutsideRoutes(profile.dnsServers, effective)
            if (outside.isNotEmpty()) {
                warnings += "DNS server(s) ${outside.joinToString(", ")} not covered by any tunnel route"
            }

            // Split DNS: the OS resolves through the forwarder's proxy address,
            // which must be routed into the interface (a host route per
            // assigned family) so the core sees the queries.
            var dnsServers = profile.dnsServers
            var dnsProxyAddresses = emptyList<String>()
            if (DnsProxy.isEnabled(profile)) {
                val proxies = ArrayList<String>()
                if (address4 != null) {
                    routes4 = (routes4 + IpPrefix.host(DnsProxy.ADDRESS4)!!).sorted()
                    proxies += DnsProxy.ADDRESS4
                }
                if (address6 != null) {
                    routes6 = (routes6 + IpPrefix.host(DnsProxy.ADDRESS6)!!).sorted()
                    proxies += DnsProxy.ADDRESS6
                }
                dnsServers = proxies
                dnsProxyAddresses = proxies
            }

            return TunnelPlan(
                mtu = net.mtu,
                address4 = address4,
                address6 = address6,
                routes4 = routes4,
                routes6 = routes6,
                bypass4 = bypass4,
                bypass6 = bypass6,
                bypassExcluded = excludeRoutes,
                dnsServers = dnsServers,
                dnsMatchDomains = profile.dnsMatchDomains,
                dnsProxyAddresses = dnsProxyAddresses,
                remoteAddress = net.gateway ?: net.gateway6,
                warnings = warnings,
                profileDnsServers = profile.dnsServers,
            )
        }
    }
}
