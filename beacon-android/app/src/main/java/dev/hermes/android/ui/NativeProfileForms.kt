package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeProfileAdminState
import dev.hermes.android.data.PROFILE_ROLE_MAX
import dev.hermes.android.data.profileNameProblem

// The three forms for profiles, shared by Settings › Profiles and Bot Mode (where a profile is a bot). Each closes itself when the
// change finishes (NativeProfileAdminState.completed grows) and shows what went wrong in place.

private val noCapitals = KeyboardOptions(capitalization = KeyboardCapitalization.None)

/** The "completed" counter when the form opened; the form closes once it grows. */
@Composable private fun CloseWhenDone(admin: NativeProfileAdminState, onDone: () -> Unit) {
    val opened = remember { admin.completed }
    LaunchedEffect(admin.completed) { if (admin.completed > opened) onDone() }
}

/** New profile: a name checked the way Hermes checks it, what to start from (empty or a copy of [profiles], each an id and its label), and an optional role. Bot Mode's suggested roles open it with [initialName] and [initialRole] filled in. */
@Composable internal fun NewProfileDialog(
    profiles: List<Pair<String, String>>, admin: NativeProfileAdminState, onCreate: (name: String, copyFrom: String, role: String) -> Unit, onDismiss: () -> Unit,
    initialName: String = "", initialRole: String = "",
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var from by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(initialRole) }
    var choosing by remember { mutableStateOf(false) }
    val problem = profileNameProblem(name, profiles.map { it.first })
    val shown = problem?.takeIf { name.isNotBlank() }
    CloseWhenDone(admin, onDismiss)
    AlertDialog(onDismissRequest = { if (!admin.busy) onDismiss() }, title = { Text(uiText("New profile")) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(64) }, label = { Text(uiText("Name")) }, singleLine = true, enabled = !admin.busy, isError = shown != null, keyboardOptions = noCapitals,
                supportingText = { Text(uiText(shown?.text ?: "Lowercase letters, numbers, - and _.")) }, modifier = Modifier.fillMaxWidth().testTag("new-profile-name"))
            Box {
                OutlinedButton(onClick = { choosing = true }, enabled = !admin.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new-profile-from")) {
                    Text("${uiText("Start from")}: ${if (from.isBlank()) uiText("Empty") else profiles.firstOrNull { it.first == from }?.second ?: from}", Modifier.weight(1f))
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(choosing, { choosing = false }) {
                    DropdownMenuItem({ Text(uiText("Empty")) }, onClick = { from = ""; choosing = false })
                    profiles.forEach { (id, label) -> DropdownMenuItem({ Text(label) }, onClick = { from = id; choosing = false }, modifier = Modifier.testTag("new-profile-from:$id")) }
                }
            }
            if (from.isNotBlank()) Text(uiText("Copies its settings, instructions, keys, skills and notes."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(role, { role = it.take(PROFILE_ROLE_MAX) }, label = { Text(uiText("Role (optional)")) }, singleLine = true, enabled = !admin.busy, modifier = Modifier.fillMaxWidth().testTag("new-profile-role"))
            if (admin.error.isNotBlank()) Text(uiText(admin.error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { onCreate(name, from, role) }, enabled = !admin.busy && problem == null, modifier = Modifier.heightIn(min = 48.dp).testTag("new-profile-create")) { Text(uiText(if (admin.busy) "Creating…" else "Create")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !admin.busy) { Text(uiText("Cancel")) } })
}

/** Edit role: the one line a bot's row shows under its name (Hermes keeps it as the profile's description). */
/** A profile's role (Bot Mode) or description (Profiles): Hermes keeps one short line. [title] and [label] are already translated. */
@Composable internal fun EditRoleDialog(profile: String, current: String, admin: NativeProfileAdminState, onSave: (String) -> Unit, onDismiss: () -> Unit,
    title: String = uiText("Edit role"), label: String = uiText("Role")) {
    var role by rememberSaveable(profile) { mutableStateOf(current) }
    CloseWhenDone(admin, onDismiss)
    AlertDialog(onDismissRequest = { if (!admin.busy) onDismiss() }, title = { Text(title) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(role, { role = it.take(PROFILE_ROLE_MAX) }, label = { Text(label) }, singleLine = true, enabled = !admin.busy, modifier = Modifier.fillMaxWidth().testTag("edit-role-field"))
            if (admin.error.isNotBlank()) Text(uiText(admin.error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = { onSave(role) }, enabled = !admin.busy && role.trim() != current.trim(), modifier = Modifier.heightIn(min = 48.dp).testTag("edit-role-save")) { Text(uiText(if (admin.busy) "Saving…" else "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !admin.busy) { Text(uiText("Cancel")) } })
}

/** Delete profile: says what goes in plain words, using the [name] the owner sees, and stays disabled until the exact profile id is typed.
 *  [groups] counts the shared spaces with this bot, which Bot Mode stops listing once one of their bots is gone. */
@Composable internal fun DeleteProfileDialog(profile: String, name: String, bot: Boolean, admin: NativeProfileAdminState, onDelete: () -> Unit, onDismiss: () -> Unit, groups: Int = 0) {
    var typed by rememberSaveable(profile) { mutableStateOf("") }
    CloseWhenDone(admin, onDismiss)
    AlertDialog(onDismissRequest = { if (!admin.busy) onDismiss() }, title = { Text(uiText(if (bot) "Delete bot?" else "Delete profile?")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("Deletes {0} and its chats, memory and settings on your computer. This can't be undone.").replace("{0}", name))
            if (groups > 0) Text(uiText("Its group chats ({0}) will disappear from Bot Mode.").replace("{0}", "$groups"))
            OutlinedTextField(typed, { typed = it }, label = { Text(uiText("Type {0} to confirm").replace("{0}", profile)) }, singleLine = true, enabled = !admin.busy, keyboardOptions = noCapitals, modifier = Modifier.fillMaxWidth().testTag("delete-profile-confirm"))
            if (admin.error.isNotBlank()) Text(uiText(admin.error), color = MaterialTheme.colorScheme.error)
        } },
        confirmButton = { TextButton(onClick = onDelete, enabled = !admin.busy && typed.trim() == profile, modifier = Modifier.heightIn(min = 48.dp).testTag("delete-profile-confirm-button")) { Text(uiText(if (admin.busy) "Deleting…" else "Delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !admin.busy) { Text(uiText("Cancel")) } })
}

/**
 * A profile's instructions, whole and editable, over the page: Save keeps them in Hermes; leaving with changes asks first.
 * Owner, 4 Nov 2026: the page showed them read-only, through the chat renderer.
 */
@Composable internal fun EditInstructionsDialog(profile: String, current: String, admin: NativeProfileAdminState, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var draft by rememberSaveable(profile) { mutableStateOf(current) }
    var leaving by remember { mutableStateOf(false) }
    CloseWhenDone(admin, onDismiss)
    val changed = draft != current
    val close = { if (!admin.busy) { if (changed) leaving = true else onDismiss() } }
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxSize().testTag("profile-instructions-editor"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing)) {
                androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = close, enabled = !admin.busy, modifier = Modifier.testTag("profile-instructions-close")) {
                        androidx.compose.material3.Icon(Icons.Rounded.Close, uiText("Close"))
                    }
                    Text(uiText("Instructions"), Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { onSave(draft) }, enabled = !admin.busy && changed, modifier = Modifier.heightIn(min = 48.dp).testTag("profile-instructions-save")) {
                        Text(uiText(if (admin.busy) "Saving..." else "Save"))
                    }
                }
                if (admin.error.isNotBlank()) Text(uiText(admin.error), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp).testTag("profile-instructions-field"),
                    enabled = !admin.busy, textStyle = MaterialTheme.typography.bodyLarge, placeholder = { Text(uiText("No instructions")) })
            }
        }
    }
    if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text(uiText("Discard changes?")) },
        confirmButton = { TextButton(onClick = { leaving = false; onDismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag("profile-instructions-discard")) { Text(uiText("Discard")) } },
        dismissButton = { TextButton(onClick = { leaving = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Keep editing")) } })
}
