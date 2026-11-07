package dev.hermes.android

import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CacheInvalidationTest {
    private val scope = Scope("https://cache.example", "owner", "default", nativeProvider = "fixture")
    private fun cache(jobs: CoroutineScope) = ScreenCache({ null }, { _, _ -> }, { _ -> }, jobs, quietMs = 600_000)

    @Test fun responseStartedBeforeMutationCannotRepopulateTheInvalidatedFamily() = runBlocking {
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = cache(jobs).apply { open(scope) }
            val before = cache.readVersion(scope, "/api/crons")
            val other = cache.readVersion(scope, "/api/models")
            cache.invalidate(scope, "/api/crons/create")
            cache.put(scope, "crons", "/api/crons", 3_000, json("jobs" to "old answer arriving late"), before)
            assertNull(cache.exact(scope, "crons", "/api/crons"))
            assertFalse(cache.readCurrent(scope, "/api/crons", before))
            assertTrue("Unrelated catalog reads remain valid", cache.readCurrent(scope, "/api/models", other))
            val fresh = cache.readVersion(scope, "/api/crons")
            cache.put(scope, "crons", "/api/crons", 4_000, json("jobs" to "after mutation"), fresh)
            assertEquals("after mutation", cache.exact(scope, "crons", "/api/crons")?.value?.string("jobs"))
            cache.clear(scope)
            cache.open(scope)
            cache.put(scope, "crons", "/api/crons", 5_000, json("jobs" to "retired account"), fresh)
            assertNull("Reopening the same account does not revive its old read lease", cache.exact(scope, "crons", "/api/crons"))
        } finally { jobs.cancel() }
    }

    @Test fun normalizedAliasesShareOneReadAndOneCancelledScreenCannotCancelAnother() = runBlocking {
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val count = AtomicInteger()
        try {
            val cache = cache(jobs)
            val implicit = screenReadIdentity(scope, "/api/tools/toolsets", emptyMap(), 2)
            val explicit = screenReadIdentity(scope, "/api/tools/toolsets", mapOf("profile" to scope.profile), 2)
            assertEquals(implicit, explicit)
            suspend fun read(): JSONObject { count.incrementAndGet(); started.complete(Unit); release.await(); return json("toolsets" to "current") }
            val first = async { cache.fetch(scope, implicit) { read() } }
            started.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) { cache.fetch(scope, explicit) { read() } }
            first.cancelAndJoin()
            assertEquals(1, count.get())
            release.complete(Unit)
            assertEquals("current", second.await().string("toolsets"))
            assertEquals(1, count.get())
        } finally { jobs.cancel() }
    }

    @Test fun lastConsumerCancellationAndAccountClearCloseTheirExactReads() = runBlocking {
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = cache(jobs)
            val started = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>()
            var monitorHeldOnLastRelease = true
            val read = async { cache.fetch(scope, "one read") {
                try { started.complete(Unit); awaitCancellation() } finally { monitorHeldOnLastRelease = Thread.holdsLock(cache); stopped.complete(Unit) }
            } }
            started.await(); read.cancelAndJoin(); withTimeout(1_000) { stopped.await() }
            assertFalse(monitorHeldOnLastRelease)
            val startedAgain = CompletableDeferred<Unit>(); val stoppedAgain = CompletableDeferred<Unit>()
            val next = async { cache.fetch(scope, "one read") {
                try { startedAgain.complete(Unit); awaitCancellation() } finally { stoppedAgain.complete(Unit) }
            } }
            startedAgain.await(); cache.clear(scope)
            withTimeout(1_000) { stoppedAgain.await() }; next.join()
            assertTrue(next.isCancelled)
        } finally { jobs.cancel() }
    }

    @Test fun clearInvokesSynchronousCancellationOutsideOwnerGuardAndCacheMonitor() = runBlocking {
        val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val cache = cache(jobs).apply { open(scope) }
            val started = CompletableDeferred<Unit>()
            var ownerGuardHeld = false
            val clearingThread = Thread.currentThread()
            val observations = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
            val reader = async(start = CoroutineStart.UNDISPATCHED) {
                cache.fetch(scope, "guarded-clear") {
                    suspendCancellableCoroutine<JSONObject> { continuation ->
                        continuation.invokeOnCancellation {
                            observations += Triple(ownerGuardHeld, Thread.holdsLock(cache), Thread.currentThread() === clearingThread)
                        }
                        started.complete(Unit)
                    }
                }
            }
            started.await()
            cache.clear(scope, removeIfCurrent = { remove ->
                ownerGuardHeld = true
                try { remove(); true } finally { ownerGuardHeld = false }
            }, dropCurrent = {
                assertEquals("Cancellation handlers already ran synchronously before the disk drop", 1, observations.size)
            })
            reader.join()
            assertTrue(reader.isCancelled)
            assertEquals(listOf(Triple(false, false, true)), observations)
        } finally { jobs.cancel() }
    }

    @Test fun requestIdentitySeparatesScopesGenerationsAndUnescapedPathComponents() {
        val original = screenReadIdentity(scope, "/api/pc/files", mapOf("path" to "x&job_id=y"), 2)
        assertNotEquals(original, screenReadIdentity(scope, "/api/pc/files", mapOf("path" to "x", "job_id" to "y"), 2))
        assertNotEquals(original, screenReadIdentity(scope.copy(owner = "other"), "/api/pc/files", mapOf("path" to "x&job_id=y"), 2))
        assertNotEquals(original, screenReadIdentity(scope, "/api/pc/files", mapOf("path" to "x&job_id=y"), 3))
        assertNotEquals(screenReadIdentity(scope, "/api/profiles", emptyMap(), 2), screenReadIdentity(scope, "/api/profiles", mapOf("profile" to "other"), 2))
    }

    @Test fun approvalDisplayIsShortLivedAndNeverEligibleForDiskCaching() {
        assertTrue(screenDisplayFresh("/api/approval/mode", 1_000, 10_999))
        assertFalse(screenDisplayFresh("/api/approval/mode", 1_000, 11_000))
        assertFalse(screenDisplayFresh("/api/approval/mode", 1_000, 1_500, invalidatedAt = 1_001))
        assertFalse(screenDisplayFresh("/api/approval/mode", 2_000, 1_999))
        assertTrue(screenDisplayFresh("/api/models", 1_000, 120_999))
        assertFalse(screenReadCacheable("/api/approval/mode", mapOf("session_id" to "chat"), scope.profile))
        assertFalse(screenReadCacheable("/api/approval/respond", emptyMap(), scope.profile))
    }
}
