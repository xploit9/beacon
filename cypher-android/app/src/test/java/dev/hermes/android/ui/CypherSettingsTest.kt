package dev.hermes.android.ui

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CypherSettingsTest {
    @Test fun voiceLabelsCapitalizeDisplayNamesWithoutChangingStoredValues() {
        assertEquals("Eve", voiceChoiceLabel("eve"))
        assertEquals("Aoede", voiceChoiceLabel("Aoede"))
        assertEquals("", voiceChoiceLabel(""))
    }

    @Test fun missingCypherPluginRoutesAreDistinguishedFromOtherFailures() {
        assertTrue(dev.hermes.android.isMissingCypherPluginRoute(ApiFailure(404, "Not Found", plugin = true)))
        assertTrue(dev.hermes.android.isMissingCypherPluginRoute(ApiFailure(400, "Unknown route", plugin = true)))
        assertFalse(dev.hermes.android.isMissingCypherPluginRoute(ApiFailure(404, "Not Found", plugin = false)))
        assertFalse(dev.hermes.android.isMissingCypherPluginRoute(ApiFailure(503, "Service unavailable", plugin = true)))
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
}
