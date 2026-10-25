package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPreloadTest {
    @Test fun prefetchIsBoundedLowPriorityAndNeverTouchesChatScopedReads() {
        assertEquals(2, PREFETCH_PARALLELISM)
        assertEquals(prefetchReads.size, prefetchReads.map { it.key }.distinct().size)
        assertTrue("startup reads are warmed separately", prefetchReads.none { read -> startupReads.any { it.path == read.path } })
        assertTrue(prefetchReads.all { it.path in screenReadPaths || it.path == "/api/mcp/servers" })
        assertTrue(prefetchReads.none { read -> read.query.keys.any { it == "session_id" || it == "chat" } })
        assertTrue("the warm-up waits for the chat before starting", PREFETCH_DELAY > 0 && PREFETCH_READY_WAIT > PREFETCH_DELAY)
        assertTrue("a return to the foreground re-warms after a while, not every time", PREFETCH_REWARM_AFTER > STARTUP_READ_TTL)
    }
    @Test fun recentHistoryIsBoundedAndDoesNotRepeatBeacon() {
        assertEquals(listOf("first", "second", "third"), startupRecentSessions(listOf("", "beacon", "first", "first", "second", "third"), "beacon"))
        assertEquals(emptyList<String>(), startupRecentSessions(listOf("", "beacon"), "beacon"))
    }
}
