package dev.flexaccess.ezvpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.TunnelState
import dev.flexaccess.ezvpn.TunnelStatus
import dev.flexaccess.ezvpn.TunnelsManager
import dev.flexaccess.ezvpn.TunnelsManagerException
import dev.flexaccess.ezvpn.tunnelcore.TunnelProfile
import kotlinx.coroutines.delay
import java.util.UUID

/** One profile: status, the applied routing state while connected, connect/disconnect, edit, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunnelDetailScreen(
    profile: TunnelProfile,
    state: TunnelState,
    manager: TunnelsManager,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onConnect: (UUID) -> Unit,
    onDisconnect: () -> Unit,
    onDeleted: () -> Unit,
) {
    val status = state.statusOf(profile.id)
    val waiting = state.isWaiting(profile.id)
    val isActive = status.isInOperation || waiting
    val isConnecting = status == TunnelStatus.CONNECTING || waiting
    val lastError = state.lastError?.takeIf { state.profileId == profile.id }
    var confirmingDelete by remember { mutableStateOf(false) }
    var showingConnPath by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(ScreenPadding),
        ) {
            SectionTitle("Status")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(if (waiting) TunnelStatus.CONNECTING.indicatorColor else status.indicatorColor)
                Text(if (waiting) "Waiting…" else status.displayText)
                Spacer(Modifier.weight(1f))
                if (status == TunnelStatus.CONNECTED && state.connectedAtMillis != null) {
                    ConnectedSince(state.connectedAtMillis)
                }
            }
            (error ?: lastError)?.let {
                Footnote(it, color = MaterialTheme.colorScheme.error)
            }

            // Live routing state reported by the service, so what's on screen
            // is what the interface actually got.
            if (status == TunnelStatus.CONNECTED && state.runtimeInfo != null) {
                val info = state.runtimeInfo
                SectionTitle("Active routes")
                info.assignedIp?.let { ValueRows("Assigned IPv4", listOf(it)) }
                info.assignedIp6?.let { ValueRows("Assigned IPv6", listOf(it)) }
                info.mtu?.let { ValueRows("MTU", listOf(it.toString())) }
                ValueRows("Tunnel routes (IPv4)", info.includedRoutes)
                ValueRows("Tunnel routes (IPv6)", info.includedRoutes6)
                ValueRows("Bypass routes (IPv4)", info.bypassRoutes)
                ValueRows("Bypass routes (IPv6)", info.bypassRoutes6)
                if (info.dnsServers.isNotEmpty()) {
                    ValueRows("DNS servers", info.dnsServers)
                    ValueRows("DNS match domains", info.dnsMatchDomains.ifEmpty { listOf("all domains") })
                    if (info.dnsProxyAddresses.isNotEmpty()) ValueRows("DNS forwarder (in-tunnel)", info.dnsProxyAddresses)
                }
                Footnote(
                    "Bypass routes are server underlay/relay addresses carved out of the tunnel " +
                        "routes so its own transport is never captured.",
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { showingConnPath = true }) { Text("Connection path…") }
            }

            Spacer(Modifier.height(24.dp))
            when {
                isConnecting -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(if (waiting) "Reconnecting…" else "Connecting…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDisconnect) { Text("Cancel") }
                }
                isActive -> OutlinedButton(
                    onClick = onDisconnect,
                    enabled = status != TunnelStatus.DISCONNECTING,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect") }
                else -> Button(onClick = { onConnect(profile.id) }, modifier = Modifier.fillMaxWidth()) { Text("Connect") }
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onEdit, enabled = !isActive, modifier = Modifier.fillMaxWidth()) { Text("Edit") }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { confirmingDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete profile", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete ${profile.name}?") },
            text = { Text("This removes the VPN profile and its keys from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    try {
                        manager.remove(profile.id)
                        onDeleted()
                    } catch (e: TunnelsManagerException) {
                        error = e.message
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") } },
        )
    }

    if (showingConnPath) {
        ConnPathSheet(query = { manager.queryConnPath() }, onDismiss = { showingConnPath = false })
    }
}

/** "connected 3m 12s" style counter from an elapsedRealtime timestamp. */
@Composable
private fun ConnectedSince(sinceElapsedMillis: Long) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(sinceElapsedMillis) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    val secs = ((now - sinceElapsedMillis) / 1000).coerceAtLeast(0)
    val text = when {
        secs >= 3600 -> "%dh %02dm".format(secs / 3600, (secs % 3600) / 60)
        secs >= 60 -> "%dm %02ds".format(secs / 60, secs % 60)
        else -> "${secs}s"
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
