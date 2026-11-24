package dev.hermes.android.push

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback TCP relay whose link can drop. A connection that is open when the link drops goes silent for good: its bytes vanish
 * and nothing is closed, as with a phone path that stalls (no reset ever arrives). A connection opened while the link is down waits
 * and goes through once it is back, as a retried TCP handshake does.
 */
internal class StallProxy(private val target: InetSocketAddress) : Closeable {
    private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val relays = CopyOnWriteArrayList<Relay>()
    val accepted = AtomicInteger()
    val port: Int get() = listener.localPort
    @Volatile var up = true
        set(value) { field = value; if (!value) relays.forEach { it.dead = true } }

    init { thread(isDaemon = true, name = "stall-proxy-accept") { acceptLoop() } }

    private fun acceptLoop() {
        while (!listener.isClosed) {
            val client = try { listener.accept() } catch (_: Exception) { return }
            accepted.incrementAndGet()
            val relay = Relay(client)
            relays += relay
            thread(isDaemon = true, name = "stall-proxy-relay") { relay.run() }
        }
    }

    private inner class Relay(val client: Socket) {
        @Volatile var dead = false
        @Volatile private var upstream: Socket? = null

        fun run() {
            while (!up && !client.isClosed && !listener.isClosed) Thread.sleep(10)
            if (client.isClosed || listener.isClosed) return
            val server = try { Socket(target.address, target.port) } catch (_: Exception) { close(); return }
            upstream = server
            thread(isDaemon = true, name = "stall-proxy-up") { pump(client.getInputStream(), server.getOutputStream(), fromClient = true) }
            pump(server.getInputStream(), client.getOutputStream(), fromClient = false)
        }

        private fun pump(input: InputStream, output: OutputStream, fromClient: Boolean) {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (dead) continue
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) { }
            // The phone closing its socket ends the relay; on a dead link nothing from the server side ever reaches the phone.
            if (fromClient || !dead) close()
        }

        fun close() { runCatching { client.close() }; runCatching { upstream?.close() } }
    }

    override fun close() { listener.close(); relays.forEach { it.close() } }
}

class KeepConnectedStallTest {
    private val origin = "https://native.example"
    private val profiles = listOf("default")

    /** Hermes' events route in miniature: a waiting poll is held [holdMillis] (25 s on Hermes), a no-wait poll answers at once. */
    private fun server(holdMillis: Long, protocol: Protocol = Protocol.H2_PRIOR_KNOWLEDGE, waits: MutableList<Int> = CopyOnWriteArrayList()) = MockWebServer().apply {
        protocols = if (protocol == Protocol.H2_PRIOR_KNOWLEDGE) listOf(Protocol.H2_PRIOR_KNOWLEDGE) else listOf(Protocol.HTTP_1_1)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val after = request.requestUrl?.queryParameter("after")?.toLongOrNull() ?: 0L
                val wait = request.requestUrl?.queryParameter("wait")?.toIntOrNull() ?: 0
                waits += wait
                if (wait > 0) Thread.sleep(holdMillis)
                return MockResponse().setBody("""{"events":[],"next_cursor":$after}""")
            }
        }
        start()
    }

    private fun proxyFor(server: MockWebServer) = StallProxy(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port))

    private fun client(proxy: StallProxy, protocol: Protocol = Protocol.H2_PRIOR_KNOWLEDGE): BeaconClient =
        BeaconClient(AuthContext(origin, emptyList(), owner = "owner", profile = "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.protocols(if (protocol == Protocol.H2_PRIOR_KNOWLEDGE) listOf(Protocol.H2_PRIOR_KNOWLEDGE) else listOf(Protocol.HTTP_1_1))
            builder.addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("127.0.0.1").port(proxy.port).build()).build())
            }
        }

    private fun cursors() = mutableMapOf<String, String>().let { values ->
        KeepConnectedCursors({ values[it] }, { key, value -> values[key] = value; true }).apply { reset(origin, "owner", "default", 0) }
    }

    @Test fun aTimedOutHttp2PollLeavesItsDeadConnectionPooledUntilItIsDropped() = runBlocking<Unit> {
        for (protocol in listOf(Protocol.H2_PRIOR_KNOWLEDGE, Protocol.HTTP_1_1)) {
            val server = server(holdMillis = 100, protocol)
            val proxy = proxyFor(server)
            val client = client(proxy, protocol)
            try {
                suspend fun page() = runCatching { keepConnectedEvents(client, "default", profiles, 0, 1, 700) }
                assertTrue("$protocol baseline", page().isSuccess)
                assertEquals(1, proxy.accepted.get())
                proxy.up = false
                val stalled = page().exceptionOrNull()
                assertTrue("$protocol: a stalled poll ends in a timeout, got $stalled", stalled is InterruptedIOException)
                proxy.up = true
                val served = server.requestCount
                val retry = page()
                if (protocol == Protocol.H2_PRIOR_KNOWLEDGE) {
                    // HTTP/2 cancels a timed-out call with RST_STREAM only; OkHttp still counts the silent connection healthy and reuses it.
                    assertTrue("HTTP/2 retry rides the dead connection", retry.exceptionOrNull() is InterruptedIOException)
                    assertEquals("no new connection", 1, proxy.accepted.get())
                    assertEquals("the retry never reached Hermes", served, server.requestCount)
                    keepConnectedDropConnections(client)
                    assertTrue("after dropping it a fresh connection answers", page().isSuccess)
                    assertEquals(2, proxy.accepted.get())
                } else {
                    // Negative control: HTTP/1.1 closes a cancelled call's socket, so its retry opens a fresh connection by itself.
                    assertTrue("HTTP/1.1 retry answers, got ${retry.exceptionOrNull()}", retry.isSuccess)
                    assertEquals(2, proxy.accepted.get())
                }
            } finally {
                client.closeNative()
                proxy.close()
                server.shutdown()
            }
        }
    }

    /**
     * The owner's case end to end over HTTP/2: the request is the service's own ([keepConnectedEvents]) and each poller is wired as
     * the service wires it; a network change makes a new client and poller, as `restartPoll` does. Timings are scaled: Hermes holds a
     * waiting poll 2 s (25 s), a long poll gives up after 3 s (35 s), a no-wait try after 1 s (15 s). Backoff waits are skipped on the
     * pollers' clock instead of slept, so a minute of backoff costs no test time; network waits are real.
     */
    private inner class Rig(private val dropConnections: Boolean) : Closeable {
        val server = server(holdMillis = 2_000)
        val proxy = proxyFor(server)
        private val cursors = cursors()
        private val clients = mutableListOf<BeaconClient>()
        private val clientOf = mutableMapOf<KeepConnectedPoller, BeaconClient>()
        private var skipped = 0L
        val reasons = mutableListOf<String>()
        val delays = mutableListOf<Long>()

        private fun now() = System.nanoTime() / 1_000_000 + skipped

        fun poller(): KeepConnectedPoller {
            val client = client(proxy).also { clients += it }
            return KeepConnectedPoller(
                origin = origin,
                owner = "owner",
                cursors = cursors,
                profiles = { profiles },
                signedInProfile = { "default" },
                nowMillis = ::now,
                poll = { profile, after -> keepConnectedEvents(client, profile, profiles, after, KEEP_CONNECTED_LONG_POLL_SECONDS, 3_000) },
                onEvent = { _, _ -> },
                probe = { profile, after -> keepConnectedEvents(client, profile, profiles, after, 0, 1_000) },
                onLinkFailure = { if (dropConnections) keepConnectedDropConnections(client) },
            ).also { clientOf[it] = client }
        }

        /** What the service's loop does when a network appears under the VPN or the owner unlocks while reconnecting. */
        fun nudge(poller: KeepConnectedPoller) {
            keepConnectedDropConnections(clientOf.getValue(poller))
            poller.retryNow()
        }

        /** One turn of the service loop; a backoff is recorded and, unless [wait] is false, skipped on the clock. */
        suspend fun cycle(poller: KeepConnectedPoller, wait: Boolean = true): KeepConnectedCycle = poller.pollOnce().also {
            if (it is KeepConnectedCycle.Reconnecting) { reasons += poller.lastFailureReason; delays += it.delayMillis; if (wait) skipped += it.delayMillis }
        }

        override fun close() { clients.forEach { it.closeNative() }; proxy.close(); server.shutdown() }
    }

    private suspend fun <T> timed(block: suspend () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        return block() to (System.nanoTime() - start) / 1_000_000
    }

    @Test fun keepConnectedRecoversWithinSecondsOfTheLinkReturning() = runBlocking<Unit> {
        Rig(dropConnections = true).use { rig ->
            val poller = rig.poller()
            val (started, startMs) = timed { rig.cycle(poller) }
            assertEquals(KeepConnectedCycle.Connected, started)
            assertTrue("a start answers without Hermes' hold, took $startMs ms", startMs < 1_500)

            rig.proxy.up = false
            repeat(2) { assertTrue(rig.cycle(poller) is KeepConnectedCycle.Reconnecting) }
            var back = 0L
            // The link returns while the next try waits on a connection opened during the outage.
            val recovered = coroutineScope {
                launch { delay(400); back = System.nanoTime(); rig.proxy.up = true }
                rig.cycle(poller)
            }
            val recoveredAfter = (System.nanoTime() - back) / 1_000_000
            println("KEEP_E_REPRO drop=true recoveredAfterMs=$recoveredAfter connections=${rig.proxy.accepted.get()} reasons=${rig.reasons.distinct()}")
            assertEquals(KeepConnectedCycle.Connected, recovered)
            assertTrue("recovered only after $recoveredAfter ms", recoveredAfter < 1_500)
            assertTrue("each try after the stall used a fresh connection", rig.proxy.accepted.get() > 2)
            // The phone could open connections; they went silent. The owner is told the connection was lost, not that Hermes failed.
            assertEquals(listOf("Lost the connection to Hermes"), rig.reasons.distinct())
            assertEquals("Reconnecting…" to "Lost the connection to Hermes. Retrying…",
                keepConnectedNotificationText(KeepConnectedService.STATUS_RECONNECTING, "Beacon", rig.reasons.first(), online = true))
        }
    }

    @Test fun afterALongOutageTheNextTryOrANetworkChangeConnectsAtOnce() = runBlocking<Unit> {
        Rig(dropConnections = true).use { rig ->
            val poller = rig.poller()
            assertEquals(KeepConnectedCycle.Connected, rig.cycle(poller))
            rig.proxy.up = false
            repeat(7) { assertTrue(rig.cycle(poller) is KeepConnectedCycle.Reconnecting) }
            assertTrue("a long outage waits a minute between tries: ${rig.delays}", rig.delays.last() in 58_000L..60_000L)

            rig.proxy.up = true
            // Android reports a network change: the service restarts with a new client and poller, whose first try does not wait.
            val (restarted, restartMs) = timed { rig.cycle(rig.poller()) }
            // Or no callback comes (a VPN hides the change): the old poller's next try, a minute later, connects as it runs.
            val (nextTry, nextTryMs) = timed { rig.cycle(poller) }
            println("KEEP_E_LONG_OUTAGE delays=${rig.delays} restartMs=$restartMs nextTryMs=$nextTryMs connections=${rig.proxy.accepted.get()}")
            assertEquals(KeepConnectedCycle.Connected, restarted)
            assertTrue("a restart connected only after $restartMs ms", restartMs < 1_500)
            assertEquals(KeepConnectedCycle.Connected, nextTry)
            assertTrue("the next try connected only after $nextTryMs ms", nextTryMs < 1_500)
        }
    }

    @Test fun withoutDroppingTheSilentConnectionTheSamePollerNeverRecovers() = runBlocking<Unit> {
        // Negative control: the identical scenario and poller, minus the drop. Every retry goes into the dead connection.
        Rig(dropConnections = false).use { rig ->
            val poller = rig.poller()
            assertEquals(KeepConnectedCycle.Connected, rig.cycle(poller))
            rig.proxy.up = false
            repeat(2) { assertTrue(rig.cycle(poller) is KeepConnectedCycle.Reconnecting) }
            rig.proxy.up = true
            val served = rig.server.requestCount
            repeat(3) { assertTrue("never connected again", rig.cycle(poller) is KeepConnectedCycle.Reconnecting) }
            println("KEEP_E_REPRO drop=false recoveredAfterMs=-1 connections=${rig.proxy.accepted.get()} reasons=${rig.reasons.distinct()}")
            assertEquals("no fresh connection was ever opened", 1, rig.proxy.accepted.get())
            assertEquals("no try reached Hermes after the link came back", served, rig.server.requestCount)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aStartAndALostLinkBothConnectOnTheFirstAnswer() = runTest {
        var now = 0L
        var fail = false
        val calls = mutableListOf<String>()
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner", cursors = cursors(), profiles = { profiles }, signedInProfile = { "default" },
            nowMillis = { now },
            poll = { _, after ->
                calls += "poll"
                if (fail) { fail = false; throw SocketTimeoutException("timeout") }
                delay(25_000)
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> },
            probe = { _, after -> calls += "probe"; KeepConnectedPage(emptyList(), after) },
        )
        var before = testScheduler.currentTime
        assertEquals("a start, or a restart after a network change, asks without waiting", KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(0L, testScheduler.currentTime - before)
        fail = true
        assertTrue(poller.pollOnce() is KeepConnectedCycle.Reconnecting)
        assertEquals("Lost the connection to Hermes", poller.lastFailureReason)
        now = 60_000
        before = testScheduler.currentTime
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals("the answer did not wait out Hermes' 25 s hold", 0L, testScheduler.currentTime - before)
        assertEquals("", poller.lastFailureReason)
        assertEquals(listOf("probe", "poll", "probe"), calls)
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals("then it long-polls again", "poll", calls.last())
    }

    @Test fun aTryThatTimedOutGoesAgainAtOnceAndQuickLinkFailuresBackOffToAMinute() = runBlocking<Unit> {
        var now = 0L
        var slow = true
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner", cursors = cursors(), profiles = { profiles }, signedInProfile = { "default" },
            nowMillis = { now },
            poll = { _, _ -> if (slow) now += 35_000; throw SocketTimeoutException("timeout") },
            onEvent = { _, _ -> },
        )
        assertEquals("a 35 s timeout is not followed by another wait", KeepConnectedCycle.Reconnecting(1L), poller.pollOnce())
        slow = false
        val delays = (2..8).map { (poller.pollOnce() as KeepConnectedCycle.Reconnecting).delayMillis.also { now += it } }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), delays)
        assertEquals(60_000L, keepConnectedLinkRetryAt(startedAtMillis = 0, completedAtMillis = 36_000, failures = 7))
        assertEquals(75_000L, keepConnectedLinkRetryAt(startedAtMillis = 0, completedAtMillis = 75_000, failures = 7))
        assertEquals(60_100L, keepConnectedLinkRetryAt(startedAtMillis = 100, completedAtMillis = 200, failures = 7))
        assertFalse(keepConnectedLinkFailure(ApiFailure(503, "", true)))
        assertFalse(keepConnectedLinkFailure(KeepConnectedPageException()))
        assertTrue(keepConnectedLinkFailure(SocketTimeoutException("timeout")))
        assertTrue(keepConnectedLinkFailure(ConnectException("refused")))
    }

    @Test fun aLongOutageSettlesAtOneTryAMinute() = runBlocking<Unit> {
        // Hermes' computer asleep all night: every try waits out the 15 s connect timeout and fails.
        var now = 0L
        val starts = mutableListOf<Long>()
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner", cursors = cursors(), profiles = { profiles }, signedInProfile = { "default" },
            nowMillis = { now },
            poll = { _, _ -> starts += now; now += 15_000; throw SocketTimeoutException("connect timed out") },
            onEvent = { _, _ -> },
        )
        while (now < 7_200_000L) {
            val cycle = poller.pollOnce()
            if (cycle is KeepConnectedCycle.Reconnecting) now += cycle.delayMillis
        }
        val firstHour = starts.count { it < 3_600_000L }
        val secondHour = starts.count { it in 3_600_000L until 7_200_000L }
        println("KEEP_E_OUTAGE triesFirstHour=$firstHour triesSecondHour=$secondHour")
        assertTrue("first hour: $firstHour tries", firstHour in 60..66)
        assertEquals("then one try a minute", 60, secondHour)
        assertTrue("never back to back", starts.zipWithNext { a, b -> b - a }.drop(6).all { it >= 59_000L })
        assertEquals("Can't reach Hermes. Retrying…", keepConnectedReasonText(poller.lastFailureReason))
    }

    @Test fun onlyALostLinkDropsTheConnections() = runBlocking<Unit> {
        var now = 0L
        var drops = 0
        var failure: Exception = SocketTimeoutException("timeout")
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner", cursors = cursors(), profiles = { profiles }, signedInProfile = { "default" },
            nowMillis = { now },
            poll = { _, _ -> throw failure },
            onEvent = { _, _ -> },
            onLinkFailure = { drops++ },
        )
        poller.pollOnce()
        assertEquals(1, drops)
        failure = ApiFailure(503, "", true)
        now += 120_000
        poller.pollOnce()
        assertEquals("an answer from Hermes, even an error, proves the connection works", 1, drops)
    }

    @Test fun aSignWhileReconnectingBringsOneFreshTryAtOnce() = runBlocking<Unit> {
        Rig(dropConnections = true).use { rig ->
            val poller = rig.poller()
            assertEquals(KeepConnectedCycle.Connected, rig.cycle(poller))
            rig.proxy.up = false
            repeat(6) { assertTrue(rig.cycle(poller) is KeepConnectedCycle.Reconnecting) }
            // The seventh failure leaves the minute's wait in place: the clock is not moved past it.
            assertTrue(rig.cycle(poller, wait = false) is KeepConnectedCycle.Reconnecting)
            assertTrue(rig.delays.last() in 58_000L..60_000L)

            rig.proxy.up = true
            // Connections opened during the outage now deliver the requests they held (their tries had already given up); let them land.
            delay(500)
            val served = rig.server.requestCount
            assertTrue("without a sign nothing is tried before the minute is up", rig.cycle(poller, wait = false) is KeepConnectedCycle.Reconnecting)
            assertEquals(served, rig.server.requestCount)

            val connections = rig.proxy.accepted.get()
            rig.nudge(poller)
            val (nudged, nudgedMs) = timed { rig.cycle(poller) }
            println("KEEP_E_NUDGE nudgedMs=$nudgedMs newConnections=${rig.proxy.accepted.get() - connections} tries=${rig.server.requestCount - served}")
            assertEquals(KeepConnectedCycle.Connected, nudged)
            assertTrue("the nudged try connected only after $nudgedMs ms", nudgedMs < 1_500)
            assertEquals("one try", 1, rig.server.requestCount - served)
            assertEquals("on a fresh connection", connections + 1, rig.proxy.accepted.get())
        }
    }

    @Test fun aNudgedTryKeepsTheBackoffSoItCannotStorm() = runBlocking<Unit> {
        var now = 0L
        var tries = 0
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner", cursors = cursors(), profiles = { profiles }, signedInProfile = { "default" },
            nowMillis = { now },
            poll = { _, _ -> tries++; now += 15_000; throw SocketTimeoutException("connect timed out") },
            onEvent = { _, _ -> },
        )
        repeat(7) { (poller.pollOnce() as? KeepConnectedCycle.Reconnecting)?.let { now += it.delayMillis } }
        // The eighth try fails and its minute's wait has not run out 10 s later.
        assertTrue(poller.pollOnce() is KeepConnectedCycle.Reconnecting)
        val before = tries
        now += 10_000
        assertTrue("not due yet", poller.pollOnce() is KeepConnectedCycle.Reconnecting)
        assertEquals(before, tries)
        poller.retryNow()
        val after = poller.pollOnce() as KeepConnectedCycle.Reconnecting
        assertEquals("exactly one try", before + 1, tries)
        assertTrue("the wait after it is still the minute, not a fresh 1 s backoff: ${after.delayMillis}", after.delayMillis >= 44_000L)
        poller.retryNow()
        assertTrue(poller.pollOnce() is KeepConnectedCycle.Reconnecting)
        assertEquals("a second nudge is one more try, no burst", before + 2, tries)
    }

    @Test fun aNudgeFiresOnlyWhileReconnectingAndAtMostOnceEveryThirtySeconds() {
        var now = 0L
        val nudge = KeepConnectedNudge({ now })
        for (status in listOf(KeepConnectedService.STATUS_OFF, KeepConnectedService.STATUS_STARTING,
                KeepConnectedService.STATUS_CONNECTED, KeepConnectedService.STATUS_SIGNED_OUT)) {
            assertFalse(status, nudge.allow(status))
        }
        assertTrue(nudge.allow(KeepConnectedService.STATUS_RECONNECTING))
        now = 29_999
        assertFalse("a network flapping within 30 s adds nothing", nudge.allow(KeepConnectedService.STATUS_RECONNECTING))
        assertFalse(nudge.allow(KeepConnectedService.STATUS_CONNECTED))
        now = 30_000
        assertTrue(nudge.allow(KeepConnectedService.STATUS_RECONNECTING))
        // A network that comes and goes every second for an hour while reconnecting.
        var allowed = 0
        for (second in 1..3_600) { now = 30_000L + second * 1_000L; if (nudge.allow(KeepConnectedService.STATUS_RECONNECTING)) allowed++ }
        println("KEEP_E_NUDGE_FLAP allowedPerHour=$allowed")
        assertEquals("at most one early try per 30 s", 120, allowed)
    }

    /**
     * K2 (22 Nov 2026): the owner's phone stopped polling and sat on a reason-less "Retrying…". Every timeout inside a try runs on a clock
     * that stops while Android pauses Beacon, so the CPU lease's tick, timed on the clock that keeps counting, restarts a loop that stopped.
     */
    @Test fun aLoopThatStoppedTryingIsRestartedAndTheNoticeSaysWhy() {
        val tick = KEEP_CONNECTED_CPU_RENEW_MILLIS
        assertEquals("a loop that tries is left alone", null, keepConnectedRestartReason(true, KEEP_CONNECTED_POLL_TIMEOUT_MILLIS, tick))
        assertEquals(null, keepConnectedRestartReason(true, KEEP_CONNECTED_STALL_MILLIS, tick))
        assertEquals("no try for 90 s", "Lost the connection to Hermes", keepConnectedRestartReason(true, KEEP_CONNECTED_STALL_MILLIS + 1, tick))
        assertEquals("a loop that ended", "Lost the connection to Hermes", keepConnectedRestartReason(false, 0, tick))
        // The tick itself came minutes late: Android froze Beacon or let the phone sleep, and the notice says so.
        assertEquals(KEEP_CONNECTED_PAUSED, keepConnectedRestartReason(true, 600_000, 600_000))
        assertEquals("a pause the loop already came back from", null, keepConnectedRestartReason(true, 1_000, 600_000))
        assertEquals("Reconnecting…" to "Android paused Beacon. Retrying…",
            keepConnectedNotificationText(KeepConnectedService.STATUS_RECONNECTING, "Beacon", KEEP_CONNECTED_PAUSED, online = true))
        // A healthy loop starts a try or ends a wait well inside the bound: a long poll, a profile read and a probe, or the longest backoff.
        assertTrue(KEEP_CONNECTED_POLL_TIMEOUT_MILLIS + 2 * KEEP_CONNECTED_PROBE_TIMEOUT_MILLIS < KEEP_CONNECTED_STALL_MILLIS)
        assertTrue(keepConnectedBackoffMs(Int.MAX_VALUE) + KEEP_CONNECTED_PROBE_TIMEOUT_MILLIS < KEEP_CONNECTED_STALL_MILLIS)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun aConnectedCycleClearsTheLostLinkSoTheProfileListIsReadAgain() = runTest {
        var now = 0L
        var drops = 0
        var primaryFails = false
        val linkDownSeen = mutableListOf<Boolean>()
        val poller = KeepConnectedPoller(
            origin = origin, owner = "owner",
            cursors = cursors().apply { reset(origin, "owner", "work", 0) },
            profiles = { linkDown -> linkDownSeen += linkDown; listOf("default", "work") },
            signedInProfile = { "default" },
            nowMillis = { now },
            poll = { profile, after ->
                if (profile == "work" || primaryFails) throw SocketTimeoutException("timeout")
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> },
            onLinkFailure = { drops++ },
        )
        // The signed-in profile answers while another one's poll times out: Connected, and the silent connection is still dropped.
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(1, drops)
        now += 120_000
        poller.pollOnce()
        assertEquals("a Connected cycle leaves the profile list free to be read", listOf(false, false), linkDownSeen)
        // The signed-in profile loses the link too: now the list is not read while the link is down.
        primaryFails = true
        now += 120_000
        assertTrue(poller.pollOnce() is KeepConnectedCycle.Reconnecting)
        now += 120_000
        poller.pollOnce()
        assertEquals(listOf(false, false, false, true), linkDownSeen)
    }
}
