package dev.hermes.android.chatqa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHATQA-121 follow-up 6 (backlog 80), read from source; every test fails on fix 5. The files a send carried were kept under the chat stream
 * (`attachments-pending:<stream>`) and only a row link deleted them, so a send whose row never linked gave its files and words to the next linked row.
 */
class PendingFilesWiringTest {
    private val module: File by lazy { requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) }
    private val model: String by lazy { File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n") }
    private fun body(start: String, end: String) = model.substringAfter(start, "").substringBefore(end).also { assertTrue("found: $start", it.isNotBlank()) }

    @Test fun noSendIsKeptUnderTheChatStream() {
        // The only place that still names the stream kind is the reader of a 1.2.0 record; nothing writes it.
        val writes = Regex("""app\.cache\.write\(scope, "attachments-pending:\$""").findAll(model).count() +
            Regex("""app\.cache\.write\(scope, legacyPendingAttachmentsKind""").findAll(model).count()
        assertEquals("no write under the chat stream", 0, writes)
        assertEquals("three places save a send's files, each under its submission id", 3, Regex("""app\.cache\.write\(scope, pendingAttachmentsKind\(send\.submissionId\), payload\)""").findAll(model).count())
    }

    @Test fun theLinkTakesTheSendsOwnRecordAndChecksTheRow() {
        val bind = body("private suspend fun bindPersistedInput(", "keepLinkedAttachments(scope, item, linked, current)")
        assertTrue(bind.contains("app.cache.read(scope, pendingAttachmentsKind(item.submissionId))"))
        assertTrue("a 1.2.0 record is read by its stream, and only moved when it is exactly this send's", bind.contains("legacyPendingAttachmentsKind(it)") && bind.contains("pendingRecordForRow(own, legacy, item,"))
        assertTrue("the record is cleared once the link is settled, whatever it was", bind.contains("forgetPendingAttachments(scope, item.submissionId, stream)"))
        assertFalse("the stream is no longer the key", bind.contains("\"attachments-pending:\$stream\""))
    }

    @Test fun everyEndOfASendWithoutARowDropsItsFiles() {
        for ((name, start, end) in listOf(
            Triple("Remove this copy", "    fun removePendingSend() = viewModelScope.launch {", "\n    }\n"),
            Triple("Remove from the box", "    fun removeQueued(id: String) = viewModelScope.launch {", "\n    }\n"),
            Triple("Remove a local copy", "    fun removeUnresolvedLocalMessage(id: String) = viewModelScope.launch {", "\n    }\n"),
            Triple("Edit from the box", "    fun editQueued(id: String) = viewModelScope.launch {", "\n    }\n"),
            Triple("Hermes holds the send", "    private suspend fun forgetAnsweredCopy(", "\n    }\n"),
            Triple("a copy paired with Hermes' row", "    private suspend fun pairAcceptedCopies(", "\n    }\n"),
        )) assertTrue("$name drops the send's kept files", body(start, end).contains("forgetPendingAttachments(scope, "))
    }

    @Test fun aRecordOfAnotherSendIsNotShownOnARow() {
        val render = body("    private suspend fun renderSession(", "if (sourceScope != scope || expectedRevision != null && expectedRevision != chatHistoryRevision) return\n        ++chatHistoryRevision")
        assertTrue("a row's kept receipt is applied only when it does not name another submission", render.contains("attachmentRecordFitsRow(it, row)"))
    }
}
