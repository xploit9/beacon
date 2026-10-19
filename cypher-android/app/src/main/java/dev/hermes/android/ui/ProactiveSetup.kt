@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val proactiveSignalIds = listOf("email", "calendar", "tasks", "news")
private val proactiveSignalLabels = mapOf("email" to "Email", "calendar" to "Calendar", "tasks" to "Tasks", "news" to "News")
internal const val proactiveNone = "__none__"
private val proactiveHealthTime = DateTimeFormatter.ofPattern("HH:mm")
private fun JSONObject.proactiveString(key: String, default: String): String = opt(key)?.takeUnless { it == JSONObject.NULL }?.toString()?.ifBlank { default } ?: default

internal fun proactiveSelectionsFrom(proactive: JSONObject): JSONObject = JSONObject().apply {
    proactiveSignalIds.forEach { id ->
        val signal = proactive.rows("signals").firstOrNull { it.string("id") == id }
        put(id, signal?.opt("source")?.takeUnless { it == JSONObject.NULL } ?: JSONObject.NULL)
    }
}

internal fun proactiveScheduleValid(schedule: JSONObject): Boolean {
    val time = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
    return listOf("brief", "quiet_start", "quiet_end").all { time.matches(schedule.optString(it)) } &&
        schedule.proactiveString("wrap_up", proactiveNone).let { it == proactiveNone || time.matches(it) }
}

internal fun proactiveScheduleMatches(left: JSONObject, right: JSONObject?): Boolean = right != null &&
    listOf("brief", "wrap_up", "watch_minutes", "quiet_start", "quiet_end").all { key ->
        val a = left.opt(key)
        val b = right.opt(key)
        if (a == null || a == JSONObject.NULL || b == null || b == JSONObject.NULL) (a == null || a == JSONObject.NULL) && (b == null || b == JSONObject.NULL)
        else a.toString() == b.toString()
    }

internal fun proactiveSetupBody(
    selections: JSONObject,
    schedule: JSONObject,
    level: String,
    preferences: AppPreferences,
    enabled: Boolean,
    urgentDelivery: String = "notify",
): JSONObject = json("enabled" to enabled).put("signals", JSONObject().apply {
    proactiveSignalIds.forEach { id -> put(id, selections.opt(id)?.takeUnless { it == JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() && it != proactiveNone } ?: JSONObject.NULL) }
}).put("schedule", JSONObject().apply {
    put("brief", schedule.optString("brief", "08:00"))
    put("wrap_up", (if (schedule.has("wrap_up")) schedule.opt("wrap_up")?.takeUnless { it == JSONObject.NULL }?.toString() else "19:00")?.takeIf { it.isNotBlank() && it != proactiveNone } ?: JSONObject.NULL)
    put("watch_minutes", schedule.optInt("watch_minutes", 30))
    put("quiet_start", schedule.optString("quiet_start", "22:00"))
    put("quiet_end", schedule.optString("quiet_end", "07:00"))
}).put("level", level).put("urgent", json("delivery" to urgentDelivery)).apply {
    if (preferences.assistantModel.isNotBlank()) put("model", preferences.assistantModel)
    if (preferences.assistantProvider.isNotBlank()) put("provider", preferences.assistantProvider)
}

internal suspend fun saveProactiveSetup(
    vm: CypherViewModel,
    profile: String,
    selections: JSONObject,
    schedule: JSONObject,
    level: String,
    preferences: AppPreferences,
    enabled: Boolean,
    urgentDelivery: String = "notify",
): JSONObject {
    require(proactiveScheduleValid(schedule)) { "Use 24-hour time, for example 08:30." }
    val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.call(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile), method = "PUT", data = proactiveSetupBody(selections, schedule, level, preferences, enabled, urgentDelivery))
    } finally { client.closeNative() }
}

@Composable internal fun ProactiveScheduleChoices(
    schedule: JSONObject,
    level: String,
    timezone: String,
    enabled: Boolean = true,
    onChange: (JSONObject, String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val wrapUp = schedule.proactiveString("wrap_up", proactiveNone)
        Text("Times in $timezone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Use 24-hour time, like 08:30.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(schedule.optString("brief", "08:00"), { onChange(schedule.copy().put("brief", it.take(5)), level) }, label = { Text("Brief time") }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("proactive-brief-time"))
        Choice("Wrap-up", if (wrapUp == proactiveNone) "off" else "on", listOf("on", "off"), { value ->
            onChange(schedule.copy().put("wrap_up", if (value == "off") JSONObject.NULL else wrapUp.takeUnless { it == proactiveNone } ?: "19:00"), level)
        }, Modifier.testTag("proactive-wrap-up"), mapOf("on" to "Set time", "off" to "Off"), enabled)
        if (wrapUp != proactiveNone) OutlinedTextField(wrapUp, { onChange(schedule.copy().put("wrap_up", it.take(5)), level) }, label = { Text("Wrap-up time") }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("proactive-wrap-up-time"))
        Choice("Watch frequency", schedule.optInt("watch_minutes", 30).toString(), listOf("0", "5", "15", "30", "60"), { value -> onChange(schedule.copy().put("watch_minutes", value.toInt()), level) }, Modifier.testTag("proactive-watch-frequency"), mapOf("0" to "Off", "5" to "5 min", "15" to "15 min", "30" to "30 min", "60" to "1 hour"), enabled)
        if (schedule.optInt("watch_minutes") == 5) Text("5-minute checks use more model runs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("proactive-five-minute-cost-note"))
        OutlinedTextField(schedule.optString("quiet_start", "22:00"), { onChange(schedule.copy().put("quiet_start", it.take(5)), level) }, label = { Text("Quiet hours start") }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("proactive-quiet-start"))
        OutlinedTextField(schedule.optString("quiet_end", "07:00"), { onChange(schedule.copy().put("quiet_end", it.take(5)), level) }, label = { Text("Quiet hours end") }, singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = Modifier.fillMaxWidth().testTag("proactive-quiet-end"))
        Choice("How much should I interrupt you?", level, listOf("urgent", "useful", "all"), { onChange(schedule, it) }, Modifier.testTag("proactive-interruption-level"), mapOf("urgent" to "Only urgent", "useful" to "Useful", "all" to "Everything"), enabled)
    }
}

@Composable internal fun ProactiveSourceInfo(sources: List<JSONObject>, selected: String, id: String) {
    val source = sources.firstOrNull { it.string("id") == selected } ?: return
    if (source.string("detail").isNotBlank()) Text(source.string("detail"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    val caution = listOfNotNull("Can make changes".takeIf { !source.optBoolean("read_only") }, "Needs your computer".takeIf { source.optBoolean("needs_terminal") }).joinToString(" · ")
    if (caution.isNotBlank()) Text(caution, Modifier.testTag("proactive-source-caution-$id"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
}

internal fun openProactiveRequestForm(vm: CypherViewModel, profile: String, signals: List<JSONObject>, onCreated: () -> Unit) {
    val anySignal = "__any__"
    val labels = signals.associate { it.string("id") to it.string("label", it.string("id")) } + (anySignal to "Any signal")
    vm.openForm(ActionForm(
        "Add a request", "", listOf(
            Field("text", "Your request", FieldType.MULTILINE, required = true),
            Field("action", "When it happens", FieldType.CHOICE, options = listOf("notify", "call"), optionLabels = mapOf("notify" to "Notify", "call" to "Call")),
            Field("signal", "Signal", FieldType.CHOICE, options = listOf(anySignal) + proactiveSignalIds, optionLabels = labels),
            Field("night", "Even at night", FieldType.BOOLEAN),
        ),
        seed = json("action" to "notify", "signal" to anySignal, "night" to false),
        explanation = "Describe when Cypher should notify or call you.",
        actionLabel = "Add request",
        onResult = { form ->
            val text = form.string("text").trim()
            require(text.codePointCount(0, text.length) <= 300) { "This request is too long. Shorten it and try again." }
            val body = json("text" to text, "action" to form.string("action"), "night" to form.optBoolean("night"))
            form.string("signal").takeIf { it != anySignal }?.let { body.put("signal", it) }
            val source = vm.client ?: throw IllegalStateException("Reconnect before adding a request.")
            val client = source.forAuthorizedProfile(profile)
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/requests", mapOf("profile" to profile), method = "POST", data = body)
                vm.notice("Request added.")
                onCreated()
            } finally { client.closeNative() }
        },
    ))
}

@Composable internal fun ProactiveSetupSheet(
    vm: CypherViewModel,
    profile: String,
    initial: JSONObject,
    onDismiss: () -> Unit,
    onSaved: (JSONObject) -> Unit,
) {
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()
    val signals = initial.rows("signals")
    val setupIdentity = signals.joinToString("|") { signal -> "${signal.string("id")}:${signal.optString("source")}:${signal.rows("sources").joinToString(",") { it.string("id") }}" } +
        "|${initial.optJSONObject("schedule")?.toString()}|${initial.string("level")}|${initial.optJSONObject("urgent")?.toString()}"
    var selections by remember(setupIdentity) {
        mutableStateOf(JSONObject().apply {
            proactiveSignalIds.forEach { id ->
                put(id, signals.firstOrNull { it.string("id") == id }?.proactiveString("source", proactiveNone) ?: proactiveNone)
            }
        })
    }
    var schedule by remember(setupIdentity) { mutableStateOf(initial.optJSONObject("schedule")?.copy() ?: json("brief" to "08:00", "wrap_up" to "19:00", "watch_minutes" to 30, "quiet_start" to "22:00", "quiet_end" to "07:00")) }
    var level by remember(setupIdentity) { mutableStateOf(initial.string("level", "useful")) }
    val urgentDelivery = initial.optJSONObject("urgent")?.string("delivery").orEmpty().ifBlank { "notify" }
    var step by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun save(enabled: Boolean, preview: Boolean = false) {
        if (saving) return
        saving = true
        error = ""
        coroutineScope.launch {
            try {
                val saved = saveProactiveSetup(vm, profile, selections, schedule, level, prefs, enabled, urgentDelivery)
                onSaved(saved)
                if (preview) {
                    if (vm.client?.scope?.origin != ui.origin || vm.client?.scope?.owner != ui.owner || vm.preferences.value.assistantProfile != profile) return@launch
                    val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
                    val client = source.forAuthorizedProfile(profile)
                    try { client.call("$PROACTIVE_SETTINGS_PATH/preview", mapOf("profile" to profile), method = "POST", data = JSONObject()) } finally { client.closeNative() }
                } else if (enabled) onDismiss()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = (failure as? ApiFailure)?.takeIf { it.status == 409 }?.message ?: failure.message ?: "Couldn’t update Proactive. Try again."
            } finally { saving = false }
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = sheetState, modifier = Modifier.testTag("proactive-setup-sheet")) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(listOf("What should I watch?", "When?", "Try it")[step], Modifier.testTag("proactive-setup-title"), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.testTag("proactive-setup-close")) { Text("Close") }
            }
            when (step) {
                0 -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    proactiveSignalIds.forEach { id ->
                        val signal = signals.firstOrNull { it.string("id") == id }
                        val label = proactiveSignalLabels.getValue(id)
                        val sources = signal?.rows("sources").orEmpty()
                        if (sources.isEmpty()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("Not connected", Modifier.testTag("proactive-no-source-$id"), style = MaterialTheme.typography.titleSmall)
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text("Connect it on your computer", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            val available = listOf(proactiveNone) + sources.map { it.string("id") }
                            val selected = selections.opt(id)?.takeUnless { it == JSONObject.NULL }?.toString() ?: proactiveNone
                            val options = if (selected in available) available else listOf(selected) + available
                            val labels = sources.associate { it.string("id") to it.string("label", it.string("id")) } + (proactiveNone to "Don’t watch") + (if (selected in available) emptyMap() else mapOf(selected to "Saved source unavailable"))
                            Choice(label, selected, options, { value -> selections = selections.copy().put(id, value) }, Modifier.testTag("proactive-signal-choice-$id"), labels)
                            ProactiveSourceInfo(sources, selected, id)
                        }
                    }
                }
                1 -> Column {
                    ProactiveScheduleChoices(schedule, level, initial.string("timezone").ifBlank { "your time zone" }) { value, selectedLevel ->
                        schedule = value
                        level = selectedLevel
                    }
                    TextButton(onClick = {
                        openProactiveRequestForm(vm, profile, signals) {
                            // The app's client can be another profile (Assistant settings), so refresh through this profile.
                            coroutineScope.launch {
                                val source = vm.client ?: return@launch
                                runCatchingCancellable {
                                    val client = source.forAuthorizedProfile(profile)
                                    try { client.get(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile)) } finally { client.closeNative() }
                                }.onSuccess(onSaved)
                            }
                        }
                    }, enabled = initial.rows("requests").size < 20, modifier = Modifier.testTag("proactive-add-request-setup")) { Text("Add a request") }
                    if (initial.rows("requests").size >= 20) Text("Delete a request to add another.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text("Run a brief now to see what it feels like. The result will arrive as a notification.", Modifier.testTag("proactive-preview-note"), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { save(enabled = initial.optBoolean("enabled"), preview = true) }, enabled = !saving, modifier = Modifier.testTag("proactive-run-brief")) { Text(if (saving) "Working…" else "Run a brief now") }
                    Button(onClick = { save(enabled = true) }, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("proactive-turn-on")) { Text(if (saving) "Saving…" else "Turn on") }
                }
            }
            if (error.isNotBlank()) Text(error, Modifier.testTag("proactive-setup-error"), color = MaterialTheme.colorScheme.error)
            if (step < 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Button(onClick = { step++ }, modifier = Modifier.testTag("proactive-setup-next")) { Text("Next") }
            }
        }
    }
}

@Composable internal fun ProactiveSignalHealth(signal: JSONObject, timezone: String = "", modifier: Modifier = Modifier) {
    val health = signal.optJSONObject("health") ?: JSONObject()
    val state = health.string("state", "unknown")
    val checked = health.optLong("checked_at")
    val text = when (state) {
        "ok" -> if (checked > 0) "Checked ${Instant.ofEpochSecond(checked).atZone(runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.systemDefault())).format(proactiveHealthTime)}" else "Checked"
        "error" -> health.string("message").ifBlank { "Connection needs attention" } + " · Reconnect on your computer"
        else -> "Not checked yet"
    }
    Text(text, modifier.testTag("proactive-health-${signal.string("id")}"), style = MaterialTheme.typography.bodySmall, color = if (state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
