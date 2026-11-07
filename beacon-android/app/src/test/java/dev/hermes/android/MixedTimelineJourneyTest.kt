package dev.hermes.android

import dev.hermes.android.core.*
import dev.hermes.android.data.archivedVoiceTranscripts
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.interleaveCallMessages
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native-shaped display events only; no service, credentials, network or provider. */
class MixedTimelineJourneyTest {
    private val session = "stored-image-chat"
    private val profile = "default"
    private fun saved(vararg rows: JSONObject) = json("session_id" to session, "profile" to profile,
        "replay_epoch" to "epoch-image", "last_sequence" to 10, "messages" to JSONArray(rows.toList()))
    private fun row(id: Int, role: String, text: String) = json("row_id" to id, "role" to role, "content" to text,
        "timestamp" to (1_800_000_000 + id).toDouble())
    private fun event(before: ChatState, type: String, payload: JSONObject): ChatState =
        liveAfterEvent(before, type, payload, false, LiveAssistantBoundary()) ?: before

    @Test fun aFinalOnlyNativeCompletionKeepsItsOnlyReplyUntilHistoryConfirmsIt() {
        val before = ChatState(sessionId = session, streamId = session, status = "Working")
        val complete = json("session_id" to session, "admission_id" to "admission-image", "status" to "complete",
            "text" to "The image is fixed.", "outcome" to "completed")
        val after = event(before, "done", complete)
        assertEquals("The image is fixed.", after.liveText)
        assertEquals("Finishing", after.status)
    }

    @Test fun transformedFinalReplacesTheRawStreamAndExplicitReuseDoesNotInsertItTwice() {
        val before = ChatState(sessionId = session, streamId = session, liveText = "Raw provider words")
        val transformed = event(before, "done", json("status" to "complete", "text" to "The corrected final.", "response_transformed" to true))
        assertEquals("The corrected final.", transformed.liveText)
        val reused = event(transformed, "done", json("status" to "complete", "text" to "The corrected final.", "response_reused" to true))
        assertEquals("The corrected final.", reused.liveText)
        assertTrue("Completion must not invent a native row", reused.messages.isEmpty())
    }

    @Test fun epochResetRetainsUnconfirmedWordsAsDisplayOnlyAndDoesNotReviveTheirStreamAuthority() {
        val before = ChatState(sessionId = session, streamId = "old-runtime", liveText = "Already visible reply",
            liveReasoning = "Visible notes", raw = saved(row(1, "user", "fix it please")))
        val reset = saved(row(1, "user", "fix it please")).put("replay_epoch", "new-epoch").put("running", false)
            .put("pending", JSONArray().put(json("status" to "unknown", "admission_id" to "admission-image")))
        val after = event(before, "state_saved", json("replay_reset" to true, "session" to reset))
        val retained = after.raw.rows("_unconfirmed_replies").singleOrNull()
        assertNotNull("An epoch reset cannot silently discard the only displayed reply", retained)
        assertEquals("Already visible reply", retained?.string("text"))
        assertEquals(false, retained?.opt("actionable"))
        assertEquals("", after.liveText)
        assertEquals("", after.streamId)
        val reopened = JSONObject(after.raw.toString())
        assertEquals("Already visible reply", reopened.rows("_unconfirmed_replies").single().string("text"))
        assertEquals("Turn status unknown", after.status)
    }

    @Test fun completedCallArchiveUnavailableThenAvailableDoesNotEraseTheEarlierCall() {
        val voice = json("call_id" to "call-a", "event_id" to "call-a:user:1", "turn_id" to "1", "session_id" to session,
            "role" to "user", "text" to "Earlier call", "final" to true, "created_at" to 1_799_999_990.0)
        val old = saved(row(1, "user", "fix it please"), row(2, "assistant", "The image is fixed."))
            .put("voice_transcripts", JSONArray().put(voice)).put("voice_transcripts_available", true)
        val before = ChatState(sessionId = session, raw = old)
        val unavailable = saved(row(1, "user", "fix it please"), row(2, "assistant", "The image is fixed."))
            .put("voice_transcripts_available", false).put("voice_transcripts", JSONArray())
        val after = event(before, "state_saved", json("session" to unavailable))
        assertEquals("call-a:user:1", after.raw.rows("voice_transcripts").singleOrNull()?.string("event_id"))
        assertFalse(after.raw.optBoolean("voice_transcripts_available"))
        val authorizedEmpty = event(after, "state_saved", json("session" to unavailable.copy()
            .put("voice_transcripts_available", true).put("voice_transcripts_truncated", false)))
        assertTrue("A successful complete empty archive still honors retention/deletion", authorizedEmpty.raw.rows("voice_transcripts").isEmpty())
    }

    @Test fun anOlderSameEpochNativeSnapshotCannotRetractTheConfirmedFinalRow() {
        val rows = listOf(row(1, "user", "fix it please"), row(2, "assistant", "The image is fixed."))
        val before = ChatState(sessionId = session, raw = saved(*rows.toTypedArray()),
            messages = rows.mapIndexed { i, item -> Message.from(item, i, session) })
        val older = saved(rows.first()).put("last_sequence", 8)
        val after = event(before, "state_saved", json("session" to older))
        assertEquals(listOf("1", "2"), after.messages.map { it.id })
        assertEquals(10, after.raw.optInt("last_sequence"))
    }

    @Test fun unstampedPartialHistoryRetainsTheLatestMissingReplyByRowIdentityWithoutInventingNativeHistory() {
        val user = row(1, "user", "fix it please")
        val final = row(2, "assistant", "The image is fixed.")
        val before = ChatState(sessionId = session, raw = saved(user, final),
            messages = listOf(Message.from(user, 0, session), Message.from(final, 1, session)))
        val partial = json("session_id" to session, "profile" to profile, "messages" to JSONArray().put(user))
        val after = event(before, "state_saved", json("session" to partial))
        assertEquals(listOf("1"), after.messages.map { it.id })
        assertEquals(listOf("1"), after.raw.rows("messages").map { it.string("row_id") })
        val artifact = after.raw.rows("_unconfirmed_replies").single()
        assertEquals("2", artifact.string("_history_row_id"))
        assertEquals("The image is fixed.", artifact.string("text"))
        assertEquals(false, artifact.opt("actionable"))
        val reopened = JSONObject(after.raw.toString())
        assertEquals("The image is fixed.", reopened.rows("_unconfirmed_replies").single().string("text"))
        val sameWordsDifferentRow = partial.copy().put("messages", JSONArray().put(user).put(row(3, "assistant", "The image is fixed.")))
        assertEquals("2", mergeChatProjection(reopened, sameWordsDifferentRow).rows("_unconfirmed_replies").single().string("_history_row_id"))
        val exactRowReturns = partial.copy().put("messages", JSONArray().put(user).put(row(2, "assistant", "Corrected persisted final")))
        assertTrue(mergeChatProjection(reopened, exactRowReturns).rows("_unconfirmed_replies").isEmpty())
        assertTrue(mergeChatProjection(reopened, partial, allowRemoval = true).rows("_unconfirmed_replies").isEmpty())
        assertEquals(2, mergeChatProjection(before.raw, json("session_id" to session, "profile" to profile)).rows("messages").size)
    }

    @Test fun completedCallThenImageAndReplyKeepOrderThroughFailedRefreshAndProcessReopen() {
        val image = json("id" to "attachment-a", "name" to "image.png", "mime" to "image/png", "scope_key" to "fixture-scope", "staged_base64" to "Zml4dHVyZQ==")
        val input = QueuedMessage("fix it please", listOf(image), id = "local-image", state = "accepted", afterRowId = "",
            submissionId = "submission-image", admissionId = "admission-image", createdAt = 1_800_000_001_000L)
        val call = CallTranscript("call-a:user:1", "user", "Earlier call", at = 1_799_999_990_000L)
        fun display(rows: List<Message>, local: List<QueuedMessage>, calls: List<CallTranscript>) = interleaveCallMessages(
            chatTimelineMessages(rows, local), calls.map { Message("call:${it.eventId}", it.role, it.text, recordedAt = it.at) })
        val original = ChatState(sessionId = session, queued = listOf(input), callMessages = listOf(call), streamId = session,
            raw = saved().put("_display_submission_id", input.submissionId).put("_display_admission_id", input.admissionId))
        assertEquals(listOf("call:call-a:user:1", "queued:local-image"), display(original.messages, original.queued, original.callMessages).map { it.id })
        var responding = event(original, "token", json("text" to "Inspecting the image"))
        responding = event(responding, "tool", json("id" to "tool-image", "name" to "image_tool"))
        assertEquals("Inspecting the image", responding.liveText)
        assertEquals(1, responding.liveTools.size)
        responding = event(responding, "done", json("status" to "complete", "text" to "The image is fixed."))
        val rawRows = listOf(row(101, "user", "fix it please\n[image]").put("attachments", JSONArray().put(image)), row(102, "assistant", "The image is fixed."))
        val archived = json("call_id" to "call-a", "event_id" to call.eventId, "session_id" to session, "turn_id" to "1",
            "role" to "user", "text" to call.text, "final" to true, "created_at" to call.at / 1000.0)
        val complete = saved(*rawRows.toTypedArray()).put("voice_transcripts", JSONArray().put(archived)).put("voice_transcripts_available", true)
        val loaded = event(responding, "state_saved", json("session" to complete))
        val failed = complete.copy().put("voice_transcripts", JSONArray()).put("voice_transcripts_available", false)
        val refreshed = event(loaded, "state_saved", json("session" to failed))
        val encoded = JSONObject(refreshed.raw.toString())
        val scope = Scope("https://fixture.example", "owner", profile, session)
        val calls = archivedVoiceTranscripts(scope, json("session_id" to session, "profile" to profile, "events" to encoded.optJSONArray("voice_transcripts")))
        val reopened = encoded.rows("messages").mapIndexed { index, value -> Message.from(value, index, session) }
        val linked = input.copy(rowId = "101", state = "settled", outcome = "completed")
        assertEquals(listOf("call:call-a:user:1", "101", "102"), display(reopened, listOf(linked), calls).map { it.id })
        assertEquals("image.png", reopened.first().attachments.single().string("name"))
        assertEquals(input.encode().toString(), QueuedMessage.decode(input.encode()).encode().toString())
        assertEquals("The image is fixed.", reopened.last().text)
    }

    @Test fun localInputAnchorKeepsNativeOrderEvenWhenTheirClocksRegress() {
        val before = Message("1", "assistant", "Before", recordedAt = 9_000)
        val after = Message("2", "assistant", "After", recordedAt = 1_000)
        val input = QueuedMessage("Image", id = "image", state = "accepted", afterRowId = "1", createdAt = 5_000)
        assertEquals(listOf("1", "queued:image", "2"), chatTimelineMessages(listOf(before, after), listOf(input)).map { it.id })
        assertEquals(listOf("1", "2"), chatTimelineMessages(listOf(before, after), listOf(input.copy(state = "queued"))).map { it.id })
    }

    @Test fun nativeSteeringAndTypedHiddenTailsCannotMoveAnAdmittedInputBelowItsReply() {
        val before = Message("1", "assistant", "Earlier reply")
        val steering = Message("2", "user", "[System: Continue the existing turn]")
        val hidden = Message("3", "user", "Internal scaffold", displayKind = "hidden")
        val original = listOf(before, steering, hidden)
        val input = QueuedMessage("fix it please", id = "image", state = "accepted", afterRowId = pendingSendBoundary(original))
        assertEquals("1", input.afterRowId)
        val later = Message("4", "assistant", "Fixed")
        assertEquals(listOf("1", "queued:image", "4"), chatTimelineMessages(visibleTranscriptMessages(original + later), listOf(input)).map { it.id })
        assertEquals("", pendingSendBoundary(listOf(steering, hidden)))
    }

    @Test fun anAlreadyAccepted1132ItemUsesOnlyAClearSavedTimeBoundaryAndKeepsItsIdentity() {
        val old = json("id" to "old-image", "text" to "fix it please", "created_at" to 1_800_000_010_000L,
            "state" to "accepted", "admission_id" to "old-admission", "submission_id" to "old-submission",
            "attachments" to JSONArray().put(json("name" to "old.png", "mime" to "image/png", "staged_base64" to "Zml4dHVyZQ==")))
        val decoded = QueuedMessage.decode(JSONObject(old.toString()))
        assertNull(decoded.afterRowId)
        val before = Message("1", "assistant", "Earlier", recordedAt = 1_800_000_000_000L)
        val after = Message("2", "assistant", "Later work", recordedAt = 1_800_000_020_000L)
        val ordered = chatTimelineMessages(listOf(before, after), listOf(decoded))
        assertEquals(listOf("1", "queued:old-image", "2"), ordered.map { it.id })
        assertEquals("local_input", ordered[1].displayKind)
        assertEquals("old-submission", decoded.submissionId)
        assertEquals("old-admission", decoded.admissionId)
        assertEquals("", decoded.rowId)
        assertEquals("old.png", decoded.attachments.single().string("name"))
        for (unclear in listOf(listOf(before.copy(recordedAt = 0), after), listOf(after, before), listOf(before, after.copy(recordedAt = decoded.createdAt)))) {
            val unresolved = chatTimelineMessages(unclear, listOf(decoded))
            assertEquals(unclear.map { it.id }, unresolved.filterNot { it.id.startsWith("queued:") }.map { it.id })
            assertEquals("local_input_unresolved", unresolved.last().displayKind)
        }
        val unknownTime = decoded.copy(createdAt = 0)
        assertEquals("local_input_unresolved", chatTimelineMessages(listOf(before, after), listOf(unknownTime)).last().displayKind)
    }

    @Test fun explicitWholeConversationRemovalAndScopeChangeDoNotCarryUnconfirmedOutputIntoAnotherConversation() {
        val before = retainUnconfirmedReply(ChatState(sessionId = session, streamId = session, liveText = "Unconfirmed",
            raw = saved().put("_display_submission_id", "submission-image")))
        val ordinary = mergeChatProjection(before.raw, saved())
        assertEquals(1, ordinary.rows("_unconfirmed_replies").size)
        assertTrue(mergeChatProjection(before.raw, saved(), allowRemoval = true).rows("_unconfirmed_replies").isEmpty())
        assertTrue(mergeChatProjection(before.raw, saved().put("session_id", "different")).rows("_unconfirmed_replies").isEmpty())
        assertTrue(mergeChatProjection(before.raw, saved().put("profile", "different")).rows("_unconfirmed_replies").isEmpty())
    }

    @Test fun confirmedUndoRetiresOnlyArtifactsAttributedToItsExactNativeUserRow() {
        val user = row(1, "user", "fix it please")
        val final = row(2, "assistant", "Removed reply")
        val before = saved(user, final)
        val partial = json("session_id" to session, "profile" to profile, "messages" to JSONArray().put(user))
        val retained = mergeChatProjection(before, partial)
        assertEquals("1", retained.rows("_unconfirmed_replies").single().string("_history_user_row_id"))
        retained.getJSONArray("_unconfirmed_replies").put(json("id" to "unconfirmed:unbound", "role" to "assistant",
            "text" to "Unrelated uncertainty", "actionable" to false, "display_kind" to "unconfirmed_reply"))
        val empty = json("session_id" to session, "profile" to profile, "messages" to JSONArray())
        val confirmed = mergeChatProjection(retained, empty, allowRemoval = true, removedUserRowId = "1")
        assertEquals(listOf("Unrelated uncertainty"), confirmed.rows("_unconfirmed_replies").map { it.string("text") })
        assertEquals(2, mergeChatProjection(retained, empty).rows("_unconfirmed_replies").size)
        assertEquals(2, mergeChatProjection(retained, empty, allowRemoval = true, removedUserRowId = "999").rows("_unconfirmed_replies").size)
    }
}
