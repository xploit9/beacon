package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.nativeApprovalModes
import dev.hermes.android.data.appErrorText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A new, unsent chat has no state Hermes can report yet; it runs on the profile's default, so that is what it shows,
 *  or the YOLO choice Hermes accepted for it ([chosenYolo]) until Hermes can report the chat itself. */
internal fun chatApprovalSelection(record: JSONObject?, profileKey: String?, sessionId: String?, newChat: Boolean = false, chosenYolo: Boolean? = null): String {
    if (record == null || record.string("scope_key") != profileKey || record.string("session_id") != sessionId || recordError(record).isNotBlank()) return "unknown"
    val mode = record.string("profile_mode")
    return when {
        mode == "off" -> "off"
        !record.optBoolean("session_status_known") -> when {
            chosenYolo == true && mode in setOf("manual", "smart") -> "yolo"
            (chosenYolo == false || newChat) && mode in setOf("manual", "smart") -> mode
            else -> "unknown"
        }
        record.optBoolean("session_bypass") -> "yolo"
        mode in setOf("manual", "smart") -> mode
        else -> "unknown"
    }
}

/** While a read or save is in flight the shield keeps a known state on screen (the one just asked for, else the last read) instead of "Unknown". */
internal fun shownApprovalSelection(settled: String, inFlight: Boolean, asked: String?): String = if (inFlight) asked ?: settled else settled

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
    // A YOLO change in an unsent chat cannot be read back from Hermes before its first message, so the default is shown only until one is tried.
    val triedKey = "approvals-tried:${capturedScope?.key}"
    val newChat = chat.messages.isEmpty() && chat.streamId.isBlank() && data[triedKey] == null
    // The YOLO choice Hermes accepted for this chat, shown while Hermes cannot report an unsent chat's state.
    val chosenKey = "approvals-chosen:${capturedScope?.key}"
    val chosenYolo = data[chosenKey]?.takeIf { it.has("yolo") }?.optBoolean("yolo")
    // True while a YOLO change is being saved (the choice asked for), so the shield shows it at once.
    var askedYolo by remember(capturedClient, capturedScope) { mutableStateOf<Boolean?>(null) }
    val askedSelection = askedYolo?.let { if (it) "yolo" else profileMode.takeIf { mode -> mode in setOf("manual", "smart") } }
    val selection = shownApprovalSelection(chatApprovalSelection(record, capturedClient?.scope?.key, capturedScope?.session, newChat, chosenYolo), loading || busy, askedSelection)
    val runtimeSession = chat.raw.string("runtime_session_id")
    val reloadNotice = uiText("Refresh Approvals in this chat before changing it.")
    val skippedNotice = uiText("Approvals skipped for this chat")
    val restoredNotice = uiText("Approvals back on for this chat")
    LaunchedEffect(capturedClient, capturedScope, ui.connected, chat.projectionReady, runtimeSession, busy) {
        // A passive REST history has no runtime; painting its shield must not resume one.
        if (ui.connected && capturedScope != null && chat.projectionReady && !busy) vm.loadChatApprovals(passive = runtimeSession.isBlank())
    }
    fun applyYolo(turnOn: Boolean) {
        if (saving || "${capturedScope?.key}|/api/approval/mode" in vm.conversationPending.value) return
        if (!enabled || loading || profileMode !in nativeApprovalModes || profileMode == "off" || capturedClient == null || capturedScope == null || vm.client !== capturedClient || vm.authenticatedScope() != capturedScope) {
            vm.notice(reloadNotice); return
        }
        saving = true
        askedYolo = turnOn
        vm.storeData(triedKey, JSONObject())
        val job = vm.action("/api/approval/mode", json("yolo" to turnOn, "session_id" to capturedScope.session, "scope_key" to capturedScope.key), refresh = false,
            successNotice = if (turnOn) skippedNotice else restoredNotice) { _, result -> vm.storeData(chosenKey, json("yolo" to turnOn)); vm.storeData(key, result) }
        scope.launch { try { job.join(); open = false } finally { saving = false; askedYolo = null } }
    }
    Box {
        IconButton(onClick = { open = true; vm.loadChatApprovals() }, enabled = enabled && capturedClient != null && capturedScope != null && !busy, modifier = Modifier.testTag("composer-approvals")) {
            ApprovalShield(selection, uiText("Approvals") + ": " + uiText(approvalChoiceLabel(selection)))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 360.dp).testTag("approvals-menu")) {
            DropdownMenuItem({ Text(uiText("Approvals in this chat")) }, {}, enabled = false)
            if (loading || busy) DropdownMenuItem({ Text(uiText(if (busy) "Saving…" else "Checking…")) }, {}, enabled = false)
            if (record == null && !loading) DropdownMenuItem({ Text(uiText("Approvals are unknown. Tap to retry.")) }, { vm.loadChatApprovals() })
            if (profileMode == "off") DropdownMenuItem({ Text(uiText("Approvals are off")) }, {}, enabled = false)
            val choicesEnabled = enabled && !loading && !busy && profileMode in nativeApprovalModes && profileMode != "off"
            // Hermes keeps only YOLO per chat; Manual or Auto is the profile's mode and is changed in Profiles.
            if (profileMode != "off") listOf("ask", "yolo").forEach { choice ->
                val isSelected = if (choice == "ask") selection in setOf("manual", "smart") else selection == "yolo"
                DropdownMenuItem(
                    text = { Column {
                        Text(uiText(approvalChoiceLabel(if (choice == "ask") profileMode else "yolo")))
                        Text(uiText(if (choice == "yolo") "Never ask before actions" else if (profileMode == "smart") "Ask only for risky actions" else "Ask before actions"), style = MaterialTheme.typography.bodySmall)
                    } },
                    leadingIcon = { ApprovalShield(if (choice == "ask") profileMode else "yolo", null) },
                    trailingIcon = { if (isSelected) Icon(Icons.Rounded.Check, uiText("Selected")) },
                    enabled = choicesEnabled,
                    modifier = Modifier.testTag("approval-$choice").semantics { selected = isSelected },
                    onClick = { when {
                        isSelected -> open = false
                        choice == "yolo" -> { open = false; confirmYolo = true }
                        else -> applyYolo(false)
                    } },
                )
            }
        }
    }
    if (confirmYolo) AlertDialog(onDismissRequest = { confirmYolo = false }, title = { Text(uiText("Skip approvals for this chat?")) },
        text = { Text(assistantText("Beacon won’t ask before acting in this chat until you switch this off.")) },
        confirmButton = { TextButton({ confirmYolo = false; applyYolo(true) }) { Text(uiText("Skip approvals")) } },
        dismissButton = { TextButton({ confirmYolo = false }) { Text(uiText("Cancel")) } })
}

/** Profile-only policy lives in Settings; the chat menu can only toggle its own bypass. */
@Composable internal fun ProfileApprovalSettings(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val target by vm.settingsProfile.collectAsStateWithLifecycle()
    val selecting by vm.settingsProfileSelecting.collectAsStateWithLifecycle()
    val active = vm.settingsProfileClient()
    val version = vm.settingsProfileVersion()
    val scope = rememberCoroutineScope()
    var saving by remember(active, version) { mutableStateOf(false) }
    var loading by remember(active, version) { mutableStateOf(false) }
    var failed by remember(active, version) { mutableStateOf(false) }
    var readGeneration by remember(active, version) { mutableIntStateOf(0) }
    val busy = saving || selecting
    val key = "profile:approvals"
    val value = data[key]?.takeIf { it.string("scope_key") == active?.scope?.key }
    val updatedNotice = uiText("Profile approvals updated")
    fun refresh() {
        val captured = active ?: return
        val generation = ++readGeneration
        loading = true; failed = false
        scope.launch {
            val c = captured.detached()
            try {
                vm.authorizeSettingsProfile(captured, captured.scope.profile)
                if (!vm.settingsProfileCurrent(captured, version) || generation != readGeneration) return@launch
                val result = c.get("/api/profile/approval-mode")
                if (vm.settingsProfileCurrent(captured, version) && generation == readGeneration) vm.storeData(key, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (vm.settingsProfileCurrent(captured, version) && generation == readGeneration) { failed = true; vm.profileSettingsFailure(e) }
            } finally {
                c.closeNative()
                if (vm.settingsProfileCurrent(captured, version) && generation == readGeneration) loading = false
            }
        }
    }
    LaunchedEffect(active, version, target, ui.connected) { if (ui.connected) refresh() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).testTag("profile-approvals")) {
        Text(uiText("Approvals for all chats"), style = MaterialTheme.typography.titleMedium)
        Text(uiText("Chats follow this profile unless a chat skips approvals."), style = MaterialTheme.typography.bodySmall)
        if (busy || loading || value == null && !failed) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (value?.string("value") == "off") Text(uiText("Approvals are off for this profile"), color = MaterialTheme.colorScheme.error)
        if (failed) TextButton(onClick = { refresh() }) { Text(uiText("Couldn't load approvals. Retry")) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("manual", "smart").forEach { mode ->
                FilterChip(selected = value?.string("value") == mode, enabled = active != null && value != null && !loading && !busy && !failed,
                    modifier = Modifier.testTag("profile-approval-$mode"), label = { Text(uiText(approvalChoiceLabel(mode))) }, onClick = {
                        if (active != null && vm.settingsProfileCurrent(active, version) && !saving) {
                            saving = true
                            ++readGeneration
                            scope.launch {
                                val c = active.detached()
                                try {
                                    vm.authorizeSettingsProfile(active, active.scope.profile)
                                    if (!vm.settingsProfileCurrent(active, version)) return@launch
                                    val result = c.post("/api/profile/approval-mode", json("mode" to mode, "scope_key" to active.scope.key))
                                    if (vm.settingsProfileCurrent(active, version)) { vm.storeData(key, result); vm.notice(updatedNotice) }
                                } catch (e: Exception) {
                                    if (e is CancellationException) throw e
                                    if (vm.settingsProfileCurrent(active, version)) { vm.profileSettingsFailure(e); if (vm.settingsProfileCurrent(active, version)) vm.notice(appErrorText(e)) }
                                } finally {
                                    c.closeNative()
                                    if (vm.settingsProfileCurrent(active, version)) { saving = false; loading = false }
                                }
                            }
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
    "yolo" -> "Skip approvals"
    else -> "Unknown"
}

/** Every label [approvalChoiceLabel] gives; the menu and shield show them through uiText. */
internal val ApprovalStrings = listOf("manual", "smart", "off", "yolo", "").map(::approvalChoiceLabel)

@Composable internal fun ApprovalShield(selection: String, description: String?) {
    val colors = MaterialTheme.colorScheme
    // A glyph per mode, so the state never rests on colour alone.
    val (glyph, tint) = when (selection) {
        "manual" -> Icons.Rounded.GppMaybe to colors.primary
        "smart" -> Icons.Rounded.GppGood to colors.tertiary
        "off" -> Icons.Rounded.GppBad to colors.error
        "yolo" -> Icons.Rounded.Warning to colors.error
        else -> Icons.Rounded.Shield to colors.onSurfaceVariant
    }
    Icon(glyph, description, tint = tint)
}
