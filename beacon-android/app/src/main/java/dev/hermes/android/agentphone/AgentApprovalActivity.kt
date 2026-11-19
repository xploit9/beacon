package dev.hermes.android.agentphone

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.preferences
import dev.hermes.android.ui.BeaconDustCore
import dev.hermes.android.ui.Glass
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The agent phone's question, over whatever is on screen (EMU-DESIGN §5.4: "a FLAG_SECURE dialog activity, delivered by push when
 * Beacon is closed"). The window is secure before any content, so no screenshot, recording, cast or recents thumbnail holds an amount,
 * a site or a code. It is not shown over the lock screen: a notification tapped there asks for the unlock first, as a payment should.
 * It reads the records itself (the account of the push it came from, or Beacon's own sign-in), asks them one at a time, decisions
 * first, and closes when none is left. Back closes it without an answer: the question stays open on Hermes and in the notification.
 */
class AgentApprovalActivity : ComponentActivity() {
    private val model: ApprovalDialogModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        // The model outlives a turn or a fold (it opens once); after the process was ended it opens again from the same intent.
        model.open(intent.getStringExtra(EXTRA_SOURCE), intent.getStringExtra(EXTRA_ID))
        setContent {
            val app = application as BeaconApplication
            val preferences by remember(app) { app.preferences.data.map(AppPreferences::read) }.collectAsStateWithLifecycle(AppPreferences())
            val ui by model.ui.collectAsStateWithLifecycle()
            val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            LaunchedEffect(ui.finished) { if (ui.finished) finish() }
            BackHandler { model.putOff(); finish() }
            CompositionLocalProvider(LocalAssistantName provides preferences.assistantName) {
                NativeLocalization(nativeLanguageFor(LocalConfiguration.current.locales[0])) {
                    BeaconTheme(dark, preferences.skin) {
                        ApprovalDialogScreen(ui, onAnswer = model::answer, onClose = model::close)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.open(intent.getStringExtra(EXTRA_SOURCE), intent.getStringExtra(EXTRA_ID))
    }

    override fun onStart() {
        super.onStart()
        visible = true
        model.watch(lifecycle)
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    companion object {
        internal const val EXTRA_SOURCE = "dev.hermes.android.agentphone.SOURCE"
        internal const val EXTRA_ID = "dev.hermes.android.agentphone.ID"

        /** True while the dialog is on screen, so an open Beacon doesn't start a second one. */
        @Volatile internal var visible = false

        /** Opens the dialog: [source] is the push binding the question came through (null: Beacon's own sign-in), [id] the record to ask first. */
        internal fun intent(context: Context, source: String?, id: String?): Intent =
            Intent(context, AgentApprovalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { source?.let { putExtra(EXTRA_SOURCE, it) }; id?.let { putExtra(EXTRA_ID, it) } }
    }
}

/** What the dialog shows: a card, a line with Close (no sign-in, or the question asked for is gone), or nothing yet. */
internal data class DialogUi(val card: ApprovalCardUi? = null, val line: String? = null, val loading: Boolean = true, val finished: Boolean = false)

/** How long a taken answer stays on the card before the next question: a yes briefly, a no long enough to read why nothing ran. */
internal fun answeredShowMillis(phase: CardPhase.Answered): Long = if (phase.approved) 900 else 2_200

/**
 * The dialog's state across a turn or a fold. It keeps the card on screen ([current]) even after Hermes drops the record, so the
 * owner sees why a question went away instead of watching it vanish.
 */
internal class ApprovalDialogModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BeaconApplication
    private val _ui = MutableStateFlow(DialogUi())
    val ui: StateFlow<DialogUi> = _ui.asStateFlow()
    private var link: ApprovalLink? = null
    private var source: String? = null
    private var focus: String? = null
    private var current: AgentApproval? = null
    private var phase: CardPhase = CardPhase.Asking
    private var shownAt = 0L
    private var answeredAt = 0L
    private var watcher: Job? = null
    private var opened = false
    private var readOnce = false
    private var everShown = false
    /** "Open the phone" was taken: the setup view is on top, and the dialog closes now instead of showing its answered state. */
    private var closing = false

    private fun now() = SystemClock.elapsedRealtime()

    fun open(source: String?, focus: String?) {
        if (source != null) this.source = source
        if (focus != null) this.focus = focus
        if (opened) { if (link != null) viewModelScope.launch { refresh() }; return }
        opened = true
        viewModelScope.launch {
            link = AgentApprovals.link(app, this@ApprovalDialogModel.source)
            if (link == null) { _ui.value = DialogUi(line = "Open Beacon and sign in again to answer.", loading = false); return@launch }
            refresh()
        }
    }

    /** While the dialog is started: the clock for the time-left line every quarter second, and a fresh read of Hermes every 4 s. */
    // Called from onStart, but one watcher per activity: the guard below skips a second start, and the job ends with that lifecycle.
    @SuppressLint("RepeatOnLifecycleWrongUsage")
    fun watch(lifecycle: Lifecycle) {
        if (watcher?.isActive == true) return
        watcher = viewModelScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var lastRead = now()
                while (isActive) {
                    if (link != null && now() - lastRead >= READ_EVERY_MS) { refresh(); lastRead = now() }
                    render()
                    delay(TICK_MS)
                }
            }
        }
    }

    private suspend fun refresh() {
        val link = link ?: return
        AgentApprovals.refresh(app, link, source)
        render(read = true)
    }

    private fun queue(): List<AgentApproval> {
        val account = link?.account ?: return emptyList()
        val later = AgentApprovals.board.later.value
        return AgentApprovals.board.queue(account).filter { it.id !in later || it.id == focus }
            .sortedBy { if (it.id == focus) 0 else 1 }
            .let { list -> list.filter { it.kind != ApprovalKind.NeedsYou || it.id == focus } + list.filter { it.kind == ApprovalKind.NeedsYou && it.id != focus } }
            .distinct()
    }

    /** Works out the card: the one on screen keeps its place until it is answered or closed; then the next in the queue, or the end. */
    private fun render(read: Boolean = false) {
        if (closing) { _ui.value = _ui.value.copy(finished = true); return }
        val now = now()
        val queue = queue()
        var shown = current
        val answered = phase as? CardPhase.Answered
        if (answered != null && now - answeredAt >= answeredShowMillis(answered)) { shown = null; current = null }
        if (shown != null && phase == CardPhase.Asking) {
            val fresh = queue.firstOrNull { it.id == shown!!.id }
            when {
                shown.deadline <= now -> phase = CardPhase.Problem("This request ended. Nothing was done.", retry = false)
                fresh == null && read -> phase = CardPhase.Problem("Already answered or ended.", retry = false)
                fresh != null -> { shown = fresh; current = fresh }
            }
        }
        if (shown == null) {
            val next = queue.firstOrNull()
            if (next == null) {
                val state = _ui.value
                _ui.value = when {
                    state.line != null -> state
                    !read && !readOnce -> state
                    // Opened (from its notification) for one question that is already over: say so once, with Close.
                    focus != null && !everShown -> DialogUi(line = "Already answered or ended.", loading = false)
                    else -> DialogUi(loading = false, finished = true)
                }
                if (read) readOnce = true
                return
            }
            // Every new card waits a moment before it takes an answer: the owner's tap in Beacon, or on the card before, never lands on it.
            current = next; shown = next; phase = CardPhase.Asking; shownAt = now; everShown = true
        }
        if (read) readOnce = true
        val record = shown ?: return
        val others = queue.count { it.id != record.id }
        val window = approvalWindowSeconds(record.kind)
        val left = ((record.deadline - now) / 1000).coerceAtLeast(0)
        _ui.value = DialogUi(
            card = ApprovalCardUi(record, phase, position = 1, count = 1 + others,
                leftFraction = window?.let { (left.toFloat() / it).coerceIn(0f, 1f) }, leftSeconds = window?.let { left },
                answerable = approvalAnswerable(shownAt, now)),
            loading = false,
        )
    }

    fun answer(approved: Boolean, code: String?) {
        val link = link ?: return
        val record = current ?: return
        if (phase != CardPhase.Asking && (phase as? CardPhase.Problem)?.retry != true) return
        if (!approvalAnswerable(shownAt, now())) return
        phase = CardPhase.Sending(approved)
        render()
        viewModelScope.launch {
            val result = AgentApprovals.decide(app, link, record, approved, code) ?: return@launch
            phase = when (result) {
                DecisionResult.Sent -> CardPhase.Answered(answeredLine(record, approved), approved)
                DecisionResult.Retry, DecisionResult.Invalid -> CardPhase.Problem(decisionLine(result, record.kind == ApprovalKind.Code)!!, retry = true)
                else -> CardPhase.Problem(decisionLine(result, record.kind == ApprovalKind.Code)!!, retry = false)
            }
            answeredAt = now()
            if (focus == record.id) focus = null
            if (result == DecisionResult.Sent && approved && record.kind == ApprovalKind.NeedsYou) openPhone()
            render()
        }
    }

    /** "Open the phone" (a Needs-you notice): L5's setup view, opened by class name as the phone page opens it. */
    private fun openPhone() {
        val intent = Intent().setClassName(app, AGENT_PHONE_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(app.packageManager) != null) runCatching { app.startActivity(intent) }
        closing = true
        _ui.value = _ui.value.copy(finished = true)
    }

    /** Close on a question that is over: it leaves this phone (and its notification). */
    fun close() {
        val link = link
        val record = current
        if (record == null || link == null) { _ui.value = _ui.value.copy(finished = true); return }
        current = null
        phase = CardPhase.Asking
        if (focus == record.id) focus = null
        viewModelScope.launch {
            AgentApprovals.ended(app, link, record)
            render()
            if (_ui.value.card == null) _ui.value = _ui.value.copy(finished = true)
        }
    }

    /** Back: the question on screen stays open on Hermes and in the shade, and the dialog doesn't come back for it by itself. */
    fun putOff() {
        current?.takeIf { phase == CardPhase.Asking }?.let { AgentApprovals.board.putOff(it.id) }
    }

    private companion object {
        const val TICK_MS = 250L
        const val READ_EVERY_MS = 4_000L
        const val AGENT_PHONE_VIEW = "dev.hermes.android.agentphone.AgentPhoneActivity"
    }
}

/**
 * The scrim and the card: at the bottom on a phone, where the thumb is, and centred at a readable width on an unfolded phone or a
 * tablet. A tap on the scrim does nothing (an answer is never given or dropped by a stray touch); Back closes it.
 */
@Composable internal fun ApprovalDialogScreen(ui: DialogUi, onAnswer: (Boolean, String?) -> Unit, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scrim = Color.Black.copy(alpha = if (scheme.background.luminance() < .5f) .62f else .42f)
    val motion = rememberNativeMotionEnabled()
    BoxWithConstraints(Modifier.fillMaxSize().background(scrim).pointerInput(Unit) { detectTapGestures { } }.testTag("agent-approval-scrim")) {
        val wide = maxWidth >= 600.dp
        // The card is never taller than the room left above the keyboard and the system bars; past that it scrolls inside.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = if (wide) Alignment.Center else Alignment.BottomCenter) {
            val cardModifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight)
            when {
                ui.card != null -> AnimatedContent(ui.card.record.id, label = "agent-approval-next",
                    transitionSpec = { if (motion) (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 6 }) togetherWith fadeOut(tween(120)) else fadeIn(tween(0)) togetherWith fadeOut(tween(0)) }) { id ->
                    if (id == ui.card.record.id) AgentApprovalCard(ui.card, onAnswer, onClose, cardModifier)
                }
                ui.line != null -> Glass(cardModifier.testTag("agent-approval-gone")) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(uiText(ui.line), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("agent-approval-gone-line"), style = MaterialTheme.typography.titleMedium)
                        Button(onClose, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("agent-approval-close")) { Text(uiText("Close")) }
                    }
                }
                ui.loading -> Glass(cardModifier) {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { BeaconDustCore(32.dp) }
                }
            }
        }
    }
}

/**
 * While Beacon is open and in front: reads the owner's open records every 5 s (every minute on a Hermes without the route) and opens
 * the dialog for a question the owner hasn't put off. Pushes bring the same questions when Beacon is closed.
 */
@Composable internal fun AgentApprovalHost(client: BeaconClient?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        if (client == null) return@LaunchedEffect
        val link = AgentApprovals.linkFor(client)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            AgentApprovals.inFront = true
            try { while (isActive) {
                val read = runCatching { AgentApprovals.refresh(context, link) }.getOrNull()
                val later = AgentApprovals.board.later.value
                if (!AgentApprovalActivity.visible && AgentApprovals.board.queue(link.account).any { it.id !in later }) {
                    runCatching { context.startActivity(AgentApprovalActivity.intent(context, null, null)) }
                }
                delay(if (read?.supported == false) UNSUPPORTED_POLL_MS else POLL_MS)
            } } finally { AgentApprovals.inFront = false }
        }
    }
}

private const val POLL_MS = 5_000L
private const val UNSUPPORTED_POLL_MS = 60_000L

/** The dialog's own fixed lines; the words test checks each one. */
internal val AgentApprovalDialogWords = listOf("Already answered or ended.", "This request ended. Nothing was done.", "Open Beacon and sign in again to answer.")
