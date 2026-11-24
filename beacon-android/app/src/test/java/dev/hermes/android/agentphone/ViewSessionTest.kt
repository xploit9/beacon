package dev.hermes.android.agentphone

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The view's session against a fake of L2's routes and socket (CONTRACT.md §2), on virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewSessionTest {
    private class Connection(private val now: () -> Long) : ViewConnection {
        val sent = mutableListOf<Pair<Long, String>>()
        var closedWith: Int? = null
        var open = true
        override fun send(text: String): Boolean { if (open) sent += now() to text; return open }
        override fun close(code: Int) { closedWith = code; open = false }
        override fun cancel() { open = false }
    }

    private class Transport(private val now: () -> Long) : ViewTransport {
        var ticketCalls = 0
        var answer: () -> ViewTicket = { ViewTicket("t$ticketCalls") }
        val opened = mutableListOf<Pair<Connection, ViewListener>>()
        var start: StartAnswer = StartAnswer.Starting
        var startError: Exception? = null
        var starts = 0
        val phones = ArrayDeque<Pair<String, String>>()
        val handBackEpochs = mutableListOf<Long>()
        val handBacks get() = handBackEpochs.size
        /** The error the n-th hand-back (0-based) throws, if any. */
        var handBackError: (Int) -> Exception? = { null }
        override suspend fun ticket(): ViewTicket { ticketCalls++; return answer() }
        override fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection = Connection(now).also { opened += it to listener }
        override suspend fun start(): StartAnswer { starts++; startError?.let { throw it }; return start }
        override suspend fun phone(): Pair<String, String> = phones.removeFirstOrNull() ?: ("on" to "")
        override suspend fun handBack(epoch: Long) {
            handBackEpochs += epoch
            handBackError(handBacks - 1)?.let { throw it }
        }
    }

    private class Sink : FrameSink {
        val frames = mutableListOf<FrameHeader>()
        var decode: Boolean? = true
        var pending: ((Boolean) -> Unit)? = null
        var cleared = 0
        override fun submit(header: FrameHeader, data: ByteArray, done: (Boolean) -> Unit) {
            frames += header
            val result = decode
            if (result == null) pending = done else done(result)
        }
        override fun clear() { cleared++ }
    }

    private class Rig(online: () -> Boolean = { true }) {
        val scope = TestScope(StandardTestDispatcher())
        /** Outlives [scope], as the app's hand-back scope outlives the view model. */
        val lasting = kotlinx.coroutines.CoroutineScope(StandardTestDispatcher(scope.testScheduler) + kotlinx.coroutines.SupervisorJob())
        val now = { scope.testScheduler.currentTime }
        val transport = Transport(now)
        val sink = Sink()
        val session = ViewSession(transport, sink, scope, clock = now, online = online, lasting = lasting)
        val ui get() = session.ui.value
        val connection get() = transport.opened.last().first
        val listener get() = transport.opened.last().second
        fun run() = scope.runCurrent()
        fun advance(ms: Long) { scope.advanceTimeBy(ms); scope.runCurrent() }
        fun accept(helper: String = "ready") { listener.onOpen(); listener.onText(state(helper)); run() }
        fun live(helper: String = "ready"): Rig { session.open(); run(); accept(helper); return this }
        fun disconnect() {
            transport.answer = { throw IOException("down") }
            listener.onFailure(null)
            run()
        }
        fun end(reason: String?, code: Int) { reason?.let { listener.onText(bye(it)) }; listener.onClosed(code); run() }
        fun messages(of: Connection = connection) = of.sent.map { JSONObject(it.second) }
        fun types(of: Connection = connection) = messages(of).map { it.getString("t") }
    }

    companion object {
        fun state(helper: String = "ready", keyboard: Boolean = false, prompt: String? = null): String =
            JSONObject().put("t", "state").put("keyboard", keyboard).put("rotation", 0).put("helper", helper).put("lease", "you").put("epoch", 17)
                .apply { if (prompt != null) put("prompt", prompt) }.toString()
        fun bye(reason: String) = """{"t":"bye","reason":"$reason"}"""
        fun frame(seq: Long): ByteArray = byteArrayOf(1, (seq ushr 24).toByte(), (seq ushr 16).toByte(), (seq ushr 8).toByte(), seq.toByte(), 2, -48, 6, 64, 0, -1, -40, -1)
        internal val down = listOf(TouchPoint(0, .5, .5, "down"))
    }

    @Test fun opensWithOneTicketAndGoesLiveOnTheFirstState() {
        val rig = Rig()
        rig.session.open()
        rig.run()
        assertEquals(ViewPhase.Connecting, rig.ui.phase)
        rig.accept()
        assertEquals(1, rig.transport.ticketCalls)
        assertEquals(ViewPhase.Live, rig.ui.phase)
        assertEquals("ready", rig.ui.helper)
    }

    @Test fun eachFrameIsAckedOnlyAfterItIsDecoded() {
        val rig = Rig().live()
        rig.sink.decode = null
        rig.listener.onBinary(frame(5))
        rig.run()
        assertEquals(emptyList<String>(), rig.types())
        assertFalse(rig.ui.hasFrame)
        rig.sink.pending!!(true)
        rig.run()
        assertEquals(listOf("ack"), rig.types())
        assertEquals(5L, rig.messages().single().getLong("seq"))
        assertTrue(rig.ui.hasFrame)
        assertEquals(FrameHeader(5, 720, 1600, 0), rig.sink.frames.single())
    }

    @Test fun aFrameThatCannotBeDecodedIsStillAckedAndAFreshOneIsAsked() {
        val rig = Rig().live()
        rig.sink.decode = false
        rig.listener.onBinary(frame(1))
        rig.listener.onBinary(frame(2))
        rig.run()
        // Both acked (or the next frame waits 5 s); one refresh, not one per frame.
        assertEquals(listOf("ack", "refresh", "ack"), rig.types())
    }

    @Test fun touchesKeysAndTextLeaveInTheOrderTheyWereMade() {
        val rig = Rig().live()
        rig.session.touch(down)
        assertTrue(rig.session.key("home"))
        assertTrue(rig.session.text("hi"))
        rig.session.touch(listOf(TouchPoint(0, .5, .6, "up")))
        rig.run()
        assertEquals(listOf("touch", "key", "text", "touch"), rig.types())
    }

    @Test fun nothingIsSentBeforeTheViewIsLive() {
        val rig = Rig()
        rig.session.open()
        rig.run()
        rig.session.touch(down)
        assertFalse(rig.session.key("back"))
        assertFalse(rig.session.text("hi"))
        rig.listener.onOpen()
        rig.run()
        assertEquals(emptyList<String>(), rig.types())
    }

    @Test fun otherAlphabetsAndPasteWaitForTheHelper() {
        val rig = Rig().live(helper = "starting")
        assertFalse(rig.session.text("café"))
        assertTrue(rig.session.text("cafe"))
        assertEquals(ViewSession.Paste.NotReady, rig.session.paste("hello"))
        rig.listener.onText(state("ready"))
        rig.run()
        assertTrue(rig.session.text("café"))
        assertEquals(ViewSession.Paste.Sent, rig.session.paste("hello"))
        assertEquals(ViewSession.Paste.Empty, rig.session.paste(""))
        assertEquals(ViewSession.Paste.Empty, rig.session.paste(null))
        assertEquals(ViewSession.Paste.TooLong, rig.session.paste("x".repeat(10_001)))
        rig.run()
        assertEquals(listOf("text", "text", "paste"), rig.types())
        assertEquals("hello", rig.messages().last().getString("text"))
    }

    @Test fun longTextGoesInPiecesTheServerTakes() {
        val rig = Rig().live()
        assertTrue(rig.session.text("a".repeat(1_200)))
        rig.advance(1_000)
        assertEquals(listOf(500, 500, 200), rig.messages().map { it.getString("text").length })
    }

    @Test fun doneHandsThePhoneBackAndCloses() {
        val rig = Rig().live()
        rig.session.done()
        rig.run()
        assertEquals("done", rig.types().last())
        rig.end("done", 1000)
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        assertEquals(0, rig.transport.handBacks)
    }

    @Test fun doneWithoutAnAnswerStillClosesAfterTwoSeconds() {
        val rig = Rig().live()
        rig.session.done()
        rig.advance(1_999)
        assertEquals(ViewPhase.Live, rig.ui.phase)
        rig.advance(1)
        assertEquals(ViewPhase.Closed, rig.ui.phase)
    }

    @Test fun doneWithTheSocketGoneUsesTheHandBackRoute() {
        val rig = Rig().live()
        rig.disconnect()
        assertEquals(ViewPhase.BackSoon, rig.ui.phase)
        rig.session.done()
        rig.run()
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        assertEquals(1, rig.transport.handBacks)
    }

    @Test fun openedOnAnotherPhoneOffersToOpenItHere() {
        val rig = Rig().live()
        rig.end("replaced", 1000)
        assertEquals(ViewPhase.Replaced, rig.ui.phase)
        rig.advance(60_000)
        assertEquals(1, rig.transport.ticketCalls)
        rig.session.retry()
        rig.run()
        rig.accept()
        assertEquals(2, rig.transport.ticketCalls)
        assertEquals(ViewPhase.Live, rig.ui.phase)
    }

    @Test fun aLeaseThatEndedAndAPhoneThatStoppedWaitForTheOwner() {
        val ended = Rig().live()
        ended.end("ended", 1000)
        assertEquals(ViewPhase.Ended, ended.ui.phase)
        val stopped = Rig().live()
        stopped.end("stopped", 1000)
        assertEquals(ViewPhase.Stopped, stopped.ui.phase)
        stopped.transport.phones += "starting" to ""
        stopped.session.turnOn()
        stopped.run()
        assertEquals(ViewPhase.Starting, stopped.ui.phase)
        assertEquals(1, stopped.transport.starts)
        stopped.advance(3_000)
        assertEquals(2, stopped.transport.ticketCalls)
        stopped.accept()
        assertEquals(ViewPhase.Live, stopped.ui.phase)
    }

    @Test fun aPhoneThatIsOffIsStartedThenOpened() {
        val rig = Rig().live()
        rig.end("off", 1000)
        assertEquals(ViewPhase.Starting, rig.ui.phase)
        rig.transport.phones += "starting" to ""
        rig.advance(1_500)
        assertEquals(1, rig.transport.ticketCalls)
        rig.advance(1_500)
        assertEquals(2, rig.transport.ticketCalls)
        rig.accept()
        assertEquals(ViewPhase.Live, rig.ui.phase)
    }

    @Test fun aPhoneTurnedOffWhileItWasShownIsNotTurnedBackOn() {
        // Live (JOIN fu2 plugin, 17 Nov): the phone page turned the phone off; its frames failed before Hermes said "stopped", the view
        // tried once more and found the phone off. It must wait for the owner, not start the phone again behind them.
        val rig = Rig().live()
        rig.listener.onBinary(frame(1))
        rig.run()
        assertTrue(rig.ui.hasFrame)
        rig.end("error", ViewProtocol.CLOSE_FAILED)
        assertEquals(2, rig.transport.ticketCalls)
        rig.listener.onOpen()
        rig.end("off", 1000)
        assertEquals(ViewPhase.Stopped, rig.ui.phase)
        rig.advance(60_000)
        assertEquals(0, rig.transport.starts)
        assertEquals(2, rig.transport.ticketCalls)
        rig.transport.phones += "starting" to ""
        rig.session.turnOn()
        rig.run()
        assertEquals(ViewPhase.Starting, rig.ui.phase)
        assertEquals(1, rig.transport.starts)
    }

    @Test fun aRefusedStartShowsTheServersOwnLine() {
        val rig = Rig().live()
        rig.transport.start = StartAnswer.Refused("Turn on Hermes sign-in first.")
        rig.end("off", 1000)
        assertEquals(ViewPhase.StartRefused, rig.ui.phase)
        assertEquals("Turn on Hermes sign-in first.", rig.ui.line)
        val failed = Rig().live()
        failed.transport.phones += "off" to "The phone didn't start."
        failed.end("off", 1000)
        failed.advance(1_500)
        assertEquals(ViewPhase.StartRefused, failed.ui.phase)
        assertEquals("The phone didn't start.", failed.ui.line)
    }

    @Test fun aSignInThatEndedOrARemovedPhoneIsSaidPlainlyNotAsHermesAway() {
        val signedOut = Rig().live()
        signedOut.transport.startError = TicketRefused(401, "signed out")
        signedOut.end("off", 1000)
        assertEquals(ViewPhase.SignedOut, signedOut.ui.phase)
        val removed = Rig().live()
        removed.transport.startError = TicketRefused(403, "Device removed")
        removed.end("off", 1000)
        assertEquals(ViewPhase.Removed, removed.ui.phase)
        val away = Rig().live()
        away.transport.startError = IOException("refused")
        away.end("off", 1000)
        assertEquals(ViewPhase.BackSoon, away.ui.phase)
    }

    @Test fun removedIsFinalAndForgetsThePicture() {
        val rig = Rig().live()
        rig.listener.onBinary(frame(1))
        rig.run()
        assertTrue(rig.ui.hasFrame)
        rig.end("removed", 1008)
        assertEquals(ViewPhase.Removed, rig.ui.phase)
        assertFalse(rig.ui.hasFrame)
        assertTrue(rig.sink.cleared > 0)
        rig.advance(600_000)
        assertEquals(1, rig.transport.ticketCalls)
    }

    @Test fun anUpdateSaysBackInAMomentAndTriesAgainLater() {
        val rig = Rig().live()
        rig.end("update", 1013)
        assertEquals(ViewPhase.BackSoon, rig.ui.phase)
        rig.advance(19_999)
        assertEquals(1, rig.transport.ticketCalls)
        rig.advance(1)
        assertEquals(2, rig.transport.ticketCalls)
        assertEquals(ViewPhase.BackSoon, rig.ui.phase)
        rig.accept()
        assertEquals(ViewPhase.Live, rig.ui.phase)
    }

    @Test fun aPictureErrorRetriesOnceThenStops() {
        val rig = Rig().live()
        rig.end("error", 1011)
        assertEquals(2, rig.transport.ticketCalls)
        rig.accept()
        assertEquals(ViewPhase.Live, rig.ui.phase)
        rig.end("error", 1011)
        assertEquals(ViewPhase.Failed, rig.ui.phase)
        assertEquals(2, rig.transport.ticketCalls)
        rig.session.retry()
        rig.run()
        assertEquals(3, rig.transport.ticketCalls)
    }

    @Test fun aPolicyCloseWithoutAByeGetsOneFreshTicketThenStops() {
        val rig = Rig().live()
        rig.end(null, 1008)
        assertEquals(2, rig.transport.ticketCalls)
        rig.accept()
        assertEquals(ViewPhase.Live, rig.ui.phase)
        rig.end(null, 1008)
        assertEquals(ViewPhase.Failed, rig.ui.phase)
        rig.advance(600_000)
        assertEquals(2, rig.transport.ticketCalls)
    }

    @Test fun leavingControlWithAnUnacknowledgedDoneHandsBackEvenAfterDisposal() {
        val rig = Rig().live()
        rig.session.done()
        rig.run()
        assertEquals("done", rig.types().last())
        rig.advance(2_001)
        rig.session.dispose()
        rig.run()
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        assertEquals(1, rig.transport.handBacks)
    }

    @Test fun aLostDoneWithoutAnAcquisitionCannotReleaseAnotherView() {
        val rig = Rig()
        rig.session.open()
        rig.run()
        rig.listener.onOpen()
        rig.listener.onText("""{"t":"state","lease":"you"}""")
        rig.run()
        rig.session.done()
        rig.end(null, 1000)
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        assertEquals(0, rig.transport.handBacks)
    }

    @Test fun theHandBackFinishesEvenWhenTheViewGoesAwayAtOnce() {
        val rig = Rig().live()
        rig.disconnect()
        rig.session.done()
        // Closed finishes the activity, which cancels the view model's scope straight away.
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        rig.scope.cancel()
        rig.scope.testScheduler.runCurrent()
        assertEquals(1, rig.transport.handBacks)
    }

    private fun closedWithoutASocket(error: (Int) -> Exception?): Rig {
        val rig = Rig().live()
        rig.disconnect()
        rig.transport.handBackError = error
        rig.session.done()
        assertEquals(ViewPhase.Closed, rig.ui.phase)
        rig.scope.cancel()
        rig.scope.testScheduler.runCurrent()
        return rig
    }

    @Test fun aHandBackThatCannotReachHermesIsTriedAgainUntilItGoesThrough() {
        val rig = closedWithoutASocket { n -> if (n < 2) IOException("offline") else null }
        rig.scope.testScheduler.advanceTimeBy(1_999); rig.scope.testScheduler.runCurrent()
        assertEquals(1, rig.transport.handBacks)
        rig.scope.testScheduler.advanceTimeBy(2_001); rig.scope.testScheduler.runCurrent()
        assertEquals(listOf(17L, 17L, 17L), rig.transport.handBackEpochs)
        rig.scope.testScheduler.advanceTimeBy(120_000); rig.scope.testScheduler.runCurrent()
        assertEquals(3, rig.transport.handBacks)
    }

    @Test fun anyAnswerFromHermesEndsTheHandBack() {
        val rig = closedWithoutASocket { TicketRefused(403, "Device removed") }
        rig.scope.testScheduler.advanceTimeBy(120_000); rig.scope.testScheduler.runCurrent()
        assertEquals(1, rig.transport.handBacks)
    }

    @Test fun aHandBackStopsTryingAfterAMinute() {
        val rig = closedWithoutASocket { IOException("offline") }
        rig.scope.testScheduler.advanceTimeBy(300_000); rig.scope.testScheduler.runCurrent()
        val tries = rig.transport.handBacks
        assertTrue("$tries tries", tries in 29..31)
        rig.scope.testScheduler.advanceTimeBy(300_000); rig.scope.testScheduler.runCurrent()
        assertEquals(tries, rig.transport.handBacks)
    }

    @Test fun aDroppedSocketReconnectsWithAFreshTicket() {
        val rig = Rig().live()
        rig.listener.onBinary(frame(1))
        rig.run()
        rig.listener.onFailure(null)
        rig.run()
        assertEquals(ViewPhase.Reconnecting, rig.ui.phase)
        assertEquals(2, rig.transport.ticketCalls)
        rig.accept()
        assertEquals(ViewPhase.Live, rig.ui.phase)
    }

    @Test fun hermesAwayIsBackInAMomentThenNotAnswering() {
        val rig = Rig().live()
        rig.transport.answer = { throw IOException("refused") }
        rig.listener.onFailure(null)
        rig.run()
        assertEquals(ViewPhase.BackSoon, rig.ui.phase)
        rig.advance(170_000)
        assertEquals(ViewPhase.BackSoon, rig.ui.phase)
        assertTrue(rig.transport.ticketCalls > 20)
        rig.advance(20_000)
        assertEquals(ViewPhase.HermesDown, rig.ui.phase)
        val calls = rig.transport.ticketCalls
        rig.advance(600_000)
        assertEquals(calls, rig.transport.ticketCalls)
    }

    @Test fun noConnectionOnThisPhoneSaysReconnectingAndKeepsTrying() {
        val rig = Rig(online = { false }).live()
        rig.listener.onBinary(frame(1))
        rig.run()
        rig.transport.answer = { throw IOException("offline") }
        rig.listener.onFailure(null)
        rig.run()
        assertEquals(ViewPhase.Reconnecting, rig.ui.phase)
        rig.advance(600_000)
        assertEquals(ViewPhase.Reconnecting, rig.ui.phase)
        assertTrue(rig.transport.ticketCalls > 100)
    }

    @Test fun aRefusedUpgradeGetsOneFreshTicketThenStops() {
        val rig = Rig().live()
        rig.listener.onFailure(403)
        rig.run()
        assertEquals(2, rig.transport.ticketCalls)
        rig.listener.onFailure(403)
        rig.run()
        assertEquals(ViewPhase.Failed, rig.ui.phase)
        assertEquals(2, rig.transport.ticketCalls)
    }

    @Test fun ticketRefusalsSayWhy() {
        fun refusedWith(status: Int, detail: String): ViewPhase {
            val rig = Rig()
            rig.transport.answer = { throw TicketRefused(status, detail) }
            rig.session.open()
            rig.run()
            return rig.ui.phase
        }
        assertEquals(ViewPhase.SignedOut, refusedWith(401, "Verified native session required"))
        assertEquals(ViewPhase.NotSetUp, refusedWith(404, "{name}'s phone isn't set up yet."))
        assertEquals(ViewPhase.NotYours, refusedWith(403, "{name}'s phone belongs to another profile."))
        assertEquals(ViewPhase.Removed, refusedWith(403, "Device removed"))
        assertEquals(ViewPhase.BackSoon, refusedWith(503, "Update in progress"))
        assertEquals(ViewPhase.BackSoon, refusedWith(502, "Bad gateway"))
    }

    @Test fun inTheBackgroundFramesStopAndADropWaitsForTheOwner() {
        val rig = Rig().live()
        rig.session.background(true)
        rig.run()
        assertEquals("background", rig.types().last())
        assertFalse(rig.session.key("home"))
        rig.listener.onFailure(null)
        rig.run()
        assertEquals(ViewPhase.Reconnecting, rig.ui.phase)
        rig.advance(60_000)
        assertEquals(1, rig.transport.ticketCalls)
        rig.session.background(false)
        rig.run()
        assertEquals(2, rig.transport.ticketCalls)
    }

    @Test fun comingBackToALiveViewSaysForeground() {
        val rig = Rig().live()
        rig.session.background(true)
        rig.session.background(true)
        rig.session.background(false)
        rig.run()
        assertEquals(listOf("background", "foreground"), rig.types())
    }

    @Test fun theDebuggingPromptBlocksEverythingButBack() {
        val rig = Rig().live()
        rig.advance(5_000)
        rig.listener.onText(state(prompt = "usb_debugging"))
        rig.run()
        assertTrue(rig.ui.prompt)
        assertEquals(5_000L, rig.ui.promptSince)
        rig.advance(1_000)
        rig.listener.onText(state(prompt = "usb_debugging", keyboard = true))
        rig.run()
        assertEquals(5_000L, rig.ui.promptSince)
        rig.session.touch(down)
        assertFalse(rig.session.key("home"))
        assertFalse(rig.session.key("enter"))
        assertFalse(rig.session.text("ok"))
        assertEquals(ViewSession.Paste.NotReady, rig.session.paste("ok"))
        assertTrue(rig.session.key("back"))
        rig.run()
        assertEquals(listOf("key"), rig.types())
        assertEquals("back", rig.messages().single().getString("key"))
        // A finger that was already down is still lifted, so nothing stays pressed on the phone.
        rig.session.touch(listOf(TouchPoint(0, .5, .5, "up")))
        rig.session.touch(listOf(TouchPoint(0, .5, .5, "up"), TouchPoint(1, .2, .2, "move")))
        rig.run()
        assertEquals(listOf("key", "touch"), rig.types())
        rig.listener.onText(state())
        rig.run()
        assertFalse(rig.ui.prompt)
        rig.session.touch(down)
        rig.run()
        assertEquals("touch", rig.types().last())
    }

    @Test fun aRefusedTextIsSaidAndTheRowStartsOver() {
        val rig = Rig().live()
        val before = rig.ui.textReset
        rig.listener.onText("""{"t":"refused","what":"text","reason":"helper"}""")
        rig.run()
        assertEquals(FlashKind.TextRefused, rig.ui.flash?.kind)
        assertEquals(before + 1, rig.ui.textReset)
        assertEquals("off", rig.ui.helper)
        rig.listener.onText("""{"t":"refused","what":"paste","reason":"clipboard"}""")
        rig.listener.onText("""{"t":"notice","kind":"saved","line":"Saved: Kanto Foods · k${'@'}example.com"}""")
        rig.run()
        assertEquals(Flash(FlashKind.Notice, "Saved: Kanto Foods · k${'@'}example.com", rig.ui.flash!!.id), rig.ui.flash)
    }

    @Test fun anUnknownServerMessageChangesNothing() {
        val rig = Rig().live()
        val before = rig.ui
        rig.listener.onText("""{"t":"cursor","x":1}""")
        rig.listener.onText("garbage")
        rig.run()
        assertEquals(before, rig.ui)
    }

    /** Replays what was sent through the server's own buckets (agent_phone_stream.RATES); any refusal would have closed the view. */
    private fun assertServerAdmits(rig: Rig) {
        val rates = mapOf("touch" to (240.0 to 240), "key" to (20.0 to 20), "text" to (20.0 to 40), "paste" to (2.0 to 4), "ack" to (60.0 to 60), "control" to (10.0 to 10))
        val lane = mapOf("touch" to "touch", "key" to "key", "text" to "text", "paste" to "paste", "ack" to "ack")
        var now = 0L
        val buckets = rates.mapValues { (_, rate) -> TokenBucket(rate.first, rate.second) { now } }
        for ((at, text) in rig.connection.sent) {
            now = at
            val t = JSONObject(text).getString("t")
            assertTrue("$t at $at ms over the server's limit", buckets.getValue(lane[t] ?: "control").take())
        }
    }

    @Test fun burstsStayUnderTheServersLimitsAndKeepTheirOrder() {
        val rig = Rig().live()
        repeat(45) { rig.session.key("backspace") }
        repeat(70) { rig.session.text("x$it") }
        repeat(9) { rig.session.paste("p$it") }
        repeat(400) { i -> rig.session.touch(listOf(TouchPoint(0, i / 400.0, .5, if (i == 0) "down" else "move"))) }
        repeat(30) { rig.session.background(it % 2 == 0) }
        rig.session.background(false)
        rig.advance(30_000)
        assertServerAdmits(rig)
        val types = rig.types()
        assertEquals(45, types.count { it == "key" })
        assertEquals(70, types.count { it == "text" })
        assertEquals(9, types.count { it == "paste" })
        assertEquals((0 until 70).map { "x$it" }, rig.messages().filter { it.getString("t") == "text" }.map { it.getString("text") })
        // Keys, then text, then pastes: the order they were made.
        assertTrue(types.indexOfLast { it == "key" } < types.indexOfFirst { it == "text" })
        assertTrue(types.indexOfLast { it == "text" } < types.indexOfFirst { it == "paste" })
    }

    @Test fun queuedMovesOfTheSameFingersAreMergedSoTheNewestPositionWins() {
        val rig = Rig().live()
        repeat(40) { rig.session.key("backspace") }
        rig.session.touch(down)
        repeat(50) { i -> rig.session.touch(listOf(TouchPoint(0, i / 100.0, .5, "move"))) }
        rig.advance(10_000)
        val touches = rig.messages().filter { it.getString("t") == "touch" }
        assertEquals(2, touches.size)
        assertEquals(.49, touches.last().getJSONArray("points").getJSONObject(0).getDouble("x"), 1e-9)
    }

    @Test fun slowTapsLowerThePictureAndFastOnesRaiseItAgain() {
        val rig = Rig().live()
        var seq = 0L
        fun tap(gap: Long) { rig.session.touch(down); rig.run(); rig.advance(gap); rig.listener.onBinary(frame(++seq)); rig.run(); rig.session.touch(listOf(TouchPoint(0, .5, .5, "up"))); rig.advance(3_000) }
        repeat(3) { tap(700) }
        assertFalse(rig.ui.lowered)
        tap(700)
        assertTrue(rig.ui.lowered)
        assertEquals(FlashKind.Lowered, rig.ui.flash?.kind)
        assertEquals("low", rig.messages().last { it.getString("t") == "quality" }.getString("level"))
        repeat(5) { tap(80) }
        assertTrue(rig.ui.lowered) // held at least 20 s
        repeat(5) { tap(80) }
        assertFalse(rig.ui.lowered)
        assertEquals("normal", rig.messages().last { it.getString("t") == "quality" }.getString("level"))
    }

    @Test fun aGsmLikeLinkLowersThePictureWithinTheFirstTaps() {
        // Root (msg_0de3f33211e3): every tap answered after 5-20 s is the strongest slow sample, not a discarded one.
        val rig = Rig().live()
        var seq = 0L
        var loweredAfter: Int? = null
        listOf(5_000L, 12_000L, 20_000L, 8_000L, 15_000L).forEachIndexed { i, wait ->
            rig.session.touch(down); rig.run(); rig.advance(wait); rig.listener.onBinary(frame(++seq)); rig.run()
            rig.session.touch(listOf(TouchPoint(0, .5, .5, "up"))); rig.advance(500)
            if (rig.ui.lowered && loweredAfter == null) loweredAfter = i + 1
        }
        assertEquals(2, loweredAfter)
        assertEquals(FlashKind.Lowered, rig.ui.flash?.kind)
        assertEquals("low", rig.messages().last { it.getString("t") == "quality" }.getString("level"))
        assertEquals(1, rig.messages().count { it.getString("t") == "quality" })
    }

    @Test fun oneLateAnswerAloneDoesNotLowerThePicture() {
        // A tap that changed nothing on screen is answered only by a later, unrelated picture: one such sample is not a slow link.
        val rig = Rig().live()
        var seq = 0L
        fun tap(gap: Long) { rig.session.touch(down); rig.run(); rig.advance(gap); rig.listener.onBinary(frame(++seq)); rig.run(); rig.session.touch(listOf(TouchPoint(0, .5, .5, "up"))); rig.advance(3_000) }
        tap(9_000)
        repeat(6) { tap(80) }
        tap(9_000)
        repeat(3) { tap(80) }
        assertFalse(rig.ui.lowered)
        assertEquals(0, rig.messages().count { it.getString("t") == "quality" })
    }

    @Test fun aLowPictureFromALateLinkRisesOnlyAfterFastTapsAndTwentySeconds() {
        val rig = Rig().live()
        var seq = 0L
        fun tap(gap: Long) { rig.session.touch(down); rig.run(); rig.advance(gap); rig.listener.onBinary(frame(++seq)); rig.run(); rig.session.touch(listOf(TouchPoint(0, .5, .5, "up"))); rig.advance(1_000) }
        repeat(2) { tap(6_000) }
        assertTrue(rig.ui.lowered)
        repeat(5) { tap(80) }
        assertTrue(rig.ui.lowered) // about 5 s low: held at least 20 s
        repeat(4) { tap(6_000) }
        repeat(2) { tap(80) }
        assertTrue(rig.ui.lowered) // over 20 s low now, but the last five are still mostly slow
        tap(80)
        assertFalse(rig.ui.lowered) // three fast of the last five: the median is fast
        assertEquals("normal", rig.messages().last { it.getString("t") == "quality" }.getString("level"))
    }

    @Test fun aLoweredPictureStaysLowOnANewSocket() {
        val rig = Rig().live()
        var seq = 0L
        repeat(4) { rig.session.touch(down); rig.advance(700); rig.listener.onBinary(frame(++seq)); rig.run(); rig.advance(3_000) }
        assertTrue(rig.ui.lowered)
        rig.listener.onFailure(null)
        rig.run()
        rig.accept()
        assertEquals(listOf("quality"), rig.types().filter { it == "quality" })
        assertEquals("low", rig.messages().first { it.getString("t") == "quality" }.getString("level"))
    }

    @Test fun closingAnErrorCardKeepsTheHoldAndDoneCannotReleaseAReplacement() {
        val failed = Rig().live()
        failed.end("error", 1011)
        failed.accept()
        failed.end("error", 1011)
        failed.session.close()
        failed.run()
        assertEquals(ViewPhase.Closed, failed.ui.phase)
        assertEquals(0, failed.transport.handBacks)
        val replaced = Rig().live()
        replaced.end("replaced", 1000)
        replaced.session.done()
        replaced.run()
        assertEquals(ViewPhase.Closed, replaced.ui.phase)
        assertEquals(0, replaced.transport.handBacks)
    }

    @Test fun disposeClosesTheSocketWithoutHandingThePhoneBack() {
        val rig = Rig().live()
        val connection = rig.connection
        rig.session.dispose()
        rig.run()
        assertEquals(1000, connection.closedWith)
        assertFalse(rig.types(connection).contains("done"))
        assertEquals(0, rig.transport.handBacks)
        assertNull(rig.ui.flash)
        assertNotNull(rig.ui)
    }
    // contract revision 5 (EMU-JOIN fu2): the password keyboard and the codes reach the view state
    @Test fun theSessionCarriesThePasswordKeyboardAndTheCodes() {
        val rig = Rig().live()
        rig.listener.onText("""{"t":"state","keyboard":"password","rotation":0,"helper":"ready","prompt":null,"lease":"you"}""")
        rig.run()
        assertTrue(rig.ui.keyboard && rig.ui.keyboardPassword)
        rig.listener.onText("""{"t":"state","keyboard":true,"rotation":0,"helper":"ready","prompt":null,"lease":"you"}""")
        rig.run()
        assertTrue(rig.ui.keyboard)
        assertFalse(rig.ui.keyboardPassword)
        rig.listener.onText("""{"t":"notice","kind":"saved","line":"Saved: Kanto Foods","reason":"vault.saved_label","args":{"label":"Kanto Foods"}}""")
        rig.run()
        assertEquals("vault.saved_label", rig.ui.flash?.reason)
        assertEquals(mapOf("label" to "Kanto Foods"), rig.ui.flash?.args)
    }

}
