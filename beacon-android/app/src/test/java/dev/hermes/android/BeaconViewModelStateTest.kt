package dev.hermes.android

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BeaconViewModelStateTest {
    @Test fun registeredAssistantConversationIsAdoptedOnlyWhenItChanges() {
        assertEquals("shared-chat", assistantConversationToAdopt("phone-chat", "shared-chat"))
        assertNull(assistantConversationToAdopt("shared-chat", "shared-chat"))
        assertNull(assistantConversationToAdopt("phone-chat", ""))
        assertNull(assistantConversationToAdopt("phone-chat", null))
    }

    @Test fun registeredConversationOpensOnlyForTheOriginalChatInTheSameScope() {
        val profile=Scope("https://hermes.example", "owner", "default")
        assertEquals("shared-chat",assistantConversationToSelect(profile.session("phone-chat"),"phone-chat",profile,"phone-chat","shared-chat",true,""))
        assertNull(assistantConversationToSelect(profile.copy(profile="other").session("phone-chat"),"phone-chat",profile,"phone-chat","shared-chat",true,""))
        assertNull(assistantConversationToSelect(profile.session("other-chat"),"other-chat",profile,"phone-chat","shared-chat",true,""))
        assertNull(assistantConversationToSelect(profile.session("phone-chat"),"phone-chat",profile,"phone-chat","phone-chat",true,""))
        assertNull(assistantConversationToSelect(profile.session("phone-chat"),"phone-chat",profile,"phone-chat","shared-chat",false,""))
        assertNull(assistantConversationToSelect(profile.session("phone-chat"),"phone-chat",profile,"phone-chat","shared-chat",true,"streaming"))
    }

    @Test fun serverRequestPromptIsShownAndAnExplicitNullPendingClearsIt() {
        val request = JSONObject("{\"approval_id\":\"queue-1\",\"command\":\"ls\"}")
        assertEquals("queue-1", pendingPrompt(request)?.optString("approval_id"))
        assertEquals("queue-2", pendingPrompt(JSONObject("{\"pending\":{\"approval_id\":\"queue-2\"}}"))?.optString("approval_id"))
        assertNull(pendingPrompt(JSONObject("{\"pending\":null}")))
    }

    @Test fun releasedAssistantTabValueMigrates() {
        assertEquals("Beacon", migratedTab("Xara"))
        assertEquals("Chat", migratedTab("Chat"))
    }

    @Test fun terminalCompletionReleasesStreamAndKeepsUnconfirmedOutputVisible() {
        val status = "Reply ended · final reply not confirmed; live output retained"
        val running = ChatState(
            sessionId = "chat",
            streamId = "runtime",
            liveText = "partial answer",
            liveReasoning = "working notes",
            liveTools = listOf(JSONObject("{\"name\":\"terminal\"}")),
            status = status,
        )

        val ended = releaseTerminalChat(running, "runtime", replyConfirmed = false, terminalStatus = status)

        assertEquals("", ended.streamId)
        assertEquals("", ended.liveText)
        assertEquals("", ended.liveReasoning)
        val retained = ended.raw.getJSONArray("_unconfirmed_replies").getJSONObject(0)
        assertEquals("partial answer", retained.optString("text"))
        assertEquals("working notes", retained.optString("reasoning"))
        assertEquals(false, retained.opt("actionable"))
        assertTrue(ended.liveTools.isEmpty())
        assertEquals(status, ended.status)
        val newer = running.copy(streamId = "new-runtime")
        assertSame(newer, releaseTerminalChat(newer, "runtime", replyConfirmed = false, terminalStatus = status))
    }

    @Test fun confirmedTerminalReplyClearsTransientOutput() {
        val running = ChatState(streamId = "runtime", liveText = "complete", liveReasoning = "notes", liveTools = listOf(JSONObject()))

        val ended = releaseTerminalChat(running, "runtime", replyConfirmed = true, terminalStatus = "")

        assertEquals("", ended.streamId)
        assertEquals("", ended.liveText)
        assertEquals("", ended.liveReasoning)
        assertTrue(ended.liveTools.isEmpty())
    }

    @Test fun aChatSwitchDuringTheAttachmentSaveNeverWritesIntoTheNewConversation() {
        val display = JSONArray().put(json("name" to "image.png", "mime" to "image/png"))
        val shown = (0 until display.length()).mapNotNull { display.optJSONObject(it) }
        val source = ChatState(sessionId = "chat-a", messages = listOf(Message("101", "user", "Synthetic source message")),
            raw = json("messages" to JSONArray().put(json("row_id" to 101, "role" to "user", "attachments" to JSONArray()))))
        val switched = ChatState(sessionId = "chat-b", messages = listOf(Message("101", "user", "Another chat's coincident row")),
            raw = json("messages" to JSONArray().put(json("row_id" to 101, "role" to "user", "attachments" to JSONArray()))))
        var current = true
        // The first gate passed; the owner switched chats while the attachment cache wrote on IO.
        current = false
        val afterSwitch = boundAttachmentChat(switched, { current }, "101", display, shown)
        assertSame("a switch during the save leaves the newly opened chat untouched", switched, afterSwitch)
        assertTrue(afterSwitch.messages.single().attachments.isEmpty())
        assertTrue(afterSwitch.raw.rows("messages").single().rows("attachments").isEmpty())
        // The same chat still receives its display attachments.
        current = true
        val same = boundAttachmentChat(source, { current }, "101", display, shown)
        assertEquals("image.png", same.messages.single().attachments.single().string("name"))
        assertEquals("image.png", same.raw.rows("messages").single().rows("attachments").single().string("name"))
    }

    @Test fun reselectingTheOpenChatKeepsItsVisibleTranscriptOnScreen() {
        val transcript = listOf(Message("row-1", "assistant", "Kept on screen"))
        val open = ChatState(sessionId = "chat", messages = transcript, raw = JSONObject().put("messages", JSONArray()),
            liveText = "still working", projectionReady = true)

        val selected = chatOnSelection(open, "chat", "New title", "high")

        assertEquals("chat", selected.sessionId)
        assertTrue(selected.projectionReady)
        assertEquals(transcript, selected.messages)
        assertEquals("still working", selected.liveText)
        // A chat still loading keeps its skeleton until its own read completes.
        assertFalse(chatOnSelection(open.copy(projectionReady = false), "chat", "", "").projectionReady)
        // A different chat is not shown with the previous one's transcript.
        val other = chatOnSelection(open, "other", "", "high")
        assertEquals("other", other.sessionId)
        assertEquals("New conversation", other.title)
        assertEquals("high", other.sessionReasoningEffort)
        assertFalse(other.projectionReady)
        assertTrue(other.messages.isEmpty())
    }

    @Test fun oldCompletionCannotReleaseANewerSubmissionSharingTheCanonicalRuntime() {
        val newer = ChatState(sessionId = "chat", streamId = "chat", liveText = "New turn still working", status = "Responding",
            raw = JSONObject().put("_display_submission_id", "new-submission"))
        assertSame(newer, releaseTerminalChat(newer, "chat", replyConfirmed = true, terminalStatus = "", submission = "old-submission"))
        assertSame(newer, releaseTerminalChat(newer, "chat", replyConfirmed = false, terminalStatus = "Old turn ended", submission = "old-submission"))
        val same = releaseTerminalChat(newer, "chat", replyConfirmed = false, terminalStatus = "", submission = "new-submission")
        assertEquals("", same.streamId)
        assertEquals("New turn still working", same.raw.getJSONArray("_unconfirmed_replies").getJSONObject(0).getString("text"))
    }
}
