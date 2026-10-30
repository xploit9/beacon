package dev.hermes.android.ui

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hermes.android.data.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class NativePreferencesTest {
    @Test fun assistantNameReplacesBeaconInAssistantPhrases() {
        assertEquals("Nova's conversation", assistantName("Beacon's conversation", "Nova"))
        assertEquals("Nova plugin", assistantName("Beacon plugin", "Nova"))
        assertEquals("While Nova is replying", assistantName("While Beacon is replying", "Nova"))
    }
    @Test fun busySendDefaultsToSteerAndRestoresOnlyKnownChoices() {
        val key = stringPreferencesKey("busy_send_action")
        assertEquals(false, AppPreferences().busySendLoaded)
        assertEquals(true, AppPreferences.read(emptyPreferences()).busySendLoaded)
        assertEquals("steer", AppPreferences.read(emptyPreferences()).busySendAction)
        assertEquals("queue", AppPreferences.read(mutablePreferencesOf(key to "queue")).busySendAction)
        assertEquals("steer", AppPreferences.read(mutablePreferencesOf(key to "invalid")).busySendAction)
    }
}
