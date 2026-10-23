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
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.nativeApprovalModes
import kotlinx.coroutines.launch
import org.json.JSONObject

internal fun chatApprovalSelection(record: JSONObject?, profileKey: String?, sessionId: String?): String {
    if (record == null || record.string("scope_key") != profileKey || record.string("session_id") != sessionId || recordError(record).isNotBlank()) return "unknown"
    val mode = record.string("profile_mode")
    return when {
        mode == "off" -> "off"
        !record.optBoolean("session_status_known") -> "unknown"
        record.optBoolean("session_bypass") -> "yolo"
        mode in setOf("manual", "smart") -> mode
        else -> "unknown"
    }
}

/** Composer shield adapts Hermes Relay's session-info approval-bypass indicator. */
@Composable internal fun NativeApprovalMenu(vm: BeaconViewModel, enabled: Boolean) {
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val pending by vm.conversationPending.collectAsStateWithLifecycle()
    val capturedClient = vm.client
    val capturedScope = vm.authenticatedScope()?.takeIf { it.origin == ui.origin && it.owner == ui.owner && it.profile == ui.profile && it.session == chat.sessionId }
    val scope = rememberCoroutineScope()
    var open by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var confirmYolo by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    var saving by remember(capturedClient, capturedScope) { mutableStateOf(false) }
    val busy = saving || "${capturedScope?.key}|/api/approval/mode" in pending || "${capturedClient?.scope?.key}|/api/profile/approval-mode" in pending
    val key = "picker:approvals"
    val record = data[key]?.takeIf { it.string("scope_key") == capturedClient?.scope?.key && it.string("session_id") == capturedScope?.session }
    val profileMode = record?.string("profile_mode").orEmpty()
    val loading = key in refreshing
    val selection = if (loading || busy) "unknown" else chatApprovalSelection(record, capturedClient?.scope?.key, capturedScope?.session)
    val runtimeSession = chat.raw.string("runtime_session_id")
    LaunchedEffect(capturedClient, capturedScope, ui.connected, chat.projectionReady, runtimeSession, busy) {
        // A passive REST history has no runtime; painting its shield must not resume one.
        if (ui.connected && capturedScope != null && chat.projectionReady && !busy) vm.loadChatApprovals(passive = runtimeSession.isBlank())
    }
    fun applyYolo(turnOn: Boolean) {
        if (saving || "${capturedScope?.key}|/api/approval/mode" in vm.conversationPending.value) return
        if (!enabled || loading || profileMode !in nativeApprovalModes || profileMode == "off" || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice("Refresh Approvals in this chat before changing it."); return
        }
        saving = true
        val job = vm.action("/api/approval/mode", json("yolo" to turnOn, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false,
            successNotice = if (turnOn) "YOLO enabled for this chat" else "This chat follows the profile's approvals") { _, result -> vm.storeData(key, result) }
        scope.launch { try { job.join(); open = false } finally { saving = false } }
    }
    Box {
        IconButton(onClick = { open = true; vm.loadChatApprovals() }, enabled = enabled && capturedClient != null && capturedScope != null && !busy, modifier = Modifier.testTag("composer-approvals")) {
            ApprovalShield(selection, "Approvals: ${approvalChoiceLabel(selection)}")
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text("Approvals in this chat") }, {}, enabled = false)
            if (loading || busy) DropdownMenuItem({ Text(if (busy) "Saving…" else "Checking…") }, {}, enabled = false)
            if (record == null && !loading) DropdownMenuItem({ Text("Approvals are unknown. Tap to retry.") }, { vm.loadChatApprovals() })
            if (profileMode == "off") DropdownMenuItem({ Text("Approvals are off for this profile") }, {}, enabled = false)
            val choicesEnabled = enabled && !loading && !busy && profileMode in nativeApprovalModes && profileMode != "off"
            listOf("ask", "yolo").forEach { choice ->
                val isSelected = if (choice == "ask") selection in setOf("manual", "smart") else selection == "yolo"
                DropdownMenuItem(
                    text = { Column {
                        Text(if (choice == "ask") "Profile default" else "YOLO in this chat")
                        Text(if (choice == "yolo") "Skip approval prompts in this chat" else if (profileMode == "manual") "Ask first" else approvalChoiceLabel(profileMode), style = MaterialTheme.typography.bodySmall)
                    } },
                    leadingIcon = { ApprovalShield(if (choice == "ask") profileMode else "yolo", null) },
                    trailingIcon = { if (isSelected) Icon(Icons.Rounded.Check, "Selected") },
                    enabled = choicesEnabled,
                    modifier = Modifier.testTag("approval-$choice").semantics { selected = isSelected },
                    onClick = { when {
                        isSelected -> open = false
                        choice == "yolo" -> { open = false; confirmYolo = true }
                        else -> applyYolo(false)
                    } },
                )
            }
            HorizontalDivider()
            DropdownMenuItem({ Text("Change for all chats") }, { open = false; vm.more("Profiles") }, enabled = !busy, modifier = Modifier.testTag("approval-profile-settings"))
            DropdownMenuItem({ Text("Allowed toolsets") }, { open = false; vm.openCatalog("Toolsets") }, enabled = enabled && !busy)
        }
    }
    if (confirmYolo) AlertDialog(onDismissRequest = { confirmYolo = false }, title = { Text("Enable YOLO for this chat?") },
        text = { Text(assistantText("Beacon stops asking for approval in this chat until you turn YOLO off.")) },
        confirmButton = { TextButton({ confirmYolo = false; applyYolo(true) }) { Text("Enable YOLO") } },
        dismissButton = { TextButton({ confirmYolo = false }) { Text("Cancel") } })
}

/** Profile-only policy lives in Settings; the chat menu can only toggle its own bypass. */
@Composable internal fun ProfileApprovalSettings(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val pending by vm.conversationPending.collectAsStateWithLifecycle()
    val active = vm.client
    val scope = rememberCoroutineScope()
    var saving by remember(active, ui.profile) { mutableStateOf(false) }
    val busy = saving || "${active?.scope?.key}|/api/profile/approval-mode" in pending
    val key = "profile:approvals"
    val value = data[key]?.takeIf { it.string("scope_key") == active?.scope?.key }
    val loading = key in refreshing
    fun refresh() { vm.load(key, "/api/profile/approval-mode") }
    LaunchedEffect(active, ui.profile, ui.connected) { if (ui.connected) refresh() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("profile-approvals")) {
        Text("Approvals for all chats", style = MaterialTheme.typography.titleMedium)
        Text("Chats follow this profile unless YOLO is enabled in that chat.", style = MaterialTheme.typography.bodySmall)
        if (loading || busy || value == null && recordError(data[key]).isBlank()) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (value?.string("value") == "off") Text("Approvals are off for this profile", color = MaterialTheme.colorScheme.error)
        if (recordError(data[key]).isNotBlank()) TextButton(onClick = { refresh() }) { Text("Couldn't load approvals. Retry") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("manual", "smart").forEach { mode ->
                FilterChip(selected = value?.string("value") == mode, enabled = active != null && value != null && !loading && !busy,
                    modifier = Modifier.testTag("profile-approval-$mode"), label = { Text(approvalChoiceLabel(mode)) }, onClick = {
                        if (active != null && vm.client === active && !saving) {
                            saving = true
                            val job = vm.action("/api/profile/approval-mode", json("mode" to mode, "scope_key" to active.scope.key), refresh = false, successNotice = "Profile approvals updated") { _, result ->
                                vm.storeData(key, result); vm.loadChatApprovals()
                            }
                            scope.launch { try { job.join() } finally { saving = false } }
                        }
                    })
            }
        }
    }
}

internal fun approvalChoiceLabel(choice: String): String = when (choice) {
    "manual" -> "Manual"
    "smart" -> "Auto"
    "off" -> "Off"
    "yolo" -> "YOLO"
    else -> "Unknown"
}

@Composable internal fun ApprovalShield(selection: String, description: String?) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = when (selection) {
        "manual" -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
        "smart" -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        "off" -> MaterialTheme.colorScheme.error
        "yolo" -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Shield, description, tint = tint)
        if (selection == "yolo") Text("Y", Modifier.padding(bottom = 1.dp), color = if (dark) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
    }
}
