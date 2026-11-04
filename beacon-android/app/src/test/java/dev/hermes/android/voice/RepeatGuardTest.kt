package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner, 4 Nov 2026: the call said "I'll draft that email to Carlos…" three times in one turn, and stopped working when he hung up. */
class RepeatGuardTest {
    private val line = "I'll draft that email to Carlos asking about arriving two weeks early."

    @Test fun aSentenceSaidAgainBeforeTheOwnerSpeaksIsHeldBack() {
        val guard = RepeatGuard()
        assertEquals(line, guard.words(line))
        // The repeat arrives in pieces, as Gemini sends it; none of it is shown, and the guard reports it is repeating.
        assertEquals("", guard.words("I'll draft that email to "))
        assertTrue(guard.repeating)
        assertEquals("", guard.words("Carlos asking about arriving two weeks early."))
        assertFalse(guard.repeating)
        // A new sentence after it comes through.
        assertEquals("The draft is ready.", guard.words("The draft is ready."))
    }

    @Test fun wordsThatTurnOutDifferentComeBack() {
        val guard = RepeatGuard()
        guard.words(line)
        assertEquals("", guard.words("I'll draft that email to "))
        assertEquals("Joy instead.", guard.words("Joy instead."))
        assertFalse(guard.repeating)
    }

    @Test fun theOwnerSpeakingLetsTheSentenceBeSaidAgain() {
        val guard = RepeatGuard()
        guard.words(line); guard.turnEnded()
        guard.ownerSpoke()
        assertEquals(line, guard.words(line))
    }

    @Test fun shortRepliesAreNeverHeldBack() {
        val guard = RepeatGuard()
        assertEquals("Okay.", guard.words("Okay."))
        assertEquals("Okay.", guard.words("Okay."))
    }

    @Test fun handingWorkToHermesCanAskForTheCallBackInOneStep() {
        val setup = GeminiWire.setup("model", "", null, JSONObject(), emptySet(), "changes").getJSONObject("setup")
        val declarations = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val work = (0 until declarations.length()).map(declarations::getJSONObject).single { it.getString("name") == "hermes_work" }
        assertEquals("BOOLEAN", work.getJSONObject("parameters").getJSONObject("properties").getJSONObject("callback").getString("type"))
        assertTrue(GeminiWire.HERMES_ON_THE_CALL.contains("callback true"))
        assertTrue(callToolHint("hermes_work", JSONObject().put("status", "started").put("job_id", "j").put("callback", "scheduled")).contains("hang up"))
        assertTrue(callToolHint("hermes_callback", JSONObject().put("status", "not_sent")).contains("hermes_work with callback true"))
    }
}
