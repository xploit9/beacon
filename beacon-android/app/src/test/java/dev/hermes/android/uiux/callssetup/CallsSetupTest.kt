package dev.hermes.android.uiux.callssetup

import dev.hermes.android.incomingReasonFromHistory
import dev.hermes.android.ui.CallDirection
import dev.hermes.android.ui.CallHistoryEntry
import dev.hermes.android.ui.CallHistoryItem
import dev.hermes.android.ui.SETUP_CALL_PAGES
import dev.hermes.android.ui.callDirectionOf
import dev.hermes.android.ui.callRowTitle
import dev.hermes.android.ui.firstUserCallLine
import dev.hermes.android.ui.groupCallAttempts
import dev.hermes.android.ui.isShortAttempt
import dev.hermes.android.ui.setupProgress
import dev.hermes.android.ui.setupStepNumber
import dev.hermes.android.ui.shouldAskCallBubble
import dev.hermes.android.voice.CallTranscript
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsSetupTest {
    private fun call(
        id: String, state: String = "missed", at: Long = 1_000_000, duration: Long? = null, conversation: String = "chat-a",
        reason: String? = null, transcript: Boolean = false, notice: String? = null, direction: String = "incoming",
    ) = CallHistoryEntry(id, direction, state, at, duration, null, conversation, reason, notice, transcript)

    // ---- Call history: title, direction, grouping ----

    @Test fun aCallIsTitledByItsReasonThenByWhatTheUserSaidThenByItsDirection() {
        assertEquals("Check the deploy", callRowTitle("Check the deploy", "Hello there"))
        assertEquals("Hello there", callRowTitle(null, "Hello there"))
        assertEquals("Hello there", callRowTitle("   ", "Hello there"))
        assertNull(callRowTitle(null, null))
        assertNull(callRowTitle("", "  "))
    }

    @Test fun aTitleIsOneLineAndStaysShort() {
        assertEquals("Plan my day tomorrow", callRowTitle(null, "  Plan my\n day   tomorrow "))
        assertEquals(140, callRowTitle("x".repeat(400), null)!!.length)
    }

    @Test fun directionNamesTheCallWhenNothingElseDoes() {
        assertEquals(CallDirection.Missed, callDirectionOf("missed", incoming = true))
        assertEquals(CallDirection.Declined, callDirectionOf("declined", incoming = true))
        assertEquals(CallDirection.Failed, callDirectionOf("failed", incoming = false))
        assertEquals(CallDirection.Incoming, callDirectionOf("completed", incoming = true))
        assertEquals(CallDirection.Outgoing, callDirectionOf("completed", incoming = false))
        assertEquals(CallDirection.InProgress, callDirectionOf("active", incoming = false))
        assertEquals(CallDirection.Incoming, callDirectionOf("active", incoming = true))
    }

    @Test fun theFirstUserLineComesFromThatCallInTurnOrder() {
        val lines = listOf(
            CallTranscript("call-1:assistant:1", "assistant", "Hi, it's Beacon", turnId = "1"),
            CallTranscript("call-1:user:2", "user", "Second thing", turnId = "2"),
            CallTranscript("call-1:user:1", "user", "  ", turnId = "1"),
            CallTranscript("call-1:user:0", "user", "First thing", turnId = "0"),
            CallTranscript("call-2:user:1", "user", "Another call", turnId = "1"),
        )
        assertEquals("First thing", firstUserCallLine("call-1", lines))
        assertEquals("Another call", firstUserCallLine("call-2", lines))
        assertNull(firstUserCallLine("call-3", lines))
        assertNull(firstUserCallLine("call-1", lines.filter { it.role == "assistant" }))
        assertNull(firstUserCallLine("call-1", listOf(CallTranscript("call-1:user:1", "user", "Cut off", interrupted = true))))
    }

    @Test fun repeatedShortAttemptsBecomeOneRowAndEverythingElseKeepsItsOwn() {
        val newestFirst = listOf(
            call("c5", at = 1_000_300), call("c4", at = 1_000_200), call("c3", at = 1_000_100), // three missed attempts within minutes
            call("c2", state = "completed", at = 999_000, duration = 340, transcript = true),   // a real call
            call("c1", state = "completed", at = 998_000, duration = 6, direction = "outgoing"),
        )
        val items = groupCallAttempts(newestFirst)
        assertEquals(3, items.size)
        val attempts = items[0] as CallHistoryItem.Attempts
        assertEquals(listOf("c5", "c4", "c3"), attempts.calls.map { it.id })
        assertEquals("attempts:c5", attempts.key)
        assertEquals("c2", (items[1] as CallHistoryItem.Single).call.id)
        assertEquals("c1", (items[2] as CallHistoryItem.Single).call.id)
    }

    @Test fun threeSixSecondRedialsInTheSameMinuteAreOneRow() {
        val calls = listOf("c3", "c2", "c1").mapIndexed { index, id ->
            call(id, state = "completed", at = 2_000_000 - index * 20L, duration = 6, direction = "outgoing")
        }
        val item = groupCallAttempts(calls).single() as CallHistoryItem.Attempts
        assertEquals(3, item.calls.size)
    }

    @Test fun groupingNeverHidesAFeedbackPromptOrMixesCallsThatDiffer() {
        assertFalse(isShortAttempt(call("c1", notice = "notice-1")))
        assertFalse(isShortAttempt(call("c1", state = "completed", duration = 90)))
        assertFalse(isShortAttempt(call("c1", state = "completed", duration = 3, transcript = true)))
        assertFalse(isShortAttempt(call("c1", state = "active")))
        assertTrue(isShortAttempt(call("c1", state = "failed")))
        val mixed = listOf(
            call("c4", at = 1_000_400, conversation = "chat-b"),   // another conversation
            call("c3", at = 1_000_300, reason = "Urgent"),         // another reason
            call("c2", at = 1_000_200, notice = "notice-2"),       // needs its feedback buttons
            call("c1", at = 1_000_100),
        )
        assertTrue(groupCallAttempts(mixed).all { it is CallHistoryItem.Single })
        // A quarter of an hour between two attempts is a new try, not a repeat.
        val apart = listOf(call("b", at = 1_002_000), call("a", at = 1_000_000))
        assertTrue(groupCallAttempts(apart).all { it is CallHistoryItem.Single })
        assertTrue(groupCallAttempts(emptyList()).isEmpty())
    }

    // ---- Setup progress ----

    @Test fun theBarMovesOnEveryPageOfStepThree() {
        assertEquals(1f, setupProgress("connect"), 0f)
        assertEquals(2f, setupProgress("signin"), 0f)
        assertEquals(4f, setupProgress("done"), 0f)
        val steps = SETUP_CALL_PAGES.map(::setupProgress)
        assertEquals(steps.sorted(), steps)
        assertEquals("every page lands somewhere new", steps.size, steps.toSet().size)
        assertTrue("step 3 starts after step 2 and ends where it ends", steps.first() > 2f && steps.last() == 3f)
        // The count shown beside the bar is still "Step 3 of 4" on all of them.
        assertTrue(SETUP_CALL_PAGES.all { setupStepNumber(it) == 3 })
    }

    @Test fun theEarlierStepsKeepTheirWholeSegments() {
        listOf("connect", "probe", "unsupported").forEach { assertEquals(1f, setupProgress(it), 0f) }
        assertEquals(listOf("voice-provider", "voice-key", "voice-customize", "permission-microphone", "permission-notifications", "permission-full-screen"), SETUP_CALL_PAGES)
    }

    // ---- Call bubble question ----

    @Test fun theBubbleIsAskedOnceAfterACallThatRanWithoutIt() {
        assertTrue(shouldAskCallBubble(wasActive = true, bubbleWasMissing = true, active = false, alreadyAsked = false))
        assertFalse("still in the call", shouldAskCallBubble(true, true, active = true, alreadyAsked = false))
        assertFalse("the permission was there", shouldAskCallBubble(true, false, false, false))
        assertFalse("asked before", shouldAskCallBubble(true, true, false, alreadyAsked = true))
        assertFalse("no call happened", shouldAskCallBubble(false, true, false, false))
    }

    // ---- Incoming call sheet ----

    @Test fun theRingingCallsReasonComesFromTheOwnersCallList() {
        val history = JSONObject().put("calls", JSONArray()
            .put(JSONObject().put("id", "other").put("direction", "incoming").put("state", "missed").put("reason", "Not this one"))
            .put(JSONObject().put("id", "event-1").put("direction", "outgoing").put("state", "completed").put("reason", "Wrong direction"))
            .put(JSONObject().put("id", "event-1").put("direction", "incoming").put("state", "active").put("reason", "  Your 3 pm\n is moved ")))
        assertEquals("Your 3 pm is moved", incomingReasonFromHistory(history, "event-1"))
        assertEquals("", incomingReasonFromHistory(history, "event-2"))
        assertEquals("", incomingReasonFromHistory(JSONObject(), "event-1"))
    }
}
