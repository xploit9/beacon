@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONObject
import dev.hermes.android.tasks.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
/** Every state [scheduleState] names; the card shows it through uiText. */
internal val ScheduleStateStrings = listOf("Paused", "Completed", "Running", "Failed", "Waiting", "Execution unconfirmed")

/** The phone's clock and language, with the app's own schedule words translated and carrying the assistant's name. */
@Composable internal fun rememberScheduleFormat(): ScheduleFormat {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val words = ScheduleBuilderStrings.associateWith { assistantText(it) }
    return remember(is24Hour, words) { ScheduleFormat(is24Hour, text = { words[it] ?: it }) }
}

/** The schedule the form would save, in words, under the "When" fields; nothing while it is incomplete. */
@Composable fun ScheduleSummary(values: Map<String, String>, timezone: String) {
    val format = rememberScheduleFormat()
    val state = scheduleStateFrom { values[it].orEmpty() }
    val built = buildScheduleString(state).takeIf { it.isNotBlank() && (state.mode != ScheduleMode.Weekly || state.weekdays.isNotEmpty()) } ?: return
    // Typed text that is no schedule shape comes back as typed; that tells the owner nothing.
    // A typed cron code the pickers cannot hold is still described, so the owner sees what it means before saving.
    val text = (if (looksLikeCron(built)) describeCron(built, format) else null)
        ?: describeSchedule(scheduleObject(built), built, timezone, format).takeIf { it != built } ?: return
    Text(if (timezone.isNotBlank() && state.mode != ScheduleMode.Every) "$text · $timezone" else text, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable fun TasksScreen(vm: BeaconViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(uiText("Schedules"), menu = LocalOpenNavigationMenu.current) { IconButton(onClick = { refreshSchedules(vm) }) { Icon(Icons.Rounded.Refresh, uiText("Refresh schedules")) } }
        Schedules(vm, Modifier.weight(1f).widthIn(max = 720.dp).fillMaxWidth()
            .align(androidx.compose.ui.Alignment.CenterHorizontally))
    }
}
/**
 * One schedule card's times, all in one clock. [zone] is the clock Next and Last are written in: the phone's, or Hermes' when the rule
 * cannot move to the phone's clock without changing its meaning ([zoneNamed]). [inHermesClock] is only the time of day in Hermes' clock
 * ("8:00 AM"; the card adds "in Lisbon"), kept for a second line only when the rule reads differently from [description].
 */
internal class ScheduleCardClock(val description: String, val inHermesClock: String?, val zone: java.time.ZoneId, val zoneLabel: String, val zoneNamed: Boolean)

/** Hermes' zone as people say it when no localized name is known: "Europe/Lisbon" is "Lisbon". */
internal fun scheduleZoneCity(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

/**
 * Hermes' zone named in the app's language ("لشبونة" in Arabic): the localized exemplar city that [exemplar] gives for the zone, else the IANA city.
 * [exemplar] is the ICU lookup, passed in so the rule runs without the Android runtime.
 */
internal fun scheduleZoneName(zoneId: String, exemplar: (String) -> String? = { null }): String =
    runCatching { exemplar(zoneId) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: scheduleZoneCity(zoneId)

/** The zone namer for [locale], on Android's ICU zone names (the exemplar location name, e.g. the city a zone is known by). */
internal fun scheduleZoneNamer(locale: java.util.Locale): (String) -> String {
    val names = runCatching { android.icu.text.TimeZoneNames.getInstance(locale) }.getOrNull()
    return { zoneId -> scheduleZoneName(zoneId) { names?.getExemplarLocationName(it) } }
}

/** The zone namer for the app language, which is the phone's language when the app has it (see [nativeLanguageFor]). */
@Composable internal fun rememberScheduleZoneNamer(): (String) -> String {
    val language = nativeLanguageFor(androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
    return remember(language) { scheduleZoneNamer(java.util.Locale.forLanguageTag(language)) }
}

/** [text] with Hermes' zone after it, through the phone-language template "{0} ({1})". */
internal fun scheduleZoneNamed(text: String, label: String, template: String): String = template.replace("{0}", text).replace("{1}", label)

/** The instant a card's rule is read at: the next run when there is one, so the description agrees with the Next line; else [now]. */
internal fun scheduleReference(nextRunAt: String, now: java.time.Instant): java.time.Instant =
    runCatching { java.time.OffsetDateTime.parse(nextRunAt).toInstant() }.getOrDefault(now)

/** A next run under an hour away reads "in 3 min" or "Due now" and carries no clock, so it needs no zone name. */
internal fun scheduleNextIsRelative(nextRunAt: String, now: java.time.Instant): Boolean =
    runCatching { java.time.Duration.between(now, java.time.OffsetDateTime.parse(nextRunAt).toInstant()).seconds < 3600 }.getOrDefault(true)

private sealed interface PhoneClock {
    /** The rule in the phone's clock. */
    class Moved(val schedule: JSONObject) : PhoneClock
    /** The rule holds no clock time, so it reads the same anywhere. */
    object Same : PhoneClock
    /** The rule has a clock time that cannot move (a day rule across midnight, a window that wraps): it stays in Hermes' clock. */
    object Fixed : PhoneClock
}

private val plainCronNumbers = Regex("\\d+(,\\d+)*")

// A star, a number or a-b, each with an optional step, and comma lists of those.
private fun cronNumbers(field: String, low: Int, high: Int): List<Int>? = field.split(',').flatMap { item ->
    val range = item.substringBefore('/')
    val gap = item.substringAfter('/', "1").toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val (from, to) = when {
        range == "*" -> low to high
        '-' in range -> (range.substringBefore('-').toIntOrNull() ?: return null) to (range.substringAfter('-').toIntOrNull() ?: return null)
        else -> if ('/' in item) return null else (range.toIntOrNull() ?: return null).let { it to it }
    }
    if (from < low || to > high || from > to) return null
    (from..to step gap).toList()
}.distinct().sorted().takeIf { it.isNotEmpty() }

/** A schedule object as the phone's clock reads it, read at [reference] so a daylight-saving change in either zone is the one that applies to that run. */
private fun moveToPhoneClock(schedule: JSONObject?, hermes: java.time.ZoneId, phone: java.time.ZoneId, reference: java.time.Instant): PhoneClock = if (schedule == null) PhoneClock.Same else when (schedule.string("kind")) {
    "once" -> {
        val runAt = schedule.string("run_at")
        // A timestamp without an offset is Hermes' wall clock; with the offset written in, the describer can read it in any zone.
        val at = runCatching { java.time.OffsetDateTime.parse(runAt) }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(runAt.take(19)).atZone(hermes).toOffsetDateTime() }.getOrNull()
        if (at == null) PhoneClock.Same else PhoneClock.Moved(JSONObject(schedule.toString()).put("run_at", at.toString()))
    }
    "cron" -> moveCronToPhoneClock(schedule.string("expr", schedule.string("expression")), hermes, phone, reference)
    else -> PhoneClock.Same
}

private fun moveCronToPhoneClock(expr: String, hermes: java.time.ZoneId, phone: java.time.ZoneId, reference: java.time.Instant): PhoneClock {
    // What the describer cannot put in words shows no clock time either way.
    if (describeCron(expr, ScheduleFormat()) == null) return PhoneClock.Same
    val p = expr.trim().split(Regex("\\s+")).takeIf { it.size == 5 } ?: return PhoneClock.Same
    val minutes = cronNumbers(p[0], 0, 59) ?: return PhoneClock.Same
    val hours = cronNumbers(p[1], 0, 23) ?: return PhoneClock.Same
    val date = reference.atZone(hermes).toLocalDate()
    val dayRule = p[2] != "*" || p[4] != "*"
    // Every time of day the rule fires, as the phone's clock reads it on the day of the reference run, and how many days that moves it.
    class Fired(val hour: Int, val minute: Int, val originalMinute: Int, val days: Long)
    val fired = hours.flatMap { h -> minutes.map { m ->
        val at = java.time.ZonedDateTime.of(date, java.time.LocalTime.of(h, m), hermes).withZoneSameInstant(phone)
        Fired(at.hour, at.minute, m, java.time.temporal.ChronoUnit.DAYS.between(date, at.toLocalDate()))
    } }
    val crossesMidnight = fired.any { it.days != 0L }
    val rest = "${p[2]} ${p[3]} ${p[4]}"
    val rewritten: String = when {
        // A few fixed times a day ("8:00", "9:00, 17:00"): each moves on its own.
        plainCronNumbers.matches(p[0]) && plainCronNumbers.matches(p[1]) && (minutes.size == 1 || hours.size == 1) -> {
            if (dayRule && crossesMidnight) return PhoneClock.Fixed
            val times = fired.map { it.hour to it.minute }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            when {
                times.map { it.second }.distinct().size == 1 -> "${times[0].second} ${times.map { it.first }.joinToString(",")} $rest"
                times.map { it.first }.distinct().size == 1 -> "${times.map { it.second }.joinToString(",")} ${times[0].first} $rest"
                else -> return PhoneClock.Fixed
            }
        }
        // Every few minutes all day names no time of day.
        hours.size == 24 && minutes.size > 1 -> return PhoneClock.Same
        // Hourly at a minute past: only the minute moves.
        hours.size == 24 -> {
            if (dayRule && crossesMidnight) return PhoneClock.Fixed
            "${fired.map { it.minute }.distinct().singleOrNull() ?: return PhoneClock.Fixed} * $rest"
        }
        // A window of hours moves as a whole, never wrapping past midnight and never off its minutes.
        else -> {
            if (crossesMidnight || fired.any { it.minute != it.originalMinute }) return PhoneClock.Fixed
            "${p[0]} ${fired.map { it.hour }.distinct().sorted().joinToString(",")} $rest"
        }
    }
    // A rewritten rule the describer cannot read would show less than the original did.
    if (describeCron(rewritten, ScheduleFormat()) == null) return PhoneClock.Fixed
    return PhoneClock.Moved(json("kind" to "cron", "expr" to rewritten))
}

/** Just the times of day [rule] fires at in [hermes]' clock: "8:00 AM", "9:00 AM, 5:00 PM" or the window "7:00 AM–10:00 PM"; null when it names none. */
private fun scheduleClockOnly(rule: JSONObject, hermes: java.time.ZoneId, f: ScheduleFormat): String? = when (rule.string("kind")) {
    "once" -> rule.string("run_at").let { runAt ->
        (runCatching { java.time.OffsetDateTime.parse(runAt).atZoneSameInstant(hermes).toLocalTime() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(runAt.take(19)).toLocalTime() }.getOrNull())?.let { f.clock(it.hour, it.minute) }
    }
    "cron" -> cronClockOnly(rule.string("expr", rule.string("expression")), f)
    else -> null
}

private fun cronClockOnly(expr: String, f: ScheduleFormat): String? {
    val p = expr.trim().split(Regex("\\s+")).takeIf { it.size == 5 } ?: return null
    val minutes = cronNumbers(p[0], 0, 59) ?: return null
    val hours = cronNumbers(p[1], 0, 23) ?: return null
    // A few fixed times a day, as the describer words them.
    if (plainCronNumbers.matches(p[0]) && plainCronNumbers.matches(p[1]) && (minutes.size == 1 || hours.size == 1) && minutes.size * hours.size <= 6)
        return hours.flatMap { h -> minutes.map { m -> f.clock(h, m) } }.joinToString(", ")
    if (hours.size == 24) return null
    // A window: several minutes an hour read from the first hour to the end of the last; one minute from its first run to its last.
    if (minutes.size == 1) return "${f.clock(hours.first(), minutes[0])}–${f.clock(hours.last(), minutes[0])}"
    val runs = hours.fold(mutableListOf<IntRange>()) { runs, h ->
        if (runs.isNotEmpty() && runs.last().last + 1 == h) runs[runs.lastIndex] = runs.last().first..h else runs.add(h..h)
        runs
    }
    return runs.joinToString(", ") { "${f.clock(it.first, 0)}–${f.clock(it.last + 1, 0)}" }
}

/**
 * A schedule card's rule and clock (UX-38): every time on the card in the phone's zone, Hermes' time of day only on a second line and only when it
 * differs, and a rule that cannot be converted kept whole in Hermes' zone, named. A blank or unknown [hermesZoneId] (no plugin) changes nothing.
 */
internal fun scheduleCardClock(schedule: JSONObject?, display: String, hermesZoneId: String, phone: java.time.ZoneId, reference: java.time.Instant, f: ScheduleFormat = ScheduleFormat(), zoneName: (String) -> String = ::scheduleZoneCity): ScheduleCardClock {
    val own = describeSchedule(schedule, display, hermesZoneId, f)
    val city = zoneName(hermesZoneId)
    val hermes = runCatching { java.time.ZoneId.of(hermesZoneId) }.getOrNull()
    if (hermes == null || hermes.rules.getOffset(reference) == phone.rules.getOffset(reference)) return ScheduleCardClock(own, null, phone, city, false)
    val rule = schedule?.takeIf { it.string("kind") in setOf("interval", "once", "cron") } ?: scheduleObject(display)
    return when (val moved = moveToPhoneClock(rule, hermes, phone, reference)) {
        PhoneClock.Same -> ScheduleCardClock(own, null, phone, city, false)
        PhoneClock.Fixed -> ScheduleCardClock(own, null, hermes, city, true)
        is PhoneClock.Moved -> describeSchedule(moved.schedule, "", phone.id, f).let { mine -> ScheduleCardClock(mine, if (own != mine) rule?.let { scheduleClockOnly(it, hermes, f) } else null, phone, city, false) }
    }
}

/** The phone's own words for "when it runs next", already translated. */
@Composable internal fun rememberScheduleWhen(): ScheduleWhenWords {
    val due = uiText("Due now"); val minutes = uiText("in {0} min"); val today = uiText("Today {0}"); val tomorrow = uiText("Tomorrow {0}")
    return remember(due, minutes, today, tomorrow) { ScheduleWhenWords(due, minutes, today, tomorrow) }
}

/** A schedule's state as a glyph-plus-word pill: Active (Waiting while the scheduler is stopped), Paused, Failed or Running. */
@Composable internal fun ScheduleStatusChip(job: JSONObject, modifier: Modifier = Modifier) {
    val chip = ScheduleChip.of(job)
    // Under a stopped scheduler a job would run but cannot: it waits, it is not Active (the banner above says why).
    val stalled = chip == ScheduleChip.Active && scheduleGatewayNeedsAttention(job)
    val status = if (stalled) SettingsStatus.Attention else when (chip) {
        ScheduleChip.Active -> SettingsStatus.Ok
        ScheduleChip.Paused, ScheduleChip.Completed -> SettingsStatus.Off
        ScheduleChip.Failed -> SettingsStatus.Error
        ScheduleChip.Running -> SettingsStatus.Working
        ScheduleChip.Unconfirmed -> SettingsStatus.Attention
    }
    val word = uiText(if (stalled) "Waiting" else when (chip) {
        ScheduleChip.Active -> "Active"
        ScheduleChip.Paused -> "Paused"
        ScheduleChip.Failed -> "Failed"
        ScheduleChip.Running -> "Running"
        ScheduleChip.Completed -> "Completed"
        ScheduleChip.Unconfirmed -> "Execution unconfirmed"
    })
    val reason = if (chip == ScheduleChip.Failed) scheduleFailureReason(job) else ""
    if (reason.isBlank()) SettingsStatusPill(status, word, modifier.testTag("schedule-status:${job.string("id")}"))
    else FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        SettingsStatusPill(status, word, Modifier.testTag("schedule-status:${job.string("id")}"))
        Text(reason, Modifier.testTag("schedule-failure:${job.string("id")}"), style = MaterialTheme.typography.bodySmall.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** A bracketed code, a snake_case identifier or a quoted id: words for the logs, not for the owner. */
private val technicalReason = Regex("""^\[[^\]]*\]|\b[a-z]+_[a-z_]+\b|'[^'\s]+'|"[^"\s]+"""")

/** The longest reason a failed schedule's chip shows. */
internal const val FAILURE_REASON_MAX = 60

/**
 * Why a failed schedule failed, short and in Hermes' own words: the first line of its last error. Blank when it did not fail, when Hermes gave no
 * reason, or when the failure is a delivery one (the card already shows that error under the schedule).
 */
internal fun scheduleFailureReason(job: JSONObject): String {
    if (scheduleState(job) != "Failed" || job.string("last_status") == "delivery_failed") return ""
    val line = job.string("last_error").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    // "[blocked_config] delivery platform 'phone' is ..." is for the logs: a bracketed code, snake_case or a quoted id leaves only the Failed chip.
    if (technicalReason.containsMatchIn(line)) return ""
    return if (line.length > FAILURE_REASON_MAX) line.take(FAILURE_REASON_MAX - 1).trimEnd() + "…" else line
}

/** Edit, run history, pause or resume, and delete (last, in red) behind one overflow button; each does what its icon button used to. */
@Composable internal fun ScheduleMenu(id: String, paused: Boolean, ended: Boolean, canEdit: Boolean, enabled: Boolean, onPause: () -> Unit, onEdit: () -> Unit, onHistory: (() -> Unit)?, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val error = MaterialTheme.colorScheme.error
    Box {
        SettingsIconButton(Icons.Rounded.MoreVert, uiText("Schedule actions"), { open = true }, Modifier.testTag("schedule-menu:$id"))
        DropdownMenu(open, { open = false }) {
            if (canEdit) DropdownMenuItem({ Text(uiText("Edit")) }, { open = false; onEdit() }, Modifier.testTag("schedule-action:$id:edit"), leadingIcon = { Icon(Icons.Rounded.Edit, null) }, enabled = enabled)
            if (onHistory != null) DropdownMenuItem({ Text(uiText("Run history")) }, { open = false; onHistory() }, Modifier.testTag("schedule-action:$id:history"), leadingIcon = { Icon(Icons.Rounded.History, null) })
            DropdownMenuItem({ Text(uiText(if (paused) "Resume" else "Pause")) }, { open = false; onPause() }, Modifier.testTag("schedule-action:$id:pause"),
                leadingIcon = { Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null) }, enabled = enabled && !ended)
            HorizontalDivider()
            DropdownMenuItem({ Text(uiText("Delete"), color = error) }, { open = false; onDelete() }, Modifier.testTag("schedule-action:$id:delete"), leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = error) }, enabled = enabled)
        }
    }
}

/** One labeled Run now: it opens the same confirmation the lightning-bolt icon did. */
@Composable internal fun ScheduleRunNow(id: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick, Modifier.heightIn(min = 48.dp).testTag("schedule-run:$id"), enabled = enabled) {
        Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)); Text(uiText("Run now"), Modifier.padding(start = 6.dp))
    }
}

/** A result that could not be delivered: the error glyph and Hermes' words, never colour alone. */
@Composable internal fun ScheduleDeliveryError(text: String) {
    val ink = rememberSettingsStatusPalette().error
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Icon(Icons.Rounded.Error, null, Modifier.size(16.dp).padding(top = 2.dp), tint = ink)
        Text(text, style = MaterialTheme.typography.bodySmall, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun Schedules(vm: BeaconViewModel, modifier: Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    var selected by rememberSaveable(ui.profile) { mutableStateOf("") }
    var showCompleted by rememberSaveable(ui.profile) { mutableStateOf(false) }
    val actionScope = rememberCoroutineScope()
    var pending by remember(ui.profile) { mutableStateOf("") }
    val crons = data["crons"]
    val gatewayNeedsAttention = crons?.rows("jobs").orEmpty().any { !schedulePaused(it) && scheduleGatewayNeedsAttention(it) }
    val (active, completed) = splitSchedules(crons?.rows("jobs").orEmpty())
    val refresh: () -> Unit = { refreshSchedules(vm) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { refresh(); if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, selected, crons) { if (selected.isNotBlank()) vm.load("cron-history:$selected", "/api/crons/history", mapOf("job_id" to selected)) }
    val capabilities = data["task-capabilities"]
    val timezone = capabilities?.string("timezone").orEmpty()
    val format = rememberScheduleFormat()
    val whenWords = rememberScheduleWhen()
    val phoneZone = remember { java.time.ZoneId.systemDefault() }
    val zoneName = rememberScheduleZoneNamer()
    // Read once per fresh list, so a row's relative time is as old as the data under it.
    val now = remember(crons) { java.time.Instant.now() }
    val deliveries = data["cron-delivery"]?.rows("targets").orEmpty()
    val deliveryWords = DeliveryNameStrings.associateWith { uiText(it) }
    val deleteTitle = uiText("Delete schedule")
    val deleteExplanation = uiText("This schedule will be removed.")
    val deleteAction = uiText("Delete")
    val delete: (String) -> Unit = { id -> vm.openForm(ActionForm(deleteTitle, "/api/crons/delete", emptyList(), json("job_id" to id), explanation = deleteExplanation, actionLabel = deleteAction, onResult = { refresh() }, destructive = true)) }
    val nextTemplate = uiText("Next: {0}")
    val ranTemplate = uiText("Ran {0}")
    val lastTemplate = uiText("Last: {0}")
    val notScheduled = uiText("Not scheduled")
    val noRun = uiText("No recorded run")
    val inZoneTemplate = uiText("{0} in {1}")
    val zoneNamedTemplate = uiText("{0} ({1})")
    Box(modifier.fillMaxSize()) {
    LazyColumn(Modifier.fillMaxSize().testTag("schedules-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gatewayNeedsAttention) item { Glass { SettingsErrorRow(uiText("Schedules aren't running. Start Hermes on your computer."), null, "") } }
        item { Button(onClick = { cronForm(vm, null, deliveries, timezone, refresh, format = format) }, Modifier.heightIn(min = 48.dp)) { Icon(Icons.Rounded.Add, null); Text(uiText("Create schedule"), Modifier.padding(start = 8.dp)) } }
        if (crons?.optBoolean("cron_unavailable") == true) item { EmptyState(uiText("Schedules are unavailable"), uiText("Schedules aren't enabled. Check the connection in Settings."), Icons.Rounded.EventBusy) }
        if (crons?.has("_error") == true) item { Glass { SettingsErrorRow(uiText("Couldn't load schedules. Check your network, then retry."), refresh, uiText("Retry")) } }
        if (crons == null) item { Glass { SettingsSkeletonRows(3) } }
        items(active, key = { it.string("id") }) { job ->
            val id = job.string("id")
            val callback = scheduleIsCallback(job)
            val paused = schedulePaused(job)
            val idle = pending.isBlank()
            Glass(Modifier.testTag("schedule-card:$id")) { Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 4.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(end = 0.dp), verticalAlignment = androidx.compose.ui.Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(scheduleTitle(job, uiText("Untitled schedule"), format.text("Callback")), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        ScheduleStatusChip(job)
                    }
                    ScheduleMenu(id, paused, scheduleEnded(job), !callback, idle,
                        onPause = { pending = id; actionScope.launch { pauseSchedule(vm, job, refresh).join(); pending = "" } },
                        onEdit = { cronForm(vm, job, deliveries, timezone, refresh, format = format) },
                        onHistory = { selected = if (selected == id) "" else id },
                        onDelete = { delete(id) })
                }
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // One clock for the whole card: the phone's, with Hermes' time on a second line when it differs.
                    val clock = scheduleCardClock(job.optJSONObject("schedule"), scheduleDisplay(job), timezone, phoneZone, scheduleReference(job.string("next_run_at"), now), format, zoneName)
                    val named: (String) -> String = { text -> if (clock.zoneNamed) scheduleZoneNamed(text, clock.zoneLabel, zoneNamedTemplate) else text }
                    scheduleBody(job, callbackReason(capabilities, id), format.text).takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium.contentDirection(), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    clock.description.takeIf(String::isNotBlank)?.let { Text(named(it), Modifier.testTag("schedule-description:$id"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    clock.inHermesClock?.let { Text(scheduleZoneNamed(it, clock.zoneLabel, inZoneTemplate), Modifier.testTag("schedule-hermes-clock:$id"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    val nextText = scheduleNextRun(job.string("next_run_at"), notScheduled, whenWords, now, clock.zone, format.is24Hour)
                    Text(nextTemplate.replace("{0}", if (scheduleNextIsRelative(job.string("next_run_at"), now)) nextText else named(nextText)), Modifier.testTag("schedule-next:$id"), style = MaterialTheme.typography.bodyMedium)
                    Text(job.string("last_run_at").takeIf(String::isNotBlank)?.let { lastTemplate.replace("{0}", named(scheduleTime(it, clock.zone.id, noRun, format.is24Hour))) } ?: noRun, Modifier.testTag("schedule-last:$id"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // A callback saves its result nowhere the owner picks, so its target is not worth a word.
                    listOfNotNull(profileDisplayLabel(job.string("profile", ui.profile), profileRows, profileLoading), deliveryLabel(job.string("deliver", "local"), deliveryNames(deliveries), deliveryWords).takeUnless { callback }).joinToString(" · ")
                        .takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    scheduleDeliveryError(job).takeIf { it.isNotBlank() }?.let { ScheduleDeliveryError(it) }
                    if (pending == id) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                ScheduleRunNow(id, idle) { runSchedule(vm, job, refresh) }
                if (selected == id) Column(Modifier.padding(end = 12.dp, bottom = 8.dp)) { RunHistory(vm, job, data["cron-history:$id"]) }
            } }
        }
        if (completed.isNotEmpty()) {
            item(key = "completed-schedules") { CompletedHeader(completed.size, showCompleted, format.text("Completed schedules")) { showCompleted = !showCompleted } }
            if (showCompleted) items(completed, key = { it.string("id") }) { job ->
                val id = job.string("id")
                CompletedScheduleRow(id, scheduleTitle(job, uiText("Untitled schedule"), format.text("Callback")), if (scheduleIsCallback(job)) callbackReason(capabilities, id) else "",
                    job.string("last_run_at").takeIf(String::isNotBlank)?.let { ranTemplate.replace("{0}", scheduleTime(it, phoneZone.id, "", format.is24Hour)) } ?: noRun,
                    onHistory = { selected = if (selected == id) "" else id }, historyOpen = selected == id, onDelete = { delete(id) }) { RunHistory(vm, job, data["cron-history:$id"]) }
            }
        }
        if (crons != null && !crons.has("_error") && crons.rows("jobs").isEmpty() && !crons.optBoolean("cron_unavailable")) item { EmptyState(uiText("Nothing scheduled yet"), assistantText("Choose what Beacon should do and when."), Icons.Rounded.EventNote) }
    }
    // A background refresh over rows already shown: a thin bar, never a blank page.
    if (crons != null && "crons" in refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(androidx.compose.ui.Alignment.TopCenter).testTag("schedules-refreshing"))
    }
}
/** One run per line, each opening the conversation it made. */
@Composable private fun RunHistory(vm: BeaconViewModel, job: JSONObject, history: JSONObject?) {
    if (history == null) SettingsSkeletonRows(2)
    else if (history.has("_error")) Text(uiText("Run history isn't available. Check your network, then retry."), color = MaterialTheme.colorScheme.error)
    else if (history.rows("runs").isEmpty()) Text(uiText("No recorded runs yet."))
    else history.rows("runs").forEach { run ->
        val session = run.string("session_id", run.string("id"))
        TextButton(enabled = session.isNotBlank(), onClick = { vm.tab("Chat"); vm.selectSession(session) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(run.string("title", uiText("Open result conversation")) + if (run.optBoolean("is_active")) " · ${uiText("Running")}" else "") }
    }
    if (job.optBoolean("no_agent")) Text(uiText("Script schedules don't create a chat per run."), style = MaterialTheme.typography.bodySmall)
}
/** "Completed schedules (3)": closed until opened. */
@Composable internal fun CompletedHeader(count: Int, expanded: Boolean, title: String, onToggle: () -> Unit) {
    TextButton(onClick = onToggle, modifier = Modifier.heightIn(min = 48.dp).testTag("completed-schedules")) { Text("$title ($count)"); Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
}
/** A finished one-time schedule in one compact row: what it was, when it ran, its results, and delete. [onHistory] is null where a place has no run history. */
@Composable internal fun CompletedScheduleRow(id: String, title: String, reason: String, ran: String, onHistory: (() -> Unit)?, historyOpen: Boolean, onDelete: () -> Unit, history: @Composable () -> Unit = {}) {
    Glass(Modifier.testTag("completed-schedule:$id")) { Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reason.isNotBlank()) Text(reason, style = MaterialTheme.typography.bodySmall.contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ran, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onHistory != null) IconButton(onClick = onHistory) { Icon(Icons.Rounded.History, uiText("Run history"), tint = if (historyOpen) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
            IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, uiText("Delete"), tint = MaterialTheme.colorScheme.error) }
        }
        if (historyOpen) Column(Modifier.padding(end = 12.dp)) { history() }
    } }
}
internal fun pauseSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit): Job =
    vm.action(if (schedulePaused(job)) "/api/crons/resume" else "/api/crons/pause", json("job_id" to job.string("id")), refresh = false) { _, _ -> refresh() }
internal fun runSchedule(vm: BeaconViewModel, job: JSONObject, refresh: () -> Unit) = vm.openForm(ActionForm("Run this schedule", "/api/crons/run", emptyList(), json("job_id" to job.string("id")), explanation = if (job.optBoolean("no_agent")) "Run this script once now." else "Run this schedule now with its saved tools and delivery.", actionLabel = "Run", onResult = { refresh() }))
/** `deliver` is where a new task's result goes; the thread sheet starts it on its own thread. [format] is the phone's clock and language, for the hour and date choices. */
internal fun cronForm(vm: BeaconViewModel, job: JSONObject?, targets: List<JSONObject>, timezone: String, refresh: () -> Unit, deliver: String = "local", format: ScheduleFormat = ScheduleFormat()) {
    val seed = job?.let(::schedulePayload) ?: json("deliver" to deliver)
    listOf("name", "prompt", "provider", "model").forEach { if (!seed.has(it)) seed.put(it, "") }
    if (!seed.has("skills")) seed.put("skills", org.json.JSONArray())
    val stored = job?.let(::nativeScheduleInput).orEmpty()
    // A job saved with no schedule opens as Custom and empty, so saving it never invents one.
    val state = when { job == null -> ScheduleBuilderState(); stored.isBlank() -> ScheduleBuilderState(ScheduleMode.Custom); else -> parseScheduleString(stored) }
    val today = runCatching { java.time.LocalDate.now(java.time.ZoneId.of(timezone)) }.getOrElse { java.time.LocalDate.now() }
    state.formSeed(today).forEach { (key, value) -> seed.put(key, value) }
    seed.put("_timezone", timezone)
    if (job != null) {
        seed.put("job_id", job.string("id"))
        seed.put("schedule", stored)
        seed.put("_original", seed.copy())
    }
    val names = deliveryNames(targets)
    val choices = targets.filter { it.string("id") == "local" || it.optBoolean("home_target_set") }.map { it.string("id") }.ifEmpty { listOf("local") }.toMutableList()
    seed.string("deliver").takeIf { it.isNotBlank() && it !in choices }?.let { choices.add(it) }
    // A target saved on the job but no longer listed (a deleted thread, an unset platform) keeps its place under a plain name.
    val labels = names + targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.associate { it.string("id") to names.getValue(it.string("id")) + " (not configured)" } +
        choices.filter { it !in names }.associateWith { deliveryLabel(it, names) }
    val missing = targets.filter { it.string("id") != "local" && !it.optBoolean("home_target_set") }.joinToString { it.string("name") + ": configure " + it.string("home_env_var") }
    // The next 90 days, and the saved day when it falls outside them.
    val days = ((0L..90L).map { today.plusDays(it).toString() } + state.onceDate).filter { runCatching { java.time.LocalDate.parse(it) }.isSuccess }.distinct().sorted()
    val dayLabels = days.associateWith { format.fullDay(java.time.LocalDate.parse(it)) }
    val minutes = ((0..59 step 5) + state.minute).distinct().sorted().map(Int::toString)
    val weekdays = (1..7).map { it % 7 }.map { day -> Field("_wd_$day", format.weekday(day, java.time.format.TextStyle.FULL_STANDALONE).replaceFirstChar { it.titlecase(format.locale) }, FieldType.BOOLEAN, section = "When") }
    val fields = listOf(Field("name", "Schedule name"), Field("prompt", "What should ${dev.hermes.android.data.currentAssistantName} do?", FieldType.MULTILINE, true),
        Field("_schedule_mode", "How often", FieldType.CHOICE, options = ScheduleMode.entries.map { it.value }, optionLabels = ScheduleMode.entries.associate { it.value to it.label }, section = "When"),
        Field("_once_date", "Date", FieldType.CHOICE, options = days, optionLabels = dayLabels, section = "When"),
        Field("_every_n", "Every", FieldType.INTEGER, section = "When"), Field("_every_unit", "Unit", FieldType.CHOICE, options = listOf("minutes", "hours", "days"), optionLabels = mapOf("minutes" to "minutes", "hours" to "hours", "days" to "days"), section = "When")) +
        weekdays + listOf(Field("_dom", "Day of the month", FieldType.CHOICE, options = ((1..28) + state.dayOfMonth).distinct().sorted().map(Int::toString), section = "When"),
        Field("_hour", "Hour", FieldType.CHOICE, options = (0..23).map(Int::toString), optionLabels = (0..23).associate { it.toString() to format.hour(it) }, section = "When"),
        Field("_minute", "Minute", FieldType.CHOICE, options = minutes, optionLabels = minutes.associateWith { it.padStart(2, '0') }, section = "When"),
        Field("_start_hour", "Start hour", FieldType.CHOICE, options = (0..23).map(Int::toString), section = "When"), Field("_end_hour", "End hour", FieldType.CHOICE, options = (1..24).map(Int::toString), section = "When"),
        Field("_every_hours", "Every (hours)", FieldType.CHOICE, options = listOf("1", "2", "3", "4", "6", "12"), section = "When"), Field("_include_end", "Include the end hour", FieldType.BOOLEAN, help = "Off stops before the end hour.", section = "When"),
        Field("schedule", "When it runs", help = "For example: every 2h or weekdays at 9am", section = "When"),
        Field("deliver", "Deliver to", FieldType.CHOICE, options = choices, optionLabels = labels, help = missing), Field("provider", "Provider", section = "Advanced"), Field("model", "Model", section = "Advanced"), Field("skills", "Skills", FieldType.LIST, help = "One skill name per line", section = "Advanced"))
    val editableFields = if (job != null && scheduleNameOnly(job)) fields.filter { it.key == "name" } else fields
    vm.openForm(ActionForm(if (job == null) "Create schedule" else "Edit schedule", if (job == null) "/api/crons/create" else "/api/crons/update", editableFields, seed, onResult = { refresh() }))
}

internal fun refreshSchedules(vm: BeaconViewModel) {
    vm.load("crons", "/api/crons")
    vm.load("cron-delivery", "/api/crons/delivery-options")
    vm.load("task-capabilities", "/api/plugins/beacon/mobile/tasks/capabilities")
}
