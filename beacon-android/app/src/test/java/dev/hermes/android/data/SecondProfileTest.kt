package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.newChatAction
import org.junit.Assert.*
import org.junit.Test

/**
 * A secondary profile can only be served by Beacon once its own config lists the plugin (Hermes `web_server_dashboard.py` `_plugin_api_mount_skip_reason`,
 * read through the plugin's `canonical_transport.native_policy`). Until then Hermes answers the ticket request 403 "Plugin not authorized" and no socket
 * ever opens. The client turns Beacon on by itself and asks again (ProfileTurnOnTest); when that does not work the app says so in one plain line with its
 * button, not "Conversation opening failed or was not confirmed. Retry" and "Couldn't load models".
 */
class SecondProfileTest {
    private val line = "Couldn't turn on Beacon for this profile."
    private val refusal = ApiFailure(403, "Plugin not authorized", true)

    @Test fun theLineIsAFixedErrorString() {
        assertTrue(line in ErrorStrings)
        assertEquals(line, apiFailureText(403, "Plugin not authorized"))
    }

    @Test fun onlyThatExactRefusalOnThePluginRouteIsRead() {
        assertTrue(beaconOffForProfile(refusal))
        assertTrue(beaconOffForProfile(java.io.IOException("Native connection admission ended", refusal)))
        assertFalse(beaconOffForProfile(ApiFailure(403, "Owner not authorized", true)))
        assertFalse(beaconOffForProfile(ApiFailure(403, "Profile not authorized", true)))
        assertFalse(beaconOffForProfile(ApiFailure(403, "Plugin not authorized", false)))
        assertFalse(beaconOffForProfile(ApiFailure(404, "Plugin not authorized", true)))
        assertFalse(beaconOffForProfile(ApiFailure(503, "Canonical gateway unavailable", true)))
        assertFalse(beaconOffForProfile(java.io.IOException("Native connection closed (1013)")))
    }

    @Test fun theModelPickerSaysWhyInsteadOfCouldntLoadModels() {
        assertEquals(line, profileDefaultsAfterFailure(null, "scope", "work", "", BeaconOffFailure(ApiFailure(504, "slow", true))).string("_error"))
        assertEquals("Beacon isn't turned on for this profile.", profileDefaultsAfterFailure(null, "scope", "work", "", BeaconOffFailure(null)).string("_error"))
        assertEquals("Couldn't load models", profileDefaultsAfterFailure(null, "scope", "work", "", java.io.IOException("down")).string("_error"))
        assertEquals("Couldn't load models", profileDefaultsAfterFailure(null, "scope", "work", "").string("_error"))
        // The last good answer for this account and profile still wins.
        val good = json("profile" to "work", "scope_key" to "scope", "model" to "m", "reasoning_effort" to "low", "session_id" to "")
        assertEquals("m", profileDefaultsAfterFailure(good, "scope", "work", "", refusal).string("model"))
    }

    @Test fun theFirstChatCardOffersTheLinesOwnButton() {
        assertEquals("", newChatAction("", false))
        assertEquals("", newChatAction(line, true))
        assertEquals("Retry", newChatAction(line, false))
        assertEquals("Turn on", newChatAction("Beacon isn't turned on for this profile.", false))
        assertEquals("Retry", newChatAction("Couldn't open the chat. Your draft is kept.", false))
    }
}
