package dev.hermes.android.voice

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.app.ActivityManager
import android.content.Context
import dev.hermes.android.BeaconApplication
import dev.hermes.android.data.VoiceKeyStore
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real provider callbacks for synthetic speech; no keys, audio or native replies are retained. */
class NativeSpeechRenderLiveTest {
    @Test(timeout = 180_000) fun geminiReadsTheSuppliedReply() = exercise("gemini")
    @Test(timeout = 180_000) fun grokReadsTheSuppliedReply() = exercise("grok")
    @Test(timeout = 180_000) fun geminiRecoversAnUnplayedSpeechDisconnect() = exercise("gemini", true)
    @Test(timeout = 180_000) fun grokRecoversAnUnplayedSpeechDisconnect() = exercise("grok", true)
    @Test(timeout = 180_000) fun geminiRepeatedGreetingCompletesReadout() = exercise("gemini", repeatedGreeting = true)

    private fun exercise(provider: String, disconnectFirst: Boolean = false, repeatedGreeting: Boolean = false) = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        require(args.getString("beaconNoHostAudio") == "1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug"))
        @Suppress("DEPRECATION")
        val services = (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getRunningServices(Int.MAX_VALUE)
        require(services.none { it.service.className.endsWith(".BeaconCallService") })
        val credentials = requireNotNull(VoiceKeyStore(app).session(provider))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val http = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).pingInterval(20, TimeUnit.SECONDS).build()
        val events = CopyOnWriteArrayList<JSONObject>()
        val receivedBytes = AtomicLong()
        val requests = AtomicInteger()
        val disconnectPending = AtomicBoolean(disconnectFirst)
        val disconnected = AtomicBoolean()
        val prematureAudio = AtomicBoolean()
        val texts = if (repeatedGreeting) List(4) { "Good afternoon, Maya. What can I do for you?" }
        else listOf("Verified reply.", "Good afternoon, Maya. What can I do for you?", "I'm here.", "The result is 391.",
            "The label is \"ready\". Read C:\\work\\file.") +
            if (provider == "gemini") listOf("The text contains [laughing] and two plus three.") else emptyList()
        val cases = JSONArray()
        val factory = WebSocket.Factory { request, listener ->
            check(provider != "gemini") { "Gemini readout must use the dedicated HTTP API" }
            requests.incrementAndGet()
            http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) = listener.onOpen(ws, response)
                override fun onMessage(ws: WebSocket, raw: String) {
                    val frame = JSONObject(raw)
                    listener.onMessage(ws, raw)
                    if (frame.optString("type") in setOf("response.output_audio.delta", "response.audio.delta") && disconnectPending.compareAndSet(true, false)) {
                        prematureAudio.set(receivedBytes.get() != 0L); disconnected.set(true); ws.cancel()
                    }
                }
                override fun onMessage(ws: WebSocket, bytes: ByteString) = onMessage(ws, bytes.utf8())
                override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) = listener.onFailure(ws, error, response)
                override fun onClosing(ws: WebSocket, code: Int, reason: String) = listener.onClosing(ws, code, reason)
                override fun onClosed(ws: WebSocket, code: Int, reason: String) = listener.onClosed(ws, code, reason)
            })
        }
        val calls = Call.Factory { request ->
            check(provider == "gemini" && request.url.encodedPath.endsWith("/${GeminiSpeech.MODEL}:generateContent"))
            val real = http.newCall(request)
            object : Call by real {
                override fun enqueue(callback: Callback) {
                    requests.incrementAndGet()
                    real.enqueue(object : Callback {
                        override fun onFailure(call: Call, error: IOException) = callback.onFailure(call, error)
                        override fun onResponse(call: Call, response: Response) {
                            if (!disconnectPending.compareAndSet(true, false)) { callback.onResponse(call, response); return }
                            val body = requireNotNull(response.body)
                            val broken = object : ResponseBody() {
                                private val input = object : ForwardingSource(body.source()) {
                                    private var read = 0L
                                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                                        if (read >= 4096) {
                                            prematureAudio.set(receivedBytes.get() != 0L); disconnected.set(true)
                                            throw IOException("Synthetic mid-body readout disconnect")
                                        }
                                        return super.read(sink, minOf(byteCount, 4096)).also { if (it > 0) read += it }
                                    }
                                }.buffer()
                                override fun contentType() = body.contentType()
                                override fun contentLength() = body.contentLength()
                                override fun source() = input
                            }
                            callback.onResponse(call, response.newBuilder().body(broken).build())
                        }
                    })
                }
            }
        }
        lateinit var renderer: NativeSpeechRenderer
        renderer = NativeSpeechRenderer(credentials, factory, scope, { true }, { pcm ->
            receivedBytes.addAndGet(pcm.size.toLong()); renderer.played(pcm.size)
        }, { events += it }, {}, calls)
        try {
            for (text in texts) {
                // Exercise correctness without turning the acceptance batch into a provider quota stress test.
                if (provider == "gemini") delay(10_000)
                val start = events.size
                val beforeBytes = receivedBytes.get()
                val beforeRequests = requests.get()
                val started = System.nanoTime()
                assertTrue(renderer.say(text))
                withTimeout(75_000) {
                    while (events.drop(start).none { it.optString("type") in setOf("warning", "turn_complete") }) delay(20)
                }
                val observed = events.drop(start)
                cases.put(JSONObject().put("synthetic_input", text).put("delivered_pcm_bytes", receivedBytes.get() - beforeBytes)
                    .put("render_requests", requests.get() - beforeRequests).put("render_ms", (System.nanoTime() - started) / 1_000_000)
                    .put("warning", observed.any { it.optString("type") == "warning" }))
                assertFalse("Real provider readout failed; inspect sanitized diagnostics", observed.any { it.optString("type") == "warning" })
                assertTrue(receivedBytes.get() > beforeBytes)
                assertTrue(observed.any { it.optString("type") == "transcript" && it.optString("text") == text })
            }
            if (disconnectFirst) {
                assertTrue("The fault must actually interrupt a real response", disconnected.get())
                assertFalse("Incomplete first-attempt audio must not be presented", prematureAudio.get())
                assertEquals(2, cases.getJSONObject(0).getInt("render_requests"))
                assertTrue(events.any { it.optString("turn_id") == "speech-transport" && it.optInt("attempt") == 0 })
            }
        } finally {
            renderer.close(); scope.cancel()
            val suffix = if (repeatedGreeting) "-greeting-series" else if (disconnectFirst) "-recovery" else ""
            File(app.filesDir, "speech-render-$provider$suffix.json").writeText(JSONObject()
                .put("provider", provider).put("cases", cases).put("delivered_pcm_bytes", receivedBytes.get())
                .put("listening_model", credentials.model).put("synthesis_model", if (provider == "gemini") GeminiSpeech.MODEL else credentials.model)
                .put("voice", credentials.voice).put("synthetic_disconnect", disconnected.get()).put("premature_audio", prematureAudio.get())
                .put("diagnostics", JSONArray(events.filter { it.optString("type") == "media_diagnostic" }))
                .toString(), Charsets.UTF_8)
            withContext(NonCancellable + Dispatchers.IO) {
                http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown()
            }
        }
    }
}
