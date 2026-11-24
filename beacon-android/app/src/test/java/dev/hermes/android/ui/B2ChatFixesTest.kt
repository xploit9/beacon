package dev.hermes.android.ui

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.voice.interleaveCallMessages
import org.junit.Assert.*
import org.junit.Test

/** Release 1.2.3 lane B2: Edit and branch on the first message, the attachment preview identity and the call-merge fast path. */
class B2ChatFixesTest {
    @Test fun editingTheFirstMessageStartsANewChatInsteadOfAnEmptyBranch() {
        val rows = listOf(json("row_id" to 1, "role" to "user"), json("row_id" to 2, "role" to "assistant"), json("row_id" to 3, "role" to "user"))
        assertTrue(editStartsNewChat(0, rows))
        assertFalse(editStartsNewChat(2, rows))
        assertTrue("Only an archived row before it: nothing to branch from", editStartsNewChat(1, listOf(json("row_id" to 1, "role" to "user", "archived" to true), json("row_id" to 2, "role" to "user"))))
        assertFalse("Unknown rows keep the branch request", editStartsNewChat(0, emptyList()))
    }

    @Test fun aReplacedAttachmentNeverShowsThePreviousPicture() {
        val a = PreparedContent("scope", listOf<Any?>("ref-a", "thumb-a"), "picture A")
        assertEquals("picture A", attachedPreview(a, "scope", "ref-a", "thumb-a"))
        assertNull("B in the same slot", attachedPreview(a, "scope", "ref-b", "thumb-b"))
        assertNull("another thumbnail of the same file", attachedPreview(a, "scope", "ref-a", "thumb-b"))
        assertNull("another account or session", attachedPreview(a, "other", "ref-a", "thumb-a"))
    }

    @Test fun withNoCallRowsTheMergeReturnsTheSameList() {
        val messages = listOf(Message("m1", "user", "one", recordedAt = 10), Message("m2", "assistant", "two", recordedAt = 20))
        assertSame(messages, interleaveCallMessages(messages, emptyList()))
        val merged = interleaveCallMessages(messages, listOf(Message("call:1", "user", "hi", recordedAt = 15)))
        assertEquals(listOf("m1", "call:1", "m2"), merged.map { it.id })
    }
}
