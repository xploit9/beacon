package dev.hermes.android.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

internal fun voiceOptionsWithSelected(options: List<String>, selected: String): List<String> = (listOf(selected) + options).distinct()
internal fun voiceChoiceLabel(voice: String, options: List<String>): String {
    val label = voice.replaceFirstChar(Char::titlecase)
    return if (options.any { it != voice && it.replaceFirstChar(Char::titlecase) == label }) "$label ($voice)" else label
}
internal fun voiceProviderTestMessage(result: dev.hermes.android.voice.VoiceProviderTestResult): String = when (result) {
    dev.hermes.android.voice.VoiceProviderTestResult.Success -> "Connection succeeded. The key is stored on this phone."
    dev.hermes.android.voice.VoiceProviderTestResult.KeyRejected -> "That key wasn't accepted. Check it and try again."
    dev.hermes.android.voice.VoiceProviderTestResult.Unreachable -> "The provider could not be reached. Check your connection and try again."
    dev.hermes.android.voice.VoiceProviderTestResult.ProviderError -> "The provider responded with an error. Check the selected model or try again."
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

internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

@Composable private fun AssistantProfileChoice(selected: String, options: List<String>, profiles: List<JSONObject>, loading: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember(selected) { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(uiText("Profile") + ": " + if (loading) uiText("Loading profile…") else profileDisplayName(selected, profiles), Modifier.weight(1f))
        }
        DropdownMenu(expanded, { expanded = false }) {
            voiceOptionsWithSelected(options, selected).forEach { id ->
                val label = if (loading && id == selected) uiText("Loading profile…") else profileDisplayName(id, profiles)
                DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(id) }, modifier = Modifier.testTag("choice-option:Profile:$id"))
            }
        }
    }
}

/** The assistant's name (on this phone) and the profile its chat always uses. */
@Composable internal fun AssistantSettings(vm: BeaconViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var name by remember(prefs.assistantName) { mutableStateOf(prefs.assistantName) }
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
    var proactiveRefresh by remember(profile) { mutableIntStateOf(0) }
    val proactiveVersion = proactive?.toString()
    var proactiveDelivery by remember(proactiveVersion) { mutableStateOf(proactive?.string("delivery").orEmpty().ifBlank { "notify" }) }
    var proactiveCheckMinutes by remember(proactiveVersion) { mutableIntStateOf(proactive?.optInt("check_minutes", 15) ?: 15) }
    var proactiveQuietStart by remember(proactiveVersion) { mutableStateOf(proactive?.string("quiet_start").orEmpty().ifBlank { "22:00" }) }
    var proactiveQuietEnd by remember(proactiveVersion) { mutableStateOf(proactive?.string("quiet_end").orEmpty().ifBlank { "07:00" }) }
    fun reportProactiveError(error: Throwable, fallback: String) {
        if (isMissingBeaconPluginRoute(error)) vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true))
        else vm.notice(proactiveErrorMessage(error, fallback))
    }
    val proactiveChoicesChanged = proactive != null && (
        proactiveDelivery != proactive.string("delivery", "notify") ||
            proactiveCheckMinutes != proactive.optInt("check_minutes", 15) ||
            proactiveQuietStart != proactive.string("quiet_start", "22:00") ||
            proactiveQuietEnd != proactive.string("quiet_end", "07:00")
        )
    LaunchedEffect(ui.origin, ui.owner, ui.profile, profile, ui.connected, pluginStatus, vm.client, proactiveRefresh) {
        if (!ui.connected || pluginStatus == "absent") return@LaunchedEffect
        val source = vm.client ?: return@LaunchedEffect
        if (!proactiveLoaded) vm.storeData(proactiveKey, JSONObject().put("_loading", true))
        try {
            val response = fetchProactiveSettings(source, profile)
            if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.keepScreen(source.scope, proactiveKey, response)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.storeData(proactiveKey, JSONObject().put("_error", true)
                    .put("_pluginUpdateRequired", isMissingBeaconPluginRoute(error))
                    .put("_message", proactiveErrorMessage(error, "Couldn't load Proactive settings. Try again.")))
        }
    }
    fun setProactive(enabled: Boolean) {
        if (savingProactive) return
        val source = vm.client
        if (source == null || !ui.connected || pluginStatus == "absent") return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) {
            vm.notice("Couldn't update Proactive settings. Try again.")
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
                reportProactiveError(error, "Couldn't update Proactive settings. Try again.")
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    fun saveProactiveChoices() {
        if (savingProactive || !proactiveLoaded || !proactiveChoicesChanged) return
        savingProactive = true
        scope.launch {
            try {
                val body = proactiveSettingsBody(proactiveEnabled, proactiveDelivery, proactiveCheckMinutes, proactiveQuietStart, proactiveQuietEnd, prefs)
                val response = saveProactiveSettings(vm, profile, body)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                    vm.storeData(proactiveKey, response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, "Couldn't update Proactive settings. Try again.")
            } finally { savingProactive = false }
        }
    }
    fun updateProactiveRule(id: String, method: String, body: JSONObject? = null) {
        if (savingProactive || proactiveChoicesChanged) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) { vm.notice("Couldn't update the rule. Try again."); return }
        savingProactive = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/requests/${Uri.encode(id)}", mapOf("profile" to profile), method = method, data = body)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, "Couldn't update the rule. Try again.")
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    fun addProactiveRule() {
        openProactiveRuleForm(vm, profile,
            onCreated = { if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++ },
            onMissingPlugin = { vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true)) },
            sources = proactive?.let(::proactiveSources).orEmpty(),
        )
    }
    fun removeLearnedNote(note: String) {
        if (savingProactive || proactiveChoicesChanged) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) { vm.notice("Couldn't remove this preference. Try again."); return }
        savingProactive = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/learned", mapOf("profile" to profile), method = "DELETE", data = json("note" to note))
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                reportProactiveError(error, "Couldn't remove this preference. Try again.")
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    LaunchedEffect(Unit) { vm.load("navigation-profiles", "/api/profiles", force = false); vm.load("picker:models", "/api/models", force = false) }
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    val profileLabel = profileDisplayLabel(profile, profileRows, profileLoading)
    val profiles = profileRows.map { it.string("name") }.filter(String::isNotBlank)
    // "provider\tmodel" keys; blank = the profile's default. The saved pick stays listed even before the list loads.
    val models = data["picker:models"]?.rows("groups").orEmpty().flatMap { group -> group.rows("models").map { group.string("provider_id") + "\t" + it.string("id") to group.string("provider") + " · " + it.string("label", it.string("id")) } }.toMap()
    val selectedModel = if (prefs.assistantModel.isBlank()) "" else prefs.assistantProvider + "\t" + prefs.assistantModel
    LazyColumn(Modifier.testTag("assistant-settings"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name, { name = it.take(ASSISTANT_NAME_MAX) }, label = { Text(uiText("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("assistant-name"),
                        trailingIcon = { if (assistantNameOf(name) != prefs.assistantName) FilledTonalButton(onClick = { vm.setAssistantName(name) }, modifier = Modifier.testTag("assistant-name-save"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(uiText("Save")) } })
                    OutlinedTextField(ownerName, { ownerName = ownerNameOf(it) }, label = { Text("What should Beacon call you?") }, supportingText = { Text("Optional") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("owner-name"),
                        trailingIcon = { if (ownerName != prefs.ownerName) FilledTonalButton(onClick = { vm.setOwnerName(ownerName) }, modifier = Modifier.testTag("owner-name-save"), contentPadding = PaddingValues(horizontal = 8.dp)) { Text(uiText("Save")) } })
                    AssistantProfileChoice(prefs.assistantProfile, profiles, profileRows, profileLoading, { vm.setAssistantProfile(it) }, Modifier.fillMaxWidth().testTag("assistant-profile"))
                    Choice("Model", selectedModel, voiceOptionsWithSelected(listOf("") + models.keys, selectedModel), { key -> vm.setAssistantModel(key.substringAfter('\t', ""), if (key.isBlank()) "" else key.substringBefore('\t')) },
                        Modifier.fillMaxWidth().testTag("assistant-model"), optionLabels = models + ("" to "Profile default") + (selectedModel to (models[selectedModel] ?: prefs.assistantModel)).takeIf { selectedModel.isNotBlank() }.let { listOfNotNull(it).toMap() })
                    Text(uiText("Its chat always uses this profile and model. Your other chats keep theirs."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                            Text(uiText("What needs you") + " · " + profileLabel, style = MaterialTheme.typography.titleMedium)
                            Text(assistantText("Beacon tells you what needs your attention."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pluginStatus != "absent" && proactive?.optBoolean("_pluginUpdateRequired") != true) Switch(
                            checked = proactiveEnabled,
                            onCheckedChange = ::setProactive,
                            enabled = proactiveLoaded && ui.connected && vm.client != null && !savingProactive && !proactiveChoicesChanged,
                            modifier = Modifier.testTag("assistant-proactive-toggle"),
                        )
                    }
                    if (proactiveLoaded) {
                        val sources = proactive?.let(::proactiveSources).orEmpty()
                        Text(if (sources.isEmpty()) uiText("Sources not available yet.") else uiText("Can watch") + ": " + sources.joinToString(", "),
                            Modifier.testTag("proactive-sources"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (pluginStatus == "absent" || proactive?.optBoolean("_pluginUpdateRequired") == true) {
                        MissingBeaconPluginState(compact = true, update = pluginStatus != "absent")
                    } else {
                        val checking = proactive?.optBoolean("_loading") == true || ui.connected && vm.client != null && proactive == null
                        if (checking) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiText("Watch status"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ProactiveHealthChip(ProactiveHealthState.Checking)
                        }
                        if (!ui.connected || vm.client == null) Text(uiText("Connect to Hermes to manage alerts."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (proactive?.has("_error") == true) {
                            Text(uiText(proactive.string("_message", "Couldn't load Proactive settings. Try again.")), Modifier.testTag("proactive-load-error"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!proactive.optBoolean("_pluginUpdateRequired")) OutlinedButton(onClick = { proactiveRefresh++ }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry")) }
                        }
                        if (proactiveLoaded && proactiveEnabled) {
                            val controlsEnabled = ui.connected && vm.client != null && !savingProactive
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText("Watch status"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ProactiveHealthChip(proactiveHealthState(proactive, checking))
                            }
                            proactive?.takeIf { it.optJSONObject("health")?.string("state") == "error" }?.let {
                                Text(proactiveHealthText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(uiText("When something matters"), style = MaterialTheme.typography.titleMedium)
                            Choice("Delivery", proactiveDelivery, listOf("notify", "call"), { proactiveDelivery = it }, Modifier.testTag("proactive-delivery"), mapOf("notify" to "Notify", "call" to "Call"), controlsEnabled)
                            if (proactiveCallsUnavailable(proactive, proactiveDelivery, callbacksAvailable))
                                Text(uiText("Calls are off on this Hermes. You'll get a notification instead."), Modifier.testTag("proactive-calls-off"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Choice("Check every", proactiveCheckMinutes.toString(), listOf("5", "15", "30"), { proactiveCheckMinutes = it.toInt() }, Modifier.testTag("proactive-check-minutes"), mapOf("5" to "5 min", "15" to "15 min", "30" to "30 min"), controlsEnabled)
                            ProactiveTimePicker("Quiet hours start", proactiveQuietStart, controlsEnabled) { proactiveQuietStart = it }
                            ProactiveTimePicker("Quiet hours end", proactiveQuietEnd, controlsEnabled) { proactiveQuietEnd = it }
                            Button(onClick = ::saveProactiveChoices, enabled = proactiveChoicesChanged && controlsEnabled, modifier = Modifier.testTag("proactive-save-settings")) { Text(uiText("Save settings")) }
                        }
                    }
                }
            }
        }
        if (proactiveLoaded && proactiveEnabled) item {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiText("Your rules"), style = MaterialTheme.typography.titleMedium)
                    val rules = proactive.rows("rules")
                    if (rules.isEmpty()) Text(uiText("No rules yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rules.forEach { rule ->
                        val id = rule.string("id")
                        val controlsEnabled = ui.connected && vm.client != null && !savingProactive
                        val enabled = controlsEnabled && !proactiveChoicesChanged
                        Column(Modifier.fillMaxWidth().testTag("proactive-rule-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(rule.string("text"), Modifier.testTag("proactive-rule-text-$id"), style = MaterialTheme.typography.bodyMedium)
                            Text(uiText("Source") + ": " + ((rule.opt("signal") as? String)?.takeIf(String::isNotBlank) ?: uiText("Anything")),
                                Modifier.testTag("proactive-rule-source-$id"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Choice("When it matches", rule.string("action", "notify"), listOf("notify", "call"), { updateProactiveRule(id, "PATCH", json("action" to it)) }, Modifier.testTag("proactive-rule-action-$id"), mapOf("notify" to "Notify", "call" to "Call"), enabled)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText("Even at night"), Modifier.weight(1f))
                                Switch(rule.optBoolean("night"), { updateProactiveRule(id, "PATCH", json("night" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-night-$id"))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText(if (rule.optBoolean("enabled")) "On" else "Off").replaceFirstChar(Char::titlecase), Modifier.weight(1f).testTag("proactive-rule-status-$id"))
                                Switch(rule.optBoolean("enabled"), { updateProactiveRule(id, "PATCH", json("enabled" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-enabled-$id"))
                                OutlinedButton(onClick = { updateProactiveRule(id, "DELETE") }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("proactive-rule-delete-$id"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Delete")) }
                            }
                        }
                    }
                    val controlsEnabled = ui.connected && vm.client != null && !savingProactive
                    Button(onClick = ::addProactiveRule, enabled = controlsEnabled && !proactiveChoicesChanged && rules.size < 20, modifier = Modifier.heightIn(min = 48.dp).testTag("proactive-add-rule")) { Text(uiText("Add a rule")) }
                    if (rules.size >= 20) Text(uiText("Delete a rule to add another."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (proactiveLoaded && proactiveEnabled) item {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(assistantText("What Beacon learned"), style = MaterialTheme.typography.titleMedium)
                    val learned = proactiveLearnedNotes(proactive)
                    if (learned.isEmpty()) Text(assistantText("Mark alerts as Important or Not important to teach Beacon."), Modifier.testTag("proactive-learned-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    learned.forEachIndexed { index, note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(note, Modifier.weight(1f).testTag("proactive-learned-$index"), style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { removeLearnedNote(note) }, enabled = ui.connected && vm.client != null && !savingProactive && !proactiveChoicesChanged, modifier = Modifier.heightIn(min = 48.dp).testTag("proactive-learned-remove-$index"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Remove")) }
                        }
                    }
                }
            }
        }
    }
}

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
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (page) {
            "provider" -> Text("Choose a voice provider", style = MaterialTheme.typography.titleLarge)
            "key" -> Text("Add your ${voiceProviderInfo(keyFor).short} key", style = MaterialTheme.typography.titleLarge)
            else -> {
                Text("Provider for one-tap calls. Keys stay on this phone.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VoiceSettingsPluginNote(pluginAbsent)
            }
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

    fun linkFailure(failure: Throwable) = if ((failure as? ApiFailure)?.status == 409) "Too many phones. Remove one below." else "Couldn't add this phone. Try again."

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
                failure.status == 403 -> "Finish setting up Beacon on your Hermes dashboard, then retry."
                isMissingBeaconPluginRoute(failure) -> "Devices are unavailable because the Beacon plugin isn't installed on this Hermes."
                else -> "Couldn't load devices. Check your connection and try again."
            }
        } catch (_: Exception) {
            if (vm.client === client) error = "Couldn't load devices. Check your connection and try again."
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Devices", style = MaterialTheme.typography.titleLarge)
        when {
            pluginAbsent -> MissingBeaconPluginState(compact = true)
            source == null || !ui.authenticated -> {
                Text("Reconnect to Hermes to manage your devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.connect(ui.origin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reconnect") }
            }
            removed -> {
                Text("This phone was removed from Beacon", Modifier.testTag("devices-removed"), style = MaterialTheme.typography.bodyLarge)
                Text("It won't get calls or alerts until you add it again. It stays signed in to Hermes.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = ::addThisPhone, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("devices-link-this-phone")) { Text("Add this phone") }
            }
            loading && devices == null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Loading devices…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error.isNotBlank() && devices == null -> {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { refresh++ }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-devices-retry")) { Text("Retry") }
            }
            else -> {
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { refresh++ }, enabled = !loading && !busy, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("settings-devices-refresh")) { Text("Refresh") }
                val rows = devices.orEmpty()
                if (rows.none { it.id == thisDeviceId }) {
                    Text("This phone isn't linked to Beacon.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = ::addThisPhone, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("devices-link-this-phone")) { Text("Add this phone") }
                }
                DevicesList(rows, thisDeviceId, enabled = !busy) { pendingRemoval = it }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    pendingRemoval?.let { device ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingRemoval = null },
            title = { Text("Remove ${device.name}?") },
            text = { Text("It stops getting calls and alerts from Beacon.") },
            confirmButton = {
                TextButton(
                    onClick = {
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
                                    if (vm.client === active) error = "Couldn't remove this device. Try again."
                                } finally {
                                    if (vm.client === active) busy = false
                                }
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("settings-device-remove-confirm"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }
}

/** This phone first (no Remove: Sign out does that), then each other phone with its last-seen time and Remove. */
@Composable
internal fun DevicesList(devices: List<PairedDevice>, thisDeviceId: String?, enabled: Boolean = true, onRemove: (PairedDevice) -> Unit) {
    val thisPhone = devices.firstOrNull { it.id == thisDeviceId }
    val others = devices.filterNot { it.id == thisDeviceId }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        thisPhone?.let { DeviceSettingsRow(it, current = true, enabled = enabled, onRemove = null) }
        if (others.isNotEmpty()) {
            Text("Other phones", style = MaterialTheme.typography.titleMedium)
            others.forEach { device -> DeviceSettingsRow(device, current = false, enabled = enabled) { onRemove(device) } }
        } else if (thisPhone != null) {
            Text("No other phones are linked.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeviceSettingsRow(device: PairedDevice, current: Boolean, enabled: Boolean, onRemove: (() -> Unit)?) {
    val lastSeen = recordedMessageTime(device.lastSeen).takeIf { it > 0 }
    val seen = lastSeen?.let { "Last seen ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))}" } ?: "Last seen unavailable"
    Column(Modifier.fillMaxWidth().testTag("settings-device-${device.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (current) Text("This phone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(device.name, style = MaterialTheme.typography.bodyMedium)
            }
            onRemove?.let {
                OutlinedButton(onClick = it, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-device-remove-${device.id}"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            }
        }
        Text(if (current) "Linked · $seen" else seen, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun VoiceSettingsPluginNote(pluginAbsent: Boolean) {
    if (pluginAbsent) MissingBeaconPluginState(compact = true)
}
