package dev.flexaccess.ezvpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TunnelStateTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()

    @Test
    fun statusIsPerProfile() {
        val state = TunnelState(profileId = a, status = TunnelStatus.CONNECTED)
        assertEquals(TunnelStatus.CONNECTED, state.statusOf(a))
        assertEquals(TunnelStatus.DISCONNECTED, state.statusOf(b))
        assertTrue(TunnelStatus.CONNECTING.isInOperation)
        assertTrue(TunnelStatus.DISCONNECTING.isInOperation)
        assertFalse(TunnelStatus.DISCONNECTED.isInOperation)
    }

    @Test
    fun queuedProfileIsWaiting() {
        val state = TunnelState(profileId = a, status = TunnelStatus.DISCONNECTING, pendingProfileId = b)
        assertTrue(state.isWaiting(b))
        assertFalse(state.isWaiting(a))
        assertEquals(TunnelStatus.DISCONNECTED, state.statusOf(b))
    }
}
