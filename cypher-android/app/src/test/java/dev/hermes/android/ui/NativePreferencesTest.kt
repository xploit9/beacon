package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePreferencesTest {
    @Test fun assistantNameReplacesBeaconInAssistantPhrases() {
        assertEquals("Nova's conversation", assistantName("Beacon's conversation", "Nova"))
        assertEquals("Nova plugin", assistantName("Beacon plugin", "Nova"))
    }
}
