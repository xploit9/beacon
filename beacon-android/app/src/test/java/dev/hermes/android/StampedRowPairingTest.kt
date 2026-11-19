package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CHAT-121 follow-up 5. A session authority stamps the phone's submission id on the user row an admission's turn saves (`display_metadata.submission_id`:
 * gateway/session_display.submission_turn_scope, gateway/response_filters.persisted_user_metadata_for_event; Hermes' own pin is
 * tests/gateway/test_submission_row_identity.py). A row stamped for another send is that send's row, whatever its words say, so the phone never pairs
 * its copy with it by words. Synthetic text only.
 */
class StampedRowPairingTest {
    private fun copy(text: String, submission: String) = QueuedMessage(text, id = "copy-$submission", state = "accepted", admissionId = "admission-$submission",
        submissionId = submission, afterRowId = "2", admissionStatus = "started")
    private fun row(id: String, text: String, role: String = "user") = json("row_id" to id, "role" to role, "content" to text)
    private val earlier = listOf(row("1", "Earlier question"), row("2", "Earlier answer", "assistant"))

    @Test fun aRowHermesStampedForAnotherSendIsNeverThisCopysRow() {
        val sent = copy("Book the 11:45 instead", "phone-send-1")
        // The same words, sent from another client (or an earlier send of this phone): Hermes stamped that send's id on its row.
        val other = row("3", "Book the 11:45 instead").put("display_metadata", json("submission_id" to "desktop-send-7"))
        assertEquals(emptyMap<String, String>(), acceptedRowPairs(listOf(sent), earlier + other))
        // Stored as text (an older store returns the column undecoded): the same.
        val asText = row("3", "Book the 11:45 instead").put("display_metadata", json("submission_id" to "desktop-send-7").toString())
        assertEquals(emptyMap<String, String>(), acceptedRowPairs(listOf(sent), earlier + asText))
    }

    @Test fun aRowWithNoStampIsStillPairedByWordsAsBefore() {
        // An older Hermes stamps nothing: the words pairing works as it did.
        val sent = copy("Book the 11:45 instead", "phone-send-1")
        assertEquals(mapOf(sent.id to "3"), acceptedRowPairs(listOf(sent), earlier + row("3", "Book the 11:45 instead")))
        val metadataWithoutStamp = row("3", "Book the 11:45 instead").put("display_metadata", json("gateway_input_owner" to "synthetic-owner"))
        assertEquals(mapOf(sent.id to "3"), acceptedRowPairs(listOf(sent), earlier + metadataWithoutStamp))
    }
}
