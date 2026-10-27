package dev.hermes.android.data

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenCacheTest {
    private val rows = HashMap<String, String>()
    private val jobs = CoroutineScope(SupervisorJob())
    // A long quiet period keeps the automatic flush out of the way; tests flush explicitly.
    private fun newCache() = ScreenCache(read = { rows[it.key] }, write = { scope, value -> rows[scope.key] = value }, drop = { rows.remove(it.key) }, jobs = jobs, quietMs = 600_000)
    private val default = Scope("https://native.example", "owner", "default", nativeProvider = "provider")
    private val other = default.copy(profile = "other")
    private fun at(n: Long) = 1_000L + n
    @After fun stop() { jobs.cancel() }

    @Test fun answersAreIsolatedByProfileAndByAccount() = runBlocking<Unit> {
        val cache = newCache()
        val stranger = default.copy(owner = "someone-else")
        val elsewhere = default.copy(origin = "https://other.example")
        for (scope in listOf(default, other, stranger, elsewhere)) cache.open(scope)
        cache.put(default, "crons", "/api/crons", at(1), json("jobs" to "default"))
        cache.put(other, "crons", "/api/crons", at(2), json("jobs" to "other"))
        assertEquals("default", cache.latest(default).single().value.getString("jobs"))
        assertEquals("other", cache.latest(other).single().value.getString("jobs"))
        assertTrue(cache.latest(stranger).isEmpty())
        assertTrue(cache.latest(elsewhere).isEmpty())
        assertNull(cache.exact(default, "crons", "/api/crons&path=x"))
        cache.flush(default)
        assertEquals(1, rows.size)
        assertEquals("default", newCache().apply { open(default) }.exact(default, "crons", "/api/crons")?.value?.getString("jobs"))
        assertTrue(newCache().apply { open(stranger) }.latest(stranger).isEmpty())
    }

    @Test fun hydrationReturnsTheNewestAnswerOfEachKeyAfterARestart() = runBlocking<Unit> {
        val cache = newCache()
        cache.open(default)
        cache.put(default, "files", "/api/pc/files&path=.", at(1), json("path" to "home"))
        cache.put(default, "files", "/api/pc/files&path=/work", at(3), json("path" to "work"))
        cache.put(default, "crons", "/api/crons", at(2), json("jobs" to 2))
        cache.flush(default)
        val restarted = newCache().apply { open(default) }
        val latest = restarted.latest(default).associateBy { it.key }
        assertEquals(setOf("files", "crons"), latest.keys)
        assertEquals("work", latest.getValue("files").value.getString("path"))
        assertEquals(at(3), latest.getValue("files").at)
        assertEquals("home", restarted.exact(default, "files", "/api/pc/files&path=.")?.value?.getString("path"))
    }

    @Test fun theOldestAnswersAreEvictedWhenTheRowGetsTooBigOrTooLong() = runBlocking<Unit> {
        val bulky = newCache().apply { open(default) }
        repeat(14) { bulky.put(default, "k$it", "", at(it.toLong()), json("blob" to "x".repeat(300_000))) }
        assertNull(bulky.exact(default, "k0"))
        assertNotNull(bulky.exact(default, "k13"))
        assertTrue(bulky.latest(default).sumOf { it.bytes } <= ScreenCache.MAX_ROW_BYTES)
        val many = newCache().apply { open(other) }
        repeat(ScreenCache.MAX_ENTRIES + 2) { many.put(other, "k$it", "", at(it.toLong()), json("n" to it)) }
        assertEquals(ScreenCache.MAX_ENTRIES, many.latest(other).size)
        assertNull(many.exact(other, "k0"))
        assertNotNull(many.exact(other, "k${ScreenCache.MAX_ENTRIES + 1}"))
    }

    @Test fun chatProjectsKeepOnlyTheNewestTwentyAndNeverPushOutTheScreens() = runBlocking<Unit> {
        val cache = newCache().apply { open(default) }
        cache.put(default, "profiles", "/api/profiles", at(0), json("n" to 0))
        repeat(25) { cache.put(default, "project-of:c$it", screenRequest("/api/projects/for-session", mapOf("chat" to "c$it")), at(it + 1L), json("n" to it)) }
        val chats = cache.latest(default).filter { it.request.startsWith(CHAT_PROJECT_READ) }
        assertEquals(ScreenCache.CHAT_PROJECT_ENTRIES, chats.size)
        assertEquals((5 until 25).map { "project-of:c$it" }.toSet(), chats.map { it.key }.toSet())
        assertNotNull(cache.exact(default, "profiles", "/api/profiles"))
    }

    @Test fun oversizedAndCredentialShapedAnswersAreNeverKeptAndReplaceTheOlderCopy() = runBlocking<Unit> {
        val cache = newCache().apply { open(default) }
        cache.put(default, "models", "", at(1), json("groups" to 1))
        cache.put(default, "models", "", at(2), json("blob" to "x".repeat(ScreenCache.MAX_ENTRY_BYTES + 1)))
        assertNull("an answer too big for the cache must not leave its older copy behind", cache.exact(default, "models"))
        cache.put(default, "settings", "", at(3), json("access_token" to "secret"))
        cache.put(default, "nested", "", at(3), json("rows" to JSONArray().put(json("Authorization" to "Bearer x"))))
        cache.put(default, "limits", "", at(3), json("max_tokens" to 10, "context_tokens" to 20))
        assertNull(cache.exact(default, "settings"))
        assertNull(cache.exact(default, "nested"))
        assertNotNull(cache.exact(default, "limits"))
        for (name in listOf("token", "session_token", "refresh_token", "client_secret", "password", "cookie", "Authorization", "api_key", "OPENAI_API_KEY", "pairing_code", "csrf"))
            assertTrue(name, hasSecretKey(json(name to "x")))
        for (name in listOf("max_tokens", "tokens", "context_window", "name", "profile")) assertFalse(name, hasSecretKey(json(name to "x")))
        assertFalse("a flag or an empty value is not a credential", hasSecretKey(json("has_api_key" to true, "pairing_required" to false, "token" to "", "cookie" to org.json.JSONObject.NULL)))
    }

    @Test fun clearingAnAccountDropsEveryProfileAndAPendingFlushCannotBringItBack() = runBlocking<Unit> {
        val cache = newCache()
        cache.open(default)
        cache.put(default, "crons", "/api/crons", at(1), json("jobs" to 1))
        cache.put(other, "crons", "/api/crons", at(2), json("jobs" to 2))
        cache.flush(default)
        val stranger = default.copy(owner = "someone-else")
        cache.open(stranger)
        cache.put(stranger, "crons", "/api/crons", at(3), json("jobs" to 3))
        cache.flush(stranger)
        assertEquals(2, rows.size)
        cache.put(default, "crons", "/api/crons", at(4), json("jobs" to 4))
        cache.clear(default)
        cache.flush(default)
        assertEquals("only the other account's row remains", 1, rows.size)
        assertTrue(cache.latest(default).isEmpty() && cache.latest(other).isEmpty())
        cache.put(default, "crons", "/api/crons", at(5), json("jobs" to 5))
        assertTrue("a closed account takes no answers until it is opened again", cache.latest(default).isEmpty())
        assertEquals(1, cache.latest(stranger).size)
        assertTrue(newCache().apply { open(other) }.latest(other).isEmpty())
    }

    @Test fun aReadThatFinishesAfterSignOutKeepsAndWritesNothing() = runBlocking<Unit> {
        val cache = newCache().apply { open(default) }
        cache.set(default, "settings:skills", json("skills" to 1))
        cache.flush(default)
        assertEquals(1, rows.size)
        cache.clear(default)
        assertTrue(rows.isEmpty())
        // A late read or removal must not reopen the account for the late answer that follows it.
        assertTrue(cache.get(default, "settings:skills") == null)
        cache.remove(default, "settings:skills")
        cache.set(default, "settings:skills", json("skills" to 2))
        assertTrue("a cleared account takes no late answer", cache.latest(default).isEmpty())
        cache.flush(default)
        assertTrue("nothing is written for a cleared account", rows.isEmpty())
        cache.open(default)
        cache.set(default, "settings:skills", json("skills" to 3))
        assertEquals("the next sign-in opens the account again", 1, cache.latest(default).size)
    }

    @Test fun anOpenThatIsReadingWhenTheOwnerSignsOutDoesNotBringTheRowBack() = runBlocking<Unit> {
        rows[default.copy(profile = "", session = "").key] = """{"v":1,"e":[{"p":"default","k":"crons","r":"/api/crons","a":1,"d":{"jobs":1}}]}"""
        val reading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // The stored row is captured before the read suspends, like a key-store read that finishes after the sign-out.
        val cache = ScreenCache(read = { scope -> val stored = rows[scope.key]; reading.complete(Unit); release.await(); stored }, write = { scope, value -> rows[scope.key] = value }, drop = { rows.remove(it.key) }, jobs = jobs, quietMs = 600_000)
        val opening = launch { cache.open(default) }
        reading.await()
        val signOut = launch { cache.clear(default) }
        yield()
        release.complete(Unit)
        joinAll(opening, signOut)
        assertTrue("the signed-out account's screens are not readable again", cache.latest(default).isEmpty())
        cache.set(default, "settings:memory", json("notes" to 1))
        cache.flush(default)
        assertTrue("a late settings write is refused and nothing reaches the key store", cache.latest(default).isEmpty() && rows.isEmpty())
    }

    @Test fun anOpenThatWaitedBehindTheSignOutLeavesTheAccountClosed() = runBlocking<Unit> {
        val stranger = default.copy(owner = "someone-else").copy(profile = "", session = "")
        val dropping = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val cache = ScreenCache(read = { rows[it.key] }, write = { scope, value -> rows[scope.key] = value }, drop = { scope -> if (scope.key == stranger.key) { dropping.complete(Unit); release.await() }; rows.remove(scope.key) }, jobs = jobs, quietMs = 600_000)
        val busy = launch { cache.clear(stranger) }
        dropping.await()
        val signOut = launch { cache.clear(default) }
        yield()
        // This open began before the sign-out ran but gets the row's lock after it.
        val hydrating = launch { cache.open(default) }
        yield()
        release.complete(Unit)
        joinAll(busy, signOut, hydrating)
        cache.set(default, "settings:memory", json("notes" to 1))
        assertTrue("the account stays closed until the next sign-in opens it", cache.latest(default).isEmpty())
        cache.open(default)
        cache.set(default, "settings:memory", json("notes" to 2))
        assertEquals(1, cache.latest(default).size)
    }

    @Test fun aMutationDropsTheCachedReadsOfItsOwnFamilyOnly() = runBlocking<Unit> {
        val cache = newCache().apply { open(default) }
        cache.put(default, "crons", "/api/crons", at(1), json("jobs" to 1))
        cache.put(default, "cron-history:j", "/api/crons/history&job_id=j", at(1), json("runs" to 1))
        cache.put(default, "files", "/api/pc/files&path=.", at(1), json("entries" to 1))
        cache.put(other, "crons", "/api/crons", at(1), json("jobs" to 2))
        cache.invalidate(default, "/api/crons/create")
        assertNull(cache.exact(default, "crons", "/api/crons"))
        assertNull(cache.exact(default, "cron-history:j", "/api/crons/history&job_id=j"))
        assertNotNull(cache.exact(default, "files", "/api/pc/files&path=."))
        assertNotNull("another profile keeps its own answers", cache.exact(other, "crons", "/api/crons"))
        cache.flush(default)
        assertNull(newCache().apply { open(default) }.exact(default, "crons", "/api/crons"))
    }

    @Test fun anUnreadableRowIsTheSameAsNoRow() = runBlocking<Unit> {
        rows[default.copy(profile = "", session = "").key] = "not json"
        val cache = newCache().apply { open(default) }
        assertTrue(cache.latest(default).isEmpty())
        cache.put(default, "crons", "/api/crons", at(1), json("jobs" to 1))
        assertNotNull(cache.exact(default, "crons", "/api/crons"))
    }

    @Test fun onlyLocalConfigurationReadsOfTheScreenAreCacheable() {
        assertTrue(screenReadCacheable("/api/crons", emptyMap(), "p"))
        assertTrue(screenReadCacheable("/api/tools/toolsets", mapOf("profile" to "p"), "p"))
        assertFalse("another profile's read is not this profile's screen", screenReadCacheable("/api/tools/toolsets", mapOf("profile" to "q"), "p"))
        assertTrue(screenReadCacheable("/api/pc/files", mapOf("path" to "/home"), "p"))
        assertTrue(screenReadCacheable("/api/crons/history", mapOf("job_id" to "j"), "p"))
        assertFalse("chat-scoped reads stay live", screenReadCacheable("/api/crons/history", mapOf("session_id" to "s"), "p"))
        assertFalse(screenReadCacheable("/api/session/usage", mapOf("session_id" to "s"), "p"))
        assertFalse("MCP server configs can hold secrets", screenReadCacheable("/api/mcp/servers", emptyMap(), "p"))
        // The one chat-keyed read is a chat's project (no messages); every other chat-scoped read stays live.
        assertTrue(screenReadCacheable("/api/projects/for-session", mapOf("chat" to "c"), "p"))
        assertFalse(screenReadCacheable("/api/crons/history", mapOf("chat" to "c"), "p"))
        assertFalse(screenReadPaths.filterNot { it == "/api/projects/for-session" }.any { "pair" in it || "token" in it || "auth" in it || "session" in it || "mcp" in it })
        assertEquals("/api/pc/files&path=.", screenRequest("/api/pc/files", mapOf("profile" to "p", "path" to ".")))
        assertEquals(screenRequest("/api/tools/toolsets", emptyMap()), screenRequest("/api/tools/toolsets", mapOf("profile" to "p")))
    }

    @Test fun deviceAndBoardSnapshotsRoundTripWithoutCredentials() {
        val devices = listOf(PairedDevice("d1", "Pixel", "yesterday"), PairedDevice("d2", "Fold", ""))
        assertEquals(devices, pairedDevicesOf(pairedDevicesJson(devices)))
        assertNull(pairedDevicesOf(null))
        assertNull("a loading marker is not a device list", pairedDevicesOf(json("_loading" to true)))
        assertFalse(hasSecretKey(pairedDevicesJson(devices)))
        val boards = json("boards" to JSONArray().put(json("slug" to "default", "name" to "Main")), "current" to "default")
        val (choices, current) = nativeBoardChoices(boards)
        assertEquals(listOf(NativeBoardChoice("default", "Main")), choices)
        assertEquals("default", current)
    }

    @Test fun readsAndTheirMutationsShareAFamily() {
        assertEquals(readFamily("/api/crons"), readFamily("/api/crons/create"))
        assertEquals(readFamily("/api/crons"), readFamily("/api/crons/history&job_id=j"))
        assertEquals(readFamily("/api/profiles"), readFamily("/api/profile/create"))
        assertEquals(readFamily("/api/profile/approval-mode"), readFamily("/api/profile/switch"))
        assertEquals(readFamily("/api/pc/files"), readFamily("/api/pc/file/create"))
        assertEquals(readFamily("/api/plugins/beacon/mobile/tasks/capabilities"), readFamily("/api/plugins/beacon/mobile/tasks/checkups"))
        assertFalse(readFamily("/api/crons") == readFamily("/api/projects"))
        assertFalse(readFamily("/api/plugins/beacon/mobile/tasks/capabilities") == readFamily("/api/plugins/beacon/mobile/history-retention"))
    }
}
