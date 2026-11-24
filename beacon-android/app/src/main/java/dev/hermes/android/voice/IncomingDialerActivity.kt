package dev.hermes.android.voice

import android.Manifest
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.*
import dev.hermes.android.agentphone.AgentPhoneActivity
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Only call controls can draw above keyguard. Private screens live in BeaconActivity behind Android's unlock. */
class IncomingDialerActivity : ComponentActivity() {
    private val dialer: IncomingDialerModel by viewModels()
    private var returnToViewer = false
    companion object { internal const val RETURN_AFTER_CALL = "dev.hermes.android.extra.RETURN_AFTER_CALL" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BeaconApplication
        val source = intent.getStringExtra("push_source").orEmpty()
        val event = intent.getStringExtra("push_event").orEmpty()
        val invitation = app.push.ringingInvitation(source, event)
        if (invitation == null && !dialer.handling) { finish(); return }
        returnToViewer = savedInstanceState?.getBoolean("watching") ?: AgentPhoneActivity.watching(app.push.sourceScope(source))
        val callerName = (invitation ?: app.push.deadlineInvitation(source, event))?.callerName.orEmpty()
        val darkBars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)
        setContent {
            val preferences by remember(app) { app.preferences.data.map(AppPreferences::read) }.collectAsStateWithLifecycle(AppPreferences())
            val call by app.calls.state.collectAsStateWithLifecycle()
            var permissionPending by rememberSaveable { mutableStateOf(false) }
            var permissionDenied by rememberSaveable { mutableStateOf(false) }
            val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                permissionPending = false
                permissionDenied = !granted
                if (granted) dialer.answer(source, event)
            }
            val answer: () -> Unit = {
                if (invitation != null && !returnToViewer && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    // Preserve the account, chat and voice-setup routing of an unlocked notification answer.
                    startActivity(Intent(this, BeaconActivity::class.java).setAction(invitation.answerAction)
                        .putExtra("push_source", source).putExtra("push_event", event)
                        .putExtra(RETURN_AFTER_CALL, true))
                    finish()
                } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) dialer.answer(source, event)
                else if (!permissionPending) { permissionPending = true; microphone.launch(Manifest.permission.RECORD_AUDIO) }
            }
            var consumed by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (!consumed && invitation != null && intent.action == invitation.answerAction) {
                    consumed = true
                    answer()
                }
            }
            val incoming by app.push.incoming.collectAsStateWithLifecycle()
            var remaining by remember { mutableLongStateOf(invitation?.actionDurationMillis() ?: 0L) }
            LaunchedEffect(dialer.handling, incoming) {
                if (!dialer.handling) {
                    val current = app.push.ringingInvitation(source, event)
                    do {
                        remaining = current?.actionDurationMillis() ?: 0L
                        if (remaining == 0L) { if (current != null) app.push.expireInvitation(source, event); finish(); break }
                        delay(minOf(remaining, 1000L))
                    } while (true)
                }
            }
            val owned = dialer.startedAt != 0L && dialer.startedAt == call.startedAt
            LaunchedEffect(dialer.startedAt, call.active, owned) {
                if (owned && call.active && returnToViewer && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
                else if (dialer.startedAt != 0L && (!call.active || !owned)) { delay(1000); finish() }
            }
            CompositionLocalProvider(LocalAssistantName provides callerName.ifBlank { preferences.assistantName }) {
                NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) {
                    BeaconTheme(dark = true) {
                        Surface(Modifier.fillMaxSize(), color = CallBackground, contentColor = Color.White) {
                            Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
                                if (dialer.startedAt == 0L) {
                                    val status = when {
                                        permissionPending -> uiText("Waiting")
                                        dialer.busy -> uiText("Connecting…")
                                        else -> uiText("Answer")
                                    }
                                    IncomingCallContent("", remaining / (INCOMING_RING_SECONDS * 1000f),
                                        when { permissionDenied -> uiText("Allow the microphone to answer."); dialer.failed -> uiText("Couldn't connect"); else -> "" },
                                        status, !dialer.busy && !permissionPending, answer, { dialer.decline(source, event) },
                                        Modifier.widthIn(max = 520.dp), fullScreen = true)
                                } else {
                                    Column(Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(LocalAssistantName.current, style = MaterialTheme.typography.headlineLarge, color = CallText)
                                        Text(if (owned && call.active) callStatusText(call, withTool = false) else uiText("Call ended."),
                                            color = CallGoldBright, textAlign = TextAlign.Center)
                                        var now by remember(dialer.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
                                        LaunchedEffect(owned, call.active) {
                                            if (owned && call.active) while (true) {
                                                now = System.currentTimeMillis()
                                                delay(1000)
                                            }
                                        }
                                        Text(callTimerLabel(dialer.startedAt, call.endedAt.takeIf { !call.active && it > 0L } ?: now), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
                                        VoicePresence(if (owned) call else CallState(), Modifier.size(156.dp), rememberNativeMotionEnabled())
                                        if (owned && call.active) {
                                            // The same round controls as the call screen: gold when muted, and the one red end-call circle.
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                                                CallControl(uiText(if (call.muted) "Unmute" else "Mute"), uiText(if (call.muted) "Unmute" else "Mute"),
                                                    if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "beacon-lock-mute", true, call.muted,
                                                    if (call.muted) uiText("Microphone muted") else uiText("Microphone active"), { app.calls.mute() }, slot = CallCompactHangupSize)
                                                CallControl("", uiText("End call"), Icons.Rounded.CallEnd, "beacon-lock-end", true, false, "", { app.calls.hangup() },
                                                    hangup = true, size = CallCompactHangupSize)
                                            }
                                            TextButton({ openPrivateCall(call.callId) }, enabled = call.callId.isNotBlank()) { Text(uiText("Open call")) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); (application as BeaconApplication).calls.dialerVisible = true }
    override fun onStop() { (application as BeaconApplication).calls.dialerVisible = false; super.onStop() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("watching", returnToViewer)
    }

    private fun openPrivateCall(id: String) {
        fun open() {
            val call = (application as BeaconApplication).calls.state.value
            if (call.callId == id && call.active) startActivity(Intent(this, BeaconActivity::class.java)
                .putExtra(BeaconCallService.EXTRA_CALL_ID, id)
                .putExtra(RETURN_AFTER_CALL, true))
            finish()
        }
        val guard = getSystemService(KeyguardManager::class.java)
        if (guard.isKeyguardLocked) guard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = open()
        }) else open()
    }
}

internal class IncomingDialerModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BeaconApplication
    var busy by mutableStateOf(false)
        private set
    var startedAt by mutableLongStateOf(0L)
        private set
    var failed by mutableStateOf(false)
        private set
    val handling get() = busy || startedAt != 0L

    fun answer(source: String, event: String) = act {
        check(app.push.authorizeKeepConnectedAction(source, event, "answer"))
        app.calls.answer(source, event)
        startedAt = app.calls.state.value.startedAt
    }

    fun decline(source: String, event: String) = act { declineIncoming(app, source, event) }

    private fun act(action: suspend () -> Unit) {
        if (handling) return
        busy = true
        failed = false
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { busy = false }
        }
    }
}
