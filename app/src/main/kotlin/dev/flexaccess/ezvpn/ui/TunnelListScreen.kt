package dev.flexaccess.ezvpn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.BuildConfig
import dev.flexaccess.ezvpn.R
import dev.flexaccess.ezvpn.TunnelState
import dev.flexaccess.ezvpn.TunnelStatus
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import java.util.UUID

/** Root screen: the saved profiles (WireGuard-app style), each with a connect switch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelListScreen(
    profiles: List<TunnelProfile>,
    state: TunnelState,
    onOpen: (UUID) -> Unit,
    onAdd: () -> Unit,
    onKeys: () -> Unit,
    onConnect: (UUID) -> Unit,
    onDisconnect: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // The auth keys are shared across profiles, so they are
                    // managed from the root screen (and the editor's picker).
                    IconButton(onClick = onKeys) { Icon(Icons.Default.VpnKey, contentDescription = stringResource(R.string.tunnel_list_auth_keys)) }
                    IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.tunnel_list_add_profile)) }
                },
            )
        },
        bottomBar = {
            Text(
                stringResource(R.string.tunnel_list_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        },
    ) { padding ->
        if (profiles.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.tunnel_list_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.tunnel_list_empty_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(profiles, key = { it.id }) { profile ->
                    TunnelRow(profile, state, onOpen, onConnect, onDisconnect)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun TunnelRow(
    profile: TunnelProfile,
    state: TunnelState,
    onOpen: (UUID) -> Unit,
    onConnect: (UUID) -> Unit,
    onDisconnect: () -> Unit,
) {
    val status = state.statusOf(profile.id)
    val waiting = state.isWaiting(profile.id)
    val isOn = status.isInOperation || waiting
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(profile.id) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusDot(if (waiting) TunnelStatus.CONNECTING.indicatorColor else status.indicatorColor)
        Column(Modifier.weight(1f)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (waiting) stringResource(R.string.tunnel_list_waiting) else status.displayText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(4.dp))
        Switch(
            checked = isOn,
            onCheckedChange = { on -> if (on) onConnect(profile.id) else onDisconnect() },
            enabled = status != TunnelStatus.DISCONNECTING,
        )
    }
}
