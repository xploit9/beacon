package dev.hermes.android.assist

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.ViewModelProvider
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.VoiceCallRequest
import dev.hermes.android.data.MissingVoiceKeyException
import dev.hermes.android.data.NativeDeviceRemoval
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.phone.deviceLockedNow
import dev.hermes.android.push.serviceText
import dev.hermes.android.ui.voiceKeyConfiguredOnIo
import dev.hermes.android.updates.PluginInstallHub
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.CallTrust
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp

/**
 * Everything the overlay does, for one session. Questions go through the app's own chat send path into the assistant's chat;
 * calls start through the chat's call entry, locked or not (what a locked conversation may do is voice/CallTrust.kt's rule); nothing
 * here opens the app unless the owner taps a button that says so, and on the lock screen those buttons unlock first.
 */
/** Beacon's app language when one is set (Android 13+), else the phone's. */
internal fun overlayLocale(context: android.content.Context): java.util.Locale {
    if (android.os.Build.VERSION.SDK_INT >= 33) {
        val chosen = runCatching { context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales }.getOrNull()
        // An empty list, or the undetermined "und", means the owner chose no language for Beacon.
        val first = chosen?.takeIf { !it.isEmpty }?.get(0)
        if (first != null && first.language.isNotEmpty() && first.language != "und") return first
    }
    return context.resources.configuration.locales[0] ?: java.util.Locale.getDefault()
}

/** Whether a tap on the dim outside the glass closes the overlay: not while a conversation is live, whose own controls end it. */
internal fun outsideTapCloses(surface: OverlaySurface): Boolean = surface != OverlaySurface.Live

internal class AssistOverlayController(private val session: BeaconAssistSession) {
    val app get() = session.app
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableStateOf(AssistOverlayState()); private set
    var locked by mutableStateOf(false); private set
    var screen by mutableStateOf<Bitmap?>(null); private set
    var screenAttached by mutableStateOf(false)
    var draft by mutableStateOf(TextFieldValue())
    /** The app's view model, made after the first frame so the window opens before any of it loads. */
    var vm by mutableStateOf<BeaconViewModel?>(null); private set
    /** The microphone level while dictating, 0..1. */
    var heardLevel by mutableFloatStateOf(0f); private set
    /**
     * The language the overlay speaks: Beacon's own app language when the owner picked one, otherwise the phone's. The overlay
     * window does not receive the app's language override the way an activity does, so it is read here, on every show.
     */
    var locale by mutableStateOf(overlayLocale(session.context)); private set
    /** Typed turns sent into the live call, shown in its captions as the owner's words. */
    var typedTurns by mutableStateOf(emptyList<String>()); private set
    private var replyBeat = 0L
    private var shownAt = 0L
    private var awaitingFrame = false
    /** A fresh show talks as soon as its first frame is drawn (see [talksOnShow]); a re-show keeps what was on screen. */
    private var talkOnFrame = false
    /** How many private requests a locked conversation had turned away when this show began (see [askedToUnlock]). */
    private var asksSeen = 0
    private var watchingAsks = false
    private var asking: Job? = null
    private var asked: Pair<Set<String>, String>? = null
    /** The conversation this overlay asks for and runs: asked for, running here, or cancelled while it was starting. */
    private val live = AssistLiveBook()
    private var heldCallWindow = false
    /**
     * Keeps [locked] true to the lock screen while the overlay shows: the screen turning off counts as locked at once (the lock screen
     * follows it, but a read right then can still say unlocked); turning on and an unlock read it again, so an unlock over the lock
     * screen shows what the overlay held at once.
     */
    private val lockWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            locked = intent.action == Intent.ACTION_SCREEN_OFF || keyguardLocked()
            if (locked) lockedWhileOpen() else unlockedWhileOpen()
        }
    }
    private var listening = false
    private val dictation = AssistDictation(session.context, ::dictationEvent)

    /** How long each show took to its first dust frame, newest last; read by the device test. */
    val firstFrames = java.util.concurrent.CopyOnWriteArrayList<Long>()

    // --- the window ---

    fun shown(args: Bundle?) {
        shownAt = SystemClock.uptimeMillis()
        val at = shownAt
        awaitingFrame = true
        locked = keyguardLocked()
        if (!listening) listening = runCatching {
            val actions = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT) }
            ContextCompat.registerReceiver(session.context, lockWatch, actions, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        refreshLocale()
        val reveal = args?.getBoolean(EXTRA_RESHOW) == true
        if (!reveal) { screen = null; screenAttached = false; draft = TextFieldValue(); typedTurns = emptyList() }
        talkOnFrame = talksOnShow(reshow = reveal)
        live.shown()
        asksSeen = CallTrust.lockedAsks.value
        if (!watchingAsks) { watchingAsks = true; scope.launch { CallTrust.lockedAsks.collect { askedToUnlock(it) } } }
        state = reduce(state, OverlayEvent.Show(fresh = !reveal, callActive = app.calls.state.value.active))
        holdCallWindow()
        watchLock()
        // The first dust frame starts the conversation (see drawn); a window slow to draw one (a screen that comes on late) must never leave the show with nothing started.
        scope.launch { delay(FIRST_FRAME_FALLBACK_MILLIS); if (awaitingFrame && shownAt == at && state.phase != OverlayPhase.Hidden) { awaitingFrame = false; settle() } }
    }

    /**
     * Every drawn dust frame; the first after a show is timed, then the app's view model is made and, for a fresh show, the live
     * conversation starts (startLive asks for what is missing, a microphone or a sign-in, instead of failing quietly), locked or not.
     */
    fun drawn() {
        if (!awaitingFrame) return
        awaitingFrame = false
        firstFrames += SystemClock.uptimeMillis() - shownAt
        session.tick()
        // Posted, so the first frame never waits for the view model, for warming the call session or for starting the conversation.
        android.os.Handler(android.os.Looper.getMainLooper()).post { settle() }
    }

    /** What the first frame of a show leads to: see [firstFrame]. The lock is read live, so a lock that came up since the show is not missed. */
    private fun settle() {
        if (state.phase == OverlayPhase.Hidden) return
        val talks = talkOnFrame && state.phase != OverlayPhase.Dismissing
        when (firstFrame(overlayLocked(locked, keyguardLocked()), app.calls.state.value.active, talks)) {
            // Locked, the conversation starts at once like any other; only the warm session (it carries the owner's memory) is left out.
            FirstFrame.TalkLocked -> { talkOnFrame = false; ensureVm().foreground(true); startLive() }
            FirstFrame.Hold -> Unit
            FirstFrame.Warm -> warm()
            FirstFrame.WarmAndTalk -> { talkOnFrame = false; warm(); startLive() }
        }
    }

    private fun warm() { ensureVm().foreground(true); assistPrewarm(true) }

    /**
     * The phone locked (or its screen went off) with the overlay open. A conversation, one that runs or one on its way, carries on: the
     * call reads the lock itself when it starts, so one that begins locked is told nothing private. Only dictation stops, because what
     * it hears ends up in the assistant's chat.
     */
    private fun lockedWhileOpen() {
        if (state.phase == OverlayPhase.Hidden || state.phase == OverlayPhase.Dismissing) return
        dictation.cancel()
        heardLevel = 0f
        state = reduce(state, OverlayEvent.DictationStopped)
    }

    /**
     * The phone unlocked with the overlay open: the unlock line, if it is up, goes. The conversation carries on by itself (the call
     * sees the unlock and is set up again with the owner's memory, see CallTrust), so nothing starts here.
     */
    private fun unlockedWhileOpen() {
        if (state.line != OverlayLine.Unlock || state.phase == OverlayPhase.Hidden || state.phase == OverlayPhase.Dismissing) return
        state = reduce(state, OverlayEvent.Line(null))
    }

    /**
     * The unlock this overlay asked for succeeded (AssistUnlockActivity says so): the lock flag and the unlock line follow it at once,
     * whatever the lock broadcasts did. False when the overlay is not showing, so the caller brings it back.
     */
    fun unlockSeen(): Boolean {
        if (state.phase == OverlayPhase.Hidden) return false
        locked = keyguardLocked()
        if (!locked) unlockedWhileOpen()
        return true
    }

    /** The live conversation turned something private away ([asks] so far): the same line the model says, with Unlock, while the phone is locked. */
    private fun askedToUnlock(asks: Int) {
        if (state.phase != OverlayPhase.Hidden && state.phase != OverlayPhase.Dismissing &&
            unlockAsked(asks, asksSeen, overlayLocked(locked, keyguardLocked()), state.surface == OverlaySurface.Live)) state = reduce(state, OverlayEvent.Line(OverlayLine.Unlock))
        asksSeen = asks
    }

    fun summoned() { state = reduce(state, OverlayEvent.Summoned) }

    fun refreshLocale() { overlayLocale(session.context).let { if (it != locale) locale = it } }

    fun keepScreenOn(on: Boolean) = session.keepScreenOn(on)

    fun screenshot(bitmap: Bitmap?) { if (!overlayLocked(locked, keyguardLocked())) screen = bitmap }

    private var lockLook: Job? = null

    /**
     * While the overlay shows, the lock is read again every second with the screen on, so a broadcast the phone held back (MagicOS
     * does) never leaves an unlocked phone showing the locked overlay. A screen that is off is left to the broadcasts.
     */
    private fun watchLock() {
        lockLook?.cancel()
        lockLook = scope.launch {
            val power = session.context.getSystemService(android.os.PowerManager::class.java)
            while (true) {
                delay(LOCK_LOOK_MILLIS)
                if (power?.isInteractive != true) continue
                val now = keyguardLocked()
                if (now != locked) { locked = now; if (now) lockedWhileOpen() else unlockedWhileOpen() }
            }
        }
    }

    fun hidden() {
        lockLook?.cancel()
        dictation.cancel()
        // Whatever closes the overlay ends the conversation it started, before the window is let go (which would float the call).
        closeLive()
        talkOnFrame = false
        stopListening()
        heardLevel = 0f
        state = reduce(state, OverlayEvent.Hidden)
        vm?.foreground(false)
        // A hidden window keeps nothing on; leaving the flag as it is spares the next show a window relayout.
        releaseCallWindow()
        assistPrewarm(false)
    }

    fun destroyed() {
        closeLive()
        if (state.phase != OverlayPhase.Hidden) { assistPrewarm(false); releaseCallWindow() }
        dictation.cancel()
        stopListening()
        scope.cancel()
    }

    /** Closes with the dust falling away; reduced motion closes at once. Nothing keeps listening from the first moment of it. */
    fun dismiss() {
        if (state.phase == OverlayPhase.Hidden || state.phase == OverlayPhase.Dismissing) return
        dictation.cancel()
        talkOnFrame = false
        closeLive()
        state = reduce(state, OverlayEvent.Dismiss)
        if (!motionEnabled()) { session.hide(); return }
        scope.launch { delay(dev.hermes.android.ui.BEACON_DUST_DISMISS_MILLIS.toLong()); if (state.phase == OverlayPhase.Dismissing) session.hide() }
    }

    /**
     * A tap on the dim around Beacon's glass: it closes the overlay, except while a conversation is live ([outsideTapCloses]). A stray
     * tap beside the glass then does nothing; Back, a swipe down and the orb still end the conversation and close the overlay.
     */
    fun outsideTap() { if (outsideTapCloses(state.surface)) dismiss() }

    fun closeCard() { state = reduce(state, OverlayEvent.CloseCard); if (state.ask == null) { asking?.cancel(); asked = null } }

    fun clearLine() { state = reduce(state, OverlayEvent.Line(null)) }

    private fun stopListening() {
        if (listening) { listening = false; runCatching { session.context.unregisterReceiver(lockWatch) } }
    }

    /** The lock screen is up right now: the one live read of every private surface of the overlay (see [overlayLocked]). */
    private fun keyguardLocked() = deviceLockedNow(session.context)

    fun motionEnabled() = android.provider.Settings.Global.getFloat(session.context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    /** The level the dust answers: the voice while dictating, otherwise a beat that rises with each piece of the reply. */
    fun dustLevel(): Float = if (state.dictating) heardLevel else exp(-(SystemClock.uptimeMillis() - replyBeat) / 380f).coerceIn(0f, 1f)

    // --- asking ---

    private fun granted() = ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureVm(): BeaconViewModel {
        vm?.let { return it }
        // The app's own screen keeps the device-removal and plugin-install hooks; the overlay's model must never take them over.
        val removal = NativeDeviceRemoval.onForgetScreens
        val install = PluginInstallHub.controller
        // Opened on the assistant's chat from the start, so connecting never makes an empty chat for the overlay.
        val model = ViewModelProvider(session.viewModelStore, SavedStateViewModelFactory(app, session, bundleOf("tab" to "Beacon")))[BeaconViewModel::class.java]
        NativeDeviceRemoval.onForgetScreens = removal
        PluginInstallHub.controller = install
        vm = model
        scope.launch { model.chat.collect { chat -> follow(chat) } }
        scope.launch { model.voiceCallRequest.collect { request -> request?.let { startRequestedCall(model, it) } } }
        scope.launch {
            app.calls.state.collect { call ->
                when {
                    call.active -> {
                        // Closed while it was starting: it ends the moment it exists, so nothing listens with no overlay on screen.
                        if (live.callActive(call.startedAt) == LiveActive.HangUp) { app.calls.hangup(); return@collect }
                        if (call.state in setOf("listening", "thinking", "speaking")) live.heard()
                        if (state.surface != OverlaySurface.Live && state.phase != OverlayPhase.Hidden) state = reduce(state, OverlayEvent.LiveStarted)
                    }
                    // A call asked for here is still starting; any other end of a call gives the pill its orb back. A closing overlay
                    // keeps its captions until the dust has fallen away (the reducer lets go of the live surface when it is hidden).
                    !live.wanted -> {
                        // A conversation started here that failed before it got going is tried once more, with the same pill.
                        if (call.failureReason.isNotBlank() && retryable(call.failureReason) && live.retryOnce(running = true)) { retryLive(model); return@collect }
                        live.callEnded()
                        if (state.surface == OverlaySurface.Live && state.phase != OverlayPhase.Dismissing && state.phase != OverlayPhase.Hidden) {
                            state = reduce(state, OverlayEvent.LiveEnded)
                            typedTurns = emptyList()
                            // The call service words its reason in the phone's language (serviceText), so that wording is recognised too.
                            if (call.failureReason.isNotBlank()) state = reduce(state, OverlayEvent.CallFailed(callReason(call.failureReason) { listOf(serviceText(app, it)) }))
                        }
                    }
                }
            }
        }
        return model
    }

    /** A failure another try can fix: not a sign-in that expired or a microphone that failed. */
    private fun retryable(reason: String): Boolean =
        callReason(reason) { listOf(serviceText(app, it)) }.let { it != CallReason.SignInExpired && it != CallReason.MicFailed }

    private fun signedIn(model: BeaconViewModel): Boolean {
        val ui = model.ui.value
        return ui.restoringSession || (ui.authenticated && !ui.setupWizard)
    }

    /** The assistant's chat is open and quiet: the same rule the chat's call button and send use. */
    private fun ready(model: BeaconViewModel): Boolean {
        val ui = model.ui.value
        val chat = model.chat.value
        return ui.authenticated && !ui.beaconOpening && chat.sessionId.isNotBlank() && chat.raw.length() > 0 && chat.streamId.isBlank() && model.isBeaconConversation()
    }

    /**
     * Asks [text] in writing; the answer streams into the card. In a live call it is typed into the call instead. [screenQuestion]
     * is what an attached screen asks on its own, in the owner's language.
     */
    fun ask(text: String = draft.text, screenQuestion: String = SCREEN_QUESTION) {
        // The screen only goes to a written question; a typed turn into the conversation is the words alone.
        if (state.surface == OverlaySurface.Live) { text.trim().takeIf { it.isNotEmpty() }?.let(::typeIntoCall); return }
        // Locked, nothing is sent: the text box is inert, and words typed or dictated before the lock came up go nowhere.
        if (needsUnlock(OverlayAction.Ask, overlayLocked(locked, keyguardLocked()))) return
        val question = text.trim().ifEmpty { if (screenAttached) screenQuestion else "" }
        if (question.isEmpty()) return
        val model = ensureVm()
        if (!signedIn(model)) { state = reduce(state, OverlayEvent.Line(OverlayLine.SignIn)); return }
        session.tick()
        val withScreen = screenAttached && screen != null
        state = reduce(state, OverlayEvent.Asked(question, withScreen, sessionId = ""))
        draft = TextFieldValue()
        asking?.cancel()
        asked = null
        asking = scope.launch {
            val opened = awaitAssistantChat(model)
            if (opened == null) { notSent(); return@launch }
            if (opened.isEmpty()) { notSent(); state = reduce(state, OverlayEvent.Line(OverlayLine.SignIn)); return@launch }
            if (withScreen) attachScreen(model)
            val before = model.chat.value.messages.mapTo(HashSet()) { it.id }
            asked = before to opened
            state = state.copy(ask = state.ask?.copy(sessionId = opened))
            // The owner may have an unsent draft in the assistant's chat in the app: it is put back right after this send.
            val kept = model.composer.value
            // The app only sends while its screen is in front; the overlay is that screen now.
            model.foreground(true)
            model.composer(TextFieldValue(question, TextRange(question.length)))
            try { model.send().join() } finally { model.composer(kept) }
            // Hermes holds the question once it comes back, starts a reply, or the send is still settling; otherwise it was not sent.
            val accepted = withTimeoutOrNull(4_000) { while (answerFrom(model.chat.value, before, opened)?.accepted != true) delay(50); true }
            if (accepted == null) notSent()
        }
    }

    /** The question didn't reach Hermes: the card says so, and the question goes back in the field to send again. */
    private fun notSent() {
        val question = state.ask?.question.orEmpty()
        state = reduce(state, OverlayEvent.NotSent)
        val restored = draftAfterNotSent(draft.text, question)
        if (restored != draft.text) draft = TextFieldValue(restored, TextRange(restored.length))
    }

    /** "Try again": in a call, the typed words still in the field; otherwise the question that didn't go. */
    fun retry() = ask(if (state.surface == OverlaySurface.Live) draft.text else state.ask?.question ?: draft.text)

    /** The assistant chat's id once it is open and quiet; empty when nobody is signed in; null when it did not open in time. */
    private suspend fun awaitAssistantChat(model: BeaconViewModel): String? = withTimeoutOrNull(20_000) {
        var id: String? = null
        while (id == null) {
            val ui = model.ui.value
            id = when {
                !ui.restoringSession && (!ui.authenticated || ui.setupWizard) -> ""
                ui.beaconError.isNotBlank() && !ui.beaconOpening -> break
                ready(model) -> model.chat.value.sessionId
                else -> { delay(50); null }
            }
        }
        id
    }

    private suspend fun attachScreen(model: BeaconViewModel) {
        val bitmap = screen ?: return
        val uri = withContext(Dispatchers.IO) { runCatching {
            val dir = java.io.File(app.cacheDir, "camera").apply { mkdirs() }
            val file = java.io.File(dir, "assist-screen-${System.currentTimeMillis()}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        }.getOrNull() } ?: return
        model.upload(uri).join()
        screenAttached = false
    }

    /** Follows the reply to the question in flight, and what waits in the assistant's chat. */
    private fun follow(chat: dev.hermes.android.ChatState) {
        val (before, sessionId) = asked ?: return
        val progress = answerFrom(chat, before, sessionId) ?: return
        val ask = state.ask ?: return
        if (progress.answer.length > ask.answer.length) replyBeat = SystemClock.uptimeMillis()
        state = reduce(state, OverlayEvent.Progress(progress.answer, progress.streaming || !progress.accepted, progress.accepted))
        // Answered after all: the copy of the question put back in the field goes.
        if (ask.failed && state.ask?.failed == false) draftAfterLateAnswer(draft.text, ask.question).let { if (it != draft.text) draft = TextFieldValue(it) }
        val waiting = chat.approval != null || chat.clarification != null
        if (waiting && state.line == null) state = reduce(state, OverlayEvent.Line(OverlayLine.Waiting))
        if (!waiting && state.line == OverlayLine.Waiting) state = reduce(state, OverlayEvent.Line(null))
    }

    // --- the mic: dictation with a written answer ---

    fun dictate() {
        if (state.dictating) { dictation.stop(); return }
        // Locked, nothing is heard: no microphone question, no recognizer.
        if (needsUnlock(OverlayAction.Dictate, overlayLocked(locked, keyguardLocked()))) return
        if (!granted()) { state = reduce(state, OverlayEvent.Line(OverlayLine.Microphone)); return }
        ensureVm()
        if (!dictation.start()) { state = reduce(state, OverlayEvent.Line(OverlayLine.NoRecognizer)); return }
        session.tick()
        state = reduce(state, OverlayEvent.DictationStarted)
    }

    private fun dictationEvent(event: DictationEvent) {
        when (event) {
            is DictationEvent.Level -> heardLevel = event.value
            is DictationEvent.Heard -> state = reduce(state, OverlayEvent.Heard(event.text))
            is DictationEvent.Done -> {
                heardLevel = 0f
                state = reduce(state, OverlayEvent.DictationStopped)
                if (event.text.isNotBlank()) ask(event.text) else state = reduce(state, OverlayEvent.Line(OverlayLine.NotHeard))
            }
            is DictationEvent.Failed -> {
                heardLevel = 0f
                state = reduce(state, OverlayEvent.DictationStopped)
                state = reduce(state, OverlayEvent.Line(when (event.code) {
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> OverlayLine.Microphone
                    android.speech.SpeechRecognizer.ERROR_CLIENT -> null
                    else -> OverlayLine.NotHeard
                }))
            }
        }
    }

    // --- the live conversation ---

    /**
     * Starts a voice conversation here, through the same request the chat's call button makes, locked or not (the call service reads the
     * lock as it starts and a locked one is told nothing private). A missing sign-in or microphone is a line with its one action
     * instead (see [liveStart]), so no start is a dead end.
     */
    fun startLive() {
        dictation.cancel()
        val model = ensureVm()
        when (liveStart(app.calls.state.value.active, signedIn(model), granted())) {
            LiveStart.Join -> state = reduce(state, OverlayEvent.LiveStarted)
            LiveStart.SignIn -> state = reduce(state, OverlayEvent.Line(OverlayLine.SignIn))
            LiveStart.Microphone -> state = reduce(state, OverlayEvent.Line(OverlayLine.Microphone))
            LiveStart.Start -> {
                // Already on its way (an unlock seen here, then the unlock's own fresh show): not asked for twice.
                if (live.wanted) { state = reduce(state, OverlayEvent.LiveStarted); return }
                session.tick()
                live.requested()
                state = reduce(state, OverlayEvent.LiveStarted)
                askForCall(model)
            }
        }
    }

    /** The one automatic second try: the conversation is asked for again after a moment, unless the owner closed the overlay meanwhile. */
    private fun retryLive(model: BeaconViewModel) {
        live.requested(retry = true)
        askForCall(model, after = RETRY_DELAY_MILLIS)
    }

    private var giveUp: Job? = null

    private fun askForCall(model: BeaconViewModel, after: Long = 0) {
        scope.launch {
            if (after > 0) delay(after)
            // A session that is still restoring (the first show after the app's process started) is waited for: the chat's call
            // entry gives up on a signed-out chat. If it ends up signed out, the line says so.
            val ui = withTimeoutOrNull(RESTORE_WAIT_MILLIS) { model.ui.first { !it.restoringSession } }
            if (!live.wanted) return@launch
            if (ui == null || !ui.authenticated || ui.setupWizard) { endWanted(OverlayLine.SignIn); return@launch }
            model.callBeacon()
        }
        // The chat may take a moment to open; if the conversation never starts, the pill gets its orb back with a line.
        giveUp?.cancel()
        giveUp = scope.launch { delay(25_000); if (live.wanted && !app.calls.state.value.active) { live.gaveUp(); state = reduce(reduce(state, OverlayEvent.LiveEnded), OverlayEvent.Line(OverlayLine.CallFailed)) } }
    }

    private fun startRequestedCall(model: BeaconViewModel, request: VoiceCallRequest) {
        model.consumeVoiceCallRequest(request)
        // Closed, or ended with the orb, while the chat opened: nothing starts.
        if (!live.wanted) return
        val active = model.client
        if (active == null || active.scope != request.scope || model.chat.value.sessionId != request.conversationId) {
            if (live.retryOnce(running = false)) retryLive(model) else endWanted(OverlayLine.CallFailed)
            return
        }
        scope.launch {
            if (!voiceKeyConfiguredOnIo { VoiceKeyStore(app) }) { endWanted(OverlayLine.VoiceKey); return@launch }
            if (!live.wanted) return@launch
            // From here the start is in flight: a close that comes before it returns must end the call it produces (see AssistLiveBook).
            live.launched(System.currentTimeMillis())
            var returned = false
            try { model.stopReadAloud(); app.calls.start(active.detached(), request.conversationId, dev.hermes.android.phone.PhoneOrigin.Overlay); returned = true }
            catch (error: CancellationException) { throw error }
            catch (error: MissingVoiceKeyException) { endWanted(OverlayLine.VoiceKey) }
            catch (error: Exception) { if (live.retryOnce(running = false)) retryLive(model) else endWanted(OverlayLine.CallFailed) }
            finally {
                val call = app.calls.state.value
                if (live.settled(returned, call.active, call.startedAt)) app.calls.hangup()
            }
        }
    }

    private fun endWanted(line: OverlayLine) {
        if (!live.gaveUp()) return
        state = reduce(reduce(state, OverlayEvent.LiveEnded), OverlayEvent.Line(line))
    }

    /**
     * "Unlock": asks Android for the unlock (through [AssistUnlockActivity], "carry on"). The overlay and its conversation stay up
     * meanwhile; a cancelled or failed unlock leaves them as they are, with the line and this action to ask again, and after it the
     * overlay comes back with the same conversation, now trusted.
     */
    fun unlock() { AssistUnlockActivity.carryOn(app) }

    /** The orb during the conversation: ends it. A call that runs hangs up; one still starting ends the moment it exists. */
    fun endCall() {
        if (live.end()) state = reduce(state, OverlayEvent.LiveEnded)
        app.calls.hangup()
    }

    /** Closing the overlay ends a conversation it started, so nothing keeps listening hidden; a call the app is on carries on there. */
    private fun closeLive() { if (live.closing()) endCall() }

    /** The owner's answer to the approval the conversation waits on, from its card in the overlay. Locked, only no can be said. */
    fun decide(approval: CallToolApproval, decision: String) {
        // Read live as well: the phone may have locked since the card was drawn, whatever the last broadcast said.
        if (decision != "deny" && overlayLocked(locked, keyguardLocked()) && !dev.hermes.android.voice.LockScreenAccess.allowed(session.context)) return
        app.calls.decideTool(app.calls.state.value.callId, approval.requestId, approval.proposalId, decision)
    }

    private fun typeIntoCall(text: String) {
        val call = app.calls.state.value
        if (!LIVE_TYPING || !call.active) return
        if (sendTypedTurn(app, call.callId, text)) { typedTurns = (typedTurns + text).takeLast(2); draft = TextFieldValue() }
        else state = reduce(state, OverlayEvent.Line(OverlayLine.NotSent))
    }

    /**
     * The overlay is the conversation's one surface while it shows, so the app's own call window (the floating bubble, the call
     * screen) is kept away: the app is told its call screen is visible. When the overlay closes the app learns it is in the background again.
     */
    private fun holdCallWindow() {
        if (!app.calls.uiVisible) { heldCallWindow = true; app.calls.uiVisible = true }
    }

    private fun releaseCallWindow() {
        if (heldCallWindow) { heldCallWindow = false; app.calls.uiVisible = false }
    }

    // --- leaving the overlay ---

    /** Opens the app for [target]; locked, the phone unlocks first. The overlay steps aside either way. */
    fun open(target: AssistTarget, action: OverlayAction = OverlayAction.OpenInBeacon) {
        if (needsUnlock(action, overlayLocked(locked, keyguardLocked()))) AssistUnlockActivity.open(app, target) else openBeacon(app, target)
        session.hide()
    }

    /** "Unlock to see": the phone unlocks and the overlay comes back with the same answer, now shown. */
    fun reveal() {
        AssistUnlockActivity.reveal(app)
        session.hide()
    }

    /** The microphone screen; locked, after an unlock. */
    fun openPermissions() {
        if (needsUnlock(OverlayAction.Settings, overlayLocked(locked, keyguardLocked()))) AssistUnlockActivity.permissions(app) else openBeaconPermissions(app)
        session.hide()
    }

    fun attach() { if (screen != null) screenAttached = !screenAttached }

    internal companion object {
        /** The question a bare attached screen asks. */
        const val SCREEN_QUESTION = "What's on my screen?"
        /** How long a start waits for the sign-in to be restored before it says to sign in. */
        const val RESTORE_WAIT_MILLIS = 15_000L
        /** A show whose window has drawn no dust frame by then settles anyway: starting never depends on the first frame. */
        const val FIRST_FRAME_FALLBACK_MILLIS = 1_000L
        /** The pause before the automatic second try of a start that failed: long enough for a blink in the network or Android to pass. */
        const val RETRY_DELAY_MILLIS = 700L
        /** How often the overlay reads the lock again while it shows. */
        const val LOCK_LOOK_MILLIS = 1_000L
    }
}
