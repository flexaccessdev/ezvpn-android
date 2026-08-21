package dev.flexaccess.ezvpn

import android.content.Context
import android.content.SharedPreferences
import dev.flexaccess.ezvpn.tunnelcore.TunnelNames
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class ProfileStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The saved profiles (the Android counterpart of the per-profile
 * `NETunnelProviderManager`s): the non-secret list as one JSON document in a
 * private `SharedPreferences`, and each profile's secrets — its own copy of the
 * auth key, and the optional relay token — in the [SecretStore] keyed by
 * profile id. The service reads a profile plus its two secrets and never the
 * shared key list. Also remembers the last profile the user connected, which
 * is what an always-on start (no intent) connects.
 */
class ProfileStore(context: Context, private val secrets: SecretStore) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _profiles = MutableStateFlow(load())
    val profiles: StateFlow<List<TunnelProfile>> = _profiles.asStateFlow()

    fun profile(id: UUID): TunnelProfile? = _profiles.value.firstOrNull { it.id == id }

    /**
     * Add or replace `profile`, writing its secrets first so a saved profile
     * always has a key to connect with. A null/empty relay token removes any
     * stored one. Throws [ProfileStoreException] (with the previous secrets
     * restored as far as possible) when anything didn't land.
     */
    @Synchronized
    fun save(profile: TunnelProfile, authKey: String, relayAuthToken: String?) {
        val previousKey = runCatching { secrets.get(authKeyName(profile.id)) }.getOrNull()
        val previousToken = runCatching { secrets.get(relayTokenName(profile.id)) }.getOrNull()
        try {
            secrets.put(authKeyName(profile.id), authKey)
            if (relayAuthToken.isNullOrEmpty()) {
                secrets.remove(relayTokenName(profile.id))
            } else {
                secrets.put(relayTokenName(profile.id), relayAuthToken)
            }
        } catch (e: SecretStoreException) {
            restore(profile.id, previousKey, previousToken)
            throw ProfileStoreException("Couldn't save the profile's secrets: ${e.message}", e)
        }
        val previous = _profiles.value
        val next = (previous.filter { it.id != profile.id } + profile)
            .sortedWith(compareBy(TunnelNames.comparator) { it.name })
        if (!prefs.edit().putString(KEY_PROFILES, TunnelProfile.listToJson(next)).commit()) {
            restore(profile.id, previousKey, previousToken)
            throw ProfileStoreException("Couldn't write the profile list.")
        }
        _profiles.value = next
    }

    @Synchronized
    fun delete(id: UUID) {
        val next = _profiles.value.filter { it.id != id }
        if (!prefs.edit().putString(KEY_PROFILES, TunnelProfile.listToJson(next)).commit()) {
            throw ProfileStoreException("Couldn't write the profile list.")
        }
        _profiles.value = next
        if (lastProfileId == id) lastProfileId = null
        val errors = listOfNotNull(
            runCatching { secrets.remove(authKeyName(id)) }.exceptionOrNull(),
            runCatching { secrets.remove(relayTokenName(id)) }.exceptionOrNull(),
        )
        errors.firstOrNull()?.let { throw ProfileStoreException("Profile removed, but its secrets weren't: ${it.message}", it) }
    }

    /** The profile's own copy of its auth key, or null when none is stored. */
    fun authKey(id: UUID): String? = secrets.get(authKeyName(id))

    fun relayAuthToken(id: UUID): String? = secrets.get(relayTokenName(id))

    /** The profile to connect when the system starts the service with no intent (always-on). */
    var lastProfileId: UUID?
        get() = prefs.getString(KEY_LAST_PROFILE, null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_LAST_PROFILE) else putString(KEY_LAST_PROFILE, value.toString())
            }.apply()
        }

    private fun load(): List<TunnelProfile> {
        val json = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching { TunnelProfile.listFromJson(json) }.getOrElse { emptyList() }
            .sortedWith(compareBy(TunnelNames.comparator) { it.name })
    }

    private fun restore(id: UUID, key: String?, token: String?) {
        runCatching { if (key == null) secrets.remove(authKeyName(id)) else secrets.put(authKeyName(id), key) }
        runCatching { if (token == null) secrets.remove(relayTokenName(id)) else secrets.put(relayTokenName(id), token) }
    }

    private companion object {
        const val PREFS = "ezvpn-profiles"
        const val KEY_PROFILES = "profiles"
        const val KEY_LAST_PROFILE = "last_profile_id"
        fun authKeyName(id: UUID) = "auth-key:$id"
        fun relayTokenName(id: UUID) = "relay-token:$id"
    }
}
