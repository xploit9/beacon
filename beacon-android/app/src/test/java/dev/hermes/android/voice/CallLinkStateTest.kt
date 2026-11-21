package dev.hermes.android.voice

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Calls contract v3, section 6: a stalled or lost request becomes a state for the model in time, once, and the real result still comes once. */
class CallLinkStateTest {
    private class Told(val id: String, val result: JSONObject, val at: Long)
    private class Ledger(scope: TestScope, verify: (((Throwable?) -> Unit) -> Unit)? = null) {
        val told = mutableListOf<Told>()
        val window = mutableListOf<String>()
        var recovered = 0
        val link = CallLinkState({ scope.testScheduler.currentTime }, { replies, _ -> replies.forEach { told += Told(it.id, it.result, scope.testScheduler.currentTime) } },
            { _, _, result -> window += result.optString("status") }, { recovered++ }, verifyOverlap = verify)
        fun statuses(id: String) = told.filter { it.id == id }.map { it.result.getString("status") }
    }

    @Test fun stalledVerifierDoesNotOverrideNewerCallTraffic() {
        var completion: ((Throwable?) -> Unit)? = null
        var losses = 0
        val link = CallLinkState({0L}, { _, _ -> }, lostLink = { losses++ },
            verifyOverlap = { callback -> completion = callback })
        link.open("memory", "hermes_memory", "memory", hermes = true, retrySafe = true)
        link.answered()
        link.connectionFailed(IOException("old stream"), sinceAnswer = 0)
        assertNotNull(completion)
        // The verifier stalls, but several other authenticated calls on the same call pool complete.
        repeat(3) { link.answered() }
        requireNotNull(completion)(CallRequestTimeout())
        assertEquals("A single slow verification request announced a call-wide failure", 0, losses)
        assertEquals(Link.OK, link.link)
    }

    @Test fun anOlderVerifierCannotRecoverAnOutageAfterANewerGenerationBreaks() {
        var completion: ((Throwable?) -> Unit)? = null
        val link = CallLinkState({ 0L }, { _, _ -> }, verifyOverlap = { completion = it })
        link.answered()
        link.connectionFailed(IOException("older read"), sinceAnswer = 0)
        link.answered()
        link.connectionFailed(IOException("new break"))
        assertEquals(Link.LOST, link.link)
        requireNotNull(completion)(null)
        assertEquals(Link.LOST, link.link)
    }

    @Test fun aNewAmbiguityAfterNewEvidenceGetsOneNewCheck() {
        val checks = mutableListOf<(Throwable?) -> Unit>()
        var losses = 0
        val link = CallLinkState({ 0L }, { _, _ -> }, lostLink = { losses++ }, verifyOverlap = { checks += it })
        link.answered()
        link.connectionFailed(IOException("old read"), sinceAnswer = 0)
        link.answered()
        repeat(2) { link.connectionFailed(IOException("overlapping read"), sinceAnswer = 1) }
        assertEquals(1, checks.size)
        checks.first()(CallRequestTimeout())
        assertEquals(Link.OK, link.link)
        assertEquals(2, checks.size)
        checks.last()(IOException("new check failed"))
        assertEquals(Link.LOST, link.link)
        assertEquals(1, losses)
    }

    @Test fun resetAfterLastGoodAnswerStartsOutageDetection() = runTest {
        for (stillUp in listOf(true, false)) {
            val probes = mutableListOf<(Throwable?) -> Unit>()
            var outages = 0
            val link = CallLinkState({ testScheduler.currentTime }, { _, _ -> }, lostLink = { outages++ },
                probed = true, verifyOverlap = { probes += it })
            val disconnect = CompletableDeferred<Unit>()
            val operations = listOf("memory", "updates").map { id ->
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { link.track(id) { disconnect.await(); throw IOException("Synthetic stream reset") } }.exceptionOrNull()
                }
            }
            link.track(null) { Unit }
            disconnect.complete(Unit)
            for (op in operations) assertTrue(op.await() is IOException)
            assertEquals(1, probes.size)
            assertEquals(0, outages)
            probes.single()(if (stillUp) null else IOException("The fresh call-bound read failed"))
            assertEquals(if (stillUp) Link.OK else Link.LOST, link.link)
            assertEquals(if (stillUp) 0 else 1, outages)
        }
    }

    @Test fun anOlderBrokenReadDoesNotReportEveryToolOfflineAfterAnotherHermesAnswer() = runTest {
        val f = Ledger(this) { complete -> complete(null) }
        f.link.open("memory", "hermes_memory", "lookup", hermes = true, retrySafe = true)
        val broken = async {
            runCatching {
                f.link.track("memory") {
                    delay(1_000)
                    throw java.io.IOException("Synthetic stream reset")
                }
            }.exceptionOrNull()
        }
        delay(500)
        assertEquals("result", f.link.track(null) { "result" })
        assertTrue(broken.await() is java.io.IOException)
        assertEquals(Link.OK, f.link.link)
        assertFalse("hermes_unreachable" in f.statuses("memory"))
    }

    @Test fun anIoFailureLosesTheLinkAndEveryPendingCallHearsItsOwnStateOnce() = runTest {
        val f = Ledger(this)
        f.link.open("asked", "notes_search", "req-1", hermes = true, retrySafe = true)
        f.link.open("running", "hermes_work", "req-2", hermes = true, retrySafe = true)
        f.link.open("phone", "get_visual_state", "req-3", hermes = false, retrySafe = true)
        f.link.ran("running", true)
        f.link.connectionFailed()
        f.link.connectionFailed()
        assertEquals(Link.LOST, f.link.link)
        assertEquals(listOf("hermes_unreachable"), f.statuses("asked"))
        assertEquals(listOf("lost_result"), f.statuses("running"))
        assertTrue("A phone-side function does not depend on Hermes", f.statuses("phone").isEmpty())
        val unreachable = f.told.first { it.id == "asked" }.result
        for (key in listOf("status", "state_source", "request_id", "tool", "elapsed_ms", "ran_on_hermes", "retry_safe", "will_update", "detail"))
            assertTrue(key, unreachable.has(key))
        assertEquals("phone", unreachable.getString("state_source"));assertEquals("connection_lost", unreachable.getString("detail"))
        assertEquals(JSONObject.NULL, unreachable.get("ran_on_hermes"));assertTrue(unreachable.getBoolean("will_update"))
        assertEquals("result_not_received", f.told.first { it.id == "running" }.result.getString("detail"))
        assertEquals("The tool window hears the same states", listOf("hermes_unreachable", "lost_result"), f.window)
    }

    @Test fun oneTimeoutIsNotALostLinkButTwoAreAndAnAnswerResetsTheCount() = runTest {
        val f = Ledger(this)
        f.link.open("asked", "discover_tools", "req-1", hermes = true, retrySafe = true)
        f.link.timedOut();assertEquals(Link.OK, f.link.link);assertTrue(f.told.isEmpty())
        f.link.answered();f.link.timedOut();assertEquals(Link.OK, f.link.link)
        f.link.timedOut();assertEquals(Link.LOST, f.link.link)
        assertEquals("no_response", f.told.single().result.getString("detail"))
        f.link.answered();assertEquals(Link.OK, f.link.link);assertEquals("Coming back is reported once", 1, f.recovered)
    }

    @Test fun theSameStateIsNeverSentTwiceInARowAndTheFinalResultExactlyOnce() = runTest {
        val f = Ledger(this)
        f.link.open("call", "notes_search", "req-1", hermes = true, retrySafe = true)
        f.link.state("call", "still_running");f.link.state("call", "still_running")
        f.link.ran("call", true);f.link.connectionFailed();f.link.answered();f.link.state("call", "still_running")
        assertTrue(f.link.final("call", JSONObject().put("status", "completed").put("result", JSONObject().put("ok", true))))
        assertFalse("A late duplicate of the result is dropped", f.link.final("call", JSONObject().put("status", "completed")))
        f.link.state("call", "lost_result");f.link.connectionFailed()
        assertFalse(f.link.failed("call", "connection_lost"))
        assertEquals(listOf("still_running", "lost_result", "still_running", "completed"), f.statuses("call"))
        val final = f.told.last().result
        assertFalse(final.getBoolean("will_update"));assertEquals(true, final.get("ran_on_hermes"))
        assertFalse("The provider asking again is not a new call", f.link.open("call", "notes_search", "req-1", hermes = true, retrySafe = true))
        assertEquals("Nothing is sent after the final result", 4, f.told.size)
    }

    @Test fun aStalledLinkReachesTheModelWithinFiveSecondsAndTheLateResultOnceWithinFiveSecondsOfRecovery() = runTest {
        val f = Ledger(this)
        // The fault starts at 0 and lasts 45 s. OkHttp pings every 1.5 s and fails the connection when a ping is unanswered at the
        // next tick (CallHttpClientTest proves it on a real connection): every request on the stalled link fails within 3 s.
        val outage = 45_000L
        var sent = 0
        f.link.open("search", "notes_search", "req-1", hermes = true, retrySafe = true)
        val answer = async {
            f.link.request("search", CALL_INVOKE_WAIT_MS, retrySafe = true, owned = { true }) {
                sent++
                if (testScheduler.currentTime < outage) { delay(3_000); throw java.io.IOException("sent ping but didn't receive pong within 1500ms") }
                JSONObject().put("status", "completed").put("request_id", "req-1").put("ran_on_hermes", true)
            }
        }
        val result = answer.await()
        f.link.final("search", result)
        val unreachable = f.told.first()
        assertEquals("hermes_unreachable", unreachable.result.getString("status"))
        assertTrue("State after ${unreachable.at} ms", unreachable.at <= 5_000)
        val final = f.told.last()
        assertEquals("completed", final.result.getString("status"))
        assertTrue("Result ${final.at - outage} ms after the link came back", final.at - outage in 0..5_000)
        assertEquals("One state, then the result: nothing said twice", listOf("hermes_unreachable", "completed"), f.statuses("search"))
        assertTrue("The same request was sent again, never a new one", sent > 1)
        assertEquals(Link.OK, f.link.link)
    }

    @Test fun aRefusalIsNeverResentAndARequestThatMayNotBeResentFailsOnce() = runTest {
        val f = Ledger(this)
        var sent = 0
        val refused = runCatching { f.link.request("a", 0, retrySafe = true, owned = { true }) { sent++;throw ApiFailure(409, "Tool changed") } }.exceptionOrNull()
        assertTrue(refused is ApiFailure);assertEquals(1, sent);assertEquals(Link.OK, f.link.link)
        sent = 0
        val lost = runCatching { f.link.request("b", 0, retrySafe = false, owned = { true }) { sent++;throw java.io.IOException("reset") } }.exceptionOrNull()
        assertTrue(lost is java.io.IOException);assertEquals(1, sent);assertEquals(Link.LOST, f.link.link)
    }

    @Test fun aRequestWithoutAnAnswerTimesOutAfterItsServerWaitAndASecondOneLosesTheLink() = runTest {
        val f = Ledger(this)
        f.link.open("call", "notes_search", "req-1", hermes = true, retrySafe = true)
        val started = testScheduler.currentTime
        val job = launch { runCatching { f.link.request("call", CALL_INVOKE_WAIT_MS, retrySafe = true, owned = { true }) { awaitCancellation() } } }
        delay(CALL_INVOKE_WAIT_MS + CALL_REQUEST_MARGIN_MS - 1)
        assertEquals("No answer yet within the server wait plus the margin", Link.OK, f.link.link)
        assertTrue(f.told.isEmpty())
        delay(2 * (CALL_INVOKE_WAIT_MS + CALL_REQUEST_MARGIN_MS))
        assertEquals(Link.LOST, f.link.link)
        assertTrue(f.told.single().at - started <= 2 * (CALL_INVOKE_WAIT_MS + CALL_REQUEST_MARGIN_MS) + 500)
        job.cancel()
    }

    // REVIEW-INT-121 finding 1: the updates poll usually notices a stall first, so the link is lost while no function waits; a tool the
    // model calls after that hears so at once (not when Hermes answers again), and its real result still comes once.
    @Test fun aFunctionCalledAfterTheLinkWasLostIsToldAtOnceAndItsResultLater() = runTest {
        val f = Ledger(this)
        runCatching { f.link.track(null, 20_000) { throw java.io.IOException("sent ping but didn't receive pong within 1500ms") } }
        assertEquals(Link.LOST, f.link.link)
        assertTrue("Nothing waited, so nothing was told", f.told.isEmpty())
        f.link.open("tool", "notes_search", "req-1", hermes = true, retrySafe = true)
        f.link.open("phone", "get_visual_state", "req-2", hermes = false, retrySafe = true)
        val job = launch { runCatching { f.link.request("tool", CALL_INVOKE_WAIT_MS, retrySafe = true, owned = { true }) { awaitCancellation() } } }
        delay(60_000)
        job.cancel()
        assertEquals("told once, at the call", listOf("hermes_unreachable"), f.statuses("tool"))
        assertEquals(0L, f.told.single { it.id == "tool" }.at)
        assertEquals("connection_lost", f.told.single { it.id == "tool" }.result.getString("detail"))
        assertTrue("A phone-side function does not depend on Hermes", f.statuses("phone").isEmpty())
        f.link.answered()
        assertTrue(f.link.final("tool", JSONObject().put("status", "completed").put("result", JSONObject().put("ok", true))))
        assertEquals(listOf("hermes_unreachable", "completed"), f.statuses("tool"))
    }

    @Test fun theCallEndingStopsTheResends() = runTest {
        val f = Ledger(this)
        var owned = true;var sent = 0
        val error = runCatching { f.link.request("call", 0, retrySafe = true, owned = { owned }) { sent++;if (sent == 3) owned = false;throw java.io.IOException("down") } }.exceptionOrNull()
        assertTrue(error is IllegalStateException);assertEquals(3, sent)
    }

    // LINK-121 (b): a one-off failure that the retry answers within a second is never told to the model; a link that stays lost is told
    // once, after LINK_STATE_SETTLE_MS.
    @Test fun aOneOffFailureTheRetryAnswersIsNeverToldAndALostLinkIsToldAfterItsSettle() = runTest {
        val told = mutableListOf<Told>()
        val link = CallLinkState({ testScheduler.currentTime }, { replies, _ -> replies.forEach { told += Told(it.id, it.result, testScheduler.currentTime) } },
            later = { ms, block -> launch { delay(ms); block() } })
        link.open("call", "notes_search", "req-1", hermes = true, retrySafe = true)
        link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(1_000)
        link.answered()
        delay(1_000)
        assertTrue("a blip the retry answered is silent", told.none { it.result.optString("status") == "hermes_unreachable" })
        val failedAt = testScheduler.currentTime
        link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(LINK_STATE_SETTLE_MS - 100)
        assertTrue(told.none { it.result.optString("status") == "hermes_unreachable" })
        delay(200)
        val state = told.single { it.result.optString("status") == "hermes_unreachable" }
        assertEquals(failedAt + LINK_STATE_SETTLE_MS, state.at)
        link.final("call", JSONObject().put("status", "completed"))
    }

    // LINK-121 (d): the failure that lost the link names itself in the state's tool receipt and the call's tool log (a loss raised inside
    // the phone was recorded only as "network"); the model and the synced timeline never get it.
    @Test fun aLostLinkNamesWhatLostItInItsReceiptButNotToTheModel() = runTest {
        val f = Ledger(this)
        f.link.open("call", "notes_search", "req-1", hermes = true, retrySafe = true)
        f.link.connectionFailed(java.net.SocketException("Connection reset"))
        val state = f.told.single().result
        assertEquals("SocketException", state.getString("cause_class"))
        assertFalse("not for the model", GeminiWire.tool("call", "notes_search", state).toString().contains("cause_class"))
        val receipt = JSONObject().put("type", "tool_receipt").put("request_id", "req-1").put("tool_name", "notes_search").put("status", "hermes_unreachable")
            .put("cause_class", "SocketException")
        assertEquals("SocketException", toolLogEntry(receipt, 5).getString("cause_class"))
        assertFalse("the plugin refuses it on a timeline tool receipt", timelineToolReceipt(receipt)!!.has("cause_class"))
        f.link.answered()
        f.link.open("late", "notes_search", "req-2", hermes = true, retrySafe = true)
        f.link.timedOut(); f.link.timedOut()
        assertEquals("CallRequestTimeout", f.told.last { it.id == "late" }.result.getString("cause_class"))
    }

    // REVIEW-E5 P3-1 (proofO, inverted): releases are queued for the provider under the ledger's lock, so a state is never sent after its
    // function's final, however late the thread that told it runs.
    @Test(timeout = 10000) fun aStateIsNeverSentAfterItsFunctionsFinal() {
        val order = java.util.Collections.synchronizedList(ArrayList<String>())
        val stateSending = java.util.concurrent.CountDownLatch(1)
        val link = CallLinkState(clock = { 0L }, send = { replies, _ ->
            val status = replies.last().result.optString("status")
            if (status == "still_running") { stateSending.countDown();Thread.sleep(500) }
            order += status
        })
        link.open("x", "notes_search", "req-x", hermes = true, retrySafe = true)
        val t1 = Thread { link.state("x", "still_running") }.apply { start() }
        stateSending.await(5, java.util.concurrent.TimeUnit.SECONDS)
        link.final("x", JSONObject().put("status", "completed"))
        t1.join(5_000)
        assertEquals(listOf("still_running", "completed"), order.toList())
    }

    // E5 follow-up: a ledger on the test's virtual time, as the call runs it, and every release it sends (when, which calls, which states).
    private class Released(val at: Long, val ids: List<String>, val statuses: List<String>, val prompt: Boolean)
    private class Frames(scope: TestScope, probed: Boolean = false) {
        val released = mutableListOf<Released>()
        val link = CallLinkState({ scope.testScheduler.currentTime }, { replies, prompt ->
            released += Released(scope.testScheduler.currentTime, replies.map { it.id }, replies.map { it.result.optString("status") }, prompt)
        }, later = { ms, block -> scope.launch { delay(ms);block() } }, probed = probed)
        fun open(vararg ids: String) { link.frame(ids.toList());for (id in ids) link.open(id, "notes_search", "req-$id", hermes = true, retrySafe = true) }
        fun done(id: String) = link.final(id, JSONObject().put("status", "completed"))
        fun answers() = released.filter { r -> r.statuses.any { it == "completed" } }
    }

    // E5 follow-up: three reads of one frame finishing at 6.0, 6.2 and 6.5 s are one "still running" release, then ONE spoken release.
    @Test fun aSlowFrameIsOneStillRunningTurnThenOneAnswer() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(6_000);f.done("a")
        delay(200);f.done("b")
        delay(300);f.done("c")
        delay(2_000)
        val notices = f.released.filter { r -> r.statuses.all { it == "still_running" } }
        assertEquals("one notice for the frame", listOf(listOf("a", "b", "c")), notices.map { it.ids })
        assertEquals("one answer for the frame", 1, f.answers().size)
        val answer = f.answers().single()
        assertEquals(setOf("a", "b", "c"), answer.ids.toSet());assertTrue(answer.prompt);assertEquals(6_500L, answer.at)
    }

    // E5 follow-up: reads of one frame finishing at 6, 9 and 12 s: none waits more than FRAME_GATHER_MS after its own result, and the last
    // goes at once (before: 6, 9 and 12 s, each alone; a wait-for-all rule would hold the first for 6 s).
    @Test fun aResultOfASlowFrameWaitsAtMostTheGatherBound() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(6_000);f.done("a")
        delay(3_000);f.done("b")
        delay(3_000);f.done("c")
        delay(2_000)
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), f.answers().map { it.ids })
        assertEquals(listOf(6_000L + FRAME_GATHER_MS, 9_000L + FRAME_GATHER_MS, 12_000L), f.answers().map { it.at })
    }

    // E5 follow-up: while a frame gathers, an answer of another frame goes at once and alone; the frame's results then go together.
    @Test fun anotherFramesAnswerGoesAtOnceWhileAFrameGathers() = runTest {
        val f = Frames(this)
        f.open("a", "b")
        delay(5_000);f.open("x")
        delay(1_000);f.done("a")
        delay(200);f.done("x")
        delay(300);f.done("b")
        delay(2_000)
        assertEquals(listOf(listOf("x") to 6_200L, listOf("a", "b") to 6_500L), f.answers().map { it.ids to it.at })
    }

    // E5 follow-up: what the owner must act on is never held, and never waited for: a sibling waiting for an approval does not hold a ready
    // result, and a hand-over told during a gather goes at once, with the ready result.
    @Test fun nothingTheOwnerMustHearWaitsForAFrame() = runTest {
        val f = Frames(this)
        f.open("a", "w")
        delay(3_500);f.link.state("w", "needs_approval")
        delay(2_500);f.done("a")
        assertEquals("not waited for the approval", 6_000L, f.answers().single().at)
        val g = Frames(this)
        g.open("p", "q", "h")
        delay(6_000);g.done("p")
        delay(300);g.link.state("h", "handed_over")
        val told = g.released.last()
        assertEquals("the hand-over goes at once, with the ready result", 12_300L, told.at)
        assertEquals(setOf("p", "h"), told.ids.toSet());assertTrue(told.prompt)
    }

    // E5 follow-up: without a scheduler nothing gathers (and nothing is noticed): a result goes at once.
    @Test fun withoutASchedulerAFramesResultGoesAtOnce() = runTest {
        val f = Ledger(this)
        f.link.frame(listOf("a", "b"))
        f.link.open("a", "notes_search", "req-a", hermes = true, retrySafe = true);f.link.open("b", "notes_search", "req-b", hermes = true, retrySafe = true)
        f.link.final("a", JSONObject().put("status", "completed"))
        assertEquals(listOf("completed"), f.statuses("a"))
    }

    // REVIEW-E5-2 P2-1 (proofQ, inverted, on virtual time): a read queued behind its frame's write holds only its own frame. The write is told
    // "still running" at 3 s while the read waits for it; another frame's answer at 4.2 s goes at once (before: at the read's 10 s bound).
    @Test fun aReadQueuedBehindItsFramesWriteHoldsNoOtherFramesAnswer() = runTest {
        val f = Frames(this)
        f.open("w", "r");f.link.queued("r", true)
        delay(4_000);f.open("x")
        delay(200);f.done("x")
        delay(1_000)
        assertEquals(listOf(listOf("x") to 4_200L), f.answers().map { it.ids to it.at })
    }

    // REVIEW-E5-2 P2-1: F4 stays one release. The write lands while the read of its frame still waits for it: the write's result waits for
    // that read, and both go together when it lands (a holds() that left queued calls out would send the write alone, a turn of its own).
    @Test fun aWriteAndTheReadQueuedBehindItAnswerInOneRelease() = runTest {
        val f = Frames(this)
        f.open("w", "r");f.link.queued("r", true)
        delay(600);f.done("w")
        assertTrue("the write's result waits for the read of its frame", f.answers().isEmpty())
        f.link.queued("r", false)
        delay(300);f.done("r")
        assertEquals(listOf(setOf("w", "r") to 900L), f.answers().map { it.ids.toSet() to it.at })
        assertTrue(f.answers().single().prompt)
    }

    // REVIEW-E5-2 P3-1, Root's rule, with REVIEW-E5-3 P3-2's spoken settle: a frame of three reads; the link is lost at 2.0 s (the first
    // read's state settles, the others are told quietly) and back at 3.2 s, after the notice time: the frame is told "still running" within
    // the spoken settle of the return (3.7 s), in one release; another frame's answer at 4.0 s goes at once (follow-up 13: at 3.2 s).
    @Test fun aBlipAcrossTheNoticeTimeNoticesTheFrameWithinTheSpokenSettleOfTheReturn() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(1_200);f.link.answered()
        delay(600);f.open("x")
        delay(200);f.done("x")
        delay(1_000)
        assertTrue("the blip is never said", f.released.none { r -> "hermes_unreachable" in r.statuses && r.prompt })
        val notices = f.released.filter { r -> "still_running" in r.statuses }
        assertEquals(listOf(setOf("a", "b", "c") to 3_200L + SPOKEN_STATE_SETTLE_MS), notices.map { it.ids.toSet() to it.at })
        assertTrue(notices.single().prompt)
        assertEquals(listOf(listOf("x") to 4_000L), f.answers().map { it.ids to it.at })
    }

    // REVIEW-E5-2 P3-1, Root's rule: the link back at 2.8 s, before the notice time: the frame is told at 3 s, in one release, as without
    // the blip (before: one call told at 6.5 s).
    @Test fun aBlipOverBeforeTheNoticeTimeNoticesTheFrameAtThreeSeconds() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(800);f.link.answered()
        delay(3_000)
        val notices = f.released.filter { r -> "still_running" in r.statuses }
        assertEquals(listOf(setOf("a", "b", "c") to 3_000L), notices.map { it.ids.toSet() to it.at })
    }

    // REVIEW-E5-2 P3-1, a guard: a blip over before the notice time leaves the notice as it was, once, at 3 s.
    @Test fun aLinkBlipBeforeTheNoticeTimeLeavesOneNotice() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(1_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(500);f.link.answered()
        delay(5_000)
        assertEquals(listOf(3_000L), f.released.filter { r -> "still_running" in r.statuses }.map { it.at })
    }

    // REVIEW-E5-2 P3-1: the frame is one release whichever timer fires first. A frame of a phone tool p (no Hermes link) and a read a; the
    // link is lost at 2.0 s (a's state settles) and back at 2.8 s. p's timer fires first at 3 s: its notice drops a's settling state too and
    // takes a along (before: p alone at 3 s, then a alone, two turns).
    @Test fun aBlipOverBeforeTheNoticeTimeIsOneReleaseWhicheverTimerFiresFirst() = runTest {
        val f = Frames(this)
        f.link.frame(listOf("p", "a"))
        f.link.open("p", "phone_timer_set", "req-p", hermes = false, retrySafe = true)
        f.link.open("a", "notes_search", "req-a", hermes = true, retrySafe = true)
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(800);f.link.answered()
        delay(3_000)
        val notices = f.released.filter { r -> "still_running" in r.statuses }
        assertEquals(listOf(setOf("p", "a") to 3_000L), notices.map { it.ids.toSet() to it.at })
    }

    // Follow-up 16 (Root's rule A, replacing follow-up 14's): after an outage that was told (a's state spoken at 3.5 s, b and c told
    // quietly), the link's return at 8 s says nothing; the frame starts over, and still open 3 s later it is told "still running" in one
    // release with one prompt (follow-up 14: "still running" for all three 1 s after the return).
    @Test fun afterAToldOutageTheFrameStartsOverAtTheReturn() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(6_000);f.link.answered()
        delay(4_000)
        assertEquals(listOf(3_500L), f.released.filter { r -> "hermes_unreachable" in r.statuses && r.prompt }.map { it.at })
        val back = f.released.filter { it.at >= 8_000L }
        assertEquals(listOf(setOf("a", "b", "c") to 8_000L + CALL_NOTICE_MS), back.map { it.ids.toSet() to it.at })
        assertTrue(back.single().prompt);assertEquals(setOf("still_running"), back.single().statuses.toSet())
    }

    // Follow-up 16 (rule A): a result in after a told outage's return waits for its frame's others, which hold as a new frame's calls do,
    // and goes with them: when they land, or at their notice 3 s after the return with "still running" for those still open.
    @Test fun afterAToldOutageAResultInAtTheReturnGoesWithItsFrame() = runTest {
        val f = Frames(this)
        f.open("a", "b", "c")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(6_000);f.link.answered()
        delay(300);f.done("a")
        delay(4_000)
        val back = f.released.filter { it.at >= 8_000L }
        assertEquals(listOf(8_000L + CALL_NOTICE_MS), back.map { it.at })
        assertEquals(mapOf("a" to "completed", "b" to "still_running", "c" to "still_running"), back.single().ids.zip(back.single().statuses).toMap())
        assertTrue(back.single().prompt)
        val g = Frames(this)
        val start = testScheduler.currentTime
        g.open("a", "b")
        delay(2_000);g.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(6_000);g.link.answered()
        delay(100);g.done("a")
        delay(100);g.done("b")
        delay(4_000)
        assertEquals("all in: at once", listOf(setOf("a", "b") to start + 8_200L), g.released.filter { it.at >= start + 8_000L }.map { it.ids.toSet() to it.at })
    }

    // Follow-up 14: the told outage's return leaves an approval as it was: no "still running" for a call that waited on the owner.
    @Test fun afterAToldOutageTheReturnLeavesAnApprovalAsItWas() = runTest {
        val f = Frames(this)
        f.open("a", "w")
        f.link.state("w", "needs_approval")
        delay(5_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(5_000);f.link.answered()
        delay(4_000)
        assertTrue("the outage was told", f.released.any { r -> r.statuses.any { it == "hermes_unreachable" } && r.prompt })
        val back = f.released.filter { it.at >= 10_000L }
        assertEquals(listOf(listOf("a") to 10_000L + CALL_NOTICE_MS), back.map { it.ids to it.at })
    }

    // REVIEW-E5-3 P2-1 (proofX, inverted): a question asked during an outage the owner already heard of is a new request: it is told on its
    // own that it cannot reach Hermes, after the spoken settle, and a frame of two new questions is one release. When the link returns, the
    // return's rule answers them with the call the outage held, in one release (before: nothing spoken for them until the return).
    @Test fun aQuestionAskedDuringAToldOutageIsToldItCannotReachHermes() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(3_000)
        f.open("x", "y")
        delay(3_000);f.link.answered()
        delay(4_000)
        assertEquals("the outage was told", listOf(listOf("a") to 3_500L), f.released.filter { r -> "hermes_unreachable" in r.statuses && r.prompt && r.at < 5_000L }.map { it.ids to it.at })
        val asked = f.released.filter { it.at in 5_000L until 8_000L && it.prompt }
        assertEquals(listOf(setOf("x", "y") to 5_000L + SPOKEN_STATE_SETTLE_MS), asked.map { it.ids.toSet() to it.at })
        assertEquals(setOf("hermes_unreachable"), asked.single().statuses.toSet())
        val back = f.released.filter { it.at >= 8_000L }
        assertEquals(listOf(setOf("a", "x", "y") to 8_000L + CALL_NOTICE_MS), back.map { it.ids.toSet() to it.at })
    }

    // REVIEW-E5-3 P3-2 (proofB, inverted): a blip across the notice time, the link brought back by the request that carries the call's
    // result: the result replaces the notice; no "still running" just before it.
    @Test fun aBlipReturnCarryingTheResultSpeaksOnlyTheResult() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(1_200);f.link.answered();f.done("a")
        delay(3_000)
        assertEquals(listOf(listOf("completed") to 3_200L), f.released.filter { it.at >= 3_200L && it.prompt }.map { it.statuses to it.at })
    }

    // Follow-up 16 (rule A): a hand-over admitted just after a told outage's return keeps its own state, told at once (8.2 s); its sibling,
    // started over at the return, is told "still running" 3 s after the return (E6's option (b) held the hand-over in the return's release,
    // which no longer exists).
    @Test fun aHandOverAfterAToldOutagesReturnIsToldAtOnce() = runTest {
        val f = Frames(this)
        f.link.frame(listOf("a", "h"))
        f.link.open("a", "notes_search", "req-a", hermes = true, retrySafe = true)
        f.link.open("h", "hermes_work", "req-h", hermes = true, retrySafe = true)
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(6_000);f.link.answered()
        delay(200);f.link.state("h", "handed_over")
        delay(4_000)
        assertTrue("the outage was told", f.released.any { r -> "hermes_unreachable" in r.statuses && r.prompt })
        val spoken = f.released.filter { it.at >= 8_000L && it.prompt }
        assertEquals(listOf(listOf("h") to 8_200L, listOf("a") to 8_000L + CALL_NOTICE_MS), spoken.map { it.ids to it.at })
        assertEquals(listOf("handed_over"), spoken.first().statuses)
    }

    // REVIEW-E5-3 P3-3 (checkR4, adopted): a single call, a blip from 2.0 to 2.8 s: one notice at 3 s; another frame's answer at 4.0 s goes at
    // once; the blip is never said.
    @Test fun aSingleCallBlipBeforeItsNoticeHoldsNoLaterAnswer() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(800);f.link.answered()
        delay(1_000);f.open("x")
        delay(200);f.done("x")
        delay(2_000)
        assertEquals("one notice for a, at 3 s", listOf(listOf("a") to 3_000L), f.released.filter { "still_running" in it.statuses }.map { it.ids to it.at })
        assertEquals("x answered at once", listOf(4_000L), f.released.filter { "x" in it.ids && "completed" in it.statuses }.map { it.at })
        assertTrue("the blip is never said", f.released.none { r -> "hermes_unreachable" in r.statuses && r.prompt })
    }

    // Follow-up 15, on the blip's return gather (follow-up 16: a told outage's return no longer gathers): a phone tool near its own notice
    // time goes in the blip's one release with the call whose notice passed in the blip.
    @Test fun aNoticeInsideAReturnJoinsItsOneRelease() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(400);f.link.open("p", "phone_timer_set", "req-p", hermes = false, retrySafe = true)
        delay(1_600);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(1_200);f.link.answered()
        delay(2_000)
        val notices = f.released.filter { "still_running" in it.statuses }
        assertEquals(listOf(setOf("a", "p") to 3_200L + SPOKEN_STATE_SETTLE_MS), notices.map { it.ids.toSet() to it.at })
        assertTrue(notices.single().prompt)
    }

    // Follow-up 15, on the blip's return gather: the link lost again inside it. The phone tool, which does not use the link, is told "still
    // running" at once (nothing held is left without a time to go); the Hermes call gets its lost-link state again, after its settle.
    @Test fun aLossInsideAReturnLeavesNoHeldCallBehind() = runTest {
        val f = Frames(this)
        f.open("a")
        delay(400);f.link.open("p", "phone_timer_set", "req-p", hermes = false, retrySafe = true)
        delay(1_600);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(1_200);f.link.answered()
        delay(300);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(3_000)
        assertEquals("p told at the new loss", listOf(listOf("p") to 3_500L), f.released.filter { "still_running" in it.statuses }.map { it.ids to it.at })
        assertEquals("a told the link is lost again", listOf(listOf("a") to 3_500L + LINK_STATE_SETTLE_MS),
            f.released.filter { "hermes_unreachable" in it.statuses && it.prompt }.map { it.ids to it.at })
    }

    // Device round 4, D1: a request waiting its retry backoff while the link is lost goes again as soon as the link comes back (here another
    // request's answer at 2.0 s), not at the end of its backoff (before: at 3.5 s; native reads slept out 0.5, 1, 2 s and landed after the
    // return).
    @Test fun aRetryWaitingItsBackoffGoesAgainAsTheLinkComesBack() = runTest {
        val f = Frames(this)
        f.open("a")
        var down = true
        val sent = mutableListOf<Long>()
        val job = launch { f.link.request("a", 0, retrySafe = true, owned = { true }) {
            sent += testScheduler.currentTime
            if (down) throw java.io.IOException("Connection reset")
            JSONObject().put("status", "completed")
        } }
        delay(2_000)
        down = false
        runCatching { f.link.track(null) { JSONObject() } }
        job.join()
        assertEquals(listOf(0L, 500L, 1_500L, 2_000L), sent)
    }

    // Device round 4, D1, with Root's rule A: round 4's told-outage shapes on the ledger's virtual time. A frame [memory, context, web] asked
    // during the outage (told at 3.6 s); in new0/new1 a question asked after it was told (its own "can't reach Hermes" at 10.5 s). Hermes is
    // back at 15 s, where another request's answer brings the link back; every retry waiting its backoff goes again at once, and Hermes
    // answers memory, context and the new question 0.08 s later and web 1.1 s (told0, new0) or 1.3 s (told1, new1) later. One spoken release
    // at the return, when the last lands; no "still running" (before: "still running" 1 s after the return, then the late results).
    private suspend fun TestScope.toldReturn(webMs: Long, newQuestion: Boolean, staleAnswerAt: Long? = null, probed: Boolean = false): List<Released> {
        val f = Frames(this, probed)
        var down = true
        val hermesMs = mapOf("m" to 80L, "c" to 80L, "w" to webMs, "x" to 80L)
        fun ask(id: String) = launch {
            val result = f.link.request(id, 0, retrySafe = true, owned = { true }) {
                if (down) throw java.io.IOException("Connection reset")
                delay(hermesMs.getValue(id));JSONObject().put("status", "completed")
            }
            f.link.final(id, result)
        }
        delay(2_000);f.link.connectionFailed(java.net.SocketException("Connection reset"))
        delay(100);f.open("m", "c", "w");for (id in listOf("m", "c", "w")) ask(id)
        // A request sent before the loss answered after it: the link is back for a moment, and the retries fail again (a new loss).
        if (staleAnswerAt != null) { delay(staleAnswerAt - testScheduler.currentTime);runCatching { f.link.track(null) { JSONObject() } } }
        if (newQuestion) { delay(10_000 - testScheduler.currentTime);f.link.open("x", "notes_search", "req-x", hermes = true, retrySafe = true);ask("x") }
        delay(15_000 - testScheduler.currentTime)
        down = false
        runCatching { f.link.track(null) { JSONObject() } }
        delay(6_000)
        assertTrue("the outage was told", f.released.any { r -> r.prompt && "hermes_unreachable" in r.statuses && r.at < 15_000L })
        if (newQuestion) assertTrue("the new question heard it on its own", f.released.any { r -> r.prompt && r.ids == listOf("x") && r.at == 10_500L })
        return f.released.filter { it.at >= 15_000L }
    }
    private fun oneReturnRelease(back: List<Released>, at: Long, ids: Set<String>) {
        val spoken = back.filter { it.prompt }
        assertEquals("one spoken release at the return: ${back.map { it.ids.zip(it.statuses) to it.at }}", listOf(ids to at), spoken.map { it.ids.toSet() to it.at })
        assertTrue("every result, no still running", back.all { r -> r.statuses.all { it == "completed" } })
    }
    @Test fun told0_aToldOutagesReturnIsOneReleaseWhenTheLastLands() = runTest { oneReturnRelease(toldReturn(1_100, false), 16_100L, setOf("m", "c", "w")) }
    @Test fun told1_aToldOutagesReturnIsOneReleaseWhenTheLastLands() = runTest { oneReturnRelease(toldReturn(1_300, false), 16_300L, setOf("m", "c", "w")) }
    @Test fun new0_aToldOutagesReturnWithANewQuestionIsOneRelease() = runTest { oneReturnRelease(toldReturn(1_100, true), 16_100L, setOf("m", "c", "w", "x")) }
    @Test fun new1_aToldOutagesReturnWithANewQuestionIsOneRelease() = runTest { oneReturnRelease(toldReturn(1_300, true), 16_300L, setOf("m", "c", "w", "x")) }

    // REVIEW-E7 P3-1, the guard: round 4's told-outage shape with a stale answer 0.3 s into the outage (the link back for a moment; the
    // retries fail again). The real return at 15 s still wakes every retry at once: one spoken release when the last lands.
    @Test fun told0_aStaleAnswerInTheOutageKeepsTheReturnsTiming() = runTest { oneReturnRelease(toldReturn(1_100, false, staleAnswerAt = 2_300), 16_100L, setOf("m", "c", "w")) }
    @Test fun new1_aStaleAnswerInTheOutageKeepsTheReturnsTiming() = runTest { oneReturnRelease(toldReturn(1_300, true, staleAnswerAt = 2_300), 16_300L, setOf("m", "c", "w", "x")) }

    // REVIEW-E7 P3-1: a request whose own failure lost the link waits its whole backoff, whatever Hermes answers meanwhile (here another
    // request at 0.2 s brings the link back). Before, that return sent it again at once.
    @Test fun aRequestWhoseFailureLostTheLinkWaitsItsBackoffThroughAReturn() = runTest {
        val f = Frames(this)
        f.open("a")
        val sent = mutableListOf<Long>()
        val job = launch { f.link.request("a", 0, retrySafe = true, owned = { true }) {
            sent += testScheduler.currentTime
            if (sent.size == 1) throw java.io.IOException("Connection reset")
            JSONObject().put("status", "completed")
        } }
        delay(200)
        runCatching { f.link.track(null) { JSONObject() } }
        job.join()
        assertEquals(listOf(0L, 500L), sent)
    }

    // REVIEW-E7 P3-1: the same told shapes as DirectVoice runs the link, with the lost link's probe (only the probe tries while the link is
    // lost; the answer at 15 s stands in for it): still one spoken release when the last lands.
    @Test fun told0_withTheProbe() = runTest { oneReturnRelease(toldReturn(1_100, false, probed = true), 16_100L, setOf("m", "c", "w")) }
    @Test fun told1_withTheProbe() = runTest { oneReturnRelease(toldReturn(1_300, false, probed = true), 16_300L, setOf("m", "c", "w")) }
    @Test fun new0_withTheProbe() = runTest { oneReturnRelease(toldReturn(1_100, true, probed = true), 16_100L, setOf("m", "c", "w", "x")) }
    @Test fun new1_withTheProbe() = runTest { oneReturnRelease(toldReturn(1_300, true, probed = true), 16_300L, setOf("m", "c", "w", "x")) }
    @Test fun told0_aStaleAnswerWithTheProbe() = runTest { oneReturnRelease(toldReturn(1_100, false, staleAnswerAt = 2_300, probed = true), 16_100L, setOf("m", "c", "w")) }
    @Test fun new1_aStaleAnswerWithTheProbe() = runTest { oneReturnRelease(toldReturn(1_300, true, staleAnswerAt = 2_300, probed = true), 16_300L, setOf("m", "c", "w", "x")) }
}
