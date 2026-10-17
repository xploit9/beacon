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
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.collect

class CypherActivity : ComponentActivity() {
    private var vm: CypherViewModel? = null
    private var initialPush by mutableStateOf<Triple<String, String, String>?>(null)
    private var updateToken by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        push(intent)
        setContent {
            val model: CypherViewModel = viewModel()
            vm = model
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val data by model.data.collectAsStateWithLifecycle()
            val ui by model.ui.collectAsStateWithLifecycle()
            var permissionSource by rememberSaveable { mutableStateOf("") }
            var permissionEvent by rememberSaveable { mutableStateOf("") }
            var permissionAction by rememberSaveable { mutableStateOf("answer") }
            val answerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
                val source = permissionSource; val event = permissionEvent
                permissionSource = ""; permissionEvent = ""
                if (allowed && source.isNotBlank() && event.isNotBlank()) model.handlePush(source, event, permissionAction)
                else model.notice("Allow microphone access to answer Cypher. The invitation stays available until it expires.")
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
            val density = LocalDensity.current
            val fontFactor = when (preferences.fontSize) { "small" -> .9f; "large" -> 1.12f; "xlarge" -> 1.25f; else -> 1f }
            val emptySettings = remember { org.json.JSONObject() }
            CompositionLocalProvider(LocalCypherSettings provides (data["profile-settings"] ?: emptySettings), LocalDensity provides Density(density.density, density.fontScale * fontFactor), LocalLayoutDirection provides if (preferences.rtl || preferences.language in setOf("ar", "he", "fa", "ur")) LayoutDirection.Rtl else LocalLayoutDirection.current) { NativeLocalization(preferences.language) { CypherTheme(dark, preferences.skin) { NativeWindowLayout(this@CypherActivity) { CypherRoot(model); IncomingCallSheet(model, permissionSource.isNotBlank(), answer) } } } }
        }
    }
    private var pendingReturn by mutableStateOf("")
    fun allowFloatingCall(expectedId: String) {
        val calls=(application as CypherApplication).calls
        if (!calls.state.value.active || calls.state.value.callId != expectedId) return
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { vm?.notice("Floating calls are unavailable. Your call continues; return using its notification.") }
    }
    override fun onStart() { super.onStart(); (application as CypherApplication).calls.let { it.uiVisible = true; if (it.state.value.active) it.resumeWindow() }; vm?.foreground(true) }
    override fun onResume() { super.onResume(); vm?.resumeAppUpdate(); (application as CypherApplication).calls.refreshOverlayPermission() }
    override fun onStop() { (application as CypherApplication).calls.uiVisible = false; vm?.foreground(false); super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); push(intent) }
    private fun push(intent: Intent?) {
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
        ModalBottomSheet(onDismissRequest = { vm.app.push.dismissInvitation(pending.source, pending.event) }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("xara-incoming-sheet"), sheetMaxWidth = 560.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Call, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                    Text(uiText("Cypher is calling"), style = MaterialTheme.typography.headlineSmall)
                }
                Text(uiText("Answer to start a private voice call. Your microphone stays off until you answer."), style = MaterialTheme.typography.bodyLarge)
                Text(uiText("The result stays in Cypher history if you miss or decline this invitation."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                ui.error.ifBlank { call.error }.takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Button(onClick = { onAnswer(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("xara-incoming-answer")) { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text(uiText(when { requestingPermission -> "Waiting for microphone permission"; busy -> "Please wait"; else -> "Answer" })) }
                OutlinedButton(onClick = { vm.declineIncoming(pending.source, pending.event) }, enabled = !requestingPermission && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("xara-incoming-decline")) { Icon(Icons.Rounded.CallEnd, null); Spacer(Modifier.width(8.dp)); Text(uiText("Decline")) }
            }
        }
    }
}

@Composable fun CypherTheme(dark: Boolean = isSystemInDarkTheme(), skin: String = "default", content: @Composable () -> Unit) {
    NativeSkinTheme(dark, skin, content)
}

@Composable internal fun CypherRoot(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val botMode by vm.botMode.state.collectAsStateWithLifecycle()
    val botChatVisible = ui.tab == "More" && ui.morePage == "Bots" && botMode.activeBot != null && botMode.sessionId == vm.authenticatedScope()?.session
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val motionEnabled = rememberNativeMotionEnabled(preferences.reducedMotion)
    val modeEntry = remember { Animatable(1f) }
    val sidebarState = rememberSaveableStateHolder()
    LaunchedEffect(ui.botModeEnabled, motionEnabled) {
        if (motionEnabled) { modeEntry.snapTo(.5f); modeEntry.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        else modeEntry.snapTo(1f)
    }
    val modeMotion = Modifier.graphicsLayer { alpha = modeEntry.value }
    LaunchedEffect(preferences.reducedMotion) { vm.app.calls.reducedMotion=preferences.reducedMotion }
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
    BackHandler(ui.authenticated && !ui.showSessions && ui.tab !in setOf("Chat", "Xara") && (ui.tab != "More" || ui.morePage.isBlank())) {
        if (ui.tab == "More") vm.backFromSettings() else if (ui.tab == "Spaces" && vm.editor.value.path.isNotBlank()) vm.closeEditor() else if (ui.botModeEnabled) vm.showBotMode() else vm.tab("Chat")
    }
    LaunchedEffect(ui.error, ui.notice) { val text = ui.error.ifBlank { ui.notice }; if (text.isNotBlank()) { snackbar.showSnackbar(text, withDismissAction = true); vm.clearMessage() } }
    if (ui.restoringSession) {
        Surface(Modifier.fillMaxSize().testTag("session-restore-logo"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding(), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_cypher), null, Modifier.size(64.dp), tint = androidx.compose.ui.graphics.Color.Unspecified)
                Spacer(Modifier.height(16.dp))
                Text("Cypher", style = MaterialTheme.typography.displaySmall)
            }
        }
    } else if (!ui.authenticated) {
        val feedbackReserve = with(LocalDensity.current) { feedbackHeightPx.toDp() }
        Scaffold(modifier = Modifier.imePadding(), contentWindowInsets = WindowInsets.safeDrawing, snackbarHost = feedbackHost) { padding -> SignIn(vm, Modifier.padding(padding).padding(bottom = feedbackReserve).consumeWindowInsets(padding)) }
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
            val hasComposer = ui.tab in setOf("Chat", "Xara") && !(call.active && call.conversationScope == vm.authenticatedScope())
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
                        if (call.active && (ui.tab !in setOf("Chat","Xara") && !botChatVisible || call.conversationScope != vm.authenticatedScope())) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { Text("Cypher · ${call.state}", Modifier.weight(1f)); TextButton(onClick = { vm.openCurrentCall() }) { Text(uiText("Open call")) }; IconButton(onClick = vm.app.calls::hangup) { Icon(Icons.Rounded.CallEnd, uiText("Hang up Cypher")) } } }
                        Box(Modifier.weight(1f).then(modeMotion)) {
                        if (vm.client == null && !ui.loading && ui.tab in setOf("Tasks", "Spaces", "Projects", "Kanban")) {
                            Header(when(ui.tab) { "Tasks" -> "Cron"; "Spaces" -> "Files"; else -> ui.tab }, menu = openMenu)
                            EmptyState("Reconnect to Hermes", "Keep Tailscale connected to open this page. Your drafts remain saved on this phone.", action = "Reconnect", onAction = { vm.connect(ui.origin) })
                        } else when (ui.tab) { "Xara" -> ChatScreen(vm, xara = true, onComposerHeight = { composerHeightPx = it }); "Chat" -> ChatScreen(vm, onComposerHeight = { composerHeightPx = it }); "Tasks" -> TasksScreen(vm); "Spaces" -> SpacesScreen(vm); "Projects" -> NativeProjectsScreen(vm); "Kanban" -> KanbanScreen(vm); else -> MoreScreen(vm) }
                        }
                    }
                }
            }
        }
    }
    // Register after destination Back handlers so an open drawer always closes first.
    BackHandler(!ui.showSessions && ui.tab == "More" && ui.morePage.isNotBlank() && ui.morePage !in setOf("Skills", "Memory", "Bots", "Profiles")) { vm.backFromSettings() }
    BackHandler(ui.showSessions) { vm.toggleSessions(false) }
    form?.let { NativeForm(vm, it) }
    val approval=call.toolApprovals.firstOrNull()?.takeIf { ui.authenticated && call.active && call.conversationScope?.let { s -> s.owner==ui.owner&&s.profile==ui.profile&&s.origin==ui.origin }==true }
    if(approval!=null&&form==null) {
        val decide: (Boolean) -> Unit = { allow -> vm.app.calls.decideTool(call.callId,approval.requestId,approval.proposalId,allow) }
        AlertDialog(onDismissRequest={if(!approval.busy) decide(false)},
            icon={Icon(Icons.Rounded.VerifiedUser,null)}, title={Text("Allow this action?")},
            text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(approval.toolName.substringAfterLast("__").replace('_',' ').replaceFirstChar { it.titlecase() },style=MaterialTheme.typography.titleMedium)
                if(approval.toolName.startsWith("mcp__")) Text("Connection: "+approval.toolName.removePrefix("mcp__").substringBefore("__").replace('_',' '),style=MaterialTheme.typography.bodySmall)
                Text("Review the details before Cypher runs this action.")
                androidx.compose.foundation.text.selection.SelectionContainer { Text(approval.arguments,style=MaterialTheme.typography.bodySmall.copy(fontFamily=androidx.compose.ui.text.font.FontFamily.Monospace)) }
                if(approval.error.isNotBlank()) Text(approval.error,color=MaterialTheme.colorScheme.error)
                if(approval.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }},
            confirmButton={TextButton(onClick={decide(true)},enabled=!approval.busy,modifier=Modifier.testTag("cypher-tool-allow")) {Text("Allow once")}},
            dismissButton={TextButton(onClick={decide(false)},enabled=!approval.busy,modifier=Modifier.testTag("cypher-tool-deny")) {Text("Deny")}},
            modifier=Modifier.testTag("cypher-tool-approval"))
    }
}

@Composable private fun SignIn(vm: CypherViewModel, modifier: Modifier = Modifier) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var origin by rememberSaveable(ui.origin) { mutableStateOf(ui.origin) }
    var username by rememberSaveable { mutableStateOf(BuildConfig.DEFAULT_USERNAME) }
    var password by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        Text(uiText("Cypher"), style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(uiText("Your conversations, work and Cypher.\nConnected privately to Hermes."), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))
        Glass(Modifier.widthIn(max = 560.dp).fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(origin, { origin = it }, label = { Text(uiText("Private Cypher HTTPS origin")) }, placeholder = { Text(uiText("https://your-device.ts.net")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, { username = it }, label = { Text(uiText("Hermes username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(uiText("Cypher password")) }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            if (ui.error.isNotBlank()) Text(ui.error, color = MaterialTheme.colorScheme.error)
            Button(onClick = { vm.connect(origin, password, username = username); password = "" }, enabled = !ui.loading && origin.isNotBlank() && username.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { if (ui.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(uiText("Connect securely")) }
            Text(uiText("Keep Tailscale connected. Credentials are stored with Android Keystore."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
    }
}
