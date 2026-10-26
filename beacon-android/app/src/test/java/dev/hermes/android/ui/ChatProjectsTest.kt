package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.linkedFolderPath
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A chat's project in the chat window: the header line, the cached answer it reads, and the folder a link leaves behind. */
class ChatProjectsTest {
    private fun line(project: String, streaming: Boolean = false, inCall: Boolean = false, model: String = "gpt-6-luna", assistantChat: Boolean = false, replyText: Boolean = false) =
        chatHeaderLine(streaming, replyText, inCall, model, assistantChat, project)

    @Test fun theHeaderNamesTheProjectBeforeTheModel() {
        assertEquals(ChatHeaderLine("Research", "gpt-6-luna"), line("Research"))
        assertEquals("A name around the project is not part of it", "Research", line("  Research ").project)
    }

    @Test fun nothingShowsForAChatInNoProject() {
        assertEquals(ChatHeaderLine("", "gpt-6-luna"), line(""))
        assertEquals(ChatHeaderLine("", "gpt-6-luna"), line("   "))
    }

    @Test fun thinkingTypingAndACallTakeTheLineOverFromTheProject() {
        assertEquals(ChatHeaderLine("", "thinking…"), line("Research", streaming = true))
        assertEquals(ChatHeaderLine("", "typing…"), line("Research", streaming = true, replyText = true))
        assertEquals(ChatHeaderLine("", "Voice conversation"), line("Research", inCall = true, assistantChat = true))
    }

    @Test fun aProjectReplacesTheAssistantTaglineButNeverTheModel() {
        assertEquals(ChatHeaderLine("Research", ""), line("Research", model = "", assistantChat = true))
        assertEquals(ChatHeaderLine("", "Text and voice, together"), line("", model = "", assistantChat = true))
        assertEquals(ChatHeaderLine("Research", ""), line("Research", model = ""))
    }

    @Test fun theCachedAnswerNamesTheProjectOnlyForItsOwnChat() {
        val record = json("session_id" to "chat-a", "project" to json("id" to "p_research", "name" to "Research"))
        assertEquals("p_research", chatProjectOf(record, "chat-a")?.getString("id"))
        assertNull("Another chat's answer is not this chat's", chatProjectOf(record, "chat-b"))
        assertNull("Not read yet", chatProjectOf(null, "chat-a"))
        assertNull("In no project", chatProjectOf(json("session_id" to "chat-a", "project" to null), "chat-a"))
        assertNull("A failed read has no project", chatProjectOf(json("_error" to "offline"), "chat-a"))
        assertNull("A project without a name has nothing to show", chatProjectOf(json("session_id" to "chat-a", "project" to json("id" to "p_x", "name" to " ")), "chat-a"))
        assertNull("A project without an id cannot be opened", chatProjectOf(json("session_id" to "chat-a", "project" to json("name" to "Research")), "chat-a"))
    }

    @Test fun theProjectsTabOpensTheRequestedProjectOnlyWhileTheRequestIsFresh() {
        assertEquals("p_research", projectOpenRequest(json("id" to "p_research", "at" to 1_000L), now = 1_000L))
        assertEquals("p_research", projectOpenRequest(json("id" to "p_research", "at" to 1_000L), now = 10_900L))
        assertEquals("A request left behind does not jump a later visit", "", projectOpenRequest(json("id" to "p_research", "at" to 1_000L), now = 12_000L))
        assertEquals("A request from the future is not trusted", "", projectOpenRequest(json("id" to "p_research", "at" to 5_000L), now = 1_000L))
        assertEquals("A taken request is empty", "", projectOpenRequest(json(), now = 1_000L))
        assertEquals("", projectOpenRequest(null, now = 1_000L))
    }

    @Test fun aProjectSaysWhatItOffersAChat() {
        assertEquals("No folder yet", projectFolderSummary(0))
        assertEquals("1 linked folder", projectFolderSummary(1))
        assertEquals("3 linked folders", projectFolderSummary(3))
    }

    @Test fun aFreshlyLinkedFolderIsFoundByTheNameHermesStoredIt() {
        val project = json("folders" to JSONArray().put(json("path" to "C:\\Work\\Research\\", "is_primary" to true)).put(json("path" to "/home/me/notes")))
        assertEquals("Windows spelling and a trailing slash still match", "C:\\Work\\Research\\", linkedFolderPath(project, "c:/work/research"))
        assertEquals("/home/me/notes", linkedFolderPath(project, "/home/me/notes/"))
        assertNull("A folder that was not linked", linkedFolderPath(project, "/home/me/other"))
        assertNull("A project with no folders", linkedFolderPath(json("folders" to JSONArray()), "/home/me/notes"))
        assertNull("A blank path never matches", linkedFolderPath(project, ""))
    }
}
