package dev.flexaccess.ezvpn.tunnelcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworksTest {
    private val wifi = LocalNetwork("wlan0", IpPrefix.parse("192.168.1.0/24")!!)
    private val wifi6 = LocalNetwork("wlan0", IpPrefix.parse("2001:db8:1::/64")!!)

    @Test
    fun refusesRouteCoveringTheLan() {
        val msg = LocalNetworks.splitTunnelConflict(listOf("10.0.0.0/8", "192.168.0.0/16"), emptyList(), listOf(wifi))
        assertEquals(
            "refusing to start: split-tunnel route 192.168.0.0/16 overlaps current network 192.168.1.0/24 on wlan0",
            msg,
        )
    }

    @Test
    fun refusesNarrowerRouteInsideTheLan() {
        val msg = LocalNetworks.splitTunnelConflict(listOf("192.168.1.128/25"), emptyList(), listOf(wifi))
        assertTrue(msg!!.contains("192.168.1.128/25"))
    }

    @Test
    fun refusesDefaultRoute() {
        // Same policy as iOS: a full-tunnel default route captures the LAN too.
        assertTrue(LocalNetworks.splitTunnelConflict(listOf("0.0.0.0/0"), emptyList(), listOf(wifi)) != null)
    }

    @Test
    fun ipv6ConflictAndClear() {
        assertTrue(LocalNetworks.splitTunnelConflict(emptyList(), listOf("2001:db8::/32"), listOf(wifi6)) != null)
        assertNull(LocalNetworks.splitTunnelConflict(listOf("10.0.0.0/8"), listOf("fd00::/8"), listOf(wifi, wifi6)))
    }

    @Test
    fun malformedRoutesAreSkipped() {
        assertNull(LocalNetworks.splitTunnelConflict(listOf("garbage", "192.168.1.0"), emptyList(), listOf(wifi)))
    }

    @Test
    fun linkLocalDetection() {
        assertTrue(LocalNetworks.isLinkLocalV6(IpLiteral.parse("fe80::1")!!))
        assertTrue(!LocalNetworks.isLinkLocalV6(IpLiteral.parse("fd00::1")!!))
        assertTrue(!LocalNetworks.isLinkLocalV6(IpLiteral.parse("10.0.0.1")!!))
    }
}
