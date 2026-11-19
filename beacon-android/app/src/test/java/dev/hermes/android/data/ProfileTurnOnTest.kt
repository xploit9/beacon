package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.newChatAction
import dev.hermes.android.newChatOpenNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Beacon works on every profile, as Hermes' dashboard does, and nobody is asked to turn it on. When Hermes refuses the plugin for the profile the app is signed in to (403
 * "Plugin not authorized", `canonical_transport.native_policy`, before the plugin does anything), the client asks the plugin's route by itself
 * (`POST /api/plugins/beacon/admin/profile-plugin/enable {profile, auto: true}`, hermes-plugin `admin_api.py`, tested there) and runs the refused request once more. Concurrent
 * refusals share one turn-on; the route itself is never retried that way; a profile that turned Beacon off on Hermes stays off. Only when that does not work does the person see
 * one short line with a working button: Turn on for a profile that turned Beacon off, Retry when turning it on failed, nothing for a profile Hermes no longer has.
 */
class ProfileTurnOnTest {
    private val path = "/api/plugins/beacon/admin/profile-plugin/enable"
    private val ticket = "/api/plugins/beacon/mobile/gateway-ticket"
    private val offLine = "Beacon isn't turned on for this profile."
    private val failedLine = "Couldn't turn on Beacon for this profile."
    private val goneLine = "Hermes no longer has this profile."
    private val refusalBody = """{"detail":"Plugin not authorized"}"""

    @Test fun theAppMaySendExactlyThatRouteAsAPost() {
        assertEquals(path, PROFILE_PLUGIN_ENABLE_PATH)
        assertTrue(NativeOperationPolicy.direct(path, "POST"))
        for (method in listOf("GET", "PUT", "PATCH", "DELETE")) assertFalse(method, NativeOperationPolicy.direct(path, method))
    }

    @Test fun theBodyIsOneProfileNameAndAtMostAutoTrue() {
        assertTrue(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work")))
        assertTrue(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work", "auto" to true)))
        assertTrue(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "qa-0db22afcdcec4845a13178a67eaa32d1")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, null))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, JSONObject()))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("auto" to true)))
        for (auto in listOf<Any>(false, "true", 1)) assertFalse("$auto", NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work", "auto" to auto)))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work", "plugin" to "other")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work", "auto" to true, "plugin" to "other")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("plugin" to "work")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("profile" to 7)))
        for (bad in listOf("", "Work", "work profile", "../default", "work/../default", "-work", "x".repeat(65), "wörk")) {
            assertFalse(bad, NativeOperationPolicy.installBodyAllowed(path, json("profile" to bad)))
        }
        // Every other path keeps its own rule: no body rule for them, and the install route stays strict.
        assertTrue(NativeOperationPolicy.installBodyAllowed("/api/plugins/beacon/mobile/proactive", json("enabled" to true)))
        assertFalse(NativeOperationPolicy.installBodyAllowed("/api/dashboard/agent-plugins/install", json("profile" to "work")))
    }

    @Test fun eachReasonHasOneShortLineAndItsButton() {
        assertEquals(offLine, PROFILE_PLUGIN_OFF_ERROR)
        assertEquals(failedLine, PROFILE_TURN_ON_FAILED_ERROR)
        assertEquals(goneLine, PROFILE_GONE_ERROR)
        assertEquals(setOf(offLine, failedLine, goneLine), BeaconOffLines)
        // The profile turned Beacon off on Hermes: the owner's own Turn on.
        assertEquals(offLine, beaconOffLine(BeaconOffFailure(null)))
        // Turning it on did not work (busy, updating, slow, failed, a plugin without the route, no connection): Retry.
        for (status in listOf(401, 403, 404, 409, 500, 501, 503, 504)) assertEquals("$status", failedLine, beaconOffLine(BeaconOffFailure(ApiFailure(status, "x", plugin = true))))
        // A 1.2.0 plugin takes exactly {profile} and answers the phone's {profile, auto} with 400: the Turn on line, whose button sends no auto, works there; Retry never would.
        assertEquals(offLine, beaconOffLine(BeaconOffFailure(ApiFailure(400, "Choose one profile", plugin = true))))
        assertEquals("Turn on", beaconOffAction(beaconOffLine(BeaconOffFailure(ApiFailure(400, "Choose one profile", plugin = true)))))
        assertEquals(failedLine, beaconOffLine(BeaconOffFailure(java.net.ConnectException("refused"))))
        // Hermes no longer has the profile: nothing to tap.
        assertEquals(goneLine, beaconOffLine(BeaconOffFailure(ApiFailure(422, "Unknown profile", plugin = true))))
        // A refusal the phone did not try to fix (it is never for this client's profile) reads as the failure, with Retry, never as an owner's off.
        assertEquals(failedLine, beaconOffLine(ApiFailure(403, "Plugin not authorized", plugin = true)))
        assertEquals(failedLine, appErrorText(ApiFailure(403, "Plugin not authorized", plugin = true)))
        // The line is found through a not-sent turn too, and every screen's own error text is the same line.
        val wrapped = java.io.IOException("Native connection admission ended", BeaconOffFailure(null))
        assertEquals(offLine, beaconOffLine(wrapped))
        for (failure in listOf(BeaconOffFailure(null), BeaconOffFailure(ApiFailure(500, "x", true)), BeaconOffFailure(ApiFailure(422, "x", true)))) {
            assertEquals(beaconOffLine(failure), appErrorText(failure))
            assertTrue(beaconOffForProfile(failure))
        }
        assertEquals("Turn on", beaconOffAction(offLine))
        assertEquals("Retry", beaconOffAction(failedLine))
        assertEquals("", beaconOffAction(goneLine))
        assertTrue(listOf(offLine, failedLine, goneLine).all { it in ErrorStrings })
    }

    @Test fun theFirstChatCardShowsTheButtonItsLineNames() {
        assertEquals("", newChatAction("", false))
        for (line in BeaconOffLines) assertEquals(line, "", newChatAction(line, true))
        assertEquals("Turn on", newChatAction(offLine, false))
        assertEquals("Retry", newChatAction(failedLine, false))
        assertEquals("", newChatAction(goneLine, false))
        assertEquals("Retry", newChatAction("Couldn't open the chat. Your draft is kept.", false))
    }

    @Test fun theAttachNoticeNamesTheButtonTheCardReallyShows() {
        assertEquals(OPEN_CHAT_NOTICE_RETRY, newChatOpenNotice("Couldn't open the conversation"))
        assertEquals(OPEN_CHAT_NOTICE_TURN_ON, newChatOpenNotice(offLine))
        assertEquals(OPEN_CHAT_NOTICE_RETRY, newChatOpenNotice(failedLine))
        // No button on the card: the notice is the card's own line.
        assertEquals(goneLine, newChatOpenNotice(goneLine))
        assertTrue(listOf(OPEN_CHAT_NOTICE_RETRY, OPEN_CHAT_NOTICE_TURN_ON).all { it in ErrorStrings })
    }

    @Test fun nothingOfTheManualFlowIsLeft() {
        val main = listOf(".", "app", "beacon-android/app").map { java.io.File(it, "src/main/java") }.first { it.isDirectory }
        val source = main.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (gone in listOf("PROFILE_PLUGIN_COMMAND_HEAD", "profileInstallCommand", "profileTurnOnCommand", "PROFILE_UPDATE_FIRST_ERROR", "PROFILE_TURN_ON_BUSY_ERROR",
                "OPEN_CHAT_NOTICE_CHECK_AGAIN", "releaseCommitForTurnOn", "newChatSecondaryAction", "\"Copy command\"", "\"Check again\"", "Turn on Beacon for {0}?")) {
            assertFalse(gone, gone in source)
        }
    }

    private class Wire(private val answer: (Request) -> Pair<Int, String>) {
        val seen: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())
        fun paths() = seen.map { it.url.encodedPath }
        /** The turn-on bodies, keys sorted (org.json on the JVM does not keep their order). */
        fun bodies() = seen.filter { it.url.encodedPath == "/api/plugins/beacon/admin/profile-plugin/enable" }.map { request ->
            val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            body.keys().asSequence().sorted().joinToString(",", "{", "}") { "$it=${body.get(it)}" }
        }
        fun client(profile: String = "work"): BeaconClient {
            val sockets = WebSocket.Factory { _, _ -> error("no socket is needed here") }
            return BeaconClient(AuthContext("https://native.example", emptyList(), "owner", profile, backend = "hermes-dashboard"), null, sockets) { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request(); seen += request
                    val (code, body) = answer(request)
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
                }
            }
        }
    }

    @Test(timeout = 15_000) fun theOwnersTurnOnPostsTheOneProfileItIsSignedInToAndThePhoneAddsAuto() = runBlocking {
        val wire = Wire { 200 to """{"ok":true,"profile":"work","enabled":true,"changed":true}""" }
        val client = wire.client()
        try {
            assertEquals(true, client.turnOnBeaconForProfile(auto = false).opt("enabled"))
            assertEquals(true, client.turnOnBeaconForProfile(auto = true).opt("enabled"))
            assertEquals(listOf(path, path), wire.paths())
            assertTrue(wire.seen.all { it.method == "POST" && it.url.queryParameter("beacon_profile") == "work" })
            assertEquals(listOf("{profile=work}", "{auto=true,profile=work}"), wire.bodies())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun aRefusedRequestTurnsBeaconOnByItselfAndRunsOnceMore() = runBlocking {
        var on = false
        val wire = Wire { request ->
            when (request.url.encodedPath) {
                path -> { on = true; 200 to """{"ok":true,"profile":"work","enabled":true,"changed":true}""" }
                else -> if (on) 200 to """{"ticket":"fixture-ticket"}""" else 403 to refusalBody
            }
        }
        val client = wire.client()
        try {
            assertEquals("fixture-ticket", client.nativeRequest(ticket, "POST", JSONObject()).string("ticket"))
            assertEquals(listOf(ticket, path, ticket), wire.paths())
            assertEquals(listOf("{auto=true,profile=work}"), wire.bodies())
            // Once on, nothing more is asked.
            client.nativeRequest(ticket, "POST", JSONObject())
            assertEquals(listOf(ticket, path, ticket, ticket), wire.paths())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun aProfileThatTurnedBeaconOffStaysOffAndTheRequestIsNotRunAgain() = runBlocking {
        val wire = Wire { request -> if (request.url.encodedPath == path) 200 to """{"ok":true,"profile":"work","enabled":false,"changed":false}""" else 403 to refusalBody }
        val client = wire.client()
        try {
            val failure = runCatching { client.nativeRequest(ticket, "POST", JSONObject()) }.exceptionOrNull()
            assertTrue("$failure", failure is BeaconOffFailure && failure.reason == null)
            assertEquals(offLine, appErrorText(failure!!))
            assertEquals(listOf(ticket, path), wire.paths())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun aTurnOnThatFailsIsOneLineWithRetryAndTheRequestIsNotRunAgain() = runBlocking {
        for ((code, line) in listOf(500 to failedLine, 501 to failedLine, 409 to failedLine, 503 to failedLine, 504 to failedLine, 404 to failedLine, 422 to goneLine)) {
            val wire = Wire { request -> if (request.url.encodedPath == path) code to """{"detail":"x"}""" else 403 to refusalBody }
            val client = wire.client()
            try {
                val failure = runCatching { client.nativeRequest(ticket, "POST", JSONObject()) }.exceptionOrNull()
                assertTrue("$code: $failure", failure is BeaconOffFailure && (failure.reason as? ApiFailure)?.status == code)
                assertEquals("$code", line, appErrorText(failure!!))
                assertEquals("$code", listOf(ticket, path), wire.paths())
            } finally { client.closeNative() }
        }
    }

    /** A 1.2.1 phone on a 1.2.0 plugin: the plugin refuses the auto body (exactly `{profile}`, 400 "Choose one profile"), and the owner's Turn on, which sends no auto, works. */
    @Test(timeout = 15_000) fun onAPluginWithoutAutoTheLineIsTurnOnAndTurnOnWorks() = runBlocking {
        var on = false
        val wire = Wire { request ->
            when {
                request.url.encodedPath == path -> {
                    val body = JSONObject(okio.Buffer().also { request.body!!.writeTo(it) }.readUtf8())
                    if (body.length() != 1) 400 to """{"detail":"Choose one profile"}""" else { on = true; 200 to """{"ok":true,"profile":"work","enabled":true,"changed":true}""" }
                }
                on -> 200 to """{"ticket":"fixture-ticket"}"""
                else -> 403 to refusalBody
            }
        }
        val client = wire.client()
        try {
            val failure = runCatching { client.nativeRequest(ticket, "POST", JSONObject()) }.exceptionOrNull()
            assertTrue("$failure", failure is BeaconOffFailure && (failure.reason as? ApiFailure)?.status == 400)
            assertEquals(offLine, appErrorText(failure!!))
            assertEquals("Turn on", beaconOffAction(appErrorText(failure)))
            // The button: the owner's own turn-on, then the refused step works.
            assertEquals(true, client.turnOnBeaconForProfile(auto = false).opt("enabled"))
            assertEquals("fixture-ticket", client.nativeRequest(ticket, "POST", JSONObject()).string("ticket"))
            assertEquals(listOf("{auto=true,profile=work}", "{profile=work}"), wire.bodies())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun aSecondRefusalAfterTurningOnIsTheFailureNeverALoop() = runBlocking {
        val wire = Wire { request -> if (request.url.encodedPath == path) 200 to """{"ok":true,"enabled":true,"changed":true}""" else 403 to refusalBody }
        val client = wire.client()
        try {
            val failure = runCatching { client.nativeRequest(ticket, "POST", JSONObject()) }.exceptionOrNull()
            assertTrue("$failure", failure is BeaconOffFailure && failure.reason != null)
            assertEquals(failedLine, appErrorText(failure!!))
            assertEquals(listOf(ticket, path, ticket), wire.paths())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun twoConcurrentRefusalsBothSucceed() = runBlocking {
        // Each refused request asks by itself; the plugin serialises turn-ons and answers a repeat without writing (changed: false).
        val bothRefused = CountDownLatch(2)
        val turnOns = AtomicInteger()
        val on = java.util.concurrent.atomic.AtomicBoolean()
        val wire = Wire { request ->
            when {
                request.url.encodedPath == path -> { val first = turnOns.incrementAndGet() == 1; on.set(true); 200 to """{"ok":true,"enabled":true,"changed":$first}""" }
                on.get() -> 200 to """{"ticket":"fixture-ticket"}"""
                else -> { bothRefused.countDown(); bothRefused.await(5, TimeUnit.SECONDS); 403 to refusalBody }
            }
        }
        val client = wire.client()
        try {
            val answers = (1..2).map { async(Dispatchers.IO) { client.nativeRequest(ticket, "POST", JSONObject()).string("ticket") } }.awaitAll()
            assertEquals(listOf("fixture-ticket", "fixture-ticket"), answers)
            assertTrue("${turnOns.get()}", turnOns.get() in 1..2)
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun anEndedSignInDuringTheTurnOnIsTheSignInPromptNotTheRetryLine() = runBlocking {
        // The ticket is refused; the turn-on meets an expired session and the refresh is refused: the person must sign in again, never see Retry.
        val seen = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val bearer = NativeBearerRecord("old-access", "old-refresh", 0, "https://native.example", "owner", "work", "fixture")
        val client = BeaconClient(AuthContext(bearer.origin, emptyList(), bearer.owner, bearer.profile, backend = "hermes-dashboard", bearer = bearer), null,
            WebSocket.Factory { _, _ -> error("no socket is needed here") }) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); seen += request
                val (code, body) = when (request.url.encodedPath) {
                    ticket -> 403 to refusalBody
                    path -> 401 to """{"error":"session_expired","reason":"invalid_or_expired_session"}"""
                    else -> 401 to """{"detail":"Unauthorized"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        try {
            val failure = runCatching { client.nativeRequest(ticket, "POST", JSONObject()) }.exceptionOrNull()
            assertTrue("$failure", failure is NativeAuthExpiredException)
            assertFalse(beaconOffForProfile(failure!!))
            assertEquals(listOf(ticket, path, "/auth/native/refresh"), seen.map { it.url.encodedPath })
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun onlyThisProfilesOwnPluginRefusalIsFixedThisWay() = runBlocking {
        // The route itself refusing is just its answer; another 403 is not this refusal; a request for another profile is never turned on from this client.
        val cases = listOf<Triple<String, (BeaconClient) -> Unit, Pair<Int, String>>>(
            Triple("the route", { c -> runBlocking { c.turnOnBeaconForProfile(auto = true) } }, 403 to refusalBody),
            Triple("owner", { c -> runBlocking { c.nativeRequest(ticket, "POST", JSONObject()) } }, 403 to """{"detail":"Owner not authorized"}"""),
            Triple("another profile", { c -> runBlocking { c.nativeGetForProfile("/api/plugins/beacon/mobile/phone/requests", "coder", listOf("work", "coder")) } }, 403 to refusalBody),
        )
        for ((name, call, answer) in cases) {
            val wire = Wire { answer }
            val client = wire.client()
            try {
                val failure = runCatching { call(client) }.exceptionOrNull()
                assertTrue("$name: $failure", failure is ApiFailure && failure !is BeaconOffFailure && failure.status == 403)
                assertEquals(name, 1, wire.seen.size)
            } finally { client.closeNative() }
        }
    }

    @Test(timeout = 15_000) fun theOwnersTurnOnAnswersEachStatusAsAFailure() = runBlocking {
        for ((code, body) in listOf(404 to """{"detail":"Not Found"}""", 403 to """{"detail":"Owner not authorized"}""", 501 to """{"detail":"needs a Beacon update"}""",
            500 to """{"detail":"Hermes could not turn Beacon on for this profile"}""", 422 to """{"detail":"Hermes no longer has this profile"}""",
            409 to """{"detail":"Another change is running"}""", 503 to """{"detail":"An update is in progress"}""", 504 to """{"detail":"slow"}""")) {
            val wire = Wire { code to body }
            val client = wire.client()
            try {
                val failure = runCatching { client.turnOnBeaconForProfile(auto = false) }.exceptionOrNull()
                assertTrue("$code: $failure", failure is ApiFailure && failure.status == code)
            } finally { client.closeNative() }
        }
    }
}
