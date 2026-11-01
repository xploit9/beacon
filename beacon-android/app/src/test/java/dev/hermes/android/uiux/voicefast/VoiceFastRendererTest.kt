package dev.hermes.android.uiux.voicefast

import dev.hermes.android.core.json
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

/** Greeting prefetch and one-ahead rendering keep every presentation fence of the speech renderer. */
class VoiceFastRendererTest {
    private class Speech(startReady: Boolean = true, openingMs: Long = 8000) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val tts = SpeechHttpFixture()
        val ready = AtomicBoolean(startReady)
        val owner = AtomicBoolean(true)
        val audio = CopyOnWriteArrayList<ByteArray>()
        val events = CopyOnWriteArrayList<JSONObject>()
        val endings = CopyOnWriteArrayList<Boolean>()
        @Volatile var autoPlay = true
        lateinit var renderer: NativeSpeechRenderer
        init {
            renderer = NativeSpeechRenderer(
                VoiceSessionCredentials("gemini", "fixture-key", defaultVoiceModel("gemini"), defaultVoiceName("gemini"), "balanced"),
                WebSocket.Factory { _, _ -> error("Gemini speech uses HTTP") }, scope, { owner.get() && ready.get() },
                { bytes -> audio += bytes; if (autoPlay) renderer.played(bytes.size) }, { events += it }, {}, tts,
                renderable = { owner.get() }, openingEnded = { endings += it }, openingRenderMs = openingMs,
            )
        }
        fun captions(text: String) = events.count { it.optString("type") == "transcript" && it.optString("text") == text }
        fun warned() = events.any { it.optString("type") == "warning" }
        fun close() { renderer.close(); scope.cancel() }
    }

    private suspend fun waitFor(predicate: () -> Boolean): Boolean = withTimeoutOrNull(2500) {
        while (!predicate()) delay(10)
        true
    } == true

    @Test(timeout = 8000) fun prefetchIsNeverPresentedBeforeReadyAndTheOpeningUsesIt() = runBlocking {
        val f = Speech(startReady = false)
        try {
            assertTrue(f.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            f.tts.respond()
            delay(200)
            assertTrue("A finished prefetch must wait for readiness", f.audio.isEmpty())
            assertFalse("Nothing is queued before readiness", f.renderer.say("Good evening, Maya.", opening = true))
            f.ready.set(true)
            assertTrue(f.renderer.say("Good evening, Maya.", opening = true))
            assertTrue(waitFor { f.captions("Good evening, Maya.") == 1 })
            assertEquals(1, f.audio.size)
            assertEquals("The prefetched render is used once, never rendered again", 1, f.tts.requests.size)
            assertEquals(listOf(true), f.endings)
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun interruptAndCloseDiscardThePrefetch() = runBlocking {
        val f = Speech()
        try {
            assertTrue(f.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            f.renderer.interrupt()
            assertTrue("Interrupt cancels the prefetch", waitFor { f.tts.requests[0].canceled })
            assertTrue(f.renderer.say("Good evening, Maya.", opening = true))
            assertTrue("A new generation renders again", waitFor { f.tts.requests.size == 2 })
            f.tts.respond(index = 0)
            delay(150)
            assertTrue("The discarded render never plays", f.audio.isEmpty())
            f.tts.respond(index = 1)
            assertTrue(waitFor { f.captions("Good evening, Maya.") == 1 })
            assertEquals(1, f.audio.size)
        } finally { f.close() }
        val closed = Speech()
        try {
            assertTrue(closed.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { closed.tts.requests.size == 1 })
            closed.renderer.close()
            assertTrue("Close cancels the prefetch", waitFor { closed.tts.requests[0].canceled })
            closed.tts.respond(index = 0)
            delay(150)
            assertTrue(closed.audio.isEmpty())
        } finally { closed.close() }
    }

    @Test(timeout = 8000) fun mismatchingTextIsRenderedAgainAndTheOldPrefetchDiscarded() = runBlocking {
        val f = Speech()
        try {
            assertTrue(f.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            assertTrue("Same text keeps the running prefetch", f.renderer.prefetch("Good evening, Maya."))
            delay(50); assertEquals(1, f.tts.requests.size)
            assertTrue(f.renderer.prefetch("Good morning, Maya."))
            assertTrue(waitFor { f.tts.requests.size == 2 && f.tts.requests[0].canceled })
            assertEquals("Good morning, Maya.", f.tts.text(1))
            assertTrue(f.renderer.say("I'm here.", opening = true))
            assertTrue("A said line that differs from the prefetch renders its own text", waitFor { f.tts.requests.size == 3 && f.tts.requests[1].canceled })
            assertEquals("I'm here.", f.tts.text(2))
            f.tts.respond(index = 1); delay(100); assertTrue(f.audio.isEmpty())
            f.tts.respond(index = 2)
            assertTrue(waitFor { f.captions("I'm here.") == 1 })
            assertEquals(0, f.captions("Good morning, Maya."))
        } finally { f.close() }
    }

    @Test(timeout = 8000) fun prefetchedAudioKeepsGeminiValidationAndTheOneRetryRule() = runBlocking {
        val f = Speech(startReady = false)
        try {
            assertTrue(f.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            f.tts.respond(SpeechHttpFixture.pcm(finish = "MAX_TOKENS"))
            delay(150)
            f.ready.set(true)
            assertTrue(f.renderer.say("Good evening, Maya.", opening = true))
            assertTrue(waitFor { f.warned() })
            assertTrue("Invalid prefetched audio never plays", f.audio.isEmpty())
            assertEquals("Rejected audio is not rendered again", 1, f.tts.requests.size)
            assertTrue(f.events.any { it.optString("turn_id") == "speech-invalid-audio" })
            assertEquals(listOf(false), f.endings)
        } finally { f.close() }
        val busy = Speech(startReady = false)
        try {
            assertTrue(busy.renderer.prefetch("Good evening, Maya."))
            assertTrue(waitFor { busy.tts.requests.size == 1 })
            busy.tts.respond(json("error" to json("code" to 503)), 503)
            assertTrue("A transport failure retries once inside the prefetch", waitFor { busy.tts.requests.size == 2 })
            busy.tts.respond(json("error" to json("code" to 503)), 503)
            delay(150)
            busy.ready.set(true)
            assertTrue(busy.renderer.say("Good evening, Maya.", opening = true))
            assertTrue(waitFor { busy.events.any { it.optString("message").contains("speech connection dropped") } })
            delay(100)
            assertEquals("No third attempt", 2, busy.tts.requests.size)
            assertTrue(busy.audio.isEmpty())
        } finally { busy.close() }
    }

    @Test(timeout = 8000) fun openingRenderDeadlineEndsTheGreetingWithTheExistingWarning() = runBlocking {
        val f = Speech(openingMs = 300)
        try {
            assertTrue(f.renderer.say("Good evening, Maya.", opening = true))
            assertTrue(waitFor { f.tts.requests.size == 1 })
            assertTrue(waitFor { f.endings.isNotEmpty() })
            assertEquals(listOf(false), f.endings)
            assertTrue(f.events.any { it.optString("message") == "Voice could not safely read the reply. Open the attached conversation." })
            val timeout = f.events.single { it.optString("turn_id") == "speech-timeout" }
            assertEquals("timeout", timeout.getString("error_code"))
            assertTrue("The late render is cancelled", f.tts.requests[0].canceled)
            f.tts.respond(index = 0); delay(150)
            assertTrue(f.audio.isEmpty())
            assertTrue("Later lines still render", f.renderer.say("Still here."))
            assertTrue(waitFor { f.tts.requests.size == 2 })
        } finally { f.close() }
    }

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

    @Test(timeout = 8000) fun onlyOpeningBytesAreReportedAsTheGreeting() = runBlocking {
        val f = Speech()
        try {
            f.autoPlay = false
            assertTrue(f.renderer.say("Good evening, Maya.", opening = true))
            assertTrue(waitFor { f.tts.requests.size == 1 }); f.tts.respond()
            assertTrue(waitFor { f.audio.size == 1 })
            assertTrue(f.renderer.played(4000))
            assertTrue(f.renderer.played(8000))
            assertTrue(waitFor { f.endings == listOf(true) })
            assertTrue(f.renderer.say("A verified reply."))
            assertTrue(waitFor { f.tts.requests.size == 2 }); f.tts.respond(index = 1)
            assertTrue(waitFor { f.audio.size == 2 })
            assertFalse(f.renderer.played(12000))
            assertEquals(listOf(true), f.endings)
        } finally { f.close() }
    }
}
