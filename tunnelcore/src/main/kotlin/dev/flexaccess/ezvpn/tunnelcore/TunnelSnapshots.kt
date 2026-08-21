package dev.flexaccess.ezvpn.tunnelcore

import org.json.JSONArray
import org.json.JSONObject

/** Applied network configuration reported by the running tunnel service. */
data class TunnelRuntimeInfo(
    val assignedIp: String? = null,
    val assignedIp6: String? = null,
    val mtu: Int? = null,
    val includedRoutes: List<String> = emptyList(),
    val includedRoutes6: List<String> = emptyList(),
    val bypassRoutes: List<String> = emptyList(),
    val bypassRoutes6: List<String> = emptyList(),
    val dnsServers: List<String> = emptyList(),
    /** Empty while [dnsServers] answer every name. */
    val dnsMatchDomains: List<String> = emptyList(),
    /** The in-tunnel forwarder's addresses the OS was pointed at, when split DNS is on. */
    val dnsProxyAddresses: List<String> = emptyList(),
)

/** One live iroh path from the service to the server. */
data class TunnelConnectionPath(
    val kind: Kind,
    /** Human line like "Direct 1.2.3.4:52186 (rtt 1ms)". */
    val display: String,
    /** Whether iroh currently routes traffic over this path. */
    val selected: Boolean,
) {
    enum class Kind { DIRECT, RELAY, OTHER }
}

data class TunnelCustomRelay(
    val url: String,
    val working: Boolean?,
    val error: String?,
)

data class TunnelConnectionSnapshot(
    val paths: List<TunnelConnectionPath> = emptyList(),
    val customRelays: List<TunnelCustomRelay> = emptyList(),
)

/** Pure decoder for the `connPath` JSON the core returns (see ezvpn's ffi docs). */
object TunnelSnapshotDecoder {
    fun connectionSnapshot(json: String?): TunnelConnectionSnapshot {
        val obj = json?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return TunnelConnectionSnapshot()
        val paths = (obj.optJSONArray("paths") ?: JSONArray()).let { array ->
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val display = entry.optString("display", null) ?: return@mapNotNull null
                TunnelConnectionPath(
                    kind = when (entry.optString("kind")) {
                        "direct" -> TunnelConnectionPath.Kind.DIRECT
                        "relay" -> TunnelConnectionPath.Kind.RELAY
                        else -> TunnelConnectionPath.Kind.OTHER
                    },
                    display = display,
                    selected = entry.optBoolean("selected", false),
                )
            }
        }
        val relays = (obj.optJSONArray("custom_relays") ?: JSONArray()).let { array ->
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val url = entry.optString("url", null) ?: return@mapNotNull null
                TunnelCustomRelay(
                    url = url,
                    working = if (entry.isNull("working")) null else entry.optBoolean("working"),
                    error = if (entry.isNull("error")) null else entry.optString("error"),
                )
            }
        }
        return TunnelConnectionSnapshot(paths, relays)
    }
}
