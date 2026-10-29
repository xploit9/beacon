package dev.hermes.android.data

import dev.hermes.android.core.json
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeProfilesTest {
    private fun keysOf(value: JSONObject): Set<String> = value.keys().asSequence().toSet()

    @Test fun aProfileNameIsCheckedTheWayHermesChecksIt() {
        val none = emptyList<String>()
        for (ok in listOf("coder", " Coder ", "CODER", "my-bot_2", "a", "9lives", "x".repeat(64))) assertNull(ok, profileNameProblem(ok, none))
        for (blank in listOf("", "   ", "\n")) assertEquals(ProfileNameProblem.Empty, profileNameProblem(blank, none))
        for (bad in listOf("x".repeat(65), "-x", "_x", "my bot", "bot!", "../x", "a/b", "é", "co.der")) assertEquals(bad, ProfileNameProblem.Invalid, profileNameProblem(bad, none))
        // hermes_cli/profiles.py _RESERVED_NAMES; "default" is Hermes' own, whatever its case.
        // "active" is Hermes' /api/profiles/active route; a profile with that name could not be deleted from the app.
        for (reserved in listOf("hermes", "default", "Default", "test", "tmp", "root", "sudo", "active")) assertEquals(reserved, ProfileNameProblem.Reserved, profileNameProblem(reserved, none))
        assertEquals(ProfileNameProblem.Taken, profileNameProblem("CODER", listOf("default", "coder")))
        assertEquals(ProfileNameProblem.Taken, profileNameProblem("writer", listOf("Writer")))
        assertNull(profileNameProblem("writer", listOf("default", "coder")))
        assertEquals("coder", profileNameOf("  Coder "))
    }

    @Test fun deleteIsNeverOfferedForDefaultTheProfileInUseOrTheAssistantsProfile() {
        assertTrue(profileDeletable("coder", active = "default", assistant = "default"))
        assertTrue(profileDeletable("coder", active = "writer", assistant = "helper"))
        assertFalse("the built-in profile", profileDeletable("default", active = "coder", assistant = "writer"))
        assertFalse("the profile in use", profileDeletable("coder", active = "coder", assistant = "default"))
        assertFalse("the assistant's profile", profileDeletable("helper", active = "default", assistant = "helper"))
        // Leaving Bot Mode switches back to the profile it was turned on from; deleting that one would trap you in Bot Mode.
        assertFalse("the profile Bot Mode returns to", profileDeletable("writer", active = "coder", assistant = "default", returning = "writer"))
        assertTrue(profileDeletable("helper", active = "coder", assistant = "default", returning = "writer"))
        assertFalse("Hermes' own route", profileDeletable("active", active = "coder", assistant = "default"))
        // A name the delete route would not accept is not offered either.
        for (odd in listOf("", "Coder", "../x", "a b", "x".repeat(65))) assertFalse(odd, profileDeletable(odd, active = "default", assistant = "default"))
    }

    @Test fun theNewProfileBodyCarriesOnlyWhatWasAsked() {
        val bare = newProfileBody(" Coder ", "", "  ")
        assertEquals(setOf("name"), keysOf(bare))
        assertEquals("coder", bare.getString("name"))
        val copy = newProfileBody("coder", "writer", "  Writes code  ")
        assertEquals(setOf("name", "clone_from", "description"), keysOf(copy))
        assertEquals("writer", copy.getString("clone_from"))
        assertEquals("Writes code", copy.getString("description"))
        assertFalse("A copy is Hermes' plain clone, never clone_all", copy.has("clone_all"))
        assertEquals(280, newProfileBody("coder", "", "x".repeat(500)).getString("description").length)
    }

    @Test fun deleteAnswersAreDoneDonePendingOrNotConfirmed() {
        assertEquals(ProfileDelete.Done, profileDeleteOutcome(json("ok" to true, "path" to "x")))
        assertEquals(ProfileDelete.DonePending, profileDeleteOutcome(json("ok" to true, "settlement_pending" to true, "retry_command" to "hermes profile purge-identity coder")))
        assertEquals(ProfileDelete.Done, profileDeleteOutcome(json("ok" to true, "settlement_pending" to false)))
        assertNull(profileDeleteOutcome(json("ok" to false)))
        assertNull(profileDeleteOutcome(JSONObject()))
    }

    private class Harness(scope: CoroutineScope, handler: (String, String, JSONObject?) -> Pair<Int, JSONObject>) {
        val requests = java.util.concurrent.CopyOnWriteArrayList<Triple<String, String, JSONObject?>>()
        val changes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "default"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val body = request.body?.let { val buffer = okio.Buffer(); it.writeTo(buffer); JSONObject(buffer.readUtf8()) }
                requests += Triple(request.method, request.url.encodedPath, body)
                val (code, result) = handler(request.method, request.url.encodedPath, body)
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val admin = NativeProfileAdmin(scope, { client }) { deleted, notice -> changes += deleted to notice }
        suspend fun idle() { yield(); withTimeout(5000) { while (admin.state.value.busy) delay(5) } }
    }

    @Test fun creatingAProfilePostsItsBodyAndReportsOnce() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 200 to json("ok" to true, "name" to "coder") }
        try {
            h.admin.create("Coder", "writer", " Writes code ", listOf("default", "writer")); h.idle()
            val (method, path, body) = h.requests.single()
            assertEquals("POST", method); assertEquals("/api/profiles", path)
            assertEquals(setOf("name", "clone_from", "description"), keysOf(body!!))
            assertEquals("coder", body.getString("name")); assertEquals("writer", body.getString("clone_from")); assertEquals("Writes code", body.getString("description"))
            assertEquals(1, h.admin.state.value.completed)
            assertEquals("", h.admin.state.value.error)
            assertEquals(listOf("" to "Profile created"), h.changes.toList())
        } finally { h.client.closeNative() }
    }

    @Test fun aNameHermesWouldRefuseNeverLeavesThePhone() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 200 to json("ok" to true) }
        try {
            h.admin.create("Not valid!", "", "", emptyList()); h.idle()
            assertEquals(ProfileNameProblem.Invalid.text, h.admin.state.value.error)
            h.admin.create("coder", "", "", listOf("coder")); h.idle()
            assertEquals(ProfileNameProblem.Taken.text, h.admin.state.value.error)
            assertTrue(h.requests.isEmpty())
            assertEquals(0, h.admin.state.value.completed)
            assertTrue(h.changes.isEmpty())
        } finally { h.client.closeNative() }
    }

    @Test fun aNameTakenSinceTheListWasReadSaysSo() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 400 to json("detail" to "Profile 'coder' already exists at /home/x/.hermes/profiles/coder") }
        try {
            h.admin.create("coder", "", "", emptyList()); h.idle()
            assertEquals(ProfileNameProblem.Taken.text, h.admin.state.value.error)
            assertTrue(h.changes.isEmpty())
        } finally { h.client.closeNative() }
    }

    @Test fun aFailedCreateKeepsTheFormOpenWithAMessage() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 400 to json("detail" to "profile exists") }
        try {
            h.admin.create("coder", "", "", emptyList()); h.idle()
            assertTrue(h.admin.state.value.error.isNotBlank())
            assertEquals(0, h.admin.state.value.completed)
            assertTrue(h.changes.isEmpty())
            h.admin.clearError()
            assertEquals("", h.admin.state.value.error)
        } finally { h.client.closeNative() }
    }

    @Test fun deletingSendsDeleteForTheNamedProfileAndTreatsPendingCleanupAsDoneWithANote() = runBlocking<Unit> {
        var answer = json("ok" to true, "path" to "x")
        val h = Harness(this) { _, _, _ -> 200 to answer }
        try {
            h.admin.delete("coder", assistant = "helper"); h.idle()
            assertEquals("DELETE", h.requests.single().first); assertEquals("/api/profiles/coder", h.requests.single().second)
            assertNull(h.requests.single().third)
            assertEquals(listOf("coder" to "Profile deleted"), h.changes.toList())

            answer = json("ok" to true, "settlement_pending" to true, "retry_command" to "hermes profile purge-identity writer")
            h.admin.delete("writer", assistant = "helper"); h.idle()
            assertEquals("writer", h.changes.last().first)
            assertTrue(h.changes.last().second.startsWith("Profile deleted."))
            assertEquals(2, h.admin.state.value.completed)
        } finally { h.client.closeNative() }
    }

    @Test fun deleteRefusesDefaultTheActiveProfileAndTheAssistantsProfileWithoutARequest() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 200 to json("ok" to true) }
        try {
            // The client is on "default", so that is the active profile here.
            for ((name, assistant) in listOf("default" to "helper", "helper" to "helper", "Coder" to "helper")) {
                h.admin.delete(name, assistant); h.idle()
                assertEquals(name, "This profile can't be deleted.", h.admin.state.value.error)
            }
            assertTrue(h.requests.isEmpty())
            assertTrue(h.changes.isEmpty())
        } finally { h.client.closeNative() }
    }

    @Test fun aProfileThatIsAlreadyGoneCountsAsDeletedAndAnUnconfirmedAnswerDoesNot() = runBlocking<Unit> {
        var status = 404
        var answer = json("detail" to "not found")
        // After a 404, Hermes' own list decides: only a profile it no longer lists is gone.
        val listed = org.json.JSONArray().put(json("name" to "default")).put(json("name" to "writer"))
        var list = json("profiles" to listed)
        val h = Harness(this) { method, _, _ -> if (method == "GET") 200 to list else status to answer }
        try {
            h.admin.delete("coder", assistant = "helper"); h.idle()
            assertEquals(listOf("coder" to "Profile deleted"), h.changes.toList())

            // An older Hermes or a stale name also answers 404, but the profile is still listed: not done.
            h.admin.delete("writer", assistant = "helper"); h.idle()
            assertEquals(1, h.changes.size)
            assertEquals("Hermes didn't confirm that. Refresh and try again.", h.admin.state.value.error)

            // An answer that is not Hermes' list says nothing about the profile: not done either.
            list = json(); h.admin.clearError()
            h.admin.delete("coder", assistant = "helper"); h.idle()
            assertEquals(1, h.changes.size)
            assertEquals("Hermes didn't confirm that. Refresh and try again.", h.admin.state.value.error)
            list = json("profiles" to listed)

            status = 200; answer = json("ok" to false)
            h.admin.delete("writer", assistant = "helper"); h.idle()
            assertEquals(1, h.changes.size)
            assertEquals("Hermes didn't confirm that. Refresh and try again.", h.admin.state.value.error)

            status = 500; answer = json("detail" to "boom")
            h.admin.delete("writer", assistant = "helper"); h.idle()
            assertEquals(1, h.changes.size)
            assertTrue(h.admin.state.value.error.isNotBlank())
        } finally { h.client.closeNative() }
    }

    @Test fun aRoleIsSavedWithPutOnTheProfilesDescription() = runBlocking<Unit> {
        val h = Harness(this) { _, _, _ -> 200 to json("ok" to true, "description" to "Writes code") }
        try {
            h.admin.setRole("coder", "  Writes code ")
            h.idle()
            val (method, path, body) = h.requests.single()
            assertEquals("PUT", method); assertEquals("/api/profiles/coder/description", path)
            assertEquals(setOf("description"), keysOf(body!!))
            assertEquals("Writes code", body.getString("description"))
            assertEquals(listOf("" to "Role saved"), h.changes.toList())
            h.admin.setRole("../x", "no"); h.idle()
            assertEquals(1, h.requests.size)
        } finally { h.client.closeNative() }
    }

    @Test fun oneChangeRunsAtATime() = runBlocking<Unit> {
        val gate = java.util.concurrent.CountDownLatch(1)
        val h = Harness(this) { _, _, _ -> check(gate.await(5, java.util.concurrent.TimeUnit.SECONDS)); 200 to json("ok" to true) }
        try {
            h.admin.create("coder", "", "", emptyList())
            assertTrue(h.admin.state.value.busy)
            h.admin.create("writer", "", "", emptyList())
            gate.countDown(); h.idle()
            assertEquals(1, h.requests.size)
            assertEquals(1, h.admin.state.value.completed)
        } finally { gate.countDown(); h.client.closeNative() }
    }
}
