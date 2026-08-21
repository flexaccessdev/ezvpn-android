package dev.flexaccess.ezvpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.AuthKeyStore
import dev.flexaccess.ezvpn.TunnelsManager
import dev.flexaccess.ezvpn.TunnelsManagerException
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfileForm
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfileFormException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Add (`profile == null`) or edit a profile. Save validates and, on success, leaves. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelEditScreen(
    profile: TunnelProfile?,
    keys: List<AuthKeyStore.Key>,
    manager: TunnelsManager,
    onManageKeys: () -> Unit,
    onDone: () -> Unit,
) {
    val isAdd = profile == null
    var form by rememberSaveable(profile?.id, stateSaver = FormSaver) {
        mutableStateOf(if (profile == null) TunnelProfileForm() else TunnelProfileForm.from(profile))
    }
    // The relay token is a secret: it stays out of the saved instance state
    // (see FormSaver) and is read from the secret store off the main thread,
    // once per profile (and again after recreation, since it wasn't saved).
    // Only the token field is merged in, and only while the user hasn't typed
    // one, so other in-progress edits are untouched.
    if (profile != null) {
        LaunchedEffect(profile.id) {
            val token = withContext(Dispatchers.IO) {
                runCatching { manager.profileStore.relayAuthToken(profile.id) }.getOrNull()
            } ?: ""
            if (token.isNotEmpty() && form.relayAuthToken.isEmpty()) form = form.copy(relayAuthToken = token)
        }
    }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedKey = keys.firstOrNull { it.id == form.authKeyId }
    // The profile keeps its own copy of the secret, so a key deleted from the
    // list still connects — but there is nothing to preselect and nothing to
    // re-save with, so say so instead of showing an empty picker.
    val missingKeyNotice = if (form.authKeyId.isNotEmpty() && selectedKey == null) {
        "The auth key this profile used is no longer in the key list. Pick a key before saving."
    } else {
        null
    }

    fun save() {
        error = null
        val id = profile?.id ?: UUID.randomUUID()
        try {
            val submission = form.makeSubmission(id)
            val key = keys.firstOrNull { it.id == submission.profile.authKeyId }
            if (key == null) {
                error = "Pick an auth key for this profile."
                return
            }
            // The profile stores only the key's id; its secret is copied into
            // the profile's own secret so the service can read it without the
            // key list.
            if (isAdd) {
                manager.add(submission.profile, key.secret, submission.relayAuthToken)
            } else {
                manager.modify(submission.profile, key.secret, submission.relayAuthToken)
            }
            onDone()
        } catch (e: TunnelProfileFormException) {
            error = e.message
        } catch (e: TunnelsManagerException) {
            error = e.message
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isAdd) "New profile" else "Edit profile") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, contentDescription = "Cancel") } },
                actions = { TextButton(onClick = { save() }, enabled = form.hasRequiredFields) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
        ) {
            SectionTitle("Profile")
            Field("Name", form.name, { form = form.copy(name = it) }, capitalize = true)

            SectionTitle("Server")
            Field("Server node id", form.serverNodeId, { form = form.copy(serverNodeId = it) }, monospace = true)
            KeyPicker(keys, selectedKey, onPick = { form = form.copy(authKeyId = it.id) })
            selectedKey?.let {
                Footnote("Public key (put this on the server):")
                Text(it.publicKey, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            missingKeyNotice?.let { Footnote(it, color = MaterialTheme.colorScheme.tertiary) }
            TextButton(onClick = onManageKeys) { Text("Manage keys…") }
            Field("Relay URLs", form.relayUrls, { form = form.copy(relayUrls = it) }, hint = "comma-separated, optional", monospace = true)
            Field(
                "Relay token",
                form.relayAuthToken,
                { form = form.copy(relayAuthToken = it) },
                hint = "optional, custom relays only",
                secret = true,
                enabled = form.relayUrls.isNotBlank() || form.relayAuthToken.isNotEmpty(),
            )

            SectionTitle("Split tunnel")
            Field("IPv4 routes", form.routes, { form = form.copy(routes = it) }, hint = "comma-separated CIDRs, optional", monospace = true)
            Field("IPv6 routes", form.routes6, { form = form.copy(routes6 = it) }, hint = "comma-separated CIDRs, optional", monospace = true)
            Footnote("The server gateway is always routed automatically; add CIDRs here to route more.")

            SectionTitle("Split DNS (conditional forwarding)")
            Field("DNS servers", form.dnsServers, { form = form.copy(dnsServers = it) }, hint = "comma-separated IPs, optional", monospace = true)
            Field("Match domains", form.dnsMatchDomains, { form = form.copy(dnsMatchDomains = it) }, hint = "comma-separated, optional", monospace = true)
            Footnote(
                "Names under the match domains resolve via these DNS servers through the tunnel; " +
                    "everything else keeps the network's normal DNS. Android has no split DNS of its own, so " +
                    "ezvpn forwards in-tunnel (like Tailscale's MagicDNS). Servers should sit inside a tunnel " +
                    "route. Empty match domains send all DNS through the servers.",
            )

            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    hint: String? = null,
    monospace: Boolean = false,
    secret: Boolean = false,
    capitalize: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = hint?.let { { Text(it) } },
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (secret) KeyboardType.Password else if (monospace) KeyboardType.Ascii else KeyboardType.Text,
            capitalization = if (capitalize) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            autoCorrectEnabled = false,
        ),
        textStyle = if (monospace) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    )
}

@Composable
private fun KeyPicker(keys: List<AuthKeyStore.Key>, selected: AuthKeyStore.Key?, onPick: (AuthKeyStore.Key) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), enabled = keys.isNotEmpty()) {
            Text(
                selected?.let { "Auth key: ${it.name}" } ?: if (keys.isEmpty()) "No auth keys yet" else "Choose an auth key…",
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            keys.forEach { key ->
                DropdownMenuItem(text = { Text(key.name) }, onClick = { open = false; onPick(key) })
            }
        }
    }
}

/**
 * Keeps the editor's text across rotation (the form is a plain data class) —
 * except the relay token, a secret that must not sit in plaintext in the
 * saved-state Bundle; the editor re-reads it from the secret store instead.
 */
private val FormSaver = androidx.compose.runtime.saveable.listSaver<TunnelProfileForm, String>(
    save = { listOf(it.name, it.serverNodeId, it.authKeyId, it.relayUrls, it.routes, it.routes6, it.dnsServers, it.dnsMatchDomains) },
    restore = {
        TunnelProfileForm(
            name = it[0],
            serverNodeId = it[1],
            authKeyId = it[2],
            relayUrls = it[3],
            routes = it[4],
            routes6 = it[5],
            dnsServers = it[6],
            dnsMatchDomains = it[7],
        )
    },
)
