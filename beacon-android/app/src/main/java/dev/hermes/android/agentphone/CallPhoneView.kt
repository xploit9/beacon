package dev.hermes.android.agentphone

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.CallBackground
import dev.hermes.android.ui.CallToolChip
import dev.hermes.android.ui.assistantText
import dev.hermes.android.voice.chipLabel
import dev.hermes.android.ui.CallEndButton
import dev.hermes.android.ui.CallCompactHangupSize
import dev.hermes.android.ui.CallGoldBright
import dev.hermes.android.ui.CallHangupRed
import dev.hermes.android.ui.CallText
import dev.hermes.android.ui.VoicePresence
import dev.hermes.android.ui.callStatusText
import dev.hermes.android.ui.rememberNativeMotionEnabled
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.uiText
import dev.hermes.android.voice.BeaconCallService
import dev.hermes.android.voice.CallController
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.pendingApprovalFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** The call's account, once its service has taken the call: it can still be attaching when the owner taps the phone button. */
internal suspend fun callHandOffClient(waitMs: Long = 15_000, resolve: suspend () -> BeaconClient?): BeaconClient? =
    withTimeoutOrNull(waitMs) {
        var found: BeaconClient? = null
        while (found == null) { found = resolve(); if (found == null) delay(250) }
        found
    }

/** Its own secure window keeps the phone out of recordings, including a screen shared during the call. [client] is asked for this
 *  call's account when the picture first opens (and again on a retry): the call screen's button stays usable while the call's service
 *  is still taking it over, instead of going dead for the rest of the call. */
@Composable internal fun CallPhoneView(client: suspend () -> BeaconClient?, call: CallState, actions: CallController, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val decoder = remember { FrameDecoder() }
    val session = remember { ViewSession(LazyViewTransport(watching = true, client), decoder, scope) }
    DisposableEffect(session) { onDispose { session.dispose(); decoder.close() } }
    LifecycleStartEffect(session) {
        session.open()
        session.background(false)
        onStopOrDispose { session.background(true) }
    }
    val ui by session.ui.collectAsStateWithLifecycle()
    val frame by decoder.shown
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize(), color = CallBackground) {
            CallPhonePanel(call, actions::mute, { actions.hangup(); onDismiss() }, { actions.camera(false) }, actions::stopScreen,
                sendText = { actions.sendText(call.callId, it) }, card = { CallApprovalOverPhone(call, call.conversationScope, actions) }) {
                AgentPhoneViewScreen(ui, frame, LocalAssistantName.current,
                    ViewActions(done = onDismiss, close = onDismiss, retry = session::retry, turnOn = session::turnOn),
                    stageColor = CallBackground,
                    onTakeControl = {
                        onDismiss()
                        context.startActivity(Intent(context, AgentPhoneActivity::class.java).putExtra(AgentPhoneActivity.EXTRA_CONTROL, true)
                            .putExtra(BeaconCallService.EXTRA_CALL_ID, call.callId))
                    })
            }
        }
    }
}


/**
 * The call's approval card over the watched phone (R3-F9): the app's own card, hidden behind this window otherwise, so Beacon can drive
 * while the owner watches. Same account check and the same decision path as the call screen; a countdown call keeps its own dialog.
 */
@Composable internal fun CallApprovalOverPhone(call: CallState, scope: dev.hermes.android.core.Scope?, actions: CallController) {
    val approval = scope?.let { call.pendingApprovalFor(it.origin, it.owner, it.profile) }?.takeIf { (it.phone?.countdown ?: 0) == 0 } ?: return
    dev.hermes.android.ui.ApprovalCardOnScreen(approval.requestId, actions::approvalCardShown)
    CallCardOverPhone(approval, call.hermesUnreachable) { decision -> actions.decideTool(call.callId, approval.requestId, approval.proposalId, decision) }
}

/** The card over the phone's own pane, never wider than it: on an unfolded phone it never reaches the call's controls beside the phone. */
@Composable internal fun CallCardOverPhone(approval: dev.hermes.android.voice.CallToolApproval, unreachable: Boolean = false, onDecision: (String) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        dev.hermes.android.CallToolApprovalDialog(approval, unreachable = unreachable, overPhone = true, widest = maxWidth, onDecision = onDecision)
    }
}

/** On an unfolded phone the call stays beside the device. The compact strip keeps the same controls within reach. */
@Composable internal fun CallPhonePanel(
    call: CallState,
    mute: () -> Unit,
    end: () -> Unit,
    stopCamera: () -> Unit,
    stopScreen: () -> Unit,
    sendText: ((String) -> Boolean)? = null,
    /** Over the top of the phone's own area, never over the strip with End call (R3-F9). */
    card: @Composable () -> Unit = {},
    phone: @Composable () -> Unit,
) {
    var typing by remember(call.callId) { mutableStateOf(false) }
    var words by remember(call.callId) { mutableStateOf("") }
    var refused by remember(call.callId) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    fun send() {
        val message = words.trim()
        if (message.isEmpty()) return
        if (sendText?.invoke(message) == true) { words = ""; typing = false; refused = false }
        else refused = true
    }
    val controls: @Composable (Boolean) -> Unit = { wide ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TextButton(mute) { Text(uiText(if (call.muted) "Unmute" else "Mute"), color = CallGoldBright) }
            if (sendText != null) IconButton({ typing = !typing }, Modifier.testTag("call-phone-ask")) {
                Icon(Icons.Rounded.ChatBubbleOutline, assistantText("Message Beacon"), tint = CallGoldBright)
            }
            if (wide) CallEndButton(end, uiText("End call"), Modifier.testTag("call-phone-end"))
            else Button(end, Modifier.testTag("call-phone-end"), colors = ButtonDefaults.buttonColors(containerColor = CallHangupRed, contentColor = CallText)) { Text(uiText("End call")) }
        }
        if (typing) {
            OutlinedTextField(words, { words = it; refused = false }, Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                .focusRequester(focus).testTag("call-phone-message"), label = { Text(assistantText("Message Beacon")) },
                singleLine = true, isError = refused,
                supportingText = if (refused) ({ Text(uiText("Try again")) }) else null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                trailingIcon = { IconButton(::send, enabled = words.isNotBlank(), modifier = Modifier.testTag("call-phone-send")) {
                    Icon(Icons.AutoMirrored.Rounded.Send, uiText("Send"))
                } })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        call.toolReceipts.lastOrNull { it.chipLabel() != null }?.let { CallToolChip(it, unreachable = call.hermesUnreachable) }
        if (call.cameraSharing || call.cameraBusy || call.cameraStopPending) {
            TextButton(stopCamera) { Text(uiText("Stop camera"), color = CallGoldBright) }
        }
        if (call.screenSharing || call.screenBusy || call.screenStopPending) {
            TextButton(stopScreen) { Text(uiText("Stop sharing"), color = CallGoldBright) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(CallBackground).safeDrawingPadding().testTag("call-phone-panel")) {
        val maxStripHeight = maxHeight * .5f
        if (maxWidth >= 700.dp && maxHeight >= 400.dp) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(.8f).fillMaxHeight().background(CallBackground)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(LocalAssistantName.current, style = MaterialTheme.typography.headlineMedium, color = androidx.compose.ui.graphics.Color.White)
                    Text(callStatusText(call, withTool = false), color = CallGoldBright)
                    VoicePresence(call, Modifier.size(160.dp), rememberNativeMotionEnabled())
                    BeaconTheme(dark = true) { controls(true) }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { phone(); card() }
            }
        } else Column(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxWidth().heightIn(max = maxStripHeight).background(CallBackground).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally) { BeaconTheme(dark = true) { controls(false) } }
            // A little air between the strip and the viewer's own strip (its Done button no longer touches this one).
            Box(Modifier.weight(1f).padding(top = 4.dp)) { phone(); card() }
        }
    }
}
