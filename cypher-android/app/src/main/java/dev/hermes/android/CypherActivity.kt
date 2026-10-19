@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android

import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.provider.Settings
import android.net.Uri
import dev.hermes.android.voice.CypherCallService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.data.NativeAuth
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

internal data class SafeExternalConnectLink(val link: String, val sessionRemoved: Boolean)

internal fun sanitizeExternalConnectLink(uri: Uri): SafeExternalConnectLink {
    val parameters = uri.queryParameterNames
    val sessionRemoved = parameters.any { name ->
        name.equals("session", ignoreCase = true) && uri.getQueryParameters(name).any(String::isNotBlank)
    }
    val safeUri = uri.buildUpon().clearQuery()
    parameters.filterNot { it.equals("session", ignoreCase = true) }.forEach { name ->
        uri.getQueryParameters(name).forEach { value -> safeUri.appendQueryParameter(name, value) }
    }
    return SafeExternalConnectLink(safeUri.build().toString(), sessionRemoved)
}

class CypherActivity : ComponentActivity() {
    private var vm: CypherViewModel? = null
    private var initialPush by mutableStateOf<Triple<String, String, String>?>(null)
    private var updateToken by mutableStateOf<String?>(null)
    private var pendingConnectionLink by mutableStateOf<String?>(null)
    private var pendingConnectionSessionNotice by mutableStateOf(false)
    private var externalConnectionLinkOrigin by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The call-id extra is stripped once read, so a recreation before the call view opens must keep the pending return.
        pendingReturn = savedInstanceState?.getString("pending_return").orEmpty()
        push(intent)
        setContent {
            val model: CypherViewModel = viewModel()
            vm = model
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val data by model.data.collectAsStateWithLifecycle()
            val ui by model.ui.collectAsStateWithLifecycle()
            val pendingConnectionOrigin = remember(pendingConnectionLink) {
                pendingConnectionLink?.let { runCatching { parseCypherConnectLink(it).origin }.getOrNull() }
            }
            LaunchedEffect(model, pendingConnectionLink, pendingConnectionSessionNotice, ui.restoringSession, ui.setupComplete) {
                val link = pendingConnectionLink ?: return@LaunchedEffect
                if (ui.restoringSession) return@LaunchedEffect
                val origin = pendingConnectionOrigin
                if (origin == null) {
                    model.notice("This Cypher connection link is invalid.")
                    pendingConnectionLink = null
                    pendingConnectionSessionNotice = false
                } else if (!ui.setupComplete) {
                    externalConnectionLinkOrigin = origin
                    model.openSetupLink(link)
                    if (pendingConnectionSessionNotice) model.notice("For this dashboard, scan the QR code with Cypher's scanner to sign in.")
                    pendingConnectionLink = null
                    pendingConnectionSessionNotice = false
                }
            }
            var permissionSource by rememberSaveable { mutableStateOf("") }
            var permissionEvent by rememberSaveable { mutableStateOf("") }
            var permissionAction by rememberSaveable { mutableStateOf("answer") }
            val answerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                val source = permissionSource; val event = permissionEvent
                permissionSource = ""; permissionEvent = ""
                if (allowed && source.isNotBlank() && event.isNotBlank()) model.handlePush(source, event, permissionAction)
                else if (!allowed) model.notice("Allow the microphone to answer.")
            }
            val callFromNotification: (String, String, String) -> Unit = { source, event, action ->
                if (permissionSource.isBlank()) {
                    permissionSource = source; permissionEvent = event; permissionAction = action
                    answerPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            val answer: (String,String) -> Unit = { source,event -> callFromNotification(source,event,"answer") }
            LaunchedEffect(ui.authenticated, ui.connected, ui.loading, updateToken) {
                if (ui.authenticated && ui.connected && !ui.loading && model.client != null) updateToken?.let { token -> updateToken = null; intent?.removeExtra(dev.hermes.android.updates.UpdateManager.EXTRA_TOKEN); model.downloadAppUpdate(token) }
            }
            val call by (application as CypherApplication).calls.state.collectAsStateWithLifecycle()
            LaunchedEffect(ui.authenticated, ui.connected, ui.loading, ui.profile, pendingReturn, call.callId, call.active) {
                if(pendingReturn.isNotBlank()&&ui.authenticated&&!ui.loading&&call.active&&call.callId==pendingReturn&&model.client?.scope?.let { actor ->
                    call.conversationScope?.let { it.origin==actor.origin&&it.owner==actor.owner } }==true) {
                    val id=pendingReturn;pendingReturn="";model.openCurrentCall(id)
                }
            }
            LaunchedEffect(model, initialPush) { initialPush?.let { push -> initialPush = null; if (push.third in setOf("answer","callback")) callFromNotification(push.first, push.second, push.third) else model.handlePush(push.first, push.second) } }
            val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            val emptySettings = remember { org.json.JSONObject() }
            CompositionLocalProvider(LocalCypherSettings provides (data["profile-settings"] ?: emptySettings), LocalAssistantName provides preferences.assistantName) { NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) { CypherTheme(dark, preferences.skin) { NativeWindowLayout(this@CypherActivity) {
                CypherRoot(model, externalConnectionLinkOrigin) { externalConnectionLinkOrigin = null }
                IncomingCallSheet(model, permissionSource.isNotBlank(), answer)
                if (pendingConnectionLink != null && pendingConnectionOrigin != null && !ui.restoringSession && ui.setupComplete) {
                    val cancelConnectionLink = {
                        pendingConnectionLink = null
                        pendingConnectionSessionNotice = false
                    }
                    AlertDialog(
                        onDismissRequest = cancelConnectionLink,
                        title = { Text("Connect to $pendingConnectionOrigin?") },
                        text = { Text("This link will open setup for the named Hermes server. Review the address before continuing.") },
                        confirmButton = {
                            TextButton(onClick = {
                                val link = pendingConnectionLink
                                val origin = pendingConnectionOrigin
                                if (link != null && origin != null) {
                                    externalConnectionLinkOrigin = origin
                                    model.openSetupLink(link)
                                    if (pendingConnectionSessionNotice) model.notice("For this dashboard, scan the QR code with Cypher's scanner to sign in.")
                                }
                                cancelConnectionLink()
                            }) { Text("Connect") }
                        },
                        dismissButton = { TextButton(onClick = cancelConnectionLink) { Text("Cancel") } },
                    )
                }
            } } } }
        }
    }
    private var pendingReturn by mutableStateOf("")
    fun allowFloatingCall(expectedId: String) {
        val calls=(application as CypherApplication).calls
        if (!calls.state.value.active || calls.state.value.callId != expectedId) return
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { vm?.notice("Floating calls aren't available; use the call notification.") }
    }
    override fun onStart() { super.onStart(); (application as CypherApplication).calls.let { it.uiVisible = true; if (it.state.value.active) it.resumeWindow() }; vm?.foreground(true) }
    override fun onResume() { super.onResume(); NativeAuth.onActivityResumed(); vm?.resumeAppUpdate(); (application as CypherApplication).calls.refreshOverlayPermission() }
    override fun onStop() { (application as CypherApplication).calls.uiVisible = false; vm?.foreground(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("pending_return", pendingReturn) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); push(intent) }
    private fun push(intent: Intent?) {
        intent?.data?.takeIf { it.scheme.equals("cypher", true) && it.host.equals("connect", true) }?.let {
            val safe = sanitizeExternalConnectLink(it)
            pendingConnectionLink = safe.link
            pendingConnectionSessionNotice = safe.sessionRemoved
            intent?.data = null
        }
        intent?.getStringExtra(CypherCallService.EXTRA_CALL_ID)?.let { pendingReturn=it;intent.removeExtra(CypherCallService.EXTRA_CALL_ID) }
        intent?.getStringExtra(dev.hermes.android.updates.UpdateManager.EXTRA_TOKEN)?.takeIf(dev.hermes.android.updates.UpdateManager::validToken)?.let { updateToken = it }
        val source = intent?.getStringExtra("push_source") ?: intent?.getStringExtra("source_ref").orEmpty()
        val event = intent?.getStringExtra("push_event") ?: intent?.getStringExtra("event_id").orEmpty()
        if (dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) {
            val invite = (application as CypherApplication).push.ringingInvitation(source, event)
            val answer = invite != null && intent?.action == invite.answerAction
            val missed = (application as CypherApplication).push.missedInvitation(source,event)
            val callback = missed != null && intent?.action == missed.callbackAction
            initialPush = Triple(source, event, if (answer) "answer" else if(callback) "callback" else "")
            intent?.removeExtra("push_source"); intent?.removeExtra("push_event"); intent?.removeExtra("source_ref"); intent?.removeExtra("event_id")
        }
    }
}
@Composable
internal fun NativeFeedbackEffect(
    error: String,
    notice: String,
    setupWizard: Boolean,
    snackbar: SnackbarHostState,
    clearMessage: () -> Unit,
) {
    val message = rememberUpdatedState(if (setupWizard) "" else error.ifBlank { notice })
    val wizardOpen = rememberUpdatedState(setupWizard)
    val clear = rememberUpdatedState(clearMessage)
    LaunchedEffect(snackbar) {
        var wasWizardOpen = wizardOpen.value
        snapshotFlow { wizardOpen.value to message.value }.distinctUntilChanged().collectLatest { (inWizard, text) ->
            if (wasWizardOpen && !inWizard) {
                wasWizardOpen = false
                clear.value()
                return@collectLatest
            }
            if (inWizard) wasWizardOpen = true
            if (text.isNotBlank()) {
                snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long)
                clear.value()
            }
        }
    }
}

@Composable internal fun IncomingCallSheet(vm: CypherViewModel, requestingPermission: Boolean, onAnswer: (String, String) -> Unit) {
    val invite by vm.app.push.incoming.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val busy by vm.incomingBusy.collectAsStateWithLifecycle()
    var remaining by remember(invite) { mutableLongStateOf(invite?.ringDurationMillis() ?: 0) }
    LaunchedEffect(invite) {
        val pending = invite ?: return@LaunchedEffect
        remaining = pending.ringDurationMillis()
        while (remaining > 0) { kotlinx.coroutines.delay(minOf(remaining, 250)); remaining = pending.ringDurationMillis() }
        vm.app.push.expireInvitation(pending.source, pending.event)
    }
    val pending = invite?.takeIf { remaining > 0 && vm.app.push.permitsIncoming(it.source) }
    if (pending != null && ui.authenticated && !call.active) {
        ModalBottomSheet(onDismissRequest = { vm.app.push.dismissInvitation(pending.source, pending.event) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("cypher-incoming-sheet"), sheetMaxWidth = 560.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text(assistantText("Cypher is calling"), style = MaterialTheme.typography.headlineSmall)
                }
                Text(uiText("Your microphone stays off until you answer."), style = MaterialTheme.typography.bodyLarge)
                Text(uiText("The result stays in Cypher history if you miss or decline this invitation."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                ui.error.ifBlank { call.error }.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { onAnswer(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cypher-incoming-answer")) { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text(uiText(when { requestingPermission -> "Waiting for microphone permission"; busy -> "Please wait"; else -> "Answer" })) }
                OutlinedButton(onClick = { vm.declineIncoming(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("cypher-incoming-decline")) { Icon(Icons.Rounded.CallEnd, null); Spacer(Modifier.width(8.dp)); Text(uiText("Decline")) }
            }
        }
    }
}

@Composable fun CypherTheme(dark: Boolean = isSystemInDarkTheme(), skin: String = "default", content: @Composable () -> Unit) {
    NativeSkinTheme(dark, skin, content)
}

@Composable internal fun CypherRoot(vm: CypherViewModel, externalLinkOrigin: String? = null, clearExternalLinkOrigin: () -> Unit = {}) {
    val hostActivity = LocalActivity.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val botMode by vm.botMode.state.collectAsStateWithLifecycle()
    val botChatVisible = ui.tab == "More" && ui.morePage == "Bots" && botMode.activeBot != null && botMode.sessionId == vm.authenticatedScope()?.session
    val motionEnabled = rememberNativeMotionEnabled()
    val modeEntry = remember { Animatable(1f) }
    val sidebarState = rememberSaveableStateHolder()
    LaunchedEffect(ui.botModeEnabled, motionEnabled) {
        if (motionEnabled) { modeEntry.snapTo(.5f); modeEntry.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        else modeEntry.snapTo(1f)
    }
    val modeMotion = Modifier.graphicsLayer { alpha = modeEntry.value }
    val latestMotionEnabled by rememberUpdatedState(motionEnabled)
    val form by vm.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var composerHeightPx by remember { mutableIntStateOf(0) }
    var feedbackHeightPx by remember { mutableIntStateOf(0) }
    val feedbackHost: @Composable () -> Unit = {
        SnackbarHost(snackbar, Modifier.testTag("native-feedback").onSizeChanged { feedbackHeightPx = it.height }) { data ->
            Snackbar(data,
                shape = MaterialTheme.shapes.large, containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = MaterialTheme.colorScheme.onSurface, actionColor = MaterialTheme.colorScheme.primary,
                dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    BackHandler(ui.authenticated && !ui.showSessions && ui.tab !in setOf("Chat", "Cypher") && (ui.tab != "More" || ui.morePage.isBlank())) {
        if (ui.tab == "More") vm.backFromSettings() else if (ui.tab == "Spaces" && vm.editor.value.path.isNotBlank()) vm.closeEditor() else if (ui.botModeEnabled) vm.showBotMode() else vm.tab("Chat")
    }
    NativeFeedbackEffect(ui.error, ui.notice, ui.setupWizard, snackbar, vm::clearMessage)
    if (ui.restoringSession) {
        Surface(Modifier.fillMaxSize().testTag("session-restore-logo"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_cypher), null, Modifier.size(64.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Spacer(Modifier.height(16.dp))
                Text("Cypher", style = MaterialTheme.typography.displaySmall)
            }
        }
    } else if (!ui.authenticated || ui.setupWizard) {
        SetupWizard(vm, Modifier.fillMaxSize().safeDrawingPadding(), externalLinkOrigin, clearExternalLinkOrigin)
    } else {
        val keyboard = LocalSoftwareKeyboardController.current
        val openMenu = { keyboard?.hide(); vm.toggleSessions(!ui.showSessions) }
        val drawer = rememberDrawerState(if (ui.showSessions) DrawerValue.Open else DrawerValue.Closed) { target ->
            val open = target == DrawerValue.Open
            if (!latestMotionEnabled && open != vm.ui.value.showSessions) {
                // Route Material's scrim/semantic dismissal through the same instant transition.
                vm.toggleSessions(open)
                false
            } else true
        }
        val drawerRequest = remember(ui.showSessions, motionEnabled) { Any() to ui.showSessions }
        val latestDrawerRequest by rememberUpdatedState(drawerRequest)
        var runningDrawerRequest by remember { mutableStateOf<Pair<Any, Boolean>?>(null) }
        var completedDrawerRequest by remember { mutableStateOf<Pair<Any, Boolean>?>(null) }
        LaunchedEffect(drawerRequest) {
            runningDrawerRequest = drawerRequest
            try {
                if (!motionEnabled) drawer.snapTo(if (drawerRequest.second) DrawerValue.Open else DrawerValue.Closed)
                else if (drawerRequest.second) drawer.open() else drawer.close()
            }
            finally {
                if (runningDrawerRequest === drawerRequest) {
                    runningDrawerRequest = null
                    if (latestDrawerRequest === drawerRequest) completedDrawerRequest = drawerRequest
                }
            }
        }
        LaunchedEffect(drawer) {
            // Mirror settled native gestures, never an older programmatic animation.
            snapshotFlow {
                val request = latestDrawerRequest
                if (runningDrawerRequest == null && completedDrawerRequest === request && !drawer.isAnimationRunning && drawer.currentValue == drawer.targetValue) request to (drawer.currentValue == DrawerValue.Open) else null
            }.collect { settlement ->
                if (settlement != null) {
                    val (request, open) = settlement
                    // VM intent can change before Compose publishes its next request token.
                    if (latestDrawerRequest === request && runningDrawerRequest == null && completedDrawerRequest === request && vm.ui.value.showSessions == request.second && !drawer.isAnimationRunning && drawer.currentValue == drawer.targetValue && (drawer.currentValue == DrawerValue.Open) == open) vm.toggleSessions(open)
                }
            }
        }
        val swipeCloseDistance = with(LocalDensity.current) { 90.dp.toPx() }
        val drawerDirection = if (LocalLayoutDirection.current == LayoutDirection.Rtl) 1 else -1
        AdaptiveCallNavigation(drawer, ui.showSessions, navigation = { persistent ->
                val swipe = if (persistent) Modifier.pointerInput(swipeCloseDistance, drawerDirection) {
                    var distance = 0f
                    detectHorizontalDragGestures(onDragStart = { distance = 0f }, onDragEnd = { if (distance * drawerDirection > swipeCloseDistance) vm.toggleSessions(false) }) { change, amount -> distance += amount; change.consume() }
                } else Modifier
                val sidebarKey = "${ui.origin}:${ui.owner}:" + if (ui.botModeEnabled) "bots" else "normal:${ui.profile}"
                sidebarState.SaveableStateProvider(sidebarKey) {
                    val navigationModifier = Modifier.fillMaxSize().then(swipe).then(modeMotion)
                    if (ui.botModeEnabled) NativeBotModeSidebar(vm, navigationModifier, beforeRoster = { SidebarNavigation(vm) })
                    else SessionList(vm, navigationModifier.testTag("navigation-menu"), beforeHistory = { SidebarNavigation(vm) })
                }
        }) {
            val hasComposer = ui.tab in setOf("Chat", "Cypher") && !(call.active && call.conversationScope == vm.authenticatedScope())
            val snackbarBottom = with(LocalDensity.current) { if (hasComposer) composerHeightPx.toDp() else 0.dp }
            val feedbackReserve = with(LocalDensity.current) { if (hasComposer) 0.dp else feedbackHeightPx.toDp() }
            Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = { Box(Modifier.padding(bottom = snackbarBottom)) { feedbackHost() } }) { padding ->
                // The shell owns system/IME space; descendants receive already-consumed insets.
                CompositionLocalProvider(LocalOpenNavigationMenu provides openMenu) {
                    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(nativeAmbientColors(MaterialTheme.colorScheme))).padding(padding).padding(bottom = feedbackReserve).consumeWindowInsets(padding)) {
                        if (!ui.connected) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(if (ui.loading) "Opening saved history · checking connection" else "Saved history · offline", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = !ui.loading, onClick = { vm.connect(ui.origin) }) { Text(uiText("Reconnect")) }
                            }
                        }
                        if (call.active && (ui.tab !in setOf("Chat","Cypher") && !botChatVisible || call.conversationScope != vm.authenticatedScope())) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("${LocalAssistantName.current} · ${call.state}", Modifier.weight(1f)); TextButton(onClick = { vm.openCurrentCall() }) { Text(uiText("Open call")) }; IconButton(onClick = vm.app.calls::hangup) { Icon(Icons.Rounded.CallEnd, assistantText("Hang up Cypher")) } } }
                        Box(Modifier.weight(1f).then(modeMotion)) {
                        if (vm.client == null && !ui.loading && ui.tab in setOf("Tasks", "Spaces", "Projects", "Kanban")) {
                            Header(when(ui.tab) { "Tasks" -> "Cron"; "Spaces" -> "Files"; else -> ui.tab }, menu = openMenu)
                            EmptyState("Reconnect to Hermes", "Keep Tailscale connected to open this page. Your drafts remain saved on this phone.", action = "Reconnect", onAction = { vm.connect(ui.origin) })
                        } else when (ui.tab) { "Cypher" -> ChatScreen(vm, cypher = true, onComposerHeight = { composerHeightPx = it }); "Chat" -> ChatScreen(vm, onComposerHeight = { composerHeightPx = it }); "Tasks" -> TasksScreen(vm); "Spaces" -> SpacesScreen(vm); "Projects" -> NativeProjectsScreen(vm); "Kanban" -> KanbanScreen(vm); else -> MoreScreen(vm) }
                        }
                    }
                }
            }
        }
    }
    // Register after destination Back handlers so an open drawer always closes first.
    BackHandler(!ui.showSessions && ui.tab == "More" && ui.morePage.isNotBlank() && ui.morePage !in setOf("Skills", "Memory", "Bots", "Profiles")) { vm.backFromSettings() }
    BackHandler(ui.showSessions) { vm.toggleSessions(false) }
    BackHandler(ui.setupWizard && !ui.restoringSession) {
        if (ui.authenticated) vm.cancelSetupWizard() else hostActivity?.finish()
    }
    form?.let { NativeForm(vm, it) }
    val approval=call.toolApprovals.firstOrNull()?.takeIf { ui.authenticated && call.active && call.conversationScope?.let { s -> s.owner==ui.owner&&s.profile==ui.profile&&s.origin==ui.origin }==true }
    if(approval!=null&&form==null) {
        val decide: (Boolean) -> Unit = { allow -> vm.app.calls.decideTool(call.callId,approval.requestId,approval.proposalId,allow) }
        AlertDialog(onDismissRequest={if(!approval.busy) decide(false)},
            icon={Icon(Icons.Rounded.VerifiedUser,null)}, title={Text("Allow this action?")},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(approval.toolName.substringAfterLast("__").replace('_',' ').replaceFirstChar { it.titlecase() },style=MaterialTheme.typography.titleMedium)
                if(approval.toolName.startsWith("mcp__")) Text("Connection: "+approval.toolName.removePrefix("mcp__").substringBefore("__").replace('_',' '),style=MaterialTheme.typography.bodySmall)
                Text(assistantText("Review the details before Cypher runs this action."))
                androidx.compose.foundation.text.selection.SelectionContainer { Text(approval.arguments,style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)) }
                if(approval.error.isNotBlank()) Text(approval.error,color=MaterialTheme.colorScheme.error)
                if(approval.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }},
            confirmButton={TextButton(onClick={decide(true)},enabled=!approval.busy,modifier=Modifier.testTag("cypher-tool-allow")) {Text("Allow once")}},
            dismissButton={TextButton(onClick={decide(false)},enabled=!approval.busy,modifier=Modifier.testTag("cypher-tool-deny")) {Text("Deny")}},
            modifier=Modifier.testTag("cypher-tool-approval"))
    }
}
