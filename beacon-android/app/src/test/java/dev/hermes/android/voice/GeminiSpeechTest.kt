package dev.hermes.android.voice

import dev.hermes.android.data.VoiceSessionCredentials
import kotlinx.coroutines.*
import okhttp3.WebSocket
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class GeminiSpeechTest {
    private fun credentials() = VoiceSessionCredentials("gemini", "fixture-key", "models/gemini-3.8-live", "Aoede", "balanced")
    @Test fun requestSeparatesLiteralTextStyleAndListeningModel() {
        val text = "He said \"hi\".\nRead C:\\work\\file and 2+3 exactly."
        val credentials = credentials(); val request = GeminiSpeech.request(credentials, text)
        val buffer = okio.Buffer(); request.body!!.writeTo(buffer); val body = JSONObject(buffer.readUtf8())
        assertEquals("generativelanguage.googleapis.com", request.url.host)
        assertEquals("/v1beta/models/gemini-3.8-flash-lite-tts:generateContent", request.url.encodedPath)
        assertEquals("fixture-key", request.header("x-goog-api-key")); assertNull(request.header("Cookie"))
        assertEquals("models/gemini-3.8-live", credentials.model)
        val part = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
        assertEquals(text, part.getString("text")); assertEquals("natural", part.getJSONObject("speech_metadata").getString("style"))
        assertFalse(body.has("tools")); assertFalse(body.has("systemInstruction"))
        assertEquals("AUDIO_L16", body.getJSONObject("generationConfig").getJSONObject("responseFormat").getJSONObject("audio").getString("mimeType"))
        assertEquals("Aoede", body.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig").getString("voice"))
    }
    @Test fun unaryPcmCanExceedAWebSocketFrameButNotTheAggregateBudget() {
        val pcm = ByteArray(240000) { 1 }; val encoded = java.util.Base64.getEncoder().encodeToString(pcm)
        assertArrayEquals(pcm, GeminiSpeech.pcm(SpeechHttpFixture.pcm(encoded)))
        val samples = byteArrayOf(82, 73, 70, 70)
        assertArrayEquals(samples, GeminiSpeech.pcm(SpeechHttpFixture.pcm(java.util.Base64.getEncoder().encodeToString(samples))))
        val huge = java.util.Base64.getEncoder().encodeToString(ByteArray(GeminiSpeech.MAX_PCM_BYTES + 2))
        assertThrows(IllegalArgumentException::class.java) { GeminiSpeech.pcm(SpeechHttpFixture.pcm(huge)) }
    }
    @Test fun blockedPartialNonAudioAndInvalidFormatsNeverBecomeSpeech() {
        val invalid = listOf(
            SpeechHttpFixture.pcm(finish = "MAX_TOKENS"), SpeechHttpFixture.pcm(data = ""), SpeechHttpFixture.pcm(data = "AA=="),
            SpeechHttpFixture.pcm(mime = "audio/wav"), SpeechHttpFixture.pcm(mime = "audio/l16;rate=16000"),
            SpeechHttpFixture.pcm(mime = "audio/l16;rate=24000;channels=2"), SpeechHttpFixture.pcm(data = "UklGRg==", mime = "audio/wav"),
            SpeechHttpFixture.pcm().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")),
        )
        for (value in invalid) assertThrows(RuntimeException::class.java) { GeminiSpeech.pcm(value) }
        val tool = SpeechHttpFixture.pcm()
        tool.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .put("functionCall", JSONObject().put("name", "not-permitted"))
        assertThrows(IllegalArgumentException::class.java) { GeminiSpeech.pcm(tool) }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(10) }
    @Test(timeout = 10000) fun completeLiteralResponsePlaysOnceAndCaptionsOnlyAfterPresentation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val calls = SpeechHttpFixture()
        val audio = CopyOnWriteArrayList<ByteArray>(); val events = CopyOnWriteArrayList<JSONObject>()
        val renderer = NativeSpeechRenderer(credentials(), WebSocket.Factory { _, _ -> error("Gemini synthesis must not open Live") },
            scope, { true }, { audio += it }, { events += it }, {}, calls)
        try {
            assertTrue(renderer.say("Literal native reply.")); await { calls.requests.size == 1 }
            assertEquals("Literal native reply.", calls.text()); assertTrue(audio.isEmpty())
            calls.respond(); await { audio.size == 1 }
            assertTrue(events.none { it.optString("role") == "assistant" })
            renderer.played(audio.single().size); await { events.any { it.optString("text") == "Literal native reply." } }
            calls.fail(java.io.IOException("Synthetic completed call cancellation"))
            assertEquals(1, calls.requests.size)
            assertTrue(events.none { it.optString("stage") == "provider_error" })
        } finally { renderer.close(); scope.cancel() }
    }
    @Test(timeout = 10000) fun temporaryHttpFailureRetriesOnlyTheUnplayedReadoutAndOwnershipStopsLateAudio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default); val calls = SpeechHttpFixture()
        val owner = AtomicBoolean(true); val audio = CopyOnWriteArrayList<ByteArray>(); val events = CopyOnWriteArrayList<JSONObject>()
        val renderer = NativeSpeechRenderer(credentials(), WebSocket.Factory { _, _ -> error("No Live synthesis") }, scope, owner::get,
            { audio += it }, { events += it }, {}, calls)
        try {
            renderer.say("Verified reply."); await { calls.requests.size == 1 }
            calls.respond(JSONObject(), 503); await { calls.requests.size == 2 }
            assertEquals(calls.text(0), calls.text(1)); assertTrue(audio.isEmpty())
            owner.set(false); renderer.interrupt(); calls.respond(index = 1); delay(100)
            assertTrue(audio.isEmpty()); assertTrue(events.none { it.optString("role") == "assistant" })
        } finally { renderer.close(); scope.cancel() }
    }

    private class Fixture : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = SpeechHttpFixture()
        val audio = CopyOnWriteArrayList<ByteArray>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val renderer = NativeSpeechRenderer(VoiceSessionCredentials("gemini", "fixture-key", "models/gemini-3.8-live", "Aoede", "balanced"),
            WebSocket.Factory { _, _ -> error("No Live synthesis") }, scope, { true }, { audio += it }, { events += it }, {}, calls)
        override fun close() { renderer.close(); scope.cancel() }
    }

    @Test(timeout = 12000) fun httpStatusAndCertificateFailuresHaveBoundedRetriesAndPrivateDiagnostics() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) Fixture().use { f ->
            f.renderer.say("Verified reply."); await { f.calls.requests.size == 1 }
            f.calls.respond(JSONObject().put("error", "SYNTHETIC_PRIVATE_DETAIL"), status)
            val count = if (status in setOf(429, 503)) 2 else 1
            if (count == 2) {
                await { f.calls.requests.size == 2 }; f.calls.respond(JSONObject(), status)
            }
            await { f.events.any { it.optString("type") == "warning" } }
            assertEquals(count, f.calls.requests.size); assertTrue(f.audio.isEmpty())
            assertTrue(f.events.any { it.optString("error_code") == "http_$status" })
            assertFalse(f.events.toString().contains("SYNTHETIC_PRIVATE_DETAIL"))
        }
        Fixture().use { f ->
            f.renderer.say("Verified reply."); await { f.calls.requests.size == 1 }
            f.calls.fail(javax.net.ssl.SSLPeerUnverifiedException("SYNTHETIC_PRIVATE_CERTIFICATE_DETAIL"))
            await { f.events.any { it.optString("type") == "warning" } }
            assertEquals(1, f.calls.requests.size); assertTrue(f.audio.isEmpty())
            assertTrue(f.events.any { it.optString("error_code") == "tls" })
            assertFalse(f.events.toString().contains("SYNTHETIC_PRIVATE_CERTIFICATE_DETAIL"))
        }
    }

    private class TrackedBody(bytes: ByteArray, private val beforeRead: (() -> Unit)? = null) : ResponseBody() {
        var closed = false
            private set
        private val input = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long { beforeRead?.invoke(); return super.read(sink, byteCount) }
            override fun close() { closed = true; super.close() }
        }.buffer()
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength() = -1L
        override fun source() = input
    }

    @Test(timeout = 10000) fun malformedOrOversizedBodiesCloseWithoutPlayingOrRetrying() = runBlocking {
        val values = listOf("{", SpeechHttpFixture.pcm(finish = "MAX_TOKENS").toString(), SpeechHttpFixture.pcm(data = "").toString(),
            " ".repeat(GeminiSpeech.MAX_JSON_BYTES + 1))
        for (value in values) Fixture().use { f ->
            f.renderer.say("Verified reply."); await { f.calls.requests.size == 1 }
            val body = TrackedBody(value.toByteArray()); f.calls.requests.single().respondBody(body)
            await { f.events.any { it.optString("type") == "warning" } }
            assertTrue(body.closed); assertEquals(1, f.calls.requests.size); assertTrue(f.audio.isEmpty())
            assertTrue(f.events.any { it.optString("turn_id") == "speech-invalid-audio" })
        }
    }

    @Test(timeout = 10000) fun interruptionDuringBodyReadClosesItAndCannotCompleteTheNextReadout() = runBlocking {
        Fixture().use { f ->
            f.renderer.say("Old reply."); await { f.calls.requests.size == 1 }
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            val body = TrackedBody(SpeechHttpFixture.pcm().toString().toByteArray()) {
                entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            }
            val old = f.calls.requests.single()
            val reading = launch(Dispatchers.IO) { old.respondBody(body) }
            try {
                assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
                f.renderer.interrupt(); assertTrue(old.canceled)
            } finally { release.countDown() }
            reading.join(); assertTrue(body.closed); assertTrue(f.audio.isEmpty())
            f.renderer.say("New reply."); await { f.calls.requests.size == 2 }
            old.respond(SpeechHttpFixture.pcm()); assertTrue(f.audio.isEmpty())
            f.calls.respond(); await { f.audio.size == 1 }
            assertEquals("New reply.", f.calls.text()); f.renderer.played(f.audio.single().size)
            await { f.events.any { it.optString("text") == "New reply." } }
            assertTrue(f.events.none { it.optString("text") == "Old reply." || it.optString("type") == "warning" })
        }
    }
}
