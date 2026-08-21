package dev.flexaccess.ezvpn

import dev.flexaccess.ezvpn.tunnelcore.TunnelRuntimeInfo
import java.util.UUID

enum class TunnelStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ;

    val isInOperation: Boolean get() = this != DISCONNECTED
}

/**
 * What the one VPN session is doing right now, as the UI sees it. The service
 * owns at most one session; `profileId` names the profile it is operating (or,
 * once disconnected, the one whose `lastError` this is).
 */
data class TunnelState(
    val profileId: UUID? = null,
    val status: TunnelStatus = TunnelStatus.DISCONNECTED,
    /** What was actually applied to the interface; set while connected. */
    val runtimeInfo: TunnelRuntimeInfo? = null,
    val connectedAtMillis: Long? = null,
    /** Why the last session ended (or failed to start); cleared on the next connect. */
    val lastError: String? = null,
    /** A profile queued to connect once the current session has stopped. */
    val pendingProfileId: UUID? = null,
) {
    fun statusOf(id: UUID): TunnelStatus =
        if (profileId == id) status else TunnelStatus.DISCONNECTED

    fun isWaiting(id: UUID): Boolean = pendingProfileId == id
}
