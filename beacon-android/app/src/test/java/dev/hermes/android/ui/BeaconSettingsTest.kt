package dev.hermes.android.ui

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.ownerNameOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconSettingsTest {
    @Test fun providerKeyRejectionUsesTheShortKeySpecificMessage() {
        assertEquals("That key wasn't accepted. Check it and try again.",
            voiceProviderTestMessage(dev.hermes.android.voice.VoiceProviderTestResult.KeyRejected))
        assertEquals("The provider responded with an error. Check the selected model or try again.",
            voiceProviderTestMessage(dev.hermes.android.voice.VoiceProviderTestResult.ProviderError))
    }

    @Test fun ownerNameIsTrimmedBoundedAndContainsOnlyAllowedNameCharacters() {
        assertEquals("Maya O'Neil", ownerNameOf(" Maya O'Neil "))
        assertEquals("Maya Smith", ownerNameOf("Maya! Smith2"))
        assertEquals(40, ownerNameOf("A".repeat(45)).length)
        assertEquals("", ownerNameOf("---"))
        assertEquals("", ownerNameOf("!!!"))
    }

    @Test fun voiceLabelsCapitalizeDisplayNamesWithoutChangingStoredValues() {
        val options = listOf("eve", "Aoede", "Aoede", "")
        assertEquals("Eve", voiceChoiceLabel("eve", options))
        assertEquals("Aoede", voiceChoiceLabel("Aoede", options))
        assertEquals("", voiceChoiceLabel("", options))
    }

    @Test fun voiceOptionProducerRemovesOnlyExactDuplicates() {
        assertEquals(
            listOf("aoede", "Aoede", "AOEDE", "eve"),
            voiceOptionsWithSelected(listOf("Aoede", "aoede", "Aoede", "AOEDE", "eve"), "aoede"),
        )
    }

    @Test fun collidingVoiceLabelsIncludeEachExactWireId() {
        val options = listOf("aoede", "Aoede", "AOEDE", "eve")
        assertEquals("Aoede (aoede)", voiceChoiceLabel("aoede", options))
        assertEquals("Aoede (Aoede)", voiceChoiceLabel("Aoede", options))
        assertEquals("AOEDE", voiceChoiceLabel("AOEDE", options))
        assertEquals("Eve", voiceChoiceLabel("eve", options))
    }

    @Test fun savedVoiceOutsideCatalogKeepsItsExactChoiceAndCollisionLabel() {
        val options = voiceOptionsWithSelected(listOf("Aoede", "eve"), "aoede")
        val labels = options.associateWith { voiceChoiceLabel(it, options) }
        assertEquals(listOf("aoede", "Aoede", "eve"), options)
        assertEquals(
            mapOf("aoede" to "Aoede (aoede)", "Aoede" to "Aoede (Aoede)", "eve" to "Eve"),
            labels,
        )
    }

    @Test fun voiceModelIdsUseFriendlyNames() {
        assertEquals("Gemini 3.8 Live", voiceModelDisplayName("models/gemini-3.8-live"))
        assertEquals("GPT Realtime 2.1", voiceModelDisplayName("gpt-realtime-2.1"))
    }

    @Test fun missingBeaconPluginRoutesAreDistinguishedFromOtherFailures() {
        assertTrue(dev.hermes.android.isMissingBeaconPluginRoute(ApiFailure(404, "Not Found", plugin = true)))
        assertTrue(dev.hermes.android.isMissingBeaconPluginRoute(ApiFailure(400, "Unknown route", plugin = true)))
        assertFalse(dev.hermes.android.isMissingBeaconPluginRoute(ApiFailure(404, "Not Found", plugin = false)))
        assertFalse(dev.hermes.android.isMissingBeaconPluginRoute(ApiFailure(503, "Service unavailable", plugin = true)))
    }

    @Test fun unconfiguredPluginOwnerPointsToHermesDashboardWithoutShowingServerText() {
        assertEquals(
            "Finish setup on your Hermes dashboard: open the Beacon tab.",
            dev.hermes.android.beaconOwnerSetupMessage(ApiFailure(403, "Owner not authorized", plugin = true)),
        )
        assertNull(dev.hermes.android.beaconOwnerSetupMessage(ApiFailure(403, "Owner not authorized", plugin = false)))
        assertNull(dev.hermes.android.beaconOwnerSetupMessage(ApiFailure(403, "Forbidden", plugin = true)))
    }

    @Test fun callDeliveryExplainsNotificationFallbackWhenCallbacksAreDisabled() {
        val rule = JSONObject().put("action", "call")
        val proactive = JSONObject().put("rules", JSONArray().put(rule))
        assertTrue(proactiveCallsUnavailable(JSONObject(), "call", false))
        assertTrue(proactiveCallsUnavailable(proactive, "notify", false))
        assertFalse(proactiveCallsUnavailable(proactive, "call", true))
        assertFalse(proactiveCallsUnavailable(JSONObject(), "notify", false))
    }

    @Test fun currentSavedVoiceOptionsStaySelectableWhenNoCatalogIsAvailable() {
        assertEquals(
            listOf("saved-model", "catalog-model"),
            voiceOptionsWithSelected(listOf("catalog-model", "catalog-model"), "saved-model"),
        )
    }

    @Test fun voiceStoreCancellationIsRethrown() {
        val cancellation = CancellationException("settings closed")
        val thrown = runCatching {
            runBlocking { runCatchingCancellable { throw cancellation } }
        }.exceptionOrNull()
        assertSame(cancellation, thrown)
    }

    @Test fun voiceStoreFailureIsReturnedForTheExistingErrorMessage() = runBlocking {
        val failure = IllegalStateException("write failed")
        assertSame(failure, runCatchingCancellable<Unit> { throw failure }.exceptionOrNull())
    }
    @Test fun proactiveNeedsASavedAssistantChatInPlainWords() {
        assertEquals("Send Beacon a message first, then turn this on.",
            proactiveErrorMessage(ApiFailure(409, "Choose an owned assistant conversation before enabling Proactive", plugin = true), "fallback"))
        assertEquals("fallback", proactiveErrorMessage(RuntimeException(""), "fallback"))
    }
    @Test fun callsCountAsOnWhenThePluginSaysSo() {
        val on = dev.hermes.android.data.mergeOptionalFeatures(JSONObject(), JSONObject().put("plugin", "available").put("capabilities", JSONObject().put("callbacks", true)))
        assertTrue(callbacksAvailable(on))
        assertFalse(callbacksAvailable(dev.hermes.android.data.mergeOptionalFeatures(JSONObject(), JSONObject().put("capabilities", JSONObject().put("callbacks", false)))))
    }
}
