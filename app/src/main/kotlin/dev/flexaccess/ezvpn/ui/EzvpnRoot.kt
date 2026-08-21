package dev.flexaccess.ezvpn.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.flexaccess.ezvpn.TunnelsManager
import java.util.UUID

/** The app's screens; kept as plain state so no navigation library is needed. */
sealed interface Screen {
    data object List : Screen
    data class Detail(val id: UUID) : Screen
    data class Edit(val id: UUID?) : Screen
    data object Keys : Screen

    companion object {
        val saver: Saver<Screen, String> = Saver(
            save = {
                when (it) {
                    List -> "list"
                    is Detail -> "detail:${it.id}"
                    is Edit -> "edit:${it.id ?: ""}"
                    Keys -> "keys"
                }
            },
            restore = { s ->
                when {
                    s == "list" -> List
                    s == "keys" -> Keys
                    s.startsWith("detail:") -> runCatching { Detail(UUID.fromString(s.removePrefix("detail:"))) }.getOrNull()
                    s.startsWith("edit:") -> Edit(s.removePrefix("edit:").takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() })
                    else -> List
                }
            },
        )
    }
}

@Composable
fun EzvpnRoot(manager: TunnelsManager, onConnect: (UUID) -> Unit) {
    var stack by rememberSaveable(stateSaver = stackSaver) { mutableStateOf(listOf<Screen>(Screen.List)) }
    val screen = stack.last()
    val profiles by manager.profiles.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val keys by manager.authKeys.keys.collectAsStateWithLifecycle()

    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    when (screen) {
        Screen.List -> TunnelListScreen(
            profiles = profiles,
            state = state,
            onOpen = { push(Screen.Detail(it)) },
            onAdd = { push(Screen.Edit(null)) },
            onKeys = { push(Screen.Keys) },
            onConnect = onConnect,
            onDisconnect = { manager.disconnect() },
        )
        is Screen.Detail -> {
            val profile = profiles.firstOrNull { it.id == screen.id }
            if (profile == null) {
                // Deleted underneath us (or a stale restored route).
                pop()
            } else {
                TunnelDetailScreen(
                    profile = profile,
                    state = state,
                    manager = manager,
                    onBack = { pop() },
                    onEdit = { push(Screen.Edit(profile.id)) },
                    onConnect = onConnect,
                    onDisconnect = { manager.disconnect() },
                    onDeleted = { pop() },
                )
            }
        }
        is Screen.Edit -> TunnelEditScreen(
            profile = screen.id?.let { id -> profiles.firstOrNull { it.id == id } },
            keys = keys,
            manager = manager,
            onManageKeys = { push(Screen.Keys) },
            onDone = { pop() },
        )
        Screen.Keys -> KeysScreen(
            keys = keys,
            store = manager.authKeys,
            onBack = { pop() },
        )
    }
}

private val stackSaver: Saver<List<Screen>, Any> = Saver(
    save = { list -> ArrayList(list.map { with(Screen.saver) { save(it) } as String }) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        (saved as List<String>).mapNotNull { Screen.saver.restore(it) }.ifEmpty { listOf(Screen.List) }
    },
)
