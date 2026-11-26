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
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
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
import dev.hermes.android.data.BeaconOffLines
import dev.hermes.android.data.NativeAuth
import dev.hermes.android.data.PROFILE_PLUGIN_OFF_ERROR
import dev.hermes.android.data.beaconOffAction
import dev.hermes.android.ui.*
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.callTimerLabel
import dev.hermes.android.voice.line
import dev.hermes.android.voice.showsProgress
import dev.hermes.android.voice.pendingApprovalFor
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
    private var returnAfterCall = false
    private var finishWhenFocused = false
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
        returnAfterCall = savedInstanceState?.getBoolean("return_after_call") == true
        finishWhenFocused = savedInstanceState?.getBoolean("finish_when_focused") == true
        push(intent)
        // S1: the model before onStart. A system relaunch (theme, display size, a fold while away) composes only after onStart, so onStart told no
        // model it was in front, and every Send stopped until a force quit. Composition's viewModel() returns this same one.
        vm = androidx.lifecycle.ViewModelProvider(this)[BeaconViewModel::class.java]
        setContent {
            val model: BeaconViewModel = viewModel()
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
            CompositionLocalProvider(LocalBeaconSettings provides (data["profile-settings"] ?: emptySettings), LocalAssistantName provides preferences.assistantName, dev.hermes.android.voice.LocalCallAssistantProfile provides preferences.assistantProfile) { NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) { BeaconTheme(dark, preferences.skin) { NativeWindowLayout(this@BeaconActivity) {
                BeaconRoot(model, externalConnectionLinkOrigin) { externalConnectionLinkOrigin = null }
                IncomingCallSheet(model, permissionSource.isNotBlank(), answer)
                HermesPhoneApprovalHost(if (ui.authenticated) model.client else null)
                dev.hermes.android.agentphone.AgentApprovalHost(if (ui.authenticated) model.client else null)
                CallEndNotice(call) {
                    if (returnAfterCall) {
                        finishWhenFocused = true
                        if (hasWindowFocus()) finish()
                    }
                }
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
    override fun onStart() {
        super.onStart()
        (application as BeaconApplication).calls.let { calls ->
            calls.uiVisible = true
            val returning = calls.takeCallOnResume()
            if (calls.state.value.active) { calls.resumeWindow(); returning?.let { id -> vm?.openCurrentCall(id) } }
        }
        vm?.foreground(true)
    }
    override fun onResume() { super.onResume(); NativeAuth.onActivityResumed(); (application as BeaconApplication).calls.refreshOverlayPermission(); checkOverlayAnswer() }
    /** Back from the overlay screen: a file install that is still not allowed was locked by Android, not declined. */
    private fun checkOverlayAnswer() {
        if (!overlayAsked) return
        overlayAsked = false
        overlayHint = overlayNeedsRestrictedAllow(android.os.Build.VERSION.SDK_INT, installPackageSource(this), Settings.canDrawOverlays(this))
    }
    override fun onStop() { (application as BeaconApplication).calls.uiVisible = false; vm?.foreground(false); super.onStop() }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // A permission question or another window can be on top when the duration fades.
        if (hasFocus && finishWhenFocused) finish()
    }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); outState.putBoolean("return_after_call", returnAfterCall); outState.putBoolean("finish_when_focused", finishWhenFocused); outState.putString("pending_return", pendingReturn); outState.putBoolean("overlay_asked", overlayAsked); assistAt?.let { outState.putLong("assist_at", it) } }
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
        val temporary = intent?.getBooleanExtra(dev.hermes.android.voice.IncomingDialerActivity.RETURN_AFTER_CALL, false) == true
        intent?.removeExtra(dev.hermes.android.voice.IncomingDialerActivity.RETURN_AFTER_CALL)
        intent?.getStringExtra(BeaconCallService.EXTRA_CALL_ID)?.let {
            val current = (application as BeaconApplication).calls.state.value
            if (temporary && it.isNotBlank() && current.active && current.callId == it) returnAfterCall = true
            pendingReturn=it;intent.removeExtra(BeaconCallService.EXTRA_CALL_ID) }
        intent?.getStringExtra(dev.hermes.android.updates.UpdateManager.EXTRA_TOKEN)?.takeIf(dev.hermes.android.updates.UpdateManager::validToken)?.let { updateToken = it }
        val source = intent?.getStringExtra("push_source") ?: intent?.getStringExtra("source_ref").orEmpty()
        val event = intent?.getStringExtra("push_event") ?: intent?.getStringExtra("event_id").orEmpty()
        if (dev.hermes.android.push.validOpaque(source) && dev.hermes.android.push.validOpaque(event)) {
            val invite = (application as BeaconApplication).push.ringingInvitation(source, event)
            val answer = invite != null && intent?.action == invite.answerAction
            if (temporary && answer) returnAfterCall = true
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
    short: Boolean = false,
    clearMessage: () -> Unit,
) {
    val message = rememberUpdatedState(if (setupWizard) "" else error.ifBlank { notice })
    val brief = rememberUpdatedState(short)
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
                val result = snackbar.showSnackbar(text, actionLabel = button, withDismissAction = true, duration = if (brief.value) SnackbarDuration.Short else SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) action.value() else clear.value()
            }
        }
    }
}

/** How long a swiped-away call can still be taken back. A decline is sent to Hermes and cannot be undone, so it is sent when this window closes. */
internal const val INCOMING_UNDO_MILLIS = 8_000L
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
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("beacon-incoming-sheet"), sheetMaxWidth = 560.dp, containerColor = CallBackground, contentColor = Color.White,
        ) {
            CompositionLocalProvider(LocalAssistantName provides pending.callerName.ifBlank { LocalAssistantName.current }) {
                IncomingCallContent(
                    reason = dev.hermes.android.ui.callReasonWords(reason, dev.hermes.android.ui.uiTextLookup()).orEmpty(),
                    ringFraction = (remaining / (INCOMING_RING_SECONDS * 1000f)).coerceIn(0f, 1f),
                    error = ui.error.ifBlank { call.error },
                    answerLabel = when { requestingPermission -> uiText("Waiting"); busy -> uiText("Connecting…"); else -> uiText("Answer") },
                    enabled = !requestingPermission && !busy,
                    onAnswer = { onAnswer(pending.source, pending.event) },
                    onDecline = { vm.declineIncoming(pending.source, pending.event) },
                )
            }
        }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) { SnackbarHost(snackbar, Modifier.testTag("beacon-incoming-undo")) }
}

/** The gold portrait inside a thin countdown ring, the name large with "Incoming call" under it, the reason when there is one, and a round Decline (red) next to a round Answer (green). */
@Composable internal fun IncomingCallContent(
    reason: String,
    ringFraction: Float,
    error: String,
    answerLabel: String,
    enabled: Boolean,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
) {
    val motion = rememberNativeMotionEnabled()
    val ring = if (motion) animateFloatAsState(ringFraction, tween(250, easing = LinearEasing), label = "incoming-ring").value else ringFraction
    val track = CallGold.copy(alpha = .22f)
    val ink = CallGold
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        val tall = fullScreen && maxHeight >= 580.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
        val layout = if (tall) Modifier.fillMaxHeight() else Modifier.verticalScroll(rememberScrollState())
        Column(layout.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (tall) 36.dp else 20.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (tall) Spacer(Modifier.weight(.65f))
            Box(Modifier.size(if (tall) 220.dp else 144.dp).testTag("beacon-incoming-presence"), contentAlignment = androidx.compose.ui.Alignment.Center) {
                // The caller title announces the ring. The decorative portrait must not announce a connected voice state.
                // "thinking" only picks the gold colours of the call screen's own dust; it moves as before.
                VoicePresence(CallState(active = true, state = "thinking"),
                    Modifier.fillMaxSize().testTag("beacon-incoming-pulse").clearAndSetSemantics { }, motion)
                Canvas(Modifier.fillMaxSize().testTag("beacon-incoming-ring")) {
                    val stroke = 3.dp.toPx()
                    val inset = stroke / 2
                    val box = Size(size.width - stroke, size.height - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
                    drawArc(ink, -90f, 360f * ring, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
            // One merged node: TalkBack still reads "Beacon is calling" as an assertive announcement.
            val calling = assistantText("Beacon is calling")
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = calling; liveRegion = LiveRegionMode.Assertive }, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                Text(LocalAssistantName.current, Modifier.testTag("beacon-incoming-name"), style = MaterialTheme.typography.headlineLarge, color = CallText, textAlign = TextAlign.Center)
                Text(uiText("Incoming call"), Modifier.testTag("beacon-incoming-status"), style = MaterialTheme.typography.labelLarge, color = CallGoldBright, textAlign = TextAlign.Center)
            }
            if (reason.isNotBlank()) Text(reason, Modifier.testTag("beacon-incoming-reason"), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = Color.LightGray)
            if (error.isNotBlank()) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            if (tall) Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = androidx.compose.ui.Alignment.Top) {
                IncomingRoundButton(uiText("Decline"), Icons.Rounded.CallEnd, CallHangupRed, enabled, "beacon-incoming-decline", onDecline, Modifier.weight(1f))
                IncomingRoundButton(answerLabel, Icons.Rounded.Call, IncomingAnswerGreen, enabled, "beacon-incoming-answer", onAnswer, Modifier.weight(1f), halo = motion)
            }
        }
    }
}

/**
 * A 72dp round button with its word underneath; the whole column is the one tap target, and the press ripple is drawn on the circle only.
 * [halo] is one soft ring that grows from the circle and fades, over and over (the cue of a real call); a firm tick confirms the tap.
 */
@Composable private fun IncomingRoundButton(label: String, icon: ImageVector, container: Color, enabled: Boolean, tag: String, onClick: () -> Unit, modifier: Modifier, halo: Boolean = false) {
    // The ring is stopped first; its vibration outranks a tap haptic until Android has cancelled it, so the tick follows a moment later.
    val context = androidx.compose.ui.platform.LocalContext.current
    val press = remember { MutableInteractionSource() }
    var pulse by remember { mutableFloatStateOf(0f) }
    if (halo && enabled) LaunchedEffect(Unit) {
        val start = withInfiniteAnimationFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { now -> pulse = ((now - start) / 1_000_000L % INCOMING_HALO_MILLIS) / INCOMING_HALO_MILLIS.toFloat() }
    }
    Column(
        modifier.clickable(press, null, enabled = enabled, role = Role.Button, onClick = { dev.hermes.android.voice.IncomingRingtoneService.silenceActive(); dev.hermes.android.voice.IncomingRingtoneService.tapHaptic(context); onClick() }).padding(4.dp).testTag(tag),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(72.dp)
            .drawBehind { if (halo && enabled) drawCircle(container.copy(alpha = .35f * (1f - pulse)), radius = (36.dp + 24.dp * pulse).toPx()) }
            .clip(CircleShape).background(container.copy(alpha = if (enabled) 1f else .4f)).border(1.dp, CallText.copy(alpha = .14f), CircleShape)
            .indication(press, ripple()), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Icon(icon, null, Modifier.size(32.dp), tint = CallText)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}
private const val INCOMING_HALO_MILLIS = 1600L

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
    val savedScreens by vm.data.collectAsStateWithLifecycle()
    // A normal cold start reconnects quietly under the saved pages; the banner speaks once Hermes did not answer (or a Reconnect tap is running).
    var failedToConnect by remember { mutableStateOf(false) }
    LaunchedEffect(ui.connected, ui.loading, ui.restoringSession) { if (ui.connected) failedToConnect = false else if (!ui.loading && !ui.restoringSession) failedToConnect = true }
    BackHandler(ui.authenticated && !ui.showSessions && ui.tab !in setOf("Chat", "Beacon") && (ui.tab != "More" || ui.morePage.isBlank())) {
        if (ui.tab == "More") vm.backFromSettings() else if (ui.tab == "Spaces" && vm.editor.value.path.isNotBlank()) vm.closeEditor() else if (ui.botModeEnabled) vm.showBotMode() else vm.tab("Chat")
    }
    val archiveUndo by vm.archiveUndo.collectAsStateWithLifecycle()
    // A refused profile's line carries its own button wherever it shows, this bar included: Turn on for a profile that turned Beacon off, else Retry.
    val beaconOff = ui.error.takeIf { it in BeaconOffLines }?.let(::beaconOffAction).orEmpty()
    NativeFeedbackEffect(uiText(ui.error), uiText(ui.notice), ui.setupWizard, snackbar,
        actionLabel = if (beaconOff.isNotBlank()) uiText(beaconOff) else uiText("Undo").takeIf { archiveUndo != null && ui.error.isBlank() && ui.notice == "Archived" },
        onAction = { if (beaconOff.isNotBlank()) vm.turnOnBeaconForProfile(auto = ui.error != PROFILE_PLUGIN_OFF_ERROR) else vm.undoArchive() },
        short = ui.error.isBlank() && ui.notice in SHORT_NOTICES, clearMessage = vm::clearMessage)
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
                        // Signed in but Hermes stopped answering (a VPN that dropped): the same banner says so (owner, 4 Nov 2026).
                        val unreachableOrigins by dev.hermes.android.data.HermesReach.origins.collectAsState()
                        val cantReach = ui.connected && ui.origin in unreachableOrigins
                        val showOffline = offlineBannerShown(ui.connected, ui.loading, cantReach, failedToConnect)
                        val banner: @Composable () -> Unit = {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("beacon-offline-banner")) {
                            Row(Modifier.padding(start = 16.dp).heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (ui.loading) BeaconDustCore(18.dp) else Icon(Icons.Rounded.CloudOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (cantReach) uiText("Can't reach Hermes · check your connection") else if (ui.loading) uiText("Reconnecting…") else uiText("Offline · showing saved chats"), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.labelMedium)
                                TextButton(enabled = !ui.loading, onClick = { if (cantReach) vm.checkHermesReach(now = true) else vm.connect(ui.origin) }, modifier = Modifier.testTag("beacon-offline-retry")) {
                                    Text(if (cantReach) uiText("Retry") else uiText("Reconnect"))
                                }
                            }
                        }
                        }
                        if (!motionEnabled) { if (showOffline) banner() }
                        else AnimatedVisibility(
                            visible = showOffline,
                            enter = fadeIn(tween(180, easing = FastOutSlowInEasing)) + expandVertically(tween(180, easing = FastOutSlowInEasing), expandFrom = androidx.compose.ui.Alignment.Top),
                            exit = fadeOut(tween(150, easing = FastOutSlowInEasing)) + shrinkVertically(tween(180, easing = FastOutSlowInEasing), shrinkTowards = androidx.compose.ui.Alignment.Top),
                            label = "offline-banner",
                        ) { banner() }
                        if (call.active && (ui.tab !in setOf("Chat","Beacon") && !botChatVisible || call.conversationScope != vm.authenticatedScope())) CallBanner(call, onOpen = { vm.openCurrentCall() }, onHangUp = vm.app.calls::hangup)
                        Box(Modifier.weight(1f).then(modeMotion).testTag("beacon-tab-body")) {
                        val savedOnly = vm.client == null && !ui.loading && ui.tab in setOf("Tasks", "Spaces", "Projects", "Kanban") && !savedPageHasCopy(ui.tab, savedScreens)
                        val body = if (savedOnly) "saved:${ui.tab}" else ui.tab
                        val bodyContent: @Composable (String) -> Unit = { key ->
                            if (key.startsWith("saved:")) {
                                val page = key.removePrefix("saved:")
                                Header(when(page) { "Tasks" -> uiText("Schedules"); "Spaces" -> uiText("Files"); else -> uiText(page) }, menu = openMenu)
                                EmptyState(uiText("Reconnect to Hermes"), uiText("Check your network. Drafts stay on this phone."), action = uiText("Reconnect"), onAction = { vm.connect(ui.origin) })
                            } else when (key) {
                                "Beacon" -> ChatScreen(vm, beacon = true, onComposerHeight = { composerHeightPx = it }, feedbackInsetPx = if (hasComposer) feedbackHeightPx else 0)
                                "Chat" -> ChatScreen(vm, onComposerHeight = { composerHeightPx = it }, feedbackInsetPx = if (hasComposer) feedbackHeightPx else 0)
                                "Tasks" -> TasksScreen(vm); "Spaces" -> SpacesScreen(vm); "Projects" -> NativeProjectsScreen(vm); "Kanban" -> KanbanScreen(vm); else -> MoreScreen(vm)
                            }
                        }
                        // Only the new body is composed: the outgoing one is gone at once, and the shell around it does not move.
                        // The first body is shown whole; every later one fades in.
                        val bodyComposed = remember { mutableStateOf(false) }
                        if (!motionEnabled) bodyContent(body) else key(body) {
                            val entered = remember { MutableTransitionState(!bodyComposed.value).apply { targetState = true } }
                            SideEffect { bodyComposed.value = true }
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = entered,
                                enter = fadeIn(tween(150, easing = FastOutSlowInEasing)),
                                exit = ExitTransition.None,
                                label = "tab-body",
                            ) { bodyContent(body) }
                        }
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
    val approval=call.pendingApprovalFor(ui.origin,ui.owner,ui.profile)?.takeIf { ui.authenticated }
    if(approval!=null&&form==null) {
        ApprovalCardOnScreen(approval.requestId, vm.app.calls::approvalCardShown)
        CallToolApprovalDialog(approval, onDecision = { decision ->
            vm.app.calls.decideTool(call.callId,approval.requestId,approval.proposalId,decision)
        }, unreachable = call.hermesUnreachable)
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
            IconButton(onClick = onHangUp) { Icon(Icons.Rounded.CallEnd, uiText("End call")) }
        }
    }
}

@Composable
internal fun CallToolApprovalDialog(approval: CallToolApproval, unreachable: Boolean = false, overPhone: Boolean = false,
    widest: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity, onDecision: (String) -> Unit) {
    // A call the owner asked for in a live call counts down on its own card instead of waiting for a tap.
    if ((approval.phone?.countdown ?: 0) > 0) { PhoneCallCountdownDialog(approval, onDecision); return }
    var details by rememberSaveable(approval.requestId) { mutableStateOf(false) }
    val summary = remember(approval.toolName, approval.arguments, approval.appLabel) { callToolApprovalSummary(approval) }
    // A compact card at the top, not a modal: End call and the watched phone stay visible and tappable. Back is still Deny, except over
    // the watched phone ([overPhone], R3-F9), where the card sits at the top of the phone's own area and Back leaves the view.
    BackHandler(!approval.busy && !overPhone) { onDecision("deny") }
    val top = with(androidx.compose.ui.platform.LocalDensity.current) { ((if (overPhone) 0 else WindowInsets.statusBars.getTop(this)) + 8.dp.roundToPx()) }
    androidx.compose.ui.window.Popup(alignment = androidx.compose.ui.Alignment.TopCenter, offset = androidx.compose.ui.unit.IntOffset(0, top),
        properties = androidx.compose.ui.window.PopupProperties(focusable = false)) {
        // The cap comes before the fill: after it, fillMaxWidth had already fixed the width (a full-width card on an unfolded phone).
        val title = uiText("Allow this action?")
        // A non-modal popup is not announced by itself: the card is a pane with a polite live region.
        Surface(Modifier.padding(horizontal = 12.dp).widthIn(max = minOf(520.dp, (widest - 24.dp).coerceAtLeast(0.dp))).fillMaxWidth().semantics { paneTitle = title; liveRegion = LiveRegionMode.Polite }.testTag("beacon-tool-approval"),
            shape = MaterialTheme.shapes.large, tonalElevation = 6.dp, shadowElevation = 8.dp) {
            val scroll = rememberScrollState()
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.VerifiedUser, null, Modifier.size(20.dp))
                    Text(title, style = MaterialTheme.typography.titleSmall)
                }
                // Short: the action and where it applies; the raw arguments are behind Details.
                Column(Modifier.heightIn(max = if (details) 200.dp else 84.dp).verticalScrollFade(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(summary.headline?.let { dev.hermes.android.phone.PhoneLine(uiText(it.template), it.args).shown() } ?: summary.action,
                        Modifier.testTag("beacon-tool-approval-action"), style = MaterialTheme.typography.titleMedium.merge(ContentDirection))
                    if (summary.purpose.isNotBlank()) Text(dev.hermes.android.phone.PhoneLine(uiText("For: {0}"), listOf(summary.purpose)).shown(),
                        style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    if (summary.target.isNotBlank()) Text(summary.target, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    if (summary.fields.isNotBlank()) Text(summary.fields, style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    if (summary.preview.isNotBlank()) Text(summary.preview, Modifier.fillMaxWidth().testTag("beacon-tool-approval-preview"), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    if (details) {
                        if (summary.service.isNotBlank()) Text(summary.service, style = MaterialTheme.typography.bodySmall)
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(approval.arguments, Modifier.testTag("beacon-tool-approval-details"), style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                        }
                    }
                    approval.line(unreachable)?.let { Text(if (unreachable) uiText(it) else it, Modifier.testTag("beacon-tool-approval-line"), color = MaterialTheme.colorScheme.error) }
                    if (approval.showsProgress(unreachable)) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                // Screen readers go Allow once, Deny, Details; the row keeps Details, Deny, Allow once on screen.
                Row(Modifier.fillMaxWidth().semantics { isTraversalGroup = true }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TextButton(onClick = { details = !details }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { traversalIndex = 2f }.testTag("beacon-tool-approval-details-toggle")) {
                        Text(if (details) uiText("Hide details") else uiText("Details"))
                    }
                    OutlinedButton(onClick = { onDecision("deny") }, enabled = !approval.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { traversalIndex = 1f }.testTag("beacon-tool-deny")) { Text(uiText("Deny")) }
                    Button(onClick = { onDecision("approve") }, enabled = !approval.busy, modifier = Modifier.weight(1.2f).heightIn(min = 48.dp).semantics { traversalIndex = 0f }.testTag("beacon-tool-allow")) { Text(uiText("Allow once")) }
                }
                if (approval.allowCall) TextButton(onClick = { onDecision("approve_call") }, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("beacon-tool-allow-call")) { Text(uiText("Always allow during this call")) }
            }
        }
    }
}
