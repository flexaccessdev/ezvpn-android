package dev.flexaccess.ezvpn.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.TunnelStatus

val TunnelStatus.displayText: String
    get() = when (this) {
        TunnelStatus.DISCONNECTED -> "Disconnected"
        TunnelStatus.CONNECTING -> "Connecting…"
        TunnelStatus.CONNECTED -> "Connected"
        TunnelStatus.DISCONNECTING -> "Disconnecting…"
    }

val TunnelStatus.indicatorColor: Color
    get() = when (this) {
        TunnelStatus.CONNECTED -> Color(0xFF2E7D32)
        TunnelStatus.CONNECTING, TunnelStatus.DISCONNECTING -> Color(0xFFF9A825)
        TunnelStatus.DISCONNECTED -> Color(0xFF9E9E9E)
    }

@Composable
fun StatusDot(color: Color, size: Int = 10) {
    Box(
        Modifier
            .size(size.dp)
            .background(color, CircleShape),
    )
}

/** A titled block with one line per value (the "Active routes" readout). */
@Composable
fun ValueRows(title: String, values: List<String>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        if (values.isEmpty()) {
            Text("none", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            values.forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
fun Footnote(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(top = 4.dp))
}

val ScreenPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * The one clipboard call the key screen needs. Secrets are flagged sensitive
 * so the system clipboard preview hides them (Android 13+ honors the flag;
 * older versions ignore it).
 */
object Clipboard {
    fun copy(context: Context, label: String, value: String, isSecret: Boolean = false) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, value)
        if (isSecret) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
    }
}
