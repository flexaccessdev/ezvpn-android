package dev.flexaccess.ezvpn

import org.json.JSONObject

/**
 * Client auth keypair primitives, via the Rust FFI. A secret key
 * ("ed25519-sec:…") authenticates the tunnel handshake; its public key
 * ("ed25519-pub:…") is not a secret — it's what the user puts on the server's
 * authorized_keys file, and it's re-derived from the secret whenever needed
 * rather than stored. The app's named key list lives in [AuthKeyStore].
 * Keys are never generated or parsed in Kotlin: the shared FlexAccess key
 * format is owned by the Rust side.
 */
object AuthKey {
    data class Keypair(val secretKey: String, val publicKey: String)

    /** Generate a fresh ed25519 keypair; null only if the system RNG failed. */
    fun generate(): Keypair? {
        val json = runCatching { EzvpnNative.generateClientKey() }.getOrNull() ?: return null
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val secret = obj.optString("secret_key", "")
        val public = obj.optString("public_key", "")
        if (secret.isEmpty() || public.isEmpty()) return null
        return Keypair(secret, public)
    }

    /**
     * The public key of `secret`, or null when it isn't a valid secret key (the
     * native side returns null for an unparsable secret; a failure to reach it
     * at all is treated the same way, as in [generate]).
     */
    fun publicKey(secret: String): String? {
        if (secret.isEmpty()) return null
        return runCatching { EzvpnNative.clientPublicKey(secret) }.getOrNull()
    }
}
