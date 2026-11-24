package dev.hermes.android.assist

import dev.hermes.android.OFFLINE_OUTCOME
import dev.hermes.android.core.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S1d (R3 review #5): an overlay question that did not come back in 4 s used to go back in the field as not sent, even when the assistant chat's box
 * already held it, so asking again sent it twice. When the box holds it, the overlay says it is in the queue (Open) and hands nothing back.
 */
class AssistQueuedAskTest {
    private val question = "What's next today?"

    @Test fun aQuestionTheBoxHoldsIsShownAsQueuedAndNeverHandedBackSoItIsAskedOnce() {
        assertTrue("waiting to go by itself", queuedInBox(listOf(QueuedMessage(question, state = "queued", outcome = OFFLINE_OUTCOME)), question))
        assertFalse("settled Not sent goes only on the owner's Send, so it is handed back", queuedInBox(listOf(QueuedMessage(question, state = "settled", outcome = "cancelled")), question))
        assertFalse("sent: not in the box", queuedInBox(listOf(QueuedMessage(question, state = "sent")), question))
        assertFalse("another question", queuedInBox(listOf(QueuedMessage("Something else", state = "queued", outcome = OFFLINE_OUTCOME)), question))
        assertFalse(queuedInBox(emptyList(), question))

        val asked = reduce(reduce(reduce(AssistOverlayState(), OverlayEvent.Show(fresh = true, callActive = false)), OverlayEvent.Summoned), OverlayEvent.Asked(question, false, ""))
        val queued = reduce(asked, OverlayEvent.NotSent).copy(line = OverlayLine.Queued)
        assertFalse("it stops thinking", queued.ask!!.streaming)
        assertEquals(LineAction.Open, lineAction(OverlayLine.Queued))
        assertNull("a late reply takes the line's place", reduce(queued, OverlayEvent.Progress("Here is your day", streaming = true)).line)

        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val ask = File(app, "src/main/java/dev/hermes/android/assist/AssistOverlayController.kt").readText().replace("\r\n", "\n")
        assertTrue("ask() hands the question back (notSent) only when the box does not hold it",
            ask.contains("if (accepted == null) { if (queuedInBox(model.chat.value.queued, question)) state = reduce(state, OverlayEvent.NotSent).copy(line = OverlayLine.Queued) else notSent() }"))
    }
}
