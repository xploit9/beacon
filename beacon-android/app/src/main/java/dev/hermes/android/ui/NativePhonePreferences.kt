package dev.hermes.android.ui

import dev.hermes.android.core.copy
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.NativeMemoryState
import dev.hermes.android.data.parseNativeSkills
import dev.hermes.android.parseBotSummaries
import kotlinx.coroutines.delay

@Composable internal fun NativePhonePreferences(vm: BeaconViewModel, page: String) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val target by vm.settingsProfile.collectAsStateWithLifecycle()
    val profileClient = vm.settingsProfileClient()
    val profile = target.ifBlank { ui.profile }
    val profileName = profileDisplayName(profile, data["navigation-profiles"]?.rows("profiles").orEmpty())
    val editor = vm.phoneSettings
    val roster by vm.botMode.state.collectAsStateWithLifecycle()
    val choosing by vm.settingsProfileSelecting.collectAsStateWithLifecycle()
    val liveSkills by editor.skills.collectAsStateWithLifecycle()
    val liveMemory by editor.memory.collectAsStateWithLifecycle()
    // Until the editor holds this profile's lists (a cold start still reconnecting, or offline), the saved copy of this account and profile shows; with no client it is read-only.
    val savedOwner = profileClient?.scope ?: if (target.isBlank()) vm.restoredScope() else null
    val savedSkills = savedOwner?.takeIf { page == "Skills" && liveSkills.skills.isEmpty() && !liveSkills.editing }?.let { vm.screens.exact(it, "settings:skills")?.value }
    val savedMemory = savedOwner?.takeIf { page == "Memory" && liveMemory.documents.isEmpty() && liveMemory.editing.isBlank() }?.let { vm.screens.exact(it, "settings:memory")?.value }
    val seeded = savedSkills != null || savedMemory != null
    val offlineCopy = seeded && profileClient == null
    val skills = if (savedSkills != null) remember(savedSkills, liveSkills) { liveSkills.copy(skills = parseNativeSkills(savedSkills.copy())) } else liveSkills
    val memory = if (savedMemory != null) remember(savedMemory, liveMemory) { runCatching { NativeMemoryState.parse(liveMemory.scope, savedMemory.getJSONObject("status"), savedMemory.getJSONObject("notes")).copy(loading = liveMemory.loading, error = liveMemory.error) }.getOrDefault(liveMemory) } else liveMemory
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
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, ui.connected, vm.client, target, profileClient) {
        editor.bind()
        if (page == "Skills") editor.loadSkills(force = false) else editor.loadMemory(force = false)
    }
    Column(Modifier.fillMaxSize()) {
        // In Bot Mode the page belongs to a bot: its avatar and name, as Bot Mode shows bots, not the word Profile. While another bot is being
        // chosen the page names nobody, so it never shows one bot's name over another's notes.
        val botMode = ui.botModeEnabled && profile.isNotBlank() && !choosing
        val botTitle = if (botMode) roster.bots.firstOrNull { it.profile == profile }?.let { botName(it, data["navigation-profiles"]?.rows("profiles").orEmpty()) } ?: profileName else profileName
        Header(uiText(settingsLabel(page)), subtitle = if (ui.botModeEnabled) (if (botMode) botTitle else "") else if (profileName.isBlank()) "" else "${uiText("Profile")} · $profileName", back = if (page == "Skills" && skills.selected == null) null else back, menu = LocalOpenNavigationMenu.current) {
            if (botMode) ChatAvatar(botTitle, profile, Modifier.testTag("bot-page-avatar"), size = 32.dp)
            if (page == "Memory") SettingsIconButton(Icons.Rounded.Refresh, uiText("Refresh memory"), { editor.loadMemory() }, enabled = profileClient != null && !memory.loading && !memory.saving && memory.editing.isBlank())
            else SettingsIconButton(Icons.Rounded.Refresh, uiText("Refresh skills"), { skills.selected?.let(editor::openSkill) ?: editor.loadSkills() }, enabled = profileClient != null && !skills.loading && !skills.saving && !skills.editing)
        }
        // The editor is still binding to this profile: a placeholder page with the thin bar, not a lone progress line.
        // Chat join: CACHEFIX-A shows the saved copy here (seeded); CACHEFIX-B binds the editor to the restored account before the client exists.
        if (!seeded && editor.scopeKey != (profileClient?.scope?.key ?: vm.settingsRestoredScopeKey)) SettingsList(refreshing = true, testTag = "settings-binding") { item { SettingsSection(testTag = "settings-binding-section") { SettingsSkeletonRows(3) } } }
        else if (page == "Skills") NativeSkillsScreen(skills, editor::skillSearch, editor::openSkill,
            onBack = { leave(editor::closeSkill) }, onToggle = editor::toggleSkill,
            onEdit = { editing -> if (editing) editor.skillEdit(true) else leave { editor.skillEdit(false) } },
            onDraft = editor::skillDraft, onSave = editor::saveSkill,
            onRetry = { if (skills.editing) leave { editor.skillEdit(false); skills.selected?.let(editor::openSkill) } else skills.selected?.let(editor::openSkill) ?: editor.loadSkills() },
            renderInstructions = { Markdown(skillInstructionBody(it), vm) }, readOnly = offlineCopy, hasSavedContent = editor::hasSavedSkill)
        else NativeMemoryScreen(memory, editor::editMemory, editor::memoryDraft, editor::saveMemory,
            onCancel = { leave { editor.editMemory("") } }, renderNotes = { Markdown(it, vm) }, readOnly = offlineCopy)
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
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, ui.connected, vm.client) {
        vm.phoneSettings.bind(); vm.phoneSettings.loadServer(page)
        while (page == "Server updates" && ui.connected) { delay(15_000); vm.phoneSettings.resumeServerUpdate() }
    }
    Column(Modifier.fillMaxSize()) {
        // The checkpoints page is named for what it holds: the earlier versions of changed files.
        val backups = serverCheckpointPage(page)
        Header(if (backups) uiText("File backups") else uiText(settingsLabel(page)), "", back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current, actions = {
            SettingsIconButton(Icons.Rounded.Refresh, uiText(if (page == "Server updates") "Refresh update status" else "Refresh"), { vm.phoneSettings.loadServer(page) }, enabled = !state.loading)
        })
        // Until Hermes has been asked, an empty page is not an answer: it says it is checking, never "no backups" or "isn't available".
        val shown = if (state.asked || serverHasCache(page, state) || state.error.isNotBlank() || vm.client == null && !ui.loading && !ui.restoringSession) state else state.copy(loading = true)
        NativeServerSettings(page, shown, { vm.phoneSettings.loadServer(page, force = true) },
            onApplyUpdate = { if (vm.app.calls.state.value.active) vm.notice(callBlocksUpdate) else confirm = true },
            onLogFile = vm.phoneSettings::logFile, onLogSearch = vm.phoneSettings::logSearch, onReviewUnknown = { reviewUnknown = true })
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(uiText("Update Hermes on your computer?")) },
        text = { Text(uiText("Hermes may reconnect while updating.")) },
        confirmButton = { TextButton(onClick = { confirm = false; if (!vm.app.calls.state.value.active) vm.phoneSettings.applyServerUpdate() else vm.notice(callBlocksUpdate) }) { Text(uiText("Apply update")) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(uiText("Cancel")) } })
    if (reviewUnknown) AlertDialog(onDismissRequest = { reviewUnknown = false }, title = { Text(uiText("Check Hermes before retrying")) },
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
    val liveBots by vm.phoneSettings.bots.collectAsStateWithLifecycle()
    val target by vm.settingsProfile.collectAsStateWithLifecycle()
    val selecting by vm.settingsProfileSelecting.collectAsStateWithLifecycle()
    // The list opens with the profiles last saved for this account and profile, before the first read (and before the client exists).
    val savedBotsScope = vm.client?.scope ?: vm.restoredScope()
    val savedBots = if (liveBots.profiles.isEmpty() && liveBots.selected == null) savedBotsScope?.let { vm.screens.exact(it, "settings:bots")?.value }?.let { stored -> runCatching { parseBotSummaries(stored.copy()) }.getOrNull() } else null
    val state = if (!savedBots.isNullOrEmpty()) liveBots.copy(profiles = savedBots) else liveBots
    val models by vm.phoneSettings.botModels.collectAsStateWithLifecycle()
    val admin by vm.profileAdmin.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf("") }
    var describing by rememberSaveable { mutableStateOf(false) }
    var instructing by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun leave(action: () -> Unit) {
        if (!state.saving) {
            val changed = state.editing && (state.draftModel != state.selected?.model || state.draftProvider != state.selected?.provider)
            if (changed) discard = action else action()
        }
    }
    val back = { leave { if (state.editing) vm.phoneSettings.editBot(false) else if (state.selected != null) vm.closeSettingsProfile() else vm.backFromSettings() } }
    BackHandler(onBack = back)
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, vm.client) { vm.phoneSettings.bind(); vm.phoneSettings.loadBots(); if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    // "Profile settings" on a bot in Bot Mode lands here with that profile asked for: show it once the list has loaded.
    LaunchedEffect(admin.open, state.loading, state.profiles) {
        if (admin.open.isNotBlank() && !state.loading && state.profiles.any { it.profile == admin.open }) { val profile = admin.open; vm.profileAdmin.opened(); vm.inspectSettingsProfile(profile) }
    }
    // Older recoverable model drafts were stored under the chat profile even when they edited another profile.
    LaunchedEffect(state.selected?.profile, state.editing, target, selecting) {
        val selected = state.selected?.profile
        if (state.editing && selected != null && selected != target.ifBlank { ui.profile } && !selecting) vm.inspectSettingsProfile(selected)
    }
    // A profile that was open and is no longer in the list (deleted here or on the computer) closes back to the list.
    LaunchedEffect(state.profiles, state.loading) { state.selected?.profile?.let { open -> if (!state.loading && state.profiles.isNotEmpty() && state.profiles.none { it.profile == open }) vm.closeSettingsProfile() } }
    Column(Modifier.fillMaxSize()) {
        Header(uiText("Profiles"), back = back, menu = LocalOpenNavigationMenu.current)
        NativeBotSettings(if (selecting) state.copy(loading = true) else state, { vm.inspectSettingsProfile(it) }, { leave(vm::closeSettingsProfile) }, { vm.phoneSettings.editBot(true) },
            vm.phoneSettings::botDraft, vm.phoneSettings::saveBot, { leave { vm.phoneSettings.editBot(false) } },
            onSettings = { profile, page -> leave { if (state.editing) vm.phoneSettings.editBot(false); vm.openSettingsProfileTool(profile, page) } },
            onRefresh = { state.selected?.let { vm.phoneSettings.openBot(it.profile) } ?: vm.phoneSettings.loadBots() }, models = models,
            onEditDescription = { vm.profileAdmin.clearError(); describing = true }, onEditInstructions = { vm.profileAdmin.clearError(); instructing = true },
            profileLabel = { profile -> profileDisplayLabel(profile, profileRows, profileLoading) },
            onNewProfile = { creating = true }, canDelete = vm::canDeleteProfile, onDelete = { deleting = it },
            activeProfile = ui.profile, approvals = { ProfileApprovalSettings(vm) }, readOnly = vm.settingsProfileClient() == null)
    }
    state.selected?.let { bot ->
        if (describing) EditRoleDialog(bot.profile, bot.description, admin, onSave = { text -> vm.profileAdmin.setRole(bot.profile, text) { vm.phoneSettings.botEdited(bot.profile, description = it) } },
            onDismiss = { describing = false }, title = uiText("Edit description"), label = uiText("Description"))
        if (instructing) EditInstructionsDialog(bot.profile, bot.soul, admin, onSave = { text -> vm.profileAdmin.setInstructions(bot.profile, text) { vm.phoneSettings.botEdited(bot.profile, soul = it) } },
            onDismiss = { instructing = false })
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
