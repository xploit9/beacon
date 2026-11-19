@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.agentphone

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.parseAgentPhoneActivity
import dev.hermes.android.phone.PhoneDayName
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.PhoneTime
import dev.hermes.android.preferences
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SettingsAction
import dev.hermes.android.ui.SettingsActionRow
import dev.hermes.android.ui.SettingsDivider
import dev.hermes.android.ui.SettingsEmpty
import dev.hermes.android.ui.SettingsKit
import dev.hermes.android.ui.SettingsMetricRow
import dev.hermes.android.ui.SettingsSearchField
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsSkeletonRows
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsIconTile
import dev.hermes.android.ui.SettingsStatusTint
import dev.hermes.android.ui.rememberSettingsStatusPalette
import dev.hermes.android.ui.chatBeaconBackground
import dev.hermes.android.ui.nativeDayPattern
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.uiLocale
import dev.hermes.android.ui.named
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import org.json.JSONObject

/**
 * "What {name} did on its phone" (EMU-DESIGN §5.5, §8): every line the agent phone's tool wrote, newest first, by day, with a search
 * and a result filter. A done notification opens it at its own row ([EXTRA_SEQ]); the phone page (L4) can open it by class name.
 * Text only: labels, never an image.
 */
class AgentPhoneLogActivity : ComponentActivity() {
    private val model: AgentPhoneLogModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        val highlight = intent.getLongExtra(EXTRA_SEQ, 0L).takeIf { it > 0 }
        model.load()
        setContent {
            val app = application as BeaconApplication
            val preferences by remember(app) { app.preferences.data.map(AppPreferences::read) }.collectAsStateWithLifecycle(AppPreferences())
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            CompositionLocalProvider(LocalAssistantName provides preferences.assistantName) {
                NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) {
                    BeaconTheme(dark, preferences.skin) {
                        // The theme's own background first (the window's is the system's light or dark, not Beacon's choice), then the glow.
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).chatBeaconBackground().windowInsetsPadding(WindowInsets.safeDrawing)) {
                            AgentPhoneLogScreen(state, highlight, onBack = ::finish, onRetry = model::load)
                        }
                    }
                }
            }
        }
    }

    companion object {
        internal const val EXTRA_SEQ = "dev.hermes.android.agentphone.SEQ"

        /** Opens the list, at the row numbered [seq] when one is given. */
        internal fun intent(context: Context, seq: Long?): Intent = Intent(context, AgentPhoneLogActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply { seq?.let { putExtra(EXTRA_SEQ, it) } }
    }
}

/** The list's state: the rows read (null before the first read), and whether the last read failed. */
internal data class LogState(val rows: List<AgentPhoneLogRow>? = null, val failed: Boolean = false, val loading: Boolean = true)

/** Rows of the last read, per account, for this process: the list opens at once the next time, then reads again. */
private val lastRows = HashMap<String, List<AgentPhoneLogRow>>()

/** Chat join (rules22): sign-out, an account change and a phone removed on the computer: what this process holds of [scope]'s account goes,
 *  the activity list's last rows and the approval board's records. */
internal suspend fun forgetAgentPhoneAccount(scope: dev.hermes.android.core.Scope) {
    val account = approvalAccount(scope)
    synchronized(lastRows) { lastRows.remove(account) }
    AgentApprovals.board.forget(account)
}

/** The first page as it came, kept on this phone per account and profile (the app's encrypted cache), so the list opens filled after
 *  a restart too (cache review, Root msg_8b7aac5cc13b); sign-out drops it with the account's screens. Labels only, never a secret. */
internal const val AGENT_PHONE_LOG_KIND = "agent-phone-log"

internal class AgentPhoneLogModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BeaconApplication
    private val _state = MutableStateFlow(LogState())
    val state: StateFlow<LogState> = _state.asStateFlow()

    fun load() {
        _state.value = _state.value.copy(loading = true, failed = false)
        viewModelScope.launch {
            val link = AgentApprovals.link(app) ?: run { _state.value = _state.value.copy(loading = false, failed = true); return@launch }
            val kept = synchronized(lastRows) { lastRows[link.account] } ?: link.scope?.let { scope ->
                runCatching { app.cache.read(scope, AGENT_PHONE_LOG_KIND)?.let { parseAgentPhoneActivity(JSONObject(it)).sortedByDescending { row -> row.seq } } }.getOrNull()
            }
            kept?.let { cached -> if (_state.value.rows == null) _state.value = _state.value.copy(rows = cached) }
            val answer = link.endpoint.call("GET", "$ACTIVITY_ROUTE?limit=$LOG_LIMIT", null, APPROVAL_CALL_MS)
            _state.value = when {
                answer.ok -> parseAgentPhoneActivity(answer.body).sortedByDescending { it.seq }.also { rows ->
                    synchronized(lastRows) { lastRows[link.account] = rows }
                    link.scope?.let { scope -> runCatching { app.cache.write(scope, AGENT_PHONE_LOG_KIND, answer.body.toString()) } }
                }.let { LogState(it, loading = false) }
                // A Hermes without the route has nothing to list yet.
                answer.status == 404 -> LogState(emptyList(), loading = false)
                else -> _state.value.copy(loading = false, failed = true)
            }
        }
    }

    private companion object { const val LOG_LIMIT = 200 }
}

private fun actionIcon(row: AgentPhoneLogRow): ImageVector = when (row.action) {
    "type" -> Icons.Rounded.Keyboard
    "fill", "save" -> Icons.Rounded.Key
    "swipe" -> Icons.Rounded.Swipe
    "key" -> Icons.AutoMirrored.Rounded.KeyboardReturn
    "answer" -> Icons.Rounded.Android
    else -> Icons.Rounded.TouchApp
}

/** A row as the owner reads it: what was done in plain words, then the app (when the sentence doesn't name it), the [time] and (when it
 *  didn't simply run) how it ended. The phone page's Recent activity reads its rows the same way. */
@Composable internal fun logRowText(row: AgentPhoneLogRow, time: String): Pair<String, String> {
    val line = logLine(row)
    val title = PhoneLine(uiText(line.title.template), line.title.args).shown().named()
    val value = listOf(
        row.app.takeIf { it.isNotBlank() && row.action != "save" && !line.appInTitle }?.let { PhoneLine("{0}", listOf(it)).shown() }.orEmpty(),
        line.verb.takeIf(String::isNotBlank)?.let { uiText(it) }.orEmpty(),
        time,
        line.outcome.takeIf(String::isNotBlank)?.let { uiText(it).named() }.orEmpty(),
    ).filter(String::isNotBlank).joinToString(" · ")
    return title to value
}

@Composable private fun rowText(row: AgentPhoneLogRow, zone: ZoneId): Pair<String, String> = logRowText(row, PhoneTime.clock(row.atMillis, zone, uiLocale()))

/** A day's heading: Today, Yesterday, else the date in the phone's own order (with the weekday this year, the year before). */
@Composable private fun dayLabel(day: LogDay, today: LocalDate): String = when (logDayName(day.day, today)) {
    PhoneDayName.Today -> uiText("Today")
    PhoneDayName.Yesterday -> uiText("Yesterday")
    PhoneDayName.Date -> {
        val locale = uiLocale()
        val sameYear = day.day.year == today.year
        SimpleDateFormat(nativeDayPattern(locale, withYear = !sameYear, withWeekday = sameYear), locale).format(Date(day.rows.first().atMillis))
    }
}

@Composable internal fun AgentPhoneLogScreen(state: LogState, highlight: Long?, onBack: () -> Unit, onRetry: () -> Unit, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)) {
    var query by rememberSaveable { mutableStateOf("") }
    var resultKey by rememberSaveable { mutableStateOf("") }
    var pages by rememberSaveable { mutableIntStateOf(1) }
    val rows = state.rows
    val filter = LogFilter(query, LogResult.entries.firstOrNull { it.name == resultKey })
    // The search reads each row as it is shown, in the shown language.
    val texts = rows.orEmpty().associate { it.seq to rowText(it, zone).let { (title, value) -> "$title $value" } }
    val matching = remember(rows, filter, texts) { filterLog(rows.orEmpty(), filter) { texts[it.seq].orEmpty() } }
    // A notification's row is always in the first pages shown.
    val needed = highlight?.let { seq -> matching.indexOfFirst { it.seq == seq }.takeIf { it >= 0 }?.let { it / PAGE + 1 } } ?: 1
    val visible = matching.take(PAGE * maxOf(pages, needed))
    val days = remember(visible, zone) { groupLogByDay(visible, zone) }
    val list = rememberLazyListState()
    LaunchedEffect(highlight, days.isNotEmpty()) {
        if (highlight == null || days.isEmpty()) return@LaunchedEffect
        val index = days.indexOfFirst { day -> day.rows.any { it.seq == highlight } }
        if (index >= 0) list.animateScrollToItem(index + LIST_HEAD)
    }
    Column(Modifier.fillMaxSize().testTag("agent-phone-log")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack, Modifier.size(48.dp).testTag("agent-phone-log-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Back")) }
            // The whole title, on more than one line at large text: it says whose phone this is.
            Text(uiText("What {name} did on its phone").named(), Modifier.weight(1f).semantics { heading() }.testTag("agent-phone-log-title"),
                style = MaterialTheme.typography.titleLarge, maxLines = 3)
        }
        LazyColumn(Modifier.fillMaxSize(), state = list, horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(start = SettingsKit.PagePadding, end = SettingsKit.PagePadding, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap)) {
            item("summary") {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (rows != null && rows.isNotEmpty()) Summary(rows, zone, today)
                    if (rows != null && rows.isNotEmpty()) {
                        SettingsSearchField(query, { query = it; pages = 1 }, uiText("Search activity"), testTag = "agent-phone-log-search")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Each chip is a full 48 dp target, however short its word ("All").
                            FilterChip(filter.result == null, { resultKey = ""; pages = 1 }, label = { Text(uiText("All")) },
                                modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).testTag("agent-phone-log-filter:all"))
                            LogResult.entries.forEach { result ->
                                FilterChip(filter.result == result, { resultKey = if (filter.result == result) "" else result.name; pages = 1 },
                                    label = { Text(uiText(result.words)) }, modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 64.dp).testTag("agent-phone-log-filter:${result.name.lowercase()}"))
                            }
                        }
                    }
                    when {
                        rows == null && state.failed -> SettingsSection(testTag = "agent-phone-log-error") { OfflineRow(onRetry) }
                        rows == null -> SettingsSection { SettingsSkeletonRows(4) }
                        rows.isEmpty() -> SettingsSection(testTag = "agent-phone-log-empty") { SettingsEmpty(uiText("Nothing yet"), Icons.Rounded.History) }
                        matching.isEmpty() -> SettingsSection(testTag = "agent-phone-log-no-matches") {
                            SettingsEmpty(uiText("No matches"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Reset filters"), onClick = { query = ""; resultKey = ""; pages = 1 }, testTag = "agent-phone-log-reset"))
                        }
                    }
                }
            }
            days.forEach { day ->
                item("day:${day.day}") {
                    SettingsSection(dayLabel(day, today), Modifier.widthIn(max = 720.dp), testTag = "agent-phone-log-day") {
                        day.rows.forEachIndexed { index, row ->
                            if (index > 0) SettingsDivider()
                            LogRow(row, zone, marked = row.seq == highlight)
                        }
                    }
                }
            }
            if (matching.size > visible.size) item("more") {
                SettingsSection(modifier = Modifier.widthIn(max = 720.dp)) { SettingsActionRow(uiText("Show more"), onClick = { pages = maxOf(pages, needed) + 1 }, testTag = "agent-phone-log-more") }
            }
        }
    }
}

/**
 * One row: the action's tile, the thing done, then app · what was done · time · how it ended, and "Approved by you". Nothing is cut at
 * any text size (the settings row stops its label at two lines; a row here is the owner's record, read whole). A done notice's row is
 * marked. TalkBack reads the row as one item.
 */
@Composable private fun LogRow(row: AgentPhoneLogRow, zone: ZoneId, marked: Boolean) {
    val (title, value) = rowText(row, zone)
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().then(if (marked) Modifier.background(scheme.primary.copy(alpha = .10f)) else Modifier)
        .semantics(mergeDescendants = true) {}.heightIn(min = SettingsKit.RowMinHeightTwoLine).padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("agent-phone-log-row:${row.seq}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(actionIcon(row))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (row.approved) ApprovedMark()
        }
    }
}

/** Hermes didn't answer: the line wraps at any text size (the shared error row stops at two lines), Try again under it when it must. */
@Composable private fun OfflineRow(onRetry: () -> Unit) {
    val ink = rememberSettingsStatusPalette().error
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center, itemVerticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.padding(vertical = 8.dp).padding(end = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Error, null, Modifier.size(20.dp), tint = ink)
            Text(uiText("Can't reach Hermes"), style = MaterialTheme.typography.bodyMedium, color = ink)
        }
        TextButton(onRetry, Modifier.heightIn(min = 48.dp).testTag("agent-phone-log-retry")) { Text(uiText("Try again")) }
    }
}

/** "Approved by you" in the success colour, as a settings status pill looks, but it wraps instead of cutting at large text. */
@Composable private fun ApprovedMark() {
    val ink = rememberSettingsStatusPalette().ok
    Row(Modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = SettingsStatusTint)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(16.dp), tint = ink)
        Text(uiText("Approved by you"), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = ink)
    }
}

/** Today's count, what the owner approved, and what didn't run: the figures large, one per line when the text is large. */
@Composable private fun Summary(rows: List<AgentPhoneLogRow>, zone: ZoneId, today: LocalDate) {
    val todayCount = rows.count { java.time.Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate() == today }
    val metrics = listOf(
        uiText("Today") to "$todayCount",
        uiText("Approved by you") to "${rows.count { it.approved }}",
        uiText("Didn't run") to "${rows.count { LogResult.NotDone in logResultOf(it) }}",
    )
    SettingsSection(testTag = "agent-phone-log-summary") {
        if (LocalDensity.current.fontScale >= 1.3f) metrics.forEachIndexed { index, metric -> if (index > 0) SettingsDivider(); SettingsMetricRow(listOf(metric)) }
        else SettingsMetricRow(metrics)
    }
}

private const val PAGE = 20
/** Items above the first day in the list: the summary and filters. */
private const val LIST_HEAD = 1

/** The screen's fixed words; the words test checks each one. */
internal val AgentPhoneLogScreenWords = listOf("What {name} did on its phone", "Search activity", "All", "Can't reach Hermes", "Try again", "Nothing yet", "No matches", "Reset filters", "Show more", "Today", "Yesterday", "Back")
