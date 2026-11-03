package dev.hermes.android.assist

import dev.hermes.android.ChatState
import dev.hermes.android.ui.BeaconDustMood
import dev.hermes.android.ui.presenceState
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.voice.CALL_DROPPED
import dev.hermes.android.voice.CALL_HERMES_NO_ANSWER
import dev.hermes.android.voice.CALL_MIC_FAILED
import dev.hermes.android.voice.CALL_SIGN_IN_EXPIRED
import dev.hermes.android.voice.CALL_VOICE_NO_ANSWER
import dev.hermes.android.voice.CALL_VOICE_STOPPED
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.CallTranscript
import kotlinx.coroutines.delay

/**
 * Beacon's assistant overlay as plain state: what is on screen, what the dust does and what the lock screen may show. The session
 * feeds it events; the overlay only draws it. Nothing here touches Android, so every rule is checked on the JVM.
 */

/**
 * The overlay's main surface: the pill alone, a written answer above it, or a live conversation. The pill, the text box, stays in
 * all three; live, its orb shows the conversation and the captions sit above it. There is no surface of its own for a call.
 */
internal enum class OverlaySurface { Pill, Answer, Live }

/** The window's own life: rising in, open, falling away. */
internal enum class OverlayPhase { Summoning, Open, Dismissing, Hidden }

/**
 * One short line the overlay shows instead of failing quietly. Each has one action, if any. [Unlock] is the locked phone's: the side
 * button asks for the unlock first, and until it succeeds this is all the overlay says (see [startNeedsUnlock]).
 */
internal enum class OverlayLine { Microphone, VoiceKey, SignIn, NotHeard, NoRecognizer, NotSent, CallFailed, Waiting, Unlock }

/**
 * Why a call didn't start or dropped, for the six reasons the call service words itself ([full] is its constant in DirectVoice.kt).
 * The overlay's line says only the short form, next to the one action; any other reason is the plain "Couldn't start the call".
 */
internal enum class CallReason(val full: String) {
    HermesNoAnswer(CALL_HERMES_NO_ANSWER),
    VoiceNoAnswer(CALL_VOICE_NO_ANSWER),
    MicFailed(CALL_MIC_FAILED),
    Dropped(CALL_DROPPED),
    VoiceStopped(CALL_VOICE_STOPPED),
    SignInExpired(CALL_SIGN_IN_EXPIRED),
}

/**
 * The [CallReason] a call service's [reason] words, or null for any other. The service puts its line in the phone's language before
 * the call state carries it, so [spellings] gives every wording of a constant that may arrive (its English and its translation).
 */
internal fun callReason(reason: String, spellings: (String) -> Collection<String> = { emptyList() }): CallReason? {
    val text = reason.trim()
    if (text.isEmpty()) return null
    return CallReason.entries.firstOrNull { text == it.full || spellings(it.full).any { spelling -> text == spelling.trim() } }
}

/** One question asked in the overlay and its reply as it streams. */
internal data class OverlayAsk(
    val question: String,
    val withScreen: Boolean = false,
    val answer: String = "",
    val streaming: Boolean = true,
    val failed: Boolean = false,
    /** The conversation the question went to, for Open in Beacon. */
    val sessionId: String = "",
)

internal data class AssistOverlayState(
    val surface: OverlaySurface = OverlaySurface.Pill,
    val phase: OverlayPhase = OverlayPhase.Hidden,
    val dictating: Boolean = false,
    /** What dictation has heard so far, shown in the field while the owner talks. */
    val heard: String = "",
    val ask: OverlayAsk? = null,
    val line: OverlayLine? = null,
    /** Why the last call couldn't start, when it was one of the reasons the call service words; null for any other. */
    val callFailure: CallReason? = null,
    /** Bumped on every summon, so the summon animation and the first-frame report run once per show. */
    val shows: Int = 0,
)

internal sealed interface OverlayEvent {
    /**
     * The assist gesture (or an unlock that brings the same answer back). [fresh] clears the last question. [locked] is the lock
     * screen read when it shows: a fresh show on a locked phone with no conversation running starts nothing and says to unlock first.
     */
    data class Show(val fresh: Boolean, val callActive: Boolean, val locked: Boolean = false) : OverlayEvent
    data object Summoned : OverlayEvent
    data object DictationStarted : OverlayEvent
    data class Heard(val text: String) : OverlayEvent
    data object DictationStopped : OverlayEvent
    data class Asked(val question: String, val withScreen: Boolean, val sessionId: String) : OverlayEvent
    /** The reply so far; [accepted] once Hermes shows it holds the question. */
    data class Progress(val answer: String, val streaming: Boolean, val accepted: Boolean = true) : OverlayEvent
    data object NotSent : OverlayEvent
    data object LiveStarted : OverlayEvent
    data object LiveEnded : OverlayEvent
    data class Line(val line: OverlayLine?) : OverlayEvent
    /** A call didn't start or dropped while connecting; [reason] is null for any reason the overlay has no short line for. */
    data class CallFailed(val reason: CallReason?) : OverlayEvent
    data object CloseCard : OverlayEvent
    data object Dismiss : OverlayEvent
    data object Hidden : OverlayEvent
}

internal fun reduce(state: AssistOverlayState, event: OverlayEvent): AssistOverlayState = when (event) {
    is OverlayEvent.Show -> {
        val base = if (event.fresh) AssistOverlayState(shows = state.shows) else state.copy(dictating = false, heard = "")
        base.copy(
            phase = OverlayPhase.Summoning, shows = state.shows + 1,
            // A call that is running is what the owner comes back to; otherwise the last answer, or the bare pill.
            surface = if (event.callActive) OverlaySurface.Live else base.cardOrPill(),
            // Locked, a fresh show starts nothing and says so; an unlock line left from an earlier show is gone once the phone is unlocked.
            line = when {
                event.fresh && startNeedsUnlock(event.locked, event.callActive) -> OverlayLine.Unlock
                base.line == OverlayLine.Unlock -> null
                else -> base.line
            },
        )
    }
    OverlayEvent.Summoned -> if (state.phase == OverlayPhase.Summoning) state.copy(phase = OverlayPhase.Open) else state
    OverlayEvent.DictationStarted -> state.copy(dictating = true, heard = "", line = null)
    is OverlayEvent.Heard -> if (state.dictating) state.copy(heard = event.text) else state
    OverlayEvent.DictationStopped -> state.copy(dictating = false, heard = "")
    is OverlayEvent.Asked -> state.copy(
        surface = if (state.surface == OverlaySurface.Live) OverlaySurface.Live else OverlaySurface.Answer,
        ask = OverlayAsk(event.question.trim(), event.withScreen, sessionId = event.sessionId), dictating = false, heard = "", line = null,
    )
    is OverlayEvent.Progress -> state.ask?.let { ask ->
        when {
            // A question that timed out stays "Couldn't send" until Hermes shows it holds it after all.
            ask.failed && !event.accepted -> state
            // Then the late reply takes the line's place and the card comes back: it was sent.
            else -> state.copy(ask = ask.copy(answer = event.answer, streaming = event.streaming, failed = false),
                surface = if (ask.failed && state.surface == OverlaySurface.Pill) OverlaySurface.Answer else state.surface,
                line = if (ask.failed && state.line == OverlayLine.NotSent) null else state.line)
        }
    } ?: state
    // The question goes back in the field and the line offers Try again; no empty card repeats it.
    OverlayEvent.NotSent -> state.ask?.let {
        state.copy(ask = it.copy(streaming = false, failed = true), line = OverlayLine.NotSent, surface = if (state.surface == OverlaySurface.Live) OverlaySurface.Live else OverlaySurface.Pill)
    } ?: state
    OverlayEvent.LiveStarted -> state.copy(surface = OverlaySurface.Live, dictating = false, heard = "", line = null, callFailure = null)
    OverlayEvent.LiveEnded -> if (state.surface == OverlaySurface.Live) state.copy(surface = state.cardOrPill()) else state
    is OverlayEvent.Line -> state.copy(line = event.line, callFailure = null, dictating = if (event.line != null) false else state.dictating)
    is OverlayEvent.CallFailed -> state.copy(line = OverlayLine.CallFailed, callFailure = event.reason, dictating = false)
    OverlayEvent.CloseCard -> if (state.surface == OverlaySurface.Answer) state.copy(surface = OverlaySurface.Pill, ask = null, line = null) else state.copy(line = null)
    OverlayEvent.Dismiss -> if (state.phase == OverlayPhase.Hidden) state else state.copy(phase = OverlayPhase.Dismissing, dictating = false, heard = "")
    // A closed overlay holds no conversation: the dust falls away with its captions still up, and once it is gone nothing is live.
    OverlayEvent.Hidden -> state.copy(phase = OverlayPhase.Hidden, dictating = false, heard = "", surface = if (state.surface == OverlaySurface.Live) state.cardOrPill() else state.surface)
}

/** The answer card when an answered (or still answering) question is held; the bare pill when there is none, or it never left. */
private fun AssistOverlayState.cardOrPill(): OverlaySurface = if (ask != null && !ask.failed) OverlaySurface.Answer else OverlaySurface.Pill

/** What the dust does for [state]; [call] is the live call's own state while the overlay holds one. */
internal fun dustMood(state: AssistOverlayState, call: SphereState? = null): BeaconDustMood = when {
    state.phase == OverlayPhase.Summoning -> BeaconDustMood.Summon
    state.phase == OverlayPhase.Dismissing || state.phase == OverlayPhase.Hidden -> BeaconDustMood.Dismiss
    state.surface == OverlaySurface.Live -> when (call) {
        SphereState.Listening -> BeaconDustMood.Listening
        SphereState.Thinking -> BeaconDustMood.Thinking
        SphereState.Speaking, SphereState.Streaming -> BeaconDustMood.Speaking
        else -> BeaconDustMood.Idle
    }
    state.dictating -> BeaconDustMood.Listening
    state.ask?.streaming == true -> if (state.ask.answer.isBlank()) BeaconDustMood.Thinking else BeaconDustMood.Speaking
    else -> BeaconDustMood.Idle
}

/**
 * Whether the overlay keeps the screen on: while it listens, holds a live call, works on an answer, the answer was just finished
 * ([reading]), or the owner touched, typed or summoned it a moment ago ([idleHold], see [holdWhileIdle]). The lock screen otherwise
 * sleeps ten seconds after it wakes, which would hide an answer mid-sentence or an overlay the owner paused to think over.
 */
internal fun keepsScreenOn(state: AssistOverlayState, reading: Boolean, idleHold: Boolean = false): Boolean = state.phase != OverlayPhase.Hidden &&
    (state.dictating || state.surface == OverlaySurface.Live || state.ask?.streaming == true || (reading && state.ask != null) || idleHold)

/** How long an open, idle overlay keeps the screen on after the last interaction; then the phone sleeps as it normally does. */
internal const val IDLE_HOLD_MILLIS = 30_000L

/**
 * The idle hold: [hold] is true from the start until [millis] after the last interaction ([lastInteraction], in the clock [now] reads),
 * then false. It is read again after each wait, so an interaction in the meantime (a touch, typing) extends the hold.
 */
internal suspend fun holdWhileIdle(now: () -> Long, lastInteraction: () -> Long, hold: (Boolean) -> Unit, millis: Long) {
    hold(true)
    while (true) {
        val left = lastInteraction() + millis - now()
        if (left <= 0L) break
        delay(left)
    }
    hold(false)
}

// --- the lock screen ---

/*
 * The lock-screen rule, one rule for everything the overlay and its conversations show or do:
 *  - One live read. The text gates (the owner's words, the typed question and an unsent one in the text box, the tool line, a written
 *    answer, the approval card) all read [overlayLocked]: what the lock broadcasts last said, or the lock screen right now
 *    (deviceLockedNow), whichever says locked. None reads the broadcasts alone.
 *  - The overlay never starts a conversation on a locked phone. The side button on the lock screen asks for the unlock first
 *    ([startNeedsUnlock]): until it succeeds nothing is heard or said, no microphone opens, no dictation, no typed text is sent, and
 *    no voice session is warmed or connected. The one line, "Unlock your phone first." with Unlock, is all the overlay shows; the
 *    unlock (AssistUnlockActivity, "talk") brings the overlay back as a fresh start, the same as an unlocked gesture.
 *  - A conversation is TRUSTED when it started on an unlocked phone, or the phone has been unlocked at any time since (a live read at
 *    its start, the unlock broadcast, or a read when a tool runs: voice/CallTrust.kt). That stays as defense in depth: one that somehow
 *    starts locked is told nothing private (no memory, history or profile in its brief) and every tool, Hermes' and the phone's,
 *    answers "Unlock your phone first." without reading anything.
 *  - Once trusted it stays trusted for that conversation, even if the screen locks again (a call in a pocket keeps working). An
 *    approval still needs the unlocked screen to tap, as before.
 */

/** The lock state every private surface of the overlay reads: the lock broadcasts' last word, or the lock screen right now. */
internal fun overlayLocked(cached: Boolean, keyguardNow: Boolean): Boolean = cached || keyguardNow

/**
 * Whether a written answer may show. Locked, every answer waits for an unlock: questions go into the assistant's ongoing chat,
 * which carries its history and memory, so no rule about the reply can tell a private answer from a plain one.
 */
internal enum class AnswerReveal { Shown, UnlockToSee }

internal fun answerReveal(locked: Boolean): AnswerReveal = if (locked) AnswerReveal.UnlockToSee else AnswerReveal.Shown

/** What the answer card shows under the question. */
internal enum class AnswerBody { Thinking, Locked, Text, Empty }

/** Locked, the card thinks while the reply streams and then shows the lock row: never a word of the reply before an unlock. */
internal fun answerBody(locked: Boolean, ask: OverlayAsk): AnswerBody = when {
    ask.failed -> AnswerBody.Empty
    ask.streaming -> if (locked || ask.answer.isBlank()) AnswerBody.Thinking else AnswerBody.Text
    answerReveal(locked) == AnswerReveal.UnlockToSee -> AnswerBody.Locked
    ask.answer.isNotBlank() -> AnswerBody.Text
    else -> AnswerBody.Empty
}

/**
 * The question line the answer card draws. The question is the owner's own typed words, as private as the reply: locked, none of it is
 * drawn (the state keeps it, so the card comes back whole after the unlock) and the unlock row is all the card says.
 */
internal fun answerQuestion(locked: Boolean, ask: OverlayAsk): String = if (locked) "" else ask.question

/**
 * Everything the overlay can do. Locked, asking, dictating and starting the conversation wait for the unlock (a conversation that
 * already runs carries on, ending it and closing always work), and so does anything that opens the app or a settings screen.
 */
internal enum class OverlayAction { Ask, Dictate, Live, EndCall, Type, CloseCard, Dismiss, AttachScreen, OpenInBeacon, ContinueChat, OpenWaiting, Reveal, Settings }

internal fun needsUnlock(action: OverlayAction, locked: Boolean): Boolean = locked && action in setOf(
    OverlayAction.Ask, OverlayAction.Dictate, OverlayAction.Live,
    OverlayAction.OpenInBeacon, OverlayAction.ContinueChat, OverlayAction.OpenWaiting, OverlayAction.Reveal, OverlayAction.Settings,
)

/**
 * Whether starting the conversation waits for the unlock: the phone is [locked] and no conversation runs. One that runs was started
 * unlocked, so it is trusted and the overlay joins it, as before.
 */
internal fun startNeedsUnlock(locked: Boolean, callActive: Boolean): Boolean = needsUnlock(OverlayAction.Live, locked) && !callActive

/**
 * Whether the text box is inert: locked with no conversation running, nothing is typed, sent or dictated, and its orb only asks for
 * the unlock. A running conversation takes typed turns locked too (it is trusted).
 */
internal fun pillInert(locked: Boolean, live: Boolean): Boolean = locked && !live

/** What the first frame of a show leads to. */
internal enum class FirstFrame {
    /** Locked, a fresh show: no app model, no warming, no start; the unlock is asked for, once. */
    AskUnlock,
    /** Locked and nothing to start (a re-show): nothing is loaded or warmed either. */
    Hold,
    /** Unlocked, or joining a conversation that runs: the app's model loads and the call session warms. */
    Warm,
    /** Warm, and a fresh show starts the conversation. */
    WarmAndTalk,
}

internal fun firstFrame(locked: Boolean, callActive: Boolean, talks: Boolean): FirstFrame = when {
    startNeedsUnlock(locked, callActive) -> if (talks) FirstFrame.AskUnlock else FirstFrame.Hold
    talks -> FirstFrame.WarmAndTalk
    else -> FirstFrame.Warm
}

/** A suggestion above the pill. Talking live is the orb in the pill, so it has no chip of its own. */
internal sealed interface AssistChip {
    /** Ask about what is on screen; only when Android gave the overlay a screenshot. */
    data object AboutScreen : AssistChip
    data class Continue(val title: String, val sessionId: String, val profile: String) : AssistChip
    /** Something in the assistant's chat waits for the owner. It never says what, so it may show locked. */
    data class Waiting(val approval: Boolean) : AssistChip
}

/**
 * The chips in the order they appear. What waits for the owner comes first, because it matters most; the last chat's title is
 * history, so the lock screen never shows it; a screenshot is only ever granted unlocked, and the rule holds even if one came.
 */
internal fun assistChips(screenshot: Boolean, locked: Boolean, last: AssistChip.Continue?, waiting: AssistChip.Waiting?): List<AssistChip> = buildList {
    waiting?.let(::add)
    if (screenshot && !locked) add(AssistChip.AboutScreen)
    if (!locked && last != null && last.title.isNotBlank()) add(last)
}

/**
 * The chips that fit one line of [room] px at rest, each with the width it may take, so none is ever cut at the edge. What waits
 * always shows. The screen chip shows when it fits whole (the pill's + attaches the screen too). Continue shrinks into what is
 * left, its title ellipsized, down to [continueMin]; below that it is left out. [width] is a chip's own width; [gap] sits between.
 */
internal fun fitChips(chips: List<AssistChip>, room: Int, gap: Int, continueMin: Int, width: (AssistChip) -> Int): List<Pair<AssistChip, Int>> {
    val kept = mutableListOf<Pair<AssistChip, Int>>()
    var used = 0
    for (chip in chips) {
        val before = if (kept.isEmpty()) 0 else gap
        val left = room - used - before
        val own = width(chip)
        val given = when {
            own <= left -> own
            chip is AssistChip.Waiting -> left.coerceAtLeast(0)
            chip is AssistChip.Continue && left >= continueMin -> left
            else -> null
        } ?: continue
        kept += chip to given
        used += before + given
    }
    return kept
}

// --- one short line instead of a silent failure ---

/** The one thing a line offers to do about it, if anything. */
internal enum class LineAction { Allow, Open, SignIn, TryAgain, CallAgain, OpenWaiting, Unlock }

/** An expired sign-in can't be fixed by calling again: its action opens Beacon (unlocked first) to sign in. */
internal fun lineAction(line: OverlayLine, reason: CallReason? = null): LineAction? = when (line) {
    OverlayLine.Microphone -> LineAction.Allow
    OverlayLine.VoiceKey, OverlayLine.SignIn -> LineAction.Open
    OverlayLine.NotSent -> LineAction.TryAgain
    OverlayLine.CallFailed -> if (reason == CallReason.SignInExpired) LineAction.SignIn else LineAction.CallAgain
    OverlayLine.Waiting -> LineAction.OpenWaiting
    // Asks Android for the unlock again; the overlay stays, and the unlock brings it back as a fresh start.
    OverlayLine.Unlock -> LineAction.Unlock
    OverlayLine.NotHeard, OverlayLine.NoRecognizer -> null
}

/** After a send failed the question goes back in the field, unless the owner already typed something new there. */
internal fun draftAfterNotSent(draft: String, question: String): String = draft.ifBlank { question }

/** When a question that seemed lost is answered after all, the copy put back in the field goes, unless the owner changed it. */
internal fun draftAfterLateAnswer(draft: String, question: String): String = if (draft.trim() == question.trim()) "" else draft

// --- the reply, read from the assistant chat the question went to ---

/** The reply to the question asked when the chat held [before] message ids, while [sessionId] is still the open chat. */
internal data class AnswerProgress(val answer: String, val streaming: Boolean, val accepted: Boolean)

internal fun answerFrom(chat: ChatState, before: Set<String>, sessionId: String): AnswerProgress? {
    if (sessionId.isBlank() || chat.sessionId != sessionId) return null
    val fresh = chat.messages.filter { it.id !in before }
    val replies = fresh.filter { it.role == "assistant" }
    val live = chat.streamId.isNotBlank()
    val text = (replies.map { it.text.trim() } + listOfNotNull(chat.liveText.trim().takeIf { live && it.isNotEmpty() })).filter { it.isNotEmpty() }.joinToString("\n\n")
    // Accepted once Hermes holds the question: its own message came back, a reply streams, or the send is still settling.
    val accepted = live || fresh.any { it.role == "user" } || replies.isNotEmpty() || chat.uncertain.isNotBlank()
    val streaming = live || chat.uncertain.isNotBlank() || replies.isEmpty()
    return AnswerProgress(text, streaming, accepted)
}

/** The longest chat title a chip shows before an ellipsis. */
internal const val CHIP_TITLE_MAX = 24

/** [title] cut to [CHIP_TITLE_MAX] characters with an ellipsis when longer; never half of a two-part character such as an emoji. */
internal fun chipTitle(title: String): String {
    if (title.length <= CHIP_TITLE_MAX) return title
    val cut = title.take(CHIP_TITLE_MAX).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    return cut.trimEnd() + "…"
}

/** The newest conversation that is not the assistant's own: the Continue chip. */
internal fun lastChat(rows: List<org.json.JSONObject>, assistantSession: String, profile: String): AssistChip.Continue? = rows.asSequence()
    .map { row -> Triple(dev.hermes.android.core.sessionRowId(row), row.optString("title").trim(), row.optString("profile").ifBlank { profile }) }
    .firstOrNull { (id, title, _) -> id.isNotBlank() && id != assistantSession && title.isNotBlank() }
    ?.let { (id, title, rowProfile) -> AssistChip.Continue(chipTitle(title), id, rowProfile) }

// --- the ground under the overlay ---

/** The solid ground starts this far (dp) above the topmost surface: the chips, the card or the line. */
internal const val GROUND_LEAD_DP = 24

/** How much of the theme's background lies under the surfaces: all of it in light and .98 in dark, so no app shows between them. */
internal fun groundAlpha(dark: Boolean): Float = if (dark) .98f else 1f

// --- the live conversation ---

/** What starting the conversation does now: join one that runs, ask for what is missing first, or start. */
internal enum class LiveStart { Join, SignIn, Microphone, Start }

/** A conversation that runs is joined; otherwise being signed in (or still restoring) and the microphone come before a start, so no start is a dead end. */
internal fun liveStart(callActive: Boolean, signedIn: Boolean, micGranted: Boolean): LiveStart = when {
    callActive -> LiveStart.Join
    !signedIn -> LiveStart.SignIn
    !micGranted -> LiveStart.Microphone
    else -> LiveStart.Start
}

/** Whether a show starts the conversation by itself once its first frame is drawn: every fresh one. A re-show (an unlock bringing the same answer back) keeps what was on screen. */
internal fun talksOnShow(reshow: Boolean): Boolean = !reshow

/** One live caption: [owner] says whose words, the owner's or the assistant's. */
internal data class Caption(val owner: Boolean, val text: String)

/** The last two lines above the text box: the owner's words, then the assistant's reply; typed turns are the owner's too. */
internal fun liveCaptions(transcripts: List<CallTranscript>, typed: List<String>): List<Caption> =
    (transcripts.filter { it.text.isNotBlank() }.map { Caption(it.role == "user", it.text.trim()) } + typed.map { Caption(true, it) }).takeLast(2)

/** What the orb in the text box shows: a tap to talk, then the conversation's own state; a tap on it while live ends the conversation. */
internal enum class OrbFace { Talk, Connecting, Listening, Speaking, Working }

internal fun orbFace(live: Boolean, call: CallState): OrbFace = when {
    !live -> OrbFace.Talk
    !call.active -> OrbFace.Connecting
    else -> when (presenceState(call)) {
        SphereState.Thinking -> OrbFace.Working
        SphereState.Speaking, SphereState.Streaming -> OrbFace.Speaking
        else -> if (call.state == "connecting") OrbFace.Connecting else OrbFace.Listening
    }
}

/**
 * The approval the live conversation waits on, when it belongs to the account the overlay is signed in to: the same rule the app's own
 * screen uses before it asks. Null for none, another account's, or a call that is not running.
 */
internal fun waitingApproval(call: CallState, authenticated: Boolean, origin: String, owner: String, profile: String): CallToolApproval? =
    call.toolApprovals.firstOrNull()?.takeIf {
        authenticated && call.active && call.conversationScope?.let { s -> s.owner == owner && s.profile == profile && s.origin == origin } == true
    }

/** How the overlay shows [waitingApproval]: the whole card, or locked only that something waits (no details) with a way to say no. */
internal enum class ApprovalShow { None, Card, Locked }

internal fun approvalShow(live: Boolean, approval: CallToolApproval?, locked: Boolean): ApprovalShow = when {
    !live || approval == null -> ApprovalShow.None
    locked -> ApprovalShow.Locked
    else -> ApprovalShow.Card
}
