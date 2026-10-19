@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui


import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.MissingVoiceKeyException
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.voice.transcriptLabel
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.ui.sphere.resolveSphereState
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal suspend fun voiceKeyConfiguredOnIo(store: () -> VoiceKeyStore): Boolean = withContext(Dispatchers.IO) {
    val keyStore = store()
    keyStore.hasKey(keyStore.settings().provider)
}

internal fun separateToolResultIds(messages: List<JSONObject>): Set<String> = messages.asSequence()
    .filter { it.string("role") == "tool" || it.string("role") == "function" }
    .map { it.string("tool_call_id") }
    .filter(String::isNotBlank)
    .toSet()

internal fun toolCallResultId(tool: JSONObject): String = tool.string("tool_call_id").ifBlank { toolIdentity(tool) }

/** Hermes stores the tool that produced a result row as `tool_name`; title the row after it. */
internal fun toolResultTitles(messages: List<JSONObject>): Map<String, String> = messages.asSequence()
    .filter { (it.string("role") == "tool" || it.string("role") == "function") && it.string("id").isNotBlank() }
    .mapNotNull { row -> row.string("tool_name").takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,80}")) }?.let { row.string("id") to it.replace('_', ' ').replaceFirstChar(Char::titlecase) + " result" } }
    .toMap()

@Composable fun SessionList(vm: CypherViewModel, modifier: Modifier = Modifier, beforeHistory: @Composable () -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(ui.query) }
    var archived by rememberSaveable(ui.origin, ui.owner, ui.profile) { mutableStateOf(false) }
    LaunchedEffect(search, archived) { kotlinx.coroutines.delay(250); vm.refreshSessions(search, archived, force = false) }
    // One scroll owner keeps destinations, search, projects and history reachable with the IME.
    LazyColumn(modifier.background(MaterialTheme.colorScheme.surfaceContainer), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("destinations") { beforeHistory() }
        item("history-header") { Text(uiText("History"), Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item("history-search") { OutlinedTextField(search, { search = it }, leadingIcon = { Icon(Icons.Rounded.Search, null) }, placeholder = { Text(uiText("Search conversations")) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) }
        item("history-filters") { Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            FilterChip(archived, { archived = !archived }, { Text(uiText("Archived")) })
        } }
        val groups = navigationHistoryGroups(ui.sessions.map { it.optBoolean("pinned") })
        groups.forEach { (label, indices) ->
            if (indices.isNotEmpty()) item("history:$label") { Text(uiText(label), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val rows = indices.map { ui.sessions[it] }
        if (ui.sessions.isEmpty() && label == "Recent") item("empty-history") {
            // An empty list is only "empty" once the fetch has answered.
            if (ui.historyLoading) ListItem(headlineContent = { Text(uiText("Loading conversations…")) }, leadingContent = { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.testTag("history-loading"))
            else EmptyState("No conversations here", "Start a conversation or try another search.", action = "New conversation", onAction = { vm.toggleSessions(false); vm.newChat() })
        }
        items(rows, key = { "session:" + sessionRowId(it) }) { row ->
            val id = sessionRowId(row)
            ListItem(headlineContent = { Text(row.string("title").ifBlank { "Untitled conversation" }, maxLines = 2) }, leadingContent = { Icon(if (row.optBoolean("pinned")) Icons.Rounded.PushPin else Icons.Rounded.ChatBubbleOutline, null) }, trailingContent = { if (row.string("active_stream_id").isNotBlank()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }, colors = ListItemDefaults.colors(containerColor = if (id == chat.sessionId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), modifier = Modifier.semantics { selected = id == chat.sessionId }.clickable { vm.tab("Chat"); vm.openConversation(row) }.heightIn(min = 56.dp))
        }
        }

    }
}

@Composable fun ChatScreen(vm: CypherViewModel, xara: Boolean = false, onComposerHeight: (Int) -> Unit = {}, navigationBack: (() -> Unit)? = null, titleOverride: String? = null) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chatState by vm.chat.collectAsStateWithLifecycle()
    val composerDraft by vm.composer.collectAsStateWithLifecycle()
    val messages = remember(chatState.messages, chatState.callMessages, chatState.sessionId) { chatState.messages + chatState.callMessages.map { Message("call:${it.eventId}", it.role, it.text, sessionId = chatState.sessionId) } }
    val chat = chatState.copy(messages = messages)
    val callMessagesById = remember(chatState.callMessages) { chatState.callMessages.associateBy { "call:${it.eventId}" } }
    val separateToolResults = remember(chat.raw) { separateToolResultIds(chat.raw.rows("messages")) }
    val toolResultTitles = remember(chat.raw) { toolResultTitles(chat.raw.rows("messages")) }
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    var surfacedCallError by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(call.error) {
        if (call.error.isBlank()) surfacedCallError = ""
        else if (call.error != surfacedCallError) { surfacedCallError = call.error; vm.notice(call.error) }
    }
    val handoff by vm.voiceHandoff.collectAsStateWithLifecycle()
    val pendingVoiceCall by vm.voiceCallRequest.collectAsStateWithLifecycle()
    if(handoff?.scope==vm.authenticatedScope()&&handoff?.targetCall?.isBlank()==true) AlertDialog(
        onDismissRequest=vm::cancelVoiceHandoff,
        title={Text("Switching to Gemini…")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth());Text("Closing Grok and reconnecting this conversation. Your dialogue stays in this chat.") }},
        confirmButton={TextButton(vm::cancelVoiceHandoff) { Text("Stop") }})
    val side by vm.side.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val settings = LocalCypherSettings.current
    val ready = !xara || vm.isCypherConversation()
    val canonical = ready && chat.sessionId.isNotBlank()
    val permanentBotChat = ui.botModeEnabled && ui.tab == "More" && ui.morePage == "Bots"
    val activeHere = call.active && call.conversationScope == vm.authenticatedScope()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var activitySheet by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    val activity = remember(chat.liveTools, chat.queued.size, chat.approval, chat.clarification, chat.streamId) {
        chatActivity(chat.liveTools.mapIndexed { index, tool -> tool.snapshot(index, chat.streamId.isNotBlank()) }, chat.queued.size, chat.approval != null, chat.clarification != null)
    }
    var composerDetails by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    var projects by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    if (projects) ChatProjectPicker(vm, onDismiss = { projects = false })
    var search by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf("") }
    var showSearch by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(showSearch, canonical, chat.sessionId) { if (showSearch && canonical) searchFocusRequester.requestFocus() }
    val promptScope = vm.authenticatedScope()?.takeIf { it.copy(session = "") == vm.client?.scope }
    val questionId = chat.clarification?.let { it.string("clarify_id", it.string("id")) }.orEmpty()
    var questionAnswers by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, questionId,
        stateSaver = androidx.compose.runtime.saveable.mapSaver<Map<String, String>>(save = { it }, restore = { saved -> saved.mapValues { it.value as String } })) { mutableStateOf(emptyMap<String, String>()) }
    val visibleMessages = remember(messages, search) { messages.withIndex().filter { search.isBlank() || it.value.text.contains(search, true) } }
    val messageIds = remember(chat.messages) { chat.messages.map { it.id } }
    val list = key(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) {
        val start = initialConversationPosition(messageIds, chat.anchorId, chat.anchor, chat.anchorOffset)
        rememberLazyListState(initialFirstVisibleItemIndex = start.first, initialFirstVisibleItemScrollOffset = start.second)
    }
    val motionEnabled = rememberNativeMotionEnabled(prefs.reducedMotion)
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
    val transcriptAvatarState = avatarState.copy(paused = !sphereMotionEnabled || (!sphereStreaming && !sphereHasError))
    var showCallHistory by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId) { mutableStateOf(false) }
    BackHandler(activeHere && showCallHistory) { showCallHistory = false }
    LaunchedEffect(call.active) { if (!call.active) showCallHistory = false }
    LaunchedEffect(ready) { if (!ready) { menu = false; showSearch = false; search = "" } }
    var follow by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) { mutableStateOf(followsTail(true, settings.optBoolean("auto_scroll_follow", true), chat.anchorId.isNotBlank() || chat.anchor != 0 || chat.anchorOffset != 0)) }
    var anchorRestored by rememberSaveable(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.projectionReady) { mutableStateOf(chat.projectionReady) }
    var ownerScrolling by remember(list) { mutableStateOf(false) }
    val ownerScroll = remember(list) { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && (consumed.y != 0f || available.y != 0f)) ownerScrolling = true
            return Offset.Zero
        }
    } }
    var permissionClient by remember { mutableStateOf<dev.hermes.android.data.CypherClient?>(null) }
    var permissionConversation by remember { mutableStateOf<String?>(null) }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val requested=permissionClient;val conversation=permissionConversation;permissionClient=null;permissionConversation=null
        if(allowed&&requested!=null&&conversation!=null&&conversation==vm.authenticatedScope()?.session&&vm.client?.scope==requested.scope) scope.launch {
            runCatching { vm.stopReadAloud();vm.app.calls.start(requested,conversation) }.onFailure { error ->
                if(error is MissingVoiceKeyException) vm.openVoiceKeySettingsForCall(requested,conversation) else vm.notice(error.message.orEmpty())
            }
        } else vm.notice(if(allowed) "Open the conversation again before calling" else "Allow microphone permission to call Cypher")
    }
    fun requestCall(active:CypherClient,conversation:String) {
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
        if(ui.tab !in setOf("Chat","Xara") && !permanentBotChat) return@LaunchedEffect
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
                val id = current.messages.getOrNull(position.first)?.id ?: current.callMessages.getOrNull(position.first - current.messages.size)?.let { "call:${it.eventId}" }
                if (id != null) vm.anchor(position.first, position.second, id)
            }
        }
        if (!position.third) ownerScrolling = false
    } }
    val conversationEntry = remember { Animatable(1f) }
    LaunchedEffect(chat.sessionId, xara, motionEnabled) {
        if (motionEnabled) { conversationEntry.snapTo(.35f); conversationEntry.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
        else conversationEntry.snapTo(1f)
    }
    val conversationContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier.clipToBounds().chatDottedBackground().graphicsLayer { alpha = conversationEntry.value }) {
            LaunchedEffect(ui.origin, ui.owner, ui.profile, chat.sessionId, chat.messages.isNotEmpty(), search, ready) {
                if (!ready) return@LaunchedEffect
                if (search.isNotBlank()) {
                    withFrameNanos { }
                    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= visibleMessages.size.coerceAtLeast(1) }
                    list.requestScrollToItem(0)
                } else if ((!anchorRestored || !follow) && chat.messages.isNotEmpty()) {
                    // Await this panel's layout, not the last session's retained item count.
                    withFrameNanos { }
                    snapshotFlow { list.layoutInfo.totalItemsCount }.first { it >= chat.messages.size }
                    if (chat.anchor > 0 || chat.anchorOffset > 0 || chat.anchorId.isNotBlank()) {
                        follow = false
                        val index = anchoredIndex(messageIds, chat.anchorId, chat.anchor) ?: 0
                        // Apply the saved position during the attached list's next measurement.
                        list.requestScrollToItem(index, chat.anchorOffset)
                    }
                    anchorRestored = true
                }
            }
            LaunchedEffect(anchorRestored, follow, search, chat.messages.size, chat.liveText, chat.callMessages) {
                if (anchorRestored && follow && search.isBlank() && chat.messages.isNotEmpty()) {
                    if (chat.anchor > 0 || chat.anchorOffset > 0 || chat.anchorId.isNotBlank()) vm.anchor(0, 0, "")
                    // Markdown, media and fold layouts can grow after the first measurement.
                    // Keep following their measured tail, rather than only the incoming text/count.
                    snapshotFlow {
                        val layout = list.layoutInfo
                        if (list.canScrollForward && layout.totalItemsCount >= chat.messages.size) {
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
            if (canonical && showSearch) OutlinedTextField(search, { search = it }, placeholder = { Text(uiText("Find in conversation")) }, singleLine = true, trailingIcon = { IconButton(onClick = { showSearch = false; search = "" }) { Icon(Icons.Rounded.Close, uiText("Close search")) } }, modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester).padding(horizontal = 16.dp))
            if (xara && !ready) Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                EmptyState(if (ui.xaraOpening) "Opening Cypher’s conversation" else if (ui.profile != "default") "Cypher uses the default profile" else "Cypher’s conversation is unavailable", ui.xaraError.ifBlank { "Your conversation is opening. Your other chats and drafts are safely saved." }, action = if (ui.xaraOpening) "" else if (ui.profile != "default") "Switch to default profile" else "Retry Cypher", onAction = { if (ui.profile != "default") vm.switchProfile("default") else vm.openXara() })
            } else if (chat.sessionId.isBlank()) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(.9f).aspectRatio(1f).clipToBounds().testTag("chat-sphere-empty")) { ChatSphere(Modifier.fillMaxSize(), avatarState) }
                EmptyState(if (ui.newChatOpening) "Opening your conversation" else "Make room for a thought", if (ui.newChatError.isNotBlank()) ui.newChatError else if (ui.newChatOpening) "Keep typing. Your draft stays here while the conversation opens." else "Ask, explore or turn an idea into working progress.", action = if (ui.newChatError.isNotBlank() && !ui.newChatOpening) "Retry" else "", onAction = { vm.retryInitialChat() })
            }
            else if (!chat.projectionReady) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            else Box(Modifier.weight(1f).fillMaxWidth().testTag("transcript-layer")) {
                if (chat.messages.isNotEmpty() || chat.liveText.isNotBlank() || chat.liveReasoning.isNotBlank() || sphereStreaming)
                    ChatSphere(Modifier.fillMaxSize().alpha(if (sphereStreaming) .5f else .12f).testTag("chat-sphere-background"), transcriptAvatarState)
                LazyColumn(Modifier.fillMaxSize().nestedScroll(ownerScroll).testTag("conversation-transcript"), state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                if (chat.messages.isEmpty() && chat.streamId.isBlank()) item("empty-state") {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.widthIn(max = 280.dp).fillMaxWidth(.9f).aspectRatio(1f).clipToBounds().testTag("chat-sphere-empty")) { ChatSphere(Modifier.fillMaxSize(), avatarState) }
                        EmptyState("Where shall we begin?", if (xara) "Cypher keeps the same context across text, calls and completed tasks." else "Your work starts here. Choose a model and send your first message.")
                    }
                }
                if (search.isNotBlank() && visibleMessages.isEmpty()) item { Text("No messages match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visibleMessages, key = { it.value.id }) { (index, message) -> MessageRow(vm, message, index, callMessagesById[message.id], separateToolResults, toolResultTitles[message.id].orEmpty(), onToolExpanded = { expanded -> follow = followAfterToolToggle(follow, expanded) }) }
                if (chat.streamId.isNotBlank() || chat.liveText.isNotBlank() || chat.liveReasoning.isNotBlank()) item("live") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (chat.liveText.isNotBlank()) AssistantSurface { Markdown(chat.liveText, vm, vm.authenticatedScope(), interactive = false) }
                        else LinearProgressIndicator(Modifier.width(120.dp))
                        MessageActivity(chat.liveReasoning.takeIf { settings.optBoolean("show_thinking", true) }.orEmpty(), chat.liveTools, chat.status.ifBlank { "Working" }, streaming = true, onToolExpanded = { expanded -> follow = followAfterToolToggle(follow, expanded) })
                    }
                }
                chat.approval?.let { p -> item("approval:${p.string("approval_id", p.string("id"))}") {
                    val exactCypherAction = p.string("approval_id").startsWith("cypher-cf:")
                    Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(uiText("Approval needed"), style = MaterialTheme.typography.titleMedium)
                        Text(p.string("description", p.string("command", "The agent requests permission to continue.")))
                        if (p.string("command").isNotBlank()) CodeBlock(if (exactCypherAction) "json" else "shell", p.string("command"))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { promptScope?.let { vm.approval("once", p, it) } }, enabled = promptScope != null && "once" in p.optJSONArray("choices")?.strings().orEmpty()) { Text(uiText("Allow once")) }
                            if (!exactCypherAction && "session" in p.optJSONArray("choices")?.strings().orEmpty()) OutlinedButton(onClick = { promptScope?.let { vm.approval("session", p, it) } }, enabled = promptScope != null) { Text(uiText("This session")) }
                            TextButton(onClick = { promptScope?.let { vm.approval("deny", p, it) } }, enabled = promptScope != null && "deny" in p.optJSONArray("choices")?.strings().orEmpty()) { Text(uiText("Deny")) }
                            if (!exactCypherAction && "always" in p.optJSONArray("choices")?.strings().orEmpty()) TextButton(onClick = { promptScope?.let { vm.approval("always", p, it) } }, enabled = promptScope != null) { Text(uiText("Always allow")) }
                        }
                    } }
                } }
                chat.clarification?.let { p -> item("clarify:$questionId") { Clarification(vm, p, promptScope, questionAnswers) { id, value -> questionAnswers = questionAnswers + (id to value) } } }
                if (chat.status.isNotBlank() && chat.streamId.isBlank()) item { Text(chat.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (ready && !follow && list.canScrollForward) FloatingActionButton(
                    onClick = { follow = true; scope.launch { val last = (list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0); if (motionEnabled) list.animateScrollToItem(last) else list.scrollToItem(last) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("scroll-to-latest"),
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { Icon(Icons.Rounded.ExpandMore, uiText("Scroll to latest"), Modifier.size(24.dp)) }
            }
            if (ready && chat.uncertain.isNotBlank()) Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Text(uiText("Checking whether your last message arrived. Your draft is saved."), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { vm.reconcileSend() }) { Text(uiText("Check receipt")) } }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val actionMenuHeight = (maxHeight - 16.dp).coerceAtLeast(48.dp).coerceAtMost(440.dp)
    Column(Modifier.fillMaxSize()) {
        Glass(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (navigationBack != null) IconButton(onClick = navigationBack, modifier = Modifier.testTag("bot-chat-back")) { Icon(Icons.Rounded.ArrowBack, "Back to bots") }
                else IconButton(onClick = LocalOpenNavigationMenu.current ?: { vm.toggleSessions(!ui.showSessions) }, modifier = Modifier.testTag("navigation-toggle")) { Icon(Icons.Rounded.Menu, uiText(if (LocalPersistentNavigation.current) "Hide navigation" else "Show navigation")) }
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) { Text(titleOverride ?: if (xara || activeHere) "Cypher" else chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); Text(if (xara || activeHere) uiText(if (call.active) "Voice conversation" else "Text and voice, together") else if (!canonical) uiText("Loading model…") else chat.raw.string("model", "Choose a model"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                if (!xara && !permanentBotChat) IconButton(onClick = { vm.newChat() }) { Icon(Icons.Rounded.Edit, uiText("New conversation")) }
                IconButton(onClick = { showSearch = !showSearch; if (activeHere && showSearch) showCallHistory = true }, enabled = canonical) { Icon(Icons.Rounded.Search, uiText("Search this conversation")) }
                Box { IconButton(onClick = { menu = true }, enabled = ready) { Icon(Icons.Rounded.MoreVert, uiText("Conversation actions")) }; DropdownMenu(menu && ready, { menu = false }, modifier = Modifier.heightIn(max = actionMenuHeight).testTag("conversation-actions-menu")) {
                    conversationActionGroups.entries.forEachIndexed { index, (group, titles) ->
                        if (index > 0) HorizontalDivider()
                        Text(uiText(if (group == "Model and tools") "Tools" else group), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        titles.filterNot { it in setOf("Model & provider", "Reasoning") || (permanentBotChat && it == "New conversation") }.forEach { title ->
                            val label = conversationActionLabel(title, chat.raw.optBoolean("pinned"), chat.raw.optBoolean("archived")).replaceFirstChar { it.uppercase() }
                            DropdownMenuItem({ Column { Text(uiText(label)); if (activeHere && title in setOf("Delete conversation", "Undo last turn", "Compress context")) Text("End this call to continue", style = MaterialTheme.typography.labelSmall) } }, onClick = { menu = false; chatAction(vm, title) }, enabled = nativeConversationAction(title) && (canonical || title == "New conversation") && !(activeHere && title in setOf("Delete conversation", "Undo last turn", "Compress context")), leadingIcon = { Icon(chatActionIcon(label), null) }, modifier = Modifier.testTag("conversation-action:$title"))
                        }
                    }
                    HorizontalDivider()
                    if (chat.queued.isNotEmpty() || chat.uncertain.isNotBlank()) DropdownMenuItem({ Text("Message queue") }, { menu = false; composerDetails = true }, enabled = !activeHere)
                    if (composerDraft.text.startsWith("/")) DropdownMenuItem({ Text(if (chat.streamId.isNotBlank()) "Queue as text" else "Send as text") }, { menu = false; if (chat.streamId.isNotBlank()) vm.queue() else vm.send() }, enabled = canonical && !activeHere && chat.uncertain.isBlank())
                    DropdownMenuItem({ Text("Projects") }, { menu = false; projects = true }, enabled = !activeHere && chat.streamId.isBlank() && chat.sessionId.isNotBlank())
                    DropdownMenuItem({ Text("Chat preferences") }, { menu = false; vm.more("Settings") })
                } }
            }
        }
        if (activeHere) {
            CypherCallStage(vm, call, motionEnabled, showCallHistory, { showCallHistory = it }, Modifier.weight(1f), conversationContent)
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (!xara && call.conversationScope == vm.authenticatedScope() && call.transcriptStatus.isNotBlank()) Text(call.transcriptLabel(), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelSmall)
                conversationContent(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
            }
        }
        if (ready && !activeHere) Column(Modifier.fillMaxWidth().onSizeChanged { onComposerHeight(it.height) }) {
            if (activity.active) Box(Modifier.fillMaxWidth().widthIn(max = 840.dp).align(Alignment.CenterHorizontally)) { ChatActivityStrip(activity) { activitySheet = true } }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Composer(vm, activeHere, !call.active, callThisChat, composerDetails, { composerDetails = it }) }
            ChatStatusStrip(ui, chat)
        }
    }
    }
    side?.takeIf { ready }?.let { question -> AlertDialog(onDismissRequest = vm::closeSideQuestion, title = { Text(uiText("Side question")) }, text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(question.question, style = MaterialTheme.typography.titleMedium); Text(question.status, color = MaterialTheme.colorScheme.onSurfaceVariant); Markdown(question.text, vm, question.scope.session(question.sessionId), interactive = false) } }, confirmButton = { TextButton(onClick = vm::closeSideQuestion) { Text(uiText("Close")) } }, dismissButton = { TextButton(onClick = vm::resumeSideQuestion) { Text(uiText("Reconnect")) } }) }
    if (canonical) NativeCatalog(vm)
    if (activitySheet && ready) ChatActivitySheet(activity, chat.queued.map { it.text }, onRemoveQueued = { index -> vm.removeQueued(index) }, onDismiss = { activitySheet = false })
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

@Composable private fun Composer(vm: CypherViewModel, callActive: Boolean, callAvailable: Boolean, onCall: () -> Unit, details: Boolean, onDetails: (Boolean) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val draft by vm.composer.collectAsStateWithLifecycle()
    val settings = LocalCypherSettings.current
    val readOnly = chat.raw.optBoolean("read_only") || chat.raw.optBoolean("is_read_only")
    val canonical = chat.sessionId.isNotBlank()
    val editable = (chat.projectionReady || ui.newChatOpening) && (canonical || vm.canComposeInitialChat())
    val files by vm.attachments.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val preparing by vm.attachmentPreparation.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val inputClient = vm.client
    val inputScope = vm.authenticatedScope()
    val commandName = nativeSlashName(draft.text, data["slash:catalog"]?.rows("commands").orEmpty().map { it.string("name") })
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
    var attachmentRequest by remember { mutableStateOf<Pair<CypherClient, Scope?>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> val request = attachmentRequest; attachmentRequest = null; if (uris.isNotEmpty()) { if (request != null && vm.client === request.first && vm.authenticatedScope() == request.second) uris.distinct().forEach { vm.upload(it) } else vm.notice("Reopen attachments in the original account and conversation.") } }
    var dictationRequest by remember { mutableStateOf<Pair<CypherClient, Scope?>?>(null) }
    val dictation = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> val request = dictationRequest; dictationRequest = null; val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull(); if (r.resultCode == android.app.Activity.RESULT_OK && text != null) { if (request != null && vm.client === request.first && vm.authenticatedScope() == request.second) vm.dictated(text) else vm.notice("Reopen dictation in the original account and conversation.") } }
    var modelMenu by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { vm.loadSlashCatalog() }
    // Hermes reports a blank model/effort when the chat follows its profile (tui_gateway _session_info, _cfg_get_reasoning),
    // so the profile's own values are the chat's effective ones.
    val effort = chat.raw.string("reasoning_effort", chat.raw.string("reasoning"))
    val followsProfile = chat.raw.string("model").isBlank() || effort.isBlank()
    val defaults = data["profile-defaults"]?.takeIf { followsProfile && it.string("profile") == ui.profile && recordError(it).isBlank() }
    LaunchedEffect(ui.origin, ui.owner, ui.profile, followsProfile) { if (followsProfile) vm.load("profile-defaults", "/api/profile/defaults") }
    BoxWithConstraints(Modifier.widthIn(max = 840.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Reserve the action row before the editor grows. The editor scrolls its own long draft.
        val commandHeight = if (draft.text.startsWith("/")) (maxHeight - 160.dp).coerceIn(48.dp, 160.dp) else 0.dp
        val editorHeight = (maxHeight - 112.dp - commandHeight - (if (commandHeight > 0.dp) 8.dp else 0.dp) - if(chat.streamId.isNotBlank()) 48.dp else 0.dp).coerceAtMost(160.dp).coerceAtLeast(1.dp)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.text.startsWith("/")) {
                val catalog = data["slash:catalog"]
                val commands = catalog?.rows("commands").orEmpty().filter { slashMatches(it.string("name"), it.string("description"), draft.text) }.sortedBy { if (it.string("name").removePrefix("/").startsWith(draft.text.removePrefix("/"), true)) 0 else 1 }.take(20)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = commandHeight).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).testTag("slash-suggestions")) {
                    items(commands) { command -> TextButton(onClick = { val text = "/${command.string("name").removePrefix("/")} "; changeDraft(TextFieldValue(text, androidx.compose.ui.text.TextRange(text.length))) }, enabled = editable && !readOnly, modifier = Modifier.heightIn(min = 48.dp)) { Column(Modifier.fillMaxWidth()) { Text("/${command.string("name").removePrefix("/")}"); Text(command.string("description"), style = MaterialTheme.typography.bodySmall, maxLines = 2) } } }
                    if (commands.isEmpty()) item { Text(if (catalog == null) "Loading commands…" else if (draft.text.contains(' ')) "Review arguments, then run command" else "No matching native commands", style = MaterialTheme.typography.bodySmall) }
                    if (recordError(catalog).isNotBlank()) item { TextButton(onClick = { vm.loadSlashCatalog(force = true) }) { Text("Retry commands") } }
                }
            }
            val voiceAction = composerUsesVoice(draft.text, files.size, preparing.size)
            val streaming = chat.streamId.isNotBlank()
            if (streaming) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp).heightIn(min = 24.dp).testTag("composer-correct-caption"), verticalAlignment = Alignment.CenterVertically) {
                Text(uiText("Correct now"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Rounded.ExpandLess, null, Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // Relay-style composer surface: one pill with a hairline border.
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    if (chat.queued.isNotEmpty()) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 144.dp).testTag("composer-queue"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item(key = "queue-heading") { Text(queuedHeading(chat.queued.size), Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        itemsIndexed(chat.queued) { index, queued -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(queued.text.ifBlank { "Attachment" } + if (queued.attachments.isNotEmpty()) " · ${queued.attachments.size} files" else "", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            IconButton(onClick = { vm.removeQueued(index) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove queued message")) }
                        } }
                    }
                    if (files.isNotEmpty() || preparing.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("composer-attachments"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        files.forEachIndexed { index, file -> InputChip(selected = false, onClick = { onDetails(true) }, label = { Text(file.string("name", file.string("filename", "Attachment")), maxLines = 1) }, trailingIcon = { IconButton(onClick = { vm.removeAttachment(index) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Remove ${file.string("name", file.string("filename", "attachment"))}") } }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 260.dp)) }
                        preparing.forEach { file -> InputChip(selected = false, onClick = { onDetails(true) }, label = { Text(attachmentPreparationLabel(file.string("name"), file.string("status"), file.string("error")), maxLines = 2) }, leadingIcon = { if (file.string("status") != "error") CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.ErrorOutline, "Upload failed") }, trailingIcon = { IconButton(onClick = { vm.dismissAttachmentPreparation(file.string("id")) }, Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "Remove ${file.string("name")}") } }, modifier = Modifier.heightIn(min = 48.dp).widthIn(max = 280.dp)) }
                    }
                    // A visible state pill, so Queue vs. Guide does not depend on the trailing icon alone.
                    if (streaming && !voiceAction) Text(uiText("Queue next · delivers after this turn"), Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.secondaryContainer).padding(horizontal = 8.dp, vertical = 3.dp).testTag("composer-queue-caption"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    OutlinedTextField(draft, changeDraft, placeholder = { Text(if (callActive) "Finish your call to continue typing" else if (readOnly) "Branch this conversation to reply" else if (streaming) uiText("Correct the response...") else "Message Cypher") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp.coerceAtMost(editorHeight), max = editorHeight).testTag("message-input").onPreviewKeyEvent { event -> val key = event.nativeKeyEvent; val sendKey = settings.string("send_key", "enter"); val matched = when (sendKey) { "ctrl_enter", "ctrl+enter" -> key.isCtrlPressed && !key.isShiftPressed; "shift+enter" -> key.isShiftPressed && !key.isCtrlPressed; else -> !key.isShiftPressed && !key.isCtrlPressed && !key.isAltPressed }; if (key.action == android.view.KeyEvent.ACTION_DOWN && key.keyCode == android.view.KeyEvent.KEYCODE_ENTER && matched && !callActive && !readOnly) { if (editable && chat.uncertain.isBlank()) submit(); true } else false }, minLines = 1, maxLines = 6, enabled = editable && !callActive && !readOnly, shape = RoundedCornerShape(18.dp), colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = .5f)))
                    if (streaming && !voiceAction) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.steer() }, enabled = canonical && preparing.isEmpty() && !readOnly && !callActive && draft.text.isNotBlank() && !draft.text.startsWith("/") && chat.uncertain.isBlank() && files.isEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Guide") }
                        TextButton(onClick = { vm.cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            IconButton(onClick = { attachMenu = true }, enabled = canonical && !callActive && !readOnly, modifier = Modifier.testTag("composer-attach")) { Icon(Icons.Rounded.Add, uiText("Attach a file")) }
                            DropdownMenu(attachMenu, { attachMenu = false }, modifier = Modifier.testTag("attach-menu")) {
                                DropdownMenuItem({ Text(uiText("Photos")) }, { attachMenu = false; vm.client?.let { attachmentRequest = it to vm.authenticatedScope(); picker.launch(arrayOf("image/*")) } }, leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, null) }, modifier = Modifier.testTag("attach-photos"))
                                DropdownMenuItem({ Text(uiText("Files")) }, { attachMenu = false; vm.client?.let { attachmentRequest = it to vm.authenticatedScope(); picker.launch(arrayOf("*/*")) } }, leadingIcon = { Icon(Icons.Rounded.AttachFile, null) }, modifier = Modifier.testTag("attach-files"))
                            }
                        }
                        // Attach stays at the left; run controls sit together at the right edge.
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { Row(verticalAlignment = Alignment.CenterVertically) {
                        NativeApprovalMenu(vm, enabled = canonical && !callActive && !readOnly)
                        Box(Modifier.weight(1f, fill = false).widthIn(max = 168.dp)) {
                            TextButton(onClick = { modelMenu = true }, enabled = canonical && !callActive && !readOnly, modifier = Modifier.heightIn(min = 48.dp).testTag("composer-model-effort"), contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.Start) {
                                    Text(chat.raw.string("model").ifBlank { defaults?.string("model").orEmpty() }.ifBlank { "Model" }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                    Text(effort.ifBlank { defaults?.string("reasoning_effort").orEmpty() }.ifBlank { "Effort" }, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp))
                            }
                            DropdownMenu(modelMenu, { modelMenu = false }) {
                                DropdownMenuItem({ Text("Model & provider") }, { modelMenu = false; vm.openCatalog("Model & provider") })
                                DropdownMenuItem({ Text("Reasoning effort") }, { modelMenu = false; vm.openCatalog("Reasoning") })
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
                            OutlinedIconButton(onClick = { vm.cancel() }, shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.error), modifier = Modifier.size(composerActionSize).testTag("message-stop")) { Icon(Icons.Rounded.Stop, uiText("Stop"), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error) }
                        } else if (voiceAction) {
                            FilledIconButton(onClick = onCall, shape = CircleShape, modifier = Modifier.size(composerActionSize).testTag("chat-call-xara"), enabled = callAvailable && canonical && !callActive && !readOnly && chat.streamId.isBlank() && ui.bootstrap.optJSONObject("voice")?.optBoolean("available") == true) { Icon(Icons.Rounded.GraphicEq, uiText("Talk to Cypher in this conversation"), Modifier.size(24.dp)) }
                        } else {
                            FilledIconButton(onClick = submit, shape = CircleShape, modifier = Modifier.size(composerActionSize).testTag("message-send"), enabled = editable && preparing.isEmpty() && chat.uncertain.isBlank() && !callActive && !readOnly) { Icon(Icons.Rounded.ArrowUpward, uiText(if (commandName != null) "Run command" else if (chat.streamId.isNotBlank()) "Queue" else "Send"), Modifier.size(24.dp)) }
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
            if (chat.status.isNotBlank()) Text(chat.status, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chat.uncertain.isNotBlank()) {
                Text("Delivery is unconfirmed. Compare the saved conversation before reviewing or deliberately resending your retained draft.")
                TextButton(onClick = { vm.reconcileSend() }) { Text("Refresh saved conversation") }
                TextButton(onClick = { vm.openForm(ActionForm("Mark message reviewed", "", emptyList(), explanation = "You have checked the saved conversation. Keep the draft and clear its unconfirmed delivery state.", actionLabel = "Mark reviewed", onResult = { vm.reviewPendingSend() })); onDetails(false) }) { Text("Mark reviewed") }
                TextButton(onClick = { vm.openForm(ActionForm("Resend retained draft", "", emptyList(), explanation = "The earlier delivery is unconfirmed. Resending may create a duplicate if it already arrived.", actionLabel = "Resend", onResult = { vm.reviewPendingSend(resend = true) })); onDetails(false) }) { Text("Resend draft…") }
            }

            preparing.forEach { file -> Text(attachmentPreparationLabel(file.string("name"), file.string("status"), file.string("error"))) }
            if (files.isEmpty() && preparing.isEmpty() && chat.queued.isEmpty()) Text(uiText("No attachments or queued messages"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            files.forEachIndexed { index, file -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(file.string("name", file.string("filename", "Attachment")), Modifier.weight(1f)); IconButton(onClick = { vm.removeAttachment(index) }) { Icon(Icons.Rounded.Close, uiText("Remove attachment")) } } }
            chat.queued.forEachIndexed { index, queued -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Queued: ${queued.text}${if (queued.attachments.isNotEmpty()) " · ${queued.attachments.size} files" else ""}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = { vm.removeQueued(index) }) { Icon(Icons.Rounded.Close, uiText("Remove queued message")) } } }
        }
    }
}


@Composable private fun MessageRow(vm: CypherViewModel, message: Message, index: Int, callTranscript: CallTranscript? = null, separateToolResults: Set<String> = emptySet(), resultTitle: String = "", onToolExpanded: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val origin = vm.authenticatedScope()?.session(message.sessionId.ifBlank { vm.chat.value.sessionId })
    val settings = LocalCypherSettings.current
    val presentation = chatPresentation(message.role, message.text, JSONObject().put("name", resultTitle), tools = message.tools)
    val reading by vm.readingAloud.collectAsStateWithLifecycle()
    val currentCall by vm.app.calls.state.collectAsStateWithLifecycle()
    var actions by remember(message.id) { mutableStateOf(false) }
    val copy = { runCatching { ensureClipboardFits(message.text); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Cypher message", message.text)) }.onSuccess { vm.notice("Message copied") }.onFailure { vm.notice("Couldn’t copy the whole message. Select a smaller section.") }; Unit }
    val share = { runCatching { context.startActivity(Intent.createChooser(formattedShareIntent(message.text), "Share message")) }.onFailure { vm.notice(if (it is IllegalArgumentException) it.message.orEmpty() else "No app is available to share this message") }; Unit }
    val renderActions: @Composable () -> Unit = {
            if (callTranscript == null && message.text.isNotBlank()) Row(Modifier.horizontalScroll(rememberScrollState()).testTag("message-actions:${message.id}"), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                IconButton(onClick = { runCatching { val (text, html) = formattedClipboard(message.text); (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newHtmlText("Cypher message", text, html)) }.onSuccess { vm.notice("Formatted message copied") }.onFailure { vm.notice("Couldn’t copy the whole message. Select a smaller section.") } }) { Icon(Icons.Rounded.ContentCopy, uiText("Copy formatted message")) }
                IconButton(onClick = share) { Icon(Icons.Rounded.Share, uiText("Share text")) }
                IconButton(onClick = { if (reading) vm.stopReadAloud() else vm.readAloud(presentation.text) }) { Icon(if (reading) Icons.Rounded.Stop else Icons.Rounded.VolumeUp, uiText(if (reading) "Stop read aloud" else "Read aloud")) }
                Box {
                    IconButton(onClick = { actions = true }) { Icon(Icons.Rounded.MoreHoriz, uiText("More message actions")) }
                    DropdownMenu(actions, { actions = false }) {
                        DropdownMenuItem({ Text("Copy source") }, { actions = false; copy() })
                        DropdownMenuItem({ Text(uiText("Branch from here")) }, { actions = false; vm.sessionAction("/api/session/branch", json("keep_count" to index + 1)) { o -> (o.optJSONObject("session") ?: o).string("session_id").takeIf(String::isNotBlank)?.let(vm::selectSession) } }, leadingIcon = { Icon(Icons.Rounded.CallSplit, null) })
                        if (message.role == "user") DropdownMenuItem({ Text(uiText("Edit and branch")) }, { actions = false; vm.sessionAction("/api/session/branch", json("keep_count" to index)) { o -> (o.optJSONObject("session") ?: o).string("session_id").takeIf(String::isNotBlank)?.let { vm.selectSession(it, message.text) } } }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    }
                }
            }
    }
    Column(Modifier.fillMaxWidth().testTag("message-row:${message.id}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (message.interrupted && callTranscript == null) Text(uiText("Interrupted"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (presentation.activity) {
            Disclosure(presentation.summary, message.text, renderActions)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = if (message.role == "user") Arrangement.End else Arrangement.Start) {
                Text(if (message.role == "user") uiText("You") else "Cypher", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (callTranscript != null) { Spacer(Modifier.weight(1f)); Text(archivedCallLabel(callTranscript.final, callTranscript.interrupted, currentCall.active && currentCall.conversationScope == origin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (message.role == "user") BoxWithConstraints(Modifier.fillMaxWidth()) {
                Surface(modifier = Modifier.widthIn(max = maxWidth * .9f).align(Alignment.CenterEnd), shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 4.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) { if (settings.optBoolean("render_user_markdown", true)) Markdown(presentation.text, vm, origin, message.mediaSnapshots, documentKey = message.id) else SelectionContainer { Text(presentation.text, style = MaterialTheme.typography.bodyLarge) }; BubbleTime(message) }
                }
            } else if (presentation.text.isNotBlank()) AssistantSurface { Markdown(presentation.text, vm, origin, message.mediaSnapshots, documentKey = message.id); BubbleTime(message) }
            renderActions()
        }
        MessageActivity(message.reasoning.takeIf { settings.optBoolean("show_thinking", true) }.orEmpty(), message.tools, messageTime = message.recordedAt, separateToolResults = separateToolResults, onToolExpanded = onToolExpanded)
        message.attachments.forEach { attachment -> SecureAttachment(vm, attachment, origin, message.mediaSnapshots) }
    }
}

@Composable fun Disclosure(title: String, content: String, actions: @Composable () -> Unit = {}) {
    var expanded by rememberSaveable(title, content.take(50)) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            Spacer(Modifier.width(8.dp)); Text(uiText(title), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            Spacer(Modifier.width(8.dp)); Text(uiText(if (expanded) "Hide details" else "Details"), style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) {
            SelectionContainer { Text(content, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            actions()
        }
    }
}

// Message surfaces, per-tool cards, composer pill and activity strip/sheet adapt Hermes Relay's
// MessageBubble, ToolProgressCard, ChatInputBar and GatewayBackgroundProcesses (MIT, see licenses/Hermes-Relay-MIT.txt).
// Only presentation is adapted; Cypher's own ChatState and callbacks supply every value.

/** Each tool is its own collapsed card; reasoning and live status sit above them. */
@Composable internal fun MessageActivity(reasoning: String, tools: List<JSONObject>, status: String = "", streaming: Boolean = status.isNotBlank(), messageTime: Long = 0L, separateToolResults: Set<String> = emptySet(), onToolExpanded: (Boolean) -> Unit = {}) {
    if (reasoning.isBlank() && tools.isEmpty() && status.isBlank()) return
    Column(Modifier.fillMaxWidth().testTag("message-activity"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status.isNotBlank()) Text(status, Modifier.testTag("message-activity-status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (reasoning.isNotBlank()) Disclosure("Reasoning", reasoning)
        tools.forEachIndexed { index, tool -> key(tool.toolKey(index)) { ToolCard(tool, streaming, messageTime, onToolExpanded, toolCallResultId(tool) in separateToolResults) } }
    }
}

private fun JSONObject.rawToolName(): String {
    val name = string("name", optJSONObject("function")?.string("name") ?: "")
    // Hermes runs deferred tools through its `tool_call` meta-tool; name the tool it actually ran.
    if (name == "tool_call") {
        val inner = argumentsValue()?.let { it as? JSONObject ?: runCatching { JSONObject(it.toString()) }.getOrNull() }?.string("name").orEmpty()
        if (inner.matches(Regex("[A-Za-z0-9_.:-]{1,80}"))) return inner
    }
    return name.ifBlank { "tool" }
}
internal fun toolDisplayName(tool: JSONObject): String = tool.rawToolName().replace('_', ' ')
private fun JSONObject.toolKey(index: Int): String = toolIdentity(this).ifBlank { "${rawToolName()}:$index" }
/** Native completion carries `result` (possibly empty) and `is_error`; a present key is a recorded fact even when empty. */
private fun JSONObject.recordedValue(vararg keys: String): Any? = keys.firstNotNullOfOrNull { key -> if (has(key) && !isNull(key)) opt(key) else null }
private fun Any.detailText(): String = when (this) { is JSONObject -> toString(2); is org.json.JSONArray -> toString(2); else -> toString() }
private fun JSONObject.argumentsValue(): Any? = (opt("args") ?: opt("arguments") ?: optJSONObject("function")?.opt("arguments"))?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() }
/** The native `duration_s`, only when it is a real non-negative number. */
private fun JSONObject.durationSeconds(): Double? = (opt("duration_s") as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }

/** `preview` is partial output from a still-running call, so it is never terminal evidence. */
private fun toolOutcomeOf(tool: JSONObject, streaming: Boolean): ToolOutcome {
    val result = tool.recordedValue("result", "output")
    val explicit = result != null || (tool.has("is_error") && !tool.isNull("is_error"))
    return toolOutcome(tool.rawToolName(), tool.string("status"), tool.optBoolean("is_error"), result?.detailText(), explicit, streaming)
}

private fun JSONObject.snapshot(index: Int, streaming: Boolean) =
    ToolSnapshot(toolKey(index), toolDisplayName(this), toolOutcomeOf(this, streaming), durationSeconds())

private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    listOf("bash", "terminal", "execute", "shell", "code").any { it in name } -> Icons.Rounded.Code
    listOf("search", "web", "browser").any { it in name } -> Icons.Rounded.Search
    listOf("file", "read", "write", "patch").any { it in name } -> Icons.Rounded.Description
    listOf("tap", "click", "swipe", "scroll").any { it in name } -> Icons.Rounded.TouchApp
    else -> Icons.Rounded.Build
}

@Composable fun ToolCard(tool: JSONObject, streaming: Boolean = false, messageTime: Long = 0L, onExpandedChange: (Boolean) -> Unit = {}, hasSeparateResult: Boolean = false) {
    val rawName = tool.rawToolName()
    val outcome = toolOutcomeOf(tool, streaming)
    val separateResult = hasSeparateResult && outcome == ToolOutcome.NoResult
    val running = outcome == ToolOutcome.Running
    val preparing = outcome == ToolOutcome.Preparing
    // A running tool opens like Relay's; history and preparing tools start folded.
    val toolKey = tool.toolKey(0)
    var expanded by rememberSaveable(toolKey) { mutableStateOf(running) }
    val finished = outcome == ToolOutcome.Completed || outcome == ToolOutcome.Failed || outcome == ToolOutcome.Dispatched
    val duration = toolDurationLabel(tool.durationSeconds())
    // Native tool events carry a duration but no clock time; like Relay, a finished call shows its message's recorded time.
    val time = messageTimeLabel(messageTime.takeIf { finished } ?: 0L)
    val argumentsText = tool.argumentsValue()?.detailText()
    val resultText = tool.recordedValue("result", "output")?.detailText().orEmpty()
    val previewText = tool.recordedValue("preview")?.detailText().orEmpty().takeIf { resultText.isBlank() && it.isNotBlank() }
    val statusTitle = uiText("Status")
    val meta = if (separateResult) "See transcript" else when (outcome) {
        ToolOutcome.Dispatched -> toolOutcomeLabel(outcome)
        ToolOutcome.NoResult -> "No result"
        else -> listOfNotNull(duration, time).joinToString(" · ")
    }
    val (statusIcon, statusColor) = if (separateResult) Icons.Rounded.Description to MaterialTheme.colorScheme.primary else when (outcome) {
        ToolOutcome.Completed -> Icons.Rounded.Check to MaterialTheme.colorScheme.primary
        ToolOutcome.Failed -> Icons.Rounded.Close to MaterialTheme.colorScheme.error
        ToolOutcome.Preparing -> Icons.Rounded.MoreHoriz to MaterialTheme.colorScheme.onSurfaceVariant
        ToolOutcome.Running -> Icons.Rounded.HourglassTop to MaterialTheme.colorScheme.primary
        ToolOutcome.Dispatched -> Icons.Rounded.Send to MaterialTheme.colorScheme.secondary
        ToolOutcome.NoResult -> Icons.Rounded.HelpOutline to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = if (separateResult) "Result in transcript" else toolOutcomeLabel(outcome)
    Surface(Modifier.fillMaxWidth().testTag("tool-card").semantics { contentDescription = "${toolDisplayName(tool)}, $label" + (if (meta.isNotBlank() && outcome != ToolOutcome.Dispatched && outcome != ToolOutcome.NoResult) ", $meta" else "") }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClickLabel = uiText(if (expanded) "Hide details" else "Details")) { expanded = !expanded; onExpandedChange(expanded) }.testTag("tool-card-toggle"), verticalAlignment = Alignment.CenterVertically) {
                Icon(toolIcon(rawName), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(toolDisplayName(tool).replaceFirstChar(Char::titlecase), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = if (preparing) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (meta.isNotBlank()) { Text(meta, Modifier.testTag("tool-card-meta"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1); Spacer(Modifier.width(6.dp)) }
                Icon(statusIcon, label, Modifier.size(16.dp), tint = statusColor)
                Spacer(Modifier.width(4.dp))
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preparing) argumentsText?.let { Text(compactToolDetail(it, 80), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 6.dp).height(2.dp), color = MaterialTheme.colorScheme.primary)
            if (expanded) SelectionContainer { Column(Modifier.padding(bottom = 12.dp).testTag("tool-card-details"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$statusTitle: $label", Modifier.testTag("tool-card-status"), style = MaterialTheme.typography.labelSmall, color = statusColor)
                if (outcome == ToolOutcome.Dispatched) Text("The task was handed off. Its own completion isn’t recorded here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tool.string("description").takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                // Full recorded text, newlines intact, in a bounded scroll area; plain text only.
                argumentsText?.let { ToolDetailBlock("Arguments", it, "tool-card-arguments", MaterialTheme.colorScheme.onSurface, toolKey) }
                if (resultText.isNotBlank()) ToolDetailBlock(if (outcome == ToolOutcome.Failed) "Error" else "Result", resultText, "tool-card-result", if (outcome == ToolOutcome.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, toolKey)
                else if (outcome == ToolOutcome.Completed || outcome == ToolOutcome.Failed) Text("The tool finished with an empty result.", Modifier.testTag("tool-card-empty-result"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // Partial output is shown as such; it never marks the call finished.
                previewText?.let { ToolDetailBlock("Preview (partial)", it, "tool-card-preview", MaterialTheme.colorScheme.onSurface, toolKey) }
                if (separateResult) Text("The result is shown in a separate transcript row.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else if (outcome == ToolOutcome.NoResult) Text("No completion was recorded for this tool.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
    }
}

@Composable private fun ToolDetailBlock(title: String, text: String, tag: String, color: Color, toolKey: String) {
    var detailLimit by rememberSaveable(toolKey, tag) { mutableStateOf(ToolDetailPageSize) }
    val shownLimit = detailLimit.coerceAtMost(ToolDetailLimit)
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(rememberScrollState()).padding(8.dp)) {
        Text(boundedDetail(text, shownLimit), Modifier.testTag(tag), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), color = color)
    }
    if (text.length > shownLimit && shownLimit < ToolDetailLimit) TextButton(onClick = { detailLimit = nextToolDetailLimit(shownLimit) }, modifier = Modifier.testTag("$tag-show-more")) { Text(uiText("Show more")) }
    if (shownLimit > ToolDetailPageSize) TextButton(onClick = { detailLimit = ToolDetailPageSize }, modifier = Modifier.testTag("$tag-show-less")) { Text(uiText("Show less")) }
}

/** Relay's assistant bubble: restrained surface, small tail corner, wide enough for rich content. */
@Composable private fun AssistantSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(.96f).testTag("assistant-bubble"), shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), content = content)
    }
}

/** Real recorded time only; messages without one show none. */
@Composable private fun BubbleTime(message: Message) {
    messageTimeLabel(message.recordedAt)?.let { Text(it, Modifier.padding(top = 4.dp).testTag("message-time:${message.id}"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

internal fun queuedHeading(count: Int): String = "$count ${if (count == 1) "message" else "messages"} queued — delivers after this turn"

/** Composer-adjacent summary of this chat's active work; opens the activity sheet. */
@Composable internal fun ChatActivityStrip(activity: ChatActivity, onClick: () -> Unit) {
    val status = chatActivityStatus(activity)
    // Translate in composition; semantics lambdas are not composable.
    val title = uiText("Current chat activity")
    val openLabel = uiText("Open current chat activity")
    val description = "$title, $status"
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).heightIn(min = 48.dp).testTag("chat-activity-strip").semantics { contentDescription = description; stateDescription = status }.clickable(onClickLabel = openLabel, onClick = onClick), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (activity.running.isNotEmpty()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.HourglassTop, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(9.dp))
            Text(uiText("Current chat activity"), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Icon(Icons.Rounded.Visibility, null, Modifier.padding(start = 6.dp).size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Read-only: this chat's own tools, queue and prompts. No stop control exists without a real process API. */
@Composable internal fun ChatActivitySheet(activity: ChatActivity, queued: List<String>, onRemoveQueued: (Int) -> Unit, onDismiss: () -> Unit) {
    // No clock here: native tool events record no start time, so nothing can honestly tick.
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().testTag("chat-activity-sheet").heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(uiText("Current chat activity"), style = MaterialTheme.typography.titleLarge)
                    Text(uiText("Read-only live details from this chat"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp).testTag("chat-activity-close")) { Icon(Icons.Rounded.Close, uiText("Close current chat activity")) }
            }
            if (!activity.active && activity.recent.isEmpty()) Text(uiText("No active work in this chat."), Modifier.padding(horizontal = 20.dp, vertical = 16.dp).testTag("chat-activity-empty"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (activity.running.isNotEmpty()) {
                ActivitySection("Running", activity.running.size)
                activity.running.forEach { tool -> ActivityRow(tool.name, toolOutcomeLabel(tool.outcome), true) }
            }
            if (activity.waiting.isNotEmpty()) {
                ActivitySection("Waiting for you", activity.waiting.size)
                activity.waiting.forEach { ActivityRow(it, "Answer it in the conversation", false) }
            }
            if (activity.queued > 0) {
                ActivitySection("Queued", activity.queued)
                queued.forEachIndexed { index, text -> Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text.ifBlank { "Attachment" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    IconButton(onClick = { onRemoveQueued(index) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, uiText("Remove queued message")) }
                } }
            }
            if (activity.recent.isNotEmpty()) {
                ActivitySection("Recent tools", activity.recent.size)
                activity.recent.forEach { tool -> ActivityRow(tool.name, toolOutcomeLabel(tool.outcome) + listOfNotNull(toolDurationLabel(tool.durationSeconds)).joinToString("") { " · $it" }, false) }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(uiText(ProcessControlUnavailable), Modifier.padding(horizontal = 20.dp).testTag("activity-process-unavailable"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ActivitySection(label: String, count: Int) {
    Text("${uiText(label)} · $count", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable private fun Clarification(vm: CypherViewModel, prompt: JSONObject, promptScope: Scope?, answers: Map<String, String>, onAnswer: (String, String) -> Unit) {
    val answer = answers[""].orEmpty()
    val questions = prompt.rows("questions")
    Glass { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(prompt.string("question", "One detail before continuing"), style = MaterialTheme.typography.titleMedium)
        if (questions.any { it.string("qid").isBlank() || it.string("question").isBlank() }) {
            Text("The question could not be read. Refresh before answering.", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { vm.selectSession(vm.chat.value.sessionId) }) { Text("Refresh question") }
        } else if (questions.isNotEmpty()) {
            questions.forEach { q -> val id = q.getString("qid"); Text(q.getString("question")); OutlinedTextField(answers[id].orEmpty(), { onAnswer(id, it) }, label = { Text("Your answer") }, modifier = Modifier.fillMaxWidth()); q.optJSONArray("choices")?.strings()?.forEach { choice -> TextButton(onClick = { onAnswer(id, choice) }) { Text(choice) } } }
            Button(onClick = { promptScope?.let { vm.clarifyBatch(JSONObject(questions.associate { question -> question.getString("qid") to answers[question.getString("qid")] }), prompt, it) } }, enabled = promptScope != null && questions.all { answers[it.string("qid")].orEmpty().isNotBlank() }) { Text("Continue") }
        } else {
            prompt.optJSONArray("choices")?.strings()?.forEach { choice -> OutlinedButton(onClick = { promptScope?.let { vm.clarify(choice, prompt, it) } }, enabled = promptScope != null) { Text(choice) } }
            OutlinedTextField(answer, { onAnswer("", it) }, label = { Text(uiText("Your answer")) }, modifier = Modifier.fillMaxWidth())
            Button(onClick = { promptScope?.let { vm.clarify(answer, prompt, it) } }, enabled = promptScope != null && answer.isNotBlank()) { Text(uiText("Continue")) }
        }
    } }
}

@Composable private fun CypherCallStage(vm: CypherViewModel, call: CallState, motionEnabled: Boolean, showHistory: Boolean, onHistory: (Boolean) -> Unit, modifier: Modifier, conversation: @Composable (Modifier) -> Unit) {
    val context=LocalContext.current
    var permissionCall by rememberSaveable { mutableStateOf("") }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected=permissionCall; permissionCall=""
        val current=vm.app.calls.state.value
        if(granted&&current.active&&current.callId==expected) vm.app.calls.camera(true)
        else if(!granted) vm.notice("Allow camera access to share your camera with Cypher.")
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
        title={Text("Switch to Gemini?")},
        text={Text("To ${if(confirmVision=="camera") "share your camera" else "share your screen"}, end this Grok call and reconnect with Gemini in the same conversation. Your saved voice preference stays unchanged.")},
        confirmButton={TextButton({ val media=confirmVision;confirmVision="";vm.switchCallForVision(call.callId,media) },Modifier.testTag("cypher-confirm-vision-switch")) { Text("Switch to Gemini") }},
        dismissButton={TextButton({confirmVision=""},Modifier.testTag("cypher-cancel-vision-switch")) { Text("Cancel") }})
    val actions=CallUiActions(vm.app.calls::mute,{
        if(!call.visionAvailable) confirmVision="camera" else camera()
    },vm.app.calls::flipCamera,{
        (context as? dev.hermes.android.CypherActivity)?.allowFloatingCall(call.callId)
    },vm.app.calls::hangup,vm.app.calls::route,{
        if(!call.visionAvailable) confirmVision="screen" else screen()
    })
    val stage: @Composable (Modifier) -> Unit = { area ->
        LiveCallSurface(call,actions,motionEnabled,area,onConversation={onHistory(!showHistory)}) { preview ->
            if (call.cameraSharing || call.cameraBusy) CameraPreview(vm.app.calls, call.callId, preview.testTag("xara-camera-preview"))
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().testTag("xara-call-stage")) {
        if(maxWidth>=700.dp) Row(Modifier.fillMaxSize()) {
            stage(Modifier.weight(1f));VerticalDivider();conversation(Modifier.width(320.dp).fillMaxHeight())
        } else if(showHistory) Column(Modifier.fillMaxSize()) {
            TextButton(onClick={onHistory(false)},modifier=Modifier.heightIn(min=48.dp).testTag("xara-conversation-toggle")) { Text(uiText("Return to call")) }
            conversation(Modifier.weight(1f)); LiveCallDock(call,actions,motionEnabled)
        } else stage(Modifier.fillMaxSize())
    }
}

private fun chatAction(vm: CypherViewModel, title: String) {
    if (!nativeConversationAction(title)) return vm.notice("$title is unavailable in native Hermes.")
    val chat = vm.chat.value
    val seed = json("session_id" to chat.sessionId)
    val result: suspend (JSONObject) -> Unit = { response -> vm.selectSession((response.optJSONObject("session") ?: response).string("session_id", chat.sessionId)); vm.refreshSessions() }
    when (title) {
        "New conversation" -> vm.newChat()
        "Rename" -> vm.openForm(ActionForm(title, "/api/session/rename", listOf(Field("title", "Conversation title", required = true)), seed.put("title", chat.title), onResult = result))
        "Model & provider", "Reasoning", "Toolsets" -> vm.openCatalog(title)
        "Archive" -> vm.sessionAction("/api/session/archive", json("archived" to !chat.raw.optBoolean("archived")))
        "Branch" -> vm.openForm(ActionForm(title, "/api/session/branch", listOf(Field("title", "Branch title"), Field("keep_count", "Messages to keep", FieldType.INTEGER)), seed.put("keep_count", chat.messages.size), onResult = result))
        "Compress context" -> vm.sessionAction("/api/session/compress") { response ->
            vm.notice(when (response.string("status")) { "compressed" -> "Context compressed"; "aborted" -> "Compression was not needed or was cancelled."; "pending", "lock_held" -> "Hermes is still working. Refresh the conversation to check progress."; else -> "Compression result is not confirmed. Refresh the conversation." })
        }
        "Undo last turn" -> vm.openForm(ActionForm(title, "/api/session/undo", emptyList(), seed, explanation = "Undo the latest conversation turn.", actionLabel = "Undo", onResult = result))
        "Usage" -> { vm.more("Session usage") }
        "Delete conversation" -> vm.openForm(ActionForm(title, "/api/session/delete", emptyList(), seed, explanation = "Delete this conversation from Cypher. This cannot be undone by the app.", actionLabel = "Delete", onResult = { vm.newChat(); vm.refreshSessions() }))
    }
}

private fun chatActionIcon(title: String): androidx.compose.ui.graphics.vector.ImageVector = when (title) {
    "New conversation" -> Icons.Rounded.Add
    "Rename" -> Icons.Rounded.Edit
    "Archive" -> Icons.Rounded.Archive
    "Unarchive" -> Icons.Rounded.Unarchive
    "Branch" -> Icons.Rounded.CallSplit
    "Model & provider", "Reasoning" -> Icons.Rounded.Tune
    "Toolsets" -> Icons.Rounded.Build
    "Compress context" -> Icons.Rounded.UnfoldLess
    "Undo last turn" -> Icons.Rounded.Undo
    "Usage" -> Icons.Rounded.DataUsage
    "Delete conversation" -> Icons.Rounded.DeleteOutline
    else -> Icons.Rounded.MoreHoriz
}

/** Voice and send share one footprint, so the control does not jump when the draft changes. */
internal val composerActionSize = 48.dp

internal fun attachmentPreparationLabel(name: String, status: String, error: String): String =
    "$name · " + if (status == "error") error.ifBlank { "Upload failed. Remove and attach again." } else "Preparing…"

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

@Composable private fun ChatStatusStrip(ui: AppState, chat: ChatState) {
    if (!ui.authenticated) return
    val backend = ui.bootstrap.string("backend")
    val gatewayKnown = backend.contains("hermes", ignoreCase = true)
    val route = ui.origin.substringBefore("://", "").uppercase().takeIf { it == "HTTP" || it == "HTTPS" }
    val model = chat.raw.string("model").trim().takeIf(String::isNotEmpty)
    val profile = ui.profile.trim().takeIf(String::isNotEmpty)
    val trailing = listOfNotNull(model, profile).joinToString(" / ")
    if (!gatewayKnown && route == null && trailing.isBlank()) return
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp).testTag("chat-status-strip"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (gatewayKnown) {
                Icon(Icons.Rounded.Bolt, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Gateway", Modifier.padding(start = 4.dp), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            if (route == "HTTPS") Icon(Icons.Rounded.Lock, uiText("Secure connection"), Modifier.padding(start = 5.dp).size(12.dp), tint = MaterialTheme.colorScheme.primary)
            route?.let { Text("\u00B7 $it", Modifier.padding(start = 4.dp), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            if (trailing.isNotBlank()) {
                Spacer(Modifier.weight(1f))
                Text(trailing, Modifier.testTag("chat-status-model-profile"), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
    }
}
