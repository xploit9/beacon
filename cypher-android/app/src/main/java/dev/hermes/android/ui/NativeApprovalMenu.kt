package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val capturedClient = vm.client
    val capturedScope = vm.authenticatedScope()?.takeIf { it.origin == ui.origin && it.owner == ui.owner && it.profile == ui.profile && it.session == chat.sessionId }
    val scope = rememberCoroutineScope()
    var open by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var confirmOff by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var confirmYolo by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var saving by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var lastVerifiedYolo by remember(capturedClient, capturedScope) { mutableStateOf<Boolean?>(null) }
    val key = "picker:approvals"
    val path = "/api/approval/mode"
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    val record = data[key]
    val scopedRecord = record?.takeIf { it.string("scope_key") == capturedClient?.scope?.key && it.string("session_id") == capturedScope?.session }
    val current = scopedRecord?.string("value").orEmpty()
    val profileMode = scopedRecord?.string("profile_mode").orEmpty()
    val yoloKnown = scopedRecord?.optBoolean("session_status_known") == true
    val yolo = (if (yoloKnown) scopedRecord?.optBoolean("session_bypass") == true else lastVerifiedYolo == true) && profileMode != "off"
    val loading = key in refreshing
    LaunchedEffect(scopedRecord) { if (yoloKnown) lastVerifiedYolo = scopedRecord?.optBoolean("session_bypass") }
    fun refresh() { vm.load(key, path, mapOf("session_id" to capturedScope?.session.orEmpty())) }
    fun apply(mode: String) {
        if (saving) return
        if (!enabled || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Reopen Approvals in the original conversation."); return
        }
        saving = true
        val job = vm.action(path, json("mode" to mode, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false, successNotice = "Hermes approvals updated") { _, result ->
            lastVerifiedYolo = false
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
            vm.notice("Approvals are Off for this profile, so every chat already skips prompts."); return
        }
        if (!enabled || loading || current !in nativeApprovalModes || recordError(record).isNotBlank() || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Reopen Approvals in the original conversation."); return
        }
        saving = true
        val job = vm.action(path, json("yolo" to turnOn, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false,
            successNotice = if (turnOn) "YOLO enabled for this chat" else "YOLO disabled for this chat") { _, result ->
                lastVerifiedYolo = turnOn
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
            Icon(Icons.Rounded.Shield, uiText(if (yolo) "Approvals · YOLO on" else "Approvals"), tint = if (yolo) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text("Approvals · ${profileDisplayLabel(capturedClient?.scope?.profile.orEmpty(), profileRows, profileLoading)}") }, {}, enabled = false)
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
            HorizontalDivider()
            val yoloActionEnabled = enabled && !loading && !saving && current in nativeApprovalModes && error.isBlank() && profileMode != "off"
            // With profile approvals Off every chat already skips prompts, so the switch shows on and is locked.
            val yoloOn = yolo || profileMode == "off" && current.isNotBlank()
            DropdownMenuItem(
                text = { Column {
                    Text("YOLO for this chat")
                    if (profileMode == "off" && current.isNotBlank()) Text("On for every chat while approvals are Off", style = MaterialTheme.typography.bodySmall)
                } },
                trailingIcon = { Switch(checked = yoloOn, onCheckedChange = null, enabled = yoloActionEnabled) },
                enabled = yoloActionEnabled,
                modifier = Modifier.testTag("approval-yolo").semantics { toggleableState = if (yoloOn) ToggleableState.On else ToggleableState.Off },
                onClick = { if (yolo) applyYolo(false) else { open = false; confirmYolo = true } },
            )
            DropdownMenuItem({ Text("Allowed toolsets") }, { open = false; vm.openCatalog("Toolsets") }, enabled = enabled && !saving)
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false }, title = { Text("Turn off Hermes approvals?") },
        text = { Text("Hermes will run tools in this profile without asking.") },
        confirmButton = { TextButton({ confirmOff = false; apply("off") }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
    if (confirmYolo) AlertDialog(onDismissRequest = { confirmYolo = false }, title = { Text("Enable YOLO for this chat?") },
        text = { Text("Hermes stops asking for approval in this chat until you turn YOLO off.") },
        confirmButton = { TextButton({ confirmYolo = false; applyYolo(true) }) { Text("Enable YOLO") } },
        dismissButton = { TextButton({ confirmYolo = false }) { Text("Cancel") } })
}
