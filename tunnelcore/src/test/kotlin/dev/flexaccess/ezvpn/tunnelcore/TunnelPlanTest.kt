package dev.flexaccess.ezvpn.tunnelcore

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelPlanTest {
    private val profile = TunnelProfile(
        name = "home",
        serverNodeId = "abc",
        authKeyId = "k1",
        relayUrls = listOf("https://relay.example/"),
        routes = listOf("10.0.0.0/8"),
        routes6 = listOf("fd00::/8"),
        dnsServers = listOf("10.0.0.53"),
    )

    private val dualStack = """
        {"assigned_ip":"10.124.0.2","netmask":"255.255.255.255","gateway":"10.124.0.1",
         "assigned_ip6":"fd7a::2","prefix_len6":128,"gateway6":"fd7a::1","mtu":1280,
         "excluded_routes":["10.9.9.9/32"],"excluded_routes6":[]}
    """.trimIndent()

    @Test
    fun configJsonShape() {
        val json = JSONObject(TunnelConfigJson.build(profile, "ed25519-sec:xyz", "tok"))
        assertEquals("abc", json.getString("server_node_id"))
        assertEquals("ed25519-sec:xyz", json.getString("auth_key"))
        assertEquals("tok", json.getString("relay_auth_token"))
        assertEquals("10.0.0.0/8", json.getJSONArray("routes").getString(0))
        assertEquals("fd00::/8", json.getJSONArray("routes6").getString(0))
        assertEquals("https://relay.example/", json.getJSONArray("relay_urls").getString(0))
    }

    @Test
    fun relayTokenDroppedWithoutRelays() {
        val noRelays = profile.copy(relayUrls = emptyList())
        val json = JSONObject(TunnelConfigJson.build(noRelays, "s", "tok"))
        assertTrue(!json.has("relay_auth_token"))
    }

    @Test
    fun parsesDualStackReply() {
        val net = NetworkConfig.parse(dualStack)!!
        assertEquals("10.124.0.2", net.assignedIp)
        assertEquals("fd7a::1", net.gateway6)
        assertEquals(1280, net.mtu)
        assertEquals(listOf("10.9.9.9/32"), net.excludedRoutes)
        assertNull(NetworkConfig.parse("connect failed: nope"))
        assertNull(NetworkConfig.parse("""{"assigned_ip":"1.2.3.4"}"""))
    }

    @Test
    fun ipv4OnlyNullFields() {
        val net = NetworkConfig.parse(
            """{"assigned_ip":"10.124.0.2","netmask":"255.255.255.255","gateway":"10.124.0.1",
                "assigned_ip6":null,"prefix_len6":null,"gateway6":null,"mtu":1280,
                "excluded_routes":[],"excluded_routes6":[]}""",
        )!!
        assertNull(net.assignedIp6)
        assertNull(net.prefixLen6)
        assertNull(net.gateway6)
    }

    @Test
    fun planRoutesGatewayAndCarvesOutBypass() {
        val plan = TunnelPlan.from(NetworkConfig.parse(dualStack)!!, profile)
        assertEquals("10.124.0.2/32", plan.address4.toString())
        assertEquals("fd7a::2/128", plan.address6.toString())
        assertEquals("10.124.0.1", plan.remoteAddress)
        // Gateway host route + 10/8 minus the bypass /32.
        assertTrue(plan.routes4.contains(IpPrefix.parse("10.124.0.1/32")))
        assertTrue(plan.routes4.none { it.containsAddress(IpLiteral.parse("10.9.9.9")!!) })
        assertTrue(plan.routes4.any { it.containsAddress(IpLiteral.parse("10.200.0.1")!!) })
        assertEquals(listOf(IpPrefix.parse("10.9.9.9/32")), plan.bypass4)
        assertEquals(listOf(IpPrefix.parse("fd00::/8"), IpPrefix.parse("fd7a::1/128")), plan.routes6)
        assertTrue(plan.warnings.isEmpty())
        assertEquals(listOf("10.0.0.53"), plan.runtimeInfo().dnsServers)
        assertEquals(1280, plan.runtimeInfo().mtu)
    }

    @Test
    fun planWarnsAboutUnassignedFamilyAndUncoveredDns() {
        val net = NetworkConfig.parse(
            """{"assigned_ip":"10.124.0.2","netmask":"255.255.255.255","gateway":"10.124.0.1",
                "mtu":1280,"excluded_routes":[],"excluded_routes6":[]}""",
        )!!
        val plan = TunnelPlan.from(net, profile.copy(dnsServers = listOf("1.1.1.1")))
        assertNull(plan.address6)
        assertTrue(plan.routes6.isEmpty())
        assertEquals(2, plan.warnings.size)
        assertTrue(plan.warnings[0].contains("ignoring 1 IPv6 route(s)"))
        assertTrue(plan.warnings[1].contains("1.1.1.1"))
    }

    @Test
    fun splitDnsRoutesTheProxyAndKeepsRealServersForTheReadout() {
        val split = profile.copy(dnsServers = listOf("10.0.0.53"), dnsMatchDomains = listOf("corp.example"))
        val plan = TunnelPlan.from(NetworkConfig.parse(dualStack)!!, split)
        assertEquals(listOf(DnsProxy.ADDRESS4, DnsProxy.ADDRESS6), plan.dnsServers)
        assertEquals(listOf(DnsProxy.ADDRESS4, DnsProxy.ADDRESS6), plan.dnsProxyAddresses)
        assertTrue(plan.routes4.contains(IpPrefix.host(DnsProxy.ADDRESS4)))
        assertTrue(plan.routes6.contains(IpPrefix.host(DnsProxy.ADDRESS6)))
        assertEquals(plan.routes4, plan.routes4.sorted())
        val info = plan.runtimeInfo()
        assertEquals(listOf("10.0.0.53"), info.dnsServers)
        assertEquals(listOf("corp.example"), info.dnsMatchDomains)
        assertEquals(listOf(DnsProxy.ADDRESS4, DnsProxy.ADDRESS6), info.dnsProxyAddresses)

        // IPv4-only server: only the IPv4 proxy is routed and advertised.
        val v4Only = NetworkConfig.parse(
            """{"assigned_ip":"10.124.0.2","netmask":"255.255.255.255","gateway":"10.124.0.1",
                "mtu":1280,"excluded_routes":[],"excluded_routes6":[]}""",
        )!!
        val plan4 = TunnelPlan.from(v4Only, split)
        assertEquals(listOf(DnsProxy.ADDRESS4), plan4.dnsServers)
        assertTrue(plan4.routes6.isEmpty())

        // Without match domains nothing changes: the servers go to the OS as-is.
        val plain = TunnelPlan.from(NetworkConfig.parse(dualStack)!!, profile)
        assertEquals(listOf("10.0.0.53"), plain.dnsServers)
        assertTrue(plain.dnsProxyAddresses.isEmpty())
        assertTrue(plain.routes4.none { it == IpPrefix.host(DnsProxy.ADDRESS4) })
    }

    @Test
    fun configJsonCarriesDnsProxyOnlyForSplitDns() {
        val split = profile.copy(dnsMatchDomains = listOf("corp.example"))
        val request = DnsProxyRequest(fallbackServers = listOf("192.168.1.1", "fe80::1%5"), fallbackFds = listOf(41, 42))
        val json = JSONObject(TunnelConfigJson.build(split, "s", null, request))
        val proxy = json.getJSONObject("dns_proxy")
        assertEquals(DnsProxy.ADDRESS4, proxy.getJSONArray("addresses").getString(0))
        assertEquals("corp.example", proxy.getJSONArray("match_domains").getString(0))
        assertEquals("10.0.0.53", proxy.getJSONArray("servers").getString(0))
        assertEquals("fe80::1%5", proxy.getJSONArray("fallback_servers").getString(1))
        assertEquals(42, proxy.getJSONArray("fallback_fds").getInt(1))
        // No match domains, or no request from the service: no proxy object.
        assertTrue(!JSONObject(TunnelConfigJson.build(profile, "s", null, request)).has("dns_proxy"))
        assertTrue(!JSONObject(TunnelConfigJson.build(split, "s", null, null)).has("dns_proxy"))
    }

    @Test
    fun planWithoutAnyGatewayHasNoRemote() {
        val net = NetworkConfig.parse("""{"mtu":1280,"excluded_routes":[],"excluded_routes6":[]}""")!!
        val plan = TunnelPlan.from(net, profile)
        assertNull(plan.remoteAddress)
        assertNull(plan.address4)
        assertNotNull(plan.warnings)
    }
}
