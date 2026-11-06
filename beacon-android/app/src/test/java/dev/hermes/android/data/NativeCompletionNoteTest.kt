package dev.hermes.android.data

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.ui.isOwnMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Synthetic native history rows; no owner text, identities, paths or runtime access. */
class NativeCompletionNoteTest {
    private val raw = "INTERNAL NOTIFICATION\nEvidence: cache/delegation/fixture/worker.log\nKeep this envelope internal.\nBEACON_QA_WORKER_DONE"
    private val summary = "Subagent Task Completed: Synthetic helper returned BEACON_QA_WORKER_DONE"

    private fun note(kind: String = "async_delegation_complete", text: Any = summary): JSONObject = json(
        "id" to 77, "role" to "user", "content" to raw, "display_kind" to kind,
        "timestamp" to 1_720_000_001.25,
        "display_metadata" to json("display_text" to text, "completion_admission" to "a".repeat(32),
            "completion_note" to JSONArray().put("[\"async_delegation\",\"deleg_fixture\",\"\"]")),
    )

    @Test fun asyncCompletionUsesPreparedTextAndAssistantPresentation() {
        val row = note()
        val before = row.toString()
        val message = Message.from(row, 0, "synthetic-chat")
        assertEquals(summary, message.text)
        assertEquals("assistant", message.role)
        assertFalse(isOwnMessage(message))
        assertFalse(message.text.contains("INTERNAL NOTIFICATION"))
        assertFalse(message.text.contains("cache/delegation"))
        assertEquals(before, row.toString())
    }

    @Test fun processCompletionUsesTheSameNativeProducerCardContract() {
        val display = "Background Process Finished: synthetic-command"
        val message = Message.from(note("process_complete", display), 0)
        assertEquals(display, message.text)
        assertEquals("assistant", message.role)
        assertEquals("process_complete", message.displayKind)
        assertFalse(isOwnMessage(message))
    }

    @Test fun nativeRowIdentityAndUnrelatedFieldsSurvivePresentation() {
        val row = note().put("reasoning", "fixture reasoning")
            .put("tool_calls", JSONArray().put(json("id" to "fixture-call")))
            .put("attachments", JSONArray().put(json("name" to "fixture.txt")))
        row.getJSONObject("display_metadata").put("interrupted", true).put("channel", "voice")
        val first = Message.from(row, 0, "synthetic-chat")
        val inserted = Message.from(row, 12, "synthetic-chat")
        assertEquals("77", first.id)
        assertEquals(first.id, inserted.id)
        assertEquals("synthetic-chat", first.sessionId)
        assertEquals(1_720_000_001_250L, first.recordedAt)
        assertEquals("async_delegation_complete", first.displayKind)
        assertEquals("fixture reasoning", first.reasoning)
        assertEquals("fixture-call", first.tools.single().getString("id"))
        assertEquals("fixture.txt", first.attachments.single().getString("name"))
        assertTrue(first.interrupted)
        assertTrue(first.voice)
        assertNotEquals(first.id, Message.from(row.put("id", 78), 0).id)
    }

    @Test fun onlyNativeMetadataOverridesModelFacingText() {
        val row = note().put("text", raw).put("display_content", raw)
        assertEquals(summary, Message.from(row, 0).text)
        row.remove("id")
        row.put("row_id", 90)
        assertEquals("90", Message.from(row, 0).id)
    }

    @Test fun ordinaryUserTextAndLiteralJsonNeverBecomeAProducerResult() {
        val literalJson = note().toString()
        for (text in listOf(raw, summary, literalJson)) {
            val message = Message.from(json("id" to "human", "role" to "user", "content" to text), 0)
            assertEquals(text, message.text)
            assertEquals("user", message.role)
            assertTrue(isOwnMessage(message))
        }
        val untyped = note().apply { remove("display_kind") }
        assertEquals(raw, Message.from(untyped, 0).text)
        assertEquals("user", Message.from(untyped, 0).role)
    }

    @Test fun unrelatedKindsAndRolesKeepExistingPresentation() {
        for (kind in listOf("internal_notification", "cron_delivery", "model_switch", "unknown", "")) {
            val message = Message.from(note(kind), 0)
            assertEquals(kind, raw, message.text)
            assertEquals(kind, "user", message.role)
        }
        for (role in listOf("assistant", "tool", "system")) {
            val message = Message.from(note().put("role", role), 0)
            assertEquals(role, raw, message.text)
            assertEquals(role, message.role)
        }
    }

    @Test fun blankOrNonStringDisplayTextDoesNotReplaceExistingContent() {
        for (value in listOf("", " \n\t", 42, true, JSONObject.NULL, json("text" to summary), JSONArray().put(summary))) {
            val message = Message.from(note(text = value), 0)
            assertEquals("value=$value", raw, message.text)
            assertEquals("value=$value", "user", message.role)
        }
    }

    @Test fun missingOrEncodedMetadataKeepsTheNormalHistoryProjection() {
        for (metadata in listOf(JSONObject.NULL, json(), summary, json("display_text" to summary).toString())) {
            val message = Message.from(note().put("display_metadata", metadata), 0)
            assertEquals(raw, message.text)
            assertEquals("user", message.role)
        }
        val ordinary = json("role" to "user", "content" to raw, "display_text" to "Existing projection")
        assertEquals("Existing projection", Message.from(ordinary, 0).text)
        ordinary.put("display_content", "")
        assertEquals("", Message.from(ordinary, 0).text)
    }
}
