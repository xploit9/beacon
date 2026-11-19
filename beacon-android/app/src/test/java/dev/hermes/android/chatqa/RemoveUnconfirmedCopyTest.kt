package dev.hermes.android.chatqa

import dev.hermes.android.ui.LocalAction
import dev.hermes.android.ui.UNCONFIRMED_SEND_ACTIONS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHATQA-121 follow-up 4, read from source (fails on fix 3): "Remove this copy" on a send whose outcome is not known removed nothing. It called
 * reviewPendingSend(), which put the words back in the box as an "unknown" copy, saved, so the bubble stayed and came back after a restart.
 */
class RemoveUnconfirmedCopyTest {
    private val module: File by lazy { requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) }
    private fun source(path: String) = File(module, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")
    private fun body(text: String, start: String, end: String) = text.substringAfter(start, "").substringBefore(end).also { assertTrue("found: $start", it.isNotBlank()) }

    @Test fun removeOnAnUnconfirmedSendRemovesItsCopy() {
        assertTrue("the unconfirmed send offers Remove", LocalAction.RemoveCopy in UNCONFIRMED_SEND_ACTIONS)
        val screen = source("ui/ChatScreen.kt")
        val perform = body(screen, "private fun performLocalAction(", "\n}\n")
        assertTrue(perform.contains("LocalAction.RemoveCopy -> if (unconfirmed) vm.removePendingSend() else vm.removeUnresolvedLocalMessage(copyId)"))
        assertFalse("Remove does not keep the words as a copy", perform.contains("LocalAction.RemoveCopy -> if (unconfirmed) vm.reviewPendingSend()"))
    }

    @Test fun theRemovalForgetsEverySavedTraceAndNeverSends() {
        val remove = body(source("BeaconViewModel.kt"), "    fun removePendingSend() = viewModelScope.launch {", "\n    }\n")
        for (step in listOf(
            "if (!pending.remove(scope.key, unknown)) return@launch",            // nothing follows, checks or sends it from here on
            "_chat.update { it.copy(uncertain = \"\", status = \"\") }",          // the bubble goes at once
            // Chat join: the hold on a box copy is its QueuedMessage.held, which goes with the copy (the next step); the merge has no pause map.
            "updateQueued(scope, unknown.id) { null }",                            // the box's copy, saved
            "deletePending(unknown)",                                               // the saved send record
            "app.cache.delete(scope, \"pending\", raw)",                           // a record saved under another encoding
            "app.cache.delete(scope, RUN_MARKER, unknown.submissionId)",            // the run marker it wrote
        )) assertTrue(step, remove.contains(step))
        for (call in listOf("reviewPendingSend", "drainQueued", "send(", "/api/chat/start", "returnUnsentWords", "rememberOutbox", "openForm"))
            assertFalse("Remove never calls $call", remove.contains(call))
    }
}
