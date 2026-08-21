package dev.flexaccess.ezvpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.flexaccess.ezvpn.AuthKey
import dev.flexaccess.ezvpn.AuthKeyStore

/**
 * The auth-key manager: the app's shared, named ed25519 keys, with generate,
 * import (paste a secret from another device), rename, copy, and delete.
 * Public keys show unmasked (they are not secrets); secrets never render —
 * export copies straight to the clipboard, behind a confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen(keys: List<AuthKeyStore.Key>, store: AuthKeyStore, onBack: () -> Unit) {
    val context = LocalContext.current
    var addMenu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<KeyDialog?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auth keys") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, contentDescription = "Add key") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(text = { Text("Generate new key…") }, onClick = { addMenu = false; dialog = KeyDialog.Generate })
                            DropdownMenuItem(text = { Text("Enter existing key…") }, onClick = { addMenu = false; dialog = KeyDialog.Import })
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (keys.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No auth keys", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Generate a key (or paste one from another device), then put its public key on the server's authorized_keys file.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(keys, key = { it.id }) { key ->
                    KeyRow(
                        key,
                        onCopyPublic = { Clipboard.copy(context, "ezvpn public key", key.publicKey) },
                        onCopySecret = { dialog = KeyDialog.Export(key) },
                        onRename = { dialog = KeyDialog.Rename(key) },
                        onDelete = { dialog = KeyDialog.Delete(key) },
                    )
                    HorizontalDivider()
                }
                item {
                    Footnote(
                        "A profile authenticates with the key it selects. Deleting a key here doesn't " +
                            "disconnect profiles already saved with it — re-save a profile to change the key it uses.",
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        null -> {}
        KeyDialog.Generate -> NameDialog(
            title = "Name the new key",
            message = "Names only exist in this app's key list.",
            confirm = "Generate",
            onDismiss = { dialog = null },
        ) { name ->
            dialog = null
            val pair = AuthKey.generate()
            if (pair == null) errorMessage = "Key generation failed." else store.add(name, pair.secretKey).onFailure { errorMessage = it.message }
        }
        KeyDialog.Import -> ImportDialog(onDismiss = { dialog = null }) { name, secret ->
            dialog = null
            store.add(name, secret).onFailure { errorMessage = it.message }
        }
        is KeyDialog.Rename -> NameDialog(
            title = "Rename key",
            message = null,
            confirm = "Rename",
            initial = d.key.name,
            onDismiss = { dialog = null },
        ) { name ->
            dialog = null
            store.rename(d.key.id, name)?.let { errorMessage = it }
        }
        is KeyDialog.Export -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Copy the secret key?") },
            text = { Text("Anyone holding the secret key can connect as \"${d.key.name}\". Paste it into another device's key import.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    Clipboard.copy(context, "ezvpn secret key", d.key.secret, isSecret = true)
                }) { Text("Copy secret key") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        is KeyDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Delete \"${d.key.name}\"?") },
            text = { Text("The secret key is removed from this device's key list. The server keeps trusting its public key until that's taken off the authorized_keys file.") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    store.delete(d.key.id)?.let { errorMessage = it }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
    }

    errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text("Can't do that") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("OK") } },
        )
    }
}

private sealed interface KeyDialog {
    data object Generate : KeyDialog
    data object Import : KeyDialog
    data class Rename(val key: AuthKeyStore.Key) : KeyDialog
    data class Export(val key: AuthKeyStore.Key) : KeyDialog
    data class Delete(val key: AuthKeyStore.Key) : KeyDialog
}

@Composable
private fun KeyRow(
    key: AuthKeyStore.Key,
    onCopyPublic: () -> Unit,
    onCopySecret: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                key.publicKey,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Actions for ${key.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Copy public key") }, onClick = { menu = false; onCopyPublic() })
                DropdownMenuItem(text = { Text("Copy secret key…") }, onClick = { menu = false; onCopySecret() })
                DropdownMenuItem(text = { Text("Rename…") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete…", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    message: String?,
    confirm: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ImportDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter existing key") },
        text = {
            Column {
                Text(
                    "Paste a secret key generated elsewhere — copied from another device, or by \"flexaccess-keys generate-auth-key\" — to reuse its identity.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("ed25519-sec:…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name, secret) }, enabled = name.isNotBlank() && secret.isNotBlank()) { Text("Add key") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
