package dev.hermes.android.chatqa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CHATQA-121 follow-up 3, read from source (each fails on fix 2): where the open chat looks at Hermes' live inventory, and the background send's run marker. */
class ObservedTurnWiringTest {
    private val vm: String by lazy {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
    }
    private fun body(start: String, end: String): String = vm.substringAfter(start, "").substringBefore(end).also { assertTrue("found: $start", it.isNotBlank()) }

    @Test fun anOpenWithNoRunMarkedHereLooksOnceAndOnlyLists() {
        val reattach = body("    private suspend fun reattachMarkedRun(", "    private suspend fun followUnseenTurn(")
        // Chat join: wedge-121-6 reads the marker itself (it deletes it after a route close), so the flag is `marker != null`.
        assertTrue(reattach.contains("if (!shouldAskRunning(marker != null, _chat.value.streamId)) return followUnseenTurn(c, scope, request)"))
        val look = body("    private suspend fun followUnseenTurn(", "    internal fun settingsProfileClient()")
        assertTrue("only when the history stops inside a turn", look.contains("!historyStopsInsideATurn(shown.raw.rows(\"messages\"))"))
        assertTrue("one read of the live inventory", look.contains("c.nativeRpc(\"session.active_list\", JSONObject())"))
        for (call in listOf("session.resume", "recoveredNativeSession", "nativeSession(", "prompt.submit", "/api/chat/start"))
            assertFalse("the look never calls $call", look.contains(call))
        assertTrue("a running reply shows with Stop and is followed", look.contains("_chat.update { it.copy(streamId = runtime, status = \"Working\") }") && look.contains("attachStream(c, scope, runtime)"))
    }

    @Test fun theWatcherLooksOnlyWhenTheHistoryMoved() {
        val watch = body("    private fun watchSession(c: BeaconClient, scope: Scope) {", "    private fun ")
        assertTrue(watch.contains("val historyMoved = renderedServerProjection != projection"))
        assertTrue(watch.contains("} else if (historyMoved && running.isBlank()) followUnseenTurn(c, scope, request)"))
    }

    @Test fun theBackgroundSendMarksItsRunLikeTheOpenChatsSend() {
        val outbox = body("    private suspend fun sendWaiting(owner: Scope, scope: Scope): Boolean {", "    private suspend fun awaitChatIdle(")
        assertTrue(outbox.contains("if (after.state == \"accepted\") optionalLocalSave { app.cache.write(scope, RUN_MARKER, item.submissionId) }"))
    }
}
