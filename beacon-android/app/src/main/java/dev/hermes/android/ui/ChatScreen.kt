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
    LaunchedEffect(allProfiles, ui.origin, ui.owner) { if (allProfiles) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    LaunchedEffect(search, archived, view, hidden, order, allProfiles) { kotlinx.coroutines.delay(250); vm.refreshSessions(search, archived, force = false, threads = view == HistoryView.Threads, allProfiles = allProfiles) }
    // One scroll owner keeps destinations, search, projects and history reachable with the IME.
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("destinations") { beforeHistory() }
        item("history-header") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("History"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        item("history-search") {
            AnimatedVisibility(
                visible = searchExpanded || search.isNotBlank(),
                enter = if (motionEnabled) expandVertically() + fadeIn(tween(180)) else EnterTransition.None,
                exit = if (motionEnabled) shrinkVertically() + fadeOut(tween(120)) else ExitTransition.None,
            ) {
                // Opened from the search icon: focus once the field is attached (a restored query keeps the keyboard closed).
                LaunchedEffect(Unit) { if (search.isBlank()) searchFocusRequester.requestFocus() }
                OutlinedTextField(
                    search,
                    { search = it },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = if (search.isBlank()) null else ({ IconButton(onClick = { search = "" }) { Icon(Icons.Rounded.Close, uiText("Clear search")) } }),
                    placeholder = { Text(uiText("Search conversations")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).focusRequester(searchFocusRequester)
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
                        if (view != HistoryView.Threads && sources.isNotEmpty()) {
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
        val loaded = ui.sessions.filter {
            it.optBoolean("archived") == archived && (allProfiles || it.string("profile", ui.profile) == ui.profile) &&
                (view == HistoryView.Threads || it.string("source") !in hidden)
        }
        val searched = historySearchRows(loaded, search, matches, ui.profile)
        val rows = historyRows(searched, view, order, ui.profile, preferences.assistantProfile, ui.assistantSessionId)
        // The persistent assistant leads the list, like a pinned chat; its own tab keeps its conversation.
        if (view != HistoryView.Threads && !archived && (search.isBlank() || assistantTitle.contains(search.trim(), ignoreCase = true))) item("beacon-row") {
            val assistantRow = loaded.firstOrNull { ui.assistantSessionId.isNotBlank() && sessionRowId(it) == ui.assistantSessionId && it.string("profile", ui.profile) == preferences.assistantProfile }
            val glance = glances.rows["${preferences.assistantProfile}:${ui.assistantSessionId}"]
            val activity = assistantRow?.let(::rowActivityMillis) ?: 0L
            val unread = chatUnread(activity, glance, glances.base[preferences.assistantProfile] ?: Long.MAX_VALUE, open = ui.tab == "Beacon")
            // navigation-beacon is the tag of the drawer item this row replaces.
            Box(Modifier.testTag("navigation-beacon")) {
                ChatListRow(
                    id = ui.assistantSessionId.ifBlank { "beacon" }, title = assistantTitle,
                    preview = assistantRow?.let { chatPreviewLine(it, glance, youLabel, oneMessage, manyMessages) }.orEmpty().ifBlank { uiText("Text and voice, together") },
                    time = chatListTime(activity, is24Hour = is24Hour), unread = unread, pinned = true, selected = ui.tab == "Beacon", beacon = true,
                    modifier = Modifier.clickable { vm.navigate("Beacon") }.semantics { selected = ui.tab == "Beacon" }.testTag("history-beacon-row"),
                )
            }
        }
        // Threads are channels you keep adding; once one exists, starting the next stays in sight (the drawer has New thread too).
        if (view == HistoryView.Threads && !archived && search.isBlank() && rows.isNotEmpty()) item("new-thread-row") {
            ListItem(headlineContent = { Text(uiText("New thread")) }, leadingContent = { Icon(Icons.Rounded.Add, null) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clickable { naming = true }.testTag("threads-new-thread"))
        }
        // A failed read is not "no conversations".
        if (rows.isEmpty() && (ui.historyLoading || unreadable.isEmpty())) item("empty-history") {
            // An empty list is only "empty" once the fetch has answered.
            if (search.isNotBlank()) Text(uiText("No matching chats"), Modifier.padding(16.dp).testTag("history-no-matches"), color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** Fixed texts the chat shows through variables: tool outcomes and the header's status line. */
internal val ChatStrings = ToolOutcome.entries.map(::toolOutcomeLabel) + listOf("typing…", "thinking…", "Voice conversation")

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
    val messages = remember(chatState.messages, chatState.callMessages, chatState.sessionId) { dev.hermes.android.voice.interleaveCallMessages(visibleTranscriptMessages(chatState.messages), chatState.callMessages.map { Message("call:${it.eventId}", it.role, it.text, sessionId = chatState.sessionId, recordedAt = it.at) }) }
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
    val taskResultNoticeId = taskResultData?.string("notice_id").orEmpty()
    val taskResultIsEmailAlert = taskResultData?.optBoolean("email_alert") == true && taskResultNoticeId.isNotBlank()
    var taskFeedbackSent by remember(taskResult, taskResultNoticeId, chat.sessionId) { mutableStateOf(taskResultData?.optBoolean("feedback_sent") == true) }
    var taskFeedbackSending by remember(taskResult, taskResultNoticeId, chat.sessionId) { mutableStateOf(false) }
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
    fun sendTaskFeedback(verdict: String) {
        if (taskFeedbackSending || !taskResultIsEmailAlert || verdict !in setOf("important", "not_important")) return
        val source = vm.client ?: return
        val client = try { source.forAuthorizedProfile(ui.profile) } catch (_: Throwable) { vm.notice("Couldn't send feedback. Try again."); return }
        taskFeedbackSending = true
        scope.launch {
            try {
                client.call("$PROACTIVE_SETTINGS_PATH/feedback", mapOf("profile" to ui.profile), method = "POST", data = json("notice_id" to taskResultNoticeId, "verdict" to verdict))
                vm.clearTaskResult(taskResultScope, taskResultNoticeId)
                taskFeedbackSent = true
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (isMissingBeaconPluginRoute(error)) vm.storeData(proactiveKey, JSONObject().put("_pluginUpdateRequired", true))
                else vm.notice(proactiveErrorMessage(error, "Couldn't send feedback. Try again."))
            } finally { client.closeNative(); taskFeedbackSending = false }
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
    val visibleMessages = remember(messages, search, foldedToolResults, callIdByMessageId, firstCallMessageIdByCallId, matchingCallIds) {
        messages.withIndex().filter { (_, message) ->
            val callId = callIdByMessageId[message.id]
            val matches = if (callId != null) callId in matchingCallIds && firstCallMessageIdByCallId[callId] == message.id
                else search.isBlank() || message.text.contains(search, true) || message.tools.any { tool -> foldedToolResults.byCallId[toolCallResultId(tool)]?.content?.contains(search, true) == true }
            matches && message.id !in foldedToolResults.foldedMessageIds
        }.let(::mergeToolOnlyRuns)
    }
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
    val groups = remember(visibleMessages, dayStarts, callGroupByFirstMessageId, liveShown, liveBubble, sendingShown) {
        bubbleGroups(
            visibleMessages.map { it.value } + listOfNotNull(
                Message(LIVE_REPLY_ID, "assistant", if (liveBubble) "live" else "").takeIf { liveShown },
                Message(SENDING_MESSAGE_ID, "user", "sending").takeIf { sendingShown },
            ),
            dayStarts.keys, callGroupByFirstMessageId.keys,
        )
    }
    // The newest line and the viewed mark of this chat, for the chat list's preview and unread dot.
    val openRowActivity = remember(ui.sessions, chat.sessionId, ui.profile) { ui.sessions.firstOrNull { sessionRowId(it) == chat.sessionId && it.string("profile", ui.profile) == ui.profile }?.let(::rowActivityMillis) ?: 0L }
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
    val conversationEntry = remember { Animatable(1f) }
    LaunchedEffect(chat.sessionId, beacon, motionEnabled) {
        if (motionEnabled) { conversationEntry.snapTo(.35f); conversationEntry.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
        else conversationEntry.snapTo(1f)
    }
    val conversationContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.clipToBounds().chatBeaconBackground().graphicsLayer { alpha = conversationEntry.value }) {
            LaunchedEffect(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.messages.isNotEmpty(), search, ready) {
                if (!ready) return@LaunchedEffect
                if (search.isNotBlank()) {
                    withFrameNanos { }
                    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= visibleMessages.size.coerceAtLeast(1) }
                    list.requestScrollToItem(0)
                } else if ((!anchorRestored || !follow) && chat.messages.isNotEmpty()) {
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
            LaunchedEffect(anchorRestored, follow, search, chat.messages.size, visibleMessages.size, chat.liveText, chat.callMessages) {
                if (anchorRestored && follow && search.isBlank() && chat.messages.isNotEmpty()) {
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
            // Focus only while the field (and its requester) is composed; a hidden panel or rotation must not request focus.
            if (canonical && showSearch) LaunchedEffect(chat.sessionId) { searchFocusRequester.requestFocus() }
            if (canonical && showSearch) OutlinedTextField(search, { search = it }, placeholder = { Text(uiText("Find in conversation")) }, singleLine = true, suffix = if (search.isBlank()) null else ({ Text(visibleMessages.size.toString(), Modifier.testTag("search-count")) }), trailingIcon = { IconButton(onClick = { showSearch = false; search = "" }) { Icon(Icons.Rounded.Close, uiText("Close search")) } }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester).padding(horizontal = 16.dp))
            if (beacon && !ready) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Without an error the conversation is only switching (answering a call does this), so it says Opening, not unavailable.
                EmptyState(assistantText(if (ui.beaconError.isBlank()) "Opening Beacon’s conversation" else "Beacon’s conversation is unavailable"), uiText(ui.beaconError).ifBlank { uiText("Your conversation is opening. Your other chats and drafts are safely saved.") }, action = if (ui.beaconOpening) "" else assistantText("Retry Beacon"), onAction = { vm.openBeacon() })
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
                            Text(uiText(if (taskResultIsEmailAlert) "Important update" else "Task result"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { vm.clearTaskResult(taskResultScope, taskResultNoticeId, dismiss = true) }, modifier = Modifier.testTag("task-result-dismiss")) {
                                Icon(Icons.Rounded.Close, contentDescription = uiText("Dismiss"))
                            }
                        }
                        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
                            Markdown(taskResult, vm, taskResultScope, interactive = false)
                        }
                        if (pluginStatus == "available" && taskResultIsEmailAlert) {
                            if (taskFeedbackSent) Text(assistantText("Thanks — Beacon will remember."), Modifier.padding(start = 12.dp, bottom = 8.dp).testTag("task-result-feedback-sent"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { sendTaskFeedback("important") }, enabled = !taskFeedbackSending && ui.connected && vm.client != null, modifier = Modifier.testTag("task-result-feedback-important")) { Text(uiText("Important")) }
                                TextButton(onClick = { sendTaskFeedback("not_important") }, enabled = !taskFeedbackSending && ui.connected && vm.client != null, modifier = Modifier.testTag("task-result-feedback-not-important")) { Text(uiText("Not important")) }
                            }
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
                            Text(promptText, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
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
                    LazyColumn(Modifier.fillMaxSize().nestedScroll(ownerScroll).testTag("conversation-transcript"), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
                    if (beacon && (pluginStatus == "absent" || proactive?.optBoolean("_pluginUpdateRequired") == true)) item("missing-beacon-plugin") {
                        MissingBeaconPluginState(Modifier.testTag("beacon-plugin-missing"), compact = true)
                    }
                    if (chat.messages.isEmpty() && chat.streamId.isBlank() && !chatSendInFlight(chat)) item("empty-state") {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(.9f).aspectRatio(1f).clipToBounds().testTag("chat-sphere-empty")) { ChatSphere(Modifier.fillMaxSize(), avatarState) }
                            EmptyState(uiText("Where shall we begin?"), if (beacon) assistantText("Beacon keeps the same context across text, calls and completed tasks.") else uiText("Your work starts here. Choose a model and send your first message."), icon = null, centered = true)
                            StarterPrompts { vm.composer(TextFieldValue(it, TextRange(it.length))) }
                        }
                    }
                    if (search.isNotBlank() && visibleMessages.isEmpty()) item { Text(uiText("No messages match your search."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(visibleMessages, key = { (_, message) -> callGroupByFirstMessageId[message.id]?.let { "call:${it.callId}" } ?: message.id }) { (index, message) ->
                        dayStarts[message.id]?.let { DateChip(uiText(it)) }
                        val group = callGroupByFirstMessageId[message.id]
                        if (group != null) {
                            val metadata = callMetadata[group.callId]
                            CallTranscriptCard(group.callId, group.lines, metadata?.durationSeconds, metadata?.provider,
                                assistantName = LocalAssistantName.current, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), searchQuery = search)
                        } else MessageRow(vm, message, transcriptIndex[message.id] ?: index, groups[message.id] ?: BubbleGroup(startsRun = true, joinedTop = false, tail = true), bubbleMax, toolResultsByCallId = foldedToolResults.byCallId, resultTitle = toolResultTitles[message.id].orEmpty(), onToolExpanded = { expanded -> follow = followAfterToolToggle(follow, expanded) })
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
                    else if (chat.status.isNotBlank() && chat.streamId.isBlank()) item { Text(uiText(chat.status), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (ready && !follow && list.canScrollForward) SmallFloatingActionButton(
                        onClick = { follow = true; scope.launch { val last = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); if (motionEnabled) list.animateScrollToItem(last) else list.scrollToItem(last) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).minimumInteractiveComponentSize().testTag("scroll-to-latest"),
                        shape = CircleShape,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) { Icon(Icons.Rounded.ExpandMore, uiText("Scroll to latest"), Modifier.size(24.dp)) }
                }
            }
            if (ready && chat.uncertain.isNotBlank() && !chatSendInFlight(chat)) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("send-unconfirmed"), horizontalAlignment = Alignment.End) {
                // Where the message sits in the transcript: not confirmed, with the choices below.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SendTickIcon(SendTick.Unconfirmed)
                    Text(uiText("Not sure your last message arrived. Resending may duplicate it. Your draft is saved."), style = MaterialTheme.typography.bodyMedium)
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
                    ChatAvatar(headerTitle, chat.sessionId, size = 40.dp, beacon = beacon || activeHere, description = if (beacon || activeHere) assistantText("Beacon") else null)
                    // The title opens the same chat menu as the dots.
                    Column(Modifier.weight(1f).padding(start = 10.dp).heightIn(min = 48.dp).clickable(enabled = ready, onClickLabel = uiText("Conversation actions")) { menu = true }.padding(vertical = 4.dp).testTag("chat-header-title"), verticalArrangement = Arrangement.Center) {
                        Text(plainTitle(headerTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val headerModel = chatModelLabel(chat.raw.string("model"), profileDefaults["profile-defaults"]?.takeIf { it.string("profile") == ui.profile && recordError(it).isBlank() }?.string("model").orEmpty())
                        // Read from the cached project answer, so the project shows with the chat; nothing shows for a chat in no project.
                        val headerProject = chatProjectOf(profileDefaults["project-of:${chat.sessionId}"], chat.sessionId)
                        val headerLine = chatHeaderLine(chat.streamId.isNotBlank(), chat.liveText.isNotBlank(), (beacon || activeHere) && call.active, headerModel, beacon || activeHere, headerProject?.string("name").orEmpty())
                        if (headerLine.project.isNotBlank() || headerLine.text.isNotBlank()) ChatHeaderSubtitle(headerLine)
                    }
                    IconButton(onClick = { showSearch = !showSearch; if (activeHere && showSearch) showCallHistory = true }, enabled = canonical) { Icon(Icons.Rounded.Search, uiText("Search this conversation")) }
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
                            val blocked = activeHere && title in setOf("Delete conversation", "Undo last turn", "Compress context")
                            val ink = if (title == "Delete conversation") MaterialTheme.colorScheme.error else Color.Unspecified
                            DropdownMenuItem({ Column { Text(conversationMenuTitle(uiText(label))); if (blocked) Text(endCall, style = MaterialTheme.typography.labelSmall) } }, onClick = { menu = false; moreMenu = false; chatAction(vm, title) }, enabled = nativeConversationAction(title) && (canonical || title == "New conversation") && !blocked, leadingIcon = { Icon(chatActionIcon(label), null) },
                                colors = MenuDefaults.itemColors(textColor = ink, leadingIconColor = ink), modifier = Modifier.testTag("conversation-action:$title"))
                        }
                        DropdownMenu(menu && ready, { menu = false }, modifier = Modifier.heightIn(max = actionMenuHeight).testTag("conversation-actions-menu")) {
                            if (beacon) DropdownMenuItem({ Text(uiText("Call history")) }, { menu = false; showCallHistorySheet = true }, leadingIcon = { Icon(Icons.Rounded.History, null) }, modifier = Modifier.testTag("call-history-open"))
                            conversationActionGroups.entries.forEachIndexed { index, (group, titles) ->
                                if (index > 0) HorizontalDivider()
                                Text(uiText(group), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                titles.filterNot { permanentBotChat && it == "New conversation" }.forEach { actionItem(it) }
                            }
                            HorizontalDivider()
                            if (chat.queued.isNotEmpty() || chat.uncertain.isNotBlank()) DropdownMenuItem({ Text(uiText("Message queue")) }, { menu = false; composerDetails = true }, enabled = !activeHere)
                            if (composerDraft.text.startsWith("/")) DropdownMenuItem({ Text(uiText(if (chat.streamId.isNotBlank()) "Queue as text" else "Send as text")) }, { menu = false; if (chat.streamId.isNotBlank()) vm.queue() else vm.send() }, enabled = canonical && !activeHere && chat.uncertain.isBlank())
                            if (canonical && !activeHere && chat.streamId.isBlank()) ConversationProjectItem(vm, chat.sessionId, onAdd = { projects = true }, onDone = { menu = false }, onOpen = { openProjectFromChat(vm, it) })
                            if (canonical && chat.messages.isNotEmpty()) DropdownMenuItem({ Text(uiText("Share conversation")) }, { menu = false
                                runCatching { context.startActivity(Intent.createChooser(formattedShareIntent(conversationShareText(chat.messages.map { it.role to safeHistoryMessageText(it.role, it.text) }, assistantName)), shareTitle)) }
                                    .onFailure { vm.notice(if (it is IllegalArgumentException) it.message.orEmpty() else noShareApp) } }, leadingIcon = { Icon(Icons.Rounded.Share, null) })
                            if (canonical && isThreadSource(chat.raw.string("source"))) DropdownMenuItem({ Text(uiText("Scheduled posts")) }, { menu = false; threadPosts = true }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) })
                            // The developer-facing actions live one step away from the daily ones.
                            if (canonical) DropdownMenuItem({ Text(uiText("More")) }, { menu = false; moreMenu = true }, leadingIcon = { Icon(Icons.Rounded.MoreHoriz, null) }, trailingIcon = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) }, modifier = Modifier.testTag("conversation-more"))
                            DropdownMenuItem({ Text(uiText("Chat preferences")) }, { menu = false; vm.more("Settings") }, leadingIcon = { Icon(Icons.Rounded.Tune, null) })
                        }
                        DropdownMenu(moreMenu && ready, { moreMenu = false }, modifier = Modifier.heightIn(max = actionMenuHeight).testTag("conversation-more-menu")) {
                            conversationMoreActions.forEach { actionItem(it) }
                            DropdownMenuItem({ Text(uiText("Copy conversation ID")) }, { moreMenu = false
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
        if (ready && !activeHere) Column(Modifier.fillMaxWidth().onSizeChanged { onComposerHeight(it.height) }) {
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
    val submit: () -> Unit = {
        if (preparing.isNotEmpty()) vm.notice("Finish or remove attachments before sending")
        else if (draft.text.startsWith("/") && (data["slash:catalog"] == null || recordError(data["slash:catalog"]).isNotBlank())) vm.notice("Wait for commands to load or retry the command list.")
        else if (commandName != null) vm.runSlashCommand(draft.text.substringBefore(' ').removePrefix("/"), draft.text.substringAfter(' ', ""))
        else if (chat.streamId.isNotBlank()) vm.queue() else vm.send()
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
    var modelMenu by remember { mutableStateOf(false) }
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
                            Text(name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                            Text(command.string("description"), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
                    if (chat.queued.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 144.dp).testTag("composer-queue"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item(key = "queue-heading") { Text(queuedHeading(chat.queued.size, uiText("1 message will send after this reply"), uiText("{0} messages will send after this reply")), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        itemsIndexed(chat.queued) { index, queued -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(queued.text.ifBlank { uiText("Attachment") } + if (queued.attachments.isNotEmpty()) " · " + uiText(if (queued.attachments.size == 1) "1 file" else "{0} files").replace("{0}", "${queued.attachments.size}") else "", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.removeQueued(index) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove queued message")) }
                        } }
                    }
                    if (files.isNotEmpty() || preparing.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("composer-attachments"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.forEachIndexed { index, file -> PendingAttachmentChip(file, onClick = { onDetails(true) }, onRemove = { vm.removeAttachment(index) }) }
                        preparing.forEach { file -> InputChip(selected = false, onClick = { onDetails(true) }, label = { Text(attachmentPreparationLabel(file.string("name"), file.string("status"), uiText(file.string("error")), uiText("Upload failed. Remove and attach again."), uiText("Preparing…")), maxLines = 2) }, leadingIcon = { if (file.string("status") != "error") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ErrorOutline, uiText("Upload failed")) }, trailingIcon = { IconButton(onClick = { vm.dismissAttachmentPreparation(file.string("id")) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove") + " " + file.string("name")) } }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp)) }
                    }
                    // A visible state pill, so Queue vs. Guide does not depend on the trailing icon alone.
                    if (streaming && !voiceAction) Text(uiText("Will send after this reply"), Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 3.dp).testTag("composer-queue-caption"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
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
                            TextButton(onClick = { modelMenu = true }, enabled = canonical && !callActive && !readOnly, modifier = Modifier.heightIn(min = 48.dp).testTag("composer-model-effort").semantics {
                                if (modelLabel.isBlank() && effortLabel.isBlank()) contentDescription = modelOptionsDescription
                            }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
                                    if (modelLabel.isNotBlank()) Text(modelLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                    if (effortLabel.isNotBlank()) Text(effortLabel, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                DropdownMenuItem({ Text(uiText("Model & provider")) }, { modelMenu = false; vm.openCatalog("Model & provider") })
                                DropdownMenuItem({ Text(uiText("Reasoning effort")) }, { modelMenu = false; vm.openCatalog("Reasoning") })
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
                            FilledIconButton(onClick = submit, shape = CircleShape, modifier = Modifier.minimumInteractiveComponentSize().size(composerActionSize).testTag("message-send"), enabled = editable && preparing.isEmpty() && chat.uncertain.isBlank() && !callActive && !readOnly) { Icon(Icons.Rounded.ArrowUpward, uiText(if (commandName != null) "Run command" else if (chat.streamId.isNotBlank()) "Queue" else "Send"), Modifier.size(composerActionIconSize)) }
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
            if (files.isEmpty() && preparing.isEmpty() && chat.queued.isEmpty()) Text(uiText("No attachments or queued messages"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEachIndexed { index, file -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(file.string("name", file.string("filename", uiText("Attachment"))), Modifier.weight(1f)); IconButton(onClick = { vm.removeAttachment(index) }) { Icon(Icons.Rounded.Close, uiText("Remove attachment")) } } }
            chat.queued.forEachIndexed { index, queued -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(uiText("Queued") + ": ${queued.text}${if (queued.attachments.isNotEmpty()) " · " + uiText(if (queued.attachments.size == 1) "1 file" else "{0} files").replace("{0}", "${queued.attachments.size}") else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = { vm.removeQueued(index) }) { Icon(Icons.Rounded.Close, uiText("Remove queued message")) } } }
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
@Composable private fun ChatBubble(own: Boolean, group: BubbleGroup, bubbleMax: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.widthIn(max = bubbleMax),
        shape = bubbleShape(own, group.joinedTop, group.tail),
        color = if (own) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (own) null else BorderStroke(.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f)),
    ) { Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 7.dp, bottom = 5.dp), content = content) }
}

/** Plain words stay plain Text, so a short bubble shrinks to them; anything richer keeps the Markdown renderer. */
@Composable private fun BubbleText(text: String, vm: BeaconViewModel, origin: Scope?, snapshots: Map<String, String> = emptyMap(), documentKey: String = "", interactive: Boolean = true) {
    if (isPlainChatText(text)) SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
    else Markdown(text, vm, origin, snapshots, documentKey, interactive)
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

@Composable private fun MessageRow(vm: BeaconViewModel, message: Message, index: Int, group: BubbleGroup, bubbleMax: Dp, toolResultsByCallId: Map<String, FoldedToolResult> = emptyMap(), resultTitle: String = "", onToolExpanded: (Boolean) -> Unit = {}) {
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
            Box(Modifier.widthIn(max = bubbleMax).messageGestures(openMessageActions, a11yActions).testTag("message-bubble:${message.id}")) {
                Disclosure(presentation.summary, displayText)
                actionsMenu()
            }
        } else if (hasContent) {
            Box(Modifier.messageGestures(openMessageActions, a11yActions).testTag("message-bubble:${message.id}")) {
                ChatBubble(own, group, bubbleMax) {
                    // A scheduled result filed in this thread reads as a post, headed by the job that made it.
                    if (post != null) Row(Modifier.padding(bottom = 2.dp).testTag("channel-post:${message.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(post.job.ifBlank { uiText("Scheduled post") }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    if (displayText.isNotBlank()) {
                        if (own && !settings.optBoolean("render_user_markdown", true)) SelectionContainer { Text(displayText, style = MaterialTheme.typography.bodyLarge) }
                        else BubbleText(displayText, vm, origin, message.mediaSnapshots, message.id)
                    }
                    HistoryAttachmentChips(history.attachments)
                    BubbleMeta(message.recordedAt, message.id, if (own) SendTick.Sent else null)
                }
                actionsMenu()
            }
        }
        if (message.tools.isNotEmpty()) Box(Modifier.widthIn(max = bubbleMax)) { MessageActivity("", message.tools, messageTime = message.recordedAt, toolResultsByCallId = toolResultsByCallId, onToolExpanded = onToolExpanded, onCopyResult = copySource, vm = vm, scope = origin) }
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
                    Text(attachment.name, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
            Text(name, Modifier.weight(1f, fill = false).padding(horizontal = 7.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
            if (renderContent == null) SelectionContainer { Text(content, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
    val current = tools.lastOrNull()?.let(::toolDisplayName).orEmpty()
    val summary = if (live) current else toolStepsSummary(tools, uiText("1 step"), uiText("{0} steps"))
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
internal fun toolDisplayName(tool: JSONObject): String {
    val more = if (tool.string("name", tool.optJSONObject("function")?.string("name") ?: "") == "tool_call") tool.innerToolNames().size - 1 else 0
    val server = listOf("server", "server_name", "mcp_server", "tool_server").firstNotNullOfOrNull { key -> tool.string(key).takeIf(String::isNotBlank) }.orEmpty()
    return readableToolName(tool.rawToolName(), server) + if (more > 0) " and $more more" else ""
}
/** Hermes' own lookup steps say nothing about the work; a step summary names the tools that did it. */
private val toolLookupNames = setOf("tool_search", "tool_describe", "skills_list", "skill_view")
internal fun toolStepsSummary(tools: List<JSONObject>, one: String = "1 step", many: String = "{0} steps"): String {
    val names = tools.filterNot { it.rawToolName() in toolLookupNames }.map(::toolDisplayName).distinct()
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
    val statusTitle = uiText("Status")
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
    Surface(Modifier.fillMaxWidth().testTag("tool-card").semantics { contentDescription = "${toolDisplayName(tool)}, $label" + (if (meta.isNotBlank() && outcome != ToolOutcome.Dispatched && outcome != ToolOutcome.NoResult) ", $meta" else "") }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = uiText(if (expanded) "Hide details" else "Details")) { expanded = !expanded; onExpandedChange(expanded) }.testTag("tool-card-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(rawName), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(toolDisplayName(tool), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = if (preparing) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (meta.isNotBlank()) { Text(meta, Modifier.testTag("tool-card-meta"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                Icon(statusIcon, label, Modifier.size(16.dp), tint = statusColor)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(2.dp), color = MaterialTheme.colorScheme.primary)
            if (expanded) SelectionContainer { Column(Modifier.padding(bottom = 12.dp).testTag("tool-card-details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$statusTitle: $label", Modifier.testTag("tool-card-status"), style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (outcome == ToolOutcome.Dispatched) Text(uiText("The task was handed off. Its own completion isn’t recorded here."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tool.string("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // Full recorded text, newlines intact, in a bounded scroll area; plain text only.
                argumentsText?.let { ToolDetailBlock(uiText("Arguments"), it, "tool-card-arguments", MaterialTheme.colorScheme.onSurface, toolKey) }
                if (resultText.isNotBlank()) ToolDetailBlock(uiText(if (outcome == ToolOutcome.Failed) "Error" else "Result"), resultText, "tool-card-result", if (outcome == ToolOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, toolKey, onCopy = if (foldedResult != null) ({ onCopyResult(resultText) }) else null)
                else if (outcome == ToolOutcome.Completed || outcome == ToolOutcome.Failed) Text(uiText("The tool finished with an empty result."), Modifier.testTag("tool-card-empty-result"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Partial output is shown as such; it never marks the call finished.
                previewText?.let { ToolDetailBlock(uiText("Preview (partial)"), it, "tool-card-preview", MaterialTheme.colorScheme.onSurface, toolKey) }
                if (outcome == ToolOutcome.NoResult) Text(uiText("No completion was recorded for this tool."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable private fun ToolDetailBlock(title: String, text: String, tag: String, color: Color, toolKey: String, onCopy: (() -> Unit)? = null) {
    var detailLimit by rememberSaveable(toolKey, tag) { mutableStateOf(ToolDetailPageSize) }
    val shownLimit = detailLimit.coerceAtMost(ToolDetailLimit)
    if (onCopy == null) Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val copyLabel = uiText("Copy result")
        IconButton(onCopy, Modifier.size(48.dp).testTag("$tag-copy").semantics { contentDescription = copyLabel }) {
            Icon(Icons.Rounded.ContentCopy, null)
        }
    }
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text(boundedDetail(text, shownLimit), Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = color)
    }
    if (text.length > shownLimit && shownLimit < ToolDetailLimit) TextButton(onClick = { detailLimit = nextToolDetailLimit(shownLimit) }, modifier = Modifier.testTag("$tag-show-more")) { Text(uiText("Show more")) }
    if (shownLimit > ToolDetailPageSize) TextButton(onClick = { detailLimit = ToolDetailPageSize }, modifier = Modifier.testTag("$tag-show-less")) { Text(uiText("Show less")) }
}

/** A send Hermes has not answered yet; an unknown outcome is shown separately, once the request has failed or been interrupted. */
internal fun chatSendInFlight(chat: ChatState): Boolean = chat.uncertain.isNotBlank() && chat.status == "Sending"

/** The confirmation before a resend of a message whose delivery is unconfirmed; one place for the row and the details sheet. */
private fun resendForm(vm: BeaconViewModel) = ActionForm("Resend retained draft", "", emptyList(), explanation = "The earlier delivery is unconfirmed. Resending may create a duplicate if it already arrived.", actionLabel = "Resend", onResult = { vm.reviewPendingSend(resend = true) })

/** The message being sent, as its stored bubble will look (with a clock tick), so its arrival does not shift the list. */
@Composable private fun SendingMessage(text: String, group: BubbleGroup, bubbleMax: Dp) {
    Column(Modifier.fillMaxWidth().padding(top = if (group.startsRun) 10.dp else 3.dp).testTag("sending-message"), horizontalAlignment = Alignment.End) {
        ChatBubble(own = true, group, bubbleMax, Modifier.alpha(.7f)) {
            Text(text.ifBlank { uiText("Attachment") }, style = MaterialTheme.typography.bodyLarge)
            BubbleMeta(System.currentTimeMillis(), SENDING_MESSAGE_ID, SendTick.Sending)
        }
    }
}

/** "2 messages will send after this reply"; [one] and [many] are the translated templates, and {0} is the count. */
internal fun queuedHeading(count: Int, one: String = "1 message will send after this reply", many: String = "{0} messages will send after this reply"): String =
    (if (count == 1) one else many).replace("{0}", "$count")

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
            questions.forEach { q -> val id = q.getString("qid"); Text(q.getString("question")); OutlinedTextField(answers[id].orEmpty(), { onAnswer(id, it) }, label = { Text(uiText("Your answer")) }, modifier = Modifier.fillMaxWidth()); q.optJSONArray("choices")?.strings()?.forEach { choice -> TextButton(onClick = { onAnswer(id, choice) }) { Text(choice) } } }
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
        text={Text(uiText("Camera and screen sharing need another voice. Switch and continue the call?"))},
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
        "Compress context" -> vm.sessionAction("/api/session/compress") { response ->
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
    "Compress context" -> Icons.Rounded.UnfoldLess
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
