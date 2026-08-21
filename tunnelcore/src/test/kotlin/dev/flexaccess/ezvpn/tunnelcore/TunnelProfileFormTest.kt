package dev.flexaccess.ezvpn.tunnelcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TunnelProfileFormTest {
    private val id = UUID.randomUUID()
    private val valid = TunnelProfileForm(
        name = " Home ",
        serverNodeId = "node ",
        authKeyId = "k1",
        relayUrls = "https://a.example/, https://b.example/",
        relayAuthToken = " tok ",
        routes = "10.0.0.0/8, 172.16.0.0/12",
        routes6 = "fd00::/8",
        dnsServers = "10.0.0.53",
    )

    @Test
    fun trimsAndSplits() {
        val s = valid.makeSubmission(id)
        assertEquals("Home", s.profile.name)
        assertEquals("node", s.profile.serverNodeId)
        assertEquals(listOf("https://a.example/", "https://b.example/"), s.profile.relayUrls)
        assertEquals(listOf("10.0.0.0/8", "172.16.0.0/12"), s.profile.routes)
        assertEquals(listOf("fd00::/8"), s.profile.routes6)
        assertEquals("tok", s.relayAuthToken)
        assertEquals(id, s.profile.id)
    }

    @Test
    fun requiredFields() {
        assertTrue(!TunnelProfileForm().hasRequiredFields)
        assertThrows(TunnelProfileFormException.MissingRequiredFields::class.java) {
            valid.copy(authKeyId = "").makeSubmission(id)
        }
    }

    @Test
    fun tokenNeedsRelays() {
        assertThrows(TunnelProfileFormException.RelayTokenWithoutRelays::class.java) {
            valid.copy(relayUrls = "").makeSubmission(id)
        }
        assertNull(valid.copy(relayAuthToken = "").makeSubmission(id).relayAuthToken)
    }

    @Test
    fun rejectsBadRoutesAndDns() {
        assertThrows(TunnelProfileFormException.InvalidRoute::class.java) {
            valid.copy(routes = "10.0.0.0").makeSubmission(id)
        }
        assertThrows(TunnelProfileFormException.InvalidRoute::class.java) {
            valid.copy(routes = "fd00::/8").makeSubmission(id)
        }
        assertThrows(TunnelProfileFormException.InvalidRoute::class.java) {
            valid.copy(routes6 = "10.0.0.0/8").makeSubmission(id)
        }
        assertThrows(TunnelProfileFormException.InvalidDns::class.java) {
            valid.copy(dnsServers = "dns.example").makeSubmission(id)
        }
        assertThrows(TunnelProfileFormException.InvalidDns::class.java) {
            valid.copy(dnsServers = "", dnsMatchDomains = "corp.example").makeSubmission(id)
        }
        assertThrows(TunnelProfileFormException.InvalidDns::class.java) {
            valid.copy(dnsMatchDomains = "corp example").makeSubmission(id)
        }
    }

    @Test
    fun normalizesMatchDomains() {
        val s = valid.copy(dnsMatchDomains = " Corp.Example., lab ").makeSubmission(id)
        assertEquals(listOf("corp.example", "lab"), s.profile.dnsMatchDomains)
        assertTrue(DnsProxy.isEnabled(s.profile))
        assertTrue(!DnsProxy.isEnabled(valid.makeSubmission(id).profile))
        val back = TunnelProfileForm.from(s.profile)
        assertEquals("corp.example, lab", back.dnsMatchDomains)
        val list = TunnelProfile.listFromJson(TunnelProfile.listToJson(listOf(s.profile)))
        assertEquals(listOf("corp.example", "lab"), list[0].dnsMatchDomains)
    }

    @Test
    fun roundTripsThroughProfile() {
        val s = valid.makeSubmission(id)
        val back = TunnelProfileForm.from(s.profile, s.relayAuthToken ?: "")
        assertEquals("Home", back.name)
        assertEquals("10.0.0.0/8, 172.16.0.0/12", back.routes)
        assertEquals("tok", back.relayAuthToken)
    }

    @Test
    fun profileJsonRoundTrip() {
        val p = valid.makeSubmission(id).profile
        val list = TunnelProfile.listFromJson(TunnelProfile.listToJson(listOf(p)))
        assertEquals(listOf(p), list)
        // Entries without an id are dropped, not fatal.
        assertEquals(emptyList<TunnelProfile>(), TunnelProfile.listFromJson("""[{"name":"x"}]"""))
    }

    @Test
    fun nameValidation() {
        assertEquals(NameResult.Valid("Home"), TunnelNames.validate(" Home ", listOf("work")))
        assertEquals(NameResult.Invalid(TunnelNameError.EMPTY), TunnelNames.validate("  ", emptyList()))
        assertEquals(NameResult.Invalid(TunnelNameError.DUPLICATE), TunnelNames.validate("home", listOf("Home")))
        assertEquals(NameResult.Invalid(TunnelNameError.DUPLICATE), TunnelNames.validate("résumé", listOf("resume")))
        assertEquals(NameResult.Valid("Home"), TunnelNames.validate("Home", listOf("Home"), excluding = "home"))
    }

    @Test
    fun naturalSort() {
        val sorted = listOf("tunnel10", "Tunnel2", "alpha", "tunnel1").sortedWith(TunnelNames.comparator)
        assertEquals(listOf("alpha", "tunnel1", "Tunnel2", "tunnel10"), sorted)
    }

    @Test
    fun snapshotDecoder() {
        val snap = TunnelSnapshotDecoder.connectionSnapshot(
            """{"paths":[{"kind":"direct","display":"Direct 1.2.3.4:1 (rtt 1ms)","selected":true},
                        {"kind":"relay","display":"Relay https://r/ (rtt 40ms)","selected":false},
                        {"kind":"weird","display":"x"}],
                "custom_relays":[{"url":"https://r/","working":true,"error":null},{"url":"https://s/","working":null,"error":"timeout"}]}""",
        )
        assertEquals(3, snap.paths.size)
        assertEquals(TunnelConnectionPath.Kind.DIRECT, snap.paths[0].kind)
        assertTrue(snap.paths[0].selected)
        assertEquals(TunnelConnectionPath.Kind.OTHER, snap.paths[2].kind)
        assertEquals(true, snap.customRelays[0].working)
        assertNull(snap.customRelays[1].working)
        assertEquals("timeout", snap.customRelays[1].error)
        assertEquals(TunnelConnectionSnapshot(), TunnelSnapshotDecoder.connectionSnapshot("not json"))
        assertEquals(TunnelConnectionSnapshot(), TunnelSnapshotDecoder.connectionSnapshot(null))
    }
}
