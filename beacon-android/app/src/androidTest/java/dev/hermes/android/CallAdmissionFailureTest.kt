package dev.hermes.android

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.voice.BeaconCallService
import dev.hermes.android.voice.CallController
import kotlinx.coroutines.*
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real controller/service exits and serialized HTTP bodies; no provider, socket, microphone or saved account. */
class CallAdmissionFailureTest {
    @Test fun rejectedAndroidServiceStartClosesTheReservationAsFailed() = runBlocking {
        fixture {
            onStart = { throw IllegalStateException("Synthetic Android service-start rejection") }
            val error = runCatching { begin() }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals(1, serviceStarts)
            assertCloses(failed = true)
            assertNull(app.calls.spec)
            assertFalse(app.calls.state.value.active)
        }
    }

    @Test fun cancelledCoroutineDoesNotMarkTheReservationFailed() = runBlocking {
        fixture {
            val attempt = launch(start = CoroutineStart.LAZY) { begin() }
            onStart = { attempt.cancel(); throw CancellationException("Synthetic call cancellation") }
            attempt.start()
            withTimeout(5000) { attempt.join() }
            assertTrue(attempt.isCancelled)
            assertCloses(failed = false)
        }
    }

    @Test fun invalidatedReservationNeverStartsTheServiceOrBecomesFailed() = runBlocking {
        fixture {
            onReserve = { app.calls.hangup() }
            begin()
            assertEquals(0, serviceStarts)
            assertCloses(failed = false)
            assertNull(app.calls.spec)
        }
    }

    @Test fun serviceRejectionAfterUserCancellationStaysNonfailed() = runBlocking {
        fixture {
            onStart = { app.calls.hangup(); throw IllegalStateException("Rejected after cancellation") }
            assertTrue(runCatching { begin() }.exceptionOrNull() is IllegalStateException)
            // Hangup and the start catch each release the same reservation; both must preserve cancellation.
            assertCloses(failed = false, count = 2)
        }
    }

    @Test fun deliberatePendingHangupClosesWithoutFailure() = runBlocking {
        fixture {
            begin()
            assertNotNull(app.calls.spec)
            withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
            assertCloses(failed = false)
        }
    }

    @Test fun firstForegroundHandshakeRejectionClosesThePendingCallAsFailed() = runBlocking {
        fixture {
            begin()
            val service = newService()
            rejectNextForeground = true
            withContext(Dispatchers.Main.immediate) {
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(BeaconCallService.START), 0, 1))
            }
            assertEquals(1, foregroundRejections)
            assertCloses(failed = true)
            assertNull(app.calls.spec)
            assertNull(app.calls.service)
            assertNoMedia(service)
        }
    }

    @Test fun permissionRevokedBeforeServiceAdmissionClosesAsFailed() = runBlocking {
        fixture {
            begin()
            val service = newService()
            microphoneAllowed = false
            withContext(Dispatchers.Main.immediate) { service.onStartCommand(Intent(), 0, 1) }
            assertCloses(failed = true)
            assertNull(app.calls.spec)
            assertNoMedia(service)
        }
    }

    @Test fun laterForegroundAdmissionFailureUsesFailedFinalizeAndDeleteWithoutOpeningMedia() = runBlocking {
        fixture {
            begin()
            app.auth.write(auth)
            val service = newService()
            rejectNextForeground = true
            // A non-START command skips only the initial handshake, then runs real ownership/permission admission.
            withContext(Dispatchers.Main.immediate) { service.onStartCommand(Intent(), 0, 1) }
            assertEquals(1, foregroundRejections)
            assertSame(service, app.calls.service)
            assertCloses(failed = true)
            assertTrue(requests.single { it.path.endsWith("/finalize") }.body.getBoolean("failed"))
            assertNoMedia(service)
        }
    }

    @Test fun repeatedStartForegroundFailureAlsoClosesAnAlreadyOwnedServiceAsFailed() = runBlocking {
        fixture {
            begin()
            val service = newService()
            setField(service, "spec", app.calls.spec)
            app.calls.spec = null
            app.calls.service = service
            rejectNextForeground = true
            withContext(Dispatchers.Main.immediate) { service.onStartCommand(Intent().setAction(BeaconCallService.START), 0, 1) }
            assertCloses(failed = true)
            assertTrue(requests.single { it.path.endsWith("/finalize") }.body.getBoolean("failed"))
            assertNoMedia(service)
        }
    }

    @Test fun foregroundRejectionAfterPendingHangupCannotTurnCancellationIntoFailure() = runBlocking {
        fixture {
            begin()
            withContext(Dispatchers.Main.immediate) { app.calls.hangup() }
            assertCloses(failed = false)
            val service = newService()
            rejectNextForeground = true
            withContext(Dispatchers.Main.immediate) { service.onStartCommand(Intent().setAction(BeaconCallService.START), 0, 1) }
            closeServices()
            assertCloses(failed = false)
            assertNoMedia(service)
        }
    }

    private suspend fun fixture(block: suspend Fixture.() -> Unit) {
        check(InstrumentationRegistry.getArguments().getString("native_ui_probe") == "1") { "Explicit synthetic probe flag required" }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val live = target.applicationContext as BeaconApplication
        check(!live.calls.state.value.active && live.calls.spec == null && live.calls.service == null) { "Never interfere with a real call" }
        val fixture = Fixture(target)
        try { fixture.block() } finally { fixture.closeServices(); fixture.client.closeNative() }
    }

    private data class CapturedRequest(val method: String, val path: String, val body: JSONObject)

    private class Fixture(target: Context) {
        val app = BeaconApplication()
        val auth = AuthContext("https://call-admission.invalid", emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard", nativeProvider = "fixture")
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        private val services = mutableListOf<BeaconCallService>()
        var onStart: () -> Unit = {}
        var onReserve: () -> Unit = {}
        var serviceStarts = 0
        var microphoneAllowed = true
        var rejectNextForeground = false
        var foregroundRejections = 0
        private val prefs = memoryPreferences()
        private val context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = app
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "auth-private") { "Unexpected persistent storage access: $name" }
                return prefs
            }
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == Manifest.permission.RECORD_AUDIO) {
                    if (microphoneAllowed) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                } else super.checkPermission(permission, pid, uid)
            override fun startForegroundService(service: Intent): ComponentName {
                check(service.component?.className == BeaconCallService::class.java.name)
                serviceStarts++
                onStart()
                return checkNotNull(service.component)
            }
            override fun getSystemService(name: String): Any? {
                if (name == Context.NOTIFICATION_SERVICE && rejectNextForeground) {
                    rejectNextForeground = false
                    foregroundRejections++
                    // Reject notification setup inside the real foreground try/catch, before any platform promotion.
                    throw SecurityException("Synthetic foreground admission rejection")
                }
                return super.getSystemService(name)
            }
        }
        val client = BeaconClient(auth, null, persistContext = false, configureHttp = { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                check(request.url.scheme == "https" && request.url.host == "call-admission.invalid")
                val buffer = Buffer()
                request.body?.writeTo(buffer)
                val text = buffer.readUtf8()
                check(!text.contains("synthetic-unused-provider-key")) { "Voice credentials reached Hermes" }
                val body = if (text.isBlank()) JSONObject() else JSONObject(text)
                val path = request.url.encodedPath
                requests += CapturedRequest(request.method, path, body)
                val receipt = when {
                    request.method == "POST" && path == "/api/plugins/beacon/mobile/calls" -> {
                        onReserve()
                        json("call_id" to "synthetic-call", "conversation_id" to "synthetic-chat", "profile" to "default",
                            "provider" to "gemini", "transport" to "gemini_live_direct")
                    }
                    request.method == "DELETE" && path == "/api/plugins/beacon/mobile/calls/synthetic-call" -> json("closed" to true)
                    request.method == "POST" && path == "/api/plugins/beacon/mobile/calls/synthetic-call/finalize" ->
                        json("closed" to true, "transcript_status" to "saved")
                    else -> error("Unexpected admission request: ${request.method} $path")
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Synthetic admission receipt")
                    .body(receipt.toString().toResponseBody("application/json".toMediaType())).build()
            }
        })

        init {
            setField(app, "mBase", context, ContextWrapper::class.java)
            // AuthStore uses only in-memory preferences and this disposable key, never Android Keystore.
            val cipher = AppCipher()
            setField(cipher, "key\$delegate", lazyOf(SecretKeySpec(ByteArray(32), "AES")))
            setField(app, "cipher\$delegate", lazyOf(cipher))
        }

        suspend fun begin() = withContext(Dispatchers.Main.immediate) {
            // Exercise the reservation/start catch without consulting the owner's VoiceKeyStore.
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val method = CallController::class.java.declaredMethods.single { it.name == "start" && it.parameterTypes.size == 5 }
                method.isAccessible = true
                try {
                    method.invoke(app.calls, client, null, "synthetic-chat",
                        VoiceSessionCredentials("gemini", "synthetic-unused-provider-key", "synthetic-model", "synthetic-voice", "balanced"), continuation)
                } catch (error: InvocationTargetException) { throw error.targetException }
            }
        }

        fun newService(): BeaconCallService = BeaconCallService().also {
            setField(it, "mBase", context, ContextWrapper::class.java)
            setField(it, "mApplication", app, Service::class.java)
            setField(it, "mClassName", BeaconCallService::class.java.name, Service::class.java)
            val manager = Service::class.java.getDeclaredField("mActivityManager").apply { isAccessible = true }
            manager.set(it, Proxy.newProxyInstance(manager.type.classLoader, arrayOf(manager.type)) { _, method, _ ->
                when (method.name) {
                    "setServiceForeground" -> null
                    "stopServiceToken" -> true
                    else -> error("Unexpected platform service operation: ${method.name}")
                }
            })
            services += it
        }

        suspend fun assertCloses(failed: Boolean, count: Int = 1) {
            withTimeout(5000) {
                while (requests.count { it.method == "DELETE" } < count || client.http.dispatcher.runningCallsCount() != 0) delay(10)
            }
            val closes = requests.filter { it.method == "DELETE" }
            assertEquals(count, closes.size)
            closes.forEach {
                assertEquals("/api/plugins/beacon/mobile/calls/synthetic-call", it.path)
                InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                    putString("beacon_admission_close", JSONObject().put("expected_failed", failed).put("body", it.body).toString())
                })
                if (failed) assertEquals("Failed admission must reach the serialized DELETE", true, it.body.get("failed"))
                else assertTrue("Cancellation must remain nonfailed", !it.body.has("failed") || it.body.opt("failed") == false)
            }
        }

        suspend fun closeServices() {
            withContext(Dispatchers.Main.immediate) {
                services.filterNot { it.destroyed.isCompleted }.forEach { it.onDestroy() }
                app.calls.hangup()
            }
            withTimeout(5000) { services.forEach { it.destroyed.await() } }
        }
    }

    companion object {
        private fun setField(owner: Any, name: String, value: Any?, type: Class<*> = owner.javaClass) {
            type.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }

        private fun assertNoMedia(service: BeaconCallService) {
            listOf("pcm", "visual", "directVoice", "connectionJob", "telecomJob").forEach { name ->
                assertNull("Admission failure allocated $name", BeaconCallService::class.java.getDeclaredField(name).apply { isAccessible = true }.get(service))
            }
        }

        private fun memoryPreferences(): SharedPreferences {
            val values = ConcurrentHashMap<String, String>()
            val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString" -> { values[args!![0] as String] = args[1] as String; proxy }
                    "commit" -> true
                    else -> error("Unexpected preference edit: ${method.name}")
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) {
                    "getString" -> values[args!![0] as String] ?: args[1]
                    "edit" -> editor
                    else -> error("Unexpected preference read: ${method.name}")
                }
            } as SharedPreferences
        }
    }
}
