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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.voice.INCOMING_RING_SECONDS
import dev.hermes.android.voice.IncomingRingtoneService
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.assist.ACTION_ASSIST_CALL
import dev.hermes.android.assist.AssistGestures
import dev.hermes.android.assist.AssistLaunchEffect
import dev.hermes.android.assist.ContentDirection
import dev.hermes.android.assist.EXTRA_ASSIST_TOKEN
import dev.hermes.android.assist.assistFresh
import dev.hermes.android.data.NativeAuth
import dev.hermes.android.ui.*
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.callTimerLabel
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
    // Set when the overlay screen opens, so the next return can tell a locked setting from one the user left off.
    private var overlayAsked = false
    private var overlayHint by mutableStateOf(false)
    // When the phone's assist gesture was made, until it has been served; kept across a recreation, never served twice.
    private var assistAt by mutableStateOf<Long?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The call-id extra is stripped once read, so a recreation before the call view opens must keep the pending return.
        pendingReturn = savedInstanceState?.getString("pending_return").orEmpty()
        overlayAsked = savedInstanceState?.getBoolean("overlay_asked") == true
        assistAt = savedInstanceState?.getLong("assist_at")?.takeIf { assistFresh(it, System.currentTimeMillis()) }
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
            AssistLaunchEffect(model, assistAt) { assistAt = null }
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
                HermesPhoneApprovalHost(if (ui.authenticated) model.client else null)
                CallBubbleAskHost(call)
                // With the client, a plugin restart that waited for Hermes to go idle is retried whenever Beacon is in front.
                dev.hermes.android.updates.AppUpdateHost(model.app.updates, model.client, ui.bootstrap.optJSONObject("optional_features")?.optString("plugin") == "absent")
                if (overlayHint) AlertDialog(
                    onDismissRequest = { overlayHint = false },
                    title = { Text(uiText("Allow call bubble")) },
                    text = { Text(uiText("Greyed out? In App info tap ⋮ › Allow restricted settings.")) },
                    confirmButton = {
                        TextButton(onClick = { overlayHint = false; if (!openAppInfo(this@BeaconActivity)) model.notice("Open Beacon’s app settings in Android") }) { Text(uiText("Open App info")) }
                    },
                    dismissButton = { TextButton(onClick = { overlayHint = false }) { Text(uiText("Cancel")) } },
                )
                if (pendingConnectionLink != null && pendingConnectionOrigin != null && !ui.restoringSession && hasKnownOrigin) {
                    val cancelConnectionLink = {
                        pendingConnectionLink = null
                    }
                    AlertDialog(
                        onDismissRequest = cancelConnectionLink,
                        title = { Text(uiText("Connect to {0}?").replace("{0}", pendingConnectionOrigin.orEmpty())) },
                        confirmButton = {
                            TextButton(onClick = {
                                val link = pendingConnectionLink
                                val origin = pendingConnectionOrigin
                                if (link != null && origin != null) {
                                    externalConnectionLinkOrigin = origin
                                    model.openSetupLink(link, confirmed = true)
                                }
                                cancelConnectionLink()
                            }) { Text(uiText("Connect")) }
                        },
                        dismissButton = { TextButton(onClick = cancelConnectionLink) { Text(uiText("Cancel")) } },
                    )
                }
            } } } }
        }
    }
    private var pendingReturn by mutableStateOf("")
    fun allowFloatingCall(expectedId: String) {
        val calls=(application as BeaconApplication).calls
        if (!calls.state.value.active || calls.state.value.callId != expectedId) return
        openCallBubbleSettings()
    }
    /** The overlay permission screen for the call bubble; also opened from the one question asked after a call. */
    fun openCallBubbleSettings() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))); overlayAsked = true }
        catch (_: RuntimeException) { vm?.notice("Floating calls aren't available; use the call notification.") }
    }
    override fun onStart() { super.onStart(); (application as BeaconApplication).calls.let { it.uiVisible = true; if (it.state.value.active) it.resumeWindow() }; vm?.foreground(true) }
    override fun onResume() { super.onResume(); NativeAuth.onActivityResumed(); (application as BeaconApplication).calls.refreshOverlayPermission(); checkOverlayAnswer() }
    /** Back from the overlay screen: a file install that is still not allowed was locked by Android, not declined. */
    private fun checkOverlayAnswer() {
        if (!overlayAsked) return
        overlayAsked = false
        overlayHint = overlayNeedsRestrictedAllow(android.os.Build.VERSION.SDK_INT, installPackageSource(this), Settings.canDrawOverlays(this))
    }
    override fun onStop() { (application as BeaconApplication).calls.uiVisible = false; vm?.foreground(false); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putString("pending_return", pendingReturn); outState.putBoolean("overlay_asked", overlayAsked); assistAt?.let { outState.putLong("assist_at", it) } }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); push(intent) }
    private fun push(intent: Intent?) {
        // The phone's assist gesture: the token is single-use and the extra is stripped, so a recreation cannot start a second call.
        if (intent?.action == ACTION_ASSIST_CALL) {
            val token = intent.getStringExtra(EXTRA_ASSIST_TOKEN)
            intent.removeExtra(EXTRA_ASSIST_TOKEN)
            intent.action = null
            AssistGestures.take(token)?.let { assistAt = it }
        }
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

/** How long a swiped-away call can still be taken back. A decline is sent to Hermes and cannot be undone, so it is sent when this window closes. */
internal const val INCOMING_UNDO_MILLIS = 8_000L
private val IncomingDeclineRed = Color(0xffc62f2a)
private val IncomingAnswerGreen = Color(0xff2e7d4f)

/** The reason Hermes gave for the call that is ringing, from the owner's own call list (the ringing event shows there as an active incoming call). */
internal fun incomingReasonFromHistory(history: org.json.JSONObject, event: String): String =
    history.rows("calls").firstOrNull { it.string("id") == event && it.string("direction") == "incoming" }
        ?.string("reason")?.replace(Regex("\\s+"), " ")?.trim()?.take(120).orEmpty()

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
    // The call a swipe-down has declined for now: hidden at once, declined for real when the Undo window closes.
    var swiped by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pending?.key) { if (swiped != null && swiped != pending?.key) swiped = null }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latestInvite by rememberUpdatedState(invite)
    val latestRemaining by rememberUpdatedState(remaining)
    val declinedText = uiText("Call declined.")
    val undoText = uiText("Undo")
    val reason by produceState("", pending?.key) {
        value = ""
        val item = pending ?: return@produceState
        val client = vm.client?.detached() ?: return@produceState
        val profile = vm.authenticatedScope()?.profile ?: return@produceState
        value = try { incomingReasonFromHistory(client.get("/api/plugins/beacon/mobile/calls", mapOf("profile" to profile, "limit" to "10")), item.event) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { "" }
    }
    if (pending != null && ui.authenticated && !call.active && swiped != pending.key) {
        ModalBottomSheet(
            onDismissRequest = {
                val item = pending
                swiped = item.key
                IncomingRingtoneService.silence(item.source, item.event)
                scope.launch {
                    val window = minOf(latestRemaining, INCOMING_UNDO_MILLIS)
                    val result = withTimeoutOrNull(window) { snackbar.showSnackbar(declinedText, actionLabel = undoText, duration = SnackbarDuration.Indefinite) }
                    if (result == SnackbarResult.ActionPerformed) swiped = null
                    else if (latestInvite?.key == item.key && latestRemaining > 0) vm.declineIncoming(item.source, item.event)
                }
            },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("beacon-incoming-sheet"), sheetMaxWidth = 560.dp, containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            IncomingCallContent(
                reason = reason,
                ringFraction = (remaining / (INCOMING_RING_SECONDS * 1000f)).coerceIn(0f, 1f),
                error = ui.error.ifBlank { call.error },
                answerLabel = when { requestingPermission -> uiText("Waiting for microphone permission"); busy -> uiText("Please wait"); else -> uiText("Answer") },
                enabled = !requestingPermission && !busy,
                onAnswer = { onAnswer(pending.source, pending.event) },
                onDecline = { vm.declineIncoming(pending.source, pending.event) },
            )
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.testTag("beacon-incoming-undo")) }
}

/** The Beacon mark inside a thin countdown ring, the reason when there is one, and a round Decline (red) next to a round Answer (green). */
@Composable internal fun IncomingCallContent(
    reason: String,
    ringFraction: Float,
    error: String,
    answerLabel: String,
    enabled: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = rememberNativeMotionEnabled()
    val ring = if (motion) animateFloatAsState(ringFraction, tween(250, easing = LinearEasing), label = "incoming-ring").value else ringFraction
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f)
    val ink = MaterialTheme.colorScheme.primary
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(112.dp).testTag("beacon-incoming-presence"), contentAlignment = androidx.compose.ui.Alignment.Center) {
            BeaconSignal(BeaconMotionMode.Pulse, Modifier.testTag("beacon-incoming-pulse"), 96.dp)
            Canvas(Modifier.fillMaxSize().testTag("beacon-incoming-ring")) {
                val stroke = 3.dp.toPx()
                val inset = stroke / 2
                val box = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                drawArc(ink, -90f, 360f * ring, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            BeaconMark(size = 40.dp)
        }
        Text(assistantText("Beacon is calling"), Modifier.semantics { liveRegion = LiveRegionMode.Assertive }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        if (reason.isNotBlank()) Text(reason, Modifier.testTag("beacon-incoming-reason"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (error.isNotBlank()) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.Top) {
            IncomingRoundButton(uiText("Decline"), Icons.Rounded.CallEnd, IncomingDeclineRed, enabled, "beacon-incoming-decline", onDecline)
            IncomingRoundButton(answerLabel, Icons.Rounded.Call, IncomingAnswerGreen, enabled, "beacon-incoming-answer", onAnswer)
        }
    }
}

/** A 72dp round button with its word underneath; the whole column is the one tap target. */
@Composable private fun IncomingRoundButton(label: String, icon: ImageVector, container: Color, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 96.dp, max = 160.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(4.dp).testTag(tag),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(container.copy(alpha = if (enabled) 1f else .4f)), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = Color.White)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
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
    NativeFeedbackEffect(uiText(ui.error), uiText(ui.notice), ui.setupWizard, snackbar,
        actionLabel = uiText("Undo").takeIf { archiveUndo != null && ui.error.isBlank() && ui.notice == "Archived" }, onAction = vm::undoArchive, clearMessage = vm::clearMessage)
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
                        if (!ui.connected) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("beacon-offline-banner")) {
                            Row(Modifier.padding(start = 16.dp).heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (ui.loading) BeaconDustCore(18.dp) else Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (ui.loading) uiText("Reconnecting…") else uiText("Offline · showing saved chats"), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = !ui.loading, onClick = { vm.connect(ui.origin) }) { Text(uiText("Reconnect")) }
                            }
                        }
                        if (call.active && (ui.tab !in setOf("Chat","Beacon") && !botChatVisible || call.conversationScope != vm.authenticatedScope())) CallBanner(call, onOpen = { vm.openCurrentCall() }, onHangUp = vm.app.calls::hangup)
                        Box(Modifier.weight(1f).then(modeMotion)) {
                        if (vm.client == null && !ui.loading && ui.tab in setOf("Tasks", "Spaces", "Projects", "Kanban")) {
                            Header(when(ui.tab) { "Tasks" -> uiText("Schedules"); "Spaces" -> uiText("Files"); else -> uiText(ui.tab) }, menu = openMenu)
                            EmptyState(uiText("Reconnect to Hermes"), uiText("Check your network, then retry. Your saved drafts stay on this phone."), action = uiText("Reconnect"), onAction = { vm.connect(ui.origin) })
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
        ApprovalCardOnScreen(approval.requestId, vm.app.calls::approvalCardShown)
        CallToolApprovalDialog(approval, onDecision = { decision ->
            vm.app.calls.decideTool(call.callId,approval.requestId,approval.proposalId,decision)
        })
    }
}

/** The strip over every other tab while a call runs: who, what the call is doing, how long it has lasted. */
@Composable private fun CallBanner(call: CallState, onOpen: () -> Unit, onHangUp: () -> Unit) {
    var now by remember(call.callId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.callId) { while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("${LocalAssistantName.current} · ${callStatusText(call)} · ${callTimerLabel(call.startedAt, now)}", Modifier.weight(1f),
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            TextButton(onClick = onOpen) { Text(uiText("Open call")) }
            IconButton(onClick = onHangUp) { Icon(Icons.Rounded.CallEnd, assistantText("Hang up Beacon")) }
        }
    }
}

@Composable
internal fun CallToolApprovalDialog(approval: CallToolApproval, onDecision: (String) -> Unit) {
    // A call the owner asked for in a live call counts down on its own card instead of waiting for a tap.
    if ((approval.phone?.countdown ?: 0) > 0) { PhoneCallCountdownDialog(approval, onDecision); return }
    var details by rememberSaveable(approval.requestId) { mutableStateOf(false) }
    val summary = remember(approval.toolName, approval.arguments) { callToolApprovalSummary(approval) }
    AlertDialog(
        onDismissRequest = { if (!approval.busy) onDecision("deny") },
        icon = { Icon(Icons.Rounded.VerifiedUser, null) },
        title = { Text(uiText("Allow this action?")) },
        text = {
            val scroll = rememberScrollState()
            // A fade at the bottom shows while there is more to read; the answers stay in the dialog's own button column.
            Column(Modifier.heightIn(max = 420.dp).verticalScrollFade(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(summary.action, style = MaterialTheme.typography.titleMedium.merge(ContentDirection))
                if (summary.service.isNotBlank()) Text(summary.service, style = MaterialTheme.typography.bodySmall)
                if (summary.target.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiText("Target"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(summary.target, style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
                }
                if (summary.fields.isNotBlank()) Text(summary.fields, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                if (summary.preview.isNotBlank()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Text(summary.preview, Modifier.fillMaxWidth().padding(12.dp).testTag("beacon-tool-approval-preview"), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                }
                TextButton(onClick = { details = !details }, modifier = Modifier.heightIn(min = 48.dp).testTag("beacon-tool-approval-details-toggle")) {
                    Text(if (details) uiText("Hide details") else uiText("Details"))
                }
                if (details) androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(approval.arguments, Modifier.testTag("beacon-tool-approval-details"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                }
                if (approval.error.isNotBlank()) Text(callToolApprovalErrorMessage(approval.error), color = MaterialTheme.colorScheme.error)
                if (approval.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            // Emphasis goes to the one-time choice; the standing permission is the quietest button and sits apart.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onDecision("deny") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-deny")) { Text(uiText("Deny")) }
                Button(onClick = { onDecision("approve") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-allow")) { Text(uiText("Allow once")) }
                if (approval.allowCall) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { onDecision("approve_call") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-allow-call")) { Text(uiText("Always allow during this call")) }
                }
            }
        },
        modifier = Modifier.testTag("beacon-tool-approval"),
    )
}
