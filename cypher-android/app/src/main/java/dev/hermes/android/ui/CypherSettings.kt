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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.isMissingCypherPluginRoute
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
/** The plugin's capabilities sit at the top of the merged bootstrap (mergeOptionalFeatures), not under optional_features. */
internal fun callbacksAvailable(bootstrap: org.json.JSONObject): Boolean = bootstrap.optJSONObject("capabilities")?.optBoolean("callbacks") == true
internal const val PROACTIVE_SETTINGS_PATH = "/api/plugins/cypher/mobile/proactive"
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
@Composable internal fun AssistantSettings(vm: CypherViewModel) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var name by remember(prefs.assistantName) { mutableStateOf(prefs.assistantName) }
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
        if (isMissingCypherPluginRoute(error)) vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true))
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
                    .put("_pluginUpdateRequired", isMissingCypherPluginRoute(error))
                    .put("_message", proactiveErrorMessage(error, "Couldn't load email settings. Try again.")))
        }
    }
    fun setProactive(enabled: Boolean) {
        if (savingProactive) return
        val source = vm.client
        if (source == null || !ui.connected || pluginStatus == "absent") return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) {
            vm.notice("Couldn't update email settings. Try again.")
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
                reportProactiveError(error, "Couldn't update email settings. Try again.")
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
                reportProactiveError(error, "Couldn't update email settings. Try again.")
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                        Text(uiText("Assistant"), style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(name, { name = it.take(ASSISTANT_NAME_MAX) }, label = { Text(uiText("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("assistant-name"),
                        trailingIcon = { if (assistantNameOf(name) != prefs.assistantName) TextButton({ vm.setAssistantName(name) }, Modifier.testTag("assistant-name-save")) { Text(uiText("Save")) } })
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
                        Icon(Icons.Rounded.Email, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                            Text(uiText("Important emails") + " · " + profileLabel, style = MaterialTheme.typography.titleMedium)
                            Text(assistantText("Cypher checks your email and tells you when something matters. It learns from you."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (pluginStatus != "absent" && proactive?.optBoolean("_pluginUpdateRequired") != true) Switch(
                            checked = proactiveEnabled,
                            onCheckedChange = ::setProactive,
                            enabled = proactiveLoaded && ui.connected && vm.client != null && !savingProactive && !proactiveChoicesChanged,
                            modifier = Modifier.testTag("assistant-proactive-toggle"),
                        )
                    }
                    if (pluginStatus == "absent" || proactive?.optBoolean("_pluginUpdateRequired") == true) {
                        MissingCypherPluginState(compact = true, update = pluginStatus != "absent")
                    } else {
                        val checking = proactive?.optBoolean("_loading") == true || ui.connected && vm.client != null && proactive == null
                        if (checking) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(uiText("Email status"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            ProactiveHealthChip(ProactiveHealthState.Checking)
                        }
                        if (!ui.connected || vm.client == null) Text(uiText("Connect to Hermes to manage email alerts."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else if (proactive?.has("_error") == true) {
                            Text(uiText(proactive.string("_message", "Couldn't load email settings. Try again.")), Modifier.testTag("proactive-load-error"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!proactive.optBoolean("_pluginUpdateRequired")) TextButton(onClick = { proactiveRefresh++ }) { Text(uiText("Retry")) }
                        }
                        if (proactiveLoaded && proactiveEnabled) {
                            val controlsEnabled = ui.connected && vm.client != null && !savingProactive
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText("Email status"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                ProactiveHealthChip(proactiveHealthState(proactive, checking))
                            }
                            proactive?.takeIf { it.optJSONObject("health")?.string("state") == "error" }?.let {
                                Text(proactiveHealthText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(uiText("When one arrives"), style = MaterialTheme.typography.titleMedium)
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
                            Choice("When it matches", rule.string("action", "notify"), listOf("notify", "call"), { updateProactiveRule(id, "PATCH", json("action" to it)) }, Modifier.testTag("proactive-rule-action-$id"), mapOf("notify" to "Notify", "call" to "Call"), enabled)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText("Even at night"), Modifier.weight(1f))
                                Switch(rule.optBoolean("night"), { updateProactiveRule(id, "PATCH", json("night" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-night-$id"))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(uiText(if (rule.optBoolean("enabled")) "On" else "Off").replaceFirstChar(Char::titlecase), Modifier.weight(1f).testTag("proactive-rule-status-$id"))
                                Switch(rule.optBoolean("enabled"), { updateProactiveRule(id, "PATCH", json("enabled" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-enabled-$id"))
                                TextButton(onClick = { updateProactiveRule(id, "DELETE") }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-delete-$id")) { Text(uiText("Delete")) }
                            }
                        }
                    }
                    val controlsEnabled = ui.connected && vm.client != null && !savingProactive
                    TextButton(onClick = ::addProactiveRule, enabled = controlsEnabled && !proactiveChoicesChanged && rules.size < 20, modifier = Modifier.testTag("proactive-add-rule")) { Text(uiText("Add a rule")) }
                    if (rules.size >= 20) Text(uiText("Delete a rule to add another."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (proactiveLoaded && proactiveEnabled) item {
            Glass(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(assistantText("What Cypher learned"), style = MaterialTheme.typography.titleMedium)
                    val learned = proactiveLearnedNotes(proactive)
                    if (learned.isEmpty()) Text(assistantText("Mark alerts as Important or Not important to teach Cypher."), Modifier.testTag("proactive-learned-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    learned.forEachIndexed { index, note ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(note, Modifier.weight(1f).testTag("proactive-learned-$index"), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { removeLearnedNote(note) }, enabled = ui.connected && vm.client != null && !savingProactive && !proactiveChoicesChanged, modifier = Modifier.testTag("proactive-learned-remove-$index")) { Text(uiText("Remove")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun CypherSettings(vm: CypherViewModel, pluginAbsent: Boolean) {
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text("Voice & calls",style=MaterialTheme.typography.titleLarge)
            Text("Provider for one-tap calls. Keys stay on this phone.",
                style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
            VoiceSettingsPluginNote(pluginAbsent)
        }
        item { Glass(Modifier.fillMaxWidth()) { VoiceKeySetupPanel(vm::voiceKeyConfigured) } }
    }
}

@Composable internal fun VoiceSettingsPluginNote(pluginAbsent: Boolean) {
    if (pluginAbsent) MissingCypherPluginState(compact = true)
}

@Composable internal fun VoiceKeySetupPanel(onKeySaved:()->Unit,showTitle:Boolean=true) {
    val context=LocalContext.current.applicationContext
    val store=remember(context) { VoiceKeyStore(context) }
    var selection by remember { mutableStateOf(store.settings()) }
    var draft by remember(selection.provider) { mutableStateOf("") }
    var keyMask by remember(selection.provider) { mutableStateOf("") }
    var models by remember(selection.provider) { mutableStateOf(listOf(defaultVoiceModel(selection.provider))) }
    var voices by remember(selection.provider) { mutableStateOf(defaultVoiceChoices(selection.provider)) }
    var catalogLoading by remember(selection.provider) { mutableStateOf(false) }
    var catalogRefresh by remember(selection.provider) { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var modelMenu by remember(selection.provider) { mutableStateOf(false) }
    var voiceMenu by remember(selection.provider) { mutableStateOf(false) }
    var personalityMenu by remember(selection.provider) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()

    LaunchedEffect(selection.provider) {
        keyMask=withContext(Dispatchers.IO) { store.maskedKey(selection.provider).orEmpty() }
    }
    // A key typed here is never lost: it is saved (encrypted, per provider) on switch, Test or leaving the screen.
    DisposableEffect(selection.provider) { onDispose { if(draft.isNotBlank()) runCatching { store.saveKey(selection.provider,draft) } } }

    LaunchedEffect(selection.provider,catalogRefresh) {
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
            modifier=Modifier.fillMaxWidth().testTag("voice-provider-key"),
            label={Text("Provider API key")},
            placeholder={Text(if(keyMask.isBlank()) "Enter an API key" else "Saved key ••••" + keyMask.takeLast(4))},
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
                            keyMask=withContext(Dispatchers.IO) { store.maskedKey(provider).orEmpty() }
                            catalogRefresh++
                        }
                        val saved=if(newlySaved) "Key saved. " else ""
                        when(testVoiceProvider(provider,apiKey,model)) {
                            dev.hermes.android.voice.VoiceProviderTestResult.Success -> {
                                status="Connection succeeded. The key is stored on this phone."
                                onKeySaved()
                            }
                            dev.hermes.android.voice.VoiceProviderTestResult.KeyRejected -> status=saved+"The provider rejected this key. Check the key and account access."
                            dev.hermes.android.voice.VoiceProviderTestResult.Unreachable -> status=saved+"The provider could not be reached. Check your connection and try again."
                            dev.hermes.android.voice.VoiceProviderTestResult.ProviderError -> status=saved+"The provider responded with an error. Check the selected model or try again."
                        }
                        busy=false
                    }
                },
                enabled=!busy&&(draft.isNotBlank()||keyMask.isNotBlank()),
            ) { if(busy) CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp) else Text("Test") }
            if(keyMask.isNotBlank()) TextButton(onClick={
                val provider=selection.provider
                busy=true
                scope.launch {
                    val removed=runCatchingCancellable { withContext(Dispatchers.IO) { store.removeKey(provider) } }.isSuccess
                    if(removed) { keyMask="";draft="";catalogRefresh++;status="Key removed from this phone." }
                    else status="The key could not be removed."
                    busy=false
                }
            },enabled=!busy) { Text("Remove key") }
        }

        Box {
            TextButton(onClick={modelMenu=true},enabled=!busy) { Text("Model: " + selection.model) }
            DropdownMenu(expanded=modelMenu,onDismissRequest={modelMenu=false}) {
                models.distinct().forEach { model ->
                    DropdownMenuItem(text={Text(model)},onClick={
                        modelMenu=false
                        saveSelection(selection.copy(model=model))
                    })
                }
            }
        }
        Box {
            TextButton(onClick={voiceMenu=true},enabled=!busy) { Text("Voice: " + voiceChoiceLabel(selection.voice)) }
            DropdownMenu(expanded=voiceMenu,onDismissRequest={voiceMenu=false}) {
                voices.distinct().forEach { voice ->
                    DropdownMenuItem(text={Text(voiceChoiceLabel(voice))},onClick={
                        voiceMenu=false
                        saveSelection(selection.copy(voice=voice))
                    })
                }
            }
        }
        Box {
            TextButton(onClick={personalityMenu=true},enabled=!busy) { Text("Personality: " + selection.personality.replaceFirstChar { it.uppercase() }) }
            DropdownMenu(expanded=personalityMenu,onDismissRequest={personalityMenu=false}) {
                listOf("balanced","concise","warm").forEach { personality ->
                    DropdownMenuItem(text={Text(personality.replaceFirstChar { it.uppercase() })},onClick={
                        personalityMenu=false
                        saveSelection(selection.copy(personality=personality))
                    })
                }
            }
        }
        if(catalogLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        status.takeIf(String::isNotBlank)?.let { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
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
