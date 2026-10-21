package dev.hermes.android.voice

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test

class CallAdmissionTest {
    @Test fun delayedAdmissionAfterHangupNeverAllocates() = runBlocking {
        val lock=Mutex(); val closed=AtomicBoolean(false); val admitted=CompletableDeferred<Unit>()
        var allocations=0
        val activation=launch { admitted.await();admitCallTransport(lock,closed,{true}) { allocations++ } }
        yield();closed.set(true);admitted.complete(Unit);activation.join()
        assertEquals(0,allocations)
    }
    @Test fun queuedInitializerCancelledByHangupNeverAllocates() = runBlocking {
        val lock=Mutex(locked=true);val closed=AtomicBoolean(false);var allocations=0
        val initializer=launch { admitCallTransport(lock,closed,{true}) { allocations++ } }
        yield();closed.set(true);initializer.cancel();lock.unlock();initializer.join()
        assertEquals(0,allocations)
    }
    @Test fun teardownWaitsForAssignedResourcesAndNoLaterAdmissionCanReopen() = runBlocking {
        val lock=Mutex();val closed=AtomicBoolean(false);var allocated=false;var released=false
        admitCallTransport(lock,closed,{true}) { allocated=true }
        assertTrue(allocated)
        closed.set(true)
        lock.withLock { released=allocated;allocated=false }
        admitCallTransport(lock,closed,{true}) { allocated=true }
        assertTrue(released);assertFalse(allocated)
    }
    @Test fun hangupDuringAllocationWaitsForInitializerBeforeClosingItsResource() = runBlocking {
        val lock=Mutex();val closed=AtomicBoolean(false)
        val started=CompletableDeferred<Unit>()
        val unblock=java.util.concurrent.CountDownLatch(1)
        var allocated=false;var released=false
        val initializer=launch(Dispatchers.Default) {
            admitCallTransport(lock,closed,{true}) {
                started.complete(Unit)
                check(unblock.await(5,java.util.concurrent.TimeUnit.SECONDS))
                allocated=true
            }
        }
        try {
            withTimeout(5000) { started.await() }
            closed.set(true);initializer.cancel()
            val teardown=launch { lock.withLock { released=allocated;allocated=false } }
            yield();assertFalse("Cleanup cannot miss an in-flight allocation",released)
            unblock.countDown();initializer.join();teardown.join()
            assertTrue(released);assertFalse(allocated)
            admitCallTransport(lock,closed,{true}) { allocated=true }
            assertFalse(allocated)
        } finally { unblock.countDown();initializer.cancelAndJoin() }
    }
    @Test fun staleServiceOwnerCannotAllocateButCurrentOwnerCan() = runBlocking {
        val lock=Mutex();val closed=AtomicBoolean(false);var allocations=0
        admitCallTransport(lock,closed,{false}) { allocations++ }
        assertEquals(0,allocations)
        admitCallTransport(lock,closed,{true}) { allocations++ }
        assertEquals(1,allocations)
    }
}
