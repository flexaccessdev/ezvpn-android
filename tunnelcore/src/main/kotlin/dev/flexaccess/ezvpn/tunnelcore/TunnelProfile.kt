package dev.flexaccess.ezvpn.tunnelcore

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One saved VPN profile: the connection parameters plus a stable identity and
 * a display name. The app persists the list as JSON in its private storage;
 * this is the pure, testable payload. No secret is here: the client's ed25519
 * auth key and the optional relay bearer token live in the encrypted secret
 * store, keyed by profile id (see the app's `ProfileStore`). `authKeyId`
 * names which entry of the app's shared key list the secret came from, which
 * is not itself sensitive.
 *
 * `id` is a UUID minted once when the profile is created — the list identity,
 * stable across renames.
 */
data class TunnelProfile(
    val id: UUID = UUID.randomUUID(),
    /** Display name; unique per app (case-insensitively). */
    val name: String,
    val serverNodeId: String,
    /** Which key from the app's shared auth-key list this profile authenticates with. */
    val authKeyId: String,
    val relayUrls: List<String> = emptyList(),
    /** IPv4 CIDRs to route through the tunnel (split tunnel). */
    val routes: List<String> = emptyList(),
    /** IPv6 CIDRs to route through the tunnel (split tunnel). */
    val routes6: List<String> = emptyList(),
    /** DNS server IPs the tunnel provides. Empty = the tunnel touches no DNS. */
    val dnsServers: List<String> = emptyList(),
    /**
     * Domain suffixes resolved via [dnsServers]; everything else keeps the
     * network's DNS. Empty = every name goes to [dnsServers]. Implemented by
     * the in-tunnel forwarder (see [DnsProxy]), since Android has no split DNS.
     */
    val dnsMatchDomains: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_ID, id.toString())
        put(KEY_NAME, name)
        put(KEY_SERVER_NODE_ID, serverNodeId)
        put(KEY_AUTH_KEY_ID, authKeyId)
        put(KEY_RELAY_URLS, JSONArray(relayUrls))
        put(KEY_ROUTES, JSONArray(routes))
        put(KEY_ROUTES6, JSONArray(routes6))
        put(KEY_DNS_SERVERS, JSONArray(dnsServers))
        put(KEY_DNS_MATCH_DOMAINS, JSONArray(dnsMatchDomains))
    }

    companion object {
        const val KEY_ID = "profile_id"
        const val KEY_NAME = "name"
        const val KEY_SERVER_NODE_ID = "server_node_id"
        const val KEY_AUTH_KEY_ID = "auth_key_id"
        const val KEY_RELAY_URLS = "relay_urls"
        const val KEY_ROUTES = "routes"
        const val KEY_ROUTES6 = "routes6"
        const val KEY_DNS_SERVERS = "dns_servers"
        const val KEY_DNS_MATCH_DOMAINS = "dns_match_domains"

        /** Rebuild a profile; null when the stable id is missing or unparseable. */
        fun fromJson(obj: JSONObject): TunnelProfile? {
            val id = runCatching { UUID.fromString(obj.optString(KEY_ID)) }.getOrNull() ?: return null
            return TunnelProfile(
                id = id,
                name = obj.optString(KEY_NAME, "Unnamed"),
                serverNodeId = obj.optString(KEY_SERVER_NODE_ID, ""),
                authKeyId = obj.optString(KEY_AUTH_KEY_ID, ""),
                relayUrls = obj.optJSONArray(KEY_RELAY_URLS).toStringList(),
                routes = obj.optJSONArray(KEY_ROUTES).toStringList(),
                routes6 = obj.optJSONArray(KEY_ROUTES6).toStringList(),
                dnsServers = obj.optJSONArray(KEY_DNS_SERVERS).toStringList(),
                dnsMatchDomains = obj.optJSONArray(KEY_DNS_MATCH_DOMAINS).toStringList(),
            )
        }

        fun listToJson(profiles: List<TunnelProfile>): String =
            JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString()

        /** Decode a saved list; entries without a usable id are skipped. Throws on non-JSON. */
        fun listFromJson(json: String): List<TunnelProfile> {
            val array = JSONArray(json)
            return (0 until array.length()).mapNotNull { i ->
                array.optJSONObject(i)?.let { fromJson(it) }
            }
        }
    }
}

internal fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i -> optString(i, null) }
}
