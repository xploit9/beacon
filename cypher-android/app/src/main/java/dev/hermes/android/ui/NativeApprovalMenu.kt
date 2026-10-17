package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.nativeApprovalModes
import kotlinx.coroutines.launch

@Composable internal fun NativeApprovalMenu(vm: CypherViewModel, enabled: Boolean) {
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val capturedClient = vm.client
    val capturedScope = vm.authenticatedScope()?.takeIf { it.origin == ui.origin && it.owner == ui.owner && it.profile == ui.profile && it.session == chat.sessionId }
    val scope = rememberCoroutineScope()
    var open by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var confirmOff by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var saving by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    val key = "picker:approvals"
    val path = "/api/approval/mode"
    val record = data[key]
    val current = record?.takeIf { it.string("scope_key") == capturedClient?.scope?.key && it.string("session_id") == capturedScope?.session }?.string("value").orEmpty()
    val loading = key in refreshing
    fun refresh() { vm.load(key, path, mapOf("session_id" to capturedScope?.session.orEmpty())) }
    fun apply(mode: String) {
        if (saving) return
        if (!enabled || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Reopen Approvals in the original conversation."); return
        }
        saving = true
        val job = vm.action(path, json("mode" to mode, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false, successNotice = "Hermes approvals updated") { _, result -> vm.storeData(key, result) }
        scope.launch {
            try {
                job.join()
                open = false
            } finally { saving = false }
        }
    }
    Box {
        IconButton(onClick = { open = true; refresh() }, enabled = enabled && capturedClient != null && capturedScope != null && !saving, modifier = Modifier.testTag("composer-approvals")) { Icon(Icons.Rounded.Shield, uiText("Approvals")) }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text("Approvals · ${capturedClient?.scope?.profile.orEmpty()}") }, {}, enabled = false)
            if (record != null && current.isNotBlank() && !record.optBoolean("session_status_known")) DropdownMenuItem({ Text("Chat is initializing. Showing profile defaults.", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
            if (record?.optBoolean("session_bypass") == true && current.isNotBlank()) DropdownMenuItem({ Text("This chat bypasses approvals. Choose a mode to restore its profile policy.", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
            if (loading || saving) DropdownMenuItem({ Text(if (saving) "Saving…" else "Refreshing…") }, {}, enabled = false)
            val error = recordError(record)
            if (error.isNotBlank()) DropdownMenuItem({ Text(error, maxLines = 3, color = MaterialTheme.colorScheme.error) }, { refresh() })
            nativeApprovalModes.forEach { mode ->
                val description = when (mode) {
                    "manual" -> "Ask before risky Hermes actions"
                    "smart" -> "Let Hermes assess risk and ask when needed"
                    else -> "No Hermes approval prompts"
                }
                DropdownMenuItem(text = { Column { Text(mode.replaceFirstChar { it.uppercase() }); Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2) } },
                    trailingIcon = { if (current == mode) Icon(Icons.Rounded.Check, "Selected") },
                    enabled = enabled && !loading && !saving && current in nativeApprovalModes && error.isBlank(),
                    modifier = Modifier.testTag("approval-mode-$mode"), onClick = {
                        if (mode == "off" && record?.string("profile_mode") != "off") { open = false; confirmOff = true } else apply(mode)
                    })
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Direct voice changes keep their separate confirmation.", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
            DropdownMenuItem({ Text("Allowed toolsets") }, { open = false; vm.openCatalog("Toolsets") }, enabled = enabled && !saving)
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false }, title = { Text("Turn off Hermes approvals?") },
        text = { Text("Hermes tool actions in this profile can run without asking. Direct voice confirmations stay enabled.") },
        confirmButton = { TextButton({ confirmOff = false; apply("off") }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
}
