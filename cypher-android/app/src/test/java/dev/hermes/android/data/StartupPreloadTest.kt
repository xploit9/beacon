package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPreloadTest {
    @Test fun recentHistoryIsBoundedAndDoesNotRepeatXara() {
        assertEquals(listOf("first", "second", "third"), startupRecentSessions(listOf("", "xara", "first", "first", "second", "third"), "xara"))
        assertEquals(emptyList<String>(), startupRecentSessions(listOf("", "xara"), "xara"))
    }
}
