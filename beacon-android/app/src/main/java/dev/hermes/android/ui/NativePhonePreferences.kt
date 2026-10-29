package dev.hermes.android.ui

import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconViewModel
import kotlinx.coroutines.launch

@Composable internal fun NativePhonePreferences(vm: BeaconViewModel, page: String) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val editor = vm.phoneSettings
    val skills by editor.skills.collectAsStateWithLifecycle()
    val memory by editor.memory.collectAsStateWithLifecycle()
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    val saving = if (page == "Skills") skills.saving else memory.saving
    val dirty = if (page == "Skills") skills.editing && skills.draft != skills.content?.content else memory.editing.isNotBlank() && memory.draft != memory.documents.firstOrNull { it.target == memory.editing }?.content
    fun leave(action: () -> Unit) { if (!saving) { if (dirty) discard = action else action() } }
    val back = {
        leave {
            if (page == "Skills" && skills.editing) editor.skillEdit(false)
            else if (page == "Skills" && skills.selected != null) editor.closeSkill()
            else if (page == "Memory" && memory.editing.isNotBlank()) editor.editMemory("")
            else vm.backFromSettings()
        }
    }
    BackHandler(onBack = back)
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, ui.connected, vm.client) {
        editor.bind()
        if (page == "Skills") editor.loadSkills(force = false) else editor.loadMemory(force = false)
    }
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel(page)), back = if (page == "Memory" || page == "Skills" && skills.selected == null) null else back, menu = LocalOpenNavigationMenu.current) {
            if (page == "Memory") IconButton(onClick = { editor.loadMemory() }, enabled = vm.client != null && !memory.loading && !memory.saving && memory.editing.isBlank()) { Icon(Icons.Rounded.Refresh, uiText("Refresh memory")) }
            else IconButton(onClick = { skills.selected?.let(editor::openSkill) ?: editor.loadSkills() }, enabled = vm.client != null && !skills.loading && !skills.saving && !skills.editing) { Icon(Icons.Rounded.Refresh, uiText("Refresh skills")) }
        }
        if (editor.scopeKey != vm.client?.scope?.key) LinearProgressIndicator(Modifier.fillMaxWidth())
        else if (page == "Skills") NativeSkillsScreen(skills, editor::skillSearch, editor::openSkill,
            onBack = { leave(editor::closeSkill) }, onToggle = editor::toggleSkill,
            onEdit = { editing -> if (editing) editor.skillEdit(true) else leave { editor.skillEdit(false) } },
            onDraft = editor::skillDraft, onSave = editor::saveSkill,
            onRetry = { if (skills.editing) leave { editor.skillEdit(false); skills.selected?.let(editor::openSkill) } else skills.selected?.let(editor::openSkill) ?: editor.loadSkills() },
            renderInstructions = { Markdown(skillInstructionBody(it), vm) })
        else NativeMemoryScreen(memory, editor::editMemory, editor::memoryDraft, editor::saveMemory,
            onCancel = { leave { editor.editMemory("") } }, renderNotes = { Markdown(it, vm) })
    }
    discard?.let { action -> AlertDialog(onDismissRequest = { discard = null }, title = { Text(uiText("Discard unsaved changes?")) },
        text = { Text(uiText(if (page == "Memory") "Your saved notes stay unchanged." else "Your saved instructions stay unchanged.")) },
        confirmButton = { TextButton(onClick = { discard = null; action() }) { Text(uiText("Discard changes")) } },
        dismissButton = { TextButton(onClick = { discard = null }) { Text(uiText("Keep editing")) } }) }
}

@Composable internal fun NativeServerPage(vm: BeaconViewModel, page: String) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val state by vm.phoneSettings.server.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    var reviewUnknown by remember { mutableStateOf(false) }
    val callBlocksUpdate = uiText("End the current call before updating Hermes.")
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, ui.connected, vm.client) { vm.phoneSettings.bind(); vm.phoneSettings.loadServer(page) }
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current, actions = {
            IconButton(onClick = { vm.phoneSettings.loadServer(page) }, enabled = !state.loading) {
                Icon(Icons.Rounded.Refresh, uiText(if (page == "Server updates") "Refresh update status" else "Refresh"))
            }
        })
        NativeServerSettings(page, state, { vm.phoneSettings.loadServer(page, force = true) },
            onApplyUpdate = { if (vm.app.calls.state.value.active) vm.notice(callBlocksUpdate) else confirm = true },
            onLogFile = vm.phoneSettings::logFile, onLogSearch = vm.phoneSettings::logSearch, onReviewUnknown = { reviewUnknown = true })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(uiText("Update Hermes on your computer?")) },
        text = { Text(uiText("Hermes may reconnect while updating.")) },
        confirmButton = { TextButton(onClick = { confirm = false; if (!vm.app.calls.state.value.active) vm.phoneSettings.applyServerUpdate() else vm.notice(callBlocksUpdate) }) { Text(uiText("Apply update")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(uiText("Cancel")) } })
    if (reviewUnknown) AlertDialog(onDismissRequest = { reviewUnknown = false }, title = { Text(uiText("Check the server before retrying")) },
        text = { Text(uiText("The update's result is unknown. Check the installed version.")) },
        confirmButton = { TextButton(onClick = { reviewUnknown = false; vm.phoneSettings.clearReviewedUpdate() }) { Text(uiText("Check current version")) } },
        dismissButton = { TextButton(onClick = { reviewUnknown = false }) { Text(uiText("Keep waiting")) } })
}

@Composable internal fun NativeProfilesPage(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val state by vm.phoneSettings.bots.collectAsStateWithLifecycle()
    val models by vm.phoneSettings.botModels.collectAsStateWithLifecycle()
    val admin by vm.profileAdmin.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf("") }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(action: () -> Unit) {
        if (!state.saving) {
            val changed = state.editing && (state.draftModel != state.selected?.model || state.draftProvider != state.selected?.provider)
            if (changed) discard = action else action()
        }
    }
    val back = { leave { if (state.editing) vm.phoneSettings.editBot(false) else if (state.selected != null) vm.phoneSettings.closeBot() else vm.backFromSettings() } }
    BackHandler(onBack = back)
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client) { vm.phoneSettings.bind(); vm.phoneSettings.loadBots(); if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    // "Profile settings" on a bot in Bot Mode lands here with that profile asked for: show it once the list has loaded.
    LaunchedEffect(admin.open, state.loading, state.profiles) {
        if (admin.open.isNotBlank() && !state.loading && state.profiles.any { it.profile == admin.open }) { vm.phoneSettings.openBot(admin.open); vm.profileAdmin.opened() }
    }
    // A profile that was open and is no longer in the list (deleted here or on the computer) closes back to the list.
    LaunchedEffect(state.profiles, state.loading) { state.selected?.profile?.let { open -> if (!state.loading && state.profiles.isNotEmpty() && state.profiles.none { it.profile == open }) vm.phoneSettings.closeBot() } }
    Column(Modifier.fillMaxSize()) {
        Header(uiText("Profiles"), back = back, menu = LocalOpenNavigationMenu.current)
        ProfileApprovalSettings(vm)
        NativeBotSettings(state, vm.phoneSettings::openBot, { leave(vm.phoneSettings::closeBot) }, { vm.phoneSettings.editBot(true) },
            vm.phoneSettings::botDraft, vm.phoneSettings::saveBot, { leave { vm.phoneSettings.editBot(false) } },
            onSettings = { profile, page -> vm.viewModelScope.launch {
                if (profile != vm.ui.value.profile) vm.switchProfile(profile).join()
                if (vm.ui.value.profile == profile && vm.ui.value.tab == "More" && vm.ui.value.morePage in setOf("", "Profiles")) vm.more(settingsRoute(page))
            } }, onRefresh = { state.selected?.let { vm.phoneSettings.openBot(it.profile) } ?: vm.phoneSettings.loadBots() }, models = models,
            renderBehavior = { Markdown(it, vm) },
            profileLabel = { profile -> profileDisplayLabel(profile, profileRows, profileLoading) },
            onNewProfile = { creating = true }, canDelete = vm::canDeleteProfile, onDelete = { deleting = it })
    }
    if (creating) NewProfileDialog(profileRows.map { it.string("name") to profileDisplayName(it.string("name"), profileRows) }, admin,
        onCreate = { name, from, role -> vm.profileAdmin.create(name, from, role, profileRows.map { it.string("name") }) }, onDismiss = { creating = false })
    val deletable = deleting.isNotBlank() && vm.canDeleteProfile(deleting)
    LaunchedEffect(deleting, deletable) { if (deleting.isNotBlank() && !deletable) deleting = "" }
    if (deletable) DeleteProfileDialog(deleting, profileDisplayName(deleting, profileRows), bot = false, admin = admin, onDelete = { vm.deleteProfile(deleting) }, onDismiss = { deleting = "" })
    LaunchedEffect(creating, deleting) { vm.profileAdmin.clearError() }
    discard?.let { action -> AlertDialog(onDismissRequest = { discard = null }, title = { Text(uiText("Discard model selection?")) },
        confirmButton = { TextButton(onClick = { discard = null; action() }) { Text(uiText("Discard")) } },
        dismissButton = { TextButton(onClick = { discard = null }) { Text(uiText("Keep editing")) } }) }
}

private val skillDivider = Regex("(?m)^---[ \t]*$")
internal fun skillInstructionBody(source: String): String {
    val text = source.removePrefix("\uFEFF").replace("\r\n", "\n")
    val opening = skillDivider.find(text)?.takeIf { it.range.first == 0 } ?: return text
    val closing = skillDivider.find(text, opening.range.last + 1) ?: return text
    return text.substring(closing.range.last + 1).trimStart('\n')
}
