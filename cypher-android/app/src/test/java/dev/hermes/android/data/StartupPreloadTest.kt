package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPreloadTest {
    @Test fun recentHistoryIsBoundedAndDoesNotRepeatCypher() {
        assertEquals(listOf("first", "second", "third"), startupRecentSessions(listOf("", "cypher", "first", "first", "second", "third"), "cypher"))
        assertEquals(emptyList<String>(), startupRecentSessions(listOf("", "cypher"), "cypher"))
    }
}
