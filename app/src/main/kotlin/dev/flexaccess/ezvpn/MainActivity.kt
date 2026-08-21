package dev.flexaccess.ezvpn

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.flexaccess.ezvpn.ui.EzvpnRoot
import dev.flexaccess.ezvpn.ui.EzvpnTheme
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val manager = TunnelsManager.get(this)
        setContent {
            EzvpnTheme {
                ConsentGate(manager) { connect ->
                    EzvpnRoot(manager = manager, onConnect = connect)
                }
            }
        }
    }
}

/**
 * Wraps connect with the one-time system VPN consent: when the OS still needs
 * the user's approval, launch its dialog and connect once it comes back OK.
 */
@Composable
private fun ConsentGate(
    manager: TunnelsManager,
    content: @Composable (connect: (UUID) -> Unit) -> Unit,
) {
    var pending by remember { mutableStateOf<UUID?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && id != null) manager.connect(id)
    }
    content { id ->
        val intent = manager.consentIntent()
        if (intent == null) {
            manager.connect(id)
        } else {
            pending = id
            launcher.launch(intent)
        }
    }
}
