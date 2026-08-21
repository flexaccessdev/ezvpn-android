package dev.flexaccess.ezvpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.util.Log
import dev.flexaccess.ezvpn.tunnelcore.NameResult
import dev.flexaccess.ezvpn.tunnelcore.TunnelConnectionSnapshot
import dev.flexaccess.ezvpn.tunnelcore.TunnelNameError
import dev.flexaccess.ezvpn.tunnelcore.TunnelNames
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import dev.flexaccess.ezvpn.tunnelcore.TunnelRuntimeInfo
import dev.flexaccess.ezvpn.tunnelcore.TunnelSnapshotDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID

class TunnelsManagerException(message: String) : Exception(message)

/**
 * Owns the saved profiles and the one VPN session: the Android counterpart of
 * ezvpn-apple's `TunnelsManager`. CRUD goes to [ProfileStore]; connect and
 * disconnect drive [EzvpnVpnService], which runs in this same process and
 * reports back through the `on*` methods, so [state] is the single source of
 * truth for the UI. At most one tunnel runs at a time: connecting another
 * profile stops the current one first and starts the new one once it is down.
 */
class TunnelsManager(context: Context) {
    private val appContext = context.applicationContext
    val secrets: SecretStore = KeystoreSecretStore(appContext)
    val profileStore = ProfileStore(appContext, secrets)
    val authKeys = AuthKeyStore(secrets)

    val profiles: StateFlow<List<TunnelProfile>> get() = profileStore.profiles

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    // ---------------------------------------------------------------------
    // CRUD

    /** Validate the name against the other profiles and save. */
    fun add(profile: TunnelProfile, authKey: String, relayAuthToken: String?): TunnelProfile {
        val named = profile.copy(name = validatedName(profile.name, excluding = null))
        save(named, authKey, relayAuthToken)
        return named
    }

    /**
     * Rewrite an existing profile. If it is running, restart it so the change
     * takes effect (the editor disables Edit while active, but the rule holds).
     */
    fun modify(profile: TunnelProfile, authKey: String, relayAuthToken: String?) {
        val named = profile.copy(name = validatedName(profile.name, excluding = profile.id))
        save(named, authKey, relayAuthToken)
        val s = _state.value
        if (s.profileId == profile.id && s.status.isInOperation) {
            _state.update { it.copy(pendingProfileId = profile.id) }
            stopCurrent()
        }
    }

    fun remove(id: UUID) {
        val s = _state.value
        if (s.profileId == id && s.status.isInOperation) disconnect()
        _state.update { if (it.pendingProfileId == id) it.copy(pendingProfileId = null) else it }
        try {
            profileStore.delete(id)
        } catch (e: ProfileStoreException) {
            throw TunnelsManagerException(e.message ?: "Couldn't delete the profile.")
        }
    }

    private fun save(profile: TunnelProfile, authKey: String, relayAuthToken: String?) {
        try {
            profileStore.save(profile, authKey, relayAuthToken)
        } catch (e: ProfileStoreException) {
            throw TunnelsManagerException(e.message ?: "Couldn't save the profile.")
        }
    }

    private fun validatedName(raw: String, excluding: UUID?): String {
        val own = excluding?.let { profileStore.profile(it)?.name }
        val others = profiles.value.filter { it.id != excluding }.map { it.name }
        return when (val r = TunnelNames.validate(raw, others, excluding = own)) {
            is NameResult.Valid -> r.name
            is NameResult.Invalid -> throw TunnelsManagerException(
                when (r.error) {
                    TunnelNameError.EMPTY -> "Name can't be empty."
                    TunnelNameError.DUPLICATE -> "A profile with that name already exists."
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // Activation

    /**
     * The system consent screen the user must accept before this app may run a
     * VPN, or null when already granted. The activity launches it and calls
     * [connect] again on RESULT_OK.
     */
    fun consentIntent(): Intent? = VpnService.prepare(appContext)

    /**
     * Start `id`. If another session is up (or still connecting), stop it and
     * queue `id` to start once it has fully stopped. Requires consent (see
     * [consentIntent]); without it the service's `establish()` fails and the
     * error lands in [TunnelState.lastError].
     */
    fun connect(id: UUID) {
        val s = _state.value
        if (s.status.isInOperation) {
            if (s.profileId == id && s.pendingProfileId == null) return
            _state.update { it.copy(pendingProfileId = id) }
            stopCurrent()
            return
        }
        _state.update { TunnelState(profileId = id, status = TunnelStatus.CONNECTING) }
        val intent = Intent(appContext, EzvpnVpnService::class.java)
            .setAction(EzvpnVpnService.ACTION_CONNECT)
            .putExtra(EzvpnVpnService.EXTRA_PROFILE_ID, id.toString())
        try {
            appContext.startService(intent)
        } catch (e: Exception) {
            // Background-start restrictions, mostly. Nothing is running, so
            // report it right here.
            Log.e(TAG, "startService failed", e)
            _state.update { it.copy(status = TunnelStatus.DISCONNECTED, lastError = "Couldn't start the VPN service: ${e.message}") }
        }
    }

    /** Stop whatever is running (and drop any queued connect). */
    fun disconnect() {
        _state.update { it.copy(pendingProfileId = null) }
        stopCurrent()
    }

    /** Stop the running session, keeping any queued connect. */
    private fun stopCurrent() {
        val service = EzvpnVpnService.instance
        if (service == null) {
            // Nothing running: make sure the UI agrees, and let a connect queued
            // behind the (non-existent) session start.
            val s = _state.value
            if (s.status.isInOperation || s.pendingProfileId != null) onDisconnected(null, null)
            return
        }
        service.disconnect()
    }

    /** Point-in-time snapshot of the live iroh path(s); empty when not connected. */
    suspend fun queryConnPath(): TunnelConnectionSnapshot = withContext(Dispatchers.IO) {
        TunnelSnapshotDecoder.connectionSnapshot(EzvpnVpnService.instance?.connPathBlocking())
    }

    // ---------------------------------------------------------------------
    // Service callbacks (any thread)

    internal fun onConnecting(id: UUID) {
        _state.update { TunnelState(profileId = id, status = TunnelStatus.CONNECTING, pendingProfileId = it.pendingProfileId) }
    }

    internal fun onConnected(id: UUID, info: TunnelRuntimeInfo) {
        profileStore.lastProfileId = id
        _state.update {
            it.copy(
                profileId = id,
                status = TunnelStatus.CONNECTED,
                runtimeInfo = info,
                connectedAtMillis = SystemClock.elapsedRealtime(),
                lastError = null,
            )
        }
    }

    internal fun onDisconnecting() {
        _state.update { if (it.status.isInOperation) it.copy(status = TunnelStatus.DISCONNECTING) else it }
    }

    /** The session is fully down; start a queued profile if there is one. */
    internal fun onDisconnected(id: UUID?, error: String?) {
        if (error != null) Log.w(TAG, "tunnel ended: $error")
        val pending = _state.value.pendingProfileId
        _state.update {
            TunnelState(
                profileId = id ?: it.profileId,
                status = TunnelStatus.DISCONNECTED,
                lastError = error,
                pendingProfileId = null,
            )
        }
        if (pending != null) connect(pending)
    }

    companion object {
        private const val TAG = "ezvpn"

        fun get(context: Context): TunnelsManager =
            (context.applicationContext as EzvpnApplication).manager
    }
}
