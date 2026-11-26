package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.LocalBeaconSettings
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.messageTimeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Owner, 25 Nov: "these chat do not have a timestamp which pushes it below a newer chat". A reply Beacon kept when it could not prove its turn showed no
 * time and sorted below the newer question, so the newest message and its answer hid under it. This draws the real [ChatScreen] with synthetic rows only,
 * no account, provider or network, and reads what the screen actually says and where it stands.
 */
class KeptReplyOrderUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)

    private fun scope() = Scope("https://synthetic.example", "synthetic-owner", "default", nativeProvider = "hermes-dashboard").session("chat-order-fixture")

    @Test fun keptReplyShowsItsTimeAndStaysAboveTheNewerTurn() {
        assertFalse("Never replace an active real call", app.calls.state.value.active || app.calls.service != null)
        val target = scope()
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        // Row ids grow with time, as Hermes' do: 10/11 is the first turn, 30/31 the newer question and its answer.
        val firstAsk = json("row_id" to 10, "role" to "user", "content" to "Look into the paper", "timestamp" to 1_764_000_000.0)
        val firstPart = json("row_id" to 11, "role" to "assistant", "content" to "Reading the PDF now.", "timestamp" to 1_764_000_020.0)
        val newerAsk = json("row_id" to 30, "role" to "user", "content" to "Try the other route", "timestamp" to 1_764_003_600.0)
        val newerAnswer = json("row_id" to 31, "role" to "assistant", "content" to "That found the table.", "timestamp" to 1_764_003_620.0)
        // The row 1.2.4 kept when Hermes stopped returning row 12: no timestamp at all, the shape the owner's chat held.
        val kept = json("id" to "unconfirmed-history:12", "role" to "assistant", "text" to "Found the two missing rows.",
            "actionable" to false, "display_kind" to "unconfirmed_reply", "_history_row_id" to "12", "_history_user_row_id" to "10")
        val raw = json("session_id" to target.session, "profile" to target.profile, "messages" to JSONArray().put(firstAsk).put(firstPart).put(newerAsk).put(newerAnswer),
            "_unconfirmed_replies" to JSONArray().put(kept))
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", null)
                field(vm, "sourceScope", target)
                flow<AppState>(vm, "_ui").value = AppState(origin = target.origin, authenticated = true, owner = target.owner,
                    profile = target.profile, chatProfile = target.profile, tab = "Chat", connected = false, restoringSession = false)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = target.session, title = "Kept reply",
                    raw = raw, messages = raw.rows("messages").mapIndexed { i, row -> Message.from(row, i, target.session) }, projectionReady = true)
            }
            compose.setContent { NativeLocalization("en") { BeaconTheme {
                CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) { ChatScreen(vm) }
            } } }
            compose.waitForIdle()
            val reply = compose.onNodeWithTag("unconfirmed-reply:unconfirmed-history:12", useUnmergedTree = true)
            reply.assertExists()
            val replyTop = reply.fetchSemanticsNode().boundsInRoot.top
            val newerTop = compose.onNodeWithTag("message-row:30", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            val newerAnswerTop = compose.onNodeWithTag("message-row:31", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top
            // The time the owner reported missing: a real clock on the kept bubble, the same 24/12-hour one every bubble uses.
            val shownTime = compose.onAllNodesWithTag("message-time:unconfirmed-history:12", useUnmergedTree = true).fetchSemanticsNodes()
                .firstOrNull()?.config?.let { config ->
                    if (config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text))
                        config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ") { it.text } else ""
                }.orEmpty()
            val expectedTime = requireNotNull(messageTimeLabel(1_764_000_020_000L, is24Hour = android.text.format.DateFormat.is24HourFormat(compose.activity)))
            // The capture and its measurements are taken before the assertions, so a build without the fix still shows the owner's own screen.
            capture("device-chat-order")
            java.io.File(compose.activity.filesDir, "chat-order.json").writeText(json("time" to shownTime, "replyTop" to replyTop,
                "newerTop" to newerTop, "answerTop" to newerAnswerTop).toString())
            // What the owner must be able to read at once: the kept reply, the newer message and its answer.
            listOf("Found the two missing rows.", "Try the other route", "That found the table.").forEach { words ->
                assertEquals(words, 1, compose.onAllNodes(hasText(words), useUnmergedTree = true).fetchSemanticsNodes().size)
            }
            assertEquals("the kept reply says when it was said", expectedTime, shownTime)
            assertTrue("the kept reply is above the newer question: reply=$replyTop newer=$newerTop", replyTop < newerTop)
            assertTrue("and above the newer answer: reply=$replyTop answer=$newerAnswerTop", replyTop < newerAnswerTop)
        } finally {
            compose.runOnUiThread { store.clear(); app.calls.ownerScope = null }
            kotlinx.coroutines.runBlocking { app.cache.delete(target, "session"); app.cache.delete(target, "draft") }
        }
    }

    /** A real screen capture: what the owner would see, not a claim about it. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = java.io.File(compose.activity.filesDir, "chat-order").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
