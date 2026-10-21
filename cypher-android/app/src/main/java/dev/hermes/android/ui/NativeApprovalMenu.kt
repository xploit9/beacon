package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // A per-chat YOLO bypass reports value "off"; the profile's own mode is what the other choices show.
    val selection = if (yolo) "yolo" else profileMode.ifBlank { current }
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
            ApprovalShield(selection, uiText("Approvals") + ": " + approvalChoiceLabel(selection.ifBlank { "manual" }))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text("Approvals · ${profileDisplayLabel(capturedClient?.scope?.profile.orEmpty(), profileRows, profileLoading)}") }, {}, enabled = false)
            if (loading || saving) DropdownMenuItem({ Text(if (saving) "Saving…" else "Refreshing…") }, {}, enabled = false)
            val error = recordError(record)
            if (error.isNotBlank()) DropdownMenuItem({ Text(error, maxLines = 3, color = MaterialTheme.colorScheme.error) }, { refresh() })
            val choicesEnabled = enabled && !loading && !saving && current in nativeApprovalModes && error.isBlank()
            (nativeApprovalModes + "yolo").forEach { choice ->
                val yoloLocked = choice == "yolo" && profileMode == "off"
                val description = when (choice) {
                    "manual" -> "Ask before risky actions"
                    "smart" -> "Ask only when it looks risky"
                    "off" -> "Never ask in this profile"
                    else -> if (yoloLocked) "Off already skips every prompt" else "Never ask in this chat"
                }
                val isSelected = selection == choice
                DropdownMenuItem(text = { Column { Text(approvalChoiceLabel(choice)); Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 2) } },
                    leadingIcon = { ApprovalShield(choice, null) },
                    trailingIcon = { if (isSelected) Icon(Icons.Rounded.Check, "Selected") },
                    enabled = choicesEnabled && !yoloLocked,
                    modifier = Modifier.testTag(if (choice == "yolo") "approval-yolo" else "approval-mode-$choice").semantics { selected = isSelected },
                    onClick = {
                        when {
                            isSelected && choice == "yolo" -> open = false
                            choice == "yolo" -> { open = false; confirmYolo = true }
                            // Choosing a mode also clears this chat's YOLO (the native route turns the session bypass off first).
                            choice == "off" && profileMode != "off" -> { open = false; confirmOff = true }
                            else -> apply(choice)
                        }
                    })
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Allowed toolsets") }, { open = false; vm.openCatalog("Toolsets") }, enabled = enabled && !saving)
        }
    }
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false }, title = { Text(uiText("Turn off approvals?")) },
        text = { Text(assistantText("Cypher will run tools in this profile without asking.")) },
        confirmButton = { TextButton({ confirmOff = false; apply("off") }) { Text("Turn off") } },
        dismissButton = { TextButton({ confirmOff = false }) { Text("Cancel") } })
    if (confirmYolo) AlertDialog(onDismissRequest = { confirmYolo = false }, title = { Text("Enable YOLO for this chat?") },
        text = { Text(assistantText("Cypher stops asking for approval in this chat until you turn YOLO off.")) },
        confirmButton = { TextButton({ confirmYolo = false; applyYolo(true) }) { Text("Enable YOLO") } },
        dismissButton = { TextButton({ confirmYolo = false }) { Text("Cancel") } })
}

internal fun approvalChoiceLabel(choice: String): String = when (choice) {
    "smart" -> "Auto"
    "off" -> "Off"
    "yolo" -> "YOLO"
    else -> "Manual"
}

/** One shield, coloured by the chat's approval choice: Manual blue, Auto green, Off red, YOLO orange with a "Y". */
@Composable internal fun ApprovalShield(selection: String, description: String?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = when (selection) {
        "smart" -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        "off" -> MaterialTheme.colorScheme.error
        "yolo" -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        else -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
    }
    Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Shield, description, tint = tint)
        if (selection == "yolo") Text("Y", Modifier.padding(bottom = 1.dp), color = if (dark) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}
