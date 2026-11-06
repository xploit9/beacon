package dev.hermes.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class DeviceRemovalAuthorityTest {
    private val scope = Scope("https://native.example", "alice", "default", nativeProvider = "basic")

    /** Exercises the production AuthStore guard policy with synthetic admissions, without Android keystore stubs. */
    private class Admission {
        val lock = Any()
        val epoch = CredentialEpoch()
        var id = "first-admission"
        fun capture(scope: Scope): DeviceRemovalAuthority = synchronized(lock) {
            val version = epoch.readVersion()
            val captured = id
            DeviceRemovalAuthority(scope) { remove ->
                synchronized(lock) { applyDeviceRemovalIfCurrent(epoch, version, captured, { id }, remove) }
            }
        }
        fun write(next: String) = epoch.mutate { synchronized(lock) { id = next } }
    }

    @Test fun sameAccountReadmissionAndFreshSignInCannotEraseNewPairing() {
        for (mode in listOf("switch", "aba", "fresh-sign-in")) {
            val auth = Admission()
            val removal = auth.capture(scope)
            var paired = "old-phone"
            if (mode != "fresh-sign-in") auth.write("other-account")
            if (mode != "switch") auth.write("new-admission-for-alice")
            paired = "new-phone"
            assertFalse(mode, removal.ifCurrent { paired = "" })
            assertEquals(mode, "new-phone", paired)
        }
    }

    @Test fun aProfileSnapshotChangingAdmissionIsRejectedEvenWithoutAnEpochChange() {
        val auth = Admission()
        val removal = auth.capture(scope)
        val version = auth.epoch.readVersion()
        synchronized(auth.lock) { auth.id = "work-profile"; auth.id = "new-default-profile-admission" }
        assertEquals(version, auth.epoch.readVersion())
        assertFalse(removal.ifCurrent { error("old UUID authorized removal") })
    }

    @Test fun unknownOrUnstableStoreAuthorityCannotRemoveAnything() {
        val auth = Admission()
        val version = auth.epoch.readVersion()
        assertFalse(applyDeviceRemovalIfCurrent(auth.epoch, null, auth.id, { auth.id }) { error("unknown epoch") })
        assertFalse(applyDeviceRemovalIfCurrent(auth.epoch, version, null, { auth.id }) { error("unknown admission") })
        assertFalse(applyDeviceRemovalIfCurrent(auth.epoch, version, auth.id, { null }) { error("unreadable admission") })
        auth.epoch.mutate {
            assertFalse(applyDeviceRemovalIfCurrent(auth.epoch, version, auth.id, { auth.id }) { error("write in progress") })
        }
    }

    @Test fun aWriterWaitingForTheAuthMonitorInvalidatesAnOldRemoval() {
        val auth = Admission()
        val removal = auth.capture(scope)
        val waiting = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        lateinit var writer: Thread
        synchronized(auth.lock) {
            writer = thread(isDaemon = true) {
                try { auth.epoch.mutate { waiting.countDown(); synchronized(auth.lock) { auth.id = "new-admission" } } }
                catch (error: Throwable) { failure.set(error) }
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            assertFalse(removal.ifCurrent { error("pending sign-in must invalidate cleanup") })
        }
        writer.join(5_000)
        assertFalse("writer completed", writer.isAlive)
        assertNull(failure.get())
    }

    @Test fun pushLockBeforeAuthStoreAllowsConcurrentAdmissionWithoutDeadlock() {
        val auth = Admission()
        val removal = auth.capture(scope)
        val pushLock = Any()
        val pushHeld = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()
        val removed = AtomicInteger()
        val previousRemoved = NativeDeviceRemoval.onRemoved
        val previousForget = NativeDeviceRemoval.onForgetScreens
        val writer = thread(isDaemon = true) {
            try { synchronized(pushLock) {
                pushHeld.countDown()
                check(callbackEntered.await(5, TimeUnit.SECONDS))
                auth.write("new-admission")
            } } catch (error: Throwable) { failure.set(error) }
        }
        try {
            assertTrue(pushHeld.await(5, TimeUnit.SECONDS))
            NativeDeviceRemoval.onRemoved = { captured ->
                assertFalse("no auth monitor may span a hook", Thread.holdsLock(auth.lock))
                callbackEntered.countDown()
                synchronized(pushLock) { captured.ifCurrent { removed.incrementAndGet() } }
            }
            NativeDeviceRemoval.onForgetScreens = null
            val dispatch = thread(isDaemon = true) {
                try { NativeDeviceRemoval.removed(removal) } catch (error: Throwable) { failure.set(error) }
                finally { completed.countDown() }
            }
            assertTrue("push/auth lock inversion deadlocked", completed.await(5, TimeUnit.SECONDS))
            dispatch.join(5_000); writer.join(5_000)
            assertFalse(writer.isAlive)
            assertNull(failure.get())
            assertEquals("new pairing survives the waiting old response", 0, removed.get())
        } finally {
            callbackEntered.countDown()
            NativeDeviceRemoval.onRemoved = previousRemoved
            NativeDeviceRemoval.onForgetScreens = previousForget
        }
    }

    @Test fun screenClearChecksAdmissionAfterWaitingForItsOwnIoLock() = runBlocking {
        val auth = Admission()
        val removal = auth.capture(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = CoroutineScope(SupervisorJob())
        val drops = AtomicInteger()
        val cache = ScreenCache(read = { null }, write = { _, _ -> entered.complete(Unit); release.await() }, drop = { drops.incrementAndGet() }, jobs = jobs, quietMs = 60_000)
        try {
            cache.open(scope); cache.set(scope, "devices", json("value" to "old"))
            val flush = async(start = CoroutineStart.UNDISPATCHED) { cache.flush(scope) }
            withTimeout(5_000) { entered.await() }
            val clear = async(start = CoroutineStart.UNDISPATCHED) {
                cache.clear(removal) { removal.ifCurrent { drops.incrementAndGet() } }
            }
            auth.write("other-account"); auth.write("new-alice-admission")
            cache.set(scope, "devices", json("value" to "fresh"))
            release.complete(Unit)
            withTimeout(5_000) { flush.await(); clear.await() }
            assertEquals("fresh", cache.exact(scope, "devices")?.value?.getString("value"))
            assertEquals(0, drops.get())
        } finally { release.complete(Unit); jobs.cancel() }
    }

    @Test fun deferredDiskDropRechecksAdmissionAfterMemoryWasCleared() = runBlocking {
        val auth = Admission()
        val removal = auth.capture(scope)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val jobs = CoroutineScope(SupervisorJob())
        var disk: String? = "old-row"
        val cache = ScreenCache(read = { null }, write = { _, value -> disk = value }, drop = { disk = null }, jobs = jobs, quietMs = 60_000)
        try {
            cache.open(scope); cache.set(scope, "devices", json("value" to "old"))
            val clear = async(start = CoroutineStart.UNDISPATCHED) { cache.clear(removal) {
                entered.complete(Unit); release.await()
                removal.ifCurrent { disk = null }
            } }
            withTimeout(5_000) { entered.await() }
            auth.write("fresh-same-account-sign-in")
            disk = "fresh-row"
            release.complete(Unit)
            withTimeout(5_000) { clear.await() }
            assertEquals("fresh-row", disk)
        } finally { release.complete(Unit); jobs.cancel() }
    }

    @Test fun currentRemovalClearsTheAccountMemoryAndDiskOnce() = runBlocking {
        val auth = Admission()
        val removal = auth.capture(scope)
        val jobs = CoroutineScope(SupervisorJob())
        var disk: String? = "old-row"
        val drops = AtomicInteger()
        val cache = ScreenCache(read = { null }, write = { _, value -> disk = value }, drop = { disk = null }, jobs = jobs, quietMs = 60_000)
        try {
            cache.open(scope); cache.set(scope, "devices", json("value" to "old"))
            cache.set(scope.copy(profile = "work"), "devices", json("value" to "work"))
            cache.clear(removal) { account ->
                assertEquals(scope.copy(profile = "", session = ""), account)
                assertTrue(removal.ifCurrent { drops.incrementAndGet(); disk = null })
            }
            assertNull(cache.exact(scope, "devices"))
            assertNull(cache.exact(scope.copy(profile = "work"), "devices"))
            assertNull(disk)
            assertEquals(1, drops.get())
        } finally { jobs.cancel() }
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test fun screenFlushCancellationCallbacksRunOutsideTheAuthMonitor() = runBlocking {
        val auth = Admission()
        val jobs = CoroutineScope(SupervisorJob())
        val cache = ScreenCache(read = { null }, write = { _, _ -> }, drop = {}, jobs = jobs, quietMs = 60_000)
        val canceledUnderAuth = CompletableDeferred<Boolean>()
        try {
            cache.open(scope); cache.set(scope, "devices", json("value" to "old"))
            val field = ScreenCache::class.java.getDeclaredField("flushes").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST") val flushes = field.get(cache) as Map<String, Job>
            val pending = synchronized(cache) { requireNotNull(flushes[scope.copy(profile = "", session = "").key]) }
            pending.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { canceledUnderAuth.complete(Thread.holdsLock(auth.lock)) }
            cache.clear(auth.capture(scope)) { }
            assertFalse("cancellation callback must not hold the auth monitor", withTimeout(5_000) { canceledUnderAuth.await() })
        } finally { jobs.cancel() }
    }

    @Test fun admissionChangesBetweenCallbacksCannotForgetNewScreens() = runBlocking {
        for (aba in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val forgotten = AtomicInteger()
            val oldRemoved = NativeDeviceRemoval.onRemoved
            val oldForget = NativeDeviceRemoval.onForgetScreens
            NativeDeviceRemoval.onRemoved = {
                entered.countDown()
                assertTrue("release callback barrier", release.await(5, TimeUnit.SECONDS))
            }
            NativeDeviceRemoval.onForgetScreens = { forgotten.incrementAndGet() }
            val auth = AuthContext("https://native.example", emptyList(), "alice", backend = "hermes-dashboard", nativeProvider = "basic")
            val client = BeaconClient(auth, null, persistContext = false) { builder ->
                builder.addInterceptor { chain ->
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Fixture")
                        .body("""{"code":"device_removed"}""".toResponseBody("application/json".toMediaType())).build()
                }
            }
            val reply = async(Dispatchers.IO) { runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health") } }
            try {
                assertTrue("response reached callback", entered.await(5, TimeUnit.SECONDS))
                client.bindProfile("work")
                if (aba) client.bindProfile("default")
                release.countDown()
                assertEquals(403, (withTimeout(5_000) { reply.await() }.exceptionOrNull() as ApiFailure).status)
                assertEquals("old removal must not clear newly admitted screens (aba=$aba)", 0, forgotten.get())
            } finally {
                release.countDown(); reply.cancel(); client.closeNative()
                NativeDeviceRemoval.onRemoved = oldRemoved
                NativeDeviceRemoval.onForgetScreens = oldForget
            }
        }
    }
}
