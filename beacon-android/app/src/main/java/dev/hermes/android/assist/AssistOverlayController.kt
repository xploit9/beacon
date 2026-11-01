package dev.hermes.android.assist

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import dev.hermes.android.push.serviceText
import dev.hermes.android.ui.voiceKeyConfiguredOnIo
import dev.hermes.android.updates.PluginInstallHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.exp

/**
 * Everything the overlay does, for one session. Questions go through the app's own chat send path into the assistant's chat;
 * calls start through the chat's call entry; nothing here opens the app unless the owner taps a button that says so, and on the
 * lock screen those buttons unlock first.
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
    /** "Talk live" above the orb: shown for a moment on the first summon on this phone, then never again. */
    var talkHint by mutableStateOf(false); private set
    /** Where this show's hint stands: the chip row rises only once it is over, so the chips never come up under the hint. */
    var hint by mutableStateOf(HintStage.Unknown); private set
    /** The overlay's own small preferences, loaded off the main thread the first time and read from memory after. */
    private var hints: SharedPreferences? = null
    private var replyBeat = 0L
    private var shownAt = 0L
    private var awaitingFrame = false
    private var asking: Job? = null
    private var asked: Pair<Set<String>, String>? = null
    private var liveWanted = false
    private var heldCallWindow = false
    private var hinting: Job? = null
    /** An unlock while the overlay shows over the lock screen shows what it held at once. */
    private val unlocked = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { locked = false } }
    private var listening = false
    private val dictation = AssistDictation(session.context, ::dictationEvent)

    /** How long each show took to its first dust frame, newest last; read by the device test. */
    val firstFrames = java.util.concurrent.CopyOnWriteArrayList<Long>()

    // --- the window ---

    fun shown(args: Bundle?) {
        shownAt = SystemClock.uptimeMillis()
        awaitingFrame = true
        locked = session.context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (locked && !listening) listening = runCatching {
            ContextCompat.registerReceiver(session.context, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
        refreshLocale()
        // A new show starts its hint over; a hint seen before (once the preferences are loaded) lets the chips rise as ever.
        hinting?.cancel()
        talkHint = false
        hint = if (hints?.getBoolean(TALK_HINT_SEEN, false) == true) HintStage.Over else HintStage.Unknown
        val reveal = args?.getBoolean(EXTRA_RESHOW) == true
        if (!reveal) { screen = null; screenAttached = false; draft = TextFieldValue(); typedTurns = emptyList() }
        state = reduce(state, OverlayEvent.Show(fresh = !reveal, callActive = app.calls.state.value.active))
        holdCallWindow()
    }

    /** Every drawn dust frame; the first after a show is timed, then the app's view model is made. */
    fun drawn() {
        if (!awaitingFrame) return
        awaitingFrame = false
        firstFrames += SystemClock.uptimeMillis() - shownAt
        session.tick()
        // Posted, so the first frame never waits for the view model or for warming the call session.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (state.phase != OverlayPhase.Hidden) { ensureVm().foreground(true); assistPrewarm(true); offerTalkHint() }
        }
    }

    /**
     * Once per phone, the first time the pill shows: "Talk live" above the orb for a moment, and the chips rise after it has faded
     * (playTalkHint holds the order). Whether it was shown before is read off the main thread.
     */
    private fun offerTalkHint() {
        hinting?.cancel()
        hinting = scope.launch {
            playTalkHint(
                seen = { hintPreferences().getBoolean(TALK_HINT_SEEN, false) },
                markSeen = { hints?.edit()?.putBoolean(TALK_HINT_SEEN, true)?.apply() },
                state = { state }, stage = { hint = it }, visible = { talkHint = it },
                holdMillis = TALK_HINT_MILLIS, fadeMillis = if (motionEnabled()) TALK_HINT_FADE_MILLIS.toLong() else 0L,
            )
        }
    }

    /** The overlay's preferences; the first read loads the file on the IO thread (getAll waits for it), later reads are in memory. */
    private suspend fun hintPreferences(): SharedPreferences = hints ?: withContext(Dispatchers.IO) {
        app.getSharedPreferences(HINTS, Context.MODE_PRIVATE).also { it.all }
    }.also { hints = it }

    fun summoned() { state = reduce(state, OverlayEvent.Summoned) }

    fun refreshLocale() { overlayLocale(session.context).let { if (it != locale) locale = it } }

    fun keepScreenOn(on: Boolean) = session.keepScreenOn(on)

    fun screenshot(bitmap: Bitmap?) { if (!locked) screen = bitmap }

    fun hidden() {
        dictation.cancel()
        hinting?.cancel()
        stopListening()
        heardLevel = 0f
        state = reduce(state, OverlayEvent.Hidden)
        vm?.foreground(false)
        // A hidden window keeps nothing on; leaving the flag as it is spares the next show a window relayout.
        releaseCallWindow()
        assistPrewarm(false)
    }

    fun destroyed() {
        if (state.phase != OverlayPhase.Hidden) { assistPrewarm(false); releaseCallWindow() }
        dictation.cancel()
        stopListening()
        scope.cancel()
    }

    /** Closes with the dust falling away; reduced motion closes at once. */
    fun dismiss() {
        if (state.phase == OverlayPhase.Hidden || state.phase == OverlayPhase.Dismissing) return
        dictation.cancel()
        state = reduce(state, OverlayEvent.Dismiss)
        if (!motionEnabled()) { session.hide(); return }
        scope.launch { delay(dev.hermes.android.ui.BEACON_DUST_DISMISS_MILLIS.toLong()); if (state.phase == OverlayPhase.Dismissing) session.hide() }
    }

    fun closeCard() { state = reduce(state, OverlayEvent.CloseCard); if (state.ask == null) { asking?.cancel(); asked = null } }

    fun clearLine() { state = reduce(state, OverlayEvent.Line(null)) }

    private fun stopListening() {
        if (listening) { listening = false; runCatching { session.context.unregisterReceiver(unlocked) } }
    }

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
                        liveWanted = false
                        if (state.surface != OverlaySurface.Live && state.phase != OverlayPhase.Hidden) state = reduce(state, OverlayEvent.LiveStarted)
                    }
                    // A call asked for here is still starting; any other end of a call gives the band back.
                    state.surface == OverlaySurface.Live && !liveWanted -> {
                        state = reduce(state, OverlayEvent.LiveEnded)
                        typedTurns = emptyList()
                        // The call service words its reason in the phone's language (serviceText), so that wording is recognised too.
                        if (call.failureReason.isNotBlank()) state = reduce(state, OverlayEvent.CallFailed(callReason(call.failureReason) { listOf(serviceText(app, it)) }))
                    }
                }
            }
        }
        return model
    }

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
        val question = text.trim().ifEmpty { if (screenAttached) screenQuestion else "" }
        if (question.isEmpty()) return
        if (state.surface == OverlaySurface.Live) { typeIntoCall(question); return }
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

    // --- the live call ---

    /** Starts a voice conversation here, through the same request the chat's call button makes. */
    fun startLive() {
        if (app.calls.state.value.active) { state = reduce(state, OverlayEvent.LiveStarted); return }
        dictation.cancel()
        val model = ensureVm()
        if (!signedIn(model)) { state = reduce(state, OverlayEvent.Line(OverlayLine.SignIn)); return }
        if (!granted()) { state = reduce(state, OverlayEvent.Line(OverlayLine.Microphone)); return }
        session.tick()
        liveWanted = true
        state = reduce(state, OverlayEvent.LiveStarted)
        model.callBeacon()
        // The chat may take a moment to open; if the call never starts, the band gives way again.
        scope.launch { delay(25_000); if (liveWanted && !app.calls.state.value.active) { liveWanted = false; state = reduce(reduce(state, OverlayEvent.LiveEnded), OverlayEvent.Line(OverlayLine.CallFailed)) } }
    }

    private fun startRequestedCall(model: BeaconViewModel, request: VoiceCallRequest) {
        model.consumeVoiceCallRequest(request)
        val active = model.client
        if (active == null || active.scope != request.scope || model.chat.value.sessionId != request.conversationId) { endWanted(OverlayLine.CallFailed); return }
        scope.launch {
            if (!voiceKeyConfiguredOnIo { VoiceKeyStore(app) }) { endWanted(OverlayLine.VoiceKey); return@launch }
            try { model.stopReadAloud(); app.calls.start(active.detached(), request.conversationId) }
            catch (error: CancellationException) { throw error }
            catch (error: MissingVoiceKeyException) { endWanted(OverlayLine.VoiceKey) }
            catch (error: Exception) { endWanted(OverlayLine.CallFailed) }
        }
    }

    private fun endWanted(line: OverlayLine) {
        liveWanted = false
        state = reduce(reduce(state, OverlayEvent.LiveEnded), OverlayEvent.Line(line))
    }

    fun mute() = app.calls.mute()

    fun endCall() { liveWanted = false; app.calls.hangup() }

    private fun typeIntoCall(text: String) {
        val call = app.calls.state.value
        if (!LIVE_TYPING || !call.active) return
        if (sendTypedTurn(app, call.callId, text)) { typedTurns = (typedTurns + text).takeLast(2); draft = TextFieldValue() }
        else state = reduce(state, OverlayEvent.Line(OverlayLine.NotSent))
    }

    /**
     * The overlay is the call's screen while it shows, so a call started here does not also float a bubble over it. When it closes
     * the app learns the call is in the background again, which brings back the usual bubble and notification.
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
        if (needsUnlock(action, locked)) AssistUnlockActivity.open(app, target) else openBeacon(app, target)
        session.hide()
    }

    /** "Unlock to see": the phone unlocks and the overlay comes back with the same answer, now shown. */
    fun reveal() {
        AssistUnlockActivity.reveal(app)
        session.hide()
    }

    /** The microphone screen; locked, after an unlock. */
    fun openPermissions() {
        if (needsUnlock(OverlayAction.Settings, locked)) AssistUnlockActivity.permissions(app) else openBeaconPermissions(app)
        session.hide()
    }

    fun attach() { if (screen != null) screenAttached = !screenAttached }

    internal companion object {
        /** The question a bare attached screen asks. */
        const val SCREEN_QUESTION = "What's on my screen?"
        /** The overlay's own small preferences, apart from the app's. */
        const val HINTS = "assist-overlay"
        const val TALK_HINT_SEEN = "talk_hint_seen"
        const val TALK_HINT_MILLIS = 1_500L
    }
}
