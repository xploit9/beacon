package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePreferencesTest {
    @Test fun assistantNameReplacesBeaconButLeavesCypherProductNames() {
        assertEquals("Nova's conversation", assistantName("Beacon's conversation", "Nova"))
        assertEquals("Cypher plugin", assistantName("Cypher plugin", "Nova"))
    }
}
