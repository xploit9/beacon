package dev.hermes.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ComponentName
import android.media.AudioManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.core.string
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.receivePush
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NotificationSettings
import dev.hermes.android.ui.SetupWizardScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import dev.hermes.android.core.json
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ProjectionCache
import dev.hermes.android.data.ProjectionDb
import dev.hermes.android.data.NativeDeviceRemoval
import dev.hermes.android.data.DeviceRemovalAuthority
import dev.hermes.android.data.authorizesPushSource
import dev.hermes.android.push.DeliveryLog
import dev.hermes.android.voice.IncomingCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class KeepConnectedUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private class LifecycleGate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fun block() { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) { "Fixture gate was not released" } }
    }

    /** The Application is never started; only the real Android Service is started by its explicit component. */
    private class LifecycleFixture : AutoCloseable {
        val real = ApplicationProvider.getApplicationContext<Context>()
        val app = BeaconApplication()
        val prefix = "lifecycle-${UUID.randomUUID()}"
        val directory = File(real.cacheDir, prefix).apply { check(mkdirs()) }
        val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val gates = CopyOnWriteArrayList<LifecycleGate>()
        val services = CopyOnWriteArrayList<KeepConnectedService>()
        val clients = CopyOnWriteArrayList<BeaconClient>()
        val actualStarts = AtomicInteger()
        val deliveryGate = AtomicReference<LifecycleGate?>()
        val stopGate = AtomicReference<LifecycleGate?>()
        val startReads = AtomicInteger()
        val retirementReads = AtomicInteger()
        @Volatile var holdStart = false
        @Volatile var queuedStart: Intent? = null
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, prefix).apply { isDaemon = true } }
        val jobs = CoroutineScope(SupervisorJob())
        val native = NativeExperienceFixture("lifecycle-owner").apply { notificationProfiles = listOf("default") }
        val oldAuth = AuthContext(native.scope.origin, emptyList(), native.scope.owner, "default", backend = "hermes-dashboard",
            bearer = NativeBearerRecord("synthetic-old", "synthetic-refresh-old", 4_000_000_000, native.scope.origin, native.scope.owner, "default", "fixture", issuedAt = 1))
        private val oldApplication = KeepConnectedService.applicationForTest
        private val oldClientFactory = KeepConnectedService.clientFactoryForTest
        private val oldRemoved = NativeDeviceRemoval.onRemoved
        private val oldForget = NativeDeviceRemoval.onForgetScreens
        private var syntheticRemoved: ((DeviceRemovalAuthority) -> Unit)? = null
        private val oldPlugin = dev.hermes.android.updates.PluginInstallHub.controller
        private val pendingFlag = KeepConnectedService::class.java.getDeclaredField("startPending").apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean
        @Suppress("UNCHECKED_CAST") private val stopFlag = KeepConnectedService::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(null) as AtomicReference<Any?>
        private val oldPending = pendingFlag.get()
        private val oldStop = stopFlag.get()
        private val stores = ViewModelStore()
        private val vmJobs = mutableListOf<Job>()
        private val dataStoreDelegate by lazy { Class.forName("dev.hermes.android.BeaconApplicationKt").getDeclaredField("preferences\$delegate").apply { isAccessible = true }.get(null) }
        private val dataStoreField by lazy { dataStoreDelegate.javaClass.declaredFields.single { DataStore::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true } }
        private var priorDataStore: Any? = null
        private var dataStoreSwapped = false
        private val database: ProjectionDb

        init {
            check(BuildConfig.DEBUG && oldApplication == null && active() == null && !oldPending && oldStop == null) { "Lifecycle fixture requires an idle debug service" }
            val fixture = this
            val context = object : ContextWrapper(real) {
                override fun getApplicationContext(): Context = fixture.app
                override fun getFilesDir(): File = fixture.directory
                override fun getCacheDir(): File = fixture.directory
                override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                    if (name == "notification-delivery") fixture.deliveryGate.getAndSet(null)?.let { gate ->
                        check(!Thread.holdsLock(fixture.app.auth)) { "Fixture must not wait under AuthStore" }; gate.block()
                    }
                    val scoped = "${fixture.prefix}-$name"
                    fixture.names += scoped
                    val prefs = real.getSharedPreferences(scoped, mode)
                    if (name != KeepConnectedService.PREFS) return prefs
                    fixture.noteService()
                    return Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader, arrayOf(android.content.SharedPreferences::class.java)) { _, method, args ->
                        if (method.name == "getString" && args?.firstOrNull() == KeepConnectedService.KEY_ORIGIN && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) fixture.startReads.incrementAndGet()
                        if (method.name == "getString" && args?.firstOrNull() == "stop_token" && android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && Thread.currentThread().stackTrace.any { it.className == KeepConnectedService::class.java.name && it.methodName == "onStartCommand" }) fixture.retirementReads.incrementAndGet()
                        method.invoke(prefs, *(args ?: emptyArray()))
                    } as android.content.SharedPreferences
                }
                override fun startForegroundService(service: Intent): ComponentName? {
                    if (fixture.holdStart) { fixture.queuedStart = Intent(service); return ComponentName(real, KeepConnectedService::class.java) }
                    return real.startForegroundService(service).also { fixture.actualStarts.incrementAndGet() }
                }
                override fun startService(service: Intent): ComponentName? {
                    if (service.action == "dev.hermes.android.push.KEEP_CONNECTED_RETIRE") fixture.stopGate.getAndSet(null)?.block()
                    if (fixture.holdStart) { fixture.queuedStart = Intent(service); return ComponentName(real, KeepConnectedService::class.java) }
                    return real.startService(service).also { fixture.actualStarts.incrementAndGet() }
                }
                override fun stopService(service: Intent): Boolean {
                    fixture.stopGate.getAndSet(null)?.block()
                    return real.stopService(service)
                }
            }
            ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(app, context)
            database = Room.inMemoryDatabaseBuilder(real, ProjectionDb::class.java).build()
            BeaconApplication::class.java.getDeclaredField("cache\$delegate").apply { isAccessible = true }.set(app, lazyOf(ProjectionCache(database, app.cipher)))
            app.auth.write(oldAuth)
            app.push // Installs only a synthetic PushManager, whose Firebase flag stays false.
            syntheticRemoved = NativeDeviceRemoval.onRemoved
            KeepConnectedService.applicationForTest = app
            KeepConnectedService.clientFactoryForTest = { auth, store -> client(auth, store) }
        }

        fun gate(target: AtomicReference<LifecycleGate?>): LifecycleGate = LifecycleGate().also { gates += it; target.set(it) }
        fun removed(authority: DeviceRemovalAuthority) {
            app.push.javaClass.getDeclaredMethod("deviceRemoved", DeviceRemovalAuthority::class.java).apply { isAccessible = true }.invoke(app.push, authority)
        }
        fun client(auth: AuthContext, store: AuthStore): BeaconClient = native.freshClientFor(auth, store).also { clients += it }
        fun fresh(mode: String = "fresh"): AuthContext = oldAuth.copy(
            origin = if (mode == "foreign") "https://fresh-lifecycle.invalid" else oldAuth.origin,
            owner = if (mode == "other") "lifecycle-other" else oldAuth.owner,
            nativeProvider = if (mode == "other") "other-fixture" else oldAuth.nativeProvider,
        ).let { auth -> auth.copy(bearer = oldAuth.bearer?.copy(accessToken = "synthetic-new", refreshToken = "synthetic-refresh-new", origin = auth.origin, owner = auth.owner, provider = auth.nativeProvider, issuedAt = 2)) }
        fun keep(auth: AuthContext) {
            check(app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE).edit().putBoolean(KeepConnectedService.KEY_ENABLED, true)
                .putString(KeepConnectedService.KEY_ORIGIN, auth.origin).putString(KeepConnectedService.KEY_OWNER, auth.owner)
                .putString(KeepConnectedService.KEY_NATIVE_PROVIDER, auth.nativeProvider).putString(KeepConnectedService.KEY_STATUS, KeepConnectedService.STATUS_CONNECTED)
                .putLong(KeepConnectedService.KEY_LAST_RESPONSE, android.os.SystemClock.elapsedRealtime()).commit())
        }
        fun receipt(auth: AuthContext): Triple<String, String, String> {
            val source = "source-${UUID.randomUUID()}"; val event = UUID.randomUUID().toString().replace("-", "")
            val expires = System.currentTimeMillis() / 1000 + 60
            val envelope = json("kind" to "incoming_call", "event_id" to event,
                "callback_id" to event, "conversation_ref" to "conversation-${UUID.randomUUID()}",
                "source_ref" to source, "owner_id" to auth.owner, "profile" to auth.profile, "session_id" to "chat-a", "conversation_id" to "chat-a", "state" to "pending", "expires_at" to expires)
            check(KeepConnectedService.isEnabledFor(app, auth.scope())) { "SETUP_RECEIPT_ENABLED" }
            check(app.auth.read(auth.origin).authorizesPushSource(auth.scope())) { "SETUP_RECEIPT_AUTH" }
            checkNotNull(dev.hermes.android.push.prepareKeepConnectedCall(auth.scope(), envelope, true, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())) { "SETUP_RECEIPT_RELAY" }
            check(app.push.bindKeepConnectedSource(auth.scope(), source)) { "SETUP_RECEIPT_BIND" }
            val token = checkNotNull(app.push.prepareKeepConnectedCall(auth.scope(), envelope, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime())) { "SETUP_RECEIPT_MANAGER" }
            val invite = IncomingCall(source, event, expires, token, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(),
                android.provider.Settings.Global.getInt(real.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1), conversation = "chat-a")
            app.push.javaClass.getDeclaredMethod("persistInvitation", IncomingCall::class.java, java.lang.Boolean.TYPE).apply { isAccessible = true }.invoke(app.push, invite, true)
            return Triple(source, event, token)
        }
        fun active(): KeepConnectedService? = KeepConnectedService::class.java.getDeclaredField("activeInstance").apply { isAccessible = true }.get(null) as? KeepConnectedService
        fun noteService() { active()?.let { if (!services.contains(it)) services += it } }
        fun serviceJob(service: KeepConnectedService): Job = (service.javaClass.getDeclaredField("serviceScope").apply { isAccessible = true }.get(service) as CoroutineScope).coroutineContext[Job]!!
        fun pollAlive(): Boolean = active()?.let { service -> (service.javaClass.getDeclaredField("pollJob").apply { isAccessible = true }.get(service) as? Job)?.isActive } == true
        fun flushStart() { holdStart = false; real.startForegroundService(requireNotNull(queuedStart)); actualStarts.incrementAndGet(); queuedStart = null }
        fun await(label: String = "SETUP_LIFECYCLE_BOUNDARY", condition: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 8_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(10)
            check(condition()) { label }
        }
        fun vm(): BeaconViewModel {
            priorDataStore = dataStoreField.get(dataStoreDelegate)
            val scopedStore = PreferenceDataStoreFactory.create(scope = jobs, produceFile = { File(directory, "fixture.preferences_pb") })
            dataStoreField.set(dataStoreDelegate, scopedStore)
            dataStoreSwapped = true
            lateinit var model: BeaconViewModel
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                model = ViewModelProvider(stores, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                model.javaClass.getDeclaredField("originGeneration").apply { isAccessible = true }.setInt(model, 1)
                model.javaClass.getDeclaredField("client").apply { isAccessible = true }.set(model, client(oldAuth, app.auth))
                model.javaClass.getDeclaredField("_ui").apply { isAccessible = true }.get(model).let { state ->
                    @Suppress("UNCHECKED_CAST") (state as MutableStateFlow<AppState>).value = AppState(origin = oldAuth.origin, owner = oldAuth.owner, profile = "default", authenticated = true, connected = true, restoringSession = false)
                }
                model.profileSwitchClientFactoryForTest = { auth -> client(auth, app.auth) }
                vmJobs += requireNotNull(model.viewModelScope.coroutineContext[Job])
            }
            return model
        }
        override fun close() {
            gates.forEach { it.release.countDown() }; holdStart = false; queuedStart = null
            executor.shutdown(); check(executor.awaitTermination(10, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { stores.clear() }
            runBlocking { withTimeout(10_000) { vmJobs.forEach { it.join() } } }
            if (actualStarts.get() > 0) await { !pendingFlag.get() }
            noteService()
            if (services.isNotEmpty()) {
                check(active() == null || services.any { it === active() }) { "Refuse to stop an unrelated service" }
                real.stopService(Intent(real, KeepConnectedService::class.java))
                await { active() == null }
                runBlocking { withTimeout(10_000) { services.forEach { serviceJob(it).join() } } }
            }
            val push = app.push
            val prefs = push.javaClass.getDeclaredField("pairingPrefs").apply { isAccessible = true }.get(push) as android.content.SharedPreferences
            val listener = push.javaClass.getDeclaredField("pairingListener").apply { isAccessible = true }.get(push) as android.content.SharedPreferences.OnSharedPreferenceChangeListener
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            push.invalidateKeepConnectedInvitations()
            val cleanup = push.javaClass.getDeclaredField("cleanup").apply { isAccessible = true }.get(push) as CoroutineScope
            runBlocking { withTimeout(10_000) { cleanup.coroutineContext[Job]?.cancel(); cleanup.coroutineContext[Job]?.join() } }
            clients.forEach { it.closeNative() }; native.client.closeNative(); database.close()
            runBlocking { withTimeout(10_000) { jobs.coroutineContext[Job]?.cancel(); jobs.coroutineContext[Job]?.join() } }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            pendingFlag.set(oldPending); stopFlag.set(oldStop)
            if (dataStoreSwapped) dataStoreField.set(dataStoreDelegate, priorDataStore)
            KeepConnectedService.clientFactoryForTest = oldClientFactory
            KeepConnectedService.applicationForTest = oldApplication
            if (NativeDeviceRemoval.onRemoved === syntheticRemoved) NativeDeviceRemoval.onRemoved = oldRemoved
            if (NativeDeviceRemoval.onForgetScreens == null) NativeDeviceRemoval.onForgetScreens = oldForget
            dev.hermes.android.updates.PluginInstallHub.controller = oldPlugin
            names.forEach(real::deleteSharedPreferences)
            check(directory.canonicalFile.parentFile == real.cacheDir.canonicalFile && directory.name == prefix)
            directory.deleteRecursively()
        }
    }

    @Test fun lateRetirementKeepsFreshDeliveryPreferencesAndReceipts() {
        val outcomes = linkedMapOf<String, Boolean>()
        for (mode in listOf("fresh", "other", "foreign")) LifecycleFixture().use { f ->
            assertFalse("positive control starts with Keep connected never enabled", KeepConnectedService.isEnabled(f.app))
            DeliveryLog.recordArrival(f.app, "test", "old-event", 111)
            val current = requireNotNull(f.app.auth.deviceRemovalAuthority(f.oldAuth.scope()))
            f.removed(current)
            assertEquals("positive current removal clears its old timestamp", 0L, DeliveryLog.lastAlertAt(f.app))
            f.app.auth.write(f.oldAuth); f.keep(f.oldAuth)
            val captured = requireNotNull(f.app.auth.deviceRemovalAuthority(f.oldAuth.scope()))
            val gate = f.gate(f.deliveryGate)
            val old = f.executor.submit { f.removed(captured) }
            assertTrue("actual old cleanup reached delivery persistence", gate.entered.await(8, TimeUnit.SECONDS))
            val fresh = f.fresh(mode); f.app.auth.write(fresh); f.keep(fresh)
            // A successful fresh link clears the removed marker through the real pairing listener.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                f.app.getSharedPreferences("native-pairing", Context.MODE_PRIVATE).edit().putString(dev.hermes.android.data.NativePairing.accountKey(fresh.scope()), "fresh-phone").commit()
            }
            val receipt = f.receipt(fresh)
            DeliveryLog.recordArrival(f.app, "test", "fresh-event", 222)
            assertEquals(222L, DeliveryLog.lastAlertAt(f.app))
            assertTrue(f.app.push.hasKeepConnectedInvitation(receipt.first, receipt.second, receipt.third))
            assertEquals(KeepConnectedService.STATUS_CONNECTED, KeepConnectedService.status(f.app))
            gate.release.countDown(); old.get(8, TimeUnit.SECONDS)
            outcomes[mode] = DeliveryLog.lastAlertAt(f.app) == 222L && f.app.push.hasKeepConnectedInvitation(receipt.first, receipt.second, receipt.third) &&
                KeepConnectedService.status(f.app) == KeepConnectedService.STATUS_CONNECTED
        }
        assertTrue("old accepted removal changed newly admitted delivery state: $outcomes", outcomes.values.all { it })
    }

    @Test fun oldRetirementCannotStopNewActiveOrPendingStart() {
        LifecycleFixture().use { f ->
            KeepConnectedService.setEnabled(f.app, f.oldAuth, true); f.await { f.pollAlive() }
            KeepConnectedService.onUserSignedOut(f.app, requireNotNull(f.app.auth.deviceRemovalAuthority(f.oldAuth.scope())))
            f.await { f.active() == null && f.services.all { f.serviceJob(it).isCompleted } }
            assertFalse("positive current retirement stops its actual service", f.pollAlive())
        }
        val outcomes = linkedMapOf<String, Boolean>()
        for (pending in listOf(false, true)) LifecycleFixture().use { f ->
            f.keep(f.oldAuth)
            if (!pending) {
                KeepConnectedService.setEnabled(f.app, f.oldAuth, true); f.await { f.pollAlive() }
            }
            val oldAuthority = requireNotNull(f.app.auth.deviceRemovalAuthority(f.oldAuth.scope()))
            val gate = if (pending) f.gate(f.deliveryGate) else f.gate(f.stopGate)
            val old = f.executor.submit { f.removed(oldAuthority) }
            assertTrue("old accepted cleanup reached the held persistence/stop boundary", gate.entered.await(8, TimeUnit.SECONDS))
            val fresh = f.fresh(); f.app.auth.write(fresh)
            f.holdStart = pending
            val seen = f.startReads.get()
            KeepConnectedService.setEnabled(f.app, fresh, true)
            if (pending) {
                assertNotNull("new actual start transport is pending", f.queuedStart)
                gate.release.countDown(); old.get(8, TimeUnit.SECONDS)
                f.flushStart()
                f.await { f.pollAlive() }
            } else {
                f.await { f.startReads.get() > seen && f.pollAlive() }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { }
                val delivered = f.retirementReads.get()
                gate.release.countDown(); old.get(8, TimeUnit.SECONDS)
                f.await { f.retirementReads.get() > delivered }
                InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            }
            // Observe the actual command handler/token read; a current-generation loop must survive.
            outcomes[if (pending) "pending" else "active"] = f.pollAlive()
        }
        assertTrue("old retirement stopped a newer actual service generation: $outcomes", outcomes.values.all { it })
    }

    @Test fun rejectedRetirementInitializesOnlyAnUninitializedCurrentGeneration() {
        LifecycleFixture().use { f ->
            f.holdStart = true
            KeepConnectedService.setEnabled(f.app, f.oldAuth, true)
            KeepConnectedService.onUserSignedOut(f.app, requireNotNull(f.app.auth.deviceRemovalAuthority(f.oldAuth.scope())))
            val pending = KeepConnectedService::class.java.getDeclaredField("stopRequested").apply { isAccessible = true }.get(null) as AtomicReference<*>
            val request = checkNotNull(pending.get()) { "SETUP_STALE_RETIREMENT_REQUEST" }
            val id = request.javaClass.getDeclaredField("id").apply { isAccessible = true }.get(request) as String
            val fresh = f.fresh(); f.app.auth.write(fresh)
            KeepConnectedService.setEnabled(f.app, fresh, true)
            val reads = f.retirementReads.get()
            // Deliver the rejected old command through Android while the legitimate fresh start is still held.
            f.real.startService(Intent(f.real, KeepConnectedService::class.java)
                .setAction("dev.hermes.android.push.KEEP_CONNECTED_RETIRE").putExtra("retirement", id))
            f.actualStarts.incrementAndGet()
            f.await("SETUP_STALE_RETIREMENT_DELIVERY") { f.retirementReads.get() > reads }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { }
            val service = checkNotNull(f.active()) { "SETUP_STALE_RETIREMENT_INSTANCE" }
            val promoted = service.javaClass.getDeclaredField("promoted").apply { isAccessible = true }.getBoolean(service)
            var initialized = f.pollAlive() && promoted
            if (initialized) {
                val end = android.os.SystemClock.elapsedRealtime() + 8_000
                while (f.native.requests.none { it.string("path") == "/api/plugins/beacon/mobile/notifications/events" } && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(10)
                initialized = f.native.requests.any { it.string("path") == "/api/plugins/beacon/mobile/notifications/events" }
            }
            // Positive control: that same real instance must run the valid fresh generation when its start arrives.
            f.flushStart()
            f.await("SETUP_VALID_START_POSITIVE_CONTROL") { f.pollAlive() && f.native.requests.any { it.string("path") == "/api/plugins/beacon/mobile/notifications/events" } }
            assertTrue("rejected stale retirement left its new service idle or unpromoted before the valid start arrived", initialized)
            assertTrue("the legitimate newer start remains active", f.pollAlive())
        }
    }

    @Test fun logoutSuspensionCannotRetireFreshSignIn() = runBlocking {
        LifecycleFixture().use { f ->
            suspend fun phase(label: String, block: suspend () -> Unit) {
                try { withTimeout(8_000) { block() } }
                catch (timeout: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError(label, timeout) }
            }
            f.keep(f.oldAuth)
            val vm = f.vm()
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val io = dev.hermes.android.data.ScreenCache::class.java.getDeclaredField("io").apply { isAccessible = true }
            val actual = io.get(vm.screens) as Mutex
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            val held = object : Mutex by actual {
                override suspend fun lock(owner: Any?) {
                    if (first.compareAndSet(true, false)) { entered.complete(Unit); release.await() }
                    actual.lock(owner)
                }
            }
            io.set(vm.screens, held)
            val logout = vm.logout()
            var primary: Throwable? = null
            try {
                phase("SETUP_LOGOUT_SCREEN_LOCK") { entered.await() }
                assertTrue("real forgetScreens suspended after AuthStore removal", f.app.auth.read(f.oldAuth.origin).owner.isBlank())
                val fresh = f.fresh()
                lateinit var connecting: Job
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    connecting = BeaconViewModel::class.java.getDeclaredMethod("connect", String::class.java, AuthContext::class.java).apply { isAccessible = true }.invoke(vm, fresh.origin, fresh) as Job
                }
                phase("SETUP_LOGOUT_CONNECT") { connecting.join() }
                assertTrue("fresh fixture sign-in actually completed", vm.ui.value.authenticated)
                f.await("SETUP_LOGOUT_NEW_SERVICE") { f.pollAlive() }
                val receipt = f.receipt(fresh)
                DeliveryLog.recordArrival(f.app, "test", "new-after-logout", 333)
                release.complete(Unit); phase("SETUP_LOGOUT_COMPLETION") { logout.join() }
                assertEquals("old logout cleared fresh delivery history", 333L, DeliveryLog.lastAlertAt(f.app))
                assertTrue("old logout invalidated the replacement receipt", f.app.push.hasKeepConnectedInvitation(receipt.first, receipt.second, receipt.third))
                assertTrue("old logout stopped fresh fixture delivery", f.pollAlive())
            } catch (error: Throwable) { primary = error; throw error }
            finally {
                release.complete(Unit)
                try { phase("SETUP_LOGOUT_FINAL_JOIN") { logout.join() } }
                catch (cleanup: Throwable) { if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun restoreSharedPreferences(prefs: android.content.SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }

    @Test fun setupDefaultsKeepConnectedOnAndPassesTheOwnerChoice() {
        var selected: Boolean? = null
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                SetupWizardScreen(
                    state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false),
                    onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = { selected = it }, onReopen = {}, onCancel = {},
                )
            } }
        }

        compose.onNodeWithTag("setup-keep-connected").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("setup-finish").performClick()
        assertEquals(false, selected)
    }

    @Test fun disabledStartStopsWithoutCrashingTheApp() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        val keepBefore = keepPrefs.all.toMap()
        try {
            check(keepPrefs.edit().putBoolean(KeepConnectedService.KEY_ENABLED, false).commit())
            // Turning Keep connected off between the start request and onStartCommand must still promote; stopping first crashes the app.
            androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, KeepConnectedService::class.java))
            // The crash, if any, lands right after onStartCommand; this wait lets it surface before the run reports.
            Thread.sleep(1500)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 4082 })
        } finally {
            restoreSharedPreferences(keepPrefs, keepBefore)
            if (keepBefore[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
        }
    }

    @Test fun cachedConnectedStatusExpiresWhenNotificationResponsesStop() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val prefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        val before = prefs.all.toMap()
        try {
            KeepConnectedService.setEnabled(app, null, false)
            compose.waitForIdle()
            val now = android.os.SystemClock.elapsedRealtime()
            check(prefs.edit().putBoolean(KeepConnectedService.KEY_ENABLED, true).putString(KeepConnectedService.KEY_STATUS, KeepConnectedService.STATUS_CONNECTED)
                .putLong("last_response_elapsed", now - 120_000).commit())
            assertEquals(KeepConnectedService.STATUS_RECONNECTING, KeepConnectedService.status(app))
            check(prefs.edit().putLong("last_response_elapsed", android.os.SystemClock.elapsedRealtime()).commit())
            assertEquals(KeepConnectedService.STATUS_CONNECTED, KeepConnectedService.status(app))
            check(prefs.edit().putLong("last_response_elapsed", now + 3_600_000).commit())
            assertEquals(KeepConnectedService.STATUS_RECONNECTING, KeepConnectedService.status(app))
        } finally {
            restoreSharedPreferences(prefs, before)
            if (before[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
        }
    }

    @Test fun enablingPollsFixtureTaskResultsAndCallsThroughTheirNativePaths() = exerciseIncoming("default")

    @Test fun anIncomingCallKeepsItsOwnProfileWhenTheAssistantUsesAnotherProfile() = exerciseIncoming("coder")

    private fun exerciseIncoming(assistantProfile: String) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val fixture = NativeExperienceFixture()
        val scope = fixture.scope
        val expectedTab = if (assistantProfile == scope.profile) "Beacon" else "Chat"
        val priorBeacon = app.cache.read(scope, "beacon-session")
        val oldAuth = app.auth.read(scope.origin)
        val dataBefore = app.preferences.data.first()
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        // A killed run leaves its fixture cursor ahead of this fixture's first event, so the poll would never deliver it.
        val fixtureCursors = fixture.notificationProfiles.map { "cursor:${scope.copy(profile = it, session = "").key}" }
        val keepBefore = keepPrefs.all.toMap() - fixtureCursors.toSet()
        val pushPrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val voicePrefs = app.getSharedPreferences("voice-private", Context.MODE_PRIVATE)
        val voiceBefore = voicePrefs.all.toMap()
        val priorSources = mutableMapOf<String, String?>()
        fun seedExistingFcmBinding(source: String) {
            val key = "source:${source.digest()}"
            priorSources[key] = pushPrefs.getString(key, null)
            val value = JSONObject().put("source", source).put("origin", scope.origin).put("owner", scope.owner)
                .put("native_provider", scope.nativeProvider)
                .put("profile", scope.profile).put("backend", "hermes-dashboard").put("target", "fixture-target")
            val encoded = android.util.Base64.encodeToString(
                app.cipher.encrypt(value.toString().toByteArray(), key), android.util.Base64.NO_WRAP,
            )
            persistPushBindings(pushPrefs, mapOf(key to encoded))
        }
        val originKey = stringPreferencesKey("origin")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val setupStepKey = stringPreferencesKey("setup_step")
        val assistantProfileKey = stringPreferencesKey("assistant_profile")
        val beforeVolume = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_RING)
        val audio = app.getSystemService(AudioManager::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        val previousSignInNotification = runCatching { manager.activeNotifications.firstOrNull { it.id == 4083 } }.getOrNull()
        val store = ViewModelStore()
        var vmClient: dev.hermes.android.data.BeaconClient? = null
        val auth = AuthContext(
            scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
        )
        val testEvents = mutableListOf<org.json.JSONObject>()
        lateinit var vm: BeaconViewModel
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
            KeepConnectedService.setEnabled(app, null, false)
            check(keepPrefs.edit().apply { fixtureCursors.forEach { remove(it) } }.commit())
            KeepConnectedService.clientFactoryForTest = { current, authStore -> fixture.clientFor(current, authStore) }
            audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            app.auth.write(auth)
            val keepChannelId = "beacon_keep_connected"
            if (manager.getNotificationChannel(keepChannelId) == null) {
                manager.createNotificationChannel(NotificationChannel(keepChannelId, "Keep connected", NotificationManager.IMPORTANCE_LOW))
            }
            fun showSignInPrompt() {
                manager.notify(4083, Notification.Builder(app, keepChannelId)
                    .setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle("Sign in again").build())
            }
            showSignInPrompt()
            KeepConnectedService.setEnabled(app, null, false)
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            assertTrue(keepPrefs.edit().putString(KeepConnectedService.KEY_ORIGIN, scope.origin).commit())
            showSignInPrompt()
            KeepConnectedService.onUserSignedOut(app, requireNotNull(app.auth.deviceRemovalAuthority(scope)))
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            app.preferences.edit { values ->
                values[originKey] = ""
                values[setupCompleteKey] = true
                values[setupActiveKey] = false
                values[setupStepKey] = "done"
                values[assistantProfileKey] = assistantProfile
            }
            val voiceStore = VoiceKeyStore(app)
            voiceStore.saveKey(voiceStore.settings().provider, "fixture-voice-key")
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            vmClient = fixture.clientFor(auth, app.auth)
            // Only the cache names the Beacon chat, as after a cold start: nothing prepared and an empty saved handle.
            app.cache.write(scope, "beacon-session", "chat-b", protected = true)
            compose.runOnUiThread {
                field(vm, "client", vmClient)
                vm.profileSwitchClientFactoryForTest = { next -> fixture.clientFor(next, app.auth) }
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, authenticated = true, connected = true, owner = scope.owner, profile = scope.profile, setupComplete = true, restoringSession = false)
                app.calls.ownerScope = scope
                app.calls.uiVisible = false
            }
            val showIncomingCall = mutableStateOf(false)
            var answerActions = 0
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
                if (showIncomingCall.value) IncomingCallSheet(vm, requestingPermission = false) { source, event -> answerActions++; vm.handlePush(source, event, "answer") }
                else NotificationSettings(vm)
            } } }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(android.app.NotificationChannel("beacon_keep_connected", "Keep connected", NotificationManager.IMPORTANCE_LOW))
            }
            val signInPrompt = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(app, "beacon_keep_connected")
            } else android.app.Notification.Builder(app)
            manager.notify(4083, signInPrompt.setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle("Sign in again").build())

            fun notificationKey(event: JSONObject) = "${event.getString("source_ref")}\u0000${event.getString("event_id")}".digest()
            // The first connection starts every profile's cursor at Hermes's current head: an older event is not replayed.
            val olderEvent = fixture.emitTaskResult()
            testEvents += olderEvent
            compose.onNodeWithTag("keep-connected-toggle").performClick()
            compose.waitUntil(15_000) {
                KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED && fixtureCursors.all(keepPrefs::contains)
            }
            // The page shows the same state as a pill, not as a line of text.
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("keep-connected-status").assertTextContains("Connected"); true }.getOrDefault(false) }
            fun deliveryCpuHeld(): Boolean = uiAutomation.executeShellCommand("dumpsys power").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readLines().any {
                    it.contains("PARTIAL_WAKE_LOCK") && it.contains("${app.packageName}:notifications")
                }
            }
            assertTrue("Keep connected must protect notification polling while the screen sleeps", deliveryCpuHeld())
            fun shell(command: String): String = uiAutomation.executeShellCommand(command).use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().readText().trim()
            }
            val wifiWasOn = shell("settings get global wifi_on") in setOf("1", "2")
            val mobileWasOn = shell("settings get global mobile_data") != "0"
            val connectivity = app.getSystemService(android.net.ConnectivityManager::class.java)
            try {
                shell("svc wifi disable"); shell("svc data disable")
                compose.waitUntil(15_000) { connectivity.activeNetwork == null }
                try { compose.waitUntil(5_000) { !deliveryCpuHeld() } } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                    // Say what still holds the CPU, so a failure here can be told apart from a slow callback.
                    val held = uiAutomation.executeShellCommand("dumpsys power").use { d -> java.io.FileInputStream(d.fileDescriptor).bufferedReader().readLines().filter { it.contains("${app.packageName}:notifications") } }
                    val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }
                    throw AssertionError("CPU still held offline: network=${connectivity.activeNetwork} capabilities=$capabilities status=${KeepConnectedService.status(app)} lines=$held", timeout)
                }
            } finally {
                shell("svc wifi ${if (wifiWasOn) "enable" else "disable"}")
                shell("svc data ${if (mobileWasOn) "enable" else "disable"}")
            }
            compose.waitUntil(20_000) { connectivity.activeNetwork != null && deliveryCpuHeld() }
            val power = app.getSystemService(android.os.PowerManager::class.java)
            val wasInteractive = power.isInteractive
            val wasExempt = power.isIgnoringBatteryOptimizations(app.packageName)
            try {
                if (!wasExempt) shell("dumpsys deviceidle whitelist +${app.packageName}")
                shell("input keyevent KEYCODE_SLEEP")
                compose.waitUntil(5_000) { !power.isInteractive }
                shell("dumpsys deviceidle force-idle")
                assertTrue("Fixture must enter actual Android idle mode", power.isDeviceIdleMode)
                val taskResultEvent = fixture.emitTaskResult()
                testEvents += taskResultEvent
                val taskResultKey = notificationKey(taskResultEvent)
                compose.waitUntil(15_000) { manager.activeNotifications.count { it.tag == taskResultKey && it.id == 1 } == 1 }
                assertEquals("An alert must arrive during idle without reopening Beacon", 1, manager.activeNotifications.count { it.tag == taskResultKey && it.id == 1 })
                assertTrue(deliveryCpuHeld())
            } finally {
                shell("dumpsys deviceidle unforce")
                if (!wasExempt) shell("dumpsys deviceidle whitelist -${app.packageName}")
                if (wasInteractive) { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
            }
            val interactiveBeforeUi = power.isInteractive
            // The fixture's next step uses the screen even if it had already slept before the idle probe.
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) {
                power.isInteractive && compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                    compose.activity.window.decorView.hasWindowFocus()
            }
            println("KEEP_CONNECTED_FIXTURE_WAKE before=$interactiveBeforeUi entered_idle_awake=$wasInteractive after=${power.isInteractive}")
            assertTrue(manager.activeNotifications.none { it.tag == notificationKey(olderEvent) })

            val answerEvent = fixture.emitIncomingCall(conversationId = "chat-b")
            testEvents += answerEvent
            val answerSource = answerEvent.getString("source_ref")
            seedExistingFcmBinding(answerSource)
            compose.waitUntil(15_000) { app.push.ringingInvitation(answerSource, answerEvent.getString("event_id")) != null }
            // The system applies notification cancels asynchronously.
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            assertTrue(fixture.notificationWaits.contains(25L))
            val answerInvite = app.push.ringingInvitation(answerSource, answerEvent.getString("event_id"))!!
            val answerEventId = answerEvent.getString("event_id")
            fun ringingCards() = manager.activeNotifications.filter { card ->
                val inviteRoute = android.app.PendingIntent.getActivity(
                    app,
                    "$answerSource:$answerEventId".hashCode(),
                    android.content.Intent(app, BeaconActivity::class.java)
                        .setData(android.net.Uri.parse("hermes://activity/${answerInvite.key}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("push_source", answerSource)
                        .putExtra("push_event", answerEventId),
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                inviteRoute != null && card.notification.category == android.app.Notification.CATEGORY_CALL && card.notification.contentIntent == inviteRoute
            }
            fun isRingingSlot(card: android.service.notification.StatusBarNotification) =
                (card.tag == answerInvite.key && card.id == 2) || (card.tag == null && card.id == 42)
            compose.waitUntil(10_000) { ringingCards().let { it.size == 1 && isRingingSlot(it.single()) } }
            val cards = ringingCards()
            assertEquals("Expected one active call card for this invite; found ${cards.map { "${it.tag}:${it.id}" }}", 1, cards.size)
            assertTrue("The invite card must use its notification or ringtone foreground slot", isRingingSlot(cards.single()))

            val autoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { showIncomingCall.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(1000)
            val answerRequestStart = fixture.requests.size
            val answerOriginGeneration = BeaconViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }.getInt(vm)
            try {
                assertTrue("The displayed invite must still authorize Answer", app.push.authorizeKeepConnectedAction(answerSource, answerEventId, "answer"))
                assertNotNull("The fixture's selected voice key must be readable", voiceStore.session())
                compose.onNodeWithTag("beacon-incoming-answer").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                assertEquals("The actual Answer control must dispatch once", 1, answerActions)
            } catch (failure: Throwable) {
                val answer = runCatching { compose.onNodeWithTag("beacon-incoming-answer").fetchSemanticsNode().boundsInRoot }.getOrNull()
                val sheet = runCatching { compose.onNodeWithTag("beacon-incoming-sheet").fetchSemanticsNode().boundsInRoot }.getOrNull()
                val decor = compose.activity.window.decorView
                val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
                println("KEEP_CONNECTED_FIXTURE_GEOMETRY answer=$answer sheet=$sheet decor=${decor.width}x${decor.height} shown=${decor.isShown} focus=${decor.hasWindowFocus()} lifecycle=${compose.activity.lifecycle.currentState} locked=${keyguard.isKeyguardLocked} error_present=${vm.ui.value.error.isNotBlank()}")
                throw failure
            } finally { compose.mainClock.autoAdvance = autoAdvance }
            // The saved Beacon handle starts empty here, as after a cold start; the Beacon screen must still be usable.
            try { compose.waitUntil(15_000) { vm.ui.value.tab == expectedTab && vm.ui.value.profile == scope.profile && vm.client?.scope?.profile == scope.profile && vm.chat.value.sessionId == "chat-b" && vm.isBeaconConversation() == (assistantProfile == scope.profile) } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                // Keep the state that shows why answering did not open the chat.
                throw AssertionError("Answer did not open chat-b: tab=${vm.ui.value.tab} session=${vm.chat.value.sessionId} ready=${vm.isBeaconConversation()} error=${vm.ui.value.error} " +
                    "notice=${vm.ui.value.notice} callError=${app.calls.state.value.error} invite=${app.push.ringingInvitation(answerSource, answerEventId) != null} " +
                    "requests=${fixture.requests.takeLast(8).map { it.string("method") + " " + it.string("path") }}", timeout)
            }
            assertEquals("chat-b", vm.chat.value.sessionId)
            val answerPath = "/api/plugins/beacon/mobile/callbacks/${answerEvent.getString("event_id")}/answer"
            // This fixture host is hidden; the service posts the explicit receipt before refusing to start a live call.
            try { compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath } && !app.calls.state.value.active } }
            catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                println("KEEP_CONNECTED_FIXTURE_ANSWER generation_delta=${BeaconViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }.getInt(vm) - answerOriginGeneration} assistant_matches_fixture=${AppPreferences.read(app.preferences.data.first()).assistantProfile == scope.profile} profile_matches_fixture=${vm.client?.scope?.profile == scope.profile} permits=${app.push.permitsIncoming(answerSource)} authorized=${app.push.authorizeKeepConnectedAction(answerSource, answerEventId, "answer")} invite=${app.push.invitation(answerSource, answerEventId) != null} phone_provider=${voiceStore.settings().provider} fixture_provider=${fixture.voiceProvider}")
                println("KEEP_CONNECTED_FIXTURE_REJECTIONS ${fixture.rejectedRequests.map { it.string("method") + " " + it.string("path") + " " + it.optInt("status") }}")
                println("KEEP_CONNECTED_FIXTURE_ANSWER_REQUESTS ${fixture.requests.drop(answerRequestStart).filter { it.string("path") != "/api/plugins/beacon/mobile/notifications/events" }.map { it.string("method") + " " + it.string("path") }}")
                throw AssertionError("Answer callback: posted=${fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath }} active=${app.calls.state.value.active} stage=${app.calls.state.value.state} error=${app.calls.state.value.error} uiError=${vm.ui.value.error} page=${vm.ui.value.morePage} requests=${fixture.requests.filter { it.string("path") != "/api/plugins/beacon/mobile/notifications/events" }.takeLast(12).map { it.string("method") + " " + it.string("path") }}", timeout)
            }
            assertFalse(app.calls.state.value.active)
            assertTrue(fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath })

            val declineEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += declineEvent
            val declineSource = declineEvent.getString("source_ref")
            seedExistingFcmBinding(declineSource)
            compose.waitUntil(10_000) { app.push.ringingInvitation(declineSource, declineEvent.getString("event_id")) != null }
            val declineAutoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = false
            try {
                compose.waitUntil(5_000) { app.push.incoming.value?.event == declineEvent.getString("event_id") && !vm.incomingBusy.value }
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                compose.mainClock.advanceTimeBy(1000)
                compose.onNodeWithTag("beacon-incoming-decline").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            } finally { compose.mainClock.autoAdvance = declineAutoAdvance }
            compose.waitUntil(10_000) { fixture.incomingNotification?.optString("event_id") == declineEvent.getString("event_id") && fixture.incomingNotification?.optString("state") == "declined" }

            val expiryEvent = fixture.emitIncomingCall(conversationId = "chat-a", expiresInSeconds = 3)
            testEvents += expiryEvent
            val expirySource = expiryEvent.getString("source_ref")
            seedExistingFcmBinding(expirySource)
            compose.waitUntil(10_000) { app.push.missedInvitation(expirySource, expiryEvent.getString("event_id")) != null }

            val optOutEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += optOutEvent
            val optOutSource = optOutEvent.getString("source_ref")
            seedExistingFcmBinding(optOutSource)
            compose.waitUntil(10_000) { app.push.ringingInvitation(optOutSource, optOutEvent.getString("event_id")) != null }
            assertFalse(app.push.binding(optOutSource)!!.optBoolean("keep_connected"))
            KeepConnectedService.setEnabled(app, null, false)
            compose.waitUntil(5_000) { !deliveryCpuHeld() }
            assertNull(app.push.ringingInvitation(optOutSource, optOutEvent.getString("event_id")))
            assertFalse(app.push.authorizeKeepConnectedAction(optOutSource, optOutEvent.getString("event_id"), "answer"))

            val fcmEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += fcmEvent
            val fcmSource = fcmEvent.getString("source_ref")
            val fcmEventId = fcmEvent.getString("event_id")
            seedExistingFcmBinding(fcmSource)
            receivePush(app, mapOf(
                "source_ref" to fcmSource,
                "event_id" to fcmEventId,
                "conversation_ref" to fcmEvent.getString("conversation_ref"),
                "kind" to "incoming_call",
                "expires_at" to fcmEvent.getString("expires_at"),
            ))
            assertTrue(app.push.authorizeKeepConnectedAction(fcmSource, fcmEventId, "answer"))
            assertTrue(app.push.authorizeKeepConnectedAction(fcmSource, fcmEventId, "decline"))

            val receiptlessEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += receiptlessEvent
            val receiptlessSource = receiptlessEvent.getString("source_ref")
            val receiptlessEventId = receiptlessEvent.getString("event_id")
            seedExistingFcmBinding(receiptlessSource)
            assertTrue(app.push.saveInvitation(dev.hermes.android.voice.IncomingCall(
                receiptlessSource,
                receiptlessEventId,
                receiptlessEvent.getString("expires_at").toLong(),
                java.util.UUID.randomUUID().toString(),
                conversation = receiptlessEvent.getString("conversation_ref"),
            )))
            assertFalse(app.push.authorizeKeepConnectedAction(receiptlessSource, receiptlessEventId, "answer"))
        } finally {
            if (priorBeacon == null) app.cache.delete(scope, "beacon-session") else app.cache.write(scope, "beacon-session", priorBeacon, protected = true)
            KeepConnectedService.setEnabled(app, null, false)
            if (previousSignInNotification == null) manager.cancel(4083)
            else manager.notify(previousSignInNotification.tag, previousSignInNotification.id, previousSignInNotification.notification)
            runCatching { compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4082 } } }
            testEvents.forEach { event ->
                val source = event.optString("source_ref")
                val id = event.optString("event_id")
                app.push.discardInvitation(source, id)
                app.push.removeKeepConnectedCallback(source, id)
                dev.hermes.android.voice.IncomingRingtoneService.silence(source, id)
            }
            compose.runOnUiThread { store.clear(); app.calls.hangup(); app.calls.ownerScope = null; app.calls.uiVisible = false }
            vmClient?.closeNative()
            KeepConnectedService.clientFactoryForTest = null
            val sourceEdit = pushPrefs.edit()
            priorSources.forEach { (key, value) -> if (value == null) sourceEdit.remove(key) else sourceEdit.putString(key, value) }
            check(sourceEdit.commit())
            if (oldAuth.owner.isBlank() && oldAuth.bearer == null && oldAuth.cookies.isEmpty()) app.auth.remove(scope.origin) else app.auth.write(oldAuth)
            app.preferences.edit { values ->
                dataBefore[originKey]?.let { values[originKey] = it } ?: values.remove(originKey)
                dataBefore[setupCompleteKey]?.let { values[setupCompleteKey] = it } ?: values.remove(setupCompleteKey)
                dataBefore[setupActiveKey]?.let { values[setupActiveKey] = it } ?: values.remove(setupActiveKey)
                dataBefore[setupStepKey]?.let { values[setupStepKey] = it } ?: values.remove(setupStepKey)
                dataBefore[assistantProfileKey]?.let { values[assistantProfileKey] = it } ?: values.remove(assistantProfileKey)
            }
            restoreSharedPreferences(keepPrefs, keepBefore)
            restoreSharedPreferences(voicePrefs, voiceBefore)
            audio.setStreamVolume(AudioManager.STREAM_RING, beforeVolume, 0)
            if (keepBefore[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
            // Revoking a runtime permission kills this process and ends the whole instrumentation run, so the runner puts
            // POST_NOTIFICATIONS and RECORD_AUDIO back to their state before the run once the app has stopped.
        }
    }
}
