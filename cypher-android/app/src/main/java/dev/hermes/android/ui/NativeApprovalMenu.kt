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

/** Composer shield adapts Hermes Relay's session-info approval-bypass indicator. */
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
    var confirmYolo by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var saving by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var lastVerifiedYolo by remember(capturedClient, capturedScope) { mutableStateOf<Boolean?>(null) }
    var yoloReadRequested by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var yoloUnknownAfterRead by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    val key = "picker:approvals"
    val path = "/api/approval/mode"
    val record = data[key]
    val scopedRecord = record?.takeIf { it.string("scope_key") == capturedClient?.scope?.key && it.string("session_id") == capturedScope?.session }
    val current = scopedRecord?.string("value").orEmpty()
    val profileMode = scopedRecord?.string("profile_mode").orEmpty()
    val yoloKnown = scopedRecord?.optBoolean("session_status_known") == true
    val yolo = (if (yoloKnown) scopedRecord?.optBoolean("session_bypass") == true else lastVerifiedYolo == true) && profileMode != "off"
    val yoloUnknown = scopedRecord != null && current.isNotBlank() && !yoloKnown && profileMode != "off"
    val loading = key in refreshing
    LaunchedEffect(scopedRecord) {
        if (yoloKnown) {
            lastVerifiedYolo = scopedRecord?.optBoolean("session_bypass")
            yoloUnknownAfterRead = false
        } else if (yoloReadRequested && lastVerifiedYolo != null) yoloUnknownAfterRead = true
        yoloReadRequested = false
    }
    fun refresh() { yoloReadRequested = true; vm.load(key, path, mapOf("session_id" to capturedScope?.session.orEmpty())) }
    fun apply(mode: String) {
        if (saving) return
        if (!enabled || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Reopen Approvals in the original conversation."); return
        }
        saving = true
        val job = vm.action(path, json("mode" to mode, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false, successNotice = "Hermes approvals updated") { _, result ->
            lastVerifiedYolo = false
            yoloUnknownAfterRead = false
            yoloReadRequested = false
            vm.storeData(key, result)
        }
        scope.launch {
            try {
                job.join()
                open = false
            } finally { saving = false }
        }
    }
    fun applyYolo(turnOn: Boolean) {
        if (saving) return
        if (turnOn && profileMode == "off") {
            vm.notice("Profile approvals are Off, so this chat already bypasses prompts. Choose Manual or Smart before using the per-chat override."); return
        }
        if (!enabled || loading || current !in nativeApprovalModes || recordError(record).isNotBlank() || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Reopen Approvals in the original conversation."); return
        }
        saving = true
        val job = vm.action(path, json("yolo" to turnOn, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false,
            successNotice = if (turnOn) "YOLO enabled for this chat" else "YOLO disabled for this chat") { _, result ->
                lastVerifiedYolo = turnOn
                yoloUnknownAfterRead = false
                yoloReadRequested = false
                vm.storeData(key, result)
            }
        scope.launch {
            try {
                job.join()
                open = false
            } finally { saving = false }
        }
    }
    Box {
        IconButton(onClick = { open = true; refresh() }, enabled = enabled && capturedClient != null && capturedScope != null && !saving, modifier = Modifier.testTag("composer-approvals")) {
            val description = when {
                yolo && yoloUnknownAfterRead -> "Approvals · YOLO on · last verified"
                yolo -> "Approvals · YOLO on"
                yoloUnknown && yoloUnknownAfterRead && lastVerifiedYolo == false -> "Approvals · YOLO off · last verified"
                yoloUnknown && lastVerifiedYolo == false -> "Approvals"
                yoloUnknown -> "Approvals · YOLO status unknown for this chat"
                else -> "Approvals"
            }
            Icon(Icons.Rounded.Shield, uiText(description), tint = if (yolo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text("Approvals · ${capturedClient?.scope?.profile.orEmpty()}") }, {}, enabled = false)
            if (yoloUnknown) DropdownMenuItem({
                val last = lastVerifiedYolo?.let { ". Last verified YOLO: ${if (it) "on" else "off"}." }.orEmpty()
                Text("YOLO status unknown for this chat$last", style = MaterialTheme.typography.bodySmall)
            }, {}, enabled = false)
            if (scopedRecord?.optBoolean("session_bypass") == true && current.isNotBlank()) DropdownMenuItem({ Text("This chat bypasses approvals. Choose a mode to restore its profile policy.", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
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
                    // Tick the profile's own mode: a per-chat YOLO bypass reports value "off" but has its own YOLO tick.
                    trailingIcon = { if (profileMode.ifBlank { current } == mode) Icon(Icons.Rounded.Check, "Selected") },
                    enabled = enabled && !loading && !saving && current in nativeApprovalModes && error.isBlank(),
                    modifier = Modifier.testTag("approval-mode-$mode"), onClick = {
                        if (mode == "off" && profileMode != "off") { open = false; confirmOff = true } else apply(mode)
                    })
            }
            if (profileMode == "off" && current.isNotBlank()) DropdownMenuItem(
                { Text("Profile approvals are Off, so this chat already bypasses prompts. Choose Manual or Smart before using the per-chat override.", style = MaterialTheme.typography.bodySmall) },
                {}, enabled = false,
            )
            val yoloActionEnabled = enabled && !loading && !saving && current in nativeApprovalModes && error.isBlank() && profileMode != "off"
            if (yoloUnknown) {
                DropdownMenuItem(
                    text = { Column {
                        Text("Turn on YOLO for this chat…")
                        Text("Confirm to bypass approval prompts for this chat only.", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    } },
                    enabled = yoloActionEnabled,
                    modifier = Modifier.testTag("approval-yolo"),
                    onClick = { open = false; confirmYolo = true },
                )
                DropdownMenuItem(
                    text = { Column {
                        Text("Turn off YOLO for this chat")
                        Text("Write a session-scoped disable while the current status is unknown.", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    } },
                    enabled = yoloActionEnabled,
                    modifier = Modifier.testTag("approval-yolo-off"),
                    onClick = { applyYolo(false) },
                )
            } else DropdownMenuItem(
                text = { Column {
                    Text("YOLO")
                    Text("Bypass approval prompts for this chat only. Resets when the session changes.", style = MaterialTheme.typography.bodySmall, maxLines = 2)
                } },
                trailingIcon = { if (yolo) Icon(Icons.Rounded.Check, "YOLO enabled") },
                enabled = yoloActionEnabled,
                modifier = Modifier.testTag("approval-yolo"),
                onClick = { if (yolo) applyYolo(false) else { open = false; confirmYolo = true } },
            )
            HorizontalDivider()
            DropdownMenuItem({ Text("Direct voice changes keep their separate confirmation.", style = MaterialTheme.typography.bodySmall) }, {}, enabled = false)
            DropdownMenuItem({ Text("Allowed toolsets") }, { open = false; vm.openCatalog("Toolsets") }, enabled = enabled && !saving)
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false }, title = { Text("Turn off Hermes approvals?") },
        text = { Text("Hermes tool actions in this profile can run without asking. Direct voice confirmations stay enabled.") },
        confirmButton = { TextButton({ confirmOff = false; apply("off") }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
    if (confirmYolo) AlertDialog(onDismissRequest = { confirmYolo = false }, title = { Text("Enable YOLO for this chat?") },
        text = { Text("Bypass approval prompts for this chat only. Resets when the session changes.") },
        confirmButton = { TextButton({ confirmYolo = false; applyYolo(true) }) { Text("Enable YOLO") } },
        dismissButton = { TextButton({ confirmYolo = false }) { Text("Cancel") } })
}
