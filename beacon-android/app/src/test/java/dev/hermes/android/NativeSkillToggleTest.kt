package dev.hermes.android

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativeSkillToggleTest {
    private val mail = NativeSkill("mail", "Read mail", "work", false, "bundled")
    private val notes = NativeSkill("notes", "Notes", "work", true, "bundled")

    @Test fun withSkillEnabledSwitchesOnlyThatSkillAndTheOpenOne() {
        val state = NativeSkillsState(skills = listOf(mail, notes), selected = mail)
        val on = state.withSkillEnabled("mail", true)
        assertEquals(listOf(true, true), on.skills.map { it.enabled })
        assertTrue(on.selected!!.enabled)
        // Another skill open: it is untouched.
        val other = state.copy(selected = notes).withSkillEnabled("mail", true)
        assertEquals(notes, other.selected)
        // The same call with the old value is the rollback and restores the state exactly.
        assertEquals(state, on.withSkillEnabled("mail", false))
        // An unknown name changes nothing.
        assertEquals(state, state.withSkillEnabled("missing", true))
    }

    /** One skill, "mail"; [failToggle] makes Hermes refuse the change. [seen] is what the screen showed while the request was in flight. */
    private class SkillServer(val failToggle: Boolean) {
        @Volatile var enabled = false
        @Volatile var seen: Boolean? = null
        lateinit var editor: NativePhoneSettings
        /** Hermes answers the change only after the test has looked at the screen. */
        val gate = java.util.concurrent.CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        fun client() = BeaconClient(AuthContext("https://skills.example", emptyList(), owner = "owner", profile = "work"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                requests += "${request.method} ${request.url.encodedPath}"
                val (code, body) = when {
                    request.url.encodedPath == "/api/plugins/beacon/mobile/skills" ->
                        200 to """{"items":[{"name":"mail","description":"Read mail","category":"work","enabled":$enabled,"essential":false,"requirements":[]}],"warnings":[]}"""
                    request.url.encodedPath.endsWith("toggle") -> {
                        seen = editor.skills.value.skills.single().enabled
                        check(gate.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "the test never released the answer" }
                        if (failToggle) 500 to """{"error":"Hermes is busy"}""" else { enabled = true; 200 to """{"ok":true,"name":"mail","enabled":true}""" }
                    }
                    else -> 404 to "{}"
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
    }

    private fun scenario(failToggle: Boolean, verify: (NativePhoneSettings, SkillServer) -> Unit) = runBlocking {
        val server = SkillServer(failToggle)
        val client = server.client()
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NativePhoneSettings(worker, SavedStateHandle()) { client }
            server.editor = editor
            editor.loadSkills()
            worker.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(listOf(false), editor.skills.value.skills.map { it.enabled })
            editor.toggleSkill(editor.skills.value.skills.single(), true)
            // The switch has moved before Hermes has answered anything.
            assertTrue(editor.skills.value.skills.single().enabled)
            assertFalse("no screen-wide saving state while a switch saves", editor.skills.value.saving)
            assertEquals(editor.skills.value.skills.single().name, editor.skills.value.toggling)
            server.gate.countDown()
            worker.coroutineContext[Job]!!.children.toList().joinAll()
            verify(editor, server)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun theSwitchMovesAtOnceAndStaysOnceHermesConfirms() = scenario(failToggle = false) { editor, server ->
        val state = editor.skills.value
        assertTrue("shown as on while the request ran", server.seen == true)
        assertTrue(state.skills.single().enabled)
        assertFalse(state.saving); assertFalse(state.loading)
        assertNull(state.error); assertNull(state.toggling)
        // The switch itself shows the change: no notice is added above the list.
        assertNull(state.notice)
        // One write, and the one read that confirms it (inside toggle) doubles as the refresh: no second list request after it.
        assertEquals(listOf("GET /api/plugins/beacon/mobile/skills", "PUT /api/skills/toggle", "GET /api/plugins/beacon/mobile/skills"), server.requests.toList())
    }

    @Test(timeout = 15_000) fun aFailedChangePutsTheSwitchBackAndSaysWhy() = scenario(failToggle = true) { editor, server ->
        val state = editor.skills.value
        assertTrue("shown as on while the request ran", server.seen == true)
        assertFalse("rolled back", state.skills.single().enabled)
        assertFalse(state.saving); assertFalse(state.loading)
        assertFalse(state.error.isNullOrBlank()); assertNull(state.toggling)
        assertNull(state.notice)
        assertEquals(listOf("GET /api/plugins/beacon/mobile/skills", "PUT /api/skills/toggle"), server.requests.toList())
    }
}
