package dev.hermes.android.data

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** CACHEFIX-B: the warm-up policy, the redacted MCP copy, the caps on what stays on the phone, and the in-memory transcripts. */
class CachefixPolicyTest {
    @get:Rule val folder = TemporaryFolder()
    private val jobs = CoroutineScope(SupervisorJob())
    @After fun stop() { jobs.cancel() }
    private val day = 24 * 60 * 60 * 1000L
    private val now = 10 * day

    // P1-3, P2-5

    @Test fun theWarmUpFollowsTheMeteredNetworkDataSaverAndBatterySaver() {
        assertEquals(6, warmChatLimit(WarmConditions()))
        assertEquals("a metered network warms the top few", 3, warmChatLimit(WarmConditions(metered = true)))
        assertEquals("Data Saver warms the top few", 3, warmChatLimit(WarmConditions(dataSaver = true)))
        assertEquals("both warm two", 2, warmChatLimit(WarmConditions(metered = true, dataSaver = true)))
        assertEquals("battery saver warms the minimum, whatever the network", 1, warmChatLimit(WarmConditions(metered = true, dataSaver = true, powerSave = true)))
        assertEquals(1, warmChatLimit(WarmConditions(powerSave = true)))
        assertTrue(warmScreensAllowed(WarmConditions(metered = true, dataSaver = true)))
        assertFalse("battery saver leaves the screens to their own open", warmScreensAllowed(WarmConditions(powerSave = true)))
    }

    @Test fun aSavedTranscriptNewerThanTheRowIsNotReadAgain() {
        assertTrue(transcriptFresh(savedAt = now - 1_000, activityMs = now - 5_000, now = now))
        assertFalse("the chat moved on after the save", transcriptFresh(savedAt = now - 5_000, activityMs = now - 1_000, now = now))
        assertTrue("saved at the same instant counts", transcriptFresh(savedAt = now - 2_000, activityMs = now - 2_000, now = now))
        assertFalse("nothing saved", transcriptFresh(savedAt = 0, activityMs = now - 5_000, now = now))
        assertFalse("a save dated in the future is not trusted", transcriptFresh(savedAt = now + 60_000, activityMs = now - 5_000, now = now))
    }

    @Test fun aSavedTranscriptAboutToExpireCountsAsAbsentSoTheSkipNeverLeavesASkeleton() {
        val activity = now - 3 * day
        assertTrue(transcriptFresh(savedAt = now - 2 * day + 2 * 60 * 60_000L, activityMs = activity, now = now))
        assertFalse("within the hour of its two-day expiry", transcriptFresh(savedAt = now - 2 * day + 10 * 60_000L, activityMs = activity, now = now))
        assertFalse("already expired", transcriptFresh(savedAt = now - 3 * day, activityMs = activity - day, now = now))
    }

    @Test fun aChatWithNoRowActivityIsFreshOnlyBrieflyAfterItWasSaved() {
        assertTrue(transcriptFresh(savedAt = now - 30_000, activityMs = 0, now = now))
        assertFalse(transcriptFresh(savedAt = now - STARTUP_READ_TTL - 1, activityMs = 0, now = now))
    }

    @Test fun onlyTheTopListedChatsAreCandidatesAndTheFreshOnesAreSkipped() {
        val rows = (1..9).map { WarmRow("chat-$it", now - it * 60_000L) } + WarmRow("beacon", now) + WarmRow("", now)
        // chat-1 is saved after its last activity; chat-2 was saved before its last activity (a minute ago).
        val saved = mapOf("chat-1" to now - 10, "chat-2" to now - 3 * 60_000L)
        val targets = startupWarmTargets(rows, "beacon", 3, { saved[it] ?: 0L }, now).map { it.id }
        assertEquals("top three listed, less the one saved after its last activity", listOf("chat-2", "chat-3"), targets)
        assertEquals(listOf("chat-1", "chat-2", "chat-3"), startupWarmCandidates(rows, "beacon", 3).map { it.id })
        assertEquals(6, startupWarmCandidates(rows, "beacon", warmChatLimit(WarmConditions())).size)
        assertTrue("every listed chat saved up to date: nothing is read", startupWarmTargets(rows, "beacon", 6, { now - 1 }, now).isEmpty())
        assertEquals(listOf("chat-1"), startupWarmCandidates(rows + rows, "beacon", 1).map { it.id })
    }

    // P2-1

    private val secret = "sentinel-secret-9f2c"
    private fun mcpAnswer() = json("servers" to JSONArray()
        .put(json("name" to "files", "transport" to "stdio", "command" to "run-$secret", "args" to JSONArray(listOf("--token=$secret")), "env" to json("API_PASSWORD" to "***", "HOME" to secret),
            "url" to "https://example.com/$secret", "headers" to json("X-Note" to secret), "auth" to "header", "enabled" to true, "tools" to JSONArray(listOf("read")), "source" to "config", "plugin" to JSONObject.NULL))
        .put(json("name" to "search", "transport" to "http", "enabled" to false, "source" to "plugin", "plugin" to "web-tools"))
        .put(json("transport" to "stdio", "enabled" to true)))

    @Test fun mcpServersAreKeptAsAnAllowlistNeverAsAStrip() {
        val kept = redactMcpServers(mcpAnswer())
        val rows = kept.getJSONArray("servers")
        assertEquals("a row without a name is dropped", 2, rows.length())
        assertEquals(setOf("name", "transport", "enabled", "source"), rows.getJSONObject(0).keys().asSequence().toSet())
        assertEquals(setOf("name", "transport", "enabled", "source", "plugin"), rows.getJSONObject(1).keys().asSequence().toSet())
        assertFalse(kept.toString().contains(secret))
        assertEquals("redacting twice changes nothing", kept.toString(), redactMcpServers(kept).toString())
    }

    @Test fun theMcpListReachesTheEncodedRowOnlyRedacted() = runBlocking<Unit> {
        val rows = HashMap<String, String>()
        val cache = ScreenCache(read = { rows[it.key] }, write = { scope, value -> rows[scope.key] = value }, drop = { rows.remove(it.key) }, jobs = jobs, quietMs = 600_000)
        val scope = Scope("https://native.example", "owner", "default", nativeProvider = "provider")
        cache.open(scope)
        assertTrue(screenReadCacheable("/api/mcp/servers", mapOf("profile" to "default"), "default"))
        // A caller that hands the raw answer over (as the screen read does) still writes the redacted rows.
        cache.put(scope, "native-mcp", screenRequest("/api/mcp/servers", mapOf("profile" to "default")), 1_000L, mcpAnswer())
        cache.flush(scope)
        val stored = rows.values.single()
        assertFalse("no env, header, arg, command or url value reaches disk", stored.contains(secret))
        assertTrue("the saved row is really there", stored.contains("\"files\"") && stored.contains("web-tools"))
        val reopened = ScreenCache(read = { rows[it.key] }, write = { _, _ -> }, drop = {}, jobs = jobs, quietMs = 600_000).apply { open(scope) }
        assertEquals(2, reopened.exact(scope, "native-mcp", "/api/mcp/servers")!!.value.getJSONArray("servers").length())
    }

    // P3-2

    @Test fun onlyTheOpenChatsKeysSurviveAnotherChatOpening() {
        val data = mapOf("project-of:a" to 1, "project-of:b" to 2, "session-usage:a" to 3, "crons" to 4, "native-mcp" to 5, "project-of:" to 6)
        assertEquals(setOf("project-of:b", "crons", "native-mcp"), withoutOtherChatKeys(data, "b").keys)
        assertEquals("opening no chat drops every chat key", setOf("crons", "native-mcp"), withoutOtherChatKeys(data, "").keys)
        val plain = mapOf("crons" to 1)
        assertTrue("nothing to drop returns the map itself", withoutOtherChatKeys(plain, "a") === plain)
    }

    // P3-3: JOINBFIX-121 took the protected-row cap out (REVIEW-JOINB-121 P1-1): a protected row is never deleted for room. It is proved on a real
    // database in NativeProjectionStorageTest.aSavedDraftHoldingAWaitingMessageIsNeverEvictedByTheProtectedCap.

    // P3-4

    @Test fun chatAttachmentsAreCappedByTotalSizeOldestFirst() {
        val dir = folder.newFolder("attachments")
        val old = File(dir, "old").apply { writeBytes(ByteArray(400)); setLastModified(1_000) }
        val mid = File(dir, "mid").apply { writeBytes(ByteArray(400)); setLastModified(2_000) }
        val fresh = File(dir, "fresh").apply { writeBytes(ByteArray(400)) }
        ChatAttachmentStore.capTotal(dir, keep = fresh, capBytes = 900)
        assertFalse("the oldest goes", old.exists())
        assertTrue(mid.exists() && fresh.exists())
        ChatAttachmentStore.capTotal(dir, keep = fresh, capBytes = 100)
        assertFalse(mid.exists())
        assertTrue("the file being adopted is never the one removed", fresh.exists())
    }

    @Test fun adoptingAFileKeepsTheFolderUnderTheCap() {
        val dir = folder.newFolder("store")
        ChatAttachmentStore.dir = dir
        try {
            fun adopt(id: String, size: Int, age: Long) = ChatAttachmentStore.adopt(folder.newFile("pick-$id").apply { writeBytes(ByteArray(size)) }, id, capBytes = 1_000).also { it.setLastModified(age) }
            adopt("00000000-0000-0000-0000-000000000001", 600, 1_000)
            adopt("00000000-0000-0000-0000-000000000002", 600, 2_000)
            assertEquals(1, dir.listFiles()!!.size)
            assertTrue(File(dir, "00000000-0000-0000-0000-000000000002").exists())
        } finally { ChatAttachmentStore.dir = null }
    }

    // P3-7

    @Test fun theMostRecentTranscriptsStayInMemoryBoundedAndScopedByTheirKey() {
        val recent = RecentTranscripts(maxEntries = 2, maxChars = 10)
        recent.put("a", "aaa"); recent.put("b", "bbb")
        assertEquals("aaa", recent.get("a"))
        recent.put("c", "ccc")
        assertNull("the eldest left", recent.get("b"))
        assertEquals("aaa", recent.get("a"))
        recent.put("a", "x".repeat(11))
        assertNull("an oversized copy replaces nothing and removes the older one", recent.get("a"))
        assertNull("another chat's key never matches", recent.get("a-other"))
        recent.clear()
        assertNull(recent.get("c"))
    }
}
