package dev.hermes.android.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CypherSettingsTest {
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
