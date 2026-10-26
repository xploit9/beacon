package dev.hermes.android.ui

import dev.hermes.android.AppState
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.QuietWindow
import dev.hermes.android.STREAM_QUIET_MAX_MS
import dev.hermes.android.SilentStream
import dev.hermes.android.core.Draft
import dev.hermes.android.core.json
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.historyRowOf
import dev.hermes.android.plainChatRequest
import dev.hermes.android.reconnectOnNetworkReturn
import dev.hermes.android.reusableEmptyChat
import dev.hermes.android.sendArrived
import dev.hermes.android.sentCopies
import dev.hermes.android.silentStreamAction
import org.junit.Assert.*
import org.junit.Test

class UnconfirmedSendTest {
    private fun history(vararg rows: Pair<String, String>) = rows.mapIndexed { i, (role, text) -> Message("m$i", role, text) }

    @Test fun historyHoldingOneMoreCopyProvesTheSendArrived() {
        val before = sentCopies(history("user" to "hello", "assistant" to "hi"), "hello")
        val after = history("user" to "hello", "assistant" to "hi", "user" to "hello ", "assistant" to "again")
        assertEquals(1, before)
        assertTrue(sendArrived(before, after, Draft("hello")))
    }

    @Test fun anEarlierIdenticalMessageIsNotTheSend() {
        val same = history("user" to "continue", "assistant" to "ok")
        assertFalse(sendArrived(sentCopies(same, "continue"), same, Draft("continue")))
    }

    @Test fun nothingIsProvedWithoutAStartingCountOrForAttachmentsAndBlankDrafts() {
        val after = history("user" to "hello")
        assertFalse(sendArrived(null, after, Draft("hello")))
        assertFalse(sendArrived(0, after, Draft("hello", attachments = listOf(json("name" to "a.png")))))
        assertFalse(sendArrived(0, history("user" to ""), Draft("  ")))
        assertFalse(sendArrived(0, history("assistant" to "hello"), Draft("hello")))
    }

    @Test fun savedHistoryOfASignedInAccountReconnectsWhenTheNetworkReturns() {
        val offline = AppState(origin = "https://hermes.example", authenticated = true, connected = false, loading = false, setupWizard = false)
        assertTrue(reconnectOnNetworkReturn(offline))
        assertFalse(reconnectOnNetworkReturn(offline.copy(connected = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(loading = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(setupWizard = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(authenticated = false)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(origin = "")))
    }

    @Test fun aStreamThatHeardNothingSettlesAnEndedTurnAndResubscribesARunningOne() {
        assertEquals("The reply is in history", SilentStream.Settle, silentStreamAction("idle"))
        assertEquals("Hermes still runs the turn", SilentStream.Resubscribe, silentStreamAction("working"))
        assertEquals("A turn waiting for the user is quiet on purpose", SilentStream.Wait, silentStreamAction("waiting"))
        assertEquals("An unreadable status changes nothing", SilentStream.Wait, silentStreamAction(null))
    }

    @Test fun newConversationKeepsOnlyAnUnsentPhoneCreatedChat() {
        val empty = ChatState(sessionId = "new-1")
        assertTrue(reusableEmptyChat(true, empty, sendInFlight = false, keptApart = false))
        assertFalse("Hermes already holds this chat", reusableEmptyChat(false, empty, sendInFlight = false, keptApart = false))
        assertFalse(reusableEmptyChat(true, empty.copy(messages = history("user" to "hello")), sendInFlight = false, keptApart = false))
        assertFalse(reusableEmptyChat(true, empty.copy(raw = json("message_count" to 2)), sendInFlight = false, keptApart = false))
        assertFalse(reusableEmptyChat(true, empty.copy(streamId = "runtime-1"), sendInFlight = false, keptApart = false))
        assertFalse(reusableEmptyChat(true, empty.copy(uncertain = "send-1"), sendInFlight = false, keptApart = false))
        assertFalse(reusableEmptyChat(true, empty, sendInFlight = true, keptApart = false))
        assertFalse("Still loading", reusableEmptyChat(true, empty.copy(projectionReady = false), sendInFlight = false, keptApart = false))
        assertFalse("No chat behind the screen", reusableEmptyChat(true, ChatState(), sendInFlight = false, keptApart = false))
        assertFalse("A thread or a chat with a workspace or model is its own chat", reusableEmptyChat(true, empty, sendInFlight = false, keptApart = true))
    }

    @Test fun onlyTheAutomaticOpenAndABareNewConversationAskForAPlainChat() {
        assertTrue(plainChatRequest(json()))
        assertTrue("The automatic open", plainChatRequest(json("worktree" to false)))
        assertFalse(plainChatRequest(json("title" to "A thread", "thread" to true)))
        assertFalse(plainChatRequest(json("workspace" to "/work")))
        assertFalse(plainChatRequest(json("model" to "m", "model_provider" to "p")))
        assertFalse(plainChatRequest(json("worktree" to true)))
    }

    @Test fun aQuietStreamStretchesItsWindowOnlyOnHermesAnswersAndStartsOverOnAnEvent() {
        val window = QuietWindow(45_000)
        assertEquals(45_000L, window.ms)
        assertEquals("Looking ahead changes nothing", 90_000L, window.stretched())
        assertEquals(45_000L, window.ms)
        window.stretch(); window.stretch()
        assertEquals(180_000L, window.ms)
        repeat(10) { window.stretch() }
        assertEquals("The window never passes its cap", STREAM_QUIET_MAX_MS, window.ms)
        window.heard()
        assertEquals("A heard event starts over", 45_000L, window.ms)
        // A resubscribed stream begins at the stretched window and still starts over at the base one.
        val resubscribed = QuietWindow(45_000, 90_000)
        assertEquals(90_000L, resubscribed.ms)
        resubscribed.heard()
        assertEquals(45_000L, resubscribed.ms)
    }

    @Test fun aHistoryRowIsASummaryWithoutTheChatsMessages() {
        val session = json("session_id" to "chat-1", "title" to "Trip", "message_count" to 2, "messages" to org.json.JSONArray().put(json("role" to "user", "content" to "hi")),
            "voice_transcripts" to org.json.JSONArray().put(json("text" to "spoken")))
        val row = historyRowOf(session)
        assertEquals("Trip", row.getString("title"))
        assertEquals(2, row.getInt("message_count"))
        assertFalse(row.has("messages"))
        assertFalse(row.has("voice_transcripts"))
        assertTrue("The chat itself is untouched", session.has("messages") && session.has("voice_transcripts"))
    }

    @Test fun theChatMenuFollowsTheRealPinnedState() {
        val rows = listOf(
            json("session_id" to "chat-1", "profile" to "default", "pinned" to true),
            json("session_id" to "chat-2", "profile" to "default", "pinned" to false),
        )
        assertTrue("The chat's own snapshot never says pinned", openChatPinned(rows, ChatState(sessionId = "chat-1"), "default"))
        assertFalse("History's row wins over a stale mark", openChatPinned(rows, ChatState(sessionId = "chat-2", raw = json("pinned" to true)), "default"))
        assertTrue("An unlisted chat keeps what an action in it recorded", openChatPinned(rows, ChatState(sessionId = "chat-3", raw = json("pinned" to true)), "default"))
        assertFalse("Another profile's row is not this chat's", openChatPinned(rows, ChatState(sessionId = "chat-1"), "coder"))
    }

    @Test fun theAddressErrorAsksForTheDashboardLink() {
        assertEquals("Paste the https:// link from your Hermes dashboard, with nothing after the address.", runCatching { trustedOrigin("http://hermes.example") }.exceptionOrNull()?.message)
        assertEquals("https://hermes.example", trustedOrigin("https://hermes.example/"))
    }
}
