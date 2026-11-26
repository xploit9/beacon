package dev.hermes.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** REVIEW-124B must-fix 1: the light settings warm-up may start beside a reply, never beside a live call. */
class PrefetchCallGateTest {
    @Test(timeout = 15_000) fun aReturnDuringALiveCallStartsNoLightReadAndTheSameReturnAfterItDoes() = runBlocking {
        val callActive = AtomicBoolean(true)
        val reads = AtomicInteger()
        val allRan = CompletableDeferred<Unit>()
        val steps: List<suspend () -> Unit> = List(4) { { if (reads.incrementAndGet() == 4) allRan.complete(Unit) } }
        val job = launch { prefetchLightSteps(steps, current = { true }, callActive = { callActive.get() }, tickMillis = 20) }
        delay(300)
        assertEquals("a live call starts no light read", 0, reads.get())
        callActive.set(false)
        withTimeout(5_000) { allRan.await() }
        assertEquals("the same return warms every light step once the call ends", 4, reads.get())
        job.join()
    }
}
