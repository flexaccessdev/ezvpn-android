package dev.flexaccess.ezvpn.tunnelcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPrefixTest {
    @Test
    fun parsesAndZeroesHostBits() {
        assertEquals("192.168.1.0/24", IpPrefix.parse("192.168.1.23/24").toString())
        assertEquals("fd00::/8", IpPrefix.parse("fd00:1234::1/8").toString())
        assertEquals("10.0.0.0/8", IpPrefix.parse(" 10.1.2.3/8 ").toString())
        assertEquals("0.0.0.0/0", IpPrefix.parse("0.0.0.0/0").toString())
        assertEquals("::/0", IpPrefix.parse("::/0").toString())
    }

    @Test
    fun rejectsMalformedCidrs() {
        for (bad in listOf("10.0.0.0", "10.0.0.0/33", "10.0.0/8", "fd00::/129", "host.example/24", "", "10.0.0.0/x", "10/8")) {
            assertNull("should reject $bad", IpPrefix.parse(bad))
        }
    }

    @Test
    fun literalsAreStrict() {
        assertTrue(IpLiteral.isAddress("1.2.3.4"))
        assertTrue(IpLiteral.isAddress("fd00::1"))
        assertTrue(IpLiteral.isAddress("::ffff:1.2.3.4"))
        assertFalse(IpLiteral.isAddress("10.1"))
        assertFalse(IpLiteral.isAddress("example.com"))
        assertFalse(IpLiteral.isAddress("1.2.3.4.5"))
        assertFalse(IpLiteral.isAddress("256.1.1.1"))
    }

    @Test
    fun formatsIpv6Compressed() {
        assertEquals("fd00::1", IpPrefix.host("fd00:0:0:0:0:0:0:1")!!.address)
        assertEquals("2001:db8::1:0:0:1", IpPrefix.host("2001:db8:0:0:1:0:0:1")!!.address)
        assertEquals("::", IpPrefix.host("::")!!.address)
        assertEquals("1:0:0:2:0:0:0:3", IpPrefix.host("1:0:0:2:0:0:0:3")!!.address.let {
            // longest zero run (3) wins over the first (2)
            assertEquals("1:0:0:2::3", it); "1:0:0:2:0:0:0:3"
        })
    }

    @Test
    fun overlapAndContainment() {
        val net = IpPrefix.parse("10.0.0.0/8")!!
        assertTrue(net.overlaps(IpPrefix.parse("10.1.0.0/16")!!))
        assertTrue(IpPrefix.parse("10.1.0.0/16")!!.overlaps(net))
        assertFalse(net.overlaps(IpPrefix.parse("11.0.0.0/8")!!))
        assertFalse(net.overlaps(IpPrefix.parse("fd00::/8")!!))
        assertTrue(net.contains(IpPrefix.host("10.200.1.1")!!))
        assertFalse(IpPrefix.host("10.200.1.1")!!.contains(net))
        assertTrue(IpPrefix.parse("0.0.0.0/0")!!.overlaps(IpPrefix.host("8.8.8.8")!!))
        assertTrue(IpPrefix.parse("192.168.0.0/16")!!.overlaps(IpPrefix.parse("192.168.1.0/24")!!))
    }

    @Test
    fun subtractHostFromPrefix() {
        val net = IpPrefix.parse("10.0.0.0/8")!!
        val out = net.subtract(IpPrefix.host("10.1.2.3")!!)
        assertEquals(24, out.size)
        // Every remaining prefix still sits inside the original and excludes the host.
        val host = IpPrefix.host("10.1.2.3")!!
        assertTrue(out.all { net.contains(it) })
        assertTrue(out.none { it.contains(host) })
        // And together with the host they cover exactly the original: sizes add up to 2^24.
        val covered = out.sumOf { 1L shl (32 - it.prefixLength) } + 1
        assertEquals(1L shl 24, covered)
        // Siblings: a /9 not containing the host, a /10, ... down to a /32.
        assertEquals((9..32).toList(), out.map { it.prefixLength }.sorted())
    }

    @Test
    fun subtractDisjointAndCovering() {
        val net = IpPrefix.parse("10.0.0.0/8")!!
        assertEquals(listOf(net), net.subtract(IpPrefix.host("11.0.0.1")!!))
        assertEquals(emptyList<IpPrefix>(), net.subtract(IpPrefix.parse("0.0.0.0/0")!!))
        assertEquals(emptyList<IpPrefix>(), net.subtract(net))
    }

    @Test
    fun routeMathSubtractsAcrossList() {
        val included = listOf(IpPrefix.parse("0.0.0.0/0")!!, IpPrefix.parse("fd00::/8")!!)
        val excluded = listOf(IpPrefix.host("1.1.1.1")!!, IpPrefix.host("2.2.2.2")!!)
        val out = RouteMath.subtract(included, excluded)
        assertTrue(out.none { it.containsAddress(IpLiteral.parse("1.1.1.1")!!) })
        assertTrue(out.none { it.containsAddress(IpLiteral.parse("2.2.2.2")!!) })
        assertTrue(out.any { it.containsAddress(IpLiteral.parse("8.8.8.8")!!) })
        assertTrue(out.contains(IpPrefix.parse("fd00::/8")!!))
        assertEquals(out, out.sorted())
    }

    @Test
    fun interfaceRoutesUnderHostMask() {
        val assigned = IpPrefix.of(IpLiteral.parse("10.124.0.5")!!, 32)!!
        val gw = IpPrefix.host("10.124.0.1")!!
        assertEquals(listOf(gw), RouteMath.interfaceRoutes(assigned, gw))
        assertEquals(emptyList<IpPrefix>(), RouteMath.interfaceRoutes(assigned, null))
    }

    @Test
    fun interfaceRoutesUnderSubnetMask() {
        val assigned = IpPrefix.of(IpLiteral.parse("10.124.0.5")!!, 24)!!
        val gw = IpPrefix.host("10.124.0.1")!!
        // On-link subnet already covers the gateway: no separate host route.
        assertEquals(listOf(IpPrefix.parse("10.124.0.0/24")), RouteMath.interfaceRoutes(assigned, gw))
        val farGw = IpPrefix.host("10.9.9.9")!!
        assertEquals(listOf(IpPrefix.parse("10.124.0.0/24"), farGw), RouteMath.interfaceRoutes(assigned, farGw))
    }

    @Test
    fun netmaskToPrefixLength() {
        assertEquals(24, IpPrefix.prefixLengthOfMask("255.255.255.0"))
        assertEquals(32, IpPrefix.prefixLengthOfMask("255.255.255.255"))
        assertEquals(0, IpPrefix.prefixLengthOfMask("0.0.0.0"))
        assertNull(IpPrefix.prefixLengthOfMask("255.0.255.0"))
        assertNull(IpPrefix.prefixLengthOfMask("nope"))
    }
}
