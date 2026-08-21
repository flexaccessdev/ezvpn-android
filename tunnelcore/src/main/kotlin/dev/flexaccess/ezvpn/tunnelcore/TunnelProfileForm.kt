package dev.flexaccess.ezvpn.tunnelcore

import java.util.UUID

/**
 * Editable text representation of a tunnel profile. Compose owns this value
 * while the editor is on screen; keeping the parsing and validation here makes
 * the form-to-model boundary deterministic and testable without a UI.
 */
data class TunnelProfileForm(
    val name: String = "",
    val serverNodeId: String = "",
    /** The picked entry of the shared auth-key list; empty until the user picks one. */
    val authKeyId: String = "",
    val relayUrls: String = "",
    val relayAuthToken: String = "",
    val routes: String = "",
    val routes6: String = "",
    val dnsServers: String = "",
    val dnsMatchDomains: String = "",
) {
    val hasRequiredFields: Boolean
        get() = name.isNotBlank() && serverNodeId.isNotBlank() && authKeyId.isNotBlank()

    /**
     * Validate the editor fields and separate the non-secret profile from the
     * relay token that is written directly to the secret store. The auth key
     * is referenced by id here; the editor resolves its secret from the key
     * store and hands that to the profile store separately.
     */
    fun makeSubmission(id: UUID): TunnelProfileSubmission {
        if (!hasRequiredFields) throw TunnelProfileFormException.MissingRequiredFields

        val dnsServerList = splitCsv(dnsServers)
        val dnsMatchDomainList = splitCsv(dnsMatchDomains)
        SplitDns.validationError(dnsServerList, dnsMatchDomainList)?.let { throw TunnelProfileFormException.InvalidDns(it) }

        val routeList = splitCsv(routes)
        routeList.firstOrNull { IpPrefix.parse(it)?.isIpv4 != true }?.let {
            throw TunnelProfileFormException.InvalidRoute("Not an IPv4 CIDR: $it")
        }
        val route6List = splitCsv(routes6)
        route6List.firstOrNull { IpPrefix.parse(it)?.isIpv4 != false }?.let {
            throw TunnelProfileFormException.InvalidRoute("Not an IPv6 CIDR: $it")
        }

        val relayUrlList = splitCsv(relayUrls)
        val relayToken = relayAuthToken.trim()
        // The relay token is only valid with custom relays; reject it up front
        // rather than letting the core fail the connection later.
        if (relayToken.isNotEmpty() && relayUrlList.isEmpty()) {
            throw TunnelProfileFormException.RelayTokenWithoutRelays
        }

        return TunnelProfileSubmission(
            profile = TunnelProfile(
                id = id,
                name = name.trim(),
                serverNodeId = serverNodeId.trim(),
                authKeyId = authKeyId.trim(),
                relayUrls = relayUrlList,
                routes = routeList,
                routes6 = route6List,
                dnsServers = dnsServerList,
                dnsMatchDomains = dnsMatchDomainList.map { SplitDns.normalizeDomain(it) },
            ),
            // Kept out of the profile: the relay token is a secret and is
            // persisted in the secret store, like the auth key. null == no token.
            relayAuthToken = relayToken.ifEmpty { null },
        )
    }

    companion object {
        fun from(profile: TunnelProfile, relayAuthToken: String = ""): TunnelProfileForm = TunnelProfileForm(
            name = profile.name,
            serverNodeId = profile.serverNodeId,
            authKeyId = profile.authKeyId,
            relayUrls = profile.relayUrls.joinToString(", "),
            relayAuthToken = relayAuthToken,
            routes = profile.routes.joinToString(", "),
            routes6 = profile.routes6.joinToString(", "),
            dnsServers = profile.dnsServers.joinToString(", "),
            dnsMatchDomains = profile.dnsMatchDomains.joinToString(", "),
        )

        fun splitCsv(value: String): List<String> =
            value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

/** The editor's validated output keeps the secret separate from the stored profile. */
data class TunnelProfileSubmission(
    val profile: TunnelProfile,
    /** Optional shared relay bearer token; null means no token. */
    val relayAuthToken: String?,
)

sealed class TunnelProfileFormException(message: String) : Exception(message) {
    object MissingRequiredFields :
        TunnelProfileFormException("Name, server node id, and auth key are required.")

    class InvalidDns(message: String) : TunnelProfileFormException(message)
    class InvalidRoute(message: String) : TunnelProfileFormException(message)
    object RelayTokenWithoutRelays :
        TunnelProfileFormException("A relay token requires at least one relay URL.")
}
