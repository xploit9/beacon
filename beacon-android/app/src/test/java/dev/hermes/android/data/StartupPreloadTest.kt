package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPreloadTest {
    @Test fun recentHistoryIsBoundedAndDoesNotRepeatBeacon() {
        assertEquals(listOf("first", "second", "third"), startupRecentSessions(listOf("", "beacon", "first", "first", "second", "third"), "beacon"))
        assertEquals(emptyList<String>(), startupRecentSessions(listOf("", "beacon"), "beacon"))
    }
}
