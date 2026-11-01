@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.hermes.android.ui

import dev.hermes.android.voice.failedCallConversation


import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.MissingVoiceKeyException
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.transcriptLabel
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.ui.sphere.resolveSphereState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import dev.hermes.android.tasks.scheduleState

internal suspend fun voiceKeyConfiguredOnIo(store: () -> VoiceKeyStore): Boolean = withContext(Dispatchers.IO) {
    val keyStore = store()
    keyStore.hasKey(keyStore.settings().provider)
}

private suspend fun optionalBackgroundRead(read: suspend () -> JSONObject): JSONObject? = try {
    read()
} catch (error: Exception) {
    if (error is CancellationException) throw error
    null
}

private suspend fun fetchBackgroundActivity(source: BeaconClient, profile: String, storedSessionId: String, pluginAvailable: Boolean): BackgroundActivitySummary = try {
    withTimeoutOrNull(15_000L) {
        if (source.scope.profile != profile || storedSessionId.isBlank()) return@withTimeoutOrNull BackgroundActivitySummary(available = false)
        val snapshot = source.nativeSessionSnapshot()
        val runtimeSessionId = snapshot.string("session_id")
        val boundStoredSessionId = snapshot.string("stored_session_id").ifBlank { snapshot.string("session_key") }
        if (runtimeSessionId.isBlank() || storedSessionId !in setOf(boundStoredSessionId, runtimeSessionId))
            return@withTimeoutOrNull BackgroundActivitySummary(available = false)
        coroutineScope {
            val session = json("session_id" to runtimeSessionId, "profile" to profile)
            // shortcut: prompt.background only starts tasks and exposes no read-only roster; a gateway status API is out of scope here.
            val roster = async { optionalBackgroundRead { source.nativeRpc("subagent.list", session.copy()) } }
            val delegation = async { optionalBackgroundRead { source.nativeRpc("delegation.status", json("profile" to profile)) } }
            val processes = async { optionalBackgroundRead { source.nativeRpc("process.list", session.copy()) } }
            val cron = async { optionalBackgroundRead { source.nativeRequest("/api/cron/jobs", params = mapOf("profile" to profile)) } }
            val proactive = async { if (pluginAvailable) optionalBackgroundRead { fetchProactiveSettings(source, profile) } else null }
            val sessionRoster = roster.await()
            val delegationStatus = delegation.await()
            val processStatus = processes.await()
            val cronStatus = cron.await()
            val proactiveStatus = proactive.await()
            val runningWatches = proactiveStatus?.rows("jobs").orEmpty()
                .filter { it.string("cadence") == "watch" && it.optBoolean("active") }
            val watchIds = runningWatches.mapNotNull { it.string("job_id").takeIf(String::isNotBlank) }.toSet()
            val cronRows = cronStatus?.let { it.rows("items").ifEmpty { it.rows("jobs") } }.orEmpty()
            val runningCron = cronRows.count { it.string("id") !in watchIds && scheduleState(it) == "Running" }
            BackgroundActivitySummary(
                agents = sessionScopedSubagentCount(sessionRoster ?: JSONObject(), delegationStatus),
                processes = processStatus?.rows("processes")?.count { it.string("status") == "running" } ?: 0,
                cronJobs = runningCron,
                watches = runningWatches.size,
                available = sessionRoster != null && processStatus != null && cronStatus != null && (!pluginAvailable || proactiveStatus != null),
            )
        }
    } ?: BackgroundActivitySummary(available = false)
} catch (error: Exception) {
    if (error is CancellationException) throw error
    BackgroundActivitySummary(available = false)
}

private val toolNamePattern = Regex("[A-Za-z0-9_.:-]{1,80}")

internal data class FoldedToolResult(val messageId: String, val content: String, val isError: Boolean)
internal data class ToolResultFolding(val byCallId: Map<String, FoldedToolResult>, val foldedMessageIds: Set<String>)

internal fun foldToolResults(messages: List<JSONObject>, displayedMessages: List<Message>): ToolResultFolding {
    val callIds = displayedMessages.asSequence()
        .filter { it.role == "assistant" }
        .flatMap { it.tools.asSequence() }
        .map(::toolCallResultId)
        .filter(String::isNotBlank)
        .toSet()
    val messagesById = displayedMessages.associateBy { it.id }
    val results = messages.withIndex().filter { (_, row) -> (row.string("role") == "tool" || row.string("role") == "function") && row.string("tool_call_id") in callIds }
    // A call id shared by several result rows is ambiguous; folding one would hide the others.
    val unique = results.groupingBy { it.value.string("tool_call_id") }.eachCount().filterValues { it == 1 }.keys
    val matched = results.mapNotNull { (index, row) ->
        val callId = row.string("tool_call_id")
        if (callId !in unique) return@mapNotNull null
        val messageId = row.string("id", row.string("message_id", row.string("row_id", row.string("_row_id", row.string("tool_call_id", "${row.string("role")}:$index")))))
        // The folded card shows text only, so a result with images stays a visible row.
        val message = messagesById[messageId]?.takeIf { it.attachments.isEmpty() } ?: return@mapNotNull null
        callId to FoldedToolResult(message.id, message.text, row.optBoolean("is_error"))
    }
    return ToolResultFolding(matched.toMap(), matched.mapTo(LinkedHashSet<String>()) { it.second.messageId })
}

/** A scheduled result Hermes filed in a thread: `[Cron delivery: <job>]`, then the result. It reads as a post, not as the owner's own message. */
internal data class ChannelPost(val job: String, val body: String)

internal val cronDeliveryLabel = Regex("^\\[Cron delivery: ([^\\]\\n]{1,200})][ \\t]*(?:\\n|\\z)")

internal fun channelPost(displayKind: String, role: String, text: String): ChannelPost? {
    if (displayKind != "cron_delivery" || role != "user") return null
    val trimmed = text.trimStart()
    val label = cronDeliveryLabel.find(trimmed) ?: return ChannelPost("", trimmed.trim())
    return ChannelPost(label.groupValues[1].trim(), trimmed.substring(label.range.last + 1).trim())
}

/** The owner's own message: a user row, except the posts Hermes files as user rows. */
internal fun isOwnMessage(message: Message): Boolean = message.role == "user" && message.displayKind != "cron_delivery"

/**
 * How a message sits among its neighbours: [startsRun] opens a run of one sender (a bigger gap above it), [joinedTop] means an
 * earlier bubble of the run is right above it, and [tail] marks the run's last bubble.
 */
internal data class BubbleGroup(val startsRun: Boolean, val joinedTop: Boolean, val tail: Boolean)

/**
 * Runs of consecutive messages from one sender, in display order. A new day, a call transcript ([standaloneIds]) or the other
 * sender ends a run. Messages without a bubble (tool-only rounds) stay in their run but take no corner or tail.
 */
internal fun bubbleGroups(
    messages: List<Message>,
    dayStartIds: Set<String>,
    standaloneIds: Set<String> = emptySet(),
    hasBubble: (Message) -> Boolean = { it.text.isNotBlank() || it.attachments.isNotEmpty() },
): Map<String, BubbleGroup> {
    val runs = ArrayList<List<Message>>()
    var run = ArrayList<Message>()
    for (message in messages) {
        val previous = run.lastOrNull()
        if (previous != null && (message.id in dayStartIds || message.id in standaloneIds || previous.id in standaloneIds || isOwnMessage(previous) != isOwnMessage(message))) {
            runs += run
            run = ArrayList()
        }
        run += message
    }
    if (run.isNotEmpty()) runs += run
    val groups = LinkedHashMap<String, BubbleGroup>()
    for (members in runs) {
        val lastBubble = members.indexOfLast(hasBubble)
        var bubbleAbove = false
        members.forEachIndexed { index, message ->
            val bubble = hasBubble(message)
            groups[message.id] = BubbleGroup(startsRun = index == 0, joinedTop = bubble && bubbleAbove, tail = bubble && index == lastBubble)
            if (bubble) bubbleAbove = true
        }
    }
    return groups
}

private val listItemStart = Regex("^\\s*(?:[-+]|\\d+[.)])\\s")
/**
 * Text with nothing for Markdown to do: a short bubble can then shrink to its words, as in Telegram. Anything with marks,
 * links, lists, paragraphs or media keeps the full Markdown renderer.
 */
internal fun isPlainChatText(text: String): Boolean =
    text.length <= 240 && '\n' !in text && text.none { it in "*_`[]#>|$\\<!~=&" } && !text.contains("://") && !text.contains("MEDIA:", ignoreCase = true) &&
        !listItemStart.containsMatchIn(text)

/** Hermes stores each tool round of one reply as its own assistant message; show a run of tool-only rounds as one
 *  activity, so its steps fold together instead of stacking card after card above the answer. */
internal fun mergeToolOnlyRuns(messages: List<IndexedValue<Message>>): List<IndexedValue<Message>> {
    fun toolOnly(message: Message) = message.role == "assistant" && message.text.isBlank() && message.attachments.isEmpty() && message.tools.isNotEmpty()
    val merged = ArrayList<IndexedValue<Message>>(messages.size)
    for (entry in messages) {
        val last = merged.lastOrNull()
        if (last != null && toolOnly(last.value) && toolOnly(entry.value)) {
            val reasoning = listOf(last.value.reasoning, entry.value.reasoning).filter(String::isNotBlank).joinToString("\n\n")
            merged[merged.size - 1] = IndexedValue(last.index, last.value.copy(tools = last.value.tools + entry.value.tools, reasoning = reasoning,
                recordedAt = maxOf(last.value.recordedAt, entry.value.recordedAt)))
        } else merged += entry
    }
    return merged
}

internal fun toolCallResultId(tool: JSONObject): String = tool.string("tool_call_id").ifBlank { toolIdentity(tool) }

/** Hermes stores the tool that produced a result row as `tool_name`; title the row after it. */
internal fun toolResultTitles(messages: List<JSONObject>): Map<String, String> = messages.asSequence()
    .filter { (it.string("role") == "tool" || it.string("role") == "function") && it.string("id").isNotBlank() }
    .mapNotNull { row -> row.string("tool_name").takeIf { it.matches(toolNamePattern) }?.let { row.string("id") to humanToolName(it, " result").replaceFirstChar(Char::titlecase) } }
    .toMap()

/** The History source filter only offers a choice: two or more sources, or a hidden one that must stay listed to be shown again. */
internal fun historySourceFilterShown(sources: List<String>, hidden: Set<String>): Boolean = sources.size > 1 || hidden.isNotEmpty()

/** The phone's 12/24-hour setting, for the times chats and lists show. */
@Composable private fun phoneIs24Hour(): Boolean = android.text.format.DateFormat.is24HourFormat(LocalContext.current)

@Composable fun SessionList(vm: BeaconViewModel, modifier: Modifier = Modifier, beforeHistory: @Composable () -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Only the open chat's id matters to the list: a streamed token must not recompose every row.
    val openSessionId by vm.openSessionId.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val glanceState by vm.glances.collectAsStateWithLifecycle()
    // Another account's glances are not this account's.
    val glances = glanceState.takeIf { it.account == vm.glanceAccountKey() } ?: ChatGlances()
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { vm.loadGlances() }
    val assistantTitle = LocalAssistantName.current
    val youLabel = uiText("You")
    val oneMessage = uiText("1 message")
    val manyMessages = uiText("{0} messages")
    val is24Hour = phoneIs24Hour()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileLoading = ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading"))
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(ui.query) }
    var searchExpanded by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(ui.query.isNotBlank()) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val motionEnabled = rememberNativeMotionEnabled()
    var archived by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    var allProfiles by rememberSaveable(ui.origin, ui.owner) { mutableStateOf(false) }
    var view by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(HistoryView.All) }
    var naming by rememberSaveable { mutableStateOf(false) }
    var filtersOpen by remember { mutableStateOf(false) }
    // Long-press menu and the dialogs it opens, for one history row at a time.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var projectFor by remember { mutableStateOf<String?>(null) }
    val order = HistoryOrder.of(preferences.historyOrder)
    val hidden = preferences.historyHiddenSources
    var searchFocused by remember { mutableStateOf(false) }
    // While searching, the destinations fold away and the field stays on top, so results have the room above the keyboard.
    val searching = searchFocused || search.isNotBlank()
    LaunchedEffect(allProfiles, ui.origin, ui.owner) { if (allProfiles) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(search, archived, view, hidden, order, allProfiles) { kotlinx.coroutines.delay(250); vm.refreshSessions(search, archived, force = false, threads = view == HistoryView.Threads, allProfiles = allProfiles) }
    // One scroll owner keeps destinations, search, projects and history reachable with the IME.
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentPadding = PaddingValues(bottom = 16.dp)) {
        if (!searching) item("destinations") { beforeHistory() }
        val loaded = ui.sessions.filter {
            it.optBoolean("archived") == archived && (allProfiles || it.string("profile", ui.profile) == ui.profile) &&
                (view == HistoryView.Threads || it.string("source") !in hidden)
        }
        // Beacon is the always-there agent: a presence card above History, not a row among the chats. Its tab keeps its conversation.
        if (view != HistoryView.Threads && !archived && (search.isBlank() || assistantTitle.contains(search.trim(), ignoreCase = true))) item("beacon-row") {
            val assistantRow = loaded.firstOrNull { ui.assistantSessionId.isNotBlank() && sessionRowId(it) == ui.assistantSessionId && it.string("profile", ui.profile) == preferences.assistantProfile }
            val glance = glances.rows["${preferences.assistantProfile}:${ui.assistantSessionId}"]
            val activity = assistantRow?.let(::rowActivityMillis) ?: 0L
            val presence = rememberBeaconPresence(vm, ui.assistantSessionId, assistantRow?.string("active_stream_id").orEmpty().isNotBlank())
            BeaconPresenceCard(
                title = assistantTitle,
                idle = assistantRow?.let { chatPreviewLine(it, glance, youLabel, oneMessage, manyMessages) }.orEmpty().ifBlank { uiText("Text and voice, together") },
                presence = presence,
                unread = chatUnread(activity, glance, glances.base[preferences.assistantProfile] ?: Long.MAX_VALUE, open = ui.tab == "Beacon"),
                isOpen = ui.tab == "Beacon",
                // The drawer stays composed while closed; its halo only moves when it can be seen.
                animate = LocalPersistentNavigation.current || ui.showSessions,
                onClick = { vm.navigate("Beacon") },
                // Busy while it replies or already talks; the chat's own call button has the same rule.
                canCall = presence == BeaconPresence.Idle && ui.connected && voiceCallsOffered(ui.bootstrap),
                onCall = { vm.callBeacon() },
            )
        }
        // The search icon opens the field; once it is open the field itself is the header, so the icon is not shown twice.
        if (!searching) item("history-header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("History"), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(
                    onClick = {
                        if (searchExpanded && search.isBlank()) {
                            focusManager.clearFocus()
                            searchExpanded = false
                        } else searchExpanded = true
                    },
                    modifier = Modifier.testTag("history-search-toggle"),
                ) { Icon(Icons.Rounded.Search, uiText("Search conversations")) }
            }
        }
        stickyHeader("history-search") {
            AnimatedVisibility(
                visible = searchExpanded || search.isNotBlank(),
                enter = if (motionEnabled) expandVertically() + fadeIn(tween(180)) else EnterTransition.None,
                exit = if (motionEnabled) shrinkVertically() + fadeOut(tween(120)) else ExitTransition.None,
            ) {
                // Opened from the search icon: focus once the field is attached (a restored query keeps the keyboard closed).
                LaunchedEffect(Unit) { if (search.isBlank()) searchFocusRequester.requestFocus() }
                val closeSearch = { search = ""; focusManager.clearFocus(); searchFocused = false; searchExpanded = false }
                OutlinedTextField(
                    search,
                    { search = it },
                    // Back out of search from the field itself, as Telegram does.
                    leadingIcon = { IconButton(onClick = closeSearch, modifier = Modifier.testTag("history-search-close")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Close search")) } },
                    trailingIcon = if (search.isBlank()) null else ({ IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, uiText("Clear search")) } }),
                    placeholder = { Text(uiText("Search conversations")) },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp).focusRequester(searchFocusRequester)
                        .onFocusChanged { if (it.isFocused) searchFocused = true else if (searchFocused) { searchFocused = false; if (search.isBlank()) searchExpanded = false } }
                        .testTag("history-search"),
                )
            }
        }
        item("history-filters") {
            val filtered = allProfiles || archived || hidden.isNotEmpty() || order != HistoryOrder.Recent
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    val choices = listOf(HistoryView.All to "All", HistoryView.Pinned to "Pinned", HistoryView.Threads to "Threads")
                    // At a large font the check mark would take the label's room.
                    val largeFont = LocalDensity.current.fontScale > 1.15f
                    choices.forEachIndexed { index, (choice, label) ->
                        SegmentedButton(
                            selected = view == choice,
                            onClick = { view = choice },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                            modifier = Modifier.testTag(when (choice) { HistoryView.All -> "history-view-all"; HistoryView.Pinned -> "history-view-pinned"; HistoryView.Threads -> "history-threads" }),
                            icon = if (largeFont) ({}) else ({ SegmentedButtonDefaults.Icon(view == choice) }),
                        ) { Text(uiText(label), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                }
                Box {
                    IconButton(
                        onClick = { filtersOpen = true },
                        modifier = Modifier.testTag("history-filter").semantics { selected = filtered },
                    ) { Icon(Icons.Rounded.FilterList, uiText("History filters")) }
                    DropdownMenu(filtersOpen, { filtersOpen = false }) {
                        Text(uiText("Profile scope"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownMenuItem(
                            text = { Text(uiText("This profile")) },
                            leadingIcon = { RadioButton(!allProfiles, null) },
                            onClick = { allProfiles = false; filtersOpen = false },
                            modifier = Modifier.testTag("history-current-profile"),
                        )
                        DropdownMenuItem(
                            text = { Text(uiText("All profiles")) },
                            leadingIcon = { RadioButton(allProfiles, null) },
                            onClick = { allProfiles = true; filtersOpen = false },
                            modifier = Modifier.testTag("history-all-profiles"),
                        )
                        HorizontalDivider()
                        Text(uiText("Show"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DropdownMenuItem(
                            text = { Text(uiText("Archived")) },
                            leadingIcon = { Checkbox(archived, null) },
                            onClick = { archived = !archived },
                            modifier = Modifier.testTag("history-archived"),
                        )
                        HorizontalDivider()
                        Text(uiText("Sort by"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HistoryOrder.entries.forEach { option -> DropdownMenuItem(text = { Text(uiText(option.label)) }, leadingIcon = { RadioButton(option == order, null) }, onClick = { vm.setHistoryFilters(hidden, option.key) }, modifier = Modifier.testTag("history-order:" + option.key)) }
                        val sources = historySources(ui.sessions, hidden)
                        // One source is no choice; a hidden one stays listed so it can be shown again.
                        if (view != HistoryView.Threads && historySourceFilterShown(sources, hidden)) {
                            HorizontalDivider()
                            Text(uiText("Show conversations from"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            sources.forEach { source -> DropdownMenuItem(text = { Text(uiText(historySourceLabel(source))) }, leadingIcon = { Checkbox(source !in hidden, null) }, onClick = { vm.setHistoryFilters(if (source in hidden) hidden - source else hidden + source, order.key) }, modifier = Modifier.testTag("history-source:" + source)) }
                        }
                        if (filtered) { HorizontalDivider(); DropdownMenuItem(text = { Text(uiText("Reset filters")) }, onClick = { filtersOpen = false; allProfiles = false; archived = false; vm.setHistoryFilters(emptySet(), HistoryOrder.Recent.key) }) }
                    }
                }
            }
        }
        if (ui.historyLoading && search.isNotBlank()) item("history-searching") {
            ListItem(headlineContent = { Text(uiText("Searching…")) }, leadingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.testTag("history-searching"))
        }
        val unreadable = if (allProfiles) ui.historyUnreadable else emptyList()
        unreadable.forEach { profile -> item("history-unreadable:$profile") {
            Text(uiText("Couldn't load conversations") + ": " + profileDisplayName(profile, profileRows), Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("history-unreadable:$profile"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        val matches = data["history:search"]?.takeIf {
            it.string("query") == search && it.string("scope_key") == vm.client?.scope?.key && it.optBoolean("archived") == archived &&
                it.optBoolean("threads") == (view == HistoryView.Threads) && it.optBoolean("all_profiles") == allProfiles &&
                it.string("hidden_sources") == hidden.sorted().joinToString(",") && it.string("order") == order.key
        }?.rows("sessions")
        val searched = historySearchRows(loaded, search, matches, ui.profile)
        val rows = historyRows(searched, view, order, ui.profile, preferences.assistantProfile, ui.assistantSessionId)
        // A failed read is not "no conversations". New thread lives once, in the drawer above.
        if (rows.isEmpty() && (ui.historyLoading || unreadable.isEmpty())) item("empty-history") {
            // An empty list is only "empty" once the fetch has answered.
            if (search.isNotBlank()) Box(Modifier.testTag("history-no-matches").semantics(mergeDescendants = true) {}) {
                EmptyState(uiText("No matching chats"), uiText("Try another search."), Icons.Rounded.SearchOff)
            }
            else if (ui.historyLoading) ListItem(headlineContent = { Text(uiText("Loading conversations…")) }, leadingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.testTag("history-loading"))
            else when (view) {
                HistoryView.Threads -> EmptyState(uiText("No threads yet"), uiText("Start a named thread for a topic you will come back to."), action = uiText("New thread"), onAction = { naming = true })
                HistoryView.Pinned -> EmptyState(uiText("No pinned conversations"), uiText("Pin a conversation from its actions to keep it here."))
                HistoryView.All -> EmptyState(uiText("No conversations here"), uiText("Start a conversation or try another search."), action = uiText("New conversation"), onAction = { vm.toggleSessions(false); vm.newChat() })
            }
        }
        items(rows, key = { "session:" + historyRowKey(it, ui.profile) }) { row ->
            val id = sessionRowId(row)
            val rowProfile = row.string("profile", ui.profile)
            val rowKey = historyRowKey(row, ui.profile)
            val rowTag = if (allProfiles) rowKey else id
            val source = row.string("source")
            val thread = isThreadSource(source)
            val external = source.isNotBlank() && source != "android" && !thread
            val glance = glances.rows[rowKey]
            val activity = rowActivityMillis(row)
            val openHere = rowProfile == ui.profile && id == openSessionId
            val caption = listOfNotNull(
                profileDisplayLabel(rowProfile, profileRows, profileLoading).takeIf { allProfiles },
                uiText(historySourceLabel(source)).takeIf { external },
            ).joinToString(" · ")
            val line = remember(row, glance, youLabel, manyMessages) { chatPreviewLine(row, glance, youLabel, oneMessage, manyMessages) }
            val time = remember(activity, is24Hour) { chatListTime(activity, is24Hour = is24Hour) }
            Box {
                ChatListRow(
                    id = id, title = row.string("title").ifBlank { uiText("Untitled conversation") },
                    preview = if (caption.isBlank()) line else if (line.isBlank()) caption else "$caption · $line",
                    time = time, pinned = row.optBoolean("pinned"), thread = thread,
                    unread = chatUnread(activity, glance, glances.base[rowProfile] ?: Long.MAX_VALUE, open = openHere && ui.tab in setOf("Chat", "Beacon")),
                    streaming = row.string("active_stream_id").isNotBlank(), selected = openHere,
                    modifier = Modifier.semantics { selected = openHere }.combinedClickable(onLongClickLabel = uiText("Conversation actions"), onLongClick = { menuFor = rowKey }) { vm.tab("Chat"); vm.openConversation(row) }.testTag("history-row:$rowTag"),
                )
                DropdownMenu(menuFor == rowKey, { menuFor = null }, modifier = Modifier.testTag("history-row-menu")) {
                    ConversationMenuItems(vm, row, onDone = { menuFor = null }, onRename = { renaming = row }, onProject = { projectFor = id },
                        onDelete = { if (openHere) { vm.toggleSessions(false); chatAction(vm, "Delete conversation") } else deleting = row })
                }
            }
        }

    }
    renaming?.let { row -> RenameConversationDialog(vm, sessionRowId(row), row.string("profile", ui.profile), row.string("title"), onDismiss = { renaming = null }) }
    deleting?.let { row -> DeleteConversationDialog(vm, sessionRowId(row), row.string("profile", ui.profile), row.string("title"), onDismiss = { deleting = null }) }
    projectFor?.let { id -> ChatProjectPicker(vm, id, onDismiss = { projectFor = null }) }
    if (naming) NewThreadDialog(vm, onDismiss = { naming = false }, onStarted = { view = HistoryView.Threads })
}

/** The New thread dialog (a name and an optional emoji), shared by History and the drawer's New thread button. Starting calls `vm.tab("Chat")` then `vm.newThread(title)`. */
@Composable internal fun NewThreadDialog(vm: BeaconViewModel, onDismiss: () -> Unit, onStarted: () -> Unit = {}) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("New thread")) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatAvatar(threadTitle(emoji, name), "new-thread", size = 48.dp, modifier = Modifier.testTag("new-thread-avatar"))
                OutlinedTextField(name, { name = it.take(100) }, label = { Text(uiText("Thread name")) }, singleLine = true, modifier = Modifier.weight(1f).testTag("new-thread-name"))
            }
            // Optional: the pick is only the start of the Hermes title.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                threadEmojis.forEach { choice ->
                    val chosen = emoji == choice
                    Box(Modifier.size(48.dp).clip(CircleShape).background(if (chosen) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .selectable(chosen, role = androidx.compose.ui.semantics.Role.Button) { emoji = if (chosen) "" else choice }.testTag("new-thread-emoji:$choice"), contentAlignment = Alignment.Center) { Text(choice, style = MaterialTheme.typography.titleLarge) }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { onDismiss(); onStarted(); vm.tab("Chat"); vm.newThread(threadTitle(emoji, name)) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("new-thread-start")) { Text(uiText("Start thread")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Cancel")) } })
}

private val proactivePromptDismissedKey = stringPreferencesKey("proactive_prompt_dismissed")

private data class CallTranscriptMetadata(val durationSeconds: Long?, val provider: String?)

internal fun callTranscriptGroupsMatchingSearch(groups: List<CallTranscriptGroup>, search: String): List<CallTranscriptGroup> =
    groups.filter { group -> search.isBlank() || group.lines.any { it.text.contains(search, ignoreCase = true) } }

/** A Bot Chat speaks as its bot: its reply label and composer hint use the bot's name, not the assistant's. */
internal fun botChatName(activeBotProfile: String?, botSessionId: String, chatSessionId: String, profiles: List<JSONObject>): String? =
    activeBotProfile?.takeIf { botSessionId.isNotBlank() && botSessionId == chatSessionId }?.let { profileDisplayName(it, profiles) }

/** The line under a chat's title: what Hermes is doing while a turn streams, a call in progress, else the model. */
internal fun chatHeaderSubtitle(streaming: Boolean, hasReplyText: Boolean, inCall: Boolean, model: String, assistantChat: Boolean): String = when {
    streaming -> if (hasReplyText) "typing…" else "thinking…"
    inCall -> "Voice conversation"
    model.isNotBlank() -> model
    assistantChat -> "Text and voice, together"
    else -> ""
}

/** A chat Hermes has saved rows for, even when none of them shows (hidden steering, folded tools): it is not a new chat. */
internal fun savedConversation(loadedRows: Int, recordedCount: Int, listedCount: Int): Boolean = loadedRows > 0 || recordedCount > 0 || listedCount > 0

/** The line under the transcript. While a turn runs the live row speaks; offline, the top banner already says the chat is the saved copy. */
internal fun transcriptStatusShown(status: String, streaming: Boolean, connected: Boolean): Boolean =
    status.isNotBlank() && !streaming && !(!connected && status.startsWith("Cached"))

/** Fixed texts the chat shows through variables: tool outcomes and the header's status line. */
internal val ChatStrings get() = ToolOutcome.entries.map(::toolOutcomeLabel) + friendlyToolNames.values + listOf("typing…", "thinking…", "Voice conversation",
    "Queued on this phone", "Sending guidance", "Sending", "Accepted; delivery pending", "Finished; check history",
    "Turn failed; check history", "Turn interrupted; check history", "Queued message canceled", "Message rejected", "Sent", "Send outcome unknown",
    "Will send after this reply", "Run command", "Steer", "Queue")

/** The header's second line: the chat's project, then what [chatHeaderSubtitle] says. Hermes status (thinking, typing, a call) takes the line over from the project. */
internal data class ChatHeaderLine(val project: String, val text: String)

internal fun chatHeaderLine(streaming: Boolean, hasReplyText: Boolean, inCall: Boolean, model: String, assistantChat: Boolean, project: String): ChatHeaderLine {
    val status = streaming || inCall
    val shown = if (status) "" else project.trim()
    // The project already names the chat's place, so the assistant tagline would only crowd it.
    return ChatHeaderLine(shown, if (shown.isNotBlank() && model.isBlank()) "" else chatHeaderSubtitle(streaming, hasReplyText, inCall, model, assistantChat))
}

/** A small folder and the project's name, then the model: "[folder] Research · gpt-6-luna", one line that ellipsizes as a whole. */
@Composable private fun ChatHeaderSubtitle(line: ChatHeaderLine) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (line.project.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.FolderOpen, uiText("Project"), Modifier.size(12.dp), tint = muted)
            Text(line.project + if (line.text.isNotBlank()) "  ·  " + uiText(line.text) else "", Modifier.padding(start = 4.dp).testTag("chat-header-project"), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        else if (line.text.isNotBlank()) Text(uiText(line.text), Modifier.testTag("chat-header-subtitle"), style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable fun ChatScreen(vm: BeaconViewModel, beacon: Boolean = false, onComposerHeight: (Int) -> Unit = {}, navigationBack: (() -> Unit)? = null, titleOverride: String? = null) {
    val bot by vm.botMode.state.collectAsStateWithLifecycle()
    val openChat by vm.chat.collectAsStateWithLifecycle()
    val profiles = vm.data.collectAsStateWithLifecycle().value["navigation-profiles"]?.rows("profiles").orEmpty()
    val assistantName = botChatName(bot.activeBot?.profile, bot.sessionId, openChat.sessionId, profiles) ?: LocalAssistantName.current
    CompositionLocalProvider(LocalAssistantName provides assistantName) { ChatScreenBody(vm, beacon, onComposerHeight, navigationBack, titleOverride) }
}

@Composable private fun ChatScreenBody(vm: BeaconViewModel, beacon: Boolean, onComposerHeight: (Int) -> Unit, navigationBack: (() -> Unit)?, titleOverride: String?) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profileDefaults by vm.data.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val chatState by vm.chat.collectAsStateWithLifecycle()
    val composerDraft by vm.composer.collectAsStateWithLifecycle()
    // Saved call turns and alerts sit among the messages by the time they happened.
    val messages = remember(chatState.messages, chatState.callMessages, chatState.alerts, chatState.sessionId, chatState.queued) {
        val localTimes = chatState.queued.filter { it.rowId.isNotBlank() }.associate { it.rowId to it.createdAt }
        dev.hermes.android.voice.interleaveCallMessages(
            dev.hermes.android.voice.interleaveCallMessages(visibleTranscriptMessages(chatState.messages).map { row -> localTimes[row.id]?.let { row.copy(recordedAt = it) } ?: row }, chatState.callMessages.map { Message("call:${it.eventId}", it.role, it.text, sessionId = chatState.sessionId, recordedAt = it.at) }),
            chatState.alerts.map { Message("alert:${it.noticeId}", "assistant", it.text, sessionId = chatState.sessionId, recordedAt = it.createdAt) },
        )
    }
    val alertsByMessageId = remember(chatState.alerts) { chatState.alerts.associateBy { "alert:${it.noticeId}" } }
    val callTranscriptGroups = remember(chatState.callMessages) { groupCallTranscriptEvents(chatState.callMessages) }
    val callIdByMessageId = remember(messages) {
        messages.mapNotNull { message ->
            message.id.takeIf { it.startsWith("call:") }?.removePrefix("call:")
                ?.let(::callTranscriptCallId)?.let { message.id to it }
        }.toMap()
    }
    val firstCallMessageIdByCallId = remember(callIdByMessageId) {
        callIdByMessageId.entries.map { (messageId, callId) -> callId to messageId }.distinctBy { it.first }.toMap()
    }
    val callGroupByFirstMessageId = remember(callTranscriptGroups, firstCallMessageIdByCallId) {
        callTranscriptGroups.mapNotNull { group -> firstCallMessageIdByCallId[group.callId]?.let { it to group } }.toMap()
    }
    // Branch keep_count counts Hermes rows, hidden steering rows included.
    val transcriptIndex = remember(chatState.messages) { chatState.messages.withIndex().associate { it.value.id to it.index } }
    val chat = chatState.copy(messages = messages)
    val taskResultScope = (vm.pcScope() ?: Scope(ui.origin, ui.owner, ui.profile)).session(chat.sessionId)
    val taskResultKey = "task-result:${taskResultScope.key}"
    val taskResultData = profileDefaults[taskResultKey]
    val taskResult = taskResultData?.string("reason").orEmpty()
    val foldedToolResults = remember(chat.raw, chat.messages) { foldToolResults(chat.raw.rows("messages"), chat.messages) }
    val toolResultTitles = remember(chat.raw) { toolResultTitles(chat.raw.rows("messages")) }
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    var surfacedCallError by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(call.error, call.failureReason) {
        if (call.error.isBlank()) surfacedCallError = ""
        // A failed call shows its reason on its own conversation's call panel, not as a notice over the current chat.
        else if (call.failureReason.isBlank() && call.error != surfacedCallError) { surfacedCallError = call.error; vm.notice(call.error) }
    }
    val failedHere = call.failedCallConversation(call.callId)?.let { it == vm.authenticatedScope() } == true
    LaunchedEffect(call.callId, call.failureReason) {
        if (call.failedCallConversation(call.callId)?.let { it != vm.authenticatedScope() } == true) vm.openFailedCallOnce(call.callId)
    }
    val handoff by vm.voiceHandoff.collectAsStateWithLifecycle()
    val pendingVoiceCall by vm.voiceCallRequest.collectAsStateWithLifecycle()
    if(handoff?.scope==vm.authenticatedScope()&&handoff?.targetCall?.isBlank()==true) AlertDialog(
        onDismissRequest=vm::cancelVoiceHandoff,
        title={Text(uiText("Switching voice…"))},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth());Text(uiText("Reconnecting the call…")) }},
        confirmButton={TextButton(vm::cancelVoiceHandoff) { Text(uiText("Stop")) }})
    val side by vm.side.collectAsStateWithLifecycle()
    val settings = LocalBeaconSettings.current
    val ready = !beacon || vm.isBeaconConversation()
    val proactiveScope = (vm.pcScope() ?: Scope(ui.origin, ui.owner, ui.profile)).copy(profile = preferences.assistantProfile, session = "")
    val proactiveKey = proactiveSettingsKey(proactiveScope)
    val proactive = profileDefaults[proactiveKey]
    val proactiveLoaded = proactive != null && proactive.has("enabled") && !proactive.has("_error")
    val proactiveEnabled = proactiveLoaded && proactive?.optBoolean("enabled") == true
    val pluginStatus = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin").orEmpty()
    val missingPluginShown = beacon && (pluginStatus == "absent" || proactive?.optBoolean("_pluginUpdateRequired") == true)
    val proactiveContext = LocalContext.current.applicationContext
    val promptMemory by remember(proactiveContext) {
        proactiveContext.preferences.data.map { values ->
            val saved = values[proactivePromptDismissedKey]?.let { runCatching { JSONObject(it) }.getOrNull() }
            saved?.string("state").orEmpty() to saved?.string("dismissed").orEmpty()
        }
    }.collectAsStateWithLifecycle(initialValue = null as Pair<String, String>?)
    val promptText = when {
        !proactiveLoaded || pluginStatus != "available" -> ""
        !proactiveEnabled -> assistantText("Let Beacon tell you what needs you")
        else -> ""
    }
    val promptSignature = when {
        !proactiveLoaded -> ""
        !proactiveEnabled -> "${proactiveScope.key}|off"
        else -> "${proactiveScope.key}|on"
    }
    val promptDismissed = promptMemory?.let { it.first == promptSignature && it.second == promptSignature } == true
    val showProactivePrompt = beacon && ui.tab == "Beacon" && pluginStatus == "available" && promptMemory != null && promptText.isNotBlank() && !promptDismissed
    val canonical = ready && chat.sessionId.isNotBlank()
    val permanentBotChat = ui.botModeEnabled && ui.tab == "More" && ui.morePage == "Bots"
    val activeHere = call.active && call.conversationScope == vm.authenticatedScope()
    val callHistoryClient = vm.client
    val callHistoryScope = vm.authenticatedScope()?.copy(session = "")
    val callGroupIds = remember(callTranscriptGroups) { callTranscriptGroups.map { it.callId } }
    var callMetadata by remember(callHistoryClient, callHistoryScope, chat.sessionId, callGroupIds) {
        mutableStateOf(emptyMap<String, CallTranscriptMetadata>())
    }
    LaunchedEffect(callHistoryClient, callHistoryScope, chat.sessionId, callGroupIds) {
        if (callHistoryClient == null || callHistoryScope == null || callGroupIds.isEmpty()) return@LaunchedEffect
        val response = try {
            val client = callHistoryClient.detached()
            try {
                client.get("/api/plugins/beacon/mobile/calls", mapOf("profile" to callHistoryScope.profile, "limit" to "50"))
            } finally { client.closeNative() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        if (vm.client !== callHistoryClient || vm.authenticatedScope()?.copy(session = "") != callHistoryScope || vm.chat.value.sessionId != chat.sessionId) return@LaunchedEffect
        val wantedCallIds = callGroupIds.toSet()
        callMetadata = response.rows("calls").mapNotNull { row ->
            row.string("id").takeIf { it in wantedCallIds && row.string("conversation_id") == chat.sessionId }?.let { id ->
                id to CallTranscriptMetadata(
                    (row.opt("duration_s") as? Number)?.toLong()?.takeIf { it >= 0 },
                    row.string("provider").takeIf(String::isNotBlank),
                )
            }
        }.toMap()
    }
    val scope = rememberCoroutineScope()
    var alertsSending by remember(chat.sessionId) { mutableStateOf(emptySet<String>()) }
    fun sendAlertFeedback(noticeId: String, verdict: String) {
        if (noticeId in alertsSending || verdict !in setOf("important", "not_important")) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(ui.profile) } catch (_: Throwable) { vm.notice("Couldn't send feedback. Try again."); return }
        alertsSending = alertsSending + noticeId
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/feedback", mapOf("profile" to ui.profile), method = "POST", data = json("notice_id" to noticeId, "verdict" to verdict))
                vm.alertChosen(taskResultScope, noticeId, verdict)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (isMissingBeaconPluginRoute(error)) vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true))
                else vm.notice(proactiveErrorMessage(error, "Couldn't send feedback. Try again."))
            } finally { client.closeNative(); alertsSending = alertsSending - noticeId }
        }
    }
    LaunchedEffect(promptSignature, promptText, promptMemory) {
        if (pluginStatus == "available" && promptSignature.isNotBlank() && promptText.isBlank() && promptMemory?.let { it.first != promptSignature } == true)
            proactiveContext.preferences.edit { it[proactivePromptDismissedKey] = json("state" to promptSignature, "dismissed" to "").toString() }
    }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, ui.tab, preferences.assistantProfile, pluginStatus) {
        if (beacon && ui.tab == "Beacon" && ui.connected && ui.profile == preferences.assistantProfile && pluginStatus == "available") {
            val source = vm.client ?: return@LaunchedEffect
            val profile = preferences.assistantProfile
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
                        .put("_message", proactiveErrorMessage(error, "Couldn't load email settings. Try again.")))
            }
        }
    }
    var menu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var threadPosts by remember(chat.sessionId) { mutableStateOf(false) }
    var activitySheet by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    var backgroundActivity by remember(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(BackgroundActivitySummary()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(chat.streamId, ui.notice) {
        if (chat.streamId.isBlank() && ui.notice == "Stopping the current turn") vm.notice("")
    }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, ui.connected, chat.sessionId, lifecycle, pluginStatus) {
        if (!ui.connected) return@LaunchedEffect
        val source = vm.client?.takeIf { it.scope.origin == ui.origin && it.scope.owner == ui.owner && it.scope.profile == ui.profile } ?: return@LaunchedEffect
        val profile = ui.profile
        val sessionId = chat.sessionId.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val current = fetchBackgroundActivity(source, profile, sessionId, pluginStatus == "available")
                if (vm.client === source && vm.client?.scope?.origin == ui.origin && vm.client?.scope?.owner == ui.owner && vm.client?.scope?.profile == profile && vm.chat.value.sessionId == sessionId)
                    backgroundActivity = current
                delay(30_000L)
            }
        }
    }
    var composerDetails by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    var projects by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    if (projects) ChatProjectPicker(vm, chat.sessionId, onDismiss = { projects = false })
    // The chat menu offers Add to or Remove from its project at once, without a loading step.
    // Read again once a send from here has landed: before its first message Hermes has no row for a new chat, so the answer was
    // "no project". An accepted send sets a stream; one whose outcome was unknown is settled from history, so both count.
    var sentHere by remember(chat.sessionId) { mutableStateOf(false) }
    LaunchedEffect(chat.streamId, chat.uncertain) { if (chat.streamId.isNotBlank() || chat.uncertain.isNotBlank()) sentHere = true }
    val chatSaved = sentHere && messages.isNotEmpty()
    LaunchedEffect(chat.sessionId, ui.connected, chatSaved) { if (chat.sessionId.isNotBlank() && ui.connected) vm.loadConversationProject(chat.sessionId, force = chatSaved, quiet = true) }
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf("") }
    var showSearch by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val promptScope = vm.authenticatedScope()?.takeIf { it.copy(session = "") == vm.client?.scope }
    val questionId = chat.clarification?.let { it.string("clarify_id", it.string("id")) }.orEmpty()
    var questionAnswers by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, questionId,
        stateSaver = androidx.compose.runtime.saveable.mapSaver<Map<String, String>>(save = { it }, restore = { saved -> saved.mapValues { it.value as String } })) { mutableStateOf(emptyMap<String, String>()) }
    val matchingCallGroups = remember(callTranscriptGroups, search) { callTranscriptGroupsMatchingSearch(callTranscriptGroups, search) }
    val matchingCallIds = remember(matchingCallGroups) { matchingCallGroups.mapTo(mutableSetOf()) { it.callId } }
    // Find keeps the whole transcript: matches are highlighted and stepped through, never filtered.
    val visibleMessages = remember(messages, foldedToolResults, callIdByMessageId, firstCallMessageIdByCallId) {
        messages.withIndex().filter { (_, message) ->
            val callId = callIdByMessageId[message.id]
            (callId == null || firstCallMessageIdByCallId[callId] == message.id) && message.id !in foldedToolResults.foldedMessageIds
        }.let(::mergeToolOnlyRuns)
    }
    // Ids of the rows that match, oldest first; a call card matches through any of its lines.
    val findMatches = remember(visibleMessages, search, foldedToolResults, callIdByMessageId, matchingCallIds) {
        if (search.isBlank()) emptyList() else visibleMessages.map { it.value }.filter { message ->
            val callId = callIdByMessageId[message.id]
            if (callId != null) callId in matchingCallIds
            else message.text.contains(search, true) || message.tools.any { tool -> foldedToolResults.byCallId[toolCallResultId(tool)]?.content?.contains(search, true) == true }
        }.map { it.id }
    }
    val findMatchSet = remember(findMatches) { findMatches.toSet() }
    // 0 is the newest match, as in Telegram; Up steps to older ones.
    var findPosition by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, search) { mutableIntStateOf(0) }
    val currentMatchId = findMatches.getOrNull(findMatches.size - 1 - findPosition.coerceIn(0, (findMatches.size - 1).coerceAtLeast(0)))
    val dayStarts = remember(visibleMessages) { messageDayStarts(visibleMessages.map { it.value.id to it.value.recordedAt }) }
    val liveShown = chat.streamId.isNotBlank() || chat.liveText.isNotBlank() || chat.liveReasoning.isNotBlank()
    val liveBubble = chat.liveText.isNotBlank()
    val sendingShown = chatSendInFlight(chat)
    // A screen reader hears the reply once, when it is complete.
    val view = LocalView.current
    var streamedReply by remember(chat.sessionId) { mutableStateOf("") }
    LaunchedEffect(chat.liveText) { if (chat.liveText.isNotBlank()) streamedReply = chat.liveText }
    LaunchedEffect(chat.streamId) {
        if (chat.streamId.isBlank() && streamedReply.isNotBlank()) {
            @Suppress("DEPRECATION") view.announceForAccessibility(chatPreviewText(streamedReply))
            streamedReply = ""
        }
    }
    // The reply being written and the message being sent join the run before them, as their stored forms will.
    val groups = remember(visibleMessages, dayStarts, callGroupByFirstMessageId, alertsByMessageId, liveShown, liveBubble, sendingShown) {
        bubbleGroups(
            visibleMessages.map { it.value } + listOfNotNull(
                Message(LIVE_REPLY_ID, "assistant", if (liveBubble) "live" else "").takeIf { liveShown },
                Message(SENDING_MESSAGE_ID, "user", "sending").takeIf { sendingShown },
            ),
            dayStarts.keys, callGroupByFirstMessageId.keys + alertsByMessageId.keys,
        )
    }
    // The newest line and the viewed mark of this chat, for the chat list's preview and unread dot.
    val openRow = remember(ui.sessions, chat.sessionId, ui.profile) { ui.sessions.firstOrNull { sessionRowId(it) == chat.sessionId && it.string("profile", ui.profile) == ui.profile } }
    val openRowActivity = openRow?.let(::rowActivityMillis) ?: 0L
    val openRowMessageCount = openRow?.optInt("message_count") ?: 0
    val newestMessage = remember(chatState.messages) { visibleTranscriptMessages(chatState.messages).lastOrNull { it.role == "user" || it.role == "assistant" && it.text.isNotBlank() } }
    LaunchedEffect(chat.sessionId, chat.projectionReady, newestMessage?.id, newestMessage?.recordedAt, openRowActivity) {
        if (chat.projectionReady && chat.sessionId.isNotBlank()) vm.noteChatViewed(chat.sessionId, newestMessage)
    }
    val messageIds = remember(visibleMessages) { visibleMessages.map { it.value.id } }
    val ids by rememberUpdatedState(messageIds)
    val list = key(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) {
        val start = initialConversationPosition(messageIds, chat.anchorId, chat.anchor, chat.anchorOffset)
        rememberLazyListState(initialFirstVisibleItemIndex = start.first, initialFirstVisibleItemScrollOffset = start.second)
    }
    val motionEnabled = rememberNativeMotionEnabled()
    val sphereMotionEnabled = motionEnabled && settings.optBoolean("animate_background", true)
    val sphereStreaming = chat.streamId.isNotBlank()
    val sphereWaitingForFirstToken = sphereStreaming && chat.liveText.isBlank()
    val sphereHasError = chat.status.contains("error", ignoreCase = true) || chat.status.contains("failed", ignoreCase = true)
    val rawSphereState = resolveSphereState(sphereHasError, sphereStreaming, sphereWaitingForFirstToken)
    var sphereState by remember(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(rawSphereState) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, chat.sessionId, rawSphereState) {
        if (sphereState == dev.hermes.android.ui.sphere.SphereState.Thinking && rawSphereState == dev.hermes.android.ui.sphere.SphereState.Streaming) delay(1_500L)
        sphereState = rawSphereState
    }
    val sphereIntensity by animateFloatAsState(if (sphereStreaming) .7f else 0f, if (sphereMotionEnabled) tween(if (sphereStreaming) 1_000 else 2_000) else snap(), label = "chatSphereIntensity")
    val activeSphereTool = sphereStreaming && chat.liveTools.any { toolOutcomeOf(it, streaming = true) == ToolOutcome.Running }
    val sphereToolCallBurst by animateFloatAsState(if (activeSphereTool) 1f else 0f, if (sphereMotionEnabled) tween(if (activeSphereTool) 200 else 1_200) else snap(), label = "chatSphereToolBurst")
    val avatarState = dev.hermes.android.ui.sphere.AvatarRenderState(sphereState, sphereIntensity, sphereToolCallBurst, paused = !sphereMotionEnabled)
    var showCallHistory by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    var showCallHistorySheet by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    var callHistoryTargetSession by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    var callHistoryTargetId by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    BackHandler(showCallHistorySheet) { showCallHistorySheet = false }
    BackHandler(activeHere && showCallHistory) { showCallHistory = false }
    LaunchedEffect(call.active) { if (!call.active) showCallHistory = false }
    LaunchedEffect(ready) { if (!ready) { menu = false; moreMenu = false; showSearch = false; search = "" } }
    var follow by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) { mutableStateOf(followsTail(true, settings.optBoolean("auto_scroll_follow", true), chat.anchorId.isNotBlank() || chat.anchor != 0 || chat.anchorOffset != 0)) }
    var anchorRestored by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) { mutableStateOf(chat.projectionReady) }
    var ownerScrolling by remember(list) { mutableStateOf(false) }
    val ownerScroll = remember(list) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && (consumed.y != 0f || available.y != 0f)) ownerScrolling = true
            return Offset.Zero
        }
    } }
    var permissionClient by remember { mutableStateOf<dev.hermes.android.data.BeaconClient?>(null) }
    var permissionConversation by remember { mutableStateOf<String?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val requested=permissionClient;val conversation=permissionConversation;permissionClient=null;permissionConversation=null
        if(allowed&&requested!=null&&conversation!=null&&conversation==vm.authenticatedScope()?.session&&vm.client?.scope==requested.scope) scope.launch {
            runCatching { vm.stopReadAloud();vm.app.calls.start(requested,conversation) }.onFailure { error ->
                if(error is MissingVoiceKeyException) vm.openVoiceKeySettingsForCall(requested,conversation) else vm.notice(error.message.orEmpty())
            }
        } else vm.notice(if(allowed) "Open the conversation again before calling" else "Allow microphone permission to call ${dev.hermes.android.data.currentAssistantName}")
    }
    fun requestCall(active:BeaconClient,conversation:String) {
        scope.launch {
            if(!voiceKeyConfiguredOnIo { VoiceKeyStore(vm.app) }) {
                vm.openVoiceKeySettingsForCall(active,conversation)
                return@launch
            }
            if(androidx.core.content.ContextCompat.checkSelfPermission(vm.app,Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED) {
                runCatching { vm.stopReadAloud();vm.app.calls.start(active.detached(),conversation) }.onFailure { error ->
                if(error is MissingVoiceKeyException) vm.openVoiceKeySettingsForCall(active,conversation) else vm.notice(error.message.orEmpty())
                }
            } else { permissionClient=active.detached();permissionConversation=conversation;microphone.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    val callThisChat: () -> Unit = { vm.client?.let { requestCall(it,chat.sessionId) };Unit }
    LaunchedEffect(pendingVoiceCall,ui.tab,ui.morePage,permanentBotChat,chat.sessionId) {
        val pending=pendingVoiceCall?:return@LaunchedEffect
        if(ui.tab !in setOf("Chat","Beacon") && !permanentBotChat) return@LaunchedEffect
        vm.consumeVoiceCallRequest(pending)
        val active=vm.client
        if(active==null||active.scope!=pending.scope||chat.sessionId!=pending.conversationId) vm.notice("The conversation changed. Start the call again.")
        else requestCall(active,pending.conversationId)
    }
    LaunchedEffect(list, search, chat.sessionId) { snapshotFlow { Triple(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.isScrollInProgress) to ownerScrolling }.collect { (position, byOwner) ->
        if (byOwner && search.isBlank()) {
            follow = !list.canScrollForward
            val current = vm.chat.value
            if (follow) { if (current.anchor > 0 || current.anchorOffset > 0 || current.anchorId.isNotBlank()) vm.anchor(0, 0, "") }
            else {
                val id = ids.getOrNull(position.first)
                if (id != null) vm.anchor(position.first, position.second, id)
            }
        }
        if (!position.third) ownerScrolling = false
    } }
    LaunchedEffect(list, callHistoryTargetSession, callHistoryTargetId, chat.sessionId, chat.projectionReady, chat.callMessages, chat.messages, chat.raw) {
        if (callHistoryTargetId.isBlank() || callHistoryTargetSession != chat.sessionId || !chat.projectionReady) return@LaunchedEffect
        val event = vm.firstCallTranscript(callHistoryTargetId) ?: chat.callMessages.firstOrNull {
            it.eventId.startsWith("$callHistoryTargetId:user:") || it.eventId.startsWith("$callHistoryTargetId:assistant:")
        }
        if (event == null) {
            callHistoryTargetSession = ""
            callHistoryTargetId = ""
            return@LaunchedEffect
        }
        val rowId = firstCallMessageIdByCallId[callHistoryTargetId]
            ?: chat.callMessages.firstOrNull { it.eventId == event.eventId }?.let { "call:" + it.eventId }
            ?: listOf(event.messageId, event.eventId).firstOrNull { id -> id.isNotBlank() && messages.any { it.id == id } }
            ?: return@LaunchedEffect
        val index = visibleMessages.indexOfFirst { it.value.id == rowId }
        if (index >= 0) {
            showSearch = false
            search = ""
            follow = false
            withFrameNanos { }
            snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > index }
            list.animateScrollToItem(index)
            callHistoryTargetSession = ""
            callHistoryTargetId = ""
        }
    }
    val alertFocus by vm.alertFocus.collectAsStateWithLifecycle()
    LaunchedEffect(list, alertFocus, chat.sessionId, chat.projectionReady, visibleMessages) {
        val target = alertFocus ?: return@LaunchedEffect
        if (target.first.session != chat.sessionId || !chat.projectionReady) return@LaunchedEffect
        // Until the alert is in the chat the scroll waits; the view model drops it once the newest read does not find it.
        val index = visibleMessages.indexOfFirst { it.value.id == "alert:${target.second}" }
        if (index < 0) return@LaunchedEffect
        showSearch = false
        search = ""
        follow = false
        withFrameNanos { }
        snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > index }
        list.animateScrollToItem(index)
        vm.consumeAlertFocus(target)
    }
    val conversationEntry = remember { Animatable(1f) }
    LaunchedEffect(chat.sessionId, beacon, motionEnabled) {
        if (motionEnabled) { conversationEntry.snapTo(.35f); conversationEntry.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
        else conversationEntry.snapTo(1f)
    }
    val conversationContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.clipToBounds().chatBeaconBackground().graphicsLayer { alpha = conversationEntry.value }) {
            LaunchedEffect(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.messages.isNotEmpty(), search, ready) {
                if (!ready) return@LaunchedEffect
                // While finding, the current match decides the position (below); closing find returns to the saved place.
                if (search.isNotBlank()) return@LaunchedEffect
                if ((!anchorRestored || !follow) && chat.messages.isNotEmpty()) {
                    // Await this panel's layout, not the last session's retained item count.
                    withFrameNanos { }
                    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= visibleMessages.size }
                    if (chat.anchor > 0 || chat.anchorOffset > 0 || chat.anchorId.isNotBlank()) {
                        follow = false
                        val index = anchoredIndex(messageIds, chat.anchorId, chat.anchor) ?: 0
                        // Apply the saved position during the attached list's next measurement.
                        list.requestScrollToItem(index, chat.anchorOffset)
                    }
                    anchorRestored = true
                }
            }
            LaunchedEffect(anchorRestored, follow, search, chat.messages.size, visibleMessages.size, chat.liveText, chat.callMessages, chat.queued.size) {
                if (anchorRestored && follow && search.isBlank() && (chat.messages.isNotEmpty() || chat.queued.isNotEmpty())) {
                    if (chat.anchor > 0 || chat.anchorOffset > 0 || chat.anchorId.isNotBlank()) vm.anchor(0, 0, "")
                    // Markdown, media and fold layouts can grow after the first measurement.
                    // Keep following their measured tail, rather than only the incoming text/count.
                    snapshotFlow {
                        val layout = list.layoutInfo
                        // Rows shown, not raw messages: folded tool results and merged tool rounds take no row of their own.
                        if (list.canScrollForward && layout.totalItemsCount >= visibleMessages.size) {
                            val target = (layout.totalItemsCount - 1).coerceAtLeast(0)
                            val available = (layout.viewportEndOffset - layout.viewportStartOffset - layout.beforeContentPadding - layout.afterContentPadding).coerceAtLeast(1)
                            val offset = ((layout.visibleItemsInfo.firstOrNull { it.index == target }?.size ?: 0) - available).coerceAtLeast(0)
                            target to offset
                        } else null
                    }.collect { target ->
                        // A clamped scroll must not request the same measurement forever.
                        if (target != null && !ownerScrolling && follow && search.isBlank()) list.requestScrollToItem(target.first, target.second)
                    }
                }
            }
            LaunchedEffect(follow, search, chat.anchorId, chat.anchor, chat.anchorOffset) {
                if (!follow && search.isBlank() && chat.anchorId.isNotBlank()) {
                    val index = anchoredIndex(messageIds, chat.anchorId, chat.anchor) ?: return@LaunchedEffect
                    snapshotFlow { list.layoutInfo.visibleItemsInfo.map { it.index to it.size } }.collect {
                        // Reattachment measures Markdown/media asynchronously; restore again when their height settles.
                        if (!ownerScrolling && !follow && (list.firstVisibleItemIndex != index || list.firstVisibleItemScrollOffset != chat.anchorOffset))
                            list.requestScrollToItem(index, chat.anchorOffset)
                    }
                }
            }
            // Each step brings the current match into view; the transcript itself stays whole.
            LaunchedEffect(currentMatchId, list, ready) {
                val id = currentMatchId?.takeIf { ready } ?: return@LaunchedEffect
                val index = visibleMessages.indexOfFirst { it.value.id == id }.takeIf { it >= 0 } ?: return@LaunchedEffect
                val target = (if (missingPluginShown) 1 else 0) + index
                withFrameNanos { }
                snapshotFlow { list.layoutInfo.totalItemsCount }.first { it > target }
                if (motionEnabled) list.animateScrollToItem(target) else list.scrollToItem(target)
            }
            // Focus only while the field (and its requester) is composed; a hidden panel or rotation must not request focus.
            if (canonical && showSearch) LaunchedEffect(chat.sessionId) { searchFocusRequester.requestFocus() }
            if (canonical && showSearch) FindBar(search, { search = it }, onClose = { showSearch = false; search = "" }, focusRequester = searchFocusRequester)
            if (beacon && !ready) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Without an error the conversation is only switching (answering a call does this), so it says Opening, not unavailable.
                EmptyState(assistantText(if (ui.beaconError.isBlank()) "Opening Beacon’s conversation" else "Beacon’s conversation is unavailable"), uiText(ui.beaconError).ifBlank { uiText("Your chats and drafts are saved.") }, action = if (ui.beaconOpening) "" else assistantText("Retry Beacon"), onAction = { vm.openBeacon() })
            } else if (chat.sessionId.isBlank()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(.9f).aspectRatio(1f).clipToBounds().testTag("chat-sphere-empty")) { ChatSphere(Modifier.fillMaxSize(), avatarState) }
                EmptyState(uiText(if (ui.newChatOpening) "Opening your conversation" else "Make room for a thought"), if (ui.newChatError.isNotBlank()) ui.newChatError else uiText(if (ui.newChatOpening) "Keep typing. Your draft stays here while the conversation opens." else "Ask, explore or turn an idea into working progress."), icon = null, action = if (ui.newChatError.isNotBlank() && !ui.newChatOpening) uiText("Retry") else "", onAction = { vm.retryInitialChat() }, centered = true)
                if (!ui.newChatOpening && ui.newChatError.isBlank()) StarterPrompts { vm.composer(TextFieldValue(it, TextRange(it.length))) }
            }
            else if (!chat.projectionReady) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            else Column(Modifier.weight(1f).fillMaxWidth()) {
                if (taskResult.isNotBlank()) Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("task-result-card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(uiText("Task result"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { vm.dismissTaskResult(taskResultScope) }, modifier = Modifier.testTag("task-result-dismiss")) {
                                Icon(Icons.Rounded.Close, contentDescription = uiText("Dismiss"))
                            }
                        }
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            Markdown(taskResult, vm, taskResultScope, interactive = false)
                        }
                    }
                }
                if (showProactivePrompt) Card(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("proactive-prompt-card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(promptText, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.contentDirection())
                            IconButton(onClick = { scope.launch { proactiveContext.preferences.edit { it[proactivePromptDismissedKey] = json("state" to promptSignature, "dismissed" to promptSignature).toString() } } }, modifier = Modifier.testTag("proactive-prompt-dismiss")) {
                                Icon(Icons.Rounded.Close, contentDescription = uiText("Dismiss"))
                            }
                        }
                        TextButton(onClick = { vm.more("Assistant") }, modifier = Modifier.testTag("proactive-prompt-settings")) { Text(uiText("Proactive settings")) }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag("transcript-layer")) {
                    // About 80% of the width, never wider than 560 dp on a large screen.
                    val bubbleMax = (maxWidth * 0.8f).coerceAtMost(560.dp)
                    // While the scroll-to-latest button shows, the list ends above it, so it never covers the last bubble's time.
                    val latestShown = ready && !follow && list.canScrollForward
                    val transcriptBottom by animateDpAsState(if (latestShown) 72.dp else 12.dp, if (motionEnabled) tween(200) else snap(), label = "transcript-bottom")
                    LazyColumn(Modifier.fillMaxSize().nestedScroll(ownerScroll).testTag("conversation-transcript"), state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = transcriptBottom)) {
                    if (missingPluginShown) item("missing-beacon-plugin") {
                        MissingBeaconPluginState(Modifier.testTag("beacon-plugin-missing"), compact = true)
                    }
                    if (chat.messages.isEmpty() && chat.streamId.isBlank() && !chatSendInFlight(chat)) item("empty-state") {
                        // A saved chat whose rows are all hidden is not a new one: it gets no first-run welcome.
                        if (savedConversation(chatState.messages.size, chat.raw.optInt("message_count"), openRowMessageCount)) EmptyState(uiText("Nothing to show here yet"), "", Icons.Rounded.ChatBubbleOutline, centered = true)
                        else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(.9f).aspectRatio(1f).clipToBounds().testTag("chat-sphere-empty")) { ChatSphere(Modifier.fillMaxSize(), avatarState) }
                            EmptyState(uiText("Where shall we begin?"), if (beacon) assistantText("Beacon keeps the same context across text, calls and completed tasks.") else uiText("Your work starts here. Choose a model and send your first message."), icon = null, centered = true)
                            StarterPrompts { vm.composer(TextFieldValue(it, TextRange(it.length))) }
                        }
                    }
                    items(visibleMessages, key = { (_, message) -> callGroupByFirstMessageId[message.id]?.let { "call:${it.callId}" } ?: message.id }) { (index, message) ->
                        dayStarts[message.id]?.let { DateChip(uiText(it)) }
                        val group = callGroupByFirstMessageId[message.id]
                        val alert = alertsByMessageId[message.id]
                        val findHit = when { search.isBlank() || message.id !in findMatchSet -> FindHit.None; message.id == currentMatchId -> FindHit.Current; else -> FindHit.Match }
                        if (group != null) {
                            val metadata = callMetadata[group.callId]
                            CallTranscriptCard(group.callId, group.lines, metadata?.durationSeconds, metadata?.provider,
                                assistantName = LocalAssistantName.current, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).findOutline(findHit, RoundedCornerShape(20.dp)), searchQuery = search)
                        } else if (alert != null) {
                            AlertRow(alert, groups[message.id] ?: BubbleGroup(startsRun = true, joinedTop = false, tail = true), bubbleMax, vm, taskResultScope,
                                canChoose = pluginStatus == "available" && ui.connected && vm.client != null && alert.noticeId !in alertsSending && alert.expiresAt > System.currentTimeMillis() / 1000,
                                onChoose = { sendAlertFeedback(alert.noticeId, it) })
                        } else MessageRow(vm, message, transcriptIndex[message.id] ?: index, groups[message.id] ?: BubbleGroup(startsRun = true, joinedTop = false, tail = true), bubbleMax, toolResultsByCallId = foldedToolResults.byCallId, resultTitle = toolResultTitles[message.id].orEmpty(), confirmedLocalId = chat.queued.firstOrNull { it.state == "sent" && it.rowId == message.id }?.id, onToolExpanded = { expanded -> follow = followAfterToolToggle(follow, expanded) }, find = if (findHit == FindHit.None) "" else search, findHit = findHit)
                    }
                    if (liveShown) item("live") {
                        // Same bubble and spacing as the stored reply, so replacing this row does not move the text.
                        val liveGroup = groups[LIVE_REPLY_ID] ?: BubbleGroup(startsRun = true, joinedTop = false, tail = true)
                        Column(Modifier.fillMaxWidth().padding(top = if (liveGroup.startsRun) 10.dp else 3.dp).testTag("live-reply"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            // A blank meta line holds the place of the stored reply time.
                            if (chat.liveText.isNotBlank()) ChatBubble(own = false, liveGroup, bubbleMax) { BubbleText(chat.liveText, vm, vm.authenticatedScope(), interactive = false); BubbleMeta(0L, LIVE_REPLY_ID, null, reserve = true) }
                            Row(Modifier.heightIn(min = 32.dp).testTag("live-status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BeaconSignal(BeaconMotionMode.Pulse, size = 18.dp)
                                Text(chat.status.ifBlank { uiText("Working") }, Modifier.testTag("message-activity-status").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.widthIn(max = bubbleMax)) { MessageActivity(chat.liveReasoning.takeIf { settings.optBoolean("show_thinking", true) }.orEmpty(), chat.liveTools, "", streaming = true, onToolExpanded = { expanded -> follow = followAfterToolToggle(follow, expanded) }, vm = vm, scope = vm.authenticatedScope()?.session(chat.sessionId)) }
                        }
                    }
                    items(chat.queued.filter { queued -> queued.rowId.isBlank() || chat.messages.none { it.id == queued.rowId } }, key = { "queued:${it.id}" }) { queued ->
                        QueuedMessageRow(vm, queued, bubbleMax, canDismiss = chat.uncertain != queued.id)
                    }
                    chat.approval?.let { p -> item("approval:${p.string("approval_id", p.string("id"))}") {
                        val exactBeaconAction = p.string("approval_id").startsWith("beacon-cf:")
                        Glass(Modifier.padding(top = 10.dp)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(uiText("Approval needed"), style = MaterialTheme.typography.titleMedium)
                            Text(p.string("description", p.string("command", uiText("The agent requests permission to continue."))))
                            if (p.string("command").isNotBlank()) CodeBlock(if (exactBeaconAction) "json" else "shell", p.string("command"), vm)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { promptScope?.let { vm.approval("once", p, it) } }, enabled = promptScope != null && "once" in p.optJSONArray("choices")?.strings().orEmpty()) { Text(uiText("Allow once")) }
                                if (!exactBeaconAction && "session" in p.optJSONArray("choices")?.strings().orEmpty()) OutlinedButton(onClick = { promptScope?.let { vm.approval("session", p, it) } }, enabled = promptScope != null) { Text(uiText("This session")) }
                                TextButton(onClick = { promptScope?.let { vm.approval("deny", p, it) } }, enabled = promptScope != null && "deny" in p.optJSONArray("choices")?.strings().orEmpty()) { Text(uiText("Deny")) }
                                if (!exactBeaconAction && "always" in p.optJSONArray("choices")?.strings().orEmpty()) TextButton(onClick = { promptScope?.let { vm.approval("always", p, it) } }, enabled = promptScope != null) { Text(uiText("Always allow")) }
                            }
                        } }
                    } }
                    chat.clarification?.let { p -> item("clarify:$questionId") { Box(Modifier.padding(top = 10.dp)) { Clarification(vm, p, promptScope, questionAnswers) { id, value -> questionAnswers = questionAnswers + (id to value) } } } }
                    if (chatSendInFlight(chat)) item("sending") { SendingMessage(composerDraft.text, groups[SENDING_MESSAGE_ID] ?: BubbleGroup(startsRun = true, joinedTop = false, tail = true), bubbleMax) }
                    // Offline, the top banner already says the chat is saved; the transcript does not repeat it.
                    else if (transcriptStatusShown(chat.status, chat.streamId.isNotBlank(), ui.connected)) item { Text(uiText(chat.status), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (latestShown) SmallFloatingActionButton(
                        onClick = { follow = true; scope.launch { val last = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); if (motionEnabled) list.animateScrollToItem(last) else list.scrollToItem(last) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).minimumInteractiveComponentSize().testTag("scroll-to-latest"),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) { Icon(Icons.Rounded.ExpandMore, uiText("Scroll to latest"), Modifier.size(24.dp)) }
                }
            }
            if (ready && chat.uncertain.isNotBlank() && chat.status != "Sending") Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("send-unconfirmed"), horizontalAlignment = Alignment.End) {
                // Where the message sits in the transcript: not confirmed, with the choices below.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SendTickIcon(SendTick.Unconfirmed)
                    Text(uiText("Not sure your last message arrived."), style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.reconcileSend() }) { Text(uiText("Check receipt")) }
                    TextButton(onClick = { vm.openForm(resendForm(vm)) }, modifier = Modifier.testTag("send-resend")) { Text(uiText("Resend")) }
                    TextButton(onClick = { vm.reviewPendingSend() }, modifier = Modifier.testTag("send-dismiss")) { Text(uiText("Dismiss")) }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The chat menu may use the screen's height, so every action shows on a phone; a short window still scrolls.
    val actionMenuHeight = (maxHeight - 16.dp).coerceAtLeast(48.dp)
    // Landscape with the keyboard open: the field gets the room the strip and header padding would take.
    val shortWindow = maxHeight < 420.dp
    val headerColorScheme = if (activeHere) MaterialTheme.colorScheme.copy(
        background = Color(0xff080d15),
        surface = Color(0xff101a2b),
        surfaceContainer = Color(0xff101a2b),
        primary = Color(0xffd5a64a),
        onSurface = Color(0xfff5f4f0),
        onSurfaceVariant = Color(0xffc4cede),
    ) else MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        MaterialTheme(colorScheme = headerColorScheme) {
            Glass(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (shortWindow) 0.dp else 8.dp)) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (navigationBack != null) IconButton(onClick = navigationBack, modifier = Modifier.testTag("bot-chat-back")) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Back to bots")) }
                    else IconButton(onClick = LocalOpenNavigationMenu.current ?: { vm.toggleSessions(!ui.showSessions) }, modifier = Modifier.testTag("navigation-toggle")) { Icon(Icons.Rounded.Menu, uiText(if (LocalPersistentNavigation.current) "Hide navigation" else "Show navigation")) }
                    val headerTitle = titleOverride ?: if (beacon || activeHere) LocalAssistantName.current else chat.title
                    // The Beacon chat has its own header: the assistant's living mark and the card's status line. Other chats keep the generic one.
                    val presence = if (beacon) beaconPresence(call.active, ready && chat.streamId.isNotBlank()) else BeaconPresence.Idle
                    if (beacon) BeaconPresenceMark(headerTitle, presence, size = 48.dp, description = assistantText("Beacon"))
                    else ChatAvatar(headerTitle, chat.sessionId, size = 40.dp, beacon = activeHere, description = if (activeHere) assistantText("Beacon") else null)
                    // The title opens the same chat menu as the dots.
                    Column(Modifier.weight(1f).padding(start = 10.dp).heightIn(min = 48.dp).clickable(enabled = ready, onClickLabel = uiText("Conversation actions")) { menu = true }.padding(vertical = 4.dp).testTag("chat-header-title"), verticalArrangement = Arrangement.Center) {
                        Text(plainTitle(headerTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium.contentDirection(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        // Read from the cached project answer, so the project shows with the chat; nothing shows for a chat in no project.
                        val headerProject = chatProjectOf(profileDefaults["project-of:${chat.sessionId}"], chat.sessionId)
                        // In the Beacon chat a running turn or a call is the status line; otherwise the project or tagline.
                        // The model is not named here: the composer's model chip already shows it.
                        val headerLine = chatHeaderLine(chat.streamId.isNotBlank() && !beacon, chat.liveText.isNotBlank(), activeHere && !beacon, "", beacon || activeHere, headerProject?.string("name").orEmpty())
                        if (presence != BeaconPresence.Idle) BeaconStatusText(beaconStatus(presence, ""), presence, MaterialTheme.typography.labelSmall, Modifier.testTag("beacon-header-status"))
                        else if (headerLine.project.isNotBlank() || headerLine.text.isNotBlank()) ChatHeaderSubtitle(headerLine)
                    }
                    // Closing find from here also clears it, so no highlight outlives the find bar.
                    IconButton(onClick = { showSearch = !showSearch; if (!showSearch) search = ""; if (activeHere && showSearch) showCallHistory = true }, enabled = canonical) { Icon(Icons.Rounded.Search, uiText("Search this conversation")) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = ready) { Icon(Icons.Rounded.MoreVert, uiText("Conversation actions")) }
                        val assistantName = LocalAssistantName.current
                        val context = LocalContext.current
                        val shareTitle = uiText("Share conversation")
                        val noShareApp = uiText("No app is available to share this conversation")
                        val idCopied = uiText("Conversation ID copied")
                        val endCall = uiText("End this call to continue")
                        // One chat action as a menu row; Delete is red, as it is in History.
                        val actionItem: @Composable (String) -> Unit = { title ->
                            val label = conversationActionLabel(title, openChatPinned(ui.sessions, chat, ui.profile), chat.raw.optBoolean("archived")).replaceFirstChar { it.uppercase() }
                            val blocked = activeHere && title in setOf("Delete conversation", "Undo last turn", "Compress context", "Shorten history")
                            val ink = if (title in destructiveConversationActions) MaterialTheme.colorScheme.error else Color.Unspecified
                            DropdownMenuItem({ Column { Text(conversationMenuTitle(uiText(label))); if (blocked) Text(endCall, style = MaterialTheme.typography.labelSmall) } }, onClick = { menu = false; moreMenu = false; chatAction(vm, title) }, enabled = nativeConversationAction(title) && (canonical || title == "New conversation") && !blocked, leadingIcon = { Icon(chatActionIcon(label), null) },
                                colors = MenuDefaults.itemColors(textColor = ink, leadingIconColor = ink), modifier = Modifier.testTag("conversation-action:$title"))
                        }
                        DropdownMenu(menu && ready, { menu = false }, modifier = Modifier.heightIn(max = actionMenuHeight).testTag("conversation-actions-menu")) {
                            if (beacon) { DropdownMenuItem({ Text(uiText("Call history")) }, { menu = false; showCallHistorySheet = true }, leadingIcon = { Icon(Icons.Rounded.History, null) }, modifier = Modifier.testTag("call-history-open")); HorizontalDivider() }
                            // Groups are split by dividers, without headings; Delete sits apart at the very end, in red.
                            val menuGroups = conversationMenuGroups(conversationActionGroups.values.toList(), dropNewConversation = permanentBotChat)
                            menuGroups.daily.forEachIndexed { index, titles ->
                                if (index > 0) HorizontalDivider()
                                titles.forEach { actionItem(it) }
                            }
                            HorizontalDivider()
                            if (chat.queued.isNotEmpty() || chat.uncertain.isNotBlank()) DropdownMenuItem({ Text(uiText("Message queue")) }, {
                                menu = false; follow = true
                                scope.launch { list.scrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                            }, enabled = !activeHere)
                            if (composerDraft.text.startsWith("/")) DropdownMenuItem({ Text(uiText(if (chat.streamId.isNotBlank()) "Queue as text" else "Send as text")) }, { menu = false; if (chat.streamId.isNotBlank()) vm.queue() else vm.send() }, enabled = canonical && !activeHere && chat.uncertain.isBlank())
                            if (canonical && !activeHere && chat.streamId.isBlank()) ConversationProjectItem(vm, chat.sessionId, onAdd = { projects = true }, onDone = { menu = false }, onOpen = { openProjectFromChat(vm, it) })
                            if (canonical && chat.messages.isNotEmpty()) DropdownMenuItem({ Text(uiText("Share conversation")) }, { menu = false
                                runCatching { context.startActivity(Intent.createChooser(formattedShareIntent(conversationShareText(chat.messages.map { it.role to safeHistoryMessageText(it.role, it.text) }, assistantName)), shareTitle)) }
                                    .onFailure { vm.notice(if (it is IllegalArgumentException) it.message.orEmpty() else noShareApp) } }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                            if (canonical && isThreadSource(chat.raw.string("source"))) DropdownMenuItem({ Text(uiText("Scheduled posts")) }, { menu = false; threadPosts = true }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) })
                            // The developer-facing actions live one step away from the daily ones.
                            if (canonical) DropdownMenuItem({ Text(uiText("More")) }, { menu = false; moreMenu = true }, leadingIcon = { Icon(Icons.Rounded.MoreHoriz, null) }, trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, modifier = Modifier.testTag("conversation-more"))
                            DropdownMenuItem({ Text(uiText("Chat preferences")) }, { menu = false; vm.more("Settings") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                            if (menuGroups.destructive.isNotEmpty()) { HorizontalDivider(); menuGroups.destructive.forEach { actionItem(it) } }
                        }
                        DropdownMenu(moreMenu && ready, { moreMenu = false }, modifier = Modifier.heightIn(max = actionMenuHeight).testTag("conversation-more-menu")) {
                            conversationMoreActions.forEach { actionItem(it) }
                            // A developer aid: debug builds only.
                            if (BuildConfig.DEBUG) DropdownMenuItem({ Text(uiText("Copy conversation ID")) }, { moreMenu = false
                                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Conversation ID", chat.sessionId)); vm.notice(idCopied) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        }
                    }
                }
            }
        }
        if (activeHere) {
            BeaconCallStage(vm, call, motionEnabled, showCallHistory, { showCallHistory = it }, Modifier.weight(1f), conversationContent)
        } else if (failedHere) {
            CallFailurePanel(call.failureReason, onRetry = { vm.retryCall(call.callId) }, onClose = { vm.closeCallFailure(call.callId) }, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (!beacon && call.conversationScope == vm.authenticatedScope() && call.transcriptStatus.isNotBlank()) Text(call.transcriptLabel(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                conversationContent(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
            }
        }
        // Finding is reading: the composer steps aside so matches get the room, and comes back when find closes.
        val finding = canonical && showSearch
        BackHandler(finding) { showSearch = false; search = "" }
        // During a call the conversation panel can be searched too, so its steps show there as well.
        if (ready && finding) Box(Modifier.fillMaxWidth().onSizeChanged { onComposerHeight(it.height) }, contentAlignment = Alignment.Center) {
            FindNavigator(search, findPosition.coerceIn(0, (findMatches.size - 1).coerceAtLeast(0)), findMatches.size,
                onOlder = { if (findMatches.isNotEmpty()) findPosition = (findPosition + 1).coerceAtMost(findMatches.size - 1) },
                onNewer = { findPosition = (findPosition - 1).coerceAtLeast(0) })
        }
        if (ready && !activeHere && !finding) Column(Modifier.fillMaxWidth().onSizeChanged { onComposerHeight(it.height) }) {
            if (!shortWindow) Box(Modifier.fillMaxWidth().widthIn(max = 840.dp).align(Alignment.CenterHorizontally)) { ChatActivityStrip(backgroundActivity) { activitySheet = true } }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Composer(vm, activeHere, !call.active, callThisChat, composerDetails, { composerDetails = it }) }
        }
    }
    }
    side?.takeIf { ready }?.let { question -> AlertDialog(onDismissRequest = vm::closeSideQuestion, title = { Text(uiText("Side question")) }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(question.question, style = MaterialTheme.typography.titleMedium); Text(question.status, color = MaterialTheme.colorScheme.onSurfaceVariant); Markdown(question.text, vm, question.scope.session(question.sessionId), interactive = false) } }, confirmButton = { TextButton(onClick = vm::closeSideQuestion) { Text(uiText("Close")) } }, dismissButton = { TextButton(onClick = vm::resumeSideQuestion) { Text(uiText("Reconnect")) } }) }
    if (canonical) NativeCatalog(vm)
    if (activitySheet && ready && (backgroundActivity.active || !backgroundActivity.available)) ChatActivitySheet(backgroundActivity) { activitySheet = false }
    if (threadPosts && chat.sessionId.isNotBlank()) ThreadSchedulesSheet(vm, chat.sessionId, chat.title) { threadPosts = false }
    if (showCallHistorySheet && beacon) CallHistorySheet(vm, onOpenCall = { conversationId, callId ->
        showCallHistorySheet = false
        showSearch = false
        search = ""
        follow = false
        callHistoryTargetSession = conversationId
        callHistoryTargetId = callId
        if (conversationId != chat.sessionId) vm.selectSession(conversationId)
    }, onDismiss = { showCallHistorySheet = false })
}

internal fun acceptsInitialConversationEdit(
    inputScope: Scope?,
    currentScope: Scope?,
    ownerScope: Scope?,
    newChatOpening: Boolean,
    sameClient: Boolean,
    callActive: Boolean,
): Boolean = sameClient && newChatOpening && !callActive && inputScope?.session.isNullOrBlank() &&
    ownerScope != null && currentScope?.copy(session = "") == ownerScope

@Composable private fun Composer(vm: BeaconViewModel, callActive: Boolean, callAvailable: Boolean, onCall: () -> Unit, details: Boolean, onDetails: (Boolean) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val draft by vm.composer.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val busyOverride by vm.busySendOverride.collectAsStateWithLifecycle()
    val busyAction = busyOverride ?: preferences.busySendAction
    val settings = LocalBeaconSettings.current
    val readOnly = chat.raw.optBoolean("read_only") || chat.raw.optBoolean("is_read_only")
    val canonical = chat.sessionId.isNotBlank()
    val editable = (chat.projectionReady || ui.newChatOpening) && (canonical || vm.canComposeInitialChat())
    val files by vm.attachments.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val preparing by vm.attachmentPreparation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputClient = vm.client
    val inputScope = vm.authenticatedScope()
    val mobileCommands = mobileSlashCommands(data["slash:catalog"]?.rows("commands").orEmpty())
    val commandName = nativeSlashName(draft.text, mobileCommands.map { it.string("name") })
    val explicitQueue = Regex("^/queue(?=\\s|$)", RegexOption.IGNORE_CASE).find(draft.text)
    val submit: () -> Unit = {
        if (preparing.isNotEmpty()) vm.notice("Finish or remove attachments before sending")
        else if (explicitQueue != null) {
            val payload = draft.text.substring(explicitQueue.range.last + 1).trimStart()
            if (payload.isBlank()) vm.notice("Usage: /queue <message>") else vm.queue(payload)
        }
        else if (draft.text.startsWith("/") && (data["slash:catalog"] == null || recordError(data["slash:catalog"]).isNotBlank())) vm.notice("Wait for commands to load or retry the command list.")
        else if (commandName != null) vm.runSlashCommand(draft.text.substringBefore(' ').removePrefix("/"), draft.text.substringAfter(' ', ""))
        else vm.send()
    }
    val changeDraft: (TextFieldValue) -> Unit = { value ->
        val currentScope = vm.authenticatedScope()
        val callActive = vm.app.calls.state.value.let { it.active && it.conversationScope == inputScope }
        val initialCreation = acceptsInitialConversationEdit(inputScope, currentScope, inputClient?.scope, ui.newChatOpening, inputClient === vm.client, callActive)
        if (acceptsComposerEdit(inputClient, vm.client, inputScope, currentScope, callActive) || initialCreation) vm.composer(value, fromEditor = true)
    }
    var attachmentRequest by remember { mutableStateOf<Pair<BeaconClient, Scope?>?>(null) }
    fun composerAttachmentRequest(): Pair<BeaconClient, Scope?>? =
        if (editable && !readOnly && !callActive) vm.client?.let { client ->
            vm.authenticatedScope()?.takeIf { it.copy(session = "") == client.scope }?.let { client to it }
        } else null
    suspend fun uploadUris(uris: List<Uri>, request: Pair<BeaconClient, Scope?>) {
        for (uri in uris.distinct()) {
            if (request.second == null || vm.client !== request.first || vm.authenticatedScope() != request.second) {
                vm.notice("Reopen attachments in the original account and conversation.")
                return
            }
            vm.upload(uri).join()
        }
    }
    val finishAttachmentSelection: (List<Uri>) -> Unit = { uris ->
        val request = attachmentRequest
        attachmentRequest = null
        if (uris.isNotEmpty()) {
            if (request != null) vm.viewModelScope.launch { uploadUris(uris, request) }
            else vm.notice("Reopen attachments in the original account and conversation.")
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> finishAttachmentSelection(uris) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> finishAttachmentSelection(listOfNotNull(uri)) }
    // Keep clipboard carriers alive until upload finishes so Android does not revoke the pasted URI grant early.
    var clipboardLease by remember(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf<androidx.compose.foundation.content.TransferableContent?>(null) }
    var clipboardClipLease by remember(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf<ClipData?>(null) }
    val pasteClipboardImage: () -> Boolean = {
        val clip = runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip }.getOrNull()
        val images = clip?.let { clipboard ->
            val descriptionImageMime = clipboard.description?.let { description ->
                (0 until description.mimeTypeCount).map { description.getMimeType(it) }.firstOrNull(::imageMimeType)
            }
            (0 until clipboard.itemCount).mapNotNull { index ->
                val uri = clipboard.getItemAt(index).uri ?: return@mapNotNull null
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull()
                uri.takeIf { mime?.let(::imageMimeType) == true || descriptionImageMime != null }
            }
        }.orEmpty()
        if (images.isEmpty()) false else {
            val request = composerAttachmentRequest()
            if (request == null) vm.notice("Reopen attachments in the original account and conversation.") else {
                clipboardClipLease = clip
                vm.viewModelScope.launch {
                    try { uploadUris(images, request) }
                    finally { if (clipboardClipLease === clip) clipboardClipLease = null }
                }
            }
            true
        }
    }
    // Camera photos go to a private cache folder the FileProvider shares with the camera app; stale ones are cleared at start.
    var photo by rememberSaveable { mutableStateOf<String?>(null) }
    var photoScopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    var photoTaken by rememberSaveable { mutableStateOf(false) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val request = attachmentRequest; attachmentRequest = null
        // After process death the result can arrive before the restored client connects; it uploads once connected.
        if (taken && photo != null && vm.client == null) { photoTaken = true; return@rememberLauncherForActivityResult }
        val uri = photo?.let(android.net.Uri::parse); photo = null
        val scopeKey = photoScopeKey; photoScopeKey = null
        val currentScope = vm.authenticatedScope()
        if (taken && uri != null) {
            val scopeMatches = scopeKey != null && currentScope?.key == scopeKey
            val clientMatches = request == null || vm.client === request.first && currentScope == request.second
            if (scopeMatches && clientMatches) vm.upload(uri) else vm.notice("Reopen attachments in the original account and conversation.")
        }
    }
    LaunchedEffect(ui.connected, photoTaken) {
        if (!photoTaken || !ui.connected || vm.client == null) return@LaunchedEffect
        val uri = photo?.let(android.net.Uri::parse); val scopeKey = photoScopeKey
        photo = null; photoScopeKey = null; photoTaken = false
        if (uri != null && scopeKey != null && vm.authenticatedScope()?.key == scopeKey) vm.upload(uri) else vm.notice("Reopen attachments in the original account and conversation.")
    }
    val takePhoto = {
        val file = java.io.File(context.cacheDir, "camera").apply { mkdirs() }.let { java.io.File(it, "photo-${System.currentTimeMillis()}.jpg") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        photo = uri.toString(); photoScopeKey = attachmentRequest?.second?.key
        runCatching { camera.launch(uri) }.onFailure { attachmentRequest = null; photo = null; photoScopeKey = null; vm.notice("No camera app is available") }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) takePhoto() else { attachmentRequest = null; photoScopeKey = null; vm.notice("Allow camera access to take a photo.") } }
    var dictationRequest by remember { mutableStateOf<Pair<BeaconClient, Scope?>?>(null) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> val request = dictationRequest; dictationRequest = null; val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull(); if (r.resultCode == android.app.Activity.RESULT_OK && text != null) { if (request != null && vm.client === request.first && vm.authenticatedScope() == request.second) vm.dictated(text) else vm.notice("Reopen dictation in the original account and conversation.") } }
    var attachMenu by remember { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    var inputFocused by remember { mutableStateOf(false) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { vm.loadSlashCatalog() }
    // Hermes reports a blank model/effort when the chat follows its profile (tui_gateway _session_info, _cfg_get_reasoning),
    // so the profile's own values are the chat's effective ones.
    val effort = chat.raw.string("reasoning_effort", chat.raw.string("reasoning"))
    val sessionEffort = chat.sessionReasoningEffort
    val followsProfile = chat.raw.string("model").isBlank() || effort.isBlank() && sessionEffort.isBlank()
    val defaults = data["profile-defaults"]?.takeIf { followsProfile && it.string("profile") == ui.profile && it.string("scope_key") == vm.client?.scope?.key && it.string("session_id") == chat.sessionId && recordError(it).isBlank() }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady, ui.connected, followsProfile) {
        if (followsProfile && ui.connected && (chat.sessionId.isBlank() || chat.projectionReady)) vm.load("profile-defaults", "/api/profile/defaults", if (chat.sessionId.isBlank()) emptyMap() else mapOf("session_id" to chat.sessionId))
    }
    BoxWithConstraints(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Reserve the action row before the editor grows. The editor scrolls its own long draft.
        val commandHeight = if (draft.text.startsWith("/")) (maxHeight - 160.dp).coerceIn(48.dp, 224.dp) else 0.dp
        val editorHeight = (maxHeight - 112.dp - commandHeight - (if (commandHeight > 0.dp) 8.dp else 0.dp) - if(chat.streamId.isNotBlank()) 48.dp else 0.dp).coerceAtMost(160.dp).coerceAtLeast(48.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.text.startsWith("/")) {
                val catalog = data["slash:catalog"]
                val commands = mobileSlashCommands(catalog?.rows("commands").orEmpty()).filter { slashMatches(it.string("name"), it.string("description"), draft.text) }.sortedBy { if (it.string("name").removePrefix("/").startsWith(draft.text.removePrefix("/"), true)) 0 else 1 }.take(20)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = commandHeight).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).testTag("slash-suggestions")) {
                    // Compact: the bold command and its description on one line.
                    items(commands) { command ->
                        val name = "/${command.string("name").removePrefix("/")}"
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = editable && !readOnly) { val text = "$name "; changeDraft(TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold).contentDirection(), maxLines = 1)
                            Text(command.string("description"), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                    if (commands.isEmpty()) item { Text(uiText(if (catalog == null) "Loading commands…" else if (draft.text.contains(' ')) "Review arguments, then run command" else "No matching commands"), Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall) }
                    if (recordError(catalog).isNotBlank()) item { TextButton(onClick = { vm.loadSlashCatalog(force = true) }) { Text(uiText("Retry commands")) } }
                }
            }
            val voiceAction = composerUsesVoice(draft.text, files.size, preparing.size)
            val streaming = chat.streamId.isNotBlank()
            val sending = chatSendInFlight(chat)
            // Relay-style composer surface: one pill with a hairline border.
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(if (inputFocused) 2.dp else 1.dp, if (inputFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    if (files.isNotEmpty() || preparing.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("composer-attachments"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.forEachIndexed { index, file -> PendingAttachmentChip(file, onClick = { onDetails(true) }, onRemove = { vm.removeAttachment(index) }) }
                        preparing.forEach { file -> InputChip(selected = false, onClick = { onDetails(true) }, label = { Text(attachmentPreparationLabel(file.string("name"), file.string("status"), uiText(file.string("error")), uiText("Upload failed. Remove and attach again."), uiText("Preparing…")), maxLines = 2) }, leadingIcon = { if (file.string("status") != "error") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ErrorOutline, uiText("Upload failed")) }, trailingIcon = { IconButton(onClick = { vm.dismissAttachmentPreparation(file.string("id")) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove") + " " + file.string("name")) } }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp)) }
                    }
                    // A visible state pill, so Queue vs. Guide does not depend on the trailing icon alone.
                    if (streaming && !voiceAction) Text(uiText(when {
                        explicitQueue != null -> "Will send after this reply"
                        draft.text.startsWith("/") -> "Run command"
                        files.isNotEmpty() || busyAction == "queue" -> "Will send after this reply"
                        else -> "Steer"
                    }), Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 3.dp).testTag("composer-queue-caption"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    // In flight the transcript shows the message; the draft stays held (not shown twice) until Hermes accepts it.
                    // Edits are ignored rather than the field made read-only: read-only ends the input session and drops the keyboard.
                    val imagePasteReceiver = ReceiveContentListener { content ->
                        val request = composerAttachmentRequest()
                        if (request == null || !content.hasMediaType(MediaType.Image)) content else {
                            val images = mutableListOf<Uri>()
                            val remaining = content.consume { item ->
                                val uri = item.uri
                                val mime = uri?.let { runCatching { context.contentResolver.getType(it).orEmpty() }.getOrDefault("") }.orEmpty()
                                if (uri != null && imageMimeType(mime)) { images += uri; true } else false
                            }
                            if (images.isEmpty()) remaining ?: content else {
                                clipboardLease = content
                                vm.viewModelScope.launch {
                                    try { uploadUris(images, request) }
                                    finally { if (clipboardLease === content) clipboardLease = null }
                                }
                                remaining
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Telegram's bot-menu button: while the composer is empty it starts the command list.
                    if (draft.text.isEmpty() && !sending && files.isEmpty() && preparing.isEmpty()) {
                        val commandsLabel = uiText("Commands")
                        IconButton(onClick = { changeDraft(TextFieldValue("/", TextRange(1))); runCatching { inputFocus.requestFocus() } }, enabled = editable && !callActive && !readOnly, modifier = Modifier.size(48.dp).testTag("composer-commands").semantics { contentDescription = commandsLabel }) {
                            Text("/", Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Box(Modifier.weight(1f).contentReceiver(imagePasteReceiver)) {
                        OutlinedTextField(if (sending) TextFieldValue("") else draft, if (sending) { _ -> } else changeDraft, placeholder = { Text(if (sending) uiText("Sending…") else if (callActive) uiText("Finish your call to continue typing") else if (readOnly) uiText("Branch this conversation to reply") else if (streaming) uiText("Correct the response...") else assistantText("Message Beacon")) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp, max = editorHeight).testTag("message-input").focusRequester(inputFocus).onFocusChanged { inputFocused = it.isFocused }.onPreviewKeyEvent { event ->
                            val key = event.nativeKeyEvent
                            val pastedImage = key.action == android.view.KeyEvent.ACTION_DOWN && key.repeatCount == 0 && key.keyCode == android.view.KeyEvent.KEYCODE_V && key.isCtrlPressed && pasteClipboardImage()
                            if (pastedImage) true else {
                                val sendKey = settings.string("send_key", "enter")
                                val matched = when (sendKey) { "ctrl_enter", "ctrl+enter" -> key.isCtrlPressed && !key.isShiftPressed; "shift+enter" -> key.isShiftPressed && !key.isCtrlPressed; else -> !key.isShiftPressed && !key.isCtrlPressed && !key.isAltPressed }
                                if (key.action == android.view.KeyEvent.ACTION_DOWN && key.keyCode == android.view.KeyEvent.KEYCODE_ENTER && matched && !callActive && !readOnly) { if (editable && chat.uncertain.isBlank()) submit(); true } else false
                            }
                        }, minLines = 1, maxLines = 6, enabled = editable && !callActive && !readOnly, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = Color.Transparent))
                    }
                    }
                    if (streaming && !voiceAction) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.steer() }, enabled = canonical && preparing.isEmpty() && !readOnly && !callActive && draft.text.isNotBlank() && !draft.text.startsWith("/") && chat.uncertain.isBlank() && files.isEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Send now")) }
                        TextButton(onClick = { vm.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Stop")) }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { attachMenu = true }, enabled = canonical && !callActive && !readOnly, modifier = Modifier.testTag("composer-attach")) { Icon(Icons.Rounded.Add, uiText("Attach a file")) }
                            DropdownMenu(attachMenu, { attachMenu = false }, modifier = Modifier.testTag("attach-menu")) {
                                DropdownMenuItem({ Text(uiText("Photos")) }, { attachMenu = false; vm.client?.let { attachmentRequest = it to vm.authenticatedScope(); photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, modifier = Modifier.testTag("attach-photos"))
                                DropdownMenuItem({ Text(uiText("Files")) }, { attachMenu = false; vm.client?.let { attachmentRequest = it to vm.authenticatedScope(); filePicker.launch(arrayOf("*/*")) } }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }, modifier = Modifier.testTag("attach-files"))
                                DropdownMenuItem({ Text(uiText("Camera")) }, { attachMenu = false; vm.client?.let { attachmentRequest = it to vm.authenticatedScope()
                                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) takePhoto() else cameraPermission.launch(android.Manifest.permission.CAMERA) } },
                                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null) }, modifier = Modifier.testTag("attach-camera"))
                            }
                        }
                        // Attach stays at the left; run controls sit together at the right edge.
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Row(verticalAlignment = Alignment.CenterVertically) {
                        NativeApprovalMenu(vm, enabled = canonical && !callActive && !readOnly)
                        Box(Modifier.weight(1f, fill = false).widthIn(max = 168.dp)) {
                            val modelLabel = chatModelLabel(chat.raw.string("model"), defaults?.string("model").orEmpty())
                            val effortLabel = chatReasoningLabel(sessionEffort, effort, defaults?.string("reasoning_effort").orEmpty())
                            val modelOptionsDescription = uiText("Choose model and reasoning options")
                            val reasoningLabel = uiText("Reasoning effort")
                            val chipEnabled = canonical && !callActive && !readOnly
                            // One tap opens the model sheet; reasoning stays one long-press (or TalkBack action) away.
                            Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(16.dp))
                                .combinedClickable(enabled = chipEnabled, role = androidx.compose.ui.semantics.Role.Button, onLongClickLabel = reasoningLabel, onLongClick = { vm.openCatalog("Reasoning") }) { vm.openCatalog("Model & provider") }
                                .semantics(mergeDescendants = true) {
                                    if (modelLabel.isBlank() && effortLabel.isBlank()) contentDescription = modelOptionsDescription
                                    customActions = listOf(CustomAccessibilityAction(reasoningLabel) { if (chipEnabled) vm.openCatalog("Reasoning"); chipEnabled })
                                }
                                .alpha(if (chipEnabled) 1f else .38f)
                                .padding(horizontal = 8.dp).testTag("composer-model-effort"), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
                                    if (modelLabel.isNotBlank()) Text(modelLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    // The effort as a small gauge and its level, so "low" reads as reasoning, not as a bare word.
                                    if (effortLabel.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Icon(Icons.Rounded.Speed, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(effortLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        } }
                        IconButton(onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            if (intent.resolveActivity(context.packageManager) != null) vm.client?.let { dictationRequest = it to vm.authenticatedScope(); dictation.launch(intent) }
                            else vm.notice("Set up a speech recognition service in Android to use dictation.")
                        }, enabled = !callActive && !readOnly, modifier = Modifier.testTag("composer-dictate")) { Icon(Icons.Rounded.Mic, uiText("Dictate message")) }
                        if (voiceAction && streaming) {
                            // The trailing slot morphs to Stop, so the bar never widens during a turn.
                            OutlinedIconButton(onClick = { vm.cancel() }, shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error), modifier = Modifier.minimumInteractiveComponentSize().size(composerActionSize).testTag("message-stop")) { Icon(Icons.Rounded.Stop, uiText("Stop"), Modifier.size(composerActionIconSize), tint = MaterialTheme.colorScheme.error) }
                        } else if (voiceAction) {
                            FilledIconButton(onClick = onCall, shape = CircleShape, modifier = Modifier.minimumInteractiveComponentSize().size(composerActionSize).testTag("chat-call-beacon"), enabled = callAvailable && canonical && !callActive && !readOnly && chat.streamId.isBlank() && voiceCallsOffered(ui.bootstrap)) { Icon(Icons.Rounded.GraphicEq, assistantText("Talk to Beacon in this conversation"), Modifier.size(composerActionIconSize)) }
                        } else {
                            FilledIconButton(onClick = submit, shape = CircleShape, modifier = Modifier.minimumInteractiveComponentSize().size(composerActionSize).testTag("message-send"), enabled = editable && preparing.isEmpty() && chat.uncertain.isBlank() && !callActive && !readOnly) { Icon(Icons.Rounded.ArrowUpward, uiText(if (explicitQueue != null) "Queue" else if (commandName != null) "Run command" else if (chat.streamId.isNotBlank() && (busyAction == "queue" || files.isNotEmpty()) || chat.streamId.isBlank() && chat.queued.any { it.kind == "queue" && it.state !in setOf("sent", "settled") }) "Queue" else if (chat.streamId.isNotBlank()) "Steer" else "Send"), Modifier.size(composerActionIconSize)) }
                        }
                    }
                }
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { onDetails(false) }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(uiText("Message details"), style = MaterialTheme.typography.titleLarge)
            if (readOnly) Text(uiText("This source is read-only. Branch into a new conversation to continue."))
            if (chat.status.isNotBlank()) Text(uiText(chat.status), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chat.uncertain.isNotBlank()) {
                Text(uiText("Not sure this message arrived. Check the chat, then resend or dismiss."))
                TextButton(onClick = { vm.reconcileSend() }) { Text(uiText("Check receipt")) }
                TextButton(onClick = { vm.openForm(ActionForm("Dismiss this warning", "", emptyList(), explanation = "You have checked the chat. Your draft stays; the warning goes away.", actionLabel = "Dismiss", onResult = { vm.reviewPendingSend() })); onDetails(false) }) { Text(uiText("Dismiss")) }
                TextButton(onClick = { vm.openForm(resendForm(vm)); onDetails(false) }) { Text(uiText("Resend")) }
            }

            preparing.forEach { file -> Text(attachmentPreparationLabel(file.string("name"), file.string("status"), uiText(file.string("error")), uiText("Upload failed. Remove and attach again."), uiText("Preparing…"))) }
            if (files.isEmpty() && preparing.isEmpty()) Text(uiText("No attachments"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEachIndexed { index, file -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(file.string("name", file.string("filename", uiText("Attachment"))), Modifier.weight(1f)); IconButton(onClick = { vm.removeAttachment(index) }) { Icon(Icons.Rounded.Close, uiText("Remove attachment")) } } }
        }
    }
}


/** Ids the reply being written and the message being sent take among the bubble groups. */
private const val LIVE_REPLY_ID = "live-reply"
private const val SENDING_MESSAGE_ID = "sending-message"

/**
 * Long-press opens the message's menu (Relay MessageBubble); TalkBack gets each action by name. Unlike clickable, this does not
 * merge the message's semantics, so checklists, links and code controls inside it stay separately reachable and tappable.
 */
@Composable private fun Modifier.messageGestures(onLongPress: () -> Unit, actions: List<CustomAccessibilityAction>): Modifier {
    val press by rememberUpdatedState(onLongPress)
    val menu = uiText("Message actions")
    return pointerInput(Unit) { detectTapGestures(onLongPress = { press() }) }
        .semantics { onLongClick(menu) { press(); true }; customActions = actions }
}

/** A bubble: the owner's on the right in the accent tint, the assistant's on the left on a surface. */
@Composable private fun ChatBubble(own: Boolean, group: BubbleGroup, bubbleMax: Dp, modifier: Modifier = Modifier, findHit: FindHit = FindHit.None, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.widthIn(max = bubbleMax),
        shape = bubbleShape(own, group.joinedTop, group.tail),
        color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = findBorder(findHit) ?: if (own) null else BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
    ) { Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 5.dp), content = content) }
}

/** Where a row stands in an open find: not a match, a match, or the match the find bar is on. */
internal enum class FindHit { None, Match, Current }

/** A match is ringed; the current one more strongly. Colour is not the only cue: the find bar also counts "2 of 5". */
@Composable private fun findBorder(hit: FindHit): BorderStroke? = when (hit) {
    FindHit.None -> null
    FindHit.Match -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .5f))
    FindHit.Current -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
}

@Composable private fun Modifier.findOutline(hit: FindHit, shape: androidx.compose.ui.graphics.Shape): Modifier = findBorder(hit)?.let { border(it, shape) } ?: this

/** Character ranges of [query] in [text], case-insensitive and not overlapping; none for a blank query. */
internal fun findRanges(text: String, query: String): List<IntRange> {
    val needle = query.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (from <= text.length - needle.length) {
        val at = text.indexOf(needle, from, ignoreCase = true).takeIf { it >= 0 } ?: break
        ranges += at until at + needle.length
        from = at + needle.length
    }
    return ranges
}

/** [text] with every match of [query] marked, for a plain bubble. */
@Composable private fun findHighlighted(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val mark = androidx.compose.ui.text.SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer)
    return remember(text, query, mark) {
        androidx.compose.ui.text.buildAnnotatedString {
            append(text)
            findRanges(text, query).forEach { addStyle(mark, it.first, it.last + 1) }
        }
    }
}

/** Plain words stay plain Text, so a short bubble shrinks to them; anything richer keeps the Markdown renderer. */
@Composable private fun BubbleText(text: String, vm: BeaconViewModel, origin: Scope?, snapshots: Map<String, String> = emptyMap(), documentKey: String = "", interactive: Boolean = true, find: String = "") {
    if (isPlainChatText(text)) SelectionContainer { Text(findHighlighted(text, find), style = MaterialTheme.typography.bodyLarge.contentDirection()) }
    else Markdown(text, vm, origin, snapshots, documentKey, interactive)
}

/** Find in conversation, at the top: the field and Close. Where the match stands and the steps sit at the bottom, in [FindNavigator]. */
@Composable private fun FindBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, focusRequester: FocusRequester) {
    OutlinedTextField(
        query, onQuery,
        placeholder = { Text(uiText("Find in conversation")) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, uiText("Close search")) } },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focusRequester).testTag("find-in-conversation"),
    )
}

/** Find's bottom bar, where the composer was: "2 of 5" (1 is the newest), Up for an older match, Down for a newer one. */
@Composable private fun FindNavigator(query: String, position: Int, count: Int, onOlder: () -> Unit, onNewer: () -> Unit) {
    val status = when {
        query.isBlank() -> ""
        count == 0 -> uiText("No matches")
        else -> uiText("{0} of {1}").replace("{0}", "${position + 1}").replace("{1}", "$count")
    }
    Surface(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("find-navigator"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, Modifier.weight(1f).testTag("search-count").semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                color = if (count == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            IconButton(onClick = onOlder, enabled = count > 0 && position < count - 1, modifier = Modifier.testTag("find-older")) { Icon(Icons.Rounded.KeyboardArrowUp, uiText("Previous match")) }
            IconButton(onClick = onNewer, enabled = count > 0 && position > 0, modifier = Modifier.testTag("find-newer")) { Icon(Icons.Rounded.KeyboardArrowDown, uiText("Next match")) }
        }
    }
}

/** The recorded time (none when Hermes recorded none) and, for the owner's own message, its send tick, at the bubble's bottom right. */
@Composable private fun ColumnScope.BubbleMeta(recordedAt: Long, messageId: String, tick: SendTick?, reserve: Boolean = false) {
    val time = messageTimeLabel(recordedAt, is24Hour = phoneIs24Hour())
    if (time == null && tick == null && !reserve) return
    Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        if (time != null) Text(time, Modifier.testTag("message-time:$messageId"), style = nativeMetadataStyle(), color = LocalContentColor.current.copy(alpha = .7f))
        else if (reserve) Text(" ", style = nativeMetadataStyle())
        if (tick != null) SendTickIcon(tick)
    }
}

/** An email alert as Beacon's own message: its source over the words, then Important / Not important until the owner chooses. [canChoose] is false while sending, offline or once the alert has expired. */
@Composable private fun AlertRow(alert: ChatAlert, group: BubbleGroup, bubbleMax: Dp, vm: BeaconViewModel, scope: Scope, canChoose: Boolean, onChoose: (String) -> Unit) {
    val id = "alert:${alert.noticeId}"
    Column(Modifier.fillMaxWidth().padding(top = if (group.startsRun) 10.dp else 3.dp).testTag("message-row:$id"), horizontalAlignment = Alignment.Start) {
        ChatBubble(own = false, group, bubbleMax, Modifier.testTag("alert-message:${alert.noticeId}")) {
            Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.Notifications, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(alert.source.ifBlank { uiText("Important update") }, Modifier.testTag("alert-label:${alert.noticeId}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            BubbleText(alert.text, vm, scope, interactive = false)
            if (alert.verdict.isNotBlank()) Text(
                uiText(if (alert.verdict == "important") "Important" else "Not important") + " · " + assistantText("Thanks — Beacon will remember."),
                Modifier.padding(top = 4.dp).testTag("alert-verdict:${alert.noticeId}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else if (alert.expiresAt > System.currentTimeMillis() / 1000) Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onChoose("important") }, enabled = canChoose, modifier = Modifier.testTag("alert-important:${alert.noticeId}")) { Text(uiText("Important")) }
                TextButton(onClick = { onChoose("not_important") }, enabled = canChoose, modifier = Modifier.testTag("alert-not-important:${alert.noticeId}")) { Text(uiText("Not important")) }
            }
            BubbleMeta(alert.createdAt, id, null)
        }
    }
}

@Composable private fun QueuedMessageRow(vm: BeaconViewModel, message: QueuedMessage, bubbleMax: Dp, canDismiss: Boolean) {
    var menu by remember(message.id) { mutableStateOf(false) }
    val editable = message.state == "queued"
    val dismissible = canDismiss && message.state in setOf("accepted", "unknown", "settled")
    val status = when (message.state) {
        "queued" -> "Queued on this phone"
        "sending" -> if (message.kind == "steer") "Sending guidance" else "Sending"
        "accepted" -> "Accepted; delivery pending"
        "settled" -> when (message.outcome) {
            "failed" -> "Turn failed; check history"
            "interrupted" -> "Turn interrupted; check history"
            "cancelled" -> "Queued message canceled"
            "rejected" -> "Message rejected"
            else -> "Finished; check history"
        }
        "sent" -> "Sent"
        else -> "Send outcome unknown"
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("queued-message:${message.id}"), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (editable || dismissible) Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("queued-actions:${message.id}")) { Icon(Icons.Rounded.MoreVert, uiText("Message actions")) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (editable) {
                    DropdownMenuItem(text = { Text(uiText("Retry queued message")) }, onClick = { menu = false; vm.retryQueued(message.id) })
                    DropdownMenuItem(text = { Text(uiText("Edit queued message")) }, onClick = { menu = false; vm.editQueued(message.id) })
                    DropdownMenuItem(text = { Text(uiText("Remove queued message")) }, onClick = { menu = false; vm.removeQueued(message.id) })
                } else {
                    if (message.admissionId.isNotBlank()) DropdownMenuItem(text = { Text(uiText("Check receipt")) }, onClick = { menu = false; vm.checkQueuedReceipt(message.id) })
                    if (message.state in setOf("accepted", "unknown")) DropdownMenuItem(text = { Text(uiText("Resend")) }, onClick = { menu = false; vm.openForm(resendLocalForm(vm, message.id)) })
                    DropdownMenuItem(text = { Text(uiText("Remove local message")) }, onClick = { menu = false; vm.removeUnresolvedLocalMessage(message.id) })
                }
            }
        }
        ChatBubble(own = true, BubbleGroup(startsRun = true, joinedTop = false, tail = true), bubbleMax) {
            if (message.text.isNotBlank()) SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge.contentDirection()) }
            message.attachments.forEach { attachment ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.AttachFile, null, Modifier.size(16.dp))
                    Text(attachment.string("name", uiText("Attachment")), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(uiText(status), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelSmall)
            BubbleMeta(message.createdAt, message.id, if (message.state == "sending") SendTick.Sending else if (message.state == "sent") SendTick.Sent else null)
        }
    }
}

@Composable private fun MessageRow(vm: BeaconViewModel, message: Message, index: Int, group: BubbleGroup, bubbleMax: Dp, toolResultsByCallId: Map<String, FoldedToolResult> = emptyMap(), resultTitle: String = "", confirmedLocalId: String? = null, onToolExpanded: (Boolean) -> Unit = {}, find: String = "", findHit: FindHit = FindHit.None) {
    val context = LocalContext.current
    val origin = vm.authenticatedScope()?.session(message.sessionId.ifBlank { vm.chat.value.sessionId })
    val settings = LocalBeaconSettings.current
    val assistantMessageLabel = assistantText("Beacon message")
    val history = remember(message.role, message.text) { safeHistoryMessagePresentation(message.role, message.text) }
    val stopped = stoppedMessageMarker(message.role, message.text, message.interrupted)
    val post = channelPost(message.displayKind, message.role, history.text)
    val displayText = post?.body ?: history.text
    val presentation = chatPresentation(message.role, displayText, JSONObject().put("name", resultTitle), tools = message.tools)
    val reading by vm.readingAloud.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var actions by remember(message.id) { mutableStateOf(false) }
    val own = isOwnMessage(message)
    val hasContent = displayText.isNotBlank() || history.attachments.isNotEmpty()
    val openMessageActions = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); actions = true }
    val copySource: (String) -> Unit = { text -> runCatching { ensureClipboardFits(text); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(assistantMessageLabel, text)) }.onSuccess { vm.notice("Message copied") }.onFailure { vm.notice("Couldn’t copy the whole message. Select a smaller section.") }; Unit }
    val copyFormatted = { runCatching { val (text, html) = formattedClipboard(displayText); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newHtmlText(assistantMessageLabel, text, html)) }.onSuccess { vm.notice("Formatted message copied") }.onFailure { vm.notice("Couldn't copy the whole message. Select a smaller section.") }; Unit }
    val share = { runCatching { context.startActivity(Intent.createChooser(formattedShareIntent(displayText), "Share message")) }.onFailure { vm.notice(if (it is IllegalArgumentException) it.message.orEmpty() else "No app is available to share this message") }; Unit }
    val quote = { vm.quoteInReply(presentation.text) }
    val readAloud = { if (reading) vm.stopReadAloud() else vm.readAloud(presentation.text); Unit }
    val a11yActions = if (!hasContent) emptyList() else listOf(
        CustomAccessibilityAction(uiText("Copy")) { copyFormatted(); true },
        CustomAccessibilityAction(uiText("Quote in reply")) { quote(); true },
        CustomAccessibilityAction(uiText("Share")) { share(); true },
        CustomAccessibilityAction(uiText(if (reading) "Stop read aloud" else "Read aloud")) { readAloud(); true },
        CustomAccessibilityAction(uiText("More message actions")) { openMessageActions(); true },
    )
    // Long-press menu, in place of an always-visible row: Copy, Quote, Share, Read aloud, then the rest.
    val actionsMenu: @Composable () -> Unit = {
        DropdownMenu(actions, { actions = false }, modifier = Modifier.testTag("message-actions:${message.id}")) {
            DropdownMenuItem({ Text(uiText("Copy")) }, { actions = false; copyFormatted() }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, enabled = displayText.isNotBlank(), modifier = Modifier.testTag("message-copy:${message.id}"))
            DropdownMenuItem({ Text(uiText("Quote in reply")) }, { actions = false; quote() }, leadingIcon = { Icon(Icons.Rounded.FormatQuote, null) }, enabled = displayText.isNotBlank(), modifier = Modifier.testTag("message-quote:${message.id}"))
            DropdownMenuItem({ Text(uiText("Share")) }, { actions = false; share() }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, modifier = Modifier.testTag("message-share:${message.id}"))
            DropdownMenuItem({ Text(uiText(if (reading) "Stop read aloud" else "Read aloud")) }, { actions = false; readAloud() }, leadingIcon = { Icon(if (reading) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp, null) }, enabled = reading || displayText.isNotBlank(), modifier = Modifier.testTag("message-read-aloud:${message.id}"))
            HorizontalDivider()
            DropdownMenuItem({ Text(uiText("Copy source")) }, { actions = false; copySource(displayText) }, leadingIcon = { Icon(Icons.Rounded.Code, null, Modifier.testTag("copy-source-icon")) }, modifier = Modifier.testTag("copy-source-action"))
            DropdownMenuItem({ Text(uiText("Branch from here")) }, { actions = false; vm.sessionAction("/api/session/branch", json("keep_count" to index + 1)) { o -> branchSessionId(o).takeIf(String::isNotBlank)?.let { id -> vm.selectSession(id, titleHint = branchTitle(o)); vm.refreshSessions(); vm.notice("Branched into a new conversation") } } }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.CallSplit, null) })
            if (own) DropdownMenuItem({ Text(uiText("Edit and branch")) }, { actions = false; vm.sessionAction("/api/session/branch", json("keep_count" to index)) { o -> (o.optJSONObject("session") ?: o).string("session_id").takeIf(String::isNotBlank)?.let { vm.selectSession(it, message.text, titleHint = branchTitle(o)); vm.refreshSessions() } } }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            if (confirmedLocalId != null) DropdownMenuItem({ Text(uiText("Remove saved local copy")) }, { actions = false; vm.removeConfirmedLocalCopy(confirmedLocalId) })
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = if (group.startsRun) 10.dp else 3.dp).testTag("message-row:${message.id}"),
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (stopped != null) Text(uiText(stopped), Modifier.testTag("message-stopped:${message.id}"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val reasoning = message.reasoning.takeIf { settings.optBoolean("show_thinking", true) }.orEmpty()
        if (reasoning.isNotBlank()) Box(Modifier.widthIn(max = bubbleMax)) { MessageReasoningDisclosure(reasoning, message.id, vm, origin) }
        if (presentation.activity) {
            Box(Modifier.widthIn(max = bubbleMax).findOutline(findHit, RoundedCornerShape(20.dp)).messageGestures(openMessageActions, a11yActions).testTag("message-bubble:${message.id}")) {
                Disclosure(presentation.summary, displayText)
                actionsMenu()
            }
        } else if (hasContent) {
            Box(Modifier.messageGestures(openMessageActions, a11yActions).testTag("message-bubble:${message.id}")) {
                ChatBubble(own, group, bubbleMax, findHit = findHit) {
                    // A scheduled result filed in this thread reads as a post, headed by the job that made it.
                    if (post != null) Row(Modifier.padding(bottom = 2.dp).testTag("channel-post:${message.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(post.job.ifBlank { uiText("Scheduled post") }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (displayText.isNotBlank()) {
                        if (own && !settings.optBoolean("render_user_markdown", true)) SelectionContainer { Text(findHighlighted(displayText, find), style = MaterialTheme.typography.bodyLarge.contentDirection()) }
                        else BubbleText(displayText, vm, origin, message.mediaSnapshots, message.id, find = find)
                    }
                    HistoryAttachmentChips(history.attachments)
                    BubbleMeta(message.recordedAt, message.id, if (own) SendTick.Sent else null)
                }
                actionsMenu()
            }
        }
        if (message.tools.isNotEmpty()) Box(Modifier.widthIn(max = bubbleMax).then(if (hasContent) Modifier else Modifier.findOutline(findHit, RoundedCornerShape(20.dp)))) { MessageActivity("", message.tools, messageTime = message.recordedAt, toolResultsByCallId = toolResultsByCallId, onToolExpanded = onToolExpanded, onCopyResult = copySource, vm = vm, scope = origin) }
        message.attachments.forEach { attachment -> Box(Modifier.widthIn(max = bubbleMax)) { SecureAttachment(vm, attachment, origin, message.mediaSnapshots) } }
    }
}

@Composable private fun MessageReasoningDisclosure(reasoning: String, messageId: String, vm: BeaconViewModel, scope: Scope?) {
    Box(Modifier.fillMaxWidth().testTag("message-reasoning:$messageId")) {
        Disclosure("Reasoning", reasoning, renderContent = { Markdown(reasoning, vm, scope, interactive = false) })
    }
}

@Composable private fun HistoryAttachmentChips(attachments: List<HistoryAttachmentReference>) {
    if (attachments.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.testTag("history-attachment-${attachment.kind}")) {
                Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (attachment.kind == "image") Icons.Rounded.Image else Icons.Rounded.AttachFile, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(attachment.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall.contentDirection(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun decodePendingAttachmentThumbnail(encoded: String): Bitmap? {
    if (encoded.isBlank() || encoded.length > 16 * 1024 * 1024) return null
    val bytes = runCatching { android.util.Base64.decode(encoded, android.util.Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > 96 || bounds.outHeight / sample > 96) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

@Composable private fun PendingAttachmentChip(file: JSONObject, onClick: () -> Unit, onRemove: () -> Unit) {
    val name = file.string("name", file.string("filename", uiText("Attachment")))
    val image = imageMimeType(file.string("mime"))
    val encoded = file.string("staged_base64")
    val thumbnail by produceState<Bitmap?>(null, file.string("id"), encoded, image) {
        value = if (image) withContext(Dispatchers.Default) { decodePendingAttachmentThumbnail(encoded) } else null
    }
    Surface(
        Modifier.widthIn(max = 240.dp).heightIn(min = 48.dp).clickable(onClick = onClick).testTag("pending-attachment-chip"),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                if (thumbnail != null) Image(thumbnail!!.asImageBitmap(), null, Modifier.fillMaxSize().testTag("pending-attachment-thumbnail"), contentScale = ContentScale.Crop)
                else Icon(if (image) Icons.Rounded.Image else Icons.Rounded.AttachFile, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(name, Modifier.weight(1f, fill = false).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelSmall.contentDirection(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove") + " " + name, Modifier.size(18.dp)) }
        }
    }
}

@Composable fun Disclosure(title: String, content: String, actions: @Composable () -> Unit = {}, renderContent: (@Composable () -> Unit)? = null) {
    var expanded by rememberSaveable(title, content.take(50)) { mutableStateOf(false) }
    val toggleDescription = uiText(if (expanded) "Hide {0}" else "Show {0}").replace("{0}", uiText(title))
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = toggleDescription }.testTag("disclosure:$title")) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(8.dp)); Text(uiText(title), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
        }
        if (expanded) {
            if (renderContent == null) SelectionContainer { Text(content, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else renderContent()
            actions()
        }
    }
}

// Message surfaces, per-tool cards, composer pill and activity strip/sheet adapt Hermes Relay's
// MessageBubble, ToolProgressCard, ChatInputBar and GatewayBackgroundProcesses (MIT, see licenses/Hermes-Relay-MIT.txt).
// Only presentation is adapted; Beacon's own ChatState and callbacks supply every value.

/** Live work stays compact; recorded steps can be opened from the same summary row. */
@Composable internal fun MessageActivity(reasoning: String, tools: List<JSONObject>, status: String = "", streaming: Boolean = false, messageTime: Long = 0L, toolResultsByCallId: Map<String, FoldedToolResult> = emptyMap(), onToolExpanded: (Boolean) -> Unit = {}, onCopyResult: (String) -> Unit = {}, vm: BeaconViewModel? = null, scope: Scope? = null) {
    if (reasoning.isBlank() && tools.isEmpty() && status.isBlank()) return
    val live = streaming && tools.isNotEmpty()
    val grouped = tools.size >= 3 && !streaming
    val motionEnabled = rememberNativeMotionEnabled()
    var showSteps by rememberSaveable(tools.firstOrNull()?.toolKey(0)) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("message-activity"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.isNotBlank()) Text(status, Modifier.testTag("message-activity-status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reasoning.isNotBlank()) Disclosure("Reasoning", reasoning, renderContent = if (vm == null) null else ({ Markdown(reasoning, vm, scope, interactive = false) }))
        if (live || grouped) {
            ToolStepsHeader(tools, live, showSteps, motionEnabled) {
                showSteps = !showSteps
                onToolExpanded(showSteps)
            }
            AnimatedVisibility(
                visible = showSteps,
                enter = if (motionEnabled) expandVertically() + fadeIn(tween(180)) else EnterTransition.None,
                exit = if (motionEnabled) shrinkVertically() + fadeOut(tween(120)) else ExitTransition.None,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tools.forEachIndexed { index, tool ->
                        key(tool.toolKey(index)) { ToolCard(tool, streaming, messageTime, onToolExpanded, toolResultsByCallId[toolCallResultId(tool)], onCopyResult) }
                    }
                }
            }
        } else {
            tools.forEachIndexed { index, tool -> key(tool.toolKey(index)) { ToolCard(tool, streaming, messageTime, onToolExpanded, toolResultsByCallId[toolCallResultId(tool)], onCopyResult) } }
        }
    }
}

@Composable private fun ToolStepsHeader(tools: List<JSONObject>, live: Boolean, expanded: Boolean, motionEnabled: Boolean, onClick: () -> Unit) {
    val andMore = uiText("and {0} more")
    val translate = rememberToolNameTranslation()
    val current = tools.lastOrNull()?.let { toolCardName(it, andMore, translate) }.orEmpty()
    val summary = if (live) current else toolStepsSummary(tools, uiText("1 step"), uiText("{0} steps"), andMore, translate)
    val count = uiText(if (tools.size == 1) "1 step" else "{0} steps").replace("{0}", "${tools.size}")
    val stateText = uiText(if (expanded) "Expanded" else "Collapsed")
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, animationSpec = if (motionEnabled) tween(180) else snap(), label = "tool-steps-chevron")
    Surface(
        Modifier.fillMaxWidth().testTag("tool-steps").semantics {
            contentDescription = if (live) "$summary, $count" else summary
            stateDescription = stateText
        },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(onClickLabel = uiText(if (expanded) "Hide steps" else "Show steps"), onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (live) BeaconSignal(BeaconMotionMode.Pulse, size = 20.dp)
            else Icon(if (live) Icons.Rounded.Schedule else Icons.Rounded.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (live && motionEnabled) {
                AnimatedContent(
                    targetState = summary,
                    transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith (slideOutVertically { -it / 3 } + fadeOut()) },
                    label = "live-tool-step",
                    modifier = Modifier.weight(1f),
                ) { label -> Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            } else Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (live) Text(count, style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp).graphicsLayer { rotationZ = chevronRotation }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun JSONObject.rawToolName(): String {
    val name = string("name", optJSONObject("function")?.string("name") ?: "")
    // Hermes runs deferred tools through its `tool_call` meta-tool; name the tool it actually ran.
    if (name == "tool_call") innerToolNames().firstOrNull()?.let { return it }
    return name.ifBlank { "tool" }
}
/** `tool_call` carries one `name` or a batch of `calls`, each with its own `name`. */
private fun JSONObject.innerToolNames(): List<String> {
    val args = argumentsValue()?.let { it as? JSONObject ?: runCatching { JSONObject(it.toString()) }.getOrNull() } ?: return emptyList()
    val batch = args.optJSONArray("calls")
    val names = if (batch != null) List(batch.length()) { batch.optJSONObject(it)?.string("name").orEmpty() } else listOf(args.string("name"))
    return names.filter { it.matches(toolNamePattern) }
}
internal fun toolDisplayName(tool: JSONObject, andMore: String = "and {0} more"): String {
    val more = if (tool.string("name", tool.optJSONObject("function")?.string("name") ?: "") == "tool_call") tool.innerToolNames().size - 1 else 0
    val server = listOf("server", "server_name", "mcp_server", "tool_server").firstNotNullOfOrNull { key -> tool.string(key).takeIf(String::isNotBlank) }.orEmpty()
    return readableToolName(tool.rawToolName(), server) + if (more > 0) " " + andMore.replace("{0}", "$more") else ""
}

/** Plain names for the tools whose ids read badly ("Beacon call user"); every other tool keeps its readable id. Shown through uiText. */
internal val friendlyToolNames = linkedMapOf(
    "beacon_call_user" to "Call you", "beacon_email_notice" to "Email alert", "beacon_event_notice" to "Event alert",
    "beacon_create_proactive_request" to "Set up a watch", "execute_code" to "Run code", "web_extract" to "Read web page",
    "browser_navigate" to "Open web page", "session_search" to "Search past chats", "delegate_task" to "Hand off a task",
    "clarify" to "Ask you", "vision_analyze" to "Look at image", "image_generate" to "Create image", "text_to_speech" to "Speak",
    "todo" to "To-do list", "patch" to "Edit file",
)

/** The name a tool card and the steps row show: the plain name when there is one (passed through [translate]), else [toolDisplayName]. */
internal fun toolCardName(tool: JSONObject, andMore: String = "and {0} more", translate: (String) -> String = { it }): String {
    val friendly = friendlyToolNames[tool.rawToolName()] ?: return toolDisplayName(tool, andMore)
    val more = if (tool.string("name", tool.optJSONObject("function")?.string("name") ?: "") == "tool_call") tool.innerToolNames().size - 1 else 0
    return translate(friendly) + if (more > 0) " " + andMore.replace("{0}", "$more") else ""
}
/** Hermes' own lookup steps say nothing about the work; a step summary names the tools that did it. */
private val toolLookupNames = setOf("tool_search", "tool_describe", "skills_list", "skill_view")
internal fun toolStepsSummary(tools: List<JSONObject>, one: String = "1 step", many: String = "{0} steps", andMore: String = "and {0} more", translate: (String) -> String = { it }): String {
    val names = tools.filterNot { it.rawToolName() in toolLookupNames }.map { toolCardName(it, andMore, translate) }.distinct()
    val shown = names.take(3).joinToString(", ")
    return (if (tools.size == 1) one else many).replace("{0}", "${tools.size}") + if (shown.isNotBlank()) " · " + shown + if (names.size > 3) "…" else "" else ""
}
/** Calls use the phone's own provider key; Hermes only needs the Beacon plugin. A missing phone key opens Voice & calls. */
internal fun voiceCallsOffered(bootstrap: JSONObject): Boolean = bootstrap.optJSONObject("optional_features")?.optString("plugin") == "available"
/** MCP ids carry a server and sometimes an eight-character discovery hash. */
private val toolHashSuffix = Regex("(?i)(?:_|-)[0-9a-f]{8}$")
private val toolServices = listOf(
    "google_workspace" to "Google Workspace",
    "google_drive" to "Google Drive",
    "google_calendar" to "Google Calendar",
)
private val genericToolGateways = setOf("toolgateway", "toolgatewaymeeting", "toolgatewaycall")

private fun readableToolName(raw: String, declaredServer: String = "", suffix: String = ""): String {
    val parts = raw.split("__", limit = 3)
    var server = declaredServer
    var name = raw
    if (parts.size == 3 && parts[0] == "mcp" && parts[1].isNotBlank()) {
        if (server.isBlank()) server = parts[1]
        name = parts[2]
    }
    name = name.replace(toolHashSuffix, "").removePrefix("mcp__").replace("__", "_")
    if (name.startsWith("beacon_")) name = "beacon_" + name.removePrefix("beacon_")
    val normalizedServer = server.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    val service = toolServices.firstOrNull { (prefix, _) -> name.startsWith("${prefix}_") || normalizedServer == prefix.replace("_", "") }
    if (service != null) name = name.removePrefix("${service.first}_")
    val serverLabel = when {
        service != null -> service.second
        normalizedServer.isBlank() || normalizedServer in genericToolGateways -> ""
        else -> displayToolWords(server)
    }
    val label = displayToolWords(name).ifBlank { "Tool" }
    return label + suffix + if (serverLabel.isNotBlank() && !label.contains(serverLabel, ignoreCase = true)) " · $serverLabel" else ""
}

private fun displayToolWords(value: String): String {
    val words = value.replace(Regex("([a-z])([A-Z])"), "$1_$2")
        .split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotBlank)
    return words.mapIndexed { index, word ->
            when (word.lowercase(Locale.ROOT)) {
                "gmai", "gmail" -> "Gmail"
                "t" -> if (index > 0 && words[index - 1].lowercase(Locale.ROOT) in setOf("gmail", "gmai")) "thread" else "T"
                else -> word.lowercase(Locale.ROOT).let { if (index == 0) it.replaceFirstChar(Char::titlecase) else it }
            }
        }
        .joinToString(" ")
}

internal fun humanToolName(raw: String, suffix: String = ""): String = readableToolName(raw, suffix = suffix)
private fun JSONObject.toolKey(index: Int): String = toolIdentity(this).ifBlank { "${rawToolName()}:$index" }
/** Native completion carries `result` (possibly empty) and `is_error`; a present key is a recorded fact even when empty. */
private fun JSONObject.recordedValue(vararg keys: String): Any? = keys.firstNotNullOfOrNull { key -> if (has(key) && !isNull(key)) opt(key) else null }
private fun Any.detailText(): String = when (this) { is JSONObject -> toString(2); is org.json.JSONArray -> toString(2); else -> toString() }
private fun JSONObject.argumentsValue(): Any? = (opt("args") ?: opt("arguments") ?: optJSONObject("function")?.opt("arguments"))?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() }
/** The native `duration_s`, only when it is a real non-negative number. */
private fun JSONObject.durationSeconds(): Double? = (opt("duration_s") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }

/** `preview` is partial output from a still-running call, so it is never terminal evidence. */
private fun toolOutcomeOf(tool: JSONObject, streaming: Boolean, foldedResult: FoldedToolResult? = null): ToolOutcome {
    val result = tool.recordedValue("result", "output")
    val resultText = foldedResult?.content ?: result?.detailText()
    val explicit = result != null || (tool.has("is_error") && !tool.isNull("is_error")) || foldedResult != null
    return toolOutcome(tool.rawToolName(), tool.string("status"), tool.optBoolean("is_error") || foldedResult?.isError == true, resultText, explicit, streaming)
}

/** The friendly tool names in the current language. */
@Composable private fun rememberToolNameTranslation(): (String) -> String {
    val names = friendlyToolNames.values.associateWith { uiText(it) }
    return remember(names) { { name -> names[name] ?: name } }
}

private val terminalTools = setOf("terminal", "bash", "shell", "execute_command", "run_command", "run_shell_command")
private val webSearchTools = setOf("web_search", "search_web", "google_search", "brave_search")

/** A JSON object given as an object or as its text; null for anything else. */
private fun jsonObjectOf(value: Any?): JSONObject? = when (value) {
    is JSONObject -> value
    is String -> value.trim().takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
    else -> null
}

/** "persist_on_release" or "maxResults" as words: "Persist on release", "Max results". */
internal fun toolFieldLabel(key: String): String =
    key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim().lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }

/** An object's fields as label/value rows, leaving out what says nothing (null, blank, false, empty lists and objects). */
internal fun toolFields(fields: JSONObject): List<Pair<String, String>> = fields.keys().asSequence().mapNotNull { key ->
    when (val value = fields.opt(key)) {
        null, JSONObject.NULL, false -> null
        is String -> value.takeIf(String::isNotBlank)
        is JSONObject -> value.takeIf { it.length() > 0 }?.toString()
        is org.json.JSONArray -> value.takeIf { it.length() > 0 }?.let { items -> (0 until items.length()).map { items.opt(it) }.joinToString(", ") { if (it is String) it else it.toString() } }
        else -> value.toString()
    }?.let { toolFieldLabel(key) to it }
}.toList()

/** What a tool card shows when opened: a terminal's command and exit code, a web search's query and hits, else JSON as rows. */
internal data class ToolDetails(
    val command: String? = null, val exitCode: Int? = null, val output: String? = null, val error: String? = null,
    val query: String? = null, val resultCount: Int? = null, val hits: List<Pair<String, String>> = emptyList(),
    val arguments: List<Pair<String, String>>? = null, val result: List<Pair<String, String>>? = null,
)

internal fun toolDetails(rawName: String, arguments: Any?, result: String?): ToolDetails {
    val args = jsonObjectOf(arguments)
    val parsed = jsonObjectOf(result)
    val name = rawName.lowercase(Locale.ROOT)
    if (name in terminalTools) {
        val command = args?.let { it.string("command", it.string("cmd")) }?.takeIf(String::isNotBlank)
            ?: (arguments as? String)?.takeIf { args == null && it.isNotBlank() }
        if (command != null) {
            val exit = parsed?.let { r -> listOf("exit_code", "returncode", "exit_status").firstNotNullOfOrNull { key -> (r.opt(key) as? Number)?.toInt() } }
            val output = if (parsed != null) listOf("output", "stdout").firstNotNullOfOrNull { key -> parsed.string(key).takeIf(String::isNotBlank) } else result?.takeIf(String::isNotBlank)
            val error = parsed?.let { r -> listOf("error", "stderr").firstNotNullOfOrNull { key -> r.string(key).takeIf { it.isNotBlank() && it != "null" } } }
            return ToolDetails(command = command, exitCode = exit, output = output, error = error)
        }
    }
    if (name in webSearchTools) {
        val query = args?.let { it.string("query", it.string("q")) }?.takeIf(String::isNotBlank)
        if (query != null) {
            val list = parsed?.let { r -> listOf(r.optJSONArray("results"), r.optJSONObject("data")?.optJSONArray("web"), r.optJSONArray("web"), r.optJSONArray("items"), r.optJSONArray("organic")).firstOrNull { it != null } }
            val hits = list?.let { items -> (0 until items.length()).mapNotNull { items.optJSONObject(it) }.mapNotNull { hit ->
                val title = hit.string("title").ifBlank { hit.string("name") }
                val url = hit.string("url", hit.string("link"))
                if (title.isBlank() && url.isBlank()) null else title.ifBlank { url } to url
            } }.orEmpty()
            return ToolDetails(query = query, resultCount = list?.length(), hits = hits.take(5))
        }
    }
    return ToolDetails(arguments = args?.let(::toolFields), result = parsed?.let(::toolFields))
}

private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    listOf("bash", "terminal", "execute", "shell", "code").any { it in name } -> Icons.Rounded.Code
    listOf("search", "web", "browser").any { it in name } -> Icons.Rounded.Search
    listOf("file", "read", "write", "patch").any { it in name } -> Icons.Rounded.Description
    listOf("tap", "click", "swipe", "scroll").any { it in name } -> Icons.Rounded.TouchApp
    else -> Icons.Rounded.Build
}

@Composable internal fun ToolCard(tool: JSONObject, streaming: Boolean = false, messageTime: Long = 0L, onExpandedChange: (Boolean) -> Unit = {}, foldedResult: FoldedToolResult? = null, onCopyResult: (String) -> Unit = {}) {
    val rawName = tool.rawToolName()
    val outcome = toolOutcomeOf(tool, streaming, foldedResult)
    val running = outcome == ToolOutcome.Running
    val preparing = outcome == ToolOutcome.Preparing
    // A running tool opens like Relay's; history and preparing tools start folded.
    val toolKey = tool.toolKey(0)
    var expanded by rememberSaveable(toolKey) { mutableStateOf(false) }
    val finished = outcome == ToolOutcome.Completed || outcome == ToolOutcome.Failed || outcome == ToolOutcome.Dispatched
    val duration = toolDurationLabel(tool.durationSeconds())
    // Native tool events carry a duration but no clock time; like Relay, a finished call shows its message's recorded time.
    val time = messageTimeLabel(messageTime.takeIf { finished } ?: 0L, is24Hour = phoneIs24Hour())
    val argumentsText = tool.argumentsValue()?.detailText()
    val embeddedResult = tool.recordedValue("result", "output")?.detailText()
    val resultText = foldedResult?.content ?: embeddedResult.orEmpty()
    val previewText = tool.recordedValue("preview")?.detailText().orEmpty().takeIf { resultText.isBlank() && it.isNotBlank() }
    val details = remember(rawName, argumentsText, resultText) { toolDetails(rawName, tool.argumentsValue(), resultText.takeIf(String::isNotBlank)) }
    val name = toolCardName(tool, uiText("and {0} more"), rememberToolNameTranslation())
    val meta = when (outcome) {
        ToolOutcome.Dispatched -> uiText(toolOutcomeLabel(outcome))
        ToolOutcome.NoResult -> uiText("No result")
        else -> listOfNotNull(duration, time).joinToString(" · ")
    }
    val (statusIcon, statusColor) = when (outcome) {
        ToolOutcome.Completed -> Icons.Rounded.Check to MaterialTheme.colorScheme.primary
        ToolOutcome.Failed -> Icons.Rounded.Close to MaterialTheme.colorScheme.error
        ToolOutcome.Preparing -> Icons.Rounded.MoreHoriz to MaterialTheme.colorScheme.onSurfaceVariant
        ToolOutcome.Running -> Icons.Rounded.HourglassTop to MaterialTheme.colorScheme.primary
        ToolOutcome.Dispatched -> Icons.AutoMirrored.Rounded.Send to MaterialTheme.colorScheme.secondary
        ToolOutcome.NoResult -> Icons.AutoMirrored.Rounded.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = uiText(toolOutcomeLabel(outcome))
    Surface(Modifier.fillMaxWidth().testTag("tool-card").semantics { contentDescription = "$name, $label" + (if (meta.isNotBlank() && outcome != ToolOutcome.Dispatched && outcome != ToolOutcome.NoResult) ", $meta" else "") }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = uiText(if (expanded) "Hide details" else "Details")) { expanded = !expanded; onExpandedChange(expanded) }.testTag("tool-card-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(rawName), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = if (preparing) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (meta.isNotBlank()) { Text(meta, Modifier.testTag("tool-card-meta"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                // The outcome is said once, here; the opened card does not repeat it as a "Status" line.
                Icon(statusIcon, label, Modifier.size(16.dp).testTag("tool-card-status"), tint = statusColor)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(2.dp), color = MaterialTheme.colorScheme.primary)
            if (expanded) SelectionContainer { Column(Modifier.padding(bottom = 12.dp).testTag("tool-card-details"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (outcome == ToolOutcome.Dispatched) Text(uiText("The task was handed off. Its own completion isn’t recorded here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tool.string("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                val errorInk = MaterialTheme.colorScheme.error
                val resultTitle = uiText(if (outcome == ToolOutcome.Failed) "Error" else "Result")
                val copyResult = if (foldedResult != null) ({ onCopyResult(resultText) }) else null
                val command = details.command
                val query = details.query
                when {
                    // A terminal: the command line, its exit code, then what it printed.
                    command != null -> {
                        ToolCommandRow(command, details.exitCode)
                        details.output?.let { ToolDetailBlock(uiText("Output"), it, "tool-card-result", if (outcome == ToolOutcome.Failed) errorInk else MaterialTheme.colorScheme.onSurface, toolKey, onCopy = copyResult) }
                        details.error?.let { ToolDetailBlock(uiText("Error"), it, "tool-card-error", errorInk, toolKey) }
                    }
                    // A web search: what was asked, how many came back, and the first few hits.
                    query != null -> {
                        ToolSearchRow(query, details.resultCount)
                        if (details.hits.isNotEmpty()) ToolHits(details.hits)
                        else if (resultText.isNotBlank() && details.resultCount == null) ToolDetailBlock(resultTitle, resultText, "tool-card-result", if (outcome == ToolOutcome.Failed) errorInk else MaterialTheme.colorScheme.onSurface, toolKey, onCopy = copyResult)
                    }
                    else -> {
                        // JSON reads as labelled rows; plain recorded text keeps its own lines in a bounded scroll area.
                        val fields = details.arguments
                        if (fields != null) ToolFieldRows(uiText("Arguments"), fields, "tool-card-arguments")
                        else argumentsText?.let { ToolDetailBlock(uiText("Arguments"), it, "tool-card-arguments", MaterialTheme.colorScheme.onSurface, toolKey) }
                        val rows = details.result
                        if (resultText.isNotBlank() && !rows.isNullOrEmpty()) ToolFieldRows(resultTitle, rows, "tool-card-result", if (outcome == ToolOutcome.Failed) errorInk else Color.Unspecified, onCopy = copyResult)
                        else if (resultText.isNotBlank()) ToolDetailBlock(resultTitle, resultText, "tool-card-result", if (outcome == ToolOutcome.Failed) errorInk else MaterialTheme.colorScheme.onSurface, toolKey, onCopy = copyResult)
                        else if (outcome == ToolOutcome.Completed || outcome == ToolOutcome.Failed) Text(uiText("The tool finished with an empty result."), Modifier.testTag("tool-card-empty-result"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                // Partial output is shown as such; it never marks the call finished.
                previewText?.let { ToolDetailBlock(uiText("Preview (partial)"), it, "tool-card-preview", MaterialTheme.colorScheme.onSurface, toolKey) }
                if (outcome == ToolOutcome.NoResult) Text(uiText("No completion was recorded for this tool."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

/** "$ sleep 15" in the code face, and the exit code as a status pill (0 is OK, anything else failed). */
@Composable private fun ToolCommandRow(command: String, exitCode: Int?) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 10.dp, vertical = 8.dp).testTag("tool-card-command"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$ $command", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace).contentDirection(), maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (exitCode != null) SettingsStatusPill(if (exitCode == 0) SettingsStatus.Ok else SettingsStatus.Error, uiText("Exit {0}").replace("{0}", "$exitCode"), testTag = "tool-card-exit")
    }
}

/** The search as asked, with how many results came back. */
@Composable private fun ToolSearchRow(query: String, count: Int?) {
    Row(Modifier.fillMaxWidth().testTag("tool-card-query"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(query, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.contentDirection(), maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if (count != null) SettingsBadge(uiText(if (count == 1) "1 result" else "{0} results").replace("{0}", "$count"), testTag = "tool-card-count")
    }
}

/** The first hits of a search: the page title, and its address under it. */
@Composable private fun ToolHits(hits: List<Pair<String, String>>) {
    Column(Modifier.fillMaxWidth().testTag("tool-card-result"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        hits.forEach { (title, url) ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium).contentDirection(), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (url.isNotBlank() && url != title) Text(url, style = nativeMetadataStyle().contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

/** JSON as label/value rows: each value clamped to three lines, tap a row to read it whole. */
@Composable private fun ToolFieldRows(title: String, fields: List<Pair<String, String>>, tag: String, color: Color = Color.Unspecified, onCopy: (() -> Unit)? = null) {
    if (fields.isEmpty()) return
    ToolDetailTitle(title, tag, onCopy)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag)) {
        fields.forEachIndexed { index, (label, value) ->
            var open by remember(label, value) { mutableStateOf(false) }
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Column(Modifier.fillMaxWidth().clickable(onClickLabel = uiText(if (open) "Show less" else "Show more")) { open = !open }.padding(vertical = 6.dp).semantics(mergeDescendants = true) {}) {
                Text(label, style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(boundedDetail(value), style = MaterialTheme.typography.bodySmall.contentDirection(), color = color, maxLines = if (open) Int.MAX_VALUE else 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable private fun ToolDetailTitle(title: String, tag: String, onCopy: (() -> Unit)?) {
    if (onCopy == null) Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val copyLabel = uiText("Copy result")
        IconButton(onCopy, Modifier.size(48.dp).testTag("$tag-copy").semantics { contentDescription = copyLabel }) {
            Icon(Icons.Rounded.ContentCopy, null)
        }
    }
}

@Composable private fun ToolDetailBlock(title: String, text: String, tag: String, color: Color, toolKey: String, onCopy: (() -> Unit)? = null) {
    var detailLimit by rememberSaveable(toolKey, tag) { mutableStateOf(ToolDetailPageSize) }
    val shownLimit = detailLimit.coerceAtMost(ToolDetailLimit)
    ToolDetailTitle(title, tag, onCopy)
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text(boundedDetail(text, shownLimit), Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace).contentDirection(), color = color)
    }
    if (text.length > shownLimit && shownLimit < ToolDetailLimit) TextButton(onClick = { detailLimit = nextToolDetailLimit(shownLimit) }, modifier = Modifier.testTag("$tag-show-more")) { Text(uiText("Show more")) }
    if (shownLimit > ToolDetailPageSize) TextButton(onClick = { detailLimit = ToolDetailPageSize }, modifier = Modifier.testTag("$tag-show-less")) { Text(uiText("Show less")) }
}

/** A send Hermes has not answered yet; an unknown outcome is shown separately, once the request has failed or been interrupted. */
internal fun chatSendInFlight(chat: ChatState): Boolean = chat.uncertain.isNotBlank() && chat.status == "Sending" && chat.queued.none { it.id == chat.uncertain }

/** The confirmation before a resend of a message whose delivery is unconfirmed; one place for the row and the details sheet. */
private fun resendForm(vm: BeaconViewModel) = ActionForm("Resend retained draft", "", emptyList(), explanation = "The earlier delivery is unconfirmed. Resending may create a duplicate if it already arrived.", actionLabel = "Resend", onResult = { vm.reviewPendingSend(resend = true) })
private fun resendLocalForm(vm: BeaconViewModel, id: String) = ActionForm("Resend retained message", "", emptyList(), explanation = "The earlier delivery is unconfirmed. Resending may create a duplicate if it already arrived.", actionLabel = "Resend", onResult = { vm.resendUnresolved(id) })

/** The message being sent, as its stored bubble will look (with a clock tick), so its arrival does not shift the list. */
@Composable private fun SendingMessage(text: String, group: BubbleGroup, bubbleMax: Dp) {
    Column(Modifier.fillMaxWidth().padding(top = if (group.startsRun) 10.dp else 3.dp).testTag("sending-message"), horizontalAlignment = Alignment.End) {
        ChatBubble(own = true, group, bubbleMax, Modifier.alpha(.7f)) {
            Text(text.ifBlank { uiText("Attachment") }, style = MaterialTheme.typography.bodyLarge)
            BubbleMeta(System.currentTimeMillis(), SENDING_MESSAGE_ID, SendTick.Sending)
        }
    }
}

/** Compact summary of background work, separate from the current reply's transcript activity. */
@Composable internal fun ChatActivityStrip(activity: BackgroundActivitySummary, onClick: () -> Unit) {
    if (!activity.active) return  // owner: hidden unless something runs in the background; an unknown status hides too
    val texts = BackgroundActivityStrings.associateWith { uiText(it) }
    val status = backgroundActivityStatus(activity) { texts.getValue(it) }
    val title = uiText("Background activity")
    val openLabel = uiText("Open background activity")
    val description = "$title, $status"
    Surface(
        Modifier.padding(horizontal = 12.dp, vertical = 2.dp).widthIn(max = 840.dp).wrapContentWidth(Alignment.CenterHorizontally).heightIn(min = 48.dp)
            .testTag("chat-activity-strip").semantics { contentDescription = description; stateDescription = status }
            .clickable(onClickLabel = openLabel, onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .28f),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (activity.active) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
            else Icon(Icons.Rounded.Info, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable internal fun ChatActivitySheet(activity: BackgroundActivitySummary, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("chat-activity-sheet").heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiText("Background activity"), style = MaterialTheme.typography.titleLarge)
                    Text(uiText("Read-only status from this profile"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("chat-activity-close")) { Icon(Icons.Rounded.Close, uiText("Close background activity")) }
            }
            if (!activity.active) Text(
                uiText(if (activity.available) "No active background work." else "Background status unavailable."),
                Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("chat-activity-empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else {
                val total = activity.agents + activity.processes + activity.cronJobs + activity.watches
                ActivitySection(uiText("Background"), total)
                if (activity.agents > 0) ActivityRow(uiText(if (activity.agents == 1) "1 background agent" else "{0} background agents").replace("{0}", "${activity.agents}"), uiText("Running"), true)
                if (activity.processes > 0) ActivityRow(uiText(if (activity.processes == 1) "1 background process" else "{0} background processes").replace("{0}", "${activity.processes}"), uiText("Running"), true)
                if (activity.cronJobs > 0) ActivityRow(uiText(if (activity.cronJobs == 1) "1 schedule running" else "{0} schedules running").replace("{0}", "${activity.cronJobs}"), uiText("Running"), true)
                if (activity.watches > 0) ActivityRow(uiText(if (activity.watches == 1) "1 active watch" else "{0} active watches").replace("{0}", "${activity.watches}"), uiText("Watching"), true)
                if (!activity.available) Text(uiText("More background work may be active."), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ActivitySection(label: String, count: Int) {
    Text("$label · $count", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ActivityRow(title: String, detail: String, spinning: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) { if (spinning) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.Build, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(title.replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(detail, style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@Composable private fun Clarification(vm: BeaconViewModel, prompt: JSONObject, promptScope: Scope?, answers: Map<String, String>, onAnswer: (String, String) -> Unit) {
    val answer = answers[""].orEmpty()
    val questions = prompt.rows("questions")
    Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.string("question", uiText("One detail before continuing")), style = MaterialTheme.typography.titleMedium)
        if (questions.any { it.string("qid").isBlank() || it.string("question").isBlank() }) {
            Text(uiText("The question could not be read. Refresh before answering."), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.selectSession(vm.chat.value.sessionId) }) { Text(uiText("Refresh question")) }
        } else if (questions.isNotEmpty()) {
            questions.forEach { q -> val id = q.getString("qid"); Text(q.getString("question")); OutlinedTextField(answers[id].orEmpty(), { onAnswer(id, it) }, label = { Text(uiText("Your answer")) }, modifier = Modifier.fillMaxWidth()); q.optJSONArray("choices")?.strings()?.forEach { choice -> TextButton(onClick = { onAnswer(id, choice) }) { Text(choice, style = LocalTextStyle.current.contentDirection()) } } }
            Button(onClick = { promptScope?.let { vm.clarifyBatch(JSONObject(questions.associate { question -> question.getString("qid") to answers[question.getString("qid")] }), prompt, it) } }, enabled = promptScope != null && questions.all { answers[it.string("qid")].orEmpty().isNotBlank() }) { Text(uiText("Continue")) }
        } else {
            prompt.optJSONArray("choices")?.strings()?.forEach { choice -> OutlinedButton(onClick = { promptScope?.let { vm.clarify(choice, prompt, it) } }, enabled = promptScope != null) { Text(choice) } }
            OutlinedTextField(answer, { onAnswer("", it) }, label = { Text(uiText("Your answer")) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { promptScope?.let { vm.clarify(answer, prompt, it) } }, enabled = promptScope != null && answer.isNotBlank()) { Text(uiText("Continue")) }
        }
    } }
}

@Composable private fun BeaconCallStage(vm: BeaconViewModel, call: CallState, motionEnabled: Boolean, showHistory: Boolean, onHistory: (Boolean) -> Unit, modifier: Modifier, conversation: @Composable (Modifier) -> Unit) {
    val context=LocalContext.current
    var permissionCall by rememberSaveable { mutableStateOf("") }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected=permissionCall; permissionCall=""
        val current=vm.app.calls.state.value
        if(granted&&current.active&&current.callId==expected) vm.app.calls.camera(true)
        else if(!granted) vm.notice("Allow camera access to share your camera with ${dev.hermes.android.data.currentAssistantName}.")
    }
    var screenCall by rememberSaveable { mutableStateOf("") }
    var screenToken by rememberSaveable { mutableLongStateOf(0L) }
    val screenPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val expected=screenCall; val token=screenToken; screenCall="";screenToken=0
        vm.app.calls.screenConsent(expected,token,if(result.resultCode==android.app.Activity.RESULT_OK) result.data else null)
    }
    var confirmVision by remember(call.callId) { mutableStateOf("") }
    fun camera() {
        if(call.cameraSharing||call.cameraBusy||call.cameraStopPending) vm.app.calls.camera(false)
        else if(call.state!="connecting"&&permissionCall.isBlank()) { permissionCall=call.callId;permission.launch(Manifest.permission.CAMERA) }
    }
    fun screen() {
        if(call.screenSharing||call.screenBusy||call.screenStopPending) vm.app.calls.stopScreen()
        else {
            val token=vm.app.calls.beginScreenConsent(call.callId)
            if(token>0) { screenCall=call.callId;screenToken=token
                try { screenPermission.launch(context.getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()) }
                catch (_: RuntimeException) { vm.app.calls.screenConsent(screenCall,screenToken,null);screenCall="";screenToken=0 }
            }
        }
    }
    val pending by vm.voiceHandoff.collectAsStateWithLifecycle()
    LaunchedEffect(call.callId,pending?.targetCall) {
        if(call.active&&call.visionAvailable&&vm.app.calls.uiVisible) when(vm.consumeVisionHandoff(call.callId)) {
            "camera" -> camera()
            "screen" -> screen()
        }
    }
    if(confirmVision.isNotBlank()) AlertDialog(
        onDismissRequest={confirmVision=""},
        title={Text(uiText("Switch voice?"))},
        text={Text(uiText("Camera and screen need another voice."))},
        confirmButton={TextButton({ val media=confirmVision;confirmVision="";vm.switchCallForVision(call.callId,media) },Modifier.testTag("beacon-confirm-vision-switch")) { Text(uiText("Switch")) }},
        dismissButton={TextButton({confirmVision=""},Modifier.testTag("beacon-cancel-vision-switch")) { Text(uiText("Cancel")) }})
    val actions=CallUiActions(vm.app.calls::mute,{
        if(!call.visionAvailable) confirmVision="camera" else camera()
    },vm.app.calls::flipCamera,{
        (context as? dev.hermes.android.BeaconActivity)?.allowFloatingCall(call.callId)
    },vm.app.calls::hangup,vm.app.calls::route,{
        if(!call.visionAvailable) confirmVision="screen" else screen()
    })
    val stage: @Composable (Modifier) -> Unit = { area ->
        LiveCallSurface(call,actions,motionEnabled,area,onConversation={onHistory(!showHistory)}) { preview ->
            if (call.cameraSharing || call.cameraBusy) CameraPreview(vm.app.calls, call.callId, preview.testTag("beacon-camera-preview"))
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("beacon-call-stage")) {
        if(maxWidth>=700.dp) Row(Modifier.fillMaxSize()) {
            stage(Modifier.weight(1f));VerticalDivider();conversation(Modifier.width(320.dp).fillMaxHeight())
        } else if(showHistory) Column(Modifier.fillMaxSize()) {
            TextButton(onClick={onHistory(false)},modifier=Modifier.heightIn(min=48.dp).testTag("beacon-conversation-toggle")) { Text(uiText("Return to call")) }
            conversation(Modifier.weight(1f)); LiveCallDock(call,actions,motionEnabled)
        } else stage(Modifier.fillMaxSize())
    }
}

/** Whether the open chat is pinned, as History lists it; the chat's own snapshot never carries it, so only an action in this chat leaves a mark there. */
internal fun openChatPinned(rows: List<JSONObject>, chat: ChatState, profile: String): Boolean =
    rows.firstOrNull { sessionRowId(it) == chat.sessionId && it.string("profile", profile) == profile }?.optBoolean("pinned") ?: chat.raw.optBoolean("pinned")

/** Chat actions that destroy something: shown last in the menu, apart and in the error colour. */
internal val destructiveConversationActions = setOf("Delete conversation")

/** The chat menu's rows: the daily groups (empty ones dropped) and, apart, the destructive actions, wherever a group lists them. */
internal data class ConversationMenuGroups(val daily: List<List<String>>, val destructive: List<String>)

internal fun conversationMenuGroups(groups: List<List<String>>, dropNewConversation: Boolean = false): ConversationMenuGroups {
    val shown = groups.map { titles -> titles.filterNot { dropNewConversation && it == "New conversation" } }
    return ConversationMenuGroups(
        daily = shown.map { titles -> titles.filterNot { it in destructiveConversationActions } }.filter { it.isNotEmpty() },
        destructive = shown.flatten().filter { it in destructiveConversationActions }.distinct(),
    )
}

private fun chatAction(vm: BeaconViewModel, title: String) {
    if (!nativeConversationAction(title)) return vm.notice("$title is unavailable in native Hermes.")
    val chat = vm.chat.value
    val seed = json("session_id" to chat.sessionId)
    val result: suspend (JSONObject) -> Unit = { response -> vm.selectSession((response.optJSONObject("session") ?: response).string("session_id", chat.sessionId), titleHint = branchTitle(response)); vm.refreshSessions() }
    when (title) {
        "New conversation" -> vm.newChat()
        "Rename" -> vm.openForm(ActionForm(title, "/api/session/rename", listOf(Field("title", "Conversation title", required = true)), seed.put("title", chat.title), onResult = result))
        "Model & provider", "Reasoning", "Toolsets" -> vm.openCatalog(title)
        "Archive" -> vm.sessionAction("/api/session/archive", json("archived" to !chat.raw.optBoolean("archived")))
        "Pin" -> openChatPinned(vm.ui.value.sessions, chat, vm.ui.value.profile).let { pinned -> vm.conversationAction(chat.sessionId, "/api/session/pin", json("pinned" to !pinned), if (pinned) "Unpinned" else "Pinned") }
        "Branch" -> vm.openForm(ActionForm(title, "/api/session/branch", listOf(Field("title", "Branch title"), Field("keep_count", "Messages to keep", FieldType.INTEGER)), seed.put("keep_count", chat.messages.size), onResult = result))
        // "Shorten history" is the user-facing name the menu lists may give the same action.
        "Compress context", "Shorten history" -> vm.sessionAction("/api/session/compress") { response ->
            vm.notice(when (response.string("status")) { "compressed" -> "Context compressed"; "aborted" -> "Compression was not needed or was cancelled."; "pending", "lock_held" -> "${dev.hermes.android.data.currentAssistantName} is still working. Refresh the conversation to check progress."; else -> "Compression result is not confirmed. Refresh the conversation." })
        }
        "Undo last turn" -> vm.openForm(ActionForm(title, "/api/session/undo", emptyList(), seed, explanation = "Undo the latest conversation turn.", actionLabel = "Undo", onResult = result))
        "Usage" -> { vm.more("Session usage") }
        "Delete conversation" -> vm.openForm(ActionForm(title, "/api/session/delete", emptyList(), seed, explanation = "This deletes it from Hermes on every device. It can’t be undone.", actionLabel = "Delete", onResult = { vm.newChat(); vm.refreshSessions() }, destructive = true))
    }
}

private fun chatActionIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector = when (title) {
    "New conversation" -> Icons.Rounded.Add
    "Rename" -> Icons.Rounded.Edit
    "Pin", "Unpin" -> Icons.Rounded.PushPin
    "Archive" -> Icons.Rounded.Archive
    "Unarchive" -> Icons.Rounded.Unarchive
    "Branch" -> Icons.AutoMirrored.Rounded.CallSplit
    "Model & provider", "Reasoning" -> Icons.Rounded.Tune
    "Toolsets" -> Icons.Rounded.Build
    "Compress context", "Shorten history" -> Icons.Rounded.UnfoldLess
    "Undo last turn" -> Icons.AutoMirrored.Rounded.Undo
    "Usage" -> Icons.Rounded.DataUsage
    "Delete conversation" -> Icons.Rounded.DeleteOutline
    else -> Icons.Rounded.MoreHoriz
}

/** Voice, send and stop share one footprint, so the control does not jump when the draft changes. The circle is
 * 42dp; minimumInteractiveComponentSize keeps a 48dp tap space so a near miss never lands on Dictate. */
internal val composerActionSize = 42.dp
internal val composerActionIconSize = 22.dp

internal fun attachmentPreparationLabel(name: String, status: String, error: String, failed: String = "Upload failed. Remove and attach again.", preparing: String = "Preparing…"): String =
    "$name · " + if (status == "error") error.ifBlank { failed } else preparing

internal fun composerUsesVoice(text: String, readyAttachments: Int, preparingAttachments: Int): Boolean =
    text.isBlank() && readyAttachments == 0 && preparingAttachments == 0

@Composable private fun ChatSphere(modifier: Modifier, state: dev.hermes.android.ui.sphere.AvatarRenderState) {
    dev.hermes.android.ui.sphere.MorphingSphere(
        modifier = modifier,
        state = state.state,
        intensity = state.intensity,
        toolCallBurst = state.toolCallBurst,
        motionVisible = !state.paused,
    )
}
