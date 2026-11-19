package dev.hermes.android.turn

import dev.hermes.android.TURN_RESTARTED
import dev.hermes.android.TURN_STOPPED
import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.data.turnErrorText
import dev.hermes.android.holdFollowUps
import dev.hermes.android.passiveDrainSkips
import dev.hermes.android.releaseHolds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TURN-121 follow-up 2 (REVIEW-TURN TF-P2-1): the hold after a failed, stopped or restart-cut turn is saved on each waiting message, so a cold start
 * (MagicOS kills background apps routinely) keeps it, and only the owner's Send releases it. Synthetic text and ids only.
 */
class TurnFollowUp2Test {
    private val now = System.currentTimeMillis()
    private val tooLong = turnErrorText(json("status" to "error", "error_surface" to json("layer" to "provider", "code" to "context_overflow", "retryable" to false)), now)

    @Test fun aHeldFollowUpIsStillHeldWithItsReasonAfterAColdStart() {
        // The reviewer's proof, inverted: the saved draft brought the follow-up back plain "queued", with nothing saying it was held.
        val held = holdFollowUps(listOf(QueuedMessage("Synthetic follow-up", id = "q1")), tooLong)
        val back = Draft.decode(Draft(queued = held).encode()).queued.single()
        assertEquals("queued", back.state)
        assertEquals("the reason the turn before it ended on is kept with it", tooLong, back.held)
        assertTrue("no passive drain (open, watch tick, stream end) sends it", passiveDrainSkips(back, explicit = false))
        assertFalse("the owner's Send does", passiveDrainSkips(back, explicit = true))
    }

    @Test fun theHoldIsPerMessageAndOnlyOnMessagesStillWaiting() {
        val queued = listOf(QueuedMessage("First waiting", id = "a"), QueuedMessage("Second waiting", id = "b"),
            QueuedMessage("A steer", id = "s", kind = "steer"), QueuedMessage("Already sent", id = "x", state = "accepted"))
        val held = holdFollowUps(queued, TURN_RESTARTED).associateBy { it.id }
        assertEquals(TURN_RESTARTED, held.getValue("a").held)
        assertEquals("every waiting message keeps its own hold, not one id per chat", TURN_RESTARTED, held.getValue("b").held)
        assertEquals("", held.getValue("s").held)
        assertEquals("", held.getValue("x").held)
        // A later hold does not overwrite an earlier reason.
        assertEquals(TURN_RESTARTED, holdFollowUps(held.values.toList(), TURN_STOPPED).single { it.id == "a" }.held)
    }

    @Test fun oneSendReleasesEveryHoldAndSendsTheSameMessageOnce() {
        val held = holdFollowUps(listOf(QueuedMessage("First waiting", id = "a"), QueuedMessage("Second waiting", id = "b")), TURN_STOPPED)
        val released = releaseHolds(held)
        assertTrue(released.all { it.held.isEmpty() && !passiveDrainSkips(it, explicit = false) })
        // Releasing changes nothing Hermes uses to recognize the send: the same submission goes, once.
        assertEquals(held.map { it.id to it.submissionId }, released.map { it.id to it.submissionId })
        assertEquals("a released message saves as plain waiting", "", Draft.decode(Draft(queued = released).encode()).queued.first().held)
    }
}
