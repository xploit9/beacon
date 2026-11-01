@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PhoneCallback
import androidx.compose.material.icons.automirrored.rounded.Rule
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.isMissingBeaconPluginRoute
import dev.hermes.android.core.Scope
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import dev.hermes.android.core.recordedMessageTime
import dev.hermes.android.data.*
import dev.hermes.android.tasks.schedulePaused
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun voiceOptionsWithSelected(options: List<String>, selected: String): List<String> = (listOf(selected) + options).distinct()
internal fun voiceChoiceLabel(voice: String, options: List<String>): String {
    val label = voice.replaceFirstChar(Char::titlecase)
    return if (options.any { it != voice && it.replaceFirstChar(Char::titlecase) == label }) "$label ($voice)" else label
}
internal fun voiceModelDisplayName(model: String): String = model.substringAfterLast('/')
    .split(Regex("[-_]"))
    .filter(String::isNotBlank)
    .joinToString(" ") { part ->
        if (part.all(Char::isLetter) && part.length <= 3) part.uppercase()
        else part.replaceFirstChar(Char::uppercase)
    }
/** The plugin's capabilities sit at the top of the merged bootstrap (mergeOptionalFeatures), not under optional_features. */
internal fun callbacksAvailable(bootstrap: org.json.JSONObject): Boolean = bootstrap.optJSONObject("capabilities")?.optBoolean("callbacks") == true
internal const val PROACTIVE_SETTINGS_PATH = "/api/plugins/beacon/mobile/proactive"
internal fun proactiveSettingsKey(scope: Scope) = "proactive-settings:${scope.copy(session = "").key}"

/** The script the plugin installs for check-up calls (hermes-plugin/beacon/dashboard/plugin_api.py, _install_checkup_task). */
internal const val CHECKUP_SCRIPT = "beacon-checkups.py"
/** The check-up task in this profile's schedules, if the plugin installed one. */
internal fun checkupJob(crons: JSONObject?): JSONObject? = crons?.rows("jobs").orEmpty().firstOrNull { it.string("script").endsWith(CHECKUP_SCRIPT) }
/** Check-up calls are on while their task exists and is not paused. */
internal fun checkupsOn(crons: JSONObject?): Boolean = checkupJob(crons)?.let { !schedulePaused(it) } == true
/** A capabilities answer worth showing: one came back (fresh or saved), not only the first-load placeholder or a bare failure. */
internal fun checkupCapabilitiesKnown(record: JSONObject?): Boolean =
    record != null && (record.has("checkups_ready") || !record.optBoolean("_loading") && recordError(record).isBlank())

/** "File, Personal +3": the first two sources the watch can read, then how many more. */
internal fun proactiveSourcesSummary(sources: List<String>): String =
    sources.take(2).joinToString(", ") + if (sources.size > 2) " +${sources.size - 2}" else ""

internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

/** The watch switch: the whole row toggles; under its label, how the watch is doing and when it last looked. */
@Composable private fun WatchSwitchRow(checked: Boolean, enabled: Boolean, busy: Boolean, onChange: (Boolean) -> Unit, health: ProactiveHealthState?, lastCheck: String?) {
    val active = enabled && !busy
    Row(Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeightTwoLine).alpha(if (enabled) 1f else .38f)
        .toggleable(checked, enabled = active, role = Role.Switch, onValueChange = onChange).testTag("assistant-proactive-toggle")
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(Icons.Rounded.NotificationsActive)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(uiText("Watch for me"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (health != null || lastCheck != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                health?.let { ProactiveHealthChip(it) }
                lastCheck?.let { Text(uiText("Checked {0}").replace("{0}", it), Modifier.testTag("proactive-last-check"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Switch(checked, onCheckedChange = null, enabled = active)
    }
}

/** One rule: what the owner wrote with its own switch, then how it reaches them (Notify or Call), its source, night and delete. */
@Composable private fun ProactiveRuleBlock(rule: JSONObject, enabled: Boolean, onUpdate: (JSONObject) -> Unit, onDelete: () -> Unit) {
    val id = rule.string("id")
    val text = rule.string("text")
    val on = rule.optBoolean("enabled")
    val night = rule.optBoolean("night")
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("proactive-rule-$id").padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, Modifier.weight(1f).testTag("proactive-rule-text-$id"), style = MaterialTheme.typography.bodyLarge,
                color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Switch(on, { onUpdate(json("enabled" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-enabled-$id").semantics { contentDescription = text })
        }
        SegmentedChoice("When it matches", rule.string("action", "notify"), listOf("notify", "call"), listOf(uiText("Notify"), uiText("Call")), { onUpdate(json("action" to it)) },
            enabled = enabled, icons = mapOf("notify" to Icons.Rounded.Notifications, "call" to Icons.Rounded.Call))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsBadge((rule.opt("signal") as? String)?.takeIf(String::isNotBlank) ?: uiText("Anything"), Modifier.weight(1f, fill = false), testTag = "proactive-rule-source-$id")
                FilterChip(night, { onUpdate(json("night" to !night)) }, label = { Text(uiText("At night"), maxLines = 1) }, enabled = enabled,
                    leadingIcon = { Icon(Icons.Rounded.Bedtime, null, Modifier.size(FilterChipDefaults.IconSize)) }, modifier = Modifier.testTag("proactive-rule-night-$id"))
            }
            SettingsIconButton(Icons.Rounded.DeleteOutline, uiText("Delete"), onDelete, Modifier.testTag("proactive-rule-delete-$id"), enabled = enabled, tint = scheme.error)
        }
    }
}

/** The assistant's name (on this phone), the profile its chat always uses, what it watches for, and its check-up calls. */
@Composable internal fun AssistantSettings(vm: BeaconViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var name by remember(prefs.assistantName) { mutableStateOf(prefs.assistantName) }
    // The draft keeps a space being typed between names; the saved name is cleaned on save.
    var ownerName by remember(prefs.ownerName) { mutableStateOf(prefs.ownerName) }
    val profile = prefs.assistantProfile
    val proactiveKey = proactiveSettingsKey((vm.pcScope() ?: Scope(ui.origin, ui.owner, profile)).copy(profile = profile, session = ""))
    val proactive = data[proactiveKey]
    val proactiveLoaded = proactive != null && proactive.has("enabled") && !proactive.has("_error")
    val proactiveEnabled = proactiveLoaded && proactive?.optBoolean("enabled") == true
    val pluginStatus = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin").orEmpty()
    val callbacksAvailable = callbacksAvailable(ui.bootstrap)
    val scope = rememberCoroutineScope()
    var savingProactive by remember(profile) { mutableStateOf(false) }
    var proactiveFetching by remember(profile) { mutableStateOf(false) }
    var proactiveRefresh by remember(profile) { mutableIntStateOf(0) }
    val proactiveVersion = proactive?.toString()
    var proactiveDelivery by remember(proactiveVersion) { mutableStateOf(proactive?.string("delivery").orEmpty().ifBlank { "notify" }) }
    var proactiveCheckMinutes by remember(proactiveVersion) { mutableIntStateOf(proactive?.optInt("check_minutes", 15) ?: 15) }
    var proactiveQuietStart by remember(proactiveVersion) { mutableStateOf(proactive?.string("quiet_start").orEmpty().ifBlank { "22:00" }) }
    var proactiveQuietEnd by remember(proactiveVersion) { mutableStateOf(proactive?.string("quiet_end").orEmpty().ifBlank { "07:00" }) }
    var ruleToDelete by remember(profile) { mutableStateOf<Pair<String, String>?>(null) }
    var noteToRemove by remember(profile) { mutableStateOf<String?>(null) }
    // Words the callbacks below show are translated here, where uiText can run.
    val loadFailed = uiText("Couldn't load Proactive settings. Try again.")
    val updateFailed = uiText("Couldn't update Proactive settings. Try again.")
    val ruleFailed = uiText("Couldn't update the rule. Try again.")
    val noteFailed = uiText("Couldn't remove this preference. Try again.")
    val ruleExplanation = assistantText("Tell Beacon what needs your attention.")
    fun reportProactiveError(error: Throwable, fallback: String) {
        if (isMissingBeaconPluginRoute(error)) vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true))
        else vm.notice(proactiveErrorMessage(error, fallback))
    }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, profile, ui.connected, pluginStatus, vm.client, proactiveRefresh) {
        if (!ui.connected || pluginStatus == "absent") return@LaunchedEffect
        val source = vm.client ?: return@LaunchedEffect
        // Saved settings stay on screen while they refresh; the placeholder is only for a first load.
        if (!proactiveLoaded) vm.storeData(proactiveKey, JSONObject().put("_loading", true))
        proactiveFetching = true
        try {
            val response = fetchProactiveSettings(source, profile)
            if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.keepScreen(source.scope, proactiveKey, response)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.storeData(proactiveKey, JSONObject().put("_error", true)
                    .put("_pluginUpdateRequired", isMissingBeaconPluginRoute(error))
                    .put("_message", proactiveErrorMessage(error, loadFailed)))
        } finally { proactiveFetching = false }
    }
    fun setProactive(enabled: Boolean) {
        if (savingProactive) return
        val source = vm.client
        if (source == null || !ui.connected || pluginStatus == "absent") return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) {
            vm.notice(updateFailed)
            return
        }
        savingProactive = true
        scope.launch {
            try {
                val response = client.call(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile), method = "PUT", data = json("enabled" to enabled))
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                    vm.storeData(proactiveKey, response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, updateFailed)
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    /** A changed choice is saved at once; if the save fails the controls go back to what Hermes has. */
    fun saveProactiveChoices(delivery: String = proactiveDelivery, checkMinutes: Int = proactiveCheckMinutes, quietStart: String = proactiveQuietStart, quietEnd: String = proactiveQuietEnd) {
        if (savingProactive || !proactiveLoaded) return
        if (delivery == proactiveDelivery && checkMinutes == proactiveCheckMinutes && quietStart == proactiveQuietStart && quietEnd == proactiveQuietEnd) return
        val wasDelivery = proactiveDelivery
        val wasCheckMinutes = proactiveCheckMinutes
        val wasQuietStart = proactiveQuietStart
        val wasQuietEnd = proactiveQuietEnd
        proactiveDelivery = delivery; proactiveCheckMinutes = checkMinutes; proactiveQuietStart = quietStart; proactiveQuietEnd = quietEnd
        savingProactive = true
        scope.launch {
            try {
                val body = proactiveSettingsBody(proactiveEnabled, delivery, checkMinutes, quietStart, quietEnd, prefs)
                val response = saveProactiveSettings(vm, profile, body)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                    vm.storeData(proactiveKey, response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                proactiveDelivery = wasDelivery; proactiveCheckMinutes = wasCheckMinutes; proactiveQuietStart = wasQuietStart; proactiveQuietEnd = wasQuietEnd
                reportProactiveError(error, updateFailed)
            } finally { savingProactive = false }
        }
    }
    fun updateProactiveRule(id: String, method: String, body: JSONObject? = null) {
        if (savingProactive) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) { vm.notice(ruleFailed); return }
        savingProactive = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/requests/${Uri.encode(id)}", mapOf("profile" to profile), method = method, data = body)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, ruleFailed)
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    fun addProactiveRule() {
        openProactiveRuleForm(vm, profile, ruleExplanation,
            onCreated = { if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++ },
            onMissingPlugin = { vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true)) },
            sources = proactive?.let(::proactiveSources).orEmpty(),
        )
    }
    fun removeLearnedNote(note: String) {
        if (savingProactive) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) { vm.notice(noteFailed); return }
        savingProactive = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/learned", mapOf("profile" to profile), method = "DELETE", data = json("note" to note))
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, noteFailed)
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    LaunchedEffect(Unit) { vm.load("navigation-profiles", "/api/profiles", force = false); vm.load("picker:models", "/api/models", force = false) }
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val loadingProfile = uiText("Loading profile…")
    val profileLabel = if (profileLoading) loadingProfile else profileDisplayName(profile, profileRows)
    val profiles = profileRows.map { it.string("name") }.filter(String::isNotBlank)
    // "provider\tmodel" keys; blank = the profile's default. The saved pick stays listed even before the list loads.
    val models = data["picker:models"]?.rows("groups").orEmpty().flatMap { group -> group.rows("models").map { group.string("provider_id") + "\t" + it.string("id") to group.string("provider") + " · " + it.string("label", it.string("id")) } }.toMap()
    val selectedModel = if (prefs.assistantModel.isBlank()) "" else prefs.assistantProvider + "\t" + prefs.assistantModel
    // Check-ups stay bound to the active client, independently of the assistant chat profile.
    val activeScope = vm.client?.scope?.takeIf { it.origin == ui.origin && it.owner == ui.owner && it.profile == ui.profile }
    val checkupKey = activeScope?.let { "checkup-capabilities:${it.key}" }
    val checkupCapabilities = checkupKey?.let { data[it] }
    val checkupTask = checkupJob(data["crons"])
    val checkupsActive = checkupsOn(data["crons"])
    fun refreshCheckups() {
        if (checkupKey != null) {
            // A saved answer stays on screen while it refreshes; only nothing at all, or a failure, shows the placeholder.
            if (!checkupCapabilitiesKnown(checkupCapabilities)) vm.storeData(checkupKey, JSONObject().put("_loading", true))
            vm.load(checkupKey, "/api/plugins/beacon/mobile/tasks/capabilities", quiet = true)
        }
    }
    // Check-ups are the plugin's; the schedules list says whether their task is there.
    LaunchedEffect(checkupKey, ui.connected, vm.client, pluginStatus) { if (ui.connected && pluginStatus != "absent") { refreshCheckups(); vm.ensureLoaded("crons", "/api/crons") } }
    val activeProfileLabel = if (profileLoading) loadingProfile else profileDisplayName(ui.profile, profileRows)
    val checkupTitle = assistantText("Check-up calls")
    val checkupExplanation = assistantText("Beacon calls 3–4 times a day, 10:00–22:00. Do Not Disturb applies.")
    val checkupAction = uiText("Turn on")
    val checkupOffTitle = uiText("Turn off check-up calls?")
    val checkupOffExplanation = assistantText("Beacon stops these calls.")
    val checkupOffAction = uiText("Turn off")
    /** On runs the plugin's own install (or resumes a paused task); off deletes the task. Both ask first. */
    fun setCheckups(on: Boolean) {
        val after: suspend (JSONObject) -> Unit = { refreshCheckups(); refreshSchedules(vm) }
        val paused = checkupTask?.takeIf(::schedulePaused)
        when {
            on && paused != null -> vm.openForm(ActionForm(checkupTitle, "/api/crons/resume", emptyList(), json("job_id" to paused.string("id")), explanation = checkupExplanation, actionLabel = checkupAction, onResult = after))
            on -> vm.openForm(ActionForm(checkupTitle, "/api/plugins/beacon/mobile/tasks/checkups", emptyList(), explanation = checkupExplanation, actionLabel = checkupAction, onResult = after))
            checkupTask != null -> vm.openForm(ActionForm(checkupOffTitle, "/api/crons/delete", emptyList(), json("job_id" to checkupTask.string("id")), explanation = checkupOffExplanation, actionLabel = checkupOffAction, onResult = after, destructive = true))
        }
    }
    val connected = ui.connected && vm.client != null
    val controlsEnabled = connected && !savingProactive
    val pluginMissing = pluginStatus == "absent" || proactive?.optBoolean("_pluginUpdateRequired") == true
    val checkupRefreshing = checkupKey != null && checkupKey in refreshing && checkupCapabilitiesKnown(checkupCapabilities)
    val phoneAssistant = dev.hermes.android.assist.rememberPhoneAssistantState()
    SettingsList(refreshing = (proactiveFetching && proactiveLoaded) || checkupRefreshing, testTag = "assistant-settings") {
        item {
            SettingsSection(footer = assistantText("Only for Beacon's chat")) {
                SettingsTextFieldRow(uiText("Name"), name, { name = it.take(ASSISTANT_NAME_MAX) }, icon = Icons.Rounded.Face,
                    saveLabel = uiText("Save"), onSave = { vm.setAssistantName(name) }, dirty = assistantNameOf(name) != prefs.assistantName,
                    fieldTestTag = "assistant-name", saveTestTag = "assistant-name-save")
                SettingsDivider()
                SettingsTextFieldRow(assistantText("What Beacon calls you"), ownerName, { ownerName = ownerNameDraft(it) }, icon = Icons.Rounded.Badge,
                    placeholder = uiText("Optional"), saveLabel = uiText("Save"), onSave = { vm.setOwnerName(ownerName) }, dirty = ownerNameOf(ownerName) != prefs.ownerName,
                    fieldTestTag = "owner-name", saveTestTag = "owner-name-save")
                SettingsDivider()
                val profileOptions = voiceOptionsWithSelected(profiles, prefs.assistantProfile)
                SettingsChoiceRow(uiText("Profile"), prefs.assistantProfile, profileOptions, { vm.setAssistantProfile(it) }, icon = Icons.Rounded.SmartToy, style = ChoiceStyle.Menu,
                    anchorTestTag = "assistant-profile",
                    optionLabels = profileOptions.associateWith { id -> if (profileLoading && id == prefs.assistantProfile) loadingProfile else profileDisplayName(id, profileRows) })
                SettingsDivider()
                SettingsChoiceRow(uiText("Model"), selectedModel, voiceOptionsWithSelected(listOf("") + models.keys, selectedModel),
                    { key -> vm.setAssistantModel(key.substringAfter('\t', ""), if (key.isBlank()) "" else key.substringBefore('\t')) },
                    icon = Icons.Rounded.Memory, style = ChoiceStyle.Menu, anchorTestTag = "assistant-model",
                    optionLabels = mapOf("" to "Profile default") + models + (selectedModel to (models[selectedModel] ?: prefs.assistantModel)).takeIf { selectedModel.isNotBlank() }.let { listOfNotNull(it).toMap() })
            }
        }
        if (phoneAssistant != dev.hermes.android.assist.PhoneAssistantState.Unavailable) item {
            val context = LocalContext.current
            dev.hermes.android.assist.PhoneAssistantSection(phoneAssistant) { if (!dev.hermes.android.assist.openAssistantSettings(context)) vm.notice("Open Beacon’s app settings in Android") }
        }
        item {
            SettingsSection(uiText("What needs you"), trailing = { SettingsBadge(profileLabel, Modifier.padding(end = 4.dp)) }) {
                if (pluginMissing) MissingBeaconPluginState(compact = true, update = pluginStatus != "absent")
                else {
                    val checking = proactive?.optBoolean("_loading") == true || connected && proactive == null
                    if (!connected) SettingsErrorRow(uiText("Connect to manage alerts"), { vm.connect(ui.origin) }, uiText("Reconnect"))
                    else if (proactive?.has("_error") == true) SettingsErrorRow(uiText(proactive.string("_message", "Couldn't load Proactive settings. Try again.")),
                        { proactiveRefresh++ }, uiText("Retry"), Modifier.testTag("proactive-load-error"))
                    WatchSwitchRow(proactiveEnabled, enabled = proactiveLoaded && connected, busy = savingProactive, onChange = ::setProactive,
                        health = when { checking && !proactiveLoaded -> ProactiveHealthState.Checking; proactiveEnabled -> proactiveHealthState(proactive, false); else -> null },
                        lastCheck = if (proactiveEnabled) proactiveLastCheck(proactive) else null)
                    if (proactiveLoaded && proactiveEnabled) {
                        SettingsDivider()
                        SettingsChoiceRow(uiText("Delivery"), proactiveDelivery, listOf("notify", "call"), { saveProactiveChoices(delivery = it) }, icon = Icons.Rounded.Notifications,
                            optionLabels = mapOf("notify" to "Notify", "call" to "Call"), optionIcons = mapOf("notify" to Icons.Rounded.Notifications, "call" to Icons.Rounded.Call),
                            enabled = controlsEnabled, anchorTestTag = "proactive-delivery")
                        if (proactiveCallsUnavailable(proactive, proactiveDelivery, callbacksAvailable))
                            SettingsStatusPill(SettingsStatus.Attention, uiText("Calls off on this Hermes"), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), testTag = "proactive-calls-off")
                        SettingsDivider()
                        SettingsChoiceRow(uiText("Check every"), proactiveCheckMinutes.toString(), listOf("5", "15", "30"), { saveProactiveChoices(checkMinutes = it.toInt()) }, icon = Icons.Rounded.Timer,
                            optionLabels = mapOf("5" to "5 min", "15" to "15 min", "30" to "30 min"), enabled = controlsEnabled, anchorTestTag = "proactive-check-minutes")
                        SettingsDivider()
                        ProactiveTimePicker(uiText("Quiet from"), proactiveQuietStart, controlsEnabled, Icons.Rounded.Bedtime, tagLabel = "Quiet hours start") { saveProactiveChoices(quietStart = it) }
                        SettingsDivider()
                        ProactiveTimePicker(uiText("Quiet until"), proactiveQuietEnd, controlsEnabled, Icons.Rounded.WbSunny, tagLabel = "Quiet hours end") { saveProactiveChoices(quietEnd = it) }
                    }
                    if (proactiveLoaded) {
                        val sources = proactiveSources(proactive)
                        SettingsDivider()
                        SettingsValueRow(uiText("Sources"), icon = Icons.Rounded.Sensors, value = if (sources.isEmpty()) uiText("Not yet") else proactiveSourcesSummary(sources), testTag = "proactive-sources")
                    }
                }
            }
        }
        if (proactiveLoaded && proactiveEnabled) item {
            val rules = proactive.rows("rules")
            SettingsSection(uiText("Your rules")) {
                if (rules.isEmpty()) SettingsEmpty(uiText("No rules yet"), Icons.AutoMirrored.Rounded.Rule)
                rules.forEachIndexed { index, rule ->
                    if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                    val id = rule.string("id")
                    ProactiveRuleBlock(rule, controlsEnabled, onUpdate = { updateProactiveRule(id, "PATCH", it) }, onDelete = { ruleToDelete = id to rule.string("text") })
                }
                SettingsDivider()
                SettingsActionRow(uiText("Add a rule"), ::addProactiveRule, icon = Icons.Rounded.Add, value = if (rules.size >= 20) uiText("Delete one to add another") else "",
                    enabled = controlsEnabled && rules.size < 20, testTag = "proactive-add-rule")
            }
        }
        if (proactiveLoaded && proactiveEnabled) item {
            val learned = proactiveLearnedNotes(proactive)
            SettingsSection(assistantText("What Beacon learned")) {
                if (learned.isEmpty()) SettingsEmpty(assistantText("Rate alerts to teach Beacon"), Icons.Rounded.Lightbulb, Modifier.testTag("proactive-learned-empty"))
                learned.forEachIndexed { index, note ->
                    if (index > 0) SettingsDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeight).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SettingsIconTile(Icons.Rounded.Lightbulb)
                        Text(note, Modifier.weight(1f).testTag("proactive-learned-$index"), style = MaterialTheme.typography.bodyLarge)
                        SettingsIconButton(Icons.Rounded.Close, uiText("Remove note"), { noteToRemove = note }, Modifier.testTag("proactive-learned-remove-$index"), enabled = controlsEnabled)
                    }
                }
            }
        }
        // Without the plugin there is nothing to schedule; the section above already offers the install.
        if (pluginStatus != "absent") item {
            SettingsSection(checkupTitle, trailing = { SettingsBadge(activeProfileLabel, Modifier.padding(end = 4.dp)) }) {
                val ready = checkupCapabilities?.optBoolean("checkups_ready") == true
                val known = checkupCapabilitiesKnown(checkupCapabilities)
                val failed = !ui.connected || activeScope == null || (recordError(checkupCapabilities).isNotBlank() && !(checkupKey in refreshing))
                if (failed) SettingsErrorRow(uiText("Couldn't load check-up calls"), if (ui.connected && activeScope != null) ({ refreshCheckups() }) else null, uiText("Retry"), retryTestTag = "checkups-retry")
                if (!known && !failed) SettingsSkeletonRows(1)
                else if (known) SettingsSwitchRow(assistantText("Beacon calls you"), checkupsActive, ::setCheckups, icon = Icons.AutoMirrored.Rounded.PhoneCallback,
                    summary = uiText("3–4 a day, 10:00–22:00"),
                    status = if (!ready && !checkupsActive) SettingsStatus.Attention else null, statusLabel = uiText("Set up on your computer"),
                    enabled = connected && (ready || checkupsActive), testTag = "assistant-checkups-toggle")
            }
        }
    }
    ruleToDelete?.let { (id, text) ->
        SettingsConfirmDialog(uiText("Delete this rule?"), text, uiText("Delete"), { ruleToDelete = null; updateProactiveRule(id, "DELETE") }, { ruleToDelete = null },
            confirmTestTag = "proactive-rule-delete-confirm")
    }
    noteToRemove?.let { note ->
        SettingsConfirmDialog(uiText("Remove this note?"), note, uiText("Remove"), { noteToRemove = null; removeLearnedNote(note) }, { noteToRemove = null },
            confirmTestTag = "proactive-learned-remove-confirm")
    }
}

/** What the owner-name field keeps while typing: name letters, spaces, hyphens and apostrophes, untrimmed so a space can be typed. */
internal fun ownerNameDraft(raw: String): String = raw.filter { it.isLetter() || it in " -'" }.take(40)

/** Settings > Voice & calls: the same three pages the wizard shows (Provider, Key, Make it yours), without the wizard's shell. */
@Composable internal fun BeaconSettings(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VoiceKeyStore(context) }
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val page by vm.voicePage.collectAsStateWithLifecycle()
    var keyProvider by rememberSaveable { mutableStateOf("") }
    var keyWorks by remember { mutableStateOf(false) }
    // A call, an incoming call or a screen share that is waiting for a key opens straight on the key page.
    LaunchedEffect(Unit) {
        val requested = vm.pendingVoiceKeyProvider() ?: return@LaunchedEffect
        val provider = requested.ifBlank { store.settings().provider }
        if (!withContext(Dispatchers.IO) { store.hasKey(provider) }) { keyProvider = provider; vm.setVoicePage("key") }
    }
    DisposableEffect(Unit) { onDispose { vm.setVoicePage("home") } }
    BackHandler(page != "home") { vm.setVoicePage("home") }
    val keyFor = keyProvider.ifBlank { store.settings().provider }
    val voicePage = when (page) {
        "provider" -> voiceProviderPage(
            onContinue = { provider, hasKey -> if (hasKey) vm.setVoicePage("home") else { keyProvider = provider; vm.setVoicePage("key") } },
            onBack = { vm.setVoicePage("home") },
        )
        "key" -> voiceKeyPage(
            provider = keyFor, onKeySaved = vm::voiceKeyConfigured,
            onVerified = { keyWorks = true; vm.setVoicePage("home") }, onContinueAnyway = { vm.setVoicePage("home") },
            onBack = { vm.setVoicePage("home") }, removable = true, onRemoved = { vm.setVoicePage("home") },
        )
        else -> voiceChoicesPage(
            settingsMode = true, keyWorks = keyWorks, callActive = call.active,
            onChange = { vm.setVoicePage("provider") }, onAddKey = { keyProvider = store.settings().provider; vm.setVoicePage("key") },
        )
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = SettingsKit.PagePadding, end = SettingsKit.PagePadding, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap)) {
        when (page) {
            "provider" -> Text(uiText("Choose a voice provider"), Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            "key" -> Text(uiText("Add your {0} key").replace("{0}", voiceProviderInfo(keyFor).short), Modifier.padding(start = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            else -> VoiceSettingsPluginNote(pluginAbsent)
        }
        voicePage.content(this)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) { voicePage.actions(this) }
    }
}

/** Settings > Connection > Devices: this phone and every other phone linked to the account, each with its own device id. */
@Composable
internal fun DevicesSettingsSection(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val source = vm.client
    val scopeKey = source?.scope?.key.orEmpty()
    var devices by remember(scopeKey) { mutableStateOf(pairedDevicesOf(vm.data.value[PAIRED_DEVICES_KEY])) }
    var loading by remember(scopeKey) { mutableStateOf(false) }
    var busy by remember(scopeKey) { mutableStateOf(false) }
    var error by remember(scopeKey) { mutableStateOf("") }
    var removed by remember(scopeKey) { mutableStateOf(false) }
    var refresh by remember(scopeKey) { mutableIntStateOf(0) }
    var pendingRemoval by remember(scopeKey) { mutableStateOf<PairedDevice?>(null) }
    val requests = rememberCoroutineScope()
    // Words the callbacks below show are translated here, where uiText can run.
    val tooManyPhones = uiText("Too many phones. Remove one below.")
    val couldntAddPhone = uiText("Couldn't add this phone. Try again.")
    val finishSetup = uiText("Finish setting up Beacon on your Hermes dashboard, then retry.")
    // The devices route is missing: a Beacon plugin older than this app.
    val pluginMissing = uiText("Update Beacon on your Hermes")
    val couldntLoad = uiText("Couldn't load devices. Check your connection and try again.")
    val couldntRemove = uiText("Couldn't remove this device. Try again.")

    fun linkFailure(failure: Throwable) = if ((failure as? ApiFailure)?.status == 409) tooManyPhones else couldntAddPhone

    LaunchedEffect(scopeKey, refresh, pluginAbsent) {
        val client = vm.client ?: return@LaunchedEffect
        if (pluginAbsent || client.auth.owner.isBlank()) return@LaunchedEffect
        loading = true
        error = ""
        try {
            // Signing in links this phone already; this covers a link that has not run or failed yet.
            if (NativePairing.deviceId(context, client.scope) == null) {
                try { NativePairing.link(context, client, explicit = false) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { if (vm.client === client) error = linkFailure(failure) }
            }
            val result = NativePairing.devices(client)
            if (vm.client === client) {
                val thisDevice = NativePairing.deviceId(context, client.scope)
                if (thisDevice != null && result.none { it.id == thisDevice }) {
                    NativePairing.clearDeviceId(context, client.scope)
                    vm.forgetScreens()
                    removed = true
                } else { devices = result; vm.keepScreen(client.scope, PAIRED_DEVICES_KEY, pairedDevicesJson(result)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeviceRemovedFailure) {
            if (vm.client === client) {
                NativePairing.clearDeviceId(context, client.scope)
                vm.forgetScreens()
                removed = true
            }
        } catch (failure: ApiFailure) {
            if (vm.client === client) error = when {
                failure.status == 403 -> finishSetup
                isMissingBeaconPluginRoute(failure) -> pluginMissing
                else -> couldntLoad
            }
        } catch (_: Exception) {
            if (vm.client === client) error = couldntLoad
        } finally {
            if (vm.client === client) loading = false
        }
    }

    fun addThisPhone() {
        val active = vm.client ?: return
        busy = true
        requests.launch {
            try {
                NativePairing.link(context, active, explicit = true)
                if (vm.client === active) { removed = false; refresh++ }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (vm.client === active) error = linkFailure(failure)
            } finally {
                if (vm.client === active) busy = false
            }
        }
    }

    val thisDeviceId = source?.let { NativePairing.deviceId(context, it.scope) }
    val title = uiText("Devices")
    val refreshLabel = uiText("Refresh devices")
    // Refresh sits beside the heading; a spinner next to it says a read or a change is running.
    val refreshAction: @Composable RowScope.() -> Unit = {
        if (loading || busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        SettingsIconButton(Icons.Rounded.Refresh, refreshLabel, { refresh++ }, Modifier.testTag("settings-devices-refresh"), enabled = !loading && !busy)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap)) {
        when {
            pluginAbsent -> SettingsSection(title) { MissingBeaconPluginState(compact = true) }
            source == null || !ui.authenticated -> SettingsSection(title) {
                SettingsErrorRow(uiText("Reconnect to see devices"), { vm.connect(ui.origin) }, uiText("Reconnect"))
            }
            removed -> SettingsSection(title) {
                SettingsValueRow(uiText("This phone was removed"), icon = Icons.Rounded.PhoneAndroid, value = uiText("Add it to get calls and alerts"), testTag = "devices-removed")
                if (error.isNotBlank()) SettingsErrorRow(error, null, "")
                SettingsDivider()
                SettingsActionRow(uiText("Add this phone"), ::addThisPhone, icon = Icons.Rounded.Add, enabled = !busy, busy = busy, testTag = "devices-link-this-phone")
            }
            loading && devices == null -> SettingsSection(title, trailing = refreshAction) { SettingsSkeletonRows(2) }
            error.isNotBlank() && devices == null -> SettingsSection(title, trailing = refreshAction) {
                SettingsErrorRow(error, { refresh++ }, uiText("Retry"), retryTestTag = "settings-devices-retry")
            }
            else -> DevicesList(devices.orEmpty(), thisDeviceId, enabled = !busy, trailing = refreshAction, error = error,
                onAddThisPhone = ::addThisPhone) { pendingRemoval = it }
        }
    }

    pendingRemoval?.let { device ->
        SettingsConfirmDialog(
            uiText("Remove {0}?").replace("{0}", device.name), uiText("It stops getting calls and alerts from Beacon."), uiText("Remove"),
            onConfirm = {
                val active = vm.client
                if (active != null) {
                    pendingRemoval = null
                    busy = true
                    requests.launch {
                        try {
                            NativePairing.remove(active, device.id)
                            if (vm.client === active) refresh++
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            if (vm.client === active) error = couldntRemove
                        } finally {
                            if (vm.client === active) busy = false
                        }
                    }
                }
            },
            onDismiss = { if (!busy) pendingRemoval = null },
            confirmTestTag = "settings-device-remove-confirm",
        )
    }
}

/** Other phones shown before "Show all": every one up to four, else the first three. */
internal fun <T> devicesShown(others: List<T>, showAll: Boolean): List<T> = if (showAll || others.size <= 4) others else others.take(3)

/**
 * This phone first (no Remove: Sign out does that), then each other phone with when it was last seen and Remove. Without this phone
 * in the list, [onAddThisPhone] offers to link it; [error] is the last failure, shown above the rows.
 */
@Composable
internal fun DevicesList(
    devices: List<PairedDevice>,
    thisDeviceId: String?,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    error: String = "",
    onAddThisPhone: (() -> Unit)? = null,
    onRemove: (PairedDevice) -> Unit,
) {
    val thisPhone = devices.firstOrNull { it.id == thisDeviceId }
    val others = devices.filterNot { it.id == thisDeviceId }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = devicesShown(others, showAll)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap)) {
        if (thisPhone != null || onAddThisPhone != null || error.isNotBlank()) SettingsSection(uiText("Devices"), trailing = trailing,
            footer = if (thisPhone != null && others.isEmpty()) uiText("No other phones") else "") {
            if (error.isNotBlank()) SettingsErrorRow(error, null, "")
            if (thisPhone != null) DeviceSettingsRow(thisPhone, current = true, enabled = enabled, onRemove = null)
            else if (onAddThisPhone != null) SettingsActionRow(uiText("Add this phone"), onAddThisPhone, icon = Icons.Rounded.Add, value = uiText("Not linked"),
                enabled = enabled, testTag = "devices-link-this-phone")
        }
        if (others.isNotEmpty()) SettingsSection(uiText("Other phones")) {
            shown.forEachIndexed { index, device ->
                if (index > 0) SettingsDivider()
                DeviceSettingsRow(device, current = false, enabled = enabled) { onRemove(device) }
            }
            if (shown.size < others.size) TextButton(onClick = { showAll = true }, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsKit.TouchMin).testTag("devices-show-all")) {
                Text(uiText("Show all ({0})").replace("{0}", "${others.size}"))
            }
        }
    }
}

@Composable
private fun DeviceSettingsRow(device: PairedDevice, current: Boolean, enabled: Boolean, onRemove: (() -> Unit)?) {
    if (current) {
        SettingsValueRow(device.name, icon = Icons.Rounded.PhoneAndroid, value = uiText("This phone"), status = SettingsStatus.Ok, statusLabel = uiText("Linked"),
            testTag = "settings-device-${device.id}")
        return
    }
    val lastSeen = recordedMessageTime(device.lastSeen).takeIf { it > 0 }
    val now = System.currentTimeMillis()
    val seen = lastSeen?.let { uiText("Seen {0}").replace("{0}", DateUtils.getRelativeTimeSpanString(minOf(it, now), now, DateUtils.MINUTE_IN_MILLIS).toString()) }
        ?: uiText("Last seen unavailable")
    Row(Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeightTwoLine).testTag("settings-device-${device.id}").padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(Icons.Rounded.Smartphone)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(seen, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        onRemove?.let {
            SettingsIconButton(Icons.Rounded.RemoveCircleOutline, uiText("Remove {0}").replace("{0}", device.name), it, Modifier.testTag("settings-device-remove-${device.id}"),
                enabled = enabled, tint = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun VoiceSettingsPluginNote(pluginAbsent: Boolean) {
    if (pluginAbsent) SettingsSection { MissingBeaconPluginState(compact = true) }
}
