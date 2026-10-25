package dev.hermes.android.push

import dev.hermes.android.core.Scope
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.voice.IncomingCall
import dev.hermes.android.voice.RelayCallbackPhase
import dev.hermes.android.voice.RelayCallbackPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class KeepConnectedTest {
    private fun client(server: MockWebServer, readTimeoutMillis: Long = 60_000): BeaconClient =
        BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "default", backend = "hermes-dashboard"), null, persistContext = false) { builder ->
            builder.readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
            builder.addInterceptor { chain ->
                val request = chain.request()
                val local = server.url(request.url.encodedPath).newBuilder().encodedQuery(request.url.encodedQuery).build()
                chain.proceed(request.newBuilder().url(local).build())
            }
        }

    private suspend fun eventPage(client: BeaconClient, profile: String, after: Long, profiles: List<String>) =
        parseKeepConnectedPage(
            client.nativeGetForProfile(
                "/api/plugins/beacon/mobile/notifications/events", profile, profiles,
                query = mapOf("after" to after.toString(), "wait" to KEEP_CONNECTED_LONG_POLL_SECONDS.toString()),
            ),
            after,
        )

    private fun callEvent(
        owner: String = "owner",
        profile: String = "default",
        expiresAt: Long = 1_120,
    ) = JSONObject()
        .put("event_id", "c0ffee00112233445566778899aabbcc")
        .put("callback_id", "c0ffee00112233445566778899aabbcc")
        .put("source_ref", "source-opaque-1")
        .put("conversation_ref", "conversation-opaque-1")
        .put("kind", "incoming_call")
        .put("owner_id", owner)
        .put("profile", profile)
        .put("session_id", "session-1")
        .put("conversation_id", "session-1")
        .put("state", "pending")
        .put("expires_at", expiresAt)

    @Test fun pollLoopRestartsWhenItsAuthorizedOriginOrOwnerChanges() {
        assertFalse(keepConnectedLoopNeedsRestart(true, "https://one.example", "owner", "https://one.example", "owner"))
        assertTrue(keepConnectedLoopNeedsRestart(true, "https://one.example", "owner", "https://two.example", "owner"))
        assertTrue(keepConnectedLoopNeedsRestart(true, "https://one.example", "owner", "https://one.example", "other"))
        assertTrue(keepConnectedLoopNeedsRestart(false, null, null, "https://one.example", "owner"))
    }

    @Test fun switchSavedWithoutAProviderGainsTheVerifiedOneOnlyForTheSameOriginAndOwner() {
        val verified = Scope("https://one.example", "owner", "default", nativeProvider = "basic")
        assertEquals("basic", keepConnectedProviderToStamp(null, "https://one.example", "owner", verified))
        assertEquals("basic", keepConnectedProviderToStamp("", "https://one.example", "owner", verified))
        assertNull(keepConnectedProviderToStamp("basic", "https://one.example", "owner", verified))
        assertNull(keepConnectedProviderToStamp("oidc", "https://one.example", "owner", verified))
        assertNull(keepConnectedProviderToStamp(null, "https://two.example", "owner", verified))
        assertNull(keepConnectedProviderToStamp(null, "https://one.example", "other", verified))
        assertNull(keepConnectedProviderToStamp(null, "https://one.example", "owner", verified.copy(nativeProvider = "")))
    }

    @Test fun onlyAStillSignedInOwnerOfAnUpdatedSwitchIsToldToOpenBeacon() {
        val verified = Scope("https://one.example", "owner", "default", nativeProvider = "basic")
        assertTrue(keepConnectedAwaitsVerification(null, "https://one.example", "owner", null, "owner"))
        assertTrue(keepConnectedAwaitsVerification("", "https://one.example", "owner", null, "owner"))
        assertFalse(keepConnectedAwaitsVerification(null, "https://one.example", "owner", null, ""))
        assertFalse(keepConnectedAwaitsVerification(null, "https://one.example", "owner", null, "other"))
        assertFalse(keepConnectedAwaitsVerification("basic", "https://one.example", "owner", null, "owner"))
        assertFalse(keepConnectedAwaitsVerification(null, "https://one.example", "owner", verified, "owner"))
        assertFalse(keepConnectedAwaitsVerification(null, "", "owner", null, "owner"))
    }

    private fun cursors(values: MutableMap<String, String> = mutableMapOf()) = KeepConnectedCursors(
        read = values::get,
        write = { key, value -> values[key] = value; true },
    )

    @Test fun cursorPersistsPerOriginOwnerAndProfile() {
        val values = mutableMapOf<String, String>()
        val store = cursors(values)

        assertEquals(0L, store.read("https://one.example", "owner", "default"))
        assertNull(store.readIfPresent("https://one.example", "owner", "default"))
        store.advance("https://one.example", "owner", "default", 12)

        assertEquals(12L, cursors(values).read("https://one.example", "owner", "default"))
        assertEquals(0L, store.read("https://two.example", "owner", "default"))
        assertEquals(0L, store.read("https://one.example", "owner", "coder"))
        assertEquals(0L, store.read("https://one.example", "another-owner", "default"))
    }

    @Test fun firstConnectionDrainsOldPagesWithoutNotifyingAndThenDeliversNewEvents() = runTest {
        val values = mutableMapOf<String, String>()
        val store = cursors(values)
        val old = JSONObject().put("event_id", "old-reply")
        val fresh = JSONObject().put("event_id", "new-reply")
        val seedRequests = mutableListOf<Long>()
        val pollRequests = mutableListOf<Long>()
        val delivered = mutableListOf<String>()
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default") },
            poll = { _, after ->
                pollRequests += after
                KeepConnectedPage(listOf(fresh), after + 1)
            },
            onEvent = { _, event -> delivered += event.getString("event_id") },
            initialize = { _, after ->
                seedRequests += after
                if (after == 0L) KeepConnectedPage(listOf(old), 1) else KeepConnectedPage(emptyList(), 5)
            },
        )

        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(listOf(0L, 1L), seedRequests)
        assertTrue(delivered.isEmpty())
        assertEquals(5L, store.read("https://hermes.example", "owner", "default"))

        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(listOf(5L), pollRequests)
        assertEquals(listOf("new-reply"), delivered)
    }

    @Test fun taskResultPayloadPreservesFieldsUsedByTheNativePushPath() {
        val event = JSONObject()
            .put("source_ref", "source-1")
            .put("event_id", "event-1")
            .put("conversation_ref", "conversation-1")
            .put("kind", "task_result")
            .put("activity_ref", "activity-1")
            .put("expires_at", "1234")
            .put("release_version", "9")

        assertEquals(
            mapOf(
                "source_ref" to "source-1",
                "event_id" to "event-1",
                "conversation_ref" to "conversation-1",
                "kind" to "task_result",
                "activity_ref" to "activity-1",
                "expires_at" to "1234",
                "release_version" to "9",
            ),
            keepConnectedPushData(event),
        )
    }

    @Test fun outboxResetReplaysFromTheNewLowerCursor() = runTest {
        val store = cursors().apply { advance("https://hermes.example", "owner", "default", 57) }
        val event = JSONObject().put("event_id", "task-result-1")
        val reset = parseKeepConnectedPage(
            JSONObject().put("events", org.json.JSONArray().put(event)).put("next_cursor", 1).put("reset", true),
            57,
        )
        val requests = mutableListOf<Long>()
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default") },
            poll = { _, after ->
                requests += after
                if (after == 57L) reset else KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> },
        )

        poller.pollOnce()
        poller.pollOnce()

        assertEquals(listOf(57L, 1L), requests)
        assertEquals(1L, store.read("https://hermes.example", "owner", "default"))
    }

    @Test fun backoffDoublesAndStopsAtOneMinute() {
        assertEquals(1_000L, keepConnectedBackoffMs(1))
        assertEquals(2_000L, keepConnectedBackoffMs(2))
        assertEquals(32_000L, keepConnectedBackoffMs(6))
        assertEquals(60_000L, keepConnectedBackoffMs(7))
        assertEquals(60_000L, keepConnectedBackoffMs(100))
    }

    @Test fun successfulPollPacingEnforcesAMinimumCycleWithoutStackingAfterLongPoll() {
        assertEquals(500L, keepConnectedPollDelay(0))
        assertEquals(300L, keepConnectedPollDelay(200))
        assertEquals(0L, keepConnectedPollDelay(500))
        assertEquals(0L, keepConnectedPollDelay(1_500))
        assertEquals(500L, keepConnectedPollDelay(-1))
    }

    @Test fun eachAuthorizedProfileIsPolledAndGetsItsOwnCursor() = runTest {
        val store = cursors().apply {
            advance("https://hermes.example", "owner", "default", 4)
            advance("https://hermes.example", "owner", "coder", 9)
        }
        val requests = mutableMapOf<String, Long>()
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default", "coder") },
            poll = { profile, after ->
                requests[profile] = after
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(mapOf("default" to 4L, "coder" to 9L), requests)
    }

    @Test fun profileLongPollsAreBoundedAndFiveNormalProfilesStayInOneCycle() = runTest {
        suspend fun pollProfiles(profiles: List<String>): Pair<Set<String>, Int> {
            var active = 0
            var peak = 0
            val seen = linkedSetOf<String>()
            val poller = KeepConnectedPoller(
                origin = "https://hermes.example",
                owner = "owner",
                cursors = cursors(),
                profiles = { profiles },
                poll = { profile, after ->
                    active += 1
                    peak = maxOf(peak, active)
                    seen += profile
                    delay(1)
                    active -= 1
                    KeepConnectedPage(emptyList(), after)
                },
                onEvent = { _, _ -> error("No events expected") },
            )

            assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
            return seen to peak
        }

        val normal = (1..5).map { "profile-$it" }
        val (normalSeen, normalPeak) = pollProfiles(normal)
        assertEquals(normal.toSet(), normalSeen)
        assertEquals(5, normalPeak)

        val large = (1..20).map { "profile-$it" }
        val (largeSeen, largePeak) = pollProfiles(large)
        assertEquals(large.toSet(), largeSeen)
        assertEquals(8, KEEP_CONNECTED_MAX_PROFILE_POLLS)
        assertEquals(KEEP_CONNECTED_MAX_PROFILE_POLLS, largePeak)
    }

    @Test fun fastProfileRepollsBeforeASlowerProfileReturns() = runTest {
        val slowStarted = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fastEventDelivered = CompletableDeferred<Unit>()
        var fastPolls = 0
        var slowPolls = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            profiles = { listOf("fast", "slow") },
            nowMillis = { 0L },
            cursors = cursors().apply {
                reset("https://hermes.example", "owner", "fast", 0)
                reset("https://hermes.example", "owner", "slow", 0)
            },
            poll = { profile, after ->
                if (profile == "fast") {
                    fastPolls += 1
                    when (fastPolls) {
                        1 -> KeepConnectedPage(listOf(JSONObject().put("event_id", "wake")), after + 1)
                        2 -> KeepConnectedPage(listOf(JSONObject().put("event_id", "fast-call")), after + 1)
                        else -> KeepConnectedPage(emptyList(), after)
                    }
                } else {
                    slowPolls += 1
                    slowStarted.complete(Unit)
                    releaseSlow.await()
                    KeepConnectedPage(emptyList(), after)
                }
            },
            onEvent = { profile, event ->
                if (profile == "fast" && event.getString("event_id") == "fast-call") fastEventDelivered.complete(Unit)
            },
        )
        val cycle = async { poller.pollOnce() }

        withTimeout(2_000) {
            slowStarted.await()
            fastEventDelivered.await()
            while (fastPolls < 2) yield()
        }
        assertEquals("The slow profile remains in its first long poll", 1, slowPolls)
        releaseSlow.complete(Unit)
        assertEquals(KeepConnectedCycle.Connected, cycle.await())
    }

    @Test fun replayedIncomingEventUsesTheExistingReceiptToAvoidAnotherRing() = runTest {
        val store = cursors().apply { reset("https://hermes.example", "owner", "default", 0) }
        val event = JSONObject().put("event_id", "call-1")
        val receipts = mutableSetOf<String>()
        var rings = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = store,
            profiles = { listOf("default") },
            poll = { _, after -> KeepConnectedPage(listOf(event), after) },
            onEvent = { profile, item ->
                if (receipts.add("$profile:${item.getString("event_id")}")) rings += 1
            },
        )

        poller.pollOnce()
        poller.pollOnce()

        assertEquals(1, rings)
    }

    @Test fun refreshFailureStopsFurtherProfilePolling() = runTest {
        var requests = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = cursors(),
            profiles = { listOf("default", "coder") },
            poll = { _, _ -> requests += 1; throw NativeAuthExpiredException() },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.SignedOut, poller.pollOnce())
        assertEquals(KeepConnectedCycle.SignedOut, poller.pollOnce())
        assertTrue(requests <= 2)
    }

    @Test fun networkFailureBacksOffAndSuccessfulPollResetsIt() = runTest {
        var fail = true
        var now = 0L
        var requests = 0
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example",
            owner = "owner",
            cursors = cursors(),
            profiles = { listOf("default") },
            nowMillis = { now },
            poll = { _, after ->
                requests += 1
                if (fail) { fail = false; throw IOException("offline") }
                KeepConnectedPage(emptyList(), after)
            },
            onEvent = { _, _ -> error("No events expected") },
        )

        assertEquals(KeepConnectedCycle.Reconnecting(1_000L), poller.pollOnce())
        assertEquals(KeepConnectedCycle.Reconnecting(1_000L), poller.pollOnce())
        assertEquals(1, requests)
        now = 1_000L
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(2, requests)
        fail = true
        assertEquals(KeepConnectedCycle.Reconnecting(1_000L), poller.pollOnce())
        assertEquals(3, requests)
    }

    @Test fun eventHttpFailuresHaveShortSecretFreeReasons() = runTest {
        val server = MockWebServer().apply { start() }
        val client = client(server)
        try {
            val failures = listOf(
                401 to "Hermes rejected the connection (401)",
                403 to "Hermes refused profile academic (403)",
                404 to "Beacon plugin route missing (404)",
                409 to "Hermes refused the notification request (409)",
                500 to "Hermes server error (500)",
                503 to "Hermes server error (503)",
            )
            failures.forEach { (status, expected) ->
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"private response token"}"""))
                val error = runCatching {
                    client.nativeGetForProfile(
                        "/api/plugins/beacon/mobile/notifications/events", "academic", listOf("academic"),
                        query = mapOf("after" to "0", "wait" to "25"),
                    )
                }.exceptionOrNull()
                assertTrue("expected ApiFailure for HTTP $status, got $error", error is ApiFailure)
                assertEquals(status, (error as ApiFailure).status)
                val reason = keepConnectedFailureReason("academic", error)
                assertEquals(expected, reason)
                assertFalse(reason.contains("private response token"))
                val request = server.takeRequest()
                assertEquals("/api/plugins/beacon/mobile/notifications/events", request.requestUrl!!.encodedPath)
                assertEquals("academic", request.requestUrl!!.queryParameter("beacon_profile"))
                assertNull(request.requestUrl!!.queryParameter("profile"))
            }
        } finally {
            client.closeNative()
            server.shutdown()
        }
    }

    @Test fun profileFailureReasonUsesDisplayNameWhenProvided() {
        assertEquals("Hermes refused profile Research (403)", keepConnectedFailureReason("academic", ApiFailure(403, "private details"), "Research"))
    }

    @Test fun pluginUnauthorizedProfileDoesNotBlockSignedInProfileAndRetriesOnBackoff() = runTest {
        val server = MockWebServer().apply { start() }
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                if (request.requestUrl?.queryParameter("beacon_profile") == "academic") {
                    MockResponse().setResponseCode(403).setBody("""{"detail":"private profile policy"}""")
                } else {
                    val after = request.requestUrl?.queryParameter("after")?.toLongOrNull() ?: 0L
                    MockResponse().setBody(if (after == 0L) """{"events":[{"event_id":"default-call"}],"next_cursor":1}""" else """{"events":[],"next_cursor":1}""")
                }
        }
        val client = client(server)
        try {
            val profiles = listOf("default", "academic")
            val attempts = mutableMapOf<String, Int>()
            val delivered = mutableSetOf<String>()
            var now = 0L
            val poller = KeepConnectedPoller(
                origin = "https://hermes.example",
                owner = "owner",
                cursors = cursors().apply {
                    reset("https://hermes.example", "owner", "default", 0)
                    reset("https://hermes.example", "owner", "academic", 0)
                },
                profiles = { profiles },
                signedInProfile = { "default" },
                nowMillis = { now },
                poll = { profile, after ->
                    attempts[profile] = attempts.getOrDefault(profile, 0) + 1
                    eventPage(client, profile, after, profiles)
                },
                onEvent = { profile, event -> delivered += "$profile:${event.getString("event_id")}" },
            )

            assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
            assertEquals("Hermes refused profile academic (403)", poller.lastFailureReason)
            assertTrue(attempts.getOrDefault("default", 0) >= 1)
            assertEquals(1, attempts["academic"])
            assertEquals(setOf("default:default-call"), delivered)

            assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
            assertEquals(1, attempts["academic"])
            now = 999
            poller.pollOnce()
            assertEquals(1, attempts["academic"])
            now = 1_000
            poller.pollOnce()
            assertEquals(2, attempts["academic"])
        } finally {
            client.closeNative()
            server.shutdown()
        }
    }

    @Test fun invalidEventPageShapesGetASecretFreeReason() = runTest {
        val server = MockWebServer().apply { start() }
        val client = client(server)
        try {
            val invalidPages = listOf(
                """{"events":"private body","next_cursor":0}""" to 0L,
                """{"events":["private body"],"next_cursor":0}""" to 0L,
                """{"events":[],"next_cursor":"private cursor"}""" to 0L,
                """{"events":[],"next_cursor":3}""" to 4L,
                """{"events":[],"next_cursor":-1}""" to 0L,
            )
            invalidPages.forEach { (body, after) ->
                server.enqueue(MockResponse().setBody(body))
                val response = client.nativeGetForProfile(
                    "/api/plugins/beacon/mobile/notifications/events", "default", listOf("default"),
                    query = mapOf("after" to after.toString(), "wait" to "25"),
                )
                val error = runCatching { parseKeepConnectedPage(response, after) }.exceptionOrNull()
                assertTrue("expected invalid page error, got $error", error is KeepConnectedPageException)
                val reason = keepConnectedFailureReason("default", error!!)
                assertEquals("Hermes returned an invalid notification page", reason)
                assertFalse(reason.contains("private"))
            }
        } finally {
            client.closeNative()
            server.shutdown()
        }
    }

    @Test fun readTimeoutIsReportedAndTheDefaultClientAllowsTheTwentyFiveSecondLongPoll() = runTest {
        val server = MockWebServer().apply { start() }
        val defaultClient = client(server)
        try {
            assertEquals(60_000, defaultClient.http.readTimeoutMillis)
            assertEquals(0, defaultClient.http.callTimeoutMillis)
            assertTrue(defaultClient.http.readTimeoutMillis > KEEP_CONNECTED_LONG_POLL_SECONDS * 1_000)
        } finally {
            defaultClient.closeNative()
        }

        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val shortTimeoutClient = client(server, readTimeoutMillis = 150)
        try {
            val error = runCatching {
                shortTimeoutClient.nativeGetForProfile(
                    "/api/plugins/beacon/mobile/notifications/events", "default", listOf("default"),
                    query = mapOf("after" to "0", "wait" to KEEP_CONNECTED_LONG_POLL_SECONDS.toString()),
                )
            }.exceptionOrNull()
            assertTrue("expected read timeout, got $error", error is SocketTimeoutException)
            assertEquals("Hermes did not answer in time", keepConnectedFailureReason("default", error!!))
        } finally {
            shortTimeoutClient.closeNative()
            server.shutdown()
        }
    }

    @Test fun beaconCallEventAdaptsToTheFailClosedRelayBindingAndKeepsTheTwentySecondRing() {
        val scope = Scope("https://hermes.example", "owner", "default")
        val prepared = prepareKeepConnectedCall(scope, callEvent(), enabled = true, now = 1_000_000L, elapsed = 500L)

        assertEquals("c0ffee00112233445566778899aabbcc", prepared!!.call.callId)
        assertEquals(scope.origin, prepared.binding.origin)
        assertEquals(scope.owner, prepared.binding.owner)
        assertEquals(scope.profile, prepared.binding.profile)
        assertEquals("source-opaque-1", prepared.binding.deviceId)
        assertEquals("session-1", prepared.binding.sessionId)
        assertEquals(RelayCallbackPhase.Ringing, prepared.call.phase)
        assertEquals(20_000L, prepared.call.remainingRingMillis(1_000_000L, 500L))
        assertTrue(RelayCallbackPolicy.callsAllowed(prepared.preferences, prepared.binding.connectionId))
        assertNull(prepared.protocol.answer("c0ffee00112233445566778899aabbcc", "forged", prepared.binding, prepared.preferences, 1_000_000L, 500L))
        assertEquals(RelayCallbackPhase.Answering,
            prepared.protocol.answer("c0ffee00112233445566778899aabbcc", prepared.call.actionToken, prepared.binding, prepared.preferences, 1_000_000L, 500L)?.call?.phase)
    }

    @Test fun beaconCallEventHonorsTheServerExpiryAndRejectsForeignOrMalformedScope() {
        val scope = Scope("https://hermes.example", "owner", "default")
        val short = prepareKeepConnectedCall(scope, callEvent(expiresAt = 1_005), true, 1_000_000L, 500L)
        assertEquals(5_000L, short!!.call.remainingRingMillis(1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(owner = "foreign"), true, 1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(profile = "coder"), true, 1_000_000L, 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent(), enabled = false, now = 1_000_000L, elapsed = 500L))
        assertNull(prepareKeepConnectedCall(scope, callEvent().put("conversation_ref", ""), true, 1_000_000L, 500L))
    }

    @Test fun keepConnectedPresentationEndsBeforeItsAnswerAndDeclineAuthority() {
        val scope = Scope("https://hermes.example", "owner", "default")
        val now = 1_000_333L
        val elapsed = 500L
        for (serverExpiry in listOf(1_120L, 1_005L)) {
            val event = callEvent(expiresAt = serverExpiry)
            val prepared = prepareKeepConnectedCall(scope, event, true, now, elapsed)!!
            val data = keepConnectedCallData(event, now)
            val invite = IncomingCall(
                data.getValue("source_ref"), data.getValue("event_id"), data.getValue("expires_at").toLong(),
                actionToken = prepared.call.actionToken, arrivedAt = now + 250, arrivedElapsed = elapsed + 250, boot = 1,
                conversation = data.getValue("conversation_ref"),
            )
            assertEquals(serverExpiry, event.getLong("expires_at"))
            assertEquals(prepared.call.callId, invite.event)
            assertTrue(invite.expiresAt * 1_000 <= prepared.call.expiresAt)
            assertTrue(invite.expiresAt <= serverExpiry)
            assertTrue(invite.ringDurationMillis(now + 250, elapsed + 250, 1) > 0)
            assertTrue(invite.ringDurationMillis(now + 250, elapsed + 250, 1) <=
                prepared.call.remainingRingMillis(now + 250, elapsed + 250))
            assertNull(prepared.protocol.answer(invite.event, invite.actionToken, prepared.binding,
                prepared.preferences.copy(keepConnected = false), now + 1_000, elapsed + 1_000))
            val deadline = minOf(invite.expiresAt * 1_000 - now, 25_000L)
            for (after in listOf(deadline, 25_000L)) {
                assertEquals(0L, invite.ringDurationMillis(now + after, elapsed + after, 1))
            }
            assertEquals(0L, prepared.call.remainingRingMillis(now + 25_000, elapsed + 25_000))
            assertNull(prepared.protocol.answer(invite.event, invite.actionToken, prepared.binding,
                prepared.preferences, now + 25_000, elapsed + 25_000))
            assertNull(prepared.protocol.decline(invite.event, invite.actionToken, prepared.binding,
                prepared.preferences, now + 25_000, elapsed + 25_000))
        }
    }
}
