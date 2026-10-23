package dev.hermes.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SessionProjectionTest {
    @Test fun photoHeavyHistoryCannotSkipLiveRepliesApprovalsOrWatching() = runBlocking {
        val history="x".repeat(17 * 1024 * 1024)
        val steps=mutableListOf("renderSession")
        persistSessionProjection(history) { fail("Oversized history must not reach encryption or storage") }
        steps.addAll(listOf("pollPrompts","reconcile","attachStream","watchSession","finalHistory"))
        assertEquals(listOf("renderSession","pollPrompts","reconcile","attachStream","watchSession","finalHistory"),steps)
    }

    @Test fun encryptionOverheadAndUtf8SizeCountTowardTheCacheLimit() = runBlocking {
        val saved=mutableListOf<Int>()
        val limit=MAX_PROJECTION_BYTES-APP_CIPHER_OVERHEAD
        persistSessionProjection("a".repeat(limit)) { saved.add(it.toByteArray().size) }
        persistSessionProjection("a".repeat(limit+1)) { fail("Ciphertext would exceed the row limit") }
        persistSessionProjection("é".repeat(limit/2+1)) { fail("UTF-8 bytes must be counted") }
        assertEquals(listOf(limit),saved)
    }

    @Test fun failedCacheStorageStillReturnsButParentCancellationPropagates() = runBlocking {
        persistSessionProjection("history") { throw java.io.IOException("Storage full") }
        val cancelled=CancellationException("Owner left the chat")
        val failure=runCatching { persistSessionProjection("history") { throw cancelled } }.exceptionOrNull()
        assertSame(cancelled,failure)
    }
}
