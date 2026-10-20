package dev.hermes.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import org.json.JSONObject

/** "Add to project…" or "Remove from <project>", from where Hermes files the conversation. */
@Composable internal fun ConversationProjectItem(vm: CypherViewModel, id: String, onAdd: () -> Unit, onDone: () -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    LaunchedEffect(id) { vm.loadConversationProject(id) }
    // A known answer stays shown while it refreshes, so the item does not flicker.
    val record = data["project-of:$id"]?.takeIf { it.string("session_id") == id && it.has("project") }
    val project = record?.optJSONObject("project")
    when {
        record == null -> DropdownMenuItem({ Text(uiText("Project…")) }, {}, leadingIcon = { Icon(Icons.Rounded.Folder, null) }, enabled = false)
        project != null -> {
            DropdownMenuItem({ Text(uiText("Move to another project…")) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.DriveFileMove, null) }, modifier = Modifier.testTag("conversation-project-move"),
                onClick = { onDone(); onAdd() })
            DropdownMenuItem({ Text(uiText("Remove from") + " " + project.string("name")) }, leadingIcon = { Icon(Icons.Rounded.FolderOff, null) }, modifier = Modifier.testTag("conversation-project-remove"),
                onClick = { onDone(); vm.conversationAction(id, "/api/projects/detach", json("id" to project.string("id")), "Removed from ${project.string("name")}") })
        }
        else -> DropdownMenuItem({ Text(uiText("Add to project…")) }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) }, modifier = Modifier.testTag("conversation-project-add"),
            onClick = { onDone(); onAdd() })
    }
}

/** The long-press menu of a conversation in history: pin, rename, project, archive, delete. */
@Composable internal fun ConversationMenuItems(vm: CypherViewModel, row: JSONObject, onDone: () -> Unit, onRename: () -> Unit, onProject: () -> Unit, onDelete: () -> Unit) {
    val id = sessionRowId(row)
    val activeProfile = vm.ui.collectAsStateWithLifecycle().value.profile
    val profile = row.string("profile", activeProfile)
    val pinned = row.optBoolean("pinned")
    val archived = row.optBoolean("archived")
    DropdownMenuItem({ Text(conversationMenuTitle(uiText(if (pinned) "Unpin" else "Pin"))) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) }, modifier = Modifier.testTag("conversation-pin"),
        onClick = { onDone(); vm.conversationAction(id, "/api/session/pin", json("pinned" to !pinned), if (pinned) "Unpinned" else "Pinned", profile) })
    DropdownMenuItem({ Text(conversationMenuTitle(uiText("Rename"))) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { onDone(); onRename() })
    if (profile == activeProfile) ConversationProjectItem(vm, id, onAdd = onProject, onDone = onDone)
    DropdownMenuItem({ Text(conversationMenuTitle(uiText(if (archived) "Unarchive" else "Archive"))) }, leadingIcon = { Icon(if (archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
        onClick = { onDone(); vm.conversationAction(id, "/api/session/archive", json("archived" to !archived), if (archived) "Unarchived" else "Archived", profile) })
    DropdownMenuItem({ Text(conversationMenuTitle(uiText("Delete")), color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
        modifier = Modifier.testTag("conversation-delete"), onClick = { onDone(); onDelete() })
}

@Composable internal fun RenameConversationDialog(vm: CypherViewModel, id: String, profile: String, title: String, onDismiss: () -> Unit) {
    var name by remember(id) { mutableStateOf(title) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Rename")) },
        text = { OutlinedTextField(name, { name = it.take(200) }, singleLine = true, modifier = Modifier.testTag("conversation-rename-field")) },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.conversationAction(id, "/api/session/rename", json("title" to name.trim()), "Renamed", profile) }, enabled = name.isNotBlank() && name.trim() != title) { Text(uiText("Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Cancel")) } })
}

@Composable internal fun DeleteConversationDialog(vm: CypherViewModel, id: String, profile: String, title: String, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Delete conversation?")) }, text = { Text(title.ifBlank { uiText("Untitled conversation") }) },
        confirmButton = { TextButton(onClick = { onDismiss(); vm.conversationAction(id, "/api/session/delete", done = "Deleted", profile = profile) }, modifier = Modifier.testTag("conversation-delete-confirm")) { Text(uiText("Delete"), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Cancel")) } })
}

/** A thin rule with the day between messages from different days. */
@Composable internal fun DayDivider(label: String) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().testTag("day-divider"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        Text(label, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
}

/** Relay's empty-chat starters; a tap fills the composer so the prompt can still be edited. */
@Composable internal fun StarterPrompts(onPick: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().testTag("starter-prompts"), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.CenterHorizontally), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        listOf("What can you do?", "Plan my day", "Explain something simply").forEach { prompt ->
            val text = uiText(prompt)
            SuggestionChip(onClick = { onPick(text) }, label = { Text(text) })
        }
    }
}
