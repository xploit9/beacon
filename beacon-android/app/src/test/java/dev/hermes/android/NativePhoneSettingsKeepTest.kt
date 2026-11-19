package dev.hermes.android

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.ui.serverHasCache
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** CACHEFIX-B P1-2 and P2-2: Skills, Memory and the Server pages keep what they show, and show the saved copy before the network is asked. */
class NativePhoneSettingsKeepTest {
    private val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val rows = HashMap<String, String>()
    private val cache = ScreenCache(read = { rows[it.key] }, write = { scope, value -> rows[scope.key] = value }, drop = { rows.remove(it.key) }, jobs = jobs, quietMs = 600_000)
    private fun client(owner: String = "owner", profile: String = "default") = BeaconClient(AuthContext("https://native.example", emptyList(), owner = owner, profile = profile, nativeProvider = "basic"))
    @After fun stop() { jobs.cancel() }

    private fun skills(vararg names: String) = json("items" to JSONArray(names.map { json("name" to it, "description" to "d", "enabled" to true, "essential" to false, "requirements" to JSONArray()) }))
    private fun notes(text: String) = json("status" to json("active" to "builtin"),
        "notes" to json("documents" to JSONArray().put(json("target" to "memory", "content" to text, "revision" to "a".repeat(64), "enabled" to true, "limit" to 2000))))
    private fun health(version: String) = json("status" to json("overall" to "ok", "version" to version, "gateway_state" to "running", "active_agents" to 0), "recent_error" to "")

    private suspend fun saveFor(scope: Scope, vararg entries: Pair<String, org.json.JSONObject>) {
        cache.open(scope)
        entries.forEach { (key, value) -> cache.set(scope, key, value) }
    }
    private fun editor(active: AtomicReference<BeaconClient?>, authorize: suspend (BeaconClient, String) -> Unit = { _, _ -> }) =
        NativePhoneSettings(jobs, SavedStateHandle(), screens = cache, authorizeProfile = authorize) { active.get() }

    @Test fun theSavedSkillsAndNotesShowTheMomentThePagesBind() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"))
        val editor = editor(AtomicReference(a))
        editor.bind()
        assertEquals(listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        assertEquals("saved note", editor.memory.value.documents.single().content)
        a.closeNative()
    }

    @Test fun aReconnectToTheSameProfileKeepsTheListsTheyShow() = runBlocking {
        val first = client(); val second = client()
        saveFor(first.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"))
        val active = AtomicReference<BeaconClient?>(first)
        val editor = editor(active)
        editor.bind()
        cache.remove(first.scope, "settings:skills"); cache.remove(first.scope, "settings:memory")
        active.set(second)
        editor.bind()
        assertEquals("the list stays through the reconnect, not re-read from the saved copy", listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        assertEquals("saved note", editor.memory.value.documents.single().content)
        first.closeNative(); second.closeNative()
    }

    @Test fun leavingSettingsKeepsTheListsAndSigningOutDropsThem() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"))
        val editor = editor(AtomicReference(a))
        editor.bind()
        cache.remove(a.scope, "settings:skills"); cache.remove(a.scope, "settings:memory")
        editor.bind(force = true, keepLists = true)
        assertEquals("leaving Settings does not wipe the lists", listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        assertEquals("saved note", editor.memory.value.documents.single().content)
        assertFalse("only drafts and selections are dropped", editor.skills.value.loading || editor.skills.value.editing || editor.skills.value.selected != null)
        editor.bind(force = true)
        assertTrue("a rebind that is not a trip through Settings (sign-out, another profile) starts clean", editor.skills.value.skills.isEmpty())
        assertTrue(editor.memory.value.documents.isEmpty())
        a.closeNative()
    }

    @Test fun anotherProfileOrAccountNeverInheritsTheLists() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:skills" to skills("travel-planning"))
        val active = AtomicReference<BeaconClient?>(a)
        val editor = editor(active)
        editor.bind()
        assertEquals(1, editor.skills.value.skills.size)
        val coder = client(profile = "coder"); active.set(coder); editor.bind(force = true, keepLists = true)
        assertTrue("another profile of the same account", editor.skills.value.skills.isEmpty())
        val stranger = client(owner = "someone-else"); active.set(stranger); editor.bind(force = true, keepLists = true)
        assertTrue("another account", editor.skills.value.skills.isEmpty())
        a.closeNative(); coder.closeNative(); stranger.closeNative()
    }

    @Test fun theSavedListShowsBeforeHermesAnswersTheProfileCheck() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"))
        val never = CompletableDeferred<Unit>()
        val editor = editor(AtomicReference(a)) { _, _ -> never.await() }
        // A visit that wiped the lists (a rebind with no copy kept), then a plain open while the profile check waits on the network.
        editor.bind(force = true)
        assertTrue(editor.skills.value.skills.isEmpty())
        editor.loadSkills(force = false)
        editor.loadMemory(force = false)
        assertEquals("skills come from the saved copy while authorize waits", listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        assertEquals("notes come from the saved copy while authorize waits", "saved note", editor.memory.value.documents.single().content)
        never.cancel(); a.closeNative()
    }

    @Test fun aCopyThatLoadedAfterThePagesBoundIsShownWithoutAnotherBind() = runBlocking {
        val a = client()
        val restored = AtomicReference<Scope?>(a.scope)
        val editor = NativePhoneSettings(jobs, SavedStateHandle(), screens = cache, cachedScope = { restored.get() }) { null }
        editor.bind()   // a page opened before the account's saved screens were loaded
        assertTrue(editor.skills.value.skills.isEmpty())
        saveFor(a.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"), "settings:health" to health("9.9.9"))
        editor.bind()   // the same scope: a no-op, which is why a separate seed is needed
        assertTrue(editor.skills.value.skills.isEmpty())
        editor.seedSaved()
        assertEquals(listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        assertEquals("saved note", editor.memory.value.documents.single().content)
        assertEquals("a saved health answer fills the page too", "9.9.9", editor.server.value.version)
        a.closeNative()
    }

    @Test fun theBindingBeforeAnyClientExistsShowsTheRestoredAccountsCopy() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:skills" to skills("travel-planning"), "settings:memory" to notes("saved note"))
        val restored = AtomicReference<Scope?>(a.scope)
        val editor = NativePhoneSettings(jobs, SavedStateHandle(), screens = cache, cachedScope = { restored.get() }) { null }
        editor.bind()
        assertEquals("the page binds to the restored account before the client exists", a.scope.key, editor.scopeKey)
        assertEquals(listOf("travel-planning"), editor.skills.value.skills.map { it.name })
        // With no client, nothing can be edited or saved from the copy.
        editor.editMemory("memory")
        assertEquals("", editor.memory.value.editing)
        restored.set(null); editor.bind(force = true)
        assertTrue("sign-out empties it", editor.skills.value.skills.isEmpty() && editor.scopeKey.isBlank())
        a.closeNative()
    }

    @Test fun anEmptyAnswerIsStillAnAnswerSoThePageNeverDrawsASkeletonForIt() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:backups" to json("total_bytes" to 0, "sessions" to JSONArray()))
        val editor = editor(AtomicReference(a))
        assertFalse("before any answer there is nothing to show", serverHasCache("Session checkpoints", editor.server.value))
        editor.bind()
        assertTrue("no backups, answered: the page shows that under its refresh bar, not a skeleton", serverHasCache("Session checkpoints", editor.server.value) && serverHasCache("Rollback checkpoints", editor.server.value))
        assertFalse("Logs were not answered", serverHasCache("Logs", editor.server.value))
        a.closeNative()
    }

    @Test fun theLastHealthUpdateCheckAndBackupsShowBeforeHermesIsAsked() = runBlocking {
        val a = client()
        saveFor(a.scope, "settings:health" to health("9.9.9"), "settings:update-check" to json("update_available" to false, "current_version" to "9.9.9"),
            "settings:backups" to json("total_bytes" to 2048, "sessions" to JSONArray().put(json("session" to "chat-1", "files" to 2, "bytes" to 2048))))
        val active = AtomicReference<BeaconClient?>(a)
        val editor = editor(active)
        editor.bind()
        val shown = editor.server.value
        assertEquals("ok", shown.overall)
        assertEquals("9.9.9", shown.version)
        assertEquals("9.9.9", shown.update?.optString("current_version"))
        assertEquals(1, shown.checkpoints.size)
        assertTrue("it is a saved answer, shown while the real one is fetched", shown.asked && !shown.loading)
        cache.remove(a.scope, "settings:health")
        val again = client(); active.set(again); editor.bind()
        assertEquals("a reconnect to the same host keeps what the pages last showed", "9.9.9", editor.server.value.version)
        // Another host starts empty and has not been asked: the page says it is checking, never "no backups".
        val other = client(owner = "other-host"); active.set(other); editor.bind(force = true)
        assertTrue(editor.server.value.version.isBlank() && editor.server.value.checkpoints.isEmpty() && !editor.server.value.asked)
        a.closeNative(); again.closeNative(); other.closeNative()
    }

    /**
     * JOINBFIX-121 (REVIEW-JOINB-121 P3-5): the saved Health copy says "Log lines are never saved", but it kept `recent_error`, the first sentence of
     * Hermes' newest alarming errors.log line (a path or a URL can be in it). The copy keeps the verdict that sentence gave (Needs attention), not the sentence.
     */
    @Test fun theSavedHealthCopyKeepsTheVerdictAndNoLogLine() = runBlocking {
        val sentence = "FATAL: state.db was replaced underneath the gateway; refusing further writes to this file."
        val logs = mapOf("errors" to "2026-11-13 07:55:23,967 ERROR hermes_state: $sentence Divert transcripts to sessions/<id>.jsonl.",
            "agent" to "2026-11-13 07:55:25,001 INFO gateway.run: inbound message")
        val a = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "default", nativeProvider = "basic"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val answer = when (request.url.encodedPath) {
                    "/api/status" -> json("overall" to "ok", "version" to "9.9.9", "gateway_state" to "running", "active_agents" to 0)
                    "/api/logs" -> json("lines" to JSONArray(listOfNotNull(logs[request.url.queryParameter("file")])))
                    else -> error("Unexpected request ${request.url}")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(answer.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        cache.open(a.scope)
        val editor = editor(AtomicReference(a))
        editor.bind()
        editor.loadServer("Health")
        val until = System.nanoTime() + 10_000_000_000L
        while ((!editor.server.value.answered.contains("Health") || cache.exact(a.scope, "settings:health") == null) && System.nanoTime() < until) delay(10)
        assertEquals("the live page says Hermes reported an error", sentence, editor.server.value.recentError)
        assertEquals("degraded", editor.server.value.overall)
        val copy = cache.exact(a.scope, "settings:health")!!.value
        cache.flush(a.scope)
        val stored = rows.values.joinToString()
        assertFalse("no log sentence is saved", copy.has("recent_error") || stored.contains("recent_error") || stored.contains("state.db"))
        assertEquals("the verdict is", "degraded", copy.getJSONObject("status").getString("overall"))
        val reopened = editor(AtomicReference(a)); reopened.bind()  // a new editor (a restart) shows the saved copy
        assertEquals("the saved copy shows the verdict", "degraded", reopened.server.value.overall)
        assertEquals("", reopened.server.value.recentError)
        a.closeNative()
    }
}
