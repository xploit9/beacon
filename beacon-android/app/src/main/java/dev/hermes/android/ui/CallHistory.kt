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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.beaconOwnerSetupMessage
import dev.hermes.android.isMissingBeaconPluginRoute
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.push.EMAIL_FEEDBACK_PATH
import dev.hermes.android.push.validOpaque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class CallHistoryEntry(
    val id: String,
    val direction: String,
    val state: String,
    val startedAt: Long,
    val durationSeconds: Long?,
    val provider: String?,
    val conversationId: String,
    val reason: String?,
    val emailNoticeId: String?,
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
        ).takeIf { it.id.isNotBlank() && it.conversationId.isNotBlank() && it.startedAt > 0 }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallHistorySheet(vm: BeaconViewModel, onOpenCall: (conversationId: String, callId: String) -> Unit, onDismiss: () -> Unit) {
    val activeClient = vm.client
    val requestScope = vm.authenticatedScope()?.copy(session = "")
    val client = remember(activeClient) { activeClient?.detached() }
    var calls by remember(activeClient, requestScope) { mutableStateOf(emptyList<CallHistoryEntry>()) }
    var next by remember(activeClient, requestScope) { mutableStateOf<String?>(null) }
    var loading by remember(activeClient, requestScope) { mutableStateOf(false) }
    var error by remember(activeClient, requestScope) { mutableStateOf("") }
    var retryAvailable by remember(activeClient, requestScope) { mutableStateOf(true) }
    var missingPlugin by remember(activeClient, requestScope) { mutableStateOf(false) }
    var feedback by remember(activeClient, requestScope) { mutableStateOf(emptyMap<String, String>()) }
    val list = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

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

    fun sendFeedback(call: CallHistoryEntry, verdict: String) {
        val noticeId = call.emailNoticeId ?: return
        if (feedback[noticeId] == "sending" || feedback[noticeId] == "saved") return
        val feedbackClient = client ?: return
        val scope = requestScope ?: return
        if (vm.client !== activeClient || vm.authenticatedScope()?.copy(session = "") != scope) return
        feedback = feedback + (noticeId to "sending")
        coroutineScope.launch {
            val saved = try {
                if (vm.client !== activeClient || vm.authenticatedScope()?.copy(session = "") != scope) false
                else {
                    feedbackClient.nativeRequest(EMAIL_FEEDBACK_PATH, "POST", JSONObject().put("notice_id", noticeId).put("verdict", verdict))
                    vm.client === activeClient && vm.authenticatedScope()?.copy(session = "") == scope
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            feedback = if (vm.client === activeClient && vm.authenticatedScope()?.copy(session = "") == scope) {
                feedback + (noticeId to if (saved) "saved" else "failed")
            } else feedback - noticeId
        }
    }

    LaunchedEffect(activeClient, requestScope) { load() }
    LaunchedEffect(list, calls.size, next, loading, error) {
        if (next == null || loading || error.isNotBlank()) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collectLatest { lastVisible -> if (lastVisible >= calls.size - 1) load(next) }
    }

    val dayStarts = remember(calls) { messageDayStarts(calls.map { it.id to it.startedAt * 1000 }) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("Call history"), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss, modifier = Modifier.testTag("call-history-close")) {
                    Icon(Icons.Rounded.Close, uiText("Close call history"))
                }
            }
            when {
                missingPlugin -> MissingBeaconPluginState(Modifier.weight(1f), compact = true, update = true)
                error.isNotBlank() -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(uiText(error), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (retryAvailable) TextButton(onClick = { coroutineScope.launch { calls = emptyList(); next = null; load() } }) { Text(uiText("Retry")) }
                }
                calls.isEmpty() && loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                calls.isEmpty() -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    Icon(Icons.Rounded.Call, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(assistantText("Calls with Beacon appear here."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 24.dp)) {
                    itemsIndexed(calls, key = { _, call -> call.id }) { _, call ->
                        dayStarts[call.id]?.let { DayDivider(uiText(it)) }
                        CallHistoryRow(call, call.emailNoticeId?.let { feedback[it] }, { sendFeedback(call, it) }) { onOpenCall(call.conversationId, call.id) }
                    }
                    if (loading) item("call-history-loading") { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    else if (next != null) item("call-history-more") {
                        TextButton(onClick = { coroutineScope.launch { load(next) } }, modifier = Modifier.fillMaxWidth()) { Text(uiText("Load more")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryRow(call: CallHistoryEntry, feedbackStatus: String?, onFeedback: (String) -> Unit, onOpen: () -> Unit) {
    val icon = when {
        call.state == "missed" -> Icons.AutoMirrored.Rounded.PhoneMissed
        call.direction == "incoming" -> Icons.AutoMirrored.Rounded.CallReceived
        else -> Icons.AutoMirrored.Rounded.CallMade
    }
    val incoming = call.direction == "incoming"
    val title = when (call.state) {
        "missed" -> uiText("Missed call")
        "declined" -> uiText("Declined call")
        "failed" -> uiText("Call failed")
        "active" -> if (incoming) uiText("Incoming call") else uiText("Call in progress")
        else -> if (incoming) uiText("Incoming call") else uiText("Outgoing call")
    }
    val locale = Locale.getDefault()
    val clock = nativeClockPattern(locale, android.text.format.DateFormat.is24HourFormat(LocalContext.current))
    val details = buildList {
        add(SimpleDateFormat(clock, locale).format(Date(call.startedAt * 1000)))
        call.durationSeconds?.let { add(formatCallDuration(it)) }
        call.provider?.let { add(if (it == "openai") "OpenAI" else it.replaceFirstChar { character -> character.uppercase() }) }
    }.joinToString(" · ")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onOpen)
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp)
                .testTag("call-history-row:${call.id}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.padding(end = 12.dp), tint = if (call.state == "missed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                call.reason?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (call.emailNoticeId != null) {
            if (feedbackStatus == "saved") {
                Text(assistantText("Thanks — Beacon will remember."), Modifier.padding(start = 56.dp).testTag("call-history-feedback-sent:${call.id}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (feedbackStatus == "failed") Text(uiText("Couldn't save your choice. Try again."), Modifier.padding(start = 56.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onFeedback("important") }, enabled = feedbackStatus != "sending", modifier = Modifier.testTag("call-history-feedback-important:${call.id}")) { Text(uiText("Important")) }
                    TextButton(onClick = { onFeedback("not_important") }, enabled = feedbackStatus != "sending", modifier = Modifier.testTag("call-history-feedback-not-important:${call.id}")) { Text(uiText("Not important")) }
                }
            }
        }
    }
}

private fun formatCallDuration(seconds: Long): String {
    val hours = seconds / 3600
    return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
}
