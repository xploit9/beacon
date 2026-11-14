package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.beaconOwnerSetupMessage
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.isMissingBeaconPluginRoute
import dev.hermes.android.phone.isolateBidi
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val proactiveTimePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
private val proactiveHealthTime = DateTimeFormatter.ofPattern("HH:mm")

/** The plain words for a failed watch check (Ponytail review 1134, N2): a cause only when the note names it unambiguously. */
internal const val WATCH_SOURCE_SIGN_IN = "A connected source needs you to sign in again in Hermes."
internal const val WATCH_SOURCE_NO_ANSWER = "A source did not answer."
internal const val WATCH_CHECK_FAILED = "Couldn't check. Trying again next time."
/** A failed setup, shown under the Refresh action, so it does not promise a retry. */
internal const val WATCH_SETUP_FAILED = "Setup didn't finish. Refresh to see its status."
/** The failed watch check's action: it reads the status again, so it is labelled for that. */
internal const val PROACTIVE_FAILURE_ACTION = "Refresh"

internal fun proactiveSettingsBody(
    enabled: Boolean,
    delivery: String,
    checkMinutes: Int,
    quietStart: String,
    quietEnd: String,
    preferences: AppPreferences,
    /** The watch's chosen model as a picker key ("provider\tmodel"); blank, or the assistant's own model, is "Same as assistant". */
    watchModelKey: String = "",
): JSONObject = json(
    "enabled" to enabled,
    "delivery" to delivery,
    "check_minutes" to checkMinutes,
    "quiet_start" to quietStart,
    "quiet_end" to quietEnd,
).apply {
    require(proactiveSettingsValid(this)) { "Choose a valid delivery, check interval, and quiet-hours time." }
    // model stays the assistant's: nightly memory upkeep keeps it, and the watch uses it until it has a model of its own.
    if (preferences.assistantModel.isNotBlank()) put("model", preferences.assistantModel)
    if (preferences.assistantProvider.isNotBlank()) put("provider", preferences.assistantProvider)
    // The watch's own model. "Same as assistant" saves blank, so the watch follows the assistant's model.
    val (watchModel, watchProvider) = proactiveModelFromKey(proactiveWatchModelChoice(watchModelKey, proactiveModelKey(preferences.assistantModel, preferences.assistantProvider)))
    put("watch_model", watchModel)
    put("watch_provider", watchProvider)
}

/** A model and its provider as one picker key ("provider\tmodel"); blank when there is no model, which is "Same as assistant". */
internal fun proactiveModelKey(model: String, provider: String): String = if (model.isBlank()) "" else "$provider\t$model"

/** The model and provider a picker key stands for, as (model, provider); both blank for "Same as assistant". */
internal fun proactiveModelFromKey(key: String): Pair<String, String> =
    if (!key.contains('\t')) "" to "" else key.substringAfter('\t') to key.substringBefore('\t')

/** The row's choice for a saved model: "Same as assistant" (blank) when the saved model is the assistant's own, else the saved key. */
internal fun proactiveWatchModelChoice(savedKey: String, assistantKey: String): String = if (savedKey == assistantKey) "" else savedKey

/** The watch row's readable options from the profile's model list, keyed like the picker ("provider\tid"): the provider, then the model's name. */
internal fun proactiveModelLabels(groups: List<JSONObject>): Map<String, String> = groups.flatMap { group ->
    val provider = group.string("provider_id")
    val providerLabel = shortProviderLabel(group.string("provider", group.string("label", provider)))
    group.rows("models").map { model ->
        val id = model.string("id")
        proactiveModelKey(id, provider) to "$providerLabel · ${modelTitle(model.string("label"), id)}"
    }
}.toMap()

/**
 * What the watch model row reads for each choice: [sameAsAssistant] for blank, each listed model as "provider · name", and a saved pick the list does not
 * hold (not loaded yet, or no longer offered) by its own readable name, so the row never shows a raw id.
 */
internal fun proactiveWatchModelLabels(sameAsAssistant: String, listed: Map<String, String>, choice: String): Map<String, String> =
    mapOf("" to sameAsAssistant) + listed +
        (choice.takeIf { it.isNotBlank() && it !in listed }?.let { mapOf(it to modelTitle("", proactiveModelFromKey(it).first)) }.orEmpty())

internal fun proactiveSettingsValid(settings: JSONObject): Boolean =
    settings.string("delivery") in setOf("notify", "call") &&
        settings.optInt("check_minutes", -1) in setOf(5, 15, 30) &&
        proactiveTimePattern.matches(settings.optString("quiet_start")) &&
        proactiveTimePattern.matches(settings.optString("quiet_end"))

internal fun proactiveCallsUnavailable(proactive: JSONObject?, delivery: String, callbacksAvailable: Boolean): Boolean {
    if (callbacksAvailable) return false
    if (delivery == "call") return true
    val rules=proactive?.optJSONArray("rules") ?: return false
    return (0 until rules.length()).any { rules.optJSONObject(it)?.string("action") == "call" }
}

internal enum class ProactiveHealthState { Checking, Ok, Quiet, NotYet, Failed }

internal fun proactiveHealthState(proactive: JSONObject?, checking: Boolean): ProactiveHealthState = when {
    checking -> ProactiveHealthState.Checking
    proactive?.optJSONObject("health")?.string("state") == "ok" -> ProactiveHealthState.Ok
    proactive?.optJSONObject("health")?.string("state") == "error" -> ProactiveHealthState.Failed
    // The watch rests in quiet hours; the plugin says so instead of letting the last check look overdue.
    proactive?.optJSONObject("health")?.string("state") == "quiet" -> ProactiveHealthState.Quiet
    else -> ProactiveHealthState.NotYet
}

/**
 * When the watch last checked, in Hermes' zone and the phone's clock (12 or 24 hours, its locale); null before the first check.
 * An old check carries its day, so an outage of days never reads as this afternoon.
 */
internal fun proactiveLastCheck(proactive: JSONObject?, is24Hour: Boolean = true, locale: Locale = Locale.getDefault()): String? {
    val checkedAt = proactive?.optJSONObject("health")?.optLong("checked_at") ?: return null
    if (checkedAt <= 0) return null
    val timezone = runCatching { ZoneId.of(proactive.string("timezone")) }.getOrDefault(ZoneId.systemDefault())
    val checked = Instant.ofEpochSecond(checkedAt).atZone(timezone)
    val clock = nativeClockPattern(locale, is24Hour)
    val time = runCatching { checked.format(DateTimeFormatter.ofPattern(clock, locale)) }.getOrElse { checked.format(proactiveHealthTime) }
    return if (checked.toLocalDate() == java.time.LocalDate.now(timezone)) time
    else runCatching { checked.format(DateTimeFormatter.ofPattern("d MMM, $clock", locale)) }.getOrElse { "${checked.format(DateTimeFormatter.ofPattern("d MMM", locale))}, $time" }
}

/**
 * Why the last watch check failed, in plain words: the sentence for a known kind of failure, else a generic one. The watch's
 * note is free text the model wrote, so its own words never show (Ponytail review 1134, finding 8). Blank unless the check failed.
 */
internal fun proactiveFailureReason(proactive: JSONObject?): String {
    val health = proactive?.optJSONObject("health")?.takeIf { it.string("state") == "error" } ?: return ""
    // A failed setup writes no check time; its message is for the plugin, not the owner.
    if (health.optLong("checked_at") <= 0) return WATCH_SETUP_FAILED
    return proactiveNoteFailureKind(health.string("message"))
}

/**
 * The plain sentence for a failed check's note. A cause is named only when the note names it unambiguously: a sign-in that
 * has expired or a source that did not answer. Anything else, a 403 included (it can mean a missing permission), gets the generic one.
 */
internal fun proactiveNoteFailureKind(note: String): String {
    val words = note.lowercase()
    return when {
        // A status code counts only in an error form (HTTP 401, status 503, 401 Unauthorized): a bare number is a count.
        Regex("sign.?in (?:has )?expired|sign.?in again|log.?in again|re-?auth|token (?:has )?expired|invalid_grant|" +
            "(?:http|status|error|code)[ :]+401\\b|\\b401 unauthori[sz]ed")
            .containsMatchIn(words) -> WATCH_SOURCE_SIGN_IN
        Regex("timed? ?out|unreachable|did not respond|no response|connection refused|" +
            "(?:http|status|error|code)[ :]+50[234]\\b|\\b50[234] (?:bad gateway|service unavailable|gateway timeout)")
            .containsMatchIn(words) -> WATCH_SOURCE_NO_ANSWER
        else -> WATCH_CHECK_FAILED
    }
}

/** How a watch state reads as a Settings status: checking moves, OK is good, quiet and not-yet are off, a failed check is an error. */
internal fun proactiveHealthStatus(state: ProactiveHealthState): SettingsStatus = when (state) {
    ProactiveHealthState.Checking -> SettingsStatus.Working
    ProactiveHealthState.Ok -> SettingsStatus.Ok
    ProactiveHealthState.Quiet, ProactiveHealthState.NotYet -> SettingsStatus.Off
    ProactiveHealthState.Failed -> SettingsStatus.Error
}

@Composable internal fun ProactiveHealthChip(state: ProactiveHealthState, modifier: Modifier = Modifier) {
    SettingsStatusPill(proactiveHealthStatus(state), when (state) {
        ProactiveHealthState.Checking -> uiText("Checking")
        ProactiveHealthState.Ok -> uiText("OK")
        ProactiveHealthState.Quiet -> uiText("Quiet hours")
        // Beside an ON switch, "Not yet" read as "not turned on yet"; this says the watch has not looked, and "Checked {0}" takes over after a run.
        ProactiveHealthState.NotYet -> uiText("Hasn't checked yet")
        ProactiveHealthState.Failed -> uiText("Couldn't check")
    }, modifier, testTag = "proactive-health-${state.name.lowercase()}")
}

/** Only a failed watch check shows its reason and Retry; every other state shows neither. */
internal fun proactiveFailureDetailShown(state: ProactiveHealthState): Boolean = state == ProactiveHealthState.Failed

/** The reason a failed check shows, trimmed; blank for any other state or when the check gave no reason. */
internal fun proactiveFailureReasonShown(state: ProactiveHealthState, reason: String): String =
    if (proactiveFailureDetailShown(state)) reason.trim() else ""

/** A failed watch check's plain reason, with Refresh to read the status again; nothing for any other state. */
@Composable internal fun ProactiveFailureDetail(state: ProactiveHealthState, reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (!proactiveFailureDetailShown(state)) return
    val shown = proactiveFailureReasonShown(state, reason)
    Column(modifier.testTag("proactive-failure-detail"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (shown.isNotBlank()) Text(uiText(shown), Modifier.testTag("proactive-failure-reason"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onRetry, Modifier.heightIn(min = 48.dp).testTag("proactive-failure-retry")) { Text(uiText(PROACTIVE_FAILURE_ACTION)) }
    }
}

/** The plugin refuses until Hermes has saved the assistant's chat, which happens at its first message; the error is built with the plugin's sentence already mapped. */
private val noAssistantChatYet = setOf("Choose an owned assistant conversation before enabling Proactive", "Open your assistant's chat first.")

internal fun proactiveErrorMessage(error: Throwable, fallback: String): String =
    beaconOwnerSetupMessage(error)
        ?: if ((error as? ApiFailure)?.message in noAssistantChatYet)
            "Send ${dev.hermes.android.data.currentAssistantName} a message first, then turn this on." else null
        ?: if (isMissingBeaconPluginRoute(error)) "Update Beacon on Hermes to use this."
        else (error as? ApiFailure)?.message?.takeIf(String::isNotBlank) ?: error.message?.takeIf(String::isNotBlank) ?: fallback

internal fun proactiveLearnedNotes(proactive: JSONObject): List<String> {
    val notes = proactive.optJSONArray("learned") ?: return emptyList()
    return List(notes.length()) { notes.optString(it) }.filter(String::isNotBlank)
}

internal fun proactiveSources(proactive: JSONObject): List<String> {
    val sources = proactive.optJSONArray("sources") ?: return emptyList()
    return (0 until sources.length()).mapNotNull { index ->
        (sources.opt(index) as? String)?.trim()?.takeIf(String::isNotBlank)
    }.distinct()
}

/** One connected source and whether the watch reads it. */
internal data class ProactiveSource(val name: String, val watched: Boolean)

/** What the Sources sheet shows: every connected source, and whether "All sources" (the default, new sources included) is on. */
internal data class ProactiveSourcesView(val sources: List<ProactiveSource>, val all: Boolean)

/** The sources Hermes lets the owner choose from, or null when its plugin is older and sends only plain labels. */
internal fun proactiveSourceChoices(proactive: JSONObject): List<ProactiveSource>? {
    val rows = proactive.optJSONArray("source_choices") ?: return null
    return (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val name = (row.opt("name") as? String)?.trim()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        // A source nobody turned off is watched.
        ProactiveSource(name, row.optBoolean("watched", true))
    }.distinctBy { it.name }
}

/** The sheet's state: what Hermes sent, with the owner's latest tap over it until the save answers. [pick] is the chosen names; empty means all. */
internal fun proactiveSourcesView(proactive: JSONObject, pick: List<String>? = null): ProactiveSourcesView? {
    val sources = proactiveSourceChoices(proactive) ?: return null
    if (pick == null) return ProactiveSourcesView(sources, all = proactive.optBoolean("sources_all", true))
    return ProactiveSourcesView(sources.map { it.copy(watched = pick.isEmpty() || it.name in pick) }, all = pick.isEmpty())
}

internal fun proactiveSourceName(name: String): String = ruleSourceWords(name) ?: name

/** The row's value: "All sources", or the first two chosen names and a count of the rest. */
internal fun proactiveWatchSummary(view: ProactiveSourcesView, allLabel: String): String =
    if (view.all) allLabel else proactiveSourcesSummary(view.sources.filter { it.watched }.map { proactiveSourceName(it.name) })

/** The names to save after "All sources" is switched; an empty list is all of them, and switching it off names every source to start from. */
internal fun proactiveSourcesAfterAll(view: ProactiveSourcesView, on: Boolean): List<String> = if (on) emptyList() else view.sources.map { it.name }

/**
 * The names to save after one source is switched, or null when the tap changes nothing. With All sources on every source shows on,
 * and switching one off keeps all the others; switching one on changes nothing then. The last one watched stays on.
 */
internal fun proactiveSourcesAfterToggle(view: ProactiveSourcesView, name: String, on: Boolean): List<String>? {
    if (view.sources.none { it.name == name }) return null
    if (view.all) return if (on) null else view.sources.filter { it.name != name }.map { it.name }.takeIf { it.isNotEmpty() }
    return view.sources.filter { if (it.name == name) on else it.watched }.map { it.name }.takeIf { it.isNotEmpty() }
}

internal fun proactiveSourcesBody(names: List<String>): JSONObject = json("sources" to org.json.JSONArray(names))

internal suspend fun saveProactiveSources(vm: BeaconViewModel, profile: String, names: List<String>): JSONObject {
    val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.call(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile), method = "PUT", data = proactiveSourcesBody(names))
    } finally { client.closeNative() }
}

internal suspend fun saveProactiveSettings(vm: BeaconViewModel, profile: String, body: JSONObject): JSONObject {
    require(proactiveSettingsValid(body)) { "Choose a valid delivery, check interval, and quiet-hours time." }
    val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.call(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile), method = "PUT", data = body)
    } finally { client.closeNative() }
}

internal suspend fun fetchProactiveSettings(source: BeaconClient, profile: String): JSONObject {
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.get(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile))
    } finally { client.closeNative() }
}

/** A stored "HH:mm" as the phone writes a time of day (24-hour or 12-hour); anything that does not parse stays as it is. */
internal fun proactiveClockLabel(value: String, pattern: String, locale: Locale = Locale.getDefault()): String =
    runCatching { LocalTime.parse(value).format(DateTimeFormatter.ofPattern(pattern, locale)) }.getOrDefault(value)

/** Beacon's check-in calls come between these times of day in the profile's clock (the plugin's checkups window). */
private const val CHECKIN_WINDOW_START = "10:00"
private const val CHECKIN_WINDOW_END = "22:00"

/** The check-in window ("10:00–22:00" or "10:00 AM–10:00 PM") written the way the phone writes times, so it reads like the quiet hours beside it. */
internal fun checkinWindowLabel(is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
    val pattern = nativeClockPattern(locale, is24Hour)
    return isolateBidi(proactiveClockLabel(CHECKIN_WINDOW_START, pattern, locale) + "–" + proactiveClockLabel(CHECKIN_WINDOW_END, pattern, locale))
}

/** A translated sentence that names the window as "10:00" and "22:00", with those two times written the way the phone writes them. */
internal fun checkinSentenceInPhoneClock(sentence: String, is24Hour: Boolean, locale: Locale = Locale.getDefault()): String {
    val pattern = nativeClockPattern(locale, is24Hour)
    // A time the phone writes the same way stays as the sentence has it; a changed one is isolated so "10:00 PM" keeps its order in right-to-left text.
    fun written(time: String) = proactiveClockLabel(time, pattern, locale).let { if (it == time) it else isolateBidi(it) }
    return sentence.replace(CHECKIN_WINDOW_START, written(CHECKIN_WINDOW_START)).replace(CHECKIN_WINDOW_END, written(CHECKIN_WINDOW_END))
}

/**
 * A quiet-hours time as a Settings row whose value is the time the way the phone writes it; a tap opens the clock. [tagLabel] keeps
 * the row's test tags (`proactive-time-<tagLabel>`, `proactive-time-confirm-<tagLabel>`) fixed whatever the visible label says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProactiveTimePicker(
    label: String,
    value: String,
    enabled: Boolean,
    icon: ImageVector,
    tagLabel: String = label,
    onChange: (String) -> Unit,
) {
    var open by remember(label, value) { mutableStateOf(false) }
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val parts = value.split(':')
    val state = key(value) {
        rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 22,
            initialMinute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0,
            is24Hour = is24Hour,
        )
    }
    val time = proactiveClockLabel(value, nativeClockPattern(Locale.getDefault(), is24Hour))
    // Isolated, so "10:00 PM" keeps its order beside a right-to-left label instead of being reordered by it.
    SettingsNavRow(label, { open = true }, icon = icon, value = isolateBidi(time), enabled = enabled, testTag = "proactive-time-$tagLabel")
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onChange("%02d:%02d".format(state.hour, state.minute))
                open = false
            }, modifier = Modifier.testTag("proactive-time-confirm-$tagLabel")) { Text(uiText("OK")) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(uiText("Cancel")) } },
    )
}

internal fun openProactiveRuleForm(vm: BeaconViewModel, profile: String, explanation: String, onCreated: () -> Unit, onMissingPlugin: () -> Unit = {}, sources: List<String> = emptyList()) {
    vm.openForm(ActionForm(
        "Add a rule", "", listOf(
            Field("text", "Your rule", FieldType.MULTILINE, required = true),
            Field("signal", "Source", FieldType.CHOICE, options = listOf("anything") + sources,
                optionLabels = mapOf("anything" to "Anything") + sources.associateWith { ruleSourceWords(it) ?: it }),
            Field("action", "When it matches", FieldType.CHOICE, options = listOf("notify", "call"), optionLabels = mapOf("notify" to "Notify", "call" to "Call")),
            Field("night", "Even at night", FieldType.BOOLEAN),
        ),
        seed = json("signal" to "anything", "action" to "notify", "night" to false),
        explanation = explanation,
        actionLabel = "Add rule",
        onResult = onResult@{ form ->
            val text = form.string("text").trim()
            require(text.isNotBlank() && text.codePointCount(0, text.length) <= 300) { "Add a rule of 300 characters or fewer." }
            val source = vm.client ?: throw IllegalStateException("Reconnect before adding a rule.")
            val client = source.forAuthorizedProfile(profile)
            try {
                try {
                    client.call("$PROACTIVE_SETTINGS_PATH/requests", mapOf("profile" to profile), method = "POST", data = json(
                        "text" to text, "action" to form.string("action"), "night" to form.optBoolean("night"),
                        "signal" to form.string("signal").takeUnless { it.isBlank() || it == "anything" },
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (isMissingBeaconPluginRoute(error)) {
                        onMissingPlugin()
                        return@onResult
                    }
                    throw IllegalStateException(proactiveErrorMessage(error, "Couldn't add the rule. Try again."))
                }
                vm.notice("Rule added.")
                onCreated()
            } finally { client.closeNative() }
        },
    ))
}
