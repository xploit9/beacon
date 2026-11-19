package dev.hermes.android.voice

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.requestNeverSent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** The call's link to Hermes (contract v3, section 6). LOST gives every pending function call its state. */
internal enum class Link { OK, LOST }

/** A call request's client timeout is its server wait plus this (contract section 2). */
internal const val CALL_REQUEST_MARGIN_MS = 4000L
/** How long Hermes waits for a tool's result before it answers "running". */
internal const val CALL_INVOKE_WAIT_MS = 8000L
/** The plugin answers a hand-over within its own deadline (canonical_transport.HANDOVER_SECONDS); a dead link is still found by pings. */
internal const val CALL_HANDOVER_WAIT_MS = 8000L

/**
 * How long a spoken state waits before it goes to the live model. A newer state or the final result in that time replaces it: after
 * an outage, Hermes' states and the result come in a burst, and Gemini Live answers each spoken response with a turn of its own, so a
 * state spoken just before its result was taken for a result (device round 1: an invented answer, then the real one).
 */
internal const val SPOKEN_STATE_SETTLE_MS = 500L
/**
 * How long a lost link waits before the model is told (hermes_unreachable, lost_result): the first retry goes 0.5 s after a failed request
 * and is usually answered within a second, so a one-off failure is never said (LINK-121: in device round 3 the model was told
 * "hermes_unreachable" for a failure the retry answered 1 s later, then "handed_over" 2 s after that). If the link is back by then, the
 * state is dropped; earlier where the call's notice time comes, so a blip makes its notice at most the spoken settle later than the link's
 * return (REVIEW-E5-2 P3-1, REVIEW-E5-3 P3-2).
 */
internal const val LINK_STATE_SETTLE_MS = 1_500L
/**
 * How long an approval (or a question) waits before the model is told to ask the owner: the card is on screen, and an owner who answers it
 * within this time hears only the result (device round 2: approvals answered 2.9-3.1 s after the card each started a turn of their own, then
 * the result started another, and the model said they still needed approval after they had completed).
 */
internal const val APPROVAL_STATE_SETTLE_MS = 4_000L
/**
 * A request still running this long is told to the model once (still_running), so the owner hears it is on it: the model says nothing
 * while it waits for its functions (device round 3: every frame of reads ended the model's turn at once, and the owner heard nothing until
 * a result came, 4-10 s later).
 */
internal const val CALL_NOTICE_MS = 3_000L
/**
 * When a notice goes, every other call this close to its own notice time is told with it: one "still running" turn for a frame of slow
 * calls, not one per call (REVIEW-E5 P2-1).
 */
internal const val NOTICE_GATHER_MS = 500L
/**
 * How long a result of a frame already told "still running" waits for that frame's other calls still running, from its own landing; then
 * the frame's ready results go as one release, and a later one goes on its own. A noticed call holds nothing for other frames (REVIEW-E5
 * P2-2), so without this a frame of slow reads was one "still running" turn and then a turn per result (device round 3's F3 again). 1 s:
 * Hermes answers a frame's reads together (E5's live frames of three reads: all three within 82 ms of each other), while a read slowed by
 * an approval or a long tool comes seconds after its siblings (round 3: 4.2, 7.9 and 9.4 s), which must not hold a ready answer for long.
 */
internal const val FRAME_GATHER_MS = 1_000L
/** The longest a request still running holds back the results the model is waiting for; past it, they go without it. */
internal const val CALL_HOLD_MS = 10_000L
/** The longest Hermes outage a call rides out (the provider reconnects meanwhile); then it ends with "Can't reach Hermes. Check your connection, then try again." */
internal const val CALL_HERMES_OUTAGE_MS = 10 * 60_000L

/** The pause before a retry-safe request is sent again: 0.5, 1, 2, 2, ... seconds. */
internal fun callRetryDelay(attempt: Int): Long = when (attempt) { 0 -> 500L; 1 -> 1000L; else -> 2000L }
/** With the lost link's probe running, how long a retry waits for the link's return before it tries on its own ([CallLinkState.retryDelay]). */
internal const val PROBED_RETRY_FALLBACK_MS = 10_000L

/** A request that got no answer within its budget. Not a cancellation: the call goes on. */
internal class CallRequestTimeout : java.net.SocketTimeoutException("No answer from Hermes")

/** One function response for the live model. */
internal class CallReply(val id: String, val name: String, val result: JSONObject)

/** The states that wait on someone else: the owner (an approval, a question), Hermes' agent (a hand-over) or the link (while it is lost). */
private val WAITS_ON_OTHERS = setOf("needs_approval", "needs_input", "handed_over", "hermes_unreachable", "lost_result")
/** The states of a lost link. */
private val LINK_STATES = setOf("hermes_unreachable", "lost_result")

/**
 * One link state per call, and the state of every function call the live model is waiting on. The model gets the function's own
 * response each time that state changes (never the same state twice in a row), and the final result exactly once, however late it comes.
 *
 * Gemini Live answers every spoken function response with a turn of its own (Google's Scheduling reference: WHEN_IDLE adds the result "to
 * the conversation context, and prompts to generate output"). Device round 3: a frame of three reads was answered one result at a time,
 * and the model filled the wait ("(Waiting for the rest of your information to load.)"), asked again for the reads still open, said what no
 * tool had returned yet ("I couldn't find any recent mentions of invoices", "You have no events", "I've also set your reminder") and then
 * said the same answer once per queued response, four times. So responses go in releases: one release is one tool response with every
 * result and state ready by then, and asks the model to answer once (the last response carries WHEN_IDLE, every other is SILENT).
 * A release waits while a function the model is waiting on is still running and was not told it is going on (at most [CALL_HOLD_MS]; it is
 * told at [CALL_NOTICE_MS], so another frame's ready answer waits at most 3 s for an un-noticed call, 4 s with a gather). A state the owner
 * must hear goes at once (inside a link's return, in its one release): a request still running after [CALL_NOTICE_MS], an approval or a
 * question, a lost link, a hand-over. Progress the
 * model only needs to know (running, queued) goes at once and quietly. [send] queues one release for the provider, in order, under the
 * ledger's lock; [told]
 * reports each response afterwards (for the call's tool window). Every function response of a call comes from here (Gemini Live speaks and believes a response
 * sent after the final one: lane C's probe, 14 Nov 2026).
 */
internal class CallLinkState(
    private val clock: () -> Long,
    private val send: (replies: List<CallReply>, prompt: Boolean) -> Unit,
    private val told: (id: String, name: String, result: JSONObject) -> Unit = { _, _, _ -> },
    /** The link came back after it was lost: what Hermes confirmed can be read again. */
    private val recovered: () -> Unit = {},
    /** The link was lost. */
    private val lostLink: () -> Unit = {},
    /** Runs a block after a delay (the call's scope); null sends every state at once, and nothing is noticed or let go by time. */
    private val later: ((Long, () -> Unit) -> Unit)? = null,
    /** Function call [id] has its final result (whether or not it was sent yet): what waits on it may go on. */
    private val settled: (id: String) -> Unit = {},
    /** The call runs a probe while the link is lost (DirectVoice's): a retry then waits for the link's return ([retryDelay]). */
    private val probed: Boolean = false,
) {
    private class Pending(val name: String, val requestId: String, val startedAt: Long, val hermes: Boolean, val retrySafe: Boolean) {
        var ran: Boolean? = null
        var last: JSONObject? = null
        var final = false
        /** The provider cancelled the call: nothing more is sent for it, and it holds nothing back. */
        var cancelled = false
        /** The newest state or result not sent yet, and whether it may go (a spoken state waits its settle). */
        var unsent: JSONObject? = null
        var due = false
        /** Running past [CALL_HOLD_MS]: it holds no release back any more. */
        var holdOver = false
        /** The last response the model was actually sent. */
        var sent: JSONObject? = null
        /** The model was told a state of this call: the owner heard it is going on. From then on it holds nothing (REVIEW-E5 P2-2). */
        var noticed = false
        /**
         * It waits for a change before it in its own frame (DirectVoice): no notice of its own while it waits (REVIEW-E5 P3-2), and it holds
         * only its own frame's results ([release], REVIEW-E5-2 P2-1).
         */
        var queued = false
        /** Its notice time came while the link was down: told "still running" within the spoken settle of the link's return ([linkBack]). */
        var noticeMissed = false
        /** When the link was lost it waited on the owner or Hermes' agent (an approval, a question, a hand-over): the return leaves it as it was. */
        var waitedBeforeLoss = false
        /** Its "still running" was given by a blip's return gather ([startReturn]): news even after a notice. */
        var afterOutage = false
        /** Where its hold and notice clocks start: its start, or a told outage's return ([restartHeld]); the timers of an older start lapse. */
        var clockFrom = startedAt
        var clockEpoch = 0
        /** The provider frame it came in (the frame's first call id; its own id when it came alone). */
        var frame = ""
    }
    private val calls = LinkedHashMap<String, Pending>()
    /** The frame of each call that came with others, until it opens. */
    private val frameOf = HashMap<String, String>()
    /** Frames whose ready results wait for their siblings (FRAME_GATHER_MS), each with the token of its own timer. */
    private val gathering = HashMap<String, Int>()
    private var gatherToken = 0
    private val outbox = ArrayList<Pair<List<CallReply>, Boolean>>()
    private val finished = ArrayList<String>()
    /** Approvals the model was told about (by proposal id): a state saying the same approval again goes quietly (device round 3). */
    private val spokenApprovals = HashSet<String>()
    private var timeouts = 0
    /** What lost the link: the class of the failure (LINK-121: a loss raised inside the phone was recorded only as "network"). */
    private var lostCause: String? = null
    // The model was told (spoken) that Hermes cannot be reached in this outage: every other function's state of it goes quietly
    // (device round 2: "I'm having trouble reaching Hermes" said once per waiting function); a request opened after the owner heard of
    // the outage is told on its own ([open], REVIEW-E5-3 P2-1).
    private var outageSpoken = false
    // A lost-link state of this outage was spoken: the owner heard "can't reach Hermes" (follow-up 14).
    private var outageHeard = false
    /** The calls a blip's return gathers for its one release ([startReturn]). */
    private val returning = HashSet<Pending>()
    private var returnToken = 0
    /** Completed as the link comes back, a new one for each outage: a retry waiting its backoff then goes at once ([retryDelay]). */
    private var returned = CompletableDeferred<Unit>()
    @Volatile var link = Link.OK
        private set
    /** The link's losses so far: a request that sees it change across its own failure lost the link itself ([retryDelay]). */
    @Volatile var losses = 0
        private set

    /** Starts tracking function call [id]; false when the provider sent that call before. [hermes] calls depend on the link. */
    fun open(id: String, name: String, requestId: String, hermes: Boolean, retrySafe: Boolean): Boolean = update {
        if (calls.containsKey(id)) return@update false
        // A long call keeps the newest 128 calls; finished ones go first.
        if (calls.size >= 128) (calls.entries.firstOrNull { it.value.final && it.value.unsent == null } ?: calls.entries.first()).let { calls.remove(it.key) }
        val call = Pending(name, requestId, clock(), hermes, retrySafe).also { calls[id] = it }
        call.frame = frameOf.remove(id) ?: id
        // Called while the link is already down (the updates poll usually notices first): it hears so at once, not when Hermes is back.
        // After the owner heard of the outage, it is a new request: told on its own, after the spoken settle (one release per frame), so the
        // owner hears within a second that this one cannot reach Hermes now (REVIEW-E5-3 P2-1). Before that, it is part of the outage the
        // owner is about to hear of, told quietly.
        if (hermes && link == Link.LOST) stateResult(call, "hermes_unreachable", "connection_lost", cause()).let { state ->
            if (outageHeard) tell(id, call, state, settleMs = SPOKEN_STATE_SETTLE_MS) else tell(id, call, outageState(state))
        }
        // Timed by the call's scheduler, never by comparing clocks (a test's clock may stand still).
        later?.let { wait ->
            wait(CALL_NOTICE_MS) { update { if (call.clockEpoch == 0) notice(id, call) } }
            wait(CALL_HOLD_MS) { update { if (call.clockEpoch == 0) { call.holdOver = true; release() } } }
        }
        true
    }
    /** The calls of one provider frame, before they open: their results answer together after a shared notice ([FRAME_GATHER_MS]). */
    fun frame(ids: List<String>) = synchronized(this) {
        if (ids.size < 2) return@synchronized
        if (frameOf.size >= 256) frameOf.clear()
        for (id in ids) frameOf[id] = ids.first()
    }
    fun isFinal(id: String): Boolean = synchronized(this) { calls[id]?.final == true }
    fun known(id: String): Boolean = synchronized(this) { calls.containsKey(id) }
    /** How long function call [id] has been going. */
    fun elapsed(id: String): Long = synchronized(this) { calls[id]?.let { clock() - it.clockFrom } ?: 0L }

    /** Hermes confirmed the request ([ran] true), or it never left the phone ([ran] false). A confirmation is never taken back. */
    fun ran(id: String, ran: Boolean) = synchronized(this) { calls[id]?.let { if (it.ran != true) it.ran = ran } }

    /**
     * Function call [id] waits ([waiting] true) for a change before it in its own frame, or runs now ([waiting] false). While it waits it has
     * no notice of its own (the change it waits for has its own states); once it runs, its notice time starts again.
     */
    fun queued(id: String, waiting: Boolean) = update {
        val call = calls[id] ?: return@update
        call.queued = waiting
        if (!waiting) { call.noticeMissed = false; val epoch = call.clockEpoch; later?.invoke(CALL_NOTICE_MS) { update { if (call.clockEpoch == epoch) notice(id, call) } } }
    }

    /** The provider cancelled function call [id] (the owner cut the model off): it is answered no more and holds nothing back. */
    fun cancelled(id: String) = update {
        val call = calls[id] ?: return@update
        call.cancelled = true; call.unsent = null
        release()
    }

    /** A state that is not final: [status] is still_running, hermes_unreachable, lost_result or needs_approval. */
    fun state(id: String, status: String, detail: String? = null, fields: JSONObject? = null) = update {
        val call = calls[id] ?: return@update
        // While the link's return gathers it, a "running" receipt adds nothing to its "still running".
        if (call.final || call.last?.optString("status") == status || status == "running" && call in returning) return@update
        tell(id, call, stateResult(call, status, detail, fields))
    }

    /** The final result: sent once; anything after it for the same call is dropped. */
    fun final(id: String, result: JSONObject): Boolean = update {
        val call = calls[id] ?: return@update false
        if (call.final) return@update false
        call.final = true
        val value = JSONObject(result.toString()).put("will_update", false)
        if (!value.has("ran_on_hermes") && call.hermes) value.put("ran_on_hermes", call.ran ?: JSONObject.NULL)
        finished += id
        tell(id, call, value)
        true
    }

    /** A final answer the app itself decided: failed (refused, or never sent), or uncertain when it may have run. */
    fun failed(id: String, detail: String, fields: JSONObject? = null, status: String = "failed"): Boolean = update {
        val call = calls[id] ?: return@update false
        if (call.final) return@update false
        call.final = true
        finished += id
        tell(id, call, stateResult(call, status, detail, fields).put("will_update", false))
        true
    }

    /** Calls Hermes confirmed that are still waiting for their result: the ones to read again once the link is back. */
    fun confirmed(): List<Pair<String, String>> = synchronized(this) {
        calls.filterValues { it.hermes && !it.final && it.ran == true }.map { it.key to it.value.requestId }
    }

    // The link: any answer from Hermes (an error status too) means it is reachable.
    fun answered() { if (update { (link == Link.LOST).also { timeouts = 0; link = Link.OK; outageSpoken = false; if (it) { returned.complete(Unit); linkBack() } } }) recovered() }
    fun timedOut() { if (update { ++timeouts >= 2 && lost("no_response", CallRequestTimeout::class.java.simpleName) }) lostLink() }
    /** A request of the link failed with [cause] (an I/O error: a reset, a refused or closed connection, a failed ping). */
    fun connectionFailed(cause: Throwable? = null) { if (update { lost("connection_lost", cause?.javaClass?.simpleName) }) lostLink() }

    /** True when this lost the link (it was up). */
    private fun lost(detail: String, causeClass: String?): Boolean {
        if (link == Link.LOST) return false
        link = Link.LOST
        losses++
        if (returned.isCompleted) returned = CompletableDeferred()
        outageHeard = false
        // A loss ends a return: a held call that does not use the link goes now with its "still running" (Hermes calls get their lost-link
        // state below); nothing held is left without a time to go.
        for (call in returning) if (!call.hermes && !call.cancelled && call.unsent?.optString("status") == "still_running") call.due = true
        returning.clear()
        lostCause = causeClass?.takeIf { it.matches(Regex("[A-Za-z0-9_$]{1,80}")) }
        for ((id, call) in calls) if (call.hermes && !call.final && !call.cancelled) {
            val status = if (call.ran == true) "lost_result" else "hermes_unreachable"
            if (call.last?.optString("status") == status) continue
            call.waitedBeforeLoss = call.last?.optString("status").let { it in WAITS_ON_OTHERS && it !in LINK_STATES }
            tell(id, call, outageState(stateResult(call, status, if (call.ran == true) "result_not_received" else detail, cause())))
        }
        release()
        return true
    }
    /** The failure that lost the link, for the state's tool receipt (never for the model: GeminiWire leaves it out). */
    private fun cause(): JSONObject? = lostCause?.let { JSONObject().put("cause_class", it) }

    /**
     * One Hermes request for function call [id] (null for the call's own reads): an answer, a timeout after [serverWaitMs] plus the
     * margin, or an I/O failure (a failed HTTP/2 ping fails every request on that connection) feeds the link.
     */
    suspend fun <T> track(id: String?, serverWaitMs: Long = 0, block: suspend () -> T): T {
        try {
            val value = withTimeout(serverWaitMs + CALL_REQUEST_MARGIN_MS) { block() }
            answered()
            return value
        } catch (error: TimeoutCancellationException) {
            timedOut(); throw CallRequestTimeout()
        } catch (error: ApiFailure) {
            answered(); throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: java.io.IOException) {
            if (id != null && requestNeverSent(error)) ran(id, false)
            connectionFailed(error); throw error
        }
    }

    /**
     * [track], and a [retrySafe] request (a read, or one Hermes knows by its request_id) is sent again after a failure, 0.5, 1, 2,
     * 2 ... s later, until Hermes answers or [owned] says the call is over. Hermes' own refusals are never resent.
     */
    suspend fun <T> request(id: String?, serverWaitMs: Long, retrySafe: Boolean, owned: () -> Boolean, block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            check(owned()) { "Call authority changed" }
            val before = losses
            try { return track(id, serverWaitMs, block) }
            catch (error: CancellationException) { throw error }
            catch (error: ApiFailure) { throw error }
            catch (error: java.io.IOException) { if (!retrySafe) throw error }
            retryDelay(attempt++, lostIt = losses != before)
        }
    }

    /**
     * The pause before a retry ([callRetryDelay]). One that begins while the link is lost ends as soon as the link comes back, so every request
     * waiting on its backoff goes again at once when Hermes answers (device round 4, D1: native reads slept out their backoff and landed
     * after the return). A retry that fails again waits its next backoff.
     *
     * With the lost link's probe running ([probed]), only the probe ([probe]) tries on the backoff while the link is lost; any other retry
     * that begins then waits for the link's return (at most [PROBED_RETRY_FALLBACK_MS], as a probe can end without an answer or a failure).
     *
     * A request whose own failure lost the link ([lostIt]) waits its whole backoff first: no return sends it sooner. So a request that keeps
     * failing while Hermes answers the rest goes at its own backoff's pace (0.5, 1, 2, 2 s), whatever else Hermes answers meanwhile; with the
     * probe, once per return as well (REVIEW-E7 P3-1: woken at every return, it went every ~0.5 s). Requests failing while the link is
     * already lost (an outage) still go at its return.
     */
    suspend fun retryDelay(attempt: Int, probe: Boolean = false, lostIt: Boolean = false) {
        if (lostIt) delay(callRetryDelay(attempt))
        val back = synchronized(this) { returned.takeIf { link == Link.LOST } }
        if (lostIt && (back == null || !probed)) return
        val wait = if (probed && !probe && back != null) PROBED_RETRY_FALLBACK_MS else callRetryDelay(attempt)
        if (back == null) delay(wait) else withTimeoutOrNull(wait) { back.await() }
    }

    /** The first link state of an outage is spoken; the rest of that outage's link states only join the model's context (see [open]). */
    private fun outageState(result: JSONObject): JSONObject = result.apply { if (outageSpoken) put(QUIET_STATE, true) else outageSpoken = true }

    private fun stateResult(call: Pending, status: String, detail: String?, fields: JSONObject?): JSONObject =
        (fields?.let { JSONObject(it.toString()) } ?: JSONObject()).put("status", status).put("state_source", "phone")
            .put("request_id", call.requestId).put("tool", call.name).put("elapsed_ms", (clock() - call.startedAt).coerceAtLeast(0))
            .put("ran_on_hermes", call.ran ?: JSONObject.NULL).put("retry_safe", call.retrySafe).put("will_update", true)
            .apply { detail?.let { put("detail", it) } }

    // Under the lock. Quiet progress goes at once and alone (SILENT: it never starts a turn). A spoken state waits its settle
    // (SPOKEN_STATE_SETTLE_MS, an approval APPROVAL_STATE_SETTLE_MS) and goes only if nothing newer came for that call meanwhile; an
    // admitted hand-over is said at once (the owner hears of it within 2 s, and its result takes longer than that; inside a link's return,
    // in its one release, at most 1 s later: REVIEW-E5-3 P3-1). Then the release.
    private fun tell(id: String, call: Pending, result: JSONObject, now: Boolean = false, andRelease: Boolean = true, settleMs: Long? = null) {
        call.last = result
        call.afterOutage = false
        val turn = resultTurn(result)
        if (turn == ResultTurn.Quiet) {
            call.unsent = null
            call.sent = result
            outbox += listOf(CallReply(id, call.name, result)) to false
            release()
            return
        }
        call.unsent = result
        val status = result.optString("status")
        val settle = when {
            now || later == null || turn == ResultTurn.Final || status == "handed_over" -> 0L
            settleMs != null -> settleMs
            status == "needs_approval" -> APPROVAL_STATE_SETTLE_MS
            status in LINK_STATES -> LINK_STATE_SETTLE_MS
            else -> SPOKEN_STATE_SETTLE_MS
        }
        call.due = settle == 0L
        if (turn == ResultTurn.Final) gather(call)
        if (settle > 0) later?.invoke(settle) { update {
            if (call.unsent !== result || calls[id] !== call) return@update
            // A lost link that came back meanwhile is no news: the state is dropped and the call is as it was told (its notice time has not
            // come yet: one that came meant the state was dropped then, or as the link came back).
            if (status in LINK_STATES && link == Link.OK) { call.unsent = null; call.last = call.sent }
            else call.due = true
            release()
        } }
        if (andRelease) release()
    }

    /**
     * A request still running [CALL_NOTICE_MS] after the model asked for it, and told nothing yet: the owner hears it is going on. Every other
     * call within [NOTICE_GATHER_MS] of its own notice time is told in the same release, so a frame of slow calls is one turn (REVIEW-E5
     * P2-1); a call that only waits on a change of its frame gets none (P3-2). A call whose notice time comes while the link is down is told
     * within the spoken settle of the link's return ([linkBack]); a notice during a return joins its one release.
     */
    private fun notice(id: String, call: Pending) {
        // A lost-link state still settling with the link already back is no news: dropped now, so the notice keeps its time.
        if (link == Link.OK && calls[id] === call && !call.noticed) dropLinkState(call)
        if (noticeable(id, call)) noticeAll(listOf(id))
        else if (calls[id] === call && link == Link.LOST && call.last?.optString("status") in LINK_STATES && !call.final && !call.cancelled &&
            !call.noticed && !call.queued) call.noticeMissed = true
    }
    /**
     * Tells [ids] "still running" in one release, with every other call within [NOTICE_GATHER_MS] of its own notice time. With the link up,
     * a lost-link state still settling for a call it gathers is no news either: dropped, so the frame is one release whichever of its
     * timers fires first (REVIEW-E5-2 P3-1). During a return they join its one release; with a [window] they wait it in a gather of their own
     * (a result in meanwhile replaces the notice).
     */
    private fun noticeAll(ids: Collection<String>, window: Long = 0L) {
        val now = clock()
        fun near(call: Pending) = now - call.clockFrom >= CALL_NOTICE_MS - NOTICE_GATHER_MS
        if (link == Link.OK) for ((other, pending) in calls) if (other !in ids && near(pending) && calls[other] === pending && !pending.final &&
            !pending.cancelled && !pending.noticed && !pending.queued) dropLinkState(pending)
        val told = calls.entries.filter { (other, pending) -> other in ids || noticeable(other, pending) && near(pending) }.map { it.value }
        if (returning.isNotEmpty()) { hold(told); return }
        if (window > 0L && later != null) { startReturn(told, window); return }
        for ((other, pending) in calls) if (pending in told)
            tell(other, pending, stateResult(pending, "still_running", null, null), now = true, andRelease = false)
        release()
    }
    /**
     * The link is back. Every call whose notice time came while the link was down is told "still running" (its lost-link state, if not told
     * yet, is dropped: no news), in one release with the calls near their own notice time, within the spoken settle of the return: a result
     * in meanwhile, often brought by the request that brought the link back, replaces the notice (REVIEW-E5-3 P3-2), so a blip puts a notice
     * at most [SPOKEN_STATE_SETTLE_MS] after the link's return (REVIEW-E5-2 P3-1). Other settling states keep their settle (LINK-121 (b)):
     * one that ends with the link back is dropped. After an outage the owner heard, its calls start over instead ([restartHeld]).
     */
    private fun linkBack() {
        val missed = calls.filterValues { it.noticeMissed }
        for (call in missed.values) call.noticeMissed = false
        if (outageHeard) { outageHeard = false; restartHeld(); return }
        for (call in missed.values) dropLinkState(call)
        val ids = missed.filter { (id, call) -> noticeable(id, call) }.keys
        if (ids.isNotEmpty()) noticeAll(ids, window = SPOKEN_STATE_SETTLE_MS)
    }
    /**
     * After an outage the owner heard ("can't reach Hermes"), the calls it held start over as a fresh frame at the link's return (Root's rule,
     * INT-121 follow-up 16; device round 4, D1: a "still running" at the return was followed by that call's result 0.12-0.61 s later):
     * - nothing is said at the return: un-noticed, their hold and notice clocks start again (a "running" receipt read again meanwhile stays
     *   quiet: [elapsed] counts from the return);
     * - their results, read again or retried at once ([retryDelay]), go as one release when they have landed, and one still open 3 s after
     *   the return is told "still running" then, with the others near their notice; meanwhile they hold other frames' answers as any new
     *   call does, at most until that notice;
     * - so no "still running" is followed by its own result within 1 s at the return, and the frame gather ([FRAME_GATHER_MS]) and the
     *   blip's notice are unchanged.
     * A call that waited on the owner or Hermes' agent (an approval, a question, a hand-over: those keep their own states), a call waiting in
     * its frame and a phone tool are left as they were.
     */
    private fun restartHeld() {
        val wait = later ?: return
        val now = clock()
        for ((id, call) in calls) if (call.hermes && !call.final && !call.cancelled && !call.queued && !call.waitedBeforeLoss &&
            call.last?.optString("status") in LINK_STATES) {
            dropLinkState(call)
            call.noticed = false; call.holdOver = false; call.clockFrom = now
            val epoch = ++call.clockEpoch
            wait(CALL_NOTICE_MS) { update { if (call.clockEpoch == epoch) notice(id, call) } }
            wait(CALL_HOLD_MS) { update { if (call.clockEpoch == epoch) { call.holdOver = true; release() } } }
        }
    }
    /**
     * A return: [held] get "still running", held back for [window]; a result in meanwhile replaces it, and every state of a held call (a
     * hand-over too, REVIEW-E5-3 P3-1) waits for the return's one release: when each has its result, or when the time is up. Without a
     * scheduler nothing is gathered or noticed by time.
     */
    private fun startReturn(held: Collection<Pending>, window: Long) {
        returning.clear()
        val wait = later ?: return
        hold(held)
        if (returning.isEmpty()) return
        val token = ++returnToken
        wait(window) { update { if (returnToken == token && returning.isNotEmpty()) endReturn() } }
    }
    private fun hold(held: Collection<Pending>) {
        for (call in held) {
            val result = stateResult(call, "still_running", null, null)
            call.last = result; call.unsent = result; call.due = false; call.afterOutage = true
            returning += call
        }
    }
    /** The return's time is up: every call it holds goes with what it has, its "still running" now due, in one release. */
    private fun endReturn() {
        for (call in returning) if (!call.cancelled && call.unsent?.optString("status") == "still_running") call.due = true
        returning.clear()
        release()
    }
    /** A frame's new requests during a told outage are told together: a due lost-link state waits for its frame's others still settling. */
    private fun waitsForFrame(call: Pending): Boolean = call.unsent?.optString("status") in LINK_STATES &&
        calls.values.any { it !== call && it.frame == call.frame && !it.due && it.unsent?.optString("status") in LINK_STATES }
    private fun dropLinkState(call: Pending) { if (call.unsent?.optString("status") in LINK_STATES) { call.unsent = null; call.last = call.sent } }
    private fun noticeable(id: String, call: Pending): Boolean =
        calls[id] === call && !call.final && !call.cancelled && !call.noticed && !call.queued && call.unsent == null && !waitsOnOthers(call)

    /**
     * A result of a frame told "still running" (one turn for the frame, REVIEW-E5 P2-1) waits for the frame's calls still running, at most
     * [FRAME_GATHER_MS] from its own landing: the frame's ready results then go as one release. One timer per frame: a result landing while
     * the frame gathers joins it, and when it ends every ready result of the frame goes; a result landing after that starts a gather of its own.
     */
    private fun gather(call: Pending) {
        val wait = later ?: return
        if (call.frame in gathering || !openSibling(call) || calls.values.none { it.frame == call.frame && it.noticed }) return
        val token = ++gatherToken
        gathering[call.frame] = token
        wait(FRAME_GATHER_MS) { update { if (gathering[call.frame] == token) gathering.remove(call.frame); release() } }
    }
    /** Another call of [call]'s frame still running, and not waiting on the owner, Hermes' agent or the link (those are never waited for). */
    private fun openSibling(call: Pending): Boolean =
        calls.values.any { it !== call && it.frame == call.frame && !it.final && !it.cancelled && !waitsOnOthers(it) }
    /** A ready result waiting for its frame. */
    private fun gathered(call: Pending): Boolean =
        call.frame in gathering && resultTurn(call.unsent!!) == ResultTurn.Final && openSibling(call)

    /**
     * Waiting on the owner, Hermes' agent or the link (only while it is lost), its state said or still settling: it holds nothing (REVIEW-E5
     * P2-3). Once the link is back, a call told it was lost runs again: it is noticed and held like any other (REVIEW-E5-2 P3-1).
     */
    private fun waitsOnOthers(call: Pending): Boolean =
        call.last?.optString("status").let { it in WAITS_ON_OTHERS && (link == Link.LOST || it !in LINK_STATES) }
    /**
     * Still running with nothing to say yet, and not told it is going on: the model waits for it, and so does a release (up to [CALL_HOLD_MS];
     * never without a scheduler). It is told at its notice ([CALL_NOTICE_MS]), so another frame's ready answer waits at most 3 s for an
     * un-noticed call (4 s with a gather; REVIEW-E5-2 P3-3). A call told "still running" holds nothing (REVIEW-E5 P2-2): a later answer goes
     * at once. A call queued behind a change of its own frame holds only that frame's results ([release]).
     */
    private fun holds(call: Pending): Boolean = later != null &&
        !call.final && !call.cancelled && !call.noticed && !(call.unsent != null && call.due) && !waitsOnOthers(call) && !call.holdOver
    /**
     * Whether [result] tells the model something new: an answer to a repeated ask is not, nor an approval it was already told about, nor
     * "still running" again for a call it was told is going on.
     */
    private fun news(call: Pending, result: JSONObject): Boolean = when (resultTurn(result)) {
        ResultTurn.Final -> result.optString("status") != "already_requested"
        ResultTurn.Quiet -> false
        ResultTurn.Spoken -> when (result.optString("status")) {
            "needs_approval" -> approval(result)?.let { it in spokenApprovals } != true
            "still_running" -> !call.noticed || call.afterOutage
            else -> true
        }
    }
    private fun approval(result: JSONObject): String? = result.optJSONObject("proposal")?.optString("id")?.takeIf { it.isNotBlank() }

    /**
     * Sends what is due, in one release, unless a function the model is waiting on is still running and nothing due is a state the owner
     * must hear now (a first notice, an approval, a lost link, a hand-over). A call queued behind a change of its own frame holds only that
     * frame's results: the frame still answers in one release (F4), and every other frame's answer goes as if it were not there (REVIEW-E5-2
     * P2-1: a quick question asked while a write waited for its approval was held until the queued read's [CALL_HOLD_MS]).
     */
    private fun release() {
        // A blip's return gather: its calls wait until each has its result or the time is up ([startReturn]).
        if (returning.isNotEmpty() && returning.none { !it.final && !it.cancelled && !waitsOnOthers(it) }) returning.clear()
        val due = calls.entries.filter { (_, call) -> call.unsent != null && call.due && !call.cancelled && call !in returning && !waitsForFrame(call) }
        if (due.isEmpty()) return
        val urgent = due.any { (_, call) -> resultTurn(call.unsent!!) == ResultTurn.Spoken && news(call, call.unsent!!) }
        val holders = calls.values.filter { holds(it) }
        if (!urgent && holders.any { !it.queued }) return
        // A result waiting for its frame stays (FRAME_GATHER_MS), or for a call of its frame queued behind a change, unless what goes now is
        // something the owner must hear: then it goes too.
        val going = if (urgent) due else due.filter { (_, call) -> !gathered(call) && holders.none { it.frame == call.frame } }
        if (going.isEmpty()) return
        // What is news goes last, so the response that asks the model to answer is one it should answer (an answer to a repeated ask is not).
        val ordered = going.sortedBy { (_, call) -> news(call, call.unsent!!) }
        val replies = ordered.map { (id, call) -> CallReply(id, call.name, call.unsent!!) }
        val prompt = going.any { (_, call) -> news(call, call.unsent!!) }
        for ((_, call) in going) {
            if (!openSibling(call)) gathering.remove(call.frame)
            val result = call.unsent!!
            call.unsent = null
            call.sent = result
            if (resultTurn(result) == ResultTurn.Spoken) call.noticed = true
            if (result.optString("status") in LINK_STATES && resultTurn(result) == ResultTurn.Spoken) outageHeard = true
            call.afterOutage = false
            if (result.optString("status") == "needs_approval") approval(result)?.let(spokenApprovals::add)
        }
        outbox += replies to prompt
    }
    private inline fun <T> update(block: () -> T): T {
        val (value, sent, done) = synchronized(this) {
            val value = block()
            val sent = outbox.toList().also { outbox.clear() }
            // Queued for the provider under the lock, in order (REVIEW-E5 P3-1): a state can never follow its function's final. [send] only
            // builds the release and queues it; the tool window and what waits on a final hear of it after the lock.
            for ((replies, prompt) in sent) send(replies, prompt)
            Triple(value, sent, finished.toList().also { finished.clear() })
        }
        for ((replies, _) in sent) for (reply in replies) told(reply.id, reply.name, reply.result)
        for (id in done) settled(id)
        return value
    }
}
