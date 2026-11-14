package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.newChatAction
import dev.hermes.android.newChatOpenNotice
import dev.hermes.android.newChatSecondaryAction
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * One tap turns Beacon on for the profile Hermes does not serve it for. The plugin route (hermes-plugin `admin_api.py`, tested there) is
 * `POST /api/plugins/beacon/admin/profile-plugin/enable {profile}`; the app may send exactly that, for the profile it is signed in to, and
 * nothing else. When the route is missing (404, an older plugin), Beacon is not installed in the dashboard profile or needs an update (501), or Hermes could not turn Beacon on
 * (500), the person gets one line with the exact command to run, copyable, pinned to the latest release's commit like every other Beacon install; busy, updating or slow
 * (409, 503, 504) only asks them to look again; 422 says Hermes no longer has the profile; with no known commit there is no command.
 */
class ProfileTurnOnTest {
    private val path = "/api/plugins/beacon/admin/profile-plugin/enable"
    private val offLine = "Beacon isn't turned on for this profile."
    private val commit = "0123456789abcdef0123456789abcdef01234567"
    private val command = "hermes -p work plugins install xploit9/beacon/hermes-plugin/beacon --force --ref $commit --enable"

    @Test fun theAppMaySendExactlyThatRouteAsAPost() {
        assertEquals(path, PROFILE_PLUGIN_ENABLE_PATH)
        assertTrue(NativeOperationPolicy.direct(path, "POST"))
        for (method in listOf("GET", "PUT", "PATCH", "DELETE")) assertFalse(method, NativeOperationPolicy.direct(path, method))
    }

    @Test fun theBodyIsExactlyOneProfileNameAndNothingElse() {
        assertTrue(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work")))
        assertTrue(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "qa-0db22afcdcec4845a13178a67eaa32d1")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, null))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, JSONObject()))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("profile" to "work", "plugin" to "other")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("plugin" to "work")))
        assertFalse(NativeOperationPolicy.installBodyAllowed(path, json("profile" to 7)))
        for (bad in listOf("", "Work", "work profile", "../default", "work/../default", "-work", "x".repeat(65), "wörk")) {
            assertFalse(bad, NativeOperationPolicy.installBodyAllowed(path, json("profile" to bad)))
        }
        // Every other path keeps its own rule: no body rule for them, and the install route stays strict.
        assertTrue(NativeOperationPolicy.installBodyAllowed("/api/plugins/beacon/mobile/proactive", json("enabled" to true)))
        assertFalse(NativeOperationPolicy.installBodyAllowed("/api/dashboard/agent-plugins/install", json("profile" to "work")))
    }

    @Test fun theLineAndTheButtonsFollowTheState() {
        assertEquals(offLine, PROFILE_PLUGIN_OFF_ERROR)
        assertEquals("", newChatAction("", false))
        assertEquals("", newChatAction(offLine, true))
        assertEquals("Turn on", newChatAction(offLine, false))
        assertEquals("", newChatSecondaryAction(offLine, false))
        val failed = profileTurnOnFailedText("work", commit)
        assertEquals("Copy command", newChatAction(failed, false))
        assertEquals("Check again", newChatSecondaryAction(failed, false))
        assertEquals("", newChatSecondaryAction(failed, true))
        assertEquals("Retry", newChatAction("Couldn't open the chat. Your draft is kept.", false))
        // No command to run (the plugin must be updated first, or Hermes is still working): Check again is the one button, with no Copy command beside it.
        for (look in listOf(PROFILE_UPDATE_FIRST_ERROR, PROFILE_TURN_ON_BUSY_ERROR)) {
            assertEquals(look, "Check again", newChatAction(look, false))
            assertEquals(look, "", newChatSecondaryAction(look, false))
            assertEquals(look, "", newChatAction(look, true))
            assertNull(look, profileTurnOnCommand(look))
        }
        // Hermes no longer has the profile: nothing to run and nothing to wait for, so the plain Retry.
        assertEquals("Retry", newChatAction(PROFILE_GONE_ERROR, false))
        assertEquals("", newChatSecondaryAction(PROFILE_GONE_ERROR, false))
        assertNull(profileTurnOnCommand(PROFILE_GONE_ERROR))
    }

    @Test fun theCommandIsTheDocumentedPinnedInstallForThatProfileOnly() {
        assertEquals(command, profileInstallCommand("work", commit))
        assertEquals("hermes -p coder plugins install xploit9/beacon/hermes-plugin/beacon --force --ref $commit --enable", profileInstallCommand("coder", commit))
        // The same words, in the same order, as the installer the app and the install script use (hermes-plugin beacon_install.py: install SOURCE --force --ref <commit> --enable).
        assertTrue(command.contains(" plugins install xploit9/beacon/hermes-plugin/beacon --force --ref $commit --enable"))
        // Nothing that is not a Hermes profile id reaches a command the person will paste into a terminal.
        for (bad in listOf("", "Work", "a b", "a;b", "a&&b", "\$(x)", "`x`", "a\nb", "-p", "x".repeat(65))) assertNull(bad, profileInstallCommand(bad, commit))
        // Without a full release commit there is no command: an unpinned install would follow the branch tip.
        for (bad in listOf(null, "", "main", commit.take(39), commit + "0", commit.uppercase(), "$commit;rm", "z".repeat(40))) assertNull("$bad", profileInstallCommand("work", bad))
    }

    @Test fun theFailureLineCarriesTheCommandOnItsOwnLine() {
        assertEquals("Couldn't turn Beacon on from here. Run this on your Hermes computer:\n$command", profileTurnOnFailedText("work", commit))
        assertEquals(command, profileTurnOnCommand(profileTurnOnFailedText("work", commit)))
        assertNull(profileTurnOnCommand(offLine))
        assertNull(profileTurnOnCommand("Couldn't open the chat. Your draft is kept."))
        // An id the command must not carry (it cannot happen for a Hermes profile) gets the generic line, which offers no command to copy.
        assertEquals("Hermes couldn't do that. Try again.", profileTurnOnFailedText("a;b", commit))
        assertNull(profileTurnOnCommand(profileTurnOnFailedText("a;b", commit)))
        assertTrue(PROFILE_PLUGIN_COMMAND_HEAD in ErrorStrings && PROFILE_PLUGIN_OFF_ERROR in ErrorStrings)
    }

    @Test fun withNoKnownReleaseThereIsNoCommandOnlyTheUpdateFirstLine() {
        for (none in listOf(null, "", "main", commit.take(39))) {
            val text = profileTurnOnFailedText("work", none)
            assertEquals("Update Beacon on Hermes first, then tap Check again.", text)
            assertNull("$none", profileTurnOnCommand(text))
            assertFalse(text.contains("plugins install"))
        }
        assertTrue(PROFILE_UPDATE_FIRST_ERROR in ErrorStrings && PROFILE_TURN_ON_BUSY_ERROR in ErrorStrings)
    }

    @Test fun theCommandShowsForAMissingRouteAnUpdateOrAFailedTurnOnAndBusyOrSlowSaysStillWorking() {
        // admin_api.py: 404 = only the route is missing (an older plugin), 501 = Beacon is not installed in the dashboard profile or needs an update, 500 = Hermes could not turn
        // Beacon on: in each the command is the way forward.
        for (status in listOf(404, 500, 501)) assertEquals("$status", "Couldn't turn Beacon on from here. Run this on your Hermes computer:\n$command", profileTurnOnFailure("work", commit, ApiFailure(status, "Not Found", plugin = true)))
        // 409 = another change is running (busy only), 503 = an update is in progress (only), 504 = still going after 120 s: nothing to run, look again.
        for (status in listOf(409, 503, 504)) assertEquals("$status", "Hermes is still working. Tap Check again in a moment.", profileTurnOnFailure("work", commit, ApiFailure(status, "Another change is running", plugin = true)))
        // No release known: each status that would show the command says to update first instead of showing an unpinned one; the busy line does not depend on the release.
        for (status in listOf(404, 500, 501)) assertEquals("$status", PROFILE_UPDATE_FIRST_ERROR, profileTurnOnFailure("work", null, ApiFailure(status, "x", plugin = true)))
        for (status in listOf(409, 503, 504)) assertEquals("$status", PROFILE_TURN_ON_BUSY_ERROR, profileTurnOnFailure("work", null, ApiFailure(status, "x", plugin = true)))
        // Anything else keeps Hermes' own plain line, never a command.
        assertEquals("Finish setup on your Hermes dashboard: open the Beacon tab.", profileTurnOnFailure("work", commit, ApiFailure(403, "Owner not authorized", plugin = true)))
        for (status in listOf(400, 401, 429, 502)) assertNull("$status", profileTurnOnCommand(profileTurnOnFailure("work", commit, ApiFailure(status, "x", plugin = true))))
        assertNull(profileTurnOnCommand(profileTurnOnFailure("work", commit, IllegalStateException("x"))))
    }

    @Test fun aProfileHermesNoLongerHasGetsItsOwnShortLineNotACommand() {
        // admin_api.py: 422 = Hermes no longer has this profile: turning Beacon on cannot help, so nothing to run and nothing to wait for.
        for (known in listOf(commit, null)) {
            val text = profileTurnOnFailure("work", known, ApiFailure(422, "Unknown profile", plugin = true))
            assertEquals("Hermes no longer has this profile.", text)
            assertNull(profileTurnOnCommand(text))
        }
        assertEquals("Hermes no longer has this profile.", PROFILE_GONE_ERROR)
        assertTrue(PROFILE_GONE_ERROR in ErrorStrings)
        // Every other 422 keeps its generic line.
        assertEquals("Hermes couldn't use those details. Check them and try again.", appErrorText(ApiFailure(422, "x")))
    }

    @Test fun theAttachNoticeNamesTheButtonTheCardReallyShows() {
        val states = mapOf(
            "Couldn't open the conversation" to "Retry",
            PROFILE_PLUGIN_OFF_ERROR to "Turn on",
            profileTurnOnFailedText("work", commit) to "Check again",
            PROFILE_UPDATE_FIRST_ERROR to "Check again",
            PROFILE_TURN_ON_BUSY_ERROR to "Check again",
            PROFILE_GONE_ERROR to "Retry",
        )
        for ((error, button) in states) {
            val notice = newChatOpenNotice(error)
            assertEquals(error, "Tap $button to open the chat. Your draft is kept.", notice)
            // The named button is one the card has: its main button or the second one beside Copy command.
            assertTrue(error, button == newChatAction(error, false) || button == newChatSecondaryAction(error, false))
        }
        assertTrue(listOf(OPEN_CHAT_NOTICE_RETRY, OPEN_CHAT_NOTICE_TURN_ON, OPEN_CHAT_NOTICE_CHECK_AGAIN).all { it in ErrorStrings })
    }

    private fun client(profile: String, answer: (Request) -> Pair<Int, String>, seen: MutableList<Request>): BeaconClient {
        val sockets = WebSocket.Factory { _, _ -> error("no socket is needed to turn a profile on") }
        return BeaconClient(AuthContext("https://native.example", emptyList(), "owner", profile, backend = "hermes-dashboard"), null, sockets) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); seen += request
                val (code, body) = answer(request)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
    }

    @Test(timeout = 15_000) fun theClientPostsTheOneProfileItIsSignedInToAndNothingElse() = runBlocking {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val client = client("work", { 200 to """{"ok":true,"profile":"work","enabled":true,"changed":true}""" }, seen)
        try {
            val answer = client.turnOnBeaconForProfile()
            assertEquals(true, answer.opt("enabled"))
            val request = seen.single()
            assertEquals("POST", request.method)
            assertEquals(path, request.url.encodedPath)
            assertEquals("work", request.url.queryParameter("beacon_profile"))
            val buffer = okio.Buffer().also { request.body!!.writeTo(it) }
            assertEquals("""{"profile":"work"}""", buffer.readUtf8())
        } finally { client.closeNative() }
    }

    @Test(timeout = 15_000) fun aMissingOrRefusingRouteIsAFailureTheCallerShowsTheCommandFor() = runBlocking {
        for ((code, body) in listOf(404 to """{"detail":"Not Found"}""", 403 to """{"detail":"Owner not authorized"}""", 501 to """{"detail":"needs a Beacon update"}""",
            500 to """{"detail":"Hermes could not turn Beacon on for this profile"}""", 422 to """{"detail":"Hermes no longer has this profile"}""",
            409 to """{"detail":"Another change is running"}""", 503 to """{"detail":"An update is in progress"}""", 504 to """{"detail":"slow"}""")) {
            val client = client("work", { code to body }, java.util.Collections.synchronizedList(mutableListOf()))
            try {
                val failure = runCatching { client.turnOnBeaconForProfile() }.exceptionOrNull()
                assertTrue("$code: $failure", failure is ApiFailure && failure.status == code)
            } finally { client.closeNative() }
        }
    }
}
