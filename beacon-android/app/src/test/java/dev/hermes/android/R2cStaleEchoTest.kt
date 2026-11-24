package dev.hermes.android

import dev.hermes.android.core.*
import org.junit.Assert.*
import org.junit.Test

/**
 * R2C (owner, 22 Nov 19:51, Root msg_70674261c439): in the main chat a user "done" from Tue 17 Nov 14:49 (Hermes row 55476, deep in the history) stayed at
 * the very bottom, below the new message, while a turn ran. Beacon reads only the newest rows of a chat; the phone's own copy of that send was still queued
 * (a finished copy retires only once both ends of its turn are proven inside the loaded rows), its row had left the window, so the chat drew the copy as a
 * local bubble after every newer row. Synthetic chats only.
 */
class R2cStaleEchoTest {
    private val nov17 = 1_794_926_940_000L
    private val today = 1_795_355_000_000L
    /** The loaded window: today's newest rows. The running step has no saved time yet, so the window's clock is not clear. */
    private val window = listOf(
        Message("60001", "user", "Plan the week", recordedAt = today),
        Message("60002", "assistant", "Here is the plan", recordedAt = today + 1_000),
        Message("60003", "user", "Thanks, now book it", recordedAt = today + 2_000),
        Message("60004", "assistant", "", recordedAt = 0),
    )
    /** The phone's copy of the 17 Nov send: Hermes named its row (55476) and the turn finished, but its receipt never retired it. */
    private val echo = QueuedMessage("done", id = "echo-17nov", createdAt = nov17, state = "settled", outcome = "completed", rowId = "55476",
        admissionId = "admission-17nov", submissionId = "submission-17nov", afterRowId = "55475")

    @Test fun anOldEchoWhoseRowIsAboveTheWindowIsNeverDrawnAfterNewerRows() {
        assertEquals("only Hermes' rows, in their order", window.map { it.id }, chatTimelineMessages(window, listOf(echo)).map { it.id })
        // With its row loaded the echo and its row are one row, in time order.
        val withRow = listOf(Message("55476", "user", "done", recordedAt = nov17)) + window
        assertEquals(withRow.map { it.id }, chatTimelineMessages(withRow, listOf(echo)).map { it.id })
    }

    @Test fun anEchoHermesHasButNeverNamedIsDrawnBeforeTheWindowNotAfterIt() {
        // Hermes accepted it (so it has it) but no read paired it with a row before the row left the window: it was sent after row 55475.
        val unnamed = echo.copy(state = "accepted", outcome = "", rowId = "")
        val shown = chatTimelineMessages(window, listOf(unnamed)).map { it.id }
        assertEquals(listOf("queued:echo-17nov") + window.map { it.id }, shown)
    }

    @Test fun anEchoHermesNeverGotGoesToTheBoxNotTheTimeline() {
        val notSent = echo.copy(state = "settled", outcome = NOT_SENT_OUTCOMES.first(), rowId = "")
        assertEquals(window.map { it.id }, chatTimelineMessages(window, listOf(notSent)).map { it.id })
        assertEquals(listOf(notSent), queueBoxItems(listOf(notSent)))
        assertTrue("its row says Not sent", queueBoxSaysNotSent(notSent))
    }

    @Test fun theCacheSavedBeforeTheFixHealsOnFirstLoad() {
        // The draft as the 1.2.3 candidate saved it: the stale copy beside a copy of today's that is still running and one finished inside the window.
        val running = QueuedMessage("Thanks, now book it", id = "today-running", createdAt = today + 2_000, state = "accepted", rowId = "60003", admissionId = "a2", submissionId = "s2", afterRowId = "60002")
        val finished = running.copy(id = "today-finished", state = "settled", outcome = "completed", rowId = "60001", admissionId = "a1", submissionId = "s1", afterRowId = "")
        val saved = Draft(queued = listOf(echo, running, finished)).encode()
        val loaded = Draft.decode(saved).queued
        assertEquals("the first history render retires only the finished copy above the window", listOf("echo-17nov"), copiesAboveWindow(loaded, window).map { it.id })
        assertEquals(window.map { it.id }, chatTimelineMessages(window, loaded).map { it.id })
        // An empty or unnumbered window proves nothing about where a row is.
        assertEquals(emptyList<QueuedMessage>(), copiesAboveWindow(loaded, emptyList()))
        assertEquals(emptyList<QueuedMessage>(), copiesAboveWindow(loaded, listOf(Message("user:0", "user", "hi"))))
    }
}
