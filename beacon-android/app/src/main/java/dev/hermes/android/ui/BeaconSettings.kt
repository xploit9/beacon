package dev.hermes.android.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.isMissingBeaconPluginRoute
import dev.hermes.android.core.Scope
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import dev.hermes.android.data.*
import dev.hermes.android.voice.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal fun voiceOptionsWithSelected(options: List<String>, selected: String): List<String> = (listOf(selected) + options).distinct()
internal fun voiceChoiceLabel(voice: String): String = voice.replaceFirstChar(Char::titlecase)
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

private data class VoiceCatalog(val models: List<String> = emptyList(), val voices: List<String> = emptyList())

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
    val proactiveKey = proactiveSettingsKey(Scope(ui.origin, ui.owner, profile))
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
        vm.storeData(proactiveKey, JSONObject().put("_loading", true))
        try {
            val response = fetchProactiveSettings(source, profile)
            if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.storeData(proactiveKey, response)
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

@Composable internal fun BeaconSettings(vm: BeaconViewModel, pluginAbsent: Boolean) {
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text("Provider for one-tap calls. Keys stay on this phone.",
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            VoiceSettingsPluginNote(pluginAbsent)
        }
        item { Glass(Modifier.fillMaxWidth()) { VoiceKeySetupPanel(vm::voiceKeyConfigured) } }
    }
}

@Composable
internal fun DevicesSettingsSection(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val source = vm.client
    val scopeKey = source?.scope?.key.orEmpty()
    var devices by remember(scopeKey) { mutableStateOf<List<PairedDevice>?>(null) }
    var loading by remember(scopeKey) { mutableStateOf(false) }
    var removing by remember(scopeKey) { mutableStateOf(false) }
    var error by remember(scopeKey) { mutableStateOf("") }
    var removed by remember(scopeKey) { mutableStateOf(false) }
    var refresh by remember(scopeKey) { mutableIntStateOf(0) }
    var pendingRemoval by remember(scopeKey) { mutableStateOf<PairedDevice?>(null) }
    val requests = rememberCoroutineScope()

    LaunchedEffect(scopeKey, refresh, pluginAbsent) {
        val client = vm.client ?: return@LaunchedEffect
        if (pluginAbsent || client.auth.owner.isBlank()) return@LaunchedEffect
        loading = true
        error = ""
        try {
            val result = NativePairing.devices(client)
            if (vm.client === client) {
                val thisDevice = NativePairing.deviceId(context, client.scope)
                if (thisDevice != null && result.none { it.id == thisDevice }) {
                    NativePairing.clearDeviceId(context, client.scope)
                    removed = true
                } else devices = result
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: DeviceRemovedFailure) {
            if (vm.client === client) {
                NativePairing.clearDeviceId(context, client.scope)
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

    val thisDeviceId = source?.let { NativePairing.deviceId(context, it.scope) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Devices", style = MaterialTheme.typography.titleLarge)
        when {
            pluginAbsent -> MissingBeaconPluginState(compact = true)
            source == null || !ui.authenticated -> {
                Text("Reconnect to Hermes to manage paired devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.connect(ui.origin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Reconnect") }
            }
            removed -> {
                Text("This phone was removed from Beacon on your PC", Modifier.testTag("devices-removed"), style = MaterialTheme.typography.bodyLarge)
                Text("Pair this phone again to restore its connection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { vm.reopenSetupWizard(ui.origin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("devices-pair-again")) { Text("Pair again") }
            }
            loading && devices == null -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Loading devices…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error.isNotBlank() -> {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { refresh++ }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-devices-retry")) { Text("Retry") }
            }
            else -> {
                OutlinedButton(onClick = { refresh++ }, enabled = !loading && !removing, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp).testTag("settings-devices-refresh")) { Text("Refresh") }
                val rows = devices.orEmpty()
                val thisPhone = rows.firstOrNull { it.id == thisDeviceId }
                if (thisPhone != null) DeviceSettingsRow(thisPhone, current = true) { pendingRemoval = thisPhone }
                else {
                    Text("This phone isn't paired yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { vm.reopenSetupWizard(ui.origin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("devices-pair-this-phone")) { Text("Pair this phone") }
                }
                val others = rows.filterNot { it.id == thisDeviceId }
                if (others.isNotEmpty()) {
                    Text("Other devices", style = MaterialTheme.typography.titleMedium)
                    others.forEach { device -> DeviceSettingsRow(device, current = false) { pendingRemoval = device } }
                } else if (thisPhone != null) {
                    Text("No other devices are paired.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (removing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    pendingRemoval?.let { device ->
        val client = vm.client
        val isThisPhone = client != null && device.id == NativePairing.deviceId(context, client.scope)
        AlertDialog(
            onDismissRequest = { if (!removing) pendingRemoval = null },
            title = { Text(if (isThisPhone) "Remove this phone?" else "Remove ${device.name}?") },
            text = { Text("This device will no longer be connected to Beacon on your PC.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val active = vm.client
                        if (active != null) {
                            pendingRemoval = null
                            removing = true
                            requests.launch {
                                try {
                                    NativePairing.remove(active, device.id)
                                    if (vm.client === active) {
                                        if (isThisPhone) {
                                            NativePairing.clearDeviceId(context, active.scope)
                                            removed = true
                                        } else refresh++
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    if (vm.client === active) error = "Couldn't remove this device. Try again."
                                } finally {
                                    if (vm.client === active) removing = false
                                }
                            }
                        }
                    },
                    enabled = !removing,
                    modifier = Modifier.testTag("settings-device-remove-confirm"),
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }, enabled = !removing) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DeviceSettingsRow(device: PairedDevice, current: Boolean, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("settings-device-${device.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (current) Text("This phone", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(device.name, style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(onClick = onRemove, modifier = Modifier.heightIn(min = 48.dp).testTag("settings-device-remove-${device.id}"), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
        }
        Text(
            if (device.lastSeen.isBlank()) "Last seen unavailable" else "Last seen ${device.lastSeen}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable internal fun VoiceSettingsPluginNote(pluginAbsent: Boolean) {
    if (pluginAbsent) MissingBeaconPluginState(compact = true)
}

@Composable internal fun VoiceKeySetupPanel(
    onKeySaved: () -> Unit,
    showTitle: Boolean = true,
    onKeyAvailabilityChanged: (Boolean) -> Unit = {},
    showCustomization: Boolean = true,
) {
    val context=LocalContext.current.applicationContext
    val store=remember(context) { VoiceKeyStore(context) }
    var selection by remember { mutableStateOf(store.settings()) }
    var draft by remember(selection.provider) { mutableStateOf("") }
    var keySaved by remember(selection.provider) { mutableStateOf(false) }
    val keyFieldRequester = remember { FocusRequester() }
    var models by remember(selection.provider) { mutableStateOf(listOf(defaultVoiceModel(selection.provider))) }
    var voices by remember(selection.provider) { mutableStateOf(defaultVoiceChoices(selection.provider)) }
    var catalogLoading by remember(selection.provider) { mutableStateOf(false) }
    var catalogRefresh by remember(selection.provider) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()

    LaunchedEffect(selection.provider) {
        keySaved = withContext(Dispatchers.IO) { store.hasKey(selection.provider) }
        onKeyAvailabilityChanged(keySaved)
    }
    // A key typed here is never lost: it is saved (encrypted, per provider) on switch, Test or leaving the screen.
    DisposableEffect(selection.provider) { onDispose { if(draft.isNotBlank()) runCatching { store.saveKey(selection.provider,draft) } } }

    LaunchedEffect(selection.provider,catalogRefresh,showCustomization) {
        if (!showCustomization) return@LaunchedEffect
        val apiKey=withContext(Dispatchers.IO) { store.key(selection.provider) }
        if(apiKey==null) {
            models=voiceOptionsWithSelected(emptyList(),selection.model)
            voices=voiceOptionsWithSelected(defaultVoiceChoices(selection.provider),selection.voice)
            catalogLoading=false
            return@LaunchedEffect
        }
        catalogLoading=true
        try {
            val catalog=loadVoiceCatalog(selection.provider,apiKey)
            models=voiceOptionsWithSelected(catalog.models,selection.model)
            voices=voiceOptionsWithSelected(catalog.voices.ifEmpty { defaultVoiceChoices(selection.provider) },selection.voice)
        } catch (cancelled:CancellationException) {
            throw cancelled
        } catch (_:Exception) {
            models=voiceOptionsWithSelected(emptyList(),selection.model)
            voices=voiceOptionsWithSelected(defaultVoiceChoices(selection.provider),selection.voice)
        } finally {
            catalogLoading=false
        }
    }

    fun saveSelection(next:VoicePreferences) {
        if(busy) return
        busy=true
        scope.launch {
            val saved=runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(next) } }.isSuccess
            if(saved) { selection=next;status="Saved for the next call." }
            else status="Voice preferences could not be saved."
            busy=false
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(showTitle) Text("Realtime provider",style=MaterialTheme.typography.titleMedium)
        listOf("gemini" to "Gemini Live","grok" to "Grok","openai" to "OpenAI Realtime").forEach { (id,label) ->
            Glass {
                ListItem(
                    headlineContent={Text(label)},
                    supportingContent={Text(if(selection.provider==id) "Selected for new calls" else "Use for new calls")},
                    leadingContent={RadioButton(selected=selection.provider==id,onClick=null)},
                    modifier=Modifier.fillMaxWidth().testTag("voice-model-$id").selectable(selected=selection.provider==id,role=Role.RadioButton,onClick={
                        if(selection.provider!=id&&!busy) {
                            val next=store.settings(id)
                            val typed=draft.trim()
                            val leaving=selection.provider
                            draft=""
                            status=""
                            if(typed.isNotEmpty()) scope.launch {
                                if(runCatchingCancellable { withContext(Dispatchers.IO) { store.saveKey(leaving,typed) } }.isFailure) status="That key couldn't be saved. Check it and try again."
                            }
                            saveSelection(next)
                        }
                    }),
                    colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent)
                )
            }
        }

        OutlinedTextField(
            value=draft,
            onValueChange={draft=it.take(8192);status=""},
            modifier=Modifier.fillMaxWidth().focusRequester(keyFieldRequester).testTag("voice-provider-key"),
            label={Text("Provider API key")},
            placeholder={Text(if(keySaved) "Enter a replacement key" else "Enter an API key")},
            supportingText={if(keySaved) Text("Key saved")},
            visualTransformation=PasswordVisualTransformation(),
            keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),
            singleLine=true,
            enabled=!busy,
        )
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Button(
                onClick={
                    val provider=selection.provider
                    val model=selection.model
                    val entered=draft.trim()
                    busy=true
                    scope.launch {
                        val apiKey=entered.ifBlank { withContext(Dispatchers.IO) { store.key(provider).orEmpty() } }
                        if(apiKey.isBlank()) { status="Enter an API key first.";busy=false;return@launch }
                        val newlySaved=entered.isNotBlank()&&runCatchingCancellable { withContext(Dispatchers.IO) { store.saveKey(provider,apiKey) } }.isSuccess
                        if(entered.isNotBlank()&&!newlySaved) { status="That key couldn't be saved. Check it and try again.";busy=false;return@launch }
                        if(newlySaved) {
                            draft=""
                            keySaved=true
                            onKeyAvailabilityChanged(true)
                            catalogRefresh++
                        }
                        val saved=if(newlySaved) "Key saved. " else ""
                        val result=testVoiceProvider(provider,apiKey,model)
                        status=when(result) {
                            dev.hermes.android.voice.VoiceProviderTestResult.Success -> { onKeySaved();voiceProviderTestMessage(result) }
                            dev.hermes.android.voice.VoiceProviderTestResult.KeyRejected -> voiceProviderTestMessage(result)
                            else -> saved+voiceProviderTestMessage(result)
                        }
                        busy=false
                    }
                },
                enabled=!busy&&(draft.isNotBlank()||keySaved),
            ) { if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Test") }
            if(keySaved) OutlinedButton(onClick={ keyFieldRequester.requestFocus() },enabled=!busy, modifier=Modifier.heightIn(min=48.dp)) { Text("Replace") }
        }
        if(keySaved) TextButton(onClick={
                val provider=selection.provider
                busy=true
                scope.launch {
                    val removed=runCatchingCancellable { withContext(Dispatchers.IO) { store.removeKey(provider) } }.isSuccess
                    if(removed) { keySaved=false;draft="";catalogRefresh++;status="Key removed from this phone.";onKeyAvailabilityChanged(false) }
                    else status="The key could not be removed."
                    busy=false
                }
            },enabled=!busy, modifier=Modifier.align(Alignment.End).heightIn(min=48.dp), colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Remove") }

        if (showCustomization) {
            Text("Make it yours", style = MaterialTheme.typography.titleMedium)
            VoiceCustomizationChoices(
                selection = selection,
                models = models,
                voices = voices,
                catalogLoading = catalogLoading,
                busy = busy,
                onModelSelected = { saveSelection(selection.copy(model = it)) },
                onVoiceSelected = { saveSelection(selection.copy(voice = it)) },
                onPersonalitySelected = { saveSelection(selection.copy(personality = it)) },
            )
        }
        status.takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable internal fun VoiceCustomizationPanel(ownerName: String, onOwnerNameChange: (String) -> Unit, onContinue: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VoiceKeyStore(context) }
    var selection by remember { mutableStateOf(store.settings()) }
    var models by remember(selection.provider) { mutableStateOf(listOf(defaultVoiceModel(selection.provider))) }
    var voices by remember(selection.provider) { mutableStateOf(defaultVoiceChoices(selection.provider)) }
    var catalogLoading by remember(selection.provider) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var ownerNameDraft by remember(ownerName) { mutableStateOf(ownerName) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selection.provider) {
        val apiKey = withContext(Dispatchers.IO) { store.key(selection.provider) }
        if (apiKey == null) {
            models = voiceOptionsWithSelected(emptyList(), selection.model)
            voices = voiceOptionsWithSelected(defaultVoiceChoices(selection.provider), selection.voice)
            return@LaunchedEffect
        }
        catalogLoading = true
        try {
            val catalog = loadVoiceCatalog(selection.provider, apiKey)
            models = voiceOptionsWithSelected(catalog.models, selection.model)
            voices = voiceOptionsWithSelected(catalog.voices.ifEmpty { defaultVoiceChoices(selection.provider) }, selection.voice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            models = voiceOptionsWithSelected(emptyList(), selection.model)
            voices = voiceOptionsWithSelected(defaultVoiceChoices(selection.provider), selection.voice)
        } finally {
            catalogLoading = false
        }
    }

    fun saveSelection(next: VoicePreferences) {
        if (busy) return
        busy = true
        scope.launch {
            val saved = runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(next) } }.isSuccess
            if (saved) { selection = next; status = "Saved for the next call." }
            else status = "Voice preferences could not be saved."
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Make it yours", style = MaterialTheme.typography.headlineSmall)
        Text("Choose a voice and personality for calls.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(ownerNameDraft, { ownerNameDraft = ownerNameOf(it); onOwnerNameChange(ownerNameDraft) },
            label = { Text("What should Beacon call you?") }, supportingText = { Text("Optional") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("setup-owner-name"))
        VoiceCustomizationChoices(
            selection = selection,
            models = models,
            voices = voices,
            catalogLoading = catalogLoading,
            busy = busy,
            onModelSelected = { saveSelection(selection.copy(model = it)) },
            onVoiceSelected = { saveSelection(selection.copy(voice = it)) },
            onPersonalitySelected = { saveSelection(selection.copy(personality = it)) },
        )
        status.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Button(onClick = onContinue, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-voice-customize-continue")) { Text("Continue") }
        TextButton(onClick = onSkip, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("setup-voice-customize-skip")) { Text("Skip for now") }
    }
}

@Composable internal fun VoiceCustomizationChoices(
    selection: VoicePreferences,
    models: List<String>,
    voices: List<String>,
    catalogLoading: Boolean,
    busy: Boolean,
    onModelSelected: (String) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onPersonalitySelected: (String) -> Unit,
) {
    val modelOptions = voiceOptionsWithSelected(models, selection.model)
    val voiceOptions = voiceOptionsWithSelected(voices, selection.voice)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Choice("Model", selection.model, modelOptions, onModelSelected,
            modifier = Modifier.fillMaxWidth(),
            optionLabels = modelOptions.associateWith(::voiceModelDisplayName), enabled = !busy)
        Choice("Voice", selection.voice, voiceOptions, onVoiceSelected,
            modifier = Modifier.fillMaxWidth(),
            optionLabels = voiceOptions.associateWith(::voiceChoiceLabel), enabled = !busy)
        if (catalogLoading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BeaconSignal(BeaconMotionMode.Sweep, size = 18.dp)
            Text("Loading voice options", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Choice("Personality", selection.personality, listOf("balanced", "concise", "warm"), onPersonalitySelected,
            modifier = Modifier.fillMaxWidth(),
            optionLabels = mapOf("balanced" to "Balanced", "concise" to "Concise", "warm" to "Warm"), enabled = !busy)
    }
}

private fun defaultVoiceChoices(provider:String):List<String> = when(provider) {
    "grok" -> listOf("eve","ara","rex","sal","leo")
    "openai" -> OpenAiWire.VOICES
    else -> listOf("Puck","Charon","Kore","Fenrir","Aoede","Leda","Orus","Zephyr")
}

private suspend fun loadVoiceCatalog(provider:String,apiKey:String):VoiceCatalog = withContext(Dispatchers.IO) {
    val client=OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(15,TimeUnit.SECONDS).build()
    fun get(request:VoiceProviderRequest):JSONObject? = try {
        client.newCall(request.rawRequest()).execute().use { response ->
            if(!response.isSuccessful) return@use null
            val body=response.body?:return@use null
            if(body.contentLength()>1_000_000) return@use null
            JSONObject(body.string())
        }
    } catch (cancelled:CancellationException) {
        throw cancelled
    } catch (_:Exception) {
        null
    }
    try {
        when(provider) {
            "gemini" -> VoiceCatalog(
                GeminiWire.models(get(GeminiWire.modelRequest(apiKey))?:JSONObject()),
                GeminiWire.voices(get(GeminiWire.voiceRequest(apiKey))?:JSONObject()),
            )
            "grok" -> VoiceCatalog(
                GrokWire.models(get(GrokWire.modelRequest(apiKey))?:JSONObject()),
                GrokWire.voices(get(GrokWire.voiceRequest(apiKey))?:JSONObject()),
            )
            "openai" -> VoiceCatalog(
                OpenAiWire.models(get(OpenAiWire.modelRequest(apiKey))?:JSONObject()),
                OpenAiWire.VOICES,
            )
            else -> VoiceCatalog()
        }
    } finally {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
