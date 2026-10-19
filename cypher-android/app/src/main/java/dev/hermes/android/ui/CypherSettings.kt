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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
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
    val proactiveSetupComplete = proactive?.optBoolean("setup_complete") == true
    val pluginStatus = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin").orEmpty()
    val scope = rememberCoroutineScope()
    var savingProactive by remember(profile) { mutableStateOf(false) }
    var proactiveRefresh by remember(profile) { mutableIntStateOf(0) }
    var showProactiveSetup by remember(profile) { mutableStateOf(false) }
    val proactiveVersion = proactive?.toString()
    var proactiveSelections by remember(proactiveVersion) { mutableStateOf(proactive?.let(::proactiveSelectionsFrom) ?: JSONObject()) }
    var proactiveSchedule by remember(proactiveVersion) { mutableStateOf(proactive?.optJSONObject("schedule")?.copy() ?: json("brief" to "08:00", "wrap_up" to "19:00", "watch_minutes" to 30, "quiet_start" to "22:00", "quiet_end" to "07:00")) }
    var proactiveLevel by remember(proactiveVersion) { mutableStateOf(proactive?.string("level").orEmpty().ifBlank { "useful" }) }
    var proactiveUrgentDelivery by remember(proactiveVersion) { mutableStateOf(proactive?.optJSONObject("urgent")?.string("delivery").orEmpty().ifBlank { "notify" }) }
    val proactiveChoicesChanged = proactive != null && (proactiveSelections.toString() != proactiveSelectionsFrom(proactive).toString() || !proactiveScheduleMatches(proactiveSchedule, proactive.optJSONObject("schedule")) || proactiveLevel != proactive.string("level") || proactiveUrgentDelivery != proactive.optJSONObject("urgent")?.string("delivery", "notify"))
    LaunchedEffect(ui.origin, ui.owner, ui.profile, profile, ui.connected, pluginStatus, vm.client, proactiveRefresh) {
        if (!ui.connected || pluginStatus == "absent") return@LaunchedEffect
        val source = vm.client ?: return@LaunchedEffect
        vm.storeData(proactiveKey, JSONObject().put("_loading", true))
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) {
            vm.storeData(proactiveKey, JSONObject().put("_error", "Couldn’t load proactive settings."))
            return@LaunchedEffect
        }
        try {
            val response = client.get(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile))
            if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.storeData(proactiveKey, response)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                vm.storeData(proactiveKey, JSONObject().put("_error", "Couldn’t load proactive settings."))
        } finally { client.closeNative() }
    }
    fun setProactive(enabled: Boolean) {
        if (savingProactive) return
        if (enabled && !proactiveSetupComplete) { showProactiveSetup = true; return }
        val source = vm.client
        if (source == null || !ui.connected || pluginStatus == "absent") return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) {
            vm.notice("Couldn’t update proactive settings. Try again.")
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
                // A 409 names a host setup step that retrying cannot fix.
                vm.notice((error as? ApiFailure)?.takeIf { it.status == 409 }?.message ?: "Couldn’t update proactive settings. Try again.")
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    fun saveProactiveChoices() {
        if (savingProactive || !proactiveSetupComplete || !proactiveChoicesChanged) return
        savingProactive = true
        scope.launch {
            try {
                val response = saveProactiveSetup(vm, profile, proactiveSelections.copy(), proactiveSchedule.copy(), proactiveLevel, prefs, proactiveEnabled, proactiveUrgentDelivery)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                    vm.storeData(proactiveKey, response)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                vm.notice((error as? ApiFailure)?.takeIf { it.status == 409 }?.message ?: error.message ?: "Couldn’t update proactive settings. Try again.")
            } finally { savingProactive = false }
        }
    }
    fun runProactivePreview() {
        if (savingProactive) return
        savingProactive = true
        scope.launch {
            try {
                if (proactiveChoicesChanged && proactive != null) {
                    val saved = saveProactiveSetup(vm, profile, proactiveSelections.copy(), proactiveSchedule.copy(), proactiveLevel, prefs, proactiveEnabled, proactiveUrgentDelivery)
                    if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile)
                        vm.storeData(proactiveKey, saved)
                }
                if (vm.client?.scope?.origin != ui.origin || vm.client?.scope?.owner != ui.owner || vm.preferences.value.assistantProfile != profile) return@launch
                val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
                val client = source.forAuthorizedProfile(profile)
                try { client.call("$PROACTIVE_SETTINGS_PATH/preview", mapOf("profile" to profile), method = "POST", data = JSONObject()) } finally { client.closeNative() }
                vm.notice("Brief requested. It will arrive as a notification.")
            }
            catch (error: Throwable) {
                if (error is CancellationException) throw error
                vm.notice((error as? ApiFailure)?.takeIf { it.status == 409 }?.message ?: error.message ?: "Couldn’t run a brief. Try again.")
            } finally { savingProactive = false }
        }
    }
    fun updateProactiveRequest(id: String, method: String, body: JSONObject? = null) {
        if (savingProactive || proactiveChoicesChanged) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(profile) } catch (_: Throwable) { vm.notice("Couldn’t update the request. Try again."); return }
        savingProactive = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/requests/${Uri.encode(id)}", mapOf("profile" to profile), method = method, data = body)
                if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                vm.notice((error as? ApiFailure)?.takeIf { it.status == 409 }?.message ?: error.message ?: "Couldn’t update the request. Try again.")
            } finally { client.closeNative(); savingProactive = false }
        }
    }
    fun addProactiveRequest(signals: List<JSONObject>) {
        openProactiveRequestForm(vm, profile, signals) {
            if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) proactiveRefresh++
        }
    }
    LaunchedEffect(Unit) { vm.load("navigation-profiles", "/api/profiles", force = false); vm.load("picker:models", "/api/models", force = false) }
    val profiles = data["navigation-profiles"]?.rows("profiles")?.map { it.string("name") }?.filter(String::isNotBlank).orEmpty()
    // "provider\tmodel" keys; blank = the profile's default. The saved pick stays listed even before the list loads.
    val models = data["picker:models"]?.rows("groups").orEmpty().flatMap { group -> group.rows("models").map { group.string("provider_id") + "\t" + it.string("id") to group.string("provider") + " · " + it.string("label", it.string("id")) } }.toMap()
    val selectedModel = if (prefs.assistantModel.isBlank()) "" else prefs.assistantProvider + "\t" + prefs.assistantModel
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedTextField(name, { name = it.take(ASSISTANT_NAME_MAX) }, label = { Text(uiText("Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("assistant-name"),
                trailingIcon = { if (assistantNameOf(name) != prefs.assistantName) TextButton({ vm.setAssistantName(name) }, Modifier.testTag("assistant-name-save")) { Text(uiText("Save")) } })
        }
        item {
            // The saved choice stays listed even before the profile list loads or if it is gone, so it is never silently swapped.
            Choice("Profile", prefs.assistantProfile, voiceOptionsWithSelected(profiles, prefs.assistantProfile), { vm.setAssistantProfile(it) }, Modifier.fillMaxWidth().testTag("assistant-profile"))
        }
        item {
            Choice("Model", selectedModel, voiceOptionsWithSelected(listOf("") + models.keys, selectedModel), { key -> vm.setAssistantModel(key.substringAfter('\t', ""), if (key.isBlank()) "" else key.substringBefore('\t')) },
                Modifier.fillMaxWidth().testTag("assistant-model"), optionLabels = models + ("" to "Profile default") + (selectedModel to (models[selectedModel] ?: prefs.assistantModel)).takeIf { selectedModel.isNotBlank() }.let { listOfNotNull(it).toMap() })
            Text(uiText("Its chat always uses this profile and model. Your other chats keep theirs."), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pluginStatus != "absent") item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(uiText("Proactive"), style = MaterialTheme.typography.titleMedium)
                        Text(uiText("Briefings, follow-ups and alerts"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = proactiveEnabled, onCheckedChange = ::setProactive, enabled = proactiveLoaded && ui.connected && vm.client != null && !savingProactive && !proactiveChoicesChanged,
                        modifier = Modifier.testTag("assistant-proactive-toggle"))
                }
                if (proactive?.optBoolean("_loading") == true || ui.connected && vm.client != null && proactive == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else if (!ui.connected || vm.client == null) Text(uiText("Connect to Hermes to manage Proactive."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (proactive?.has("_error") == true) {
                    Text(uiText("Proactive settings couldn’t be loaded."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { proactiveRefresh++ }) { Text(uiText("Retry")) }
                }
            }
        }
        if (pluginStatus != "absent" && proactiveSetupComplete) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText("What Cypher watches"), style = MaterialTheme.typography.titleMedium)
                proactive?.rows("signals").orEmpty().forEach { signal ->
                    val id = signal.string("id")
                    val label = signal.string("label", id)
                    val sources = signal.rows("sources")
                    val available = listOf(proactiveNone) + sources.map { it.string("id") }
                    val selected = proactiveSelections.opt(id)?.takeUnless { it == JSONObject.NULL }?.toString() ?: proactiveNone
                    val options = if (selected in available) available else listOf(selected) + available
                    val labels = sources.associate { it.string("id") to it.string("label", it.string("id")) } + (proactiveNone to "Don’t watch") + (if (selected in available) emptyMap() else mapOf(selected to "Saved source unavailable"))
                    if (sources.isEmpty()) Text("$label · Not connected. Connect it on your computer.", Modifier.testTag("proactive-no-source-$id"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        Choice(label, selected, options, { value -> proactiveSelections = proactiveSelections.copy().put(id, if (value == proactiveNone) JSONObject.NULL else value) }, Modifier.testTag("proactive-signal-choice-$id"), labels, enabled = !savingProactive && ui.connected && vm.client != null)
                        ProactiveSourceInfo(sources, selected, id)
                    }
                    ProactiveSignalHealth(signal, proactive?.string("timezone").orEmpty(), Modifier.padding(start = 12.dp))
                }
                Text(uiText("Schedule"), style = MaterialTheme.typography.titleMedium)
                Choice("For urgent things", proactiveUrgentDelivery, listOf("notify", "call"), { proactiveUrgentDelivery = it }, Modifier.testTag("proactive-urgent-delivery"), mapOf("notify" to "Notify me", "call" to "Call me"), enabled = !savingProactive && ui.connected && vm.client != null)
                ProactiveScheduleChoices(proactiveSchedule, proactiveLevel, proactive?.string("timezone").orEmpty().ifBlank { "your time zone" }, enabled = !savingProactive && ui.connected && vm.client != null) { schedule, selectedLevel ->
                    proactiveSchedule = schedule
                    proactiveLevel = selectedLevel
                }
                TextButton(onClick = ::saveProactiveChoices, enabled = proactiveChoicesChanged && !savingProactive && ui.connected && vm.client != null, modifier = Modifier.testTag("proactive-save-setup")) { Text(uiText("Save settings")) }
                Text(uiText("Your requests"), style = MaterialTheme.typography.titleMedium)
                val requests = proactive?.rows("requests").orEmpty()
                if (requests.isEmpty()) Text(uiText("No requests yet."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                requests.forEach { request ->
                    val id = request.string("id")
                    val enabled = !savingProactive && !proactiveChoicesChanged && ui.connected && vm.client != null
                    Column(Modifier.fillMaxWidth().testTag("proactive-request-$id"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(request.string("text"), Modifier.testTag("proactive-request-text-$id"), style = MaterialTheme.typography.bodyMedium)
                        Choice("Delivery", request.string("action", "notify"), listOf("notify", "call"), { updateProactiveRequest(id, "PATCH", json("action" to it)) }, Modifier.testTag("proactive-request-action-$id"), mapOf("notify" to "Notify", "call" to "Call"), enabled)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiText("Even at night"), Modifier.weight(1f))
                            Switch(request.optBoolean("night"), { updateProactiveRequest(id, "PATCH", json("night" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-request-night-$id"))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiText(if (request.optBoolean("enabled")) "On" else "Off"), Modifier.weight(1f).testTag("proactive-request-status-$id"))
                            Switch(request.optBoolean("enabled"), { updateProactiveRequest(id, "PATCH", json("enabled" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-request-enabled-$id"))
                            TextButton(onClick = { updateProactiveRequest(id, "DELETE") }, enabled = enabled, modifier = Modifier.testTag("proactive-request-delete-$id")) { Text(uiText("Delete")) }
                        }
                    }
                }
                TextButton(onClick = { addProactiveRequest(proactive?.rows("signals").orEmpty()) }, enabled = !savingProactive && !proactiveChoicesChanged && ui.connected && vm.client != null && requests.size < 20, modifier = Modifier.testTag("proactive-add-request-settings")) { Text(uiText("Add a request")) }
                if (requests.size >= 20) Text(uiText("Delete a request to add another."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = ::runProactivePreview, enabled = !savingProactive && ui.connected && vm.client != null, modifier = Modifier.testTag("proactive-run-brief")) { Text(uiText("Run a brief now")) }
                TextButton(onClick = { showProactiveSetup = true }, enabled = !savingProactive && !proactiveChoicesChanged && ui.connected && vm.client != null, modifier = Modifier.testTag("proactive-change-setup")) { Text(uiText("Change setup")) }
            }
        }
    }
    if (showProactiveSetup && proactive != null) ProactiveSetupSheet(vm, profile, proactive, onDismiss = { showProactiveSetup = false }, onSaved = {
        if (vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.preferences.value.assistantProfile == profile) vm.storeData(proactiveKey, it)
    })
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
    if (pluginAbsent) Text(uiText("Calls need the Cypher plugin on your Hermes."), color=MaterialTheme.colorScheme.onSurfaceVariant)
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
                            draft=""
                            status=""
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
                        when(testVoiceProvider(provider,apiKey,model)) {
                            dev.hermes.android.voice.VoiceProviderTestResult.Success -> {
                                val saved=runCatchingCancellable { if(entered.isNotBlank()) withContext(Dispatchers.IO) { store.saveKey(provider,apiKey) };true }.getOrDefault(false)
                                if(saved) {
                                    draft=""
                                    keyMask=withContext(Dispatchers.IO) { store.maskedKey(provider).orEmpty() }
                                    catalogRefresh++
                                    status="Connection succeeded. The key is stored on this phone."
                                    onKeySaved()
                                } else status="Connection succeeded, but the key could not be saved."
                            }
                            dev.hermes.android.voice.VoiceProviderTestResult.KeyRejected -> status="The provider rejected this key. Check the key and account access."
                            dev.hermes.android.voice.VoiceProviderTestResult.Unreachable -> status="The provider could not be reached. Check your connection and try again."
                            dev.hermes.android.voice.VoiceProviderTestResult.ProviderError -> status="The provider responded with an error. Check the selected model or try again."
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
            TextButton(onClick={voiceMenu=true},enabled=!busy) { Text("Voice: " + selection.voice) }
            DropdownMenu(expanded=voiceMenu,onDismissRequest={voiceMenu=false}) {
                voices.distinct().forEach { voice ->
                    DropdownMenuItem(text={Text(voice)},onClick={
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
