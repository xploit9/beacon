package dev.hermes.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.automirrored.rounded.PhoneMissed
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.beaconOwnerSetupMessage
import dev.hermes.android.isMissingBeaconPluginRoute
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.push.EMAIL_FEEDBACK_PATH
import dev.hermes.android.push.validOpaque
import dev.hermes.android.voice.CallTranscript
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class CallHistoryEntry(
    val id: String,
    val direction: String,
    val state: String,
    val startedAt: Long,
    val durationSeconds: Long?,
    val provider: String?,
    val conversationId: String,
    val reason: String?,
    val emailNoticeId: String?,
    val hasTranscript: Boolean = false,
) {
    companion object {
        fun from(value: JSONObject) = CallHistoryEntry(
            id = value.string("id"),
            direction = value.string("direction"),
            state = value.string("state"),
            startedAt = value.optLong("started_at"),
            durationSeconds = (value.opt("duration_s") as? Number)?.toLong()?.takeIf { it >= 0 },
            provider = value.string("provider").takeIf(String::isNotBlank),
            conversationId = value.string("conversation_id"),
            reason = value.string("reason").takeIf(String::isNotBlank),
            emailNoticeId = value.string("email_notice_id").takeIf(::validOpaque),
            hasTranscript = value.optBoolean("has_transcript"),
        ).takeIf { it.id.isNotBlank() && it.conversationId.isNotBlank() && it.startedAt > 0 }
    }
}

/** How a call is described when it has no reason and no words of its own. */
internal enum class CallDirection { Missed, Declined, Failed, InProgress, Incoming, Outgoing }

internal fun callDirectionOf(state: String, incoming: Boolean): CallDirection = when (state) {
    "missed" -> CallDirection.Missed
    "declined" -> CallDirection.Declined
    "failed" -> CallDirection.Failed
    "active" -> if (incoming) CallDirection.Incoming else CallDirection.InProgress
    else -> if (incoming) CallDirection.Incoming else CallDirection.Outgoing
}

@Composable
private fun callDirectionText(direction: CallDirection): String = when (direction) {
    CallDirection.Missed -> uiText("Missed call")
    CallDirection.Declined -> uiText("Declined call")
    CallDirection.Failed -> uiText("Call failed")
    CallDirection.InProgress -> uiText("Call in progress")
    CallDirection.Incoming -> uiText("Incoming call")
    CallDirection.Outgoing -> uiText("Outgoing call")
}

private val whitespaceRun = Regex("\\s+")

/** What a call is called: the reason Hermes gave, else the first thing the user said, else null so the row falls back to its direction. */
internal fun callRowTitle(reason: String?, firstUserLine: String?): String? =
    listOf(reason, firstUserLine).firstNotNullOfOrNull { it?.replace(whitespaceRun, " ")?.trim()?.take(140)?.takeIf(String::isNotBlank) }

/** The first thing the user said in [callId], from the transcript lines of the open conversation. */
internal fun firstUserCallLine(callId: String, lines: List<CallTranscript>): String? =
    dev.hermes.android.voice.orderCallTranscriptLines(lines.filter { it.eventId.startsWith("$callId:") && it.role == "user" && it.text.isNotBlank() && !it.interrupted })
        .firstOrNull()?.text

/** A call that ended almost at once, or never connected: the kind that repeats when someone retries. */
internal const val SHORT_ATTEMPT_SECONDS = 20L
private const val ATTEMPT_GAP_SECONDS = 15 * 60L

internal fun isShortAttempt(call: CallHistoryEntry): Boolean =
    call.emailNoticeId == null && !call.hasTranscript && when (call.state) {
        "missed", "declined", "failed" -> true
        "completed" -> (call.durationSeconds ?: 0L) < SHORT_ATTEMPT_SECONDS
        else -> false
    }

private fun sameAttempt(a: CallHistoryEntry, b: CallHistoryEntry) =
    isShortAttempt(a) && isShortAttempt(b) && a.conversationId == b.conversationId && a.direction == b.direction &&
        a.state == b.state && a.reason == b.reason && kotlin.math.abs(a.startedAt - b.startedAt) <= ATTEMPT_GAP_SECONDS

internal sealed interface CallHistoryItem {
    val key: String
    data class Single(val call: CallHistoryEntry) : CallHistoryItem { override val key get() = call.id }
    /** Newest first, like the list it came from. */
    data class Attempts(val calls: List<CallHistoryEntry>) : CallHistoryItem { override val key get() = "attempts:" + calls.first().id }
}

/** Repeated short attempts to the same conversation become one row ("3 attempts"); everything else keeps its own row. */
internal fun groupCallAttempts(calls: List<CallHistoryEntry>): List<CallHistoryItem> {
    val items = mutableListOf<CallHistoryItem>()
    var run = mutableListOf<CallHistoryEntry>()
    fun flush() {
        when (run.size) { 0 -> Unit; 1 -> items += CallHistoryItem.Single(run[0]); else -> items += CallHistoryItem.Attempts(run.toList()) }
        run = mutableListOf()
    }
    for (call in calls) {
        val last = run.lastOrNull()
        if (last == null || !sameAttempt(last, call)) flush()
        run += call
    }
    flush()
    return items
}

/** The last pages this phone loaded, per account and profile, so the sheet opens with them and refreshes behind. */
private object CallHistoryMemory {
    private var owner: Any? = null
    private var calls = emptyList<CallHistoryEntry>()
    private var next: String? = null
    @Synchronized fun calls(key: Any?) = if (key != null && key == owner) calls else emptyList()
    @Synchronized fun next(key: Any?) = if (key != null && key == owner) next else null
    @Synchronized fun remember(key: Any?, calls: List<CallHistoryEntry>, next: String?) {
        if (key == null) return
        owner = key; this.calls = calls; this.next = next
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallHistorySheet(vm: BeaconViewModel, onOpenCall: (conversationId: String, callId: String) -> Unit, onDismiss: () -> Unit) {
    val activeClient = vm.client
    val requestScope = vm.authenticatedScope()?.copy(session = "")
    val client = remember(activeClient) { activeClient?.detached() }
    var calls by remember(activeClient, requestScope) { mutableStateOf(CallHistoryMemory.calls(requestScope)) }
    var next by remember(activeClient, requestScope) { mutableStateOf(CallHistoryMemory.next(requestScope)) }
    var loading by remember(activeClient, requestScope) { mutableStateOf(false) }
    var error by remember(activeClient, requestScope) { mutableStateOf("") }
    var retryAvailable by remember(activeClient, requestScope) { mutableStateOf(true) }
    var missingPlugin by remember(activeClient, requestScope) { mutableStateOf(false) }
    var feedback by remember(activeClient, requestScope) { mutableStateOf(emptyMap<String, String>()) }
    val list = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val chat by vm.chat.collectAsStateWithLifecycle()

    suspend fun load(cursor: String? = null) {
        if (loading || activeClient == null || requestScope == null || client == null) return
        loading = true
        error = ""
        retryAvailable = true
        missingPlugin = false
        try {
            val query = mutableMapOf("profile" to requestScope.profile, "limit" to "50")
            cursor?.let { query["before"] = it }
            val response = client.get("/api/plugins/beacon/mobile/calls", query)
            if (vm.client !== activeClient || vm.authenticatedScope()?.copy(session = "") != requestScope) return
            val page = response.rows("calls").mapNotNull(CallHistoryEntry::from)
            calls = if (cursor == null) page else calls + page
            next = response.string("next").ifBlank { null }
            CallHistoryMemory.remember(requestScope, calls, next)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val ownerSetup = beaconOwnerSetupMessage(failure)
            missingPlugin = ownerSetup == null && isMissingBeaconPluginRoute(failure)
            retryAvailable = ownerSetup == null && !missingPlugin
            error = ownerSetup ?: if (retryAvailable) "Call history unavailable" else ""
        } finally {
            loading = false
        }
    }

    // A tap on Important or Not important opens the rule sheet for that call; the choice is sent from there.
    var emailRule by remember(activeClient, requestScope) { mutableStateOf<EmailRuleRequest?>(null) }
    fun chooseEmail(call: CallHistoryEntry, verdict: String) {
        val noticeId = call.emailNoticeId ?: return
        if (feedback[noticeId] == "saved") return
        emailRule = EmailRuleRequest(noticeId, verdict)
    }
    fun sameScope() = vm.client === activeClient && vm.authenticatedScope()?.copy(session = "") == requestScope
    EmailRuleSheetHost(
        emailRule,
        fetch = { request ->
            val feedbackClient = client?.takeIf { sameScope() } ?: throw IllegalStateException("Reconnect first.")
            feedbackClient.nativeRequest("$PROACTIVE_SETTINGS_PATH/suggestion", "GET", null, mapOf("notice_id" to request.noticeId, "verdict" to request.verdict))
        },
        send = { request, rule ->
            val feedbackClient = client?.takeIf { sameScope() } ?: throw IllegalStateException("Reconnect first.")
            feedbackClient.nativeRequest(EMAIL_FEEDBACK_PATH, "POST", JSONObject().put("notice_id", request.noticeId).put("verdict", request.verdict).apply { if (rule != null) put("rule", rule) })
            check(sameScope()) { "Reconnect first." }
        },
        onChosen = { request ->
            if (sameScope()) feedback = feedback + (request.noticeId to "saved")
            emailRule = null
        },
        onDismiss = { emailRule = null },
    )

    LaunchedEffect(activeClient, requestScope) { load() }
    LaunchedEffect(list, calls.size, next, loading, error) {
        if (next == null || loading || error.isNotBlank()) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            // Rows are grouped, so the end of the list is its own item count, not the number of calls.
            .collectLatest { lastVisible -> if (lastVisible >= list.layoutInfo.totalItemsCount - 1) load(next) }
    }

    val items = remember(calls) { groupCallAttempts(calls) }
    val dayStarts = remember(calls) { messageDayStarts(calls.map { it.id to it.startedAt * 1000 }) }
    val openLines = remember(chat.sessionId, chat.callMessages) { chat.sessionId to chat.callMessages }
    fun titleLine(call: CallHistoryEntry) = firstUserCallLine(call.id, openLines.second).takeIf { call.conversationId == openLines.first }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("Call history"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("call-history-close")) {
                    Icon(Icons.Rounded.Close, uiText("Close call history"))
                }
            }
            // A 2dp bar while the saved calls refresh; the space is always there so the list never jumps.
            Box(Modifier.fillMaxWidth().height(2.dp)) { if (loading && calls.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("call-history-refreshing")) }
            when {
                missingPlugin -> MissingBeaconPluginState(Modifier.weight(1f), compact = true, update = true)
                error.isNotBlank() && calls.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(uiText(error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (retryAvailable) TextButton(onClick = { coroutineScope.launch { load() } }) { Text(uiText("Retry")) }
                }
                calls.isEmpty() && loading -> SettingsSkeletonRows(4, Modifier.weight(1f))
                calls.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(assistantText("Calls with Beacon appear here."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    if (error.isNotBlank()) item("call-history-error") {
                        SettingsErrorRow(uiText(error), if (retryAvailable) ({ coroutineScope.launch { load() } }) else null, uiText("Retry"))
                    }
                    itemsIndexed(items, key = { _, item -> item.key }) { _, item ->
                        when (item) {
                            is CallHistoryItem.Single -> {
                                val call = item.call
                                dayStarts[call.id]?.let { DayDivider(uiText(it)) }
                                CallHistoryRow(call, titleLine(call), call.emailNoticeId?.let { feedback[it] }, { chooseEmail(call, it) }) { onOpenCall(call.conversationId, call.id) }
                            }
                            is CallHistoryItem.Attempts -> {
                                dayStarts[item.calls.first().id]?.let { DayDivider(uiText(it)) }
                                CallAttemptsRows(item, ::titleLine) { call -> onOpenCall(call.conversationId, call.id) }
                            }
                        }
                    }
                    if (next != null && !loading) item("call-history-more") {
                        TextButton(onClick = { coroutineScope.launch { load(next) } }, modifier = Modifier.fillMaxWidth()) { Text(uiText("Load more")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun callDetails(call: CallHistoryEntry): String {
    val locale = Locale.getDefault()
    val clock = nativeClockPattern(locale, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    return buildList {
        add(SimpleDateFormat(clock, locale).format(Date(call.startedAt * 1000)))
        call.durationSeconds?.let { add(formatCallDuration(it)) }
    }.joinToString(" · ")
}

/** The icon tile, the title and a line of facts. A missed call is drawn in the error colour. */
@Composable
private fun CallRowContent(call: CallHistoryEntry, title: String?, details: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    val missed = call.state == "missed"
    val incoming = call.direction == "incoming"
    val icon = when {
        missed -> Icons.AutoMirrored.Rounded.PhoneMissed
        incoming -> Icons.AutoMirrored.Rounded.CallReceived
        else -> Icons.AutoMirrored.Rounded.CallMade
    }
    val shown = title ?: callDirectionText(callDirectionOf(call.state, incoming))
    Row(modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsIconTile(icon, destructive = missed)
        Column(Modifier.weight(1f)) {
            Text(shown, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                color = if (missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(details, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun CallHistoryRow(call: CallHistoryEntry, firstUserLine: String?, feedbackStatus: String?, onFeedback: (String) -> Unit, onOpen: () -> Unit) {
    val title = callRowTitle(call.reason, firstUserLine)
    Column(Modifier.fillMaxWidth()) {
        CallRowContent(call, title, callDetails(call), Modifier.clickable(role = Role.Button, onClick = onOpen).testTag("call-history-row:${call.id}"))
        if (call.emailNoticeId != null) {
            if (feedbackStatus == "saved") {
                Text(uiText("Saved"), Modifier.padding(start = 52.dp).testTag("call-history-feedback-sent:${call.id}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onFeedback("important") }, modifier = Modifier.testTag("call-history-feedback-important:${call.id}")) { Text(uiText("Important")) }
                    TextButton(onClick = { onFeedback("not_important") }, modifier = Modifier.testTag("call-history-feedback-not-important:${call.id}")) { Text(uiText("Not important")) }
                }
            }
        }
    }
}

/** Repeated short attempts: one row ("3 attempts") that opens into the individual attempts. */
@Composable
private fun CallAttemptsRows(item: CallHistoryItem.Attempts, titleLine: (CallHistoryEntry) -> String?, onOpen: (CallHistoryEntry) -> Unit) {
    var expanded by rememberSaveable(item.key) { mutableStateOf(false) }
    val newest = item.calls.first()
    val title = callRowTitle(newest.reason, titleLine(newest))
    val count = uiText("{0} attempts").replace("{0}", "${item.calls.size}")
    val state = uiText(if (expanded) "Expanded" else "Collapsed")
    Column(Modifier.fillMaxWidth()) {
        CallRowContent(
            newest, title, "${callDetails(newest).substringBefore(" · ")} · $count",
            Modifier.clickable(role = Role.Button) { expanded = !expanded }.semantics { stateDescription = state }.testTag("call-history-group:${newest.id}"),
        ) {
            Icon(Icons.Rounded.ExpandMore, null, Modifier.rotate(if (expanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) item.calls.forEach { call ->
            Row(Modifier.fillMaxWidth().padding(start = 52.dp)) {
                CallRowContent(call, null, callDetails(call), Modifier.clickable(role = Role.Button) { onOpen(call) }.testTag("call-history-row:${call.id}"))
            }
        }
    }
}

internal fun formatCallDuration(seconds: Long): String {
    val hours = seconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
