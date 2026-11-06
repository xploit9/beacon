package dev.hermes.android

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.digest
import dev.hermes.android.data.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic isolated preferences and in-memory Room only; execution belongs to the coordinator. */
class DeviceRemovalAuthorityGuardTest {
    private class Fixture : AutoCloseable {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "removal-fixture-${UUID.randomUUID()}"
        private val names = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val isolated = "$prefix-$name"
                synchronized(names) { names += isolated }
                return base.getSharedPreferences(isolated, mode)
            }
        }
        val cipher = AppCipher()
        val auth = AuthContext("https://native.example", emptyList(), "alice", backend = "hermes-dashboard",
            bearer = NativeBearerRecord("synthetic-access", "synthetic-refresh", 4_000_000_000, "https://native.example", "alice", "default", "basic"))
        val store = AuthStore(context, cipher)
        val transactions = ThreadPoolExecutor(0, 1, 100, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { task ->
            Thread(task, "removal-fixture-db").apply { isDaemon = true }
        }
        val db = Room.inMemoryDatabaseBuilder(context, ProjectionDb::class.java)
            .setTransactionExecutor(transactions).build()
        val cache = ProjectionCache(db, cipher)
        val pairing = context.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
        private val pairingField = NativePairing::class.java.getDeclaredField("store").apply { isAccessible = true }
        private val previousPairing = pairingField.get(NativePairing)
        init { pairingField.set(NativePairing, pairing); store.write(auth); pair("old-phone") }
        fun pair(id: String) { check(pairing.edit().putString(NativePairing.accountKey(auth.scope()), id).commit()) }
        fun admit(mode: String) {
            if (mode != "fresh") store.write(auth.copy(owner = "bob", bearer = auth.bearer?.copy(owner = "bob")))
            if (mode != "switch") store.write(auth)
        }
        fun client(beforeReply: () -> Unit = {}): BeaconClient = BeaconClient(auth, store, persistContext = false, configureHttp = { builder ->
            builder.addInterceptor { chain ->
                beforeReply()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(403).message("Fixture")
                    .body("""{"code":"device_removed"}""".toResponseBody("application/json".toMediaType())).build()
            }
        })
        override fun close() {
            // Room may enqueue transaction cleanup after close; its idle daemon worker retires itself.
            db.close()
            pairingField.set(NativePairing, previousPairing)
            synchronized(names) { names.forEach(base::deleteSharedPreferences) }
        }
    }

    @Test fun realStoredAdmissionRejectsSwitchAbaAndFreshSignInBeforeReply() = runBlocking {
        for (mode in listOf("switch", "aba", "fresh")) Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val removed = AtomicInteger()
            val oldRemoved = NativeDeviceRemoval.onRemoved
            val oldForget = NativeDeviceRemoval.onForgetScreens
            val account = fixture.auth.scope().copy(profile = "", session = "")
            val client = fixture.client { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            NativeDeviceRemoval.onRemoved = { captured -> captured.ifCurrent { removed.incrementAndGet(); NativePairing.clearDeviceId(fixture.context, captured.scope) } }
            NativeDeviceRemoval.onForgetScreens = { captured -> runBlocking { fixture.cache.deleteIfCurrent(account, "screens", captured) }; Unit }
            val reply = async(Dispatchers.IO) { runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health") } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.admit(mode); fixture.pair("fresh-phone"); fixture.cache.write(account, "screens", "fresh-row")
                release.countDown()
                val refusal = withTimeout(5_000) { reply.await() }.exceptionOrNull()
                assertTrue("A stale reply is refused before applying device removal", refusal is CachedIdentityMismatch || refusal is NativeAuthExpiredException || refusal is ApiFailure && refusal.status == 403)
                assertEquals(0, removed.get())
                assertEquals("fresh-phone", NativePairing.deviceId(fixture.context, fixture.auth.scope()))
                assertEquals("fresh-row", fixture.cache.read(account, "screens"))
            } finally {
                release.countDown(); reply.cancel(); client.closeNative()
                NativeDeviceRemoval.onRemoved = oldRemoved; NativeDeviceRemoval.onForgetScreens = oldForget
            }
        }
    }

    @Test fun admissionAfterValidationBeforeTargetMutationKeepsNewPairingAndScreens() = runBlocking {
        for (mode in listOf("aba", "fresh")) Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val oldRemoved = NativeDeviceRemoval.onRemoved
            val oldForget = NativeDeviceRemoval.onForgetScreens
            val account = fixture.auth.scope().copy(profile = "", session = "")
            NativeDeviceRemoval.onRemoved = { captured ->
                assertFalse(Thread.holdsLock(fixture.store))
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                captured.ifCurrent { NativePairing.clearDeviceId(fixture.context, captured.scope) }
            }
            NativeDeviceRemoval.onForgetScreens = { captured -> runBlocking { fixture.cache.deleteIfCurrent(account, "screens", captured) }; Unit }
            val client = fixture.client()
            val reply = async(Dispatchers.IO) { runCatching { client.nativeRequest("/api/plugins/beacon/mobile/health") } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fixture.admit(mode); fixture.pair("fresh-phone"); fixture.cache.write(account, "screens", "fresh-row")
                release.countDown()
                val refusal = withTimeout(5_000) { reply.await() }.exceptionOrNull()
                assertTrue("A stale reply cannot apply device removal", refusal is CachedIdentityMismatch || refusal is NativeAuthExpiredException || refusal is ApiFailure && refusal.status == 403)
                assertEquals("fresh-phone", NativePairing.deviceId(fixture.context, fixture.auth.scope()))
                assertEquals("fresh-row", fixture.cache.read(account, "screens"))
            } finally {
                release.countDown(); reply.cancel(); client.closeNative()
                NativeDeviceRemoval.onRemoved = oldRemoved; NativeDeviceRemoval.onForgetScreens = oldForget
            }
        }
    }

    @Test fun actualRoomDeletionChecksAuthorityAfterAcquiringItsTransaction() = runBlocking {
        Fixture().use { fixture ->
            val account = fixture.auth.scope().copy(profile = "", session = "")
            val removal = requireNotNull(fixture.store.deviceRemovalAuthority(fixture.auth.scope()))
            fixture.cache.write(account, "screens", "old-row")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding = async(Dispatchers.IO) { fixture.db.withTransaction {
                entered.complete(Unit); release.await()
                fixture.admit("fresh")
                val key = "${account.key}:${"screens".digest()}"
                val bytes = fixture.cipher.encrypt("fresh-row".toByteArray(), key)
                fixture.db.rows().put(Projection(key, account.key, bytes, System.currentTimeMillis(), false, bytes.size.toLong()))
            } }
            withTimeout(5_000) { entered.await() }
            val executor = fixture.db.transactionExecutor
            val executorLock = executor.javaClass.getDeclaredField("syncLock").apply { isAccessible = true }.get(executor)
            val pending = executor.javaClass.getDeclaredField("tasks").apply { isAccessible = true }.get(executor) as Collection<*>
            val queuedBefore = synchronized(executorLock) { pending.size }
            val deleting = async(Dispatchers.IO) { fixture.cache.deleteIfCurrent(account, "screens", removal) }
            try {
                // Room 2.8.2 queues here before it invokes the underlying transaction executor.
                withTimeout(5_000) { while (synchronized(executorLock) { pending.size <= queuedBefore }) delay(1) }
                release.complete(Unit)
                withTimeout(5_000) { holding.await(); assertFalse(deleting.await()) }
                assertEquals("fresh-row", fixture.cache.read(account, "screens"))
            } finally { release.complete(Unit); holding.cancel(); deleting.cancel() }
        }
    }

    @Test fun currentStoredAuthorityClearsOnlyItsPairingAndScreens() = runBlocking {
        Fixture().use { fixture ->
            val account = fixture.auth.scope().copy(profile = "", session = "")
            val removal = requireNotNull(fixture.store.deviceRemovalAuthority(fixture.auth.scope()))
            fixture.cache.write(account, "screens", "old-row")
            fixture.cache.write(fixture.auth.scope().session("synthetic-chat"), "draft", "keep-draft", protected = true)
            assertTrue(removal.ifCurrent { NativePairing.clearDeviceId(fixture.context, removal.scope) })
            assertTrue(fixture.cache.deleteIfCurrent(account, "screens", removal))
            assertNull(NativePairing.deviceId(fixture.context, fixture.auth.scope()))
            assertNull(fixture.cache.read(account, "screens"))
            assertEquals("keep-draft", fixture.cache.read(fixture.auth.scope().session("synthetic-chat"), "draft"))
            assertEquals(fixture.auth.scope(), fixture.store.read(fixture.auth.origin).scope())
        }
    }
}
