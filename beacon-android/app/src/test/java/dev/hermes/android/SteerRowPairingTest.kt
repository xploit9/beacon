package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.junit.Assert.*
import org.junit.Test

/**
 * An accepted steer whose turn is over pairs with the saved Hermes steer row it became, even when a later prompt was sent since
 * (a steer from an earlier turn came back as a stray bubble below a much later message). Synthetic ids, words and times only.
 */
class SteerRowPairingTest {
    // Synthetic round epochs, keeping the reported shape's two-hour offset.
    private val steerAt = 1_780_000_000_000L
    private val newestPromptAt = 1_780_007_450_000L
    private val steerNote = "[OUT-OF-BAND USER MESSAGE \u2014 a direct message from the user, delivered once at this position]"

    private fun user(id: Long, text: String, kind: String = "", at: Long = 0L) =
        json("row_id" to id, "role" to "user", "content" to text).apply {
            if (kind.isNotBlank()) put("display_kind", kind)
            if (at > 0) put("timestamp", at / 1000.0)
        }
    private fun steerRow(id: Long, words: String, at: Long = 0L) =
        user(id, steerNote + "\n" + words + "\n[/OUT-OF-BAND USER MESSAGE]", "steer", at)
    private fun assistant(id: Long, text: String) = json("row_id" to id, "role" to "assistant", "content" to text)
    private fun copy(words: String, id: String = "steer-old", at: Long = steerAt, state: String = "accepted") =
        QueuedMessage(words, id = id, createdAt = at, state = state, kind = "steer")

    /** The reported shape with synthetic values: the saved steer sits in the turn it changed, before a much later prompt. */
    private fun incidentShape() = listOf(
        user(10, "Fix the stray section", at = steerAt - 60_000),
        assistant(11, "Looking at the file."),
        steerRow(12, "use the other browser window instead", steerAt),
        assistant(13, "Got it, a different tool."),
        user(20, "continue", at = newestPromptAt),
        assistant(21, "Continuing."),
    )

    @Test fun anOldOpenSteerPairsWithItsSavedRowBeforeTheNewestPrompt() {
        val paired = steerRowPairs(listOf(copy("use the other browser window instead")), emptyList(), incidentShape())
        assertEquals(mapOf("steer-old" to "12"), paired)
        // The render pairs it in the same frame, so the local copy is not drawn beside the saved row.
        val retired = steersPairedOnRender(listOf(copy("use the other browser window instead")), incidentShape()).single()
        assertEquals("sent" to "12", retired.state to retired.rowId)
        val shown = chatTimelineMessages(incidentShape().mapIndexed { index, row -> Message.from(row, index, "chat") }, listOf(retired))
        assertFalse("the stale copy is not drawn", shown.any { it.id == "queued:steer-old" })
        assertEquals("the saved row renders once, in its own turn", 1, shown.count { it.id == "12" })
        assertEquals("and before the later prompt", listOf("10", "11", "12", "13", "20", "21"), shown.map { it.id })
    }

    @Test fun aCurrentTurnSteerAfterTheNewestPromptStillPairs() {
        val rows = listOf(
            user(30, "Write a story", at = newestPromptAt - 600_000),
            steerRow(31, "Make it rhyme", newestPromptAt - 590_000),
            assistant(32, "Once upon a time,"))
        val now = newestPromptAt + 1_000
        assertEquals(mapOf("steer-now" to "31"), steerRowPairs(listOf(copy("Make it rhyme", "steer-now", now)), emptyList(), rows))
        // The receipt's listing still decides which rows are this turn's.
        assertEquals(mapOf("steer-now" to "31"), steerRowPairs(listOf(copy("Make it rhyme", "steer-now", now)), listOf(30L, 31L), rows))
        assertTrue("a row the receipt does not list is not this turn's",
            steerRowPairs(listOf(copy("Make it rhyme", "steer-now", now)), listOf(30L, 39L), rows).isEmpty())
    }

    @Test fun aSteerWithDifferentWordsStaysVisible() {
        val other = copy("something else entirely")
        assertTrue(steerRowPairs(listOf(other), emptyList(), incidentShape()).isEmpty())
        val shown = chatTimelineMessages(incidentShape().mapIndexed { index, row -> Message.from(row, index, "chat") },
            steersPairedOnRender(listOf(other), incidentShape()))
        assertTrue("the unmatched steer is still a bubble", shown.any { it.id == "queued:steer-old" })
    }

    @Test fun anOldSteerNeverTakesARowAfterTheNewestPrompt() {
        // Same words, but only this turn saved them: the older copy is not that row.
        val rows = listOf(user(40, "Question", at = newestPromptAt - 60_000), steerRow(41, "same words", newestPromptAt), assistant(42, "Done."))
        assertTrue(steerRowPairs(listOf(copy("same words", at = steerAt)), emptyList(), rows).isEmpty())
        // Written this turn, that row is its own.
        assertEquals(mapOf("steer-now" to "41"), steerRowPairs(listOf(copy("same words", "steer-now", newestPromptAt + 1_000)), emptyList(), rows))
    }

    @Test fun theRowNearestTheCopysOwnTimeWins() {
        val rows = listOf(
            user(50, "First", at = steerAt - 3_600_000),
            steerRow(51, "again", steerAt - 3_500_000),
            assistant(52, "First answer"),
            user(53, "Second", at = steerAt - 120_000),
            steerRow(54, "again", steerAt - 60_000),
            assistant(55, "Second answer"),
            user(56, "continue", at = newestPromptAt),
            assistant(57, "Continuing."))
        assertEquals("the nearer of two identical rows", mapOf("steer-a" to "54"), steerRowPairs(listOf(copy("again", "steer-a", steerAt - 30_000)), emptyList(), rows))
        assertEquals("and the earlier copy takes the earlier row", mapOf("steer-a" to "51"),
            steerRowPairs(listOf(copy("again", "steer-a", steerAt - 3_450_000)), emptyList(), rows))
    }

    @Test fun aSavedRowIsNeverPairedTwice() {
        val rows = incidentShape()
        val pairs = steerRowPairs(listOf(copy("use the other browser window instead", "steer-1", steerAt - 1),
            copy("use the other browser window instead", "steer-2", steerAt)), emptyList(), rows)
        assertEquals("one row for one bubble", 1, pairs.size)
        val held = copy("use the other browser window instead", "steer-held", steerAt).copy(rowId = "12", state = "sent")
        assertTrue("a row another bubble holds is not taken",
            steerRowPairs(listOf(held, copy("use the other browser window instead", "steer-2", steerAt)), emptyList(), rows).isEmpty())
    }

    @Test fun aNonSteerRowAndASteeringNoteAreNeverASteer() {
        val plain = listOf(user(60, "use the other browser window instead", at = steerAt), user(61, "continue", at = newestPromptAt), assistant(62, "Continuing."))
        assertTrue("a plain user row is not a saved steer", steerRowPairs(listOf(copy("use the other browser window instead")), emptyList(), plain).isEmpty())
        val note = listOf(user(70, "[System: delivery note] use the other browser window instead", at = steerAt),
            user(71, "continue", at = newestPromptAt), assistant(72, "Continuing."))
        assertTrue("a steering note is not a saved steer", steerRowPairs(listOf(copy("use the other browser window instead")), emptyList(), note).isEmpty())
    }

    @Test fun aCopyWithNoTimeStaysVisible() {
        val untimed = incidentShape().map { it.copy().apply { remove("timestamp") } }
        assertTrue("no times, nothing is assumed",
            steerRowPairs(listOf(copy("use the other browser window instead", at = 0)), emptyList(), untimed).isEmpty())
    }

    @Test fun aTurnReceiptStillPairsItsOwnTurn() {
        val rows = listOf(user(80, "First", at = steerAt - 60_000), steerRow(81, "change course", steerAt), assistant(82, "Changed."),
            user(83, "Later", at = newestPromptAt), assistant(84, "Later answer"))
        val receipt = listOf(80L, 81L)
        assertEquals(mapOf("steer-1" to "81"), steerRowPairs(listOf(copy("change course", "steer-1", steerAt)), receipt, rows))
        assertTrue("the prompt itself is not a steer row", steerRowPairs(listOf(copy("First", "steer-x", steerAt)), receipt, rows).isEmpty())
    }

    @Test fun anOldSteerDoesNotTakeARecentTurnsRowAboveTheNewestPrompt() {
        // The receipt's turn starts before the newest prompt, so its rows sit above that prompt too: they belong to that turn, not to an old copy.
        val rows = listOf(
            user(85, "Older", at = steerAt - 600_000),
            steerRow(86, "again", steerAt),
            assistant(87, "Done."),
            user(88, "Recent", at = newestPromptAt - 5_000),
            steerRow(89, "again", newestPromptAt - 4_000),
            assistant(90, "Working"),
            user(91, "Newest", at = newestPromptAt),
            assistant(92, "Continuing."))
        val receipt = listOf(88L, 89L)
        assertEquals("the receipt's own steer row is this turn's", mapOf("cur" to "89"),
            steerRowPairs(listOf(copy("again", "cur", newestPromptAt + 1_000)), receipt, rows))
        assertEquals("an old copy takes its own earlier row, never the receipt's", mapOf("old" to "86"),
            steerRowPairs(listOf(copy("again", "old", steerAt - 300_000)), receipt, rows))
        assertTrue("and with only the receipt's row there is nothing for it",
            steerRowPairs(listOf(copy("again", "old", steerAt - 300_000)), receipt, rows.filterNot { it.optLong("row_id") == 86L }).isEmpty())
        // Without a receipt that row is above the newest prompt, so the old copy may take the one nearest its time.
        assertEquals(mapOf("old" to "86"), steerRowPairs(listOf(copy("again", "old", steerAt - 300_000)), emptyList(), rows))
    }

    @Test fun anOldSteerAboveTheLoadedPageIsRetiredNotDrawn() {
        // Only the newest page is loaded: the saved old steer row is not in it, so its copy is retired by its row, never drawn as a bubble.
        val page = incidentShape().filter { it.getLong("row_id") >= 20L }
        val retired = steersPairedOnRender(listOf(copy("use the other browser window instead")), incidentShape()).single()
        val shown = chatTimelineMessages(page.mapIndexed { index, row -> Message.from(row, index, "chat") }, listOf(retired))
        assertEquals(listOf("20", "21"), shown.map { it.id })
        assertFalse("no stray bubble below the newer message", shown.any { it.text == "use the other browser window instead" })
    }

    @Test fun aSteerRowStillContinuesItsTurn() {
        val turn = incidentShape().drop(1).takeWhile { it.string("role") != "user" || it.string("display_kind") == "steer" }
        assertEquals("the turn goes on past the steer, and stops at the next prompt", listOf("11", "12", "13"), turn.map { it.string("row_id") })
    }
}
