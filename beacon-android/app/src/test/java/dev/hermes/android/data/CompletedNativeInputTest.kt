package dev.hermes.android.data

import dev.hermes.android.completedQueuedInputRow
import dev.hermes.android.linkedQueuedInputRow
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.copy
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompletedNativeInputTest {
    private val epoch = "synthetic-epoch"
    private val runtime = "synthetic-runtime"
    private val first = QueuedMessage("same synthetic text", id = "local-a", state = "settled", outcome = "completed",
        submissionId = "3856edda-ce9f-45ce-b9ce-dd529aca6367", admissionId = "9d7c9c1f538344b181e1e394ed76f371")
    private val second = QueuedMessage("same synthetic text", id = "local-b", state = "settled", outcome = "completed",
        submissionId = "5d29d0ba-bdf2-4b6b-83db-e7da2ae7569c", admissionId = "70a0c938b1514dd98c77fb7192a02a8e")

    private fun event(item: QueuedMessage, user: Int, final: Int, vararg rows: Int) = json(
        "session_id" to runtime, "replay_epoch" to epoch, "seq" to if (user == 19) 1 else 2,
        "authority_epoch" to 4, "execution_generation" to 7, "admission_id" to item.admissionId, "type" to "message.complete",
        "payload" to json("admission_id" to item.admissionId, "persisted_turn" to json(
            "row_ids" to JSONArray(rows.toList()), "complete" to true, "user_row_id" to user,
            "user_row_ids" to JSONArray(listOf(user)), "final_assistant_row_id" to final, "submission_id" to item.submissionId)))
    private fun receipt(item: QueuedMessage) = json("status" to "terminal", "outcome" to "completed", "admission_id" to item.admissionId,
        "input_id" to item.submissionId, "authority_epoch" to 4, "execution_generation" to 7)
    private fun replay(vararg events: JSONObject) = json("epoch" to epoch, "truncated" to false,
        "latest_seq" to (events.lastOrNull()?.optLong("seq") ?: 0), "events" to JSONArray(events.toList()))
    private fun rows(vararg values: Pair<Int, String>) = values.map { (id, role) -> json("row_id" to id, "role" to role) }
    private fun completed(item: QueuedMessage, replay: JSONObject, receipt: JSONObject, history: List<JSONObject>, queued: List<QueuedMessage> = listOf(item), refreshed: List<JSONObject> = history, after: Long = 0): String? {
        val proof = nativeCompletedTurn(replay, runtime, epoch, receipt, item.submissionId, after)
        val checked = receipt.copy().apply { if (proof != null) put("_confirmed_persisted_turn", proof) }
        return completedQueuedInputRow(item, checked, history, refreshed, queued)
    }

    @Test fun settledResultProvesCompletedRowsWithoutTheRuntimeEventRing() {
        val turn = json("complete" to true, "user_row_id" to 15, "final_assistant_row_id" to 20,
            "row_ids" to JSONArray(listOf(15, 16, 17, 18, 19, 20)), "user_row_ids" to JSONArray(listOf(15)))
        fun settled(item: QueuedMessage, value: JSONObject = turn) = receipt(item).apply { put("result", json("persisted_turn" to value)) }
        val proof = nativeSettledTurn(settled(first), first.submissionId)
        assertEquals(first.submissionId, proof?.string("submission_id"))
        assertEquals(15L, proof?.optLong("user_row_id"))
        assertEquals(20L, proof?.optLong("final_assistant_row_id"))
        assertEquals(listOf(15L, 16L, 17L, 18L, 19L, 20L),
            (0 until proof!!.getJSONArray("row_ids").length()).map { proof.getJSONArray("row_ids").optLong(it) })
        assertNull("incomplete settlement is not a completed turn",
            nativeSettledTurn(settled(first, turn.copy().put("complete", false)), first.submissionId))
        assertNull("terminal receipt with a missing result never invents proof",
            nativeSettledTurn(receipt(first), first.submissionId))
        assertNull("the final row cannot be the user row",
            nativeSettledTurn(settled(first, turn.copy().put("final_assistant_row_id", 15)), first.submissionId))
        assertNull("the user row must be the turn's exact user row",
            nativeSettledTurn(settled(first, turn.copy().put("user_row_ids", JSONArray(listOf(16)))), first.submissionId))
        assertNull("a different admission submission is refused",
            nativeSettledTurn(settled(first, turn.copy().put("submission_id", second.submissionId)), first.submissionId))
        assertNull("only terminal completed receipts prove a turn",
            nativeSettledTurn(settled(first).apply { put("status", "started") }, first.submissionId))
        assertNull("the receipt input identity is enforced",
            nativeSettledTurn(settled(first).apply { put("input_id", second.submissionId) }, first.submissionId))
        assertNull("duplicate rows are not an exact turn",
            nativeSettledTurn(settled(first, turn.copy().put("row_ids", JSONArray(listOf(15, 20, 20)))), first.submissionId))
        assertNull("a settlement without row_ids fails closed",
            nativeSettledTurn(settled(first, turn.copy().apply { remove("row_ids") }), first.submissionId))
        assertNull("a settlement without user_row_ids fails closed",
            nativeSettledTurn(settled(first, turn.copy().apply { remove("user_row_ids") }), first.submissionId))
        assertNull("a non-array row list fails closed",
            nativeSettledTurn(settled(first, turn.copy().put("row_ids", "15,20")), first.submissionId))
        assertNull("a non-numeric row id fails closed",
            nativeSettledTurn(settled(first, turn.copy().put("row_ids", JSONArray(listOf(15, "20")))), first.submissionId))
        val malformed = settled(first, turn.copy().apply { remove("row_ids") })
        assertNull(nativeSettledTurn(malformed, first.submissionId))
        val fallback = nativeCompletedTurn(replay(event(first, 15, 20, 15, 16, 17, 18, 19, 20)),
            runtime, epoch, receipt(first), first.submissionId, after = 1)
        assertEquals("a malformed settlement still falls back to the retained message.complete event",
            first.submissionId, fallback?.string("submission_id"))
    }

    @Test fun bothObservedTurnShapesResolveOnlyTheirOwnRows() {
        val replayData = replay(event(first, 19, 20, 19, 20), event(second, 21, 24, 21, 22, 23, 24))
        val history = rows(19 to "user", 20 to "assistant", 21 to "user", 22 to "assistant", 23 to "tool", 24 to "assistant")
        assertEquals("19", completed(first, replayData, receipt(first), history, listOf(first, second)))
        assertEquals("21", completed(second, replayData, receipt(second), history, listOf(first, second)))
        val renamed = first.copy(text = "different synthetic text")
        assertEquals("19", completed(renamed, replayData, receipt(first).apply { remove("input_id") }, history, listOf(renamed, second)))
        assertNull(completed(first.copy(admissionId = second.admissionId), replayData, receipt(second), history))
        assertNull(completed(second.copy(submissionId = first.submissionId), replayData, receipt(first), history))
    }

    @Test fun retainedCompletionSurvivesEvictedAndMissingEarlierEventsOnlyAfterFreshHistory() = runBlocking {
        val retained = (800 until 1312).map { seq ->
            json("session_id" to runtime, "replay_epoch" to epoch, "seq" to seq, "type" to "status.update", "payload" to JSONObject())
        }.toMutableList()
        retained[510] = event(first, 19, 20, 19, 20).put("seq", 1310)
        val reads = mutableListOf<Long>()
        fun fetch(events: List<JSONObject>, evictedThrough: Long): suspend (Long) -> JSONObject = { after ->
            reads += after
            val missing = after < evictedThrough
            json("epoch" to epoch, "truncated" to missing, "snapshot_required" to missing, "latest_seq" to 1311,
                "events" to JSONArray(if (missing) emptyList() else events.filter { it.optLong("seq") > after }))
        }
        val history = rows(19 to "user", 20 to "assistant")
        val (after, replayData) = nativeRetainedReplay(epoch, fetch(retained, 799)) ?: error("retained tail unavailable")
        assertEquals(799L, after)
        assertEquals(0L, reads.first())
        assertTrue(reads.size <= 12)
        assertEquals("19", completed(first, replayData, receipt(first), history, after = after))
        val previousReads = reads.size
        val (afterGap, replayAfterGap) = nativeRetainedReplay(epoch, fetch(retained.filterIndexed { index, _ -> index != 20 }, 820)) ?: error("tail after gap unavailable")
        assertTrue(reads.size - previousReads <= 12)
        assertEquals("19", completed(first, replayAfterGap, receipt(first), history, after = afterGap))
        assertNull(completed(first, replayData, receipt(first), history, refreshed = rows(19 to "user"), after = after))
        val (afterEviction, afterEvicted) = nativeRetainedReplay(epoch, fetch(retained.filterIndexed { index, _ -> index != 510 }, 1310)) ?: error("tail after eviction unavailable")
        assertNull(completed(first, afterEvicted, receipt(first), history, after = afterEviction))
        val malformed = replayData.copy().put("events", JSONArray(retained.dropLast(1)))
        assertNull(completed(first, malformed, receipt(first), history, after = after))
        assertNull(completed(first, replayData.copy().put("events", JSONArray(retained.filterIndexed { index, _ -> index != 510 })), receipt(first), history, after = after))
        assertNull(completed(first.copy(state = "unknown"), replayData, receipt(first), history, after = after))
        assertNull(nativeRetainedReplay(epoch) { seen -> fetch(retained, 799)(seen).apply { if (seen != 0L) put("epoch", "other") } })
    }

    @Test fun incompleteOrForeignProofNeverRetiresAnInput() {
        val wireEvent = event(first, 19, 20, 19, 20)
        val history = rows(19 to "user", 20 to "assistant")
        val receiptData = receipt(first)
        val replayData = replay(wireEvent)
        assertNull(completed(first, replayData.copy().apply { remove("truncated") }, receiptData, history))
        assertNull(completed(first, replayData.copy().put("epoch", "other"), receiptData, history))
        assertNull(completed(first, replay(wireEvent.copy().put("session_id", "other")), receiptData, history))
        assertNull(completed(first, replay(wireEvent.copy().put("replay_epoch", "other")), receiptData, history))
        assertNull(completed(first, replay(wireEvent, wireEvent), receiptData, history))
        assertNull(completed(first, replay(wireEvent, event(second, 21, 24, 21, 22, 23, 24).put("seq", 3)), receiptData, history))
        assertNull(completed(first, replayData.copy().put("latest_seq", 2), receiptData, history))
        assertNull(completed(first, replayData.copy().put("snapshot_required", true), receiptData, history))
        assertNull(completed(first, replayData, receiptData.copy().put("input_id", second.submissionId), history))
        assertNull(completed(first, replayData, receiptData.copy().put("outcome", "failed"), history))
        assertNull(completed(first, replayData, receiptData.copy().put("status", "unknown"), history))
        assertNull(completed(first, replayData, receiptData.copy().put("execution_generation", 8), history))
        assertNull(completed(first, replayData, receiptData.copy().put("authority_epoch", 5), history))
        assertNull(completed(first, replay(wireEvent.copy().put("admission_id", second.admissionId)), receiptData, history))
        assertNull(completed(first, replayData, receiptData, rows(19 to "user")))
        assertNull(completed(first, replayData, receiptData, rows(19 to "assistant", 20 to "user")))
        assertNull(completed(first.copy(rowId = "21"), replayData, receiptData, history))
        assertNull(completed(first.copy(state = "unknown"), replayData, receiptData, history))
        assertNull(completed(first.copy(kind = "steer"), replayData, receiptData, history))
        assertNull(completed(first, replayData, receiptData, history, listOf(first, first.copy(id = "ambiguous"))))
        val partial = event(first, 19, 20, 19, 20)
        partial.getJSONObject("payload").getJSONObject("persisted_turn").put("complete", false)
        assertNull(completed(first, replay(partial), receiptData, history))
        val error = event(first, 19, 20, 19, 20)
        error.getJSONObject("payload").put("status", "error")
        assertNull(completed(first, replay(error), receiptData, history))
        val wrongRows = event(first, 19, 20, 19, 21)
        assertNull(completed(first, replay(wrongRows), receiptData, history))
    }

    @Test fun imageInputLinksByScopedRowProofBeforeCompleteTurnRetirement() {
        val image = first.copy(state = "accepted", outcome = "", attachments = listOf(json("name" to "image.png", "mime" to "image/png", "staged_base64" to "Zml4dHVyZQ==")))
        val wire = event(image, 19, 20, 19, 20)
        val partial = wire.copy().apply { getJSONObject("payload").getJSONObject("persisted_turn").put("complete", false) }
        fun checked(frame: JSONObject, input: QueuedMessage = image, confirmation: JSONObject = receipt(image)): JSONObject = confirmation.copy().apply {
            nativeCompletedTurn(replay(frame), runtime, epoch, confirmation, input.submissionId, 0, requireComplete = false)
                ?.let { put("_confirmed_persisted_input", it) }
        }
        val onlyUser = rows(19 to "user")
        assertEquals("19", linkedQueuedInputRow(image, checked(partial), onlyUser, listOf(image)))
        assertNull("A linked input does not prove that every reply row committed", completedQueuedInputRow(image, checked(partial), onlyUser, onlyUser, listOf(image)))
        assertEquals("accepted", image.state)
        assertEquals("19", linkedQueuedInputRow(image.copy(text = "different", attachments = emptyList()), checked(wire), onlyUser, listOf(image)))
        assertNull(linkedQueuedInputRow(image.copy(submissionId = "foreign"), checked(wire), onlyUser, listOf(image)))
        assertNull(linkedQueuedInputRow(image.copy(admissionId = "foreign"), checked(wire), onlyUser, listOf(image)))
        assertNull(linkedQueuedInputRow(image, checked(wire), onlyUser, listOf(image, image.copy(id = "duplicate"))))
        assertNull(linkedQueuedInputRow(image, checked(wire), rows(19 to "assistant"), listOf(image)))
        assertNull(linkedQueuedInputRow(image, checked(wire.copy().put("session_id", "foreign")), onlyUser, listOf(image)))
        assertNull(linkedQueuedInputRow(image, checked(wire.copy().put("replay_epoch", "foreign")), onlyUser, listOf(image)))
        assertNull(linkedQueuedInputRow(image, checked(wire, confirmation = receipt(image).put("execution_generation", 8)), onlyUser, listOf(image)))
        val fractional = checked(wire).apply { getJSONObject("_confirmed_persisted_input").put("user_row_id", 19.5) }
        assertNull(linkedQueuedInputRow(image, fractional, onlyUser, listOf(image)))
    }
}
