package dev.hermes.android

import dev.hermes.android.core.json
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WEDGE-121: what tells the chat a reply is held by Hermes as lost, and what the chat does about it. */
class LostReplyHelpersTest {
    @Test fun aSessionHoldingAnUnknownTurnWithNothingRunningIsLost() {
        val unknown = JSONArray().put(json("status" to "unknown"))
        assertTrue(lostTurnHeld(json("running" to false, "pending" to unknown)))
        assertTrue("A snapshot without the flag still holds it", lostTurnHeld(json("pending" to unknown)))
        assertFalse(lostTurnHeld(json("running" to true, "pending" to unknown)))
        assertFalse(lostTurnHeld(json("running" to false, "pending" to JSONArray().put(json("status" to "queued")))))
        assertFalse(lostTurnHeld(null))
    }

    /** Follow-up 1: the restart line's Send shows only while messages wait behind the lost reply and nothing runs. */
    @Test fun theChoiceShowsWhileMessagesWaitAndNothingRuns() {
        assertTrue(lostChoiceShown(ChatState(lostWaiting = true)))
        assertFalse("A running reply is followed instead", lostChoiceShown(ChatState(lostWaiting = true, streamId = "stored-a")))
        assertFalse(lostChoiceShown(ChatState()))
    }

    /** Follow-up 2 (WF-P3-1): a chat's own box, open or saved, keeps a look from closing its lost reply while a message of the owner's waits in it. */
    @Test fun aBoxWithAWaitingMessageHoldsTheLostReply() {
        assertTrue(boxHoldsWaiting(listOf(dev.hermes.android.core.QueuedMessage("synthetic waiting"))))
        // Follow-up 3 (live, 17 Nov): an accepted message is Hermes' (its snapshot counts what waits there), often the cut turn's own.
        assertFalse("Hermes holds it, not the phone", boxHoldsWaiting(listOf(dev.hermes.android.core.QueuedMessage("synthetic", state = "accepted"))))
        assertTrue(boxHoldsWaiting(listOf(dev.hermes.android.core.QueuedMessage("synthetic", state = "accepted"), dev.hermes.android.core.QueuedMessage("synthetic waiting"))))
        assertFalse(boxHoldsWaiting(listOf(dev.hermes.android.core.QueuedMessage("synthetic", state = "sent"), dev.hermes.android.core.QueuedMessage("synthetic", state = "settled"))))
        assertFalse(boxHoldsWaiting(listOf(dev.hermes.android.core.QueuedMessage("synthetic", kind = "steer"))))
        assertFalse(boxHoldsWaiting(emptyList()))
    }

    /** Follow-up 4: a bot row is marked only for its own Bot Chat, never for another lost chat of the same profile. */
    @Test fun aBotIsMarkedOnlyForItsOwnBotChat() {
        val ui = AppState(lostChats = setOf("bot-a:a-chat", "bot-b:other-chat"), lostBotChats = mapOf("bot-a" to "a-chat", "bot-c" to "c-chat"))
        assertTrue(botCutOff(ui, "bot-a"))
        assertFalse("another chat of bot-b's profile", botCutOff(ui, "bot-b"))
        assertFalse("its Bot Chat was let go", botCutOff(ui, "bot-c"))
        assertFalse(botCutOff(AppState(), "bot-a"))
    }

    /** Follow-up 2 (WF-P3-3): the quiet-stream watcher ends on a lost reply. */
    @Test fun theQuietStreamWatcherEndsOnALostReply() {
        assertTrue(silentStreamAction("lost") == SilentStream.Lost)
    }
}
