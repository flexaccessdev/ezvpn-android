package dev.flexaccess.ezvpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.tunnelcore.TunnelConnectionPath
import dev.flexaccess.ezvpn.tunnelcore.TunnelConnectionSnapshot
import dev.flexaccess.ezvpn.tunnelcore.TunnelCustomRelay
import kotlinx.coroutines.CancellationException

/**
 * On-demand "connection path" readout: a point-in-time snapshot of how the
 * running tunnel reaches the server (the live iroh relay/direct paths), like
 * `ezvpn client status` and the Apple app's sheet of the same name.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnPathSheet(query: suspend () -> TunnelConnectionSnapshot, onDismiss: () -> Unit) {
    var snapshot by remember { mutableStateOf(TunnelConnectionSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshToken) {
        try {
            snapshot = query()
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "Couldn't query the connection path: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Connection path", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { refreshToken++ }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
            }
            val queryError = error
            if (loading) {
                Footnote("Querying…")
            } else if (queryError != null) {
                Footnote(queryError, color = MaterialTheme.colorScheme.error)
            } else if (snapshot.paths.isEmpty()) {
                Footnote("No path yet — still establishing. Close this and try again in a moment.")
            } else {
                snapshot.paths.forEach { ConnPathRow(it) }
            }
            Footnote(
                "Snapshot taken just now — how this session reaches the server. Direct paths are " +
                    "peer-to-peer; relay paths hop through an iroh relay.",
            )
            if (snapshot.customRelays.isNotEmpty()) {
                SectionTitle("Custom relays")
                snapshot.customRelays.forEach { RelayRow(it) }
                Footnote("Health is reported by the running iroh endpoint; unavailable means it has not observed this relay yet.")
            }
        }
    }
}

@Composable
private fun ConnPathRow(path: TunnelConnectionPath) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(
            when (path.kind) {
                TunnelConnectionPath.Kind.DIRECT -> Color(0xFF2E7D32)
                TunnelConnectionPath.Kind.RELAY -> Color(0xFFEF6C00)
                TunnelConnectionPath.Kind.OTHER -> Color.Gray
            },
            size = 8,
        )
        Text(path.display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
        if (path.selected) {
            Text("active", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
        }
    }
}

@Composable
private fun RelayRow(relay: TunnelCustomRelay) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(relay.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        val (text, color) = when (relay.working) {
            true -> "Working" to Color(0xFF2E7D32)
            false -> (relay.error?.let { "Not working — $it" } ?: "Not working") to MaterialTheme.colorScheme.onSurfaceVariant
            null -> "Status unavailable" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}
