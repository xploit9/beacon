@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android

import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.provider.Settings
import android.net.Uri
import dev.hermes.android.voice.BeaconCallService
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
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.callToolApprovalErrorMessage
import dev.hermes.android.voice.callToolApprovalSummary
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

/** An outside link keeps only its address: a `session` value (an old dashboard secret) is dropped before the link is held. */
internal fun sanitizeExternalConnectLink(uri: Uri): String {
    val safeUri = uri.buildUpon().clearQuery()
    uri.queryParameterNames.filterNot { it.equals("session", ignoreCase = true) }.forEach { name ->
        uri.getQueryParameters(name).forEach { value -> safeUri.appendQueryParameter(name, value) }
    }
    return safeUri.build().toString()
}

class BeaconActivity : ComponentActivity() {
    private var vm: BeaconViewModel? = null
    private var initialPush by mutableStateOf<Triple<String, String, String>?>(null)
    private var updateToken by mutableStateOf<String?>(null)
    private var pendingConnectionLink by mutableStateOf<String?>(null)
    private var externalConnectionLinkOrigin by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The call-id extra is stripped once read, so a recreation before the call view opens must keep the pending return.
        pendingReturn = savedInstanceState?.getString("pending_return").orEmpty()
        push(intent)
        setContent {
            val model: BeaconViewModel = viewModel()
            vm = model
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val data by model.data.collectAsStateWithLifecycle()
            val ui by model.ui.collectAsStateWithLifecycle()
            val pendingConnectionOrigin = remember(pendingConnectionLink) {
                pendingConnectionLink?.let { runCatching { parseBeaconConnectLink(it).origin }.getOrNull() }
            }
            val hasKnownOrigin = ui.setupOrigin.isNotBlank() || ui.origin.isNotBlank()
            LaunchedEffect(model, pendingConnectionLink, ui.restoringSession, ui.origin, ui.setupOrigin) {
                val link = pendingConnectionLink ?: return@LaunchedEffect
                if (ui.restoringSession) return@LaunchedEffect
                val origin = pendingConnectionOrigin
                if (origin == null) {
                    model.notice("This Beacon connection link is invalid.")
                    pendingConnectionLink = null
                } else if (!hasKnownOrigin) {
                    externalConnectionLinkOrigin = origin
                    model.openSetupLink(link, confirmed = false)
                    pendingConnectionLink = null
                }
            }
            var permissionSource by rememberSaveable { mutableStateOf("") }
            var permissionEvent by rememberSaveable { mutableStateOf("") }
            var permissionAction by rememberSaveable { mutableStateOf("answer") }
            val callPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                val source = permissionSource; val event = permissionEvent
                val action = permissionAction
                permissionSource = ""; permissionEvent = ""
                permissionAction = "answer"
                if (allowed && source.isNotBlank() && event.isNotBlank()) model.handlePush(source, event, action)
                else if (!allowed) model.notice(if (action == "callback") "Allow microphone access to call back." else "Allow the microphone to answer.")
            }
            val callFromNotification: (String, String, String) -> Unit = { source, event, action ->
                if (permissionSource.isBlank()) {
                    permissionSource = source; permissionEvent = event; permissionAction = action
                    callPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            val answer: (String,String) -> Unit = { source,event -> callFromNotification(source,event,"answer") }
            // The update works without Hermes or a sign-in, so the tap only waits for the app to finish starting.
            LaunchedEffect(ui.loading, ui.restoringSession, updateToken) {
                if (!ui.loading && !ui.restoringSession) updateToken?.let { token -> updateToken = null; intent?.removeExtra(dev.hermes.android.updates.UpdateManager.EXTRA_TOKEN); model.downloadAppUpdate(token) }
            }
            val call by (application as BeaconApplication).calls.state.collectAsStateWithLifecycle()
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
            CompositionLocalProvider(LocalBeaconSettings provides (data["profile-settings"] ?: emptySettings), LocalAssistantName provides preferences.assistantName) { NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) { BeaconTheme(dark, preferences.skin) { NativeWindowLayout(this@BeaconActivity) {
                BeaconRoot(model, externalConnectionLinkOrigin) { externalConnectionLinkOrigin = null }
                IncomingCallSheet(model, permissionSource.isNotBlank(), answer)
                dev.hermes.android.updates.AppUpdateHost(model.app.updates)
                if (pendingConnectionLink != null && pendingConnectionOrigin != null && !ui.restoringSession && hasKnownOrigin) {
                    val cancelConnectionLink = {
                        pendingConnectionLink = null
                    }
                    AlertDialog(
                        onDismissRequest = cancelConnectionLink,
                        title = { Text("Connect to $pendingConnectionOrigin?") },
                        confirmButton = {
                            TextButton(onClick = {
                                val link = pendingConnectionLink
                                val origin = pendingConnectionOrigin
                                if (link != null && origin != null) {
                                    externalConnectionLinkOrigin = origin
                                    model.openSetupLink(link, confirmed = true)
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
        val calls=(application as BeaconApplication).calls
        if (!calls.state.value.active || calls.state.value.callId != expectedId) return
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { vm?.notice("Floating calls aren't available; use the call notification.") }
    }
    override fun onStart() { super.onStart(); (application as BeaconApplication).calls.let { it.uiVisible = true; if (it.state.value.active) it.resumeWindow() }; vm?.foreground(true) }
    override fun onResume() { super.onResume(); NativeAuth.onActivityResumed(); (application as BeaconApplication).calls.refreshOverlayPermission() }
    override fun onStop() { (application as BeaconApplication).calls.uiVisible = false; vm?.foreground(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("pending_return", pendingReturn) }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); push(intent) }
    private fun push(intent: Intent?) {
        intent?.data?.takeIf { it.scheme.equals("beacon", true) && it.host.equals("connect", true) }?.let {
            pendingConnectionLink = sanitizeExternalConnectLink(it)
            intent?.data = null
        }
        intent?.getStringExtra(BeaconCallService.EXTRA_CALL_ID)?.let { pendingReturn=it;intent.removeExtra(BeaconCallService.EXTRA_CALL_ID) }
        intent?.getStringExtra(dev.hermes.android.updates.UpdateManager.EXTRA_TOKEN)?.takeIf(dev.hermes.android.updates.UpdateManager::validToken)?.let { updateToken = it }
        val source = intent?.getStringExtra("push_source") ?: intent?.getStringExtra("source_ref").orEmpty()
        val event = intent?.getStringExtra("push_event") ?: intent?.getStringExtra("event_id").orEmpty()
        if (dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) {
            val invite = (application as BeaconApplication).push.ringingInvitation(source, event)
            val answer = invite != null && intent?.action == invite.answerAction
            val callbackInvite = (application as BeaconApplication).push.deadlineInvitation(source,event)
            val callback = dev.hermes.android.voice.isMissedCallbackAction(intent?.action, callbackInvite)
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
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    clearMessage: () -> Unit,
) {
    val message = rememberUpdatedState(if (setupWizard) "" else error.ifBlank { notice })
    val wizardOpen = rememberUpdatedState(setupWizard)
    val clear = rememberUpdatedState(clearMessage)
    val label = rememberUpdatedState(actionLabel)
    val action = rememberUpdatedState(onAction)
    LaunchedEffect(snackbar) {
        var wasWizardOpen = wizardOpen.value
        snapshotFlow { Triple(wizardOpen.value, message.value, label.value) }.distinctUntilChanged().collectLatest { (inWizard, text, button) ->
            if (wasWizardOpen && !inWizard) {
                wasWizardOpen = false
                clear.value()
                return@collectLatest
            }
            if (inWizard) wasWizardOpen = true
            if (text.isNotBlank()) {
                val result = snackbar.showSnackbar(text, actionLabel = button, withDismissAction = true, duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) action.value() else clear.value()
            }
        }
    }
}

@Composable internal fun IncomingCallSheet(vm: BeaconViewModel, requestingPermission: Boolean, onAnswer: (String, String) -> Unit) {
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
        ModalBottomSheet(onDismissRequest = { vm.app.push.dismissInvitation(pending.source, pending.event) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("beacon-incoming-sheet"), sheetMaxWidth = 560.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text(assistantText("Beacon is calling"), style = MaterialTheme.typography.headlineSmall)
                }
                ui.error.ifBlank { call.error }.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { onAnswer(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-incoming-answer")) { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text(uiText(when { requestingPermission -> "Waiting for microphone permission"; busy -> "Please wait"; else -> "Answer" })) }
                OutlinedButton(onClick = { vm.declineIncoming(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-incoming-decline")) { Icon(Icons.Rounded.CallEnd, null); Spacer(Modifier.width(8.dp)); Text(uiText("Decline")) }
            }
        }
    }
}

@Composable fun BeaconTheme(dark: Boolean = isSystemInDarkTheme(), skin: String = "default", content: @Composable () -> Unit) {
    NativeSkinTheme(dark, skin, content)
}

@Composable internal fun BeaconRoot(vm: BeaconViewModel, externalLinkOrigin: String? = null, clearExternalLinkOrigin: () -> Unit = {}) {
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
    BackHandler(ui.authenticated && !ui.showSessions && ui.tab !in setOf("Chat", "Beacon") && (ui.tab != "More" || ui.morePage.isBlank())) {
        if (ui.tab == "More") vm.backFromSettings() else if (ui.tab == "Spaces" && vm.editor.value.path.isNotBlank()) vm.closeEditor() else if (ui.botModeEnabled) vm.showBotMode() else vm.tab("Chat")
    }
    val archiveUndo by vm.archiveUndo.collectAsStateWithLifecycle()
    NativeFeedbackEffect(ui.error, ui.notice, ui.setupWizard, snackbar,
        actionLabel = "Undo".takeIf { archiveUndo != null && ui.error.isBlank() && ui.notice == "Archived" }, onAction = vm::undoArchive, clearMessage = vm::clearMessage)
    // The wizard stays composed while it connects after sign-in: swapping in the logo would drop what it has typed.
    if (ui.restoringSession && !ui.setupWizard) {
        Surface(Modifier.fillMaxSize().testTag("session-restore-logo"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_beacon_logo), null, Modifier.size(64.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Spacer(Modifier.height(16.dp))
                Text(uiText("Beacon"), style = MaterialTheme.typography.displaySmall)
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
        val hasComposer = ui.tab in setOf("Chat", "Beacon") && !(call.active && call.conversationScope == vm.authenticatedScope())
        val snackbarBottom = with(LocalDensity.current) { if (hasComposer) composerHeightPx.toDp() else 0.dp }
        val feedbackReserve = with(LocalDensity.current) { if (hasComposer) 0.dp else feedbackHeightPx.toDp() }
        Box(Modifier.fillMaxSize()) {
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
            Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets.safeDrawing) { padding ->
                // The shell owns system/IME space; descendants receive already-consumed insets.
                CompositionLocalProvider(LocalOpenNavigationMenu provides openMenu) {
                    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.linearGradient(nativeAmbientColors(MaterialTheme.colorScheme))).padding(padding).padding(bottom = feedbackReserve).consumeWindowInsets(padding)) {
                        if (!ui.connected) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(if (ui.loading) "Opening saved history · checking connection" else "Saved history · offline", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = !ui.loading, onClick = { vm.connect(ui.origin) }) { Text(uiText("Reconnect")) }
                            }
                        }
                        if (call.active && (ui.tab !in setOf("Chat","Beacon") && !botChatVisible || call.conversationScope != vm.authenticatedScope())) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("${LocalAssistantName.current} · ${call.state}", Modifier.weight(1f)); TextButton(onClick = { vm.openCurrentCall() }) { Text(uiText("Open call")) }; IconButton(onClick = vm.app.calls::hangup) { Icon(Icons.Rounded.CallEnd, assistantText("Hang up Beacon")) } } }
                        Box(Modifier.weight(1f).then(modeMotion)) {
                        if (vm.client == null && !ui.loading && ui.tab in setOf("Tasks", "Spaces", "Projects", "Kanban")) {
                            Header(when(ui.tab) { "Tasks" -> "Cron"; "Spaces" -> "Files"; else -> ui.tab }, menu = openMenu)
                            EmptyState("Reconnect to Hermes", "Keep Tailscale connected to open this page. Your drafts remain saved on this phone.", action = "Reconnect", onAction = { vm.connect(ui.origin) })
                        } else when (ui.tab) { "Beacon" -> ChatScreen(vm, beacon = true, onComposerHeight = { composerHeightPx = it }); "Chat" -> ChatScreen(vm, onComposerHeight = { composerHeightPx = it }); "Tasks" -> TasksScreen(vm); "Spaces" -> SpacesScreen(vm); "Projects" -> NativeProjectsScreen(vm); "Kanban" -> KanbanScreen(vm); else -> MoreScreen(vm) }
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding().padding(bottom = snackbarBottom), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { feedbackHost() }
        }
    }
    // Register after destination Back handlers so an open drawer always closes first.
    // Voice & calls steps out of its Key and Provider pages first (its own handler); every other page leaves Settings.
    val voicePage by vm.voicePage.collectAsStateWithLifecycle()
    BackHandler(!ui.showSessions && ui.tab == "More" && ui.morePage.isNotBlank() && ui.morePage !in setOf("Skills", "Memory", "Bots", "Profiles") && !(ui.morePage == voiceKeySetupPage() && voicePage != "home")) { vm.backFromSettings() }
    BackHandler(ui.showSessions) { vm.toggleSessions(false) }
    BackHandler(ui.setupWizard && !ui.restoringSession) {
        // Back on the Key and Make-it-yours pages returns to the provider page, like their Back button.
        if (ui.setupStep in setOf("voice-key", "voice-customize")) vm.showVoiceSetupPage("voice-provider")
        else if (ui.authenticated) vm.cancelSetupWizard() else hostActivity?.finish()
    }
    form?.let { NativeForm(vm, it) }
    val approval=call.toolApprovals.firstOrNull()?.takeIf { ui.authenticated && call.active && call.conversationScope?.let { s -> s.owner==ui.owner&&s.profile==ui.profile&&s.origin==ui.origin }==true }
    if(approval!=null&&form==null) {
        CallToolApprovalDialog(approval, onDecision = { decision ->
            vm.app.calls.decideTool(call.callId,approval.requestId,approval.proposalId,decision)
        })
    }
}

@Composable
internal fun CallToolApprovalDialog(approval: CallToolApproval, onDecision: (String) -> Unit) {
    var details by rememberSaveable(approval.requestId) { mutableStateOf(false) }
    val summary = remember(approval.toolName, approval.arguments) { callToolApprovalSummary(approval) }
    AlertDialog(
        onDismissRequest = { if (!approval.busy) onDecision("deny") },
        icon = { Icon(Icons.Rounded.VerifiedUser, null) },
        title = { Text("Allow this action?") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary.action, style = MaterialTheme.typography.titleMedium)
                if (summary.service.isNotBlank()) Text(summary.service, style = MaterialTheme.typography.bodySmall)
                if (summary.target.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Target", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary.target, style = MaterialTheme.typography.bodyLarge)
                }
                if (summary.fields.isNotBlank()) Text(summary.fields, style = MaterialTheme.typography.bodyMedium)
                if (summary.preview.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Content preview", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(summary.preview, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(assistantText("Review the details before Beacon runs this action."))
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("beacon-tool-approval-details-toggle")) {
                    Text(if (details) "Hide details" else "Details")
                }
                if (details) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(approval.arguments, Modifier.testTag("beacon-tool-approval-details"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
                if (approval.error.isNotBlank()) Text(callToolApprovalErrorMessage(approval.error), color = MaterialTheme.colorScheme.error)
                if (approval.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onDecision("deny") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-deny")) { Text("Deny") }
                TextButton(onClick = { onDecision("approve") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-allow")) { Text("Allow once") }
                if (approval.allowCall) Button(onClick = { onDecision("approve_call") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-allow-call")) { Text("Always allow during this call") }
            }
        },
        modifier = Modifier.testTag("beacon-tool-approval"),
    )
}
