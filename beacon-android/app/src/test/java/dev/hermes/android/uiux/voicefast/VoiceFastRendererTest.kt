package dev.hermes.android.uiux.voicefast

import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import dev.hermes.android.voice.NativeSpeechRenderer
import dev.hermes.android.voice.SpeechHttpFixture
import kotlinx.coroutines.*
import okhttp3.WebSocket
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** One-ahead rendering of Hermes replies keeps every presentation fence of the speech renderer. The call greeting is the live model's own (see voiceconnect). */
class VoiceFastRendererTest {
    private class Speech(startReady: Boolean = true) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tts = SpeechHttpFixture()
        val ready = AtomicBoolean(startReady)
        val owner = AtomicBoolean(true)
        val audio = CopyOnWriteArrayList<ByteArray>()
        val events = CopyOnWriteArrayList<JSONObject>()
        @Volatile var autoPlay = true
        lateinit var renderer: NativeSpeechRenderer
        init {
            renderer = NativeSpeechRenderer(
                VoiceSessionCredentials("gemini", "fixture-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced"),
                WebSocket.Factory { _, _ -> error("Gemini speech uses HTTP") }, scope, { owner.get() && ready.get() },
                { bytes -> audio += bytes; if (autoPlay) renderer.played(bytes.size) }, { events += it }, {}, tts,
            )
        }
        fun captions(text: String) = events.count { it.optString("type") == "transcript" && it.optString("text") == text }
        fun close() { renderer.close(); scope.cancel() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(2500) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test(timeout = 8000) fun theNextLineRendersWhileThisOnePlaysAndInterruptDiscardsIt() = runBlocking {
        val f = Speech()
        try {
            f.autoPlay = false
            assertTrue(f.renderer.say("First line."))
            assertTrue(f.renderer.say("Second line."))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            f.tts.respond(index = 0)
            assertTrue(waitFor { f.audio.size == 1 })
            assertTrue("The following line renders while the first plays", waitFor { f.tts.requests.size == 2 })
            assertEquals("Second line.", f.tts.text(1))
            f.tts.respond(index = 1)
            delay(150)
            assertEquals("Nothing plays ahead of the presented line", 1, f.audio.size)
            assertEquals(0, f.captions("First line."))
            f.renderer.played(12000)
            assertTrue(waitFor { f.captions("First line.") == 1 && f.audio.size == 2 })
            f.renderer.played(12000)
            assertTrue(waitFor { f.captions("Second line.") == 1 })
            assertEquals("Rendered once each", 2, f.tts.requests.size)
        } finally { f.close() }
        val cut = Speech()
        try {
            cut.autoPlay = false
            assertTrue(cut.renderer.say("First line.")); assertTrue(cut.renderer.say("Second line."))
            assertTrue(waitFor { cut.tts.requests.size == 1 }); cut.tts.respond(index = 0)
            assertTrue(waitFor { cut.tts.requests.size == 2 })
            cut.renderer.interrupt()
            assertTrue("Interrupt discards the line rendered ahead", waitFor { cut.tts.requests[1].canceled })
            cut.tts.respond(index = 1); cut.renderer.played(24000); delay(150)
            assertEquals(1, cut.audio.size)
            assertEquals(0, cut.captions("Second line."))
        } finally { cut.close() }
    }
}
