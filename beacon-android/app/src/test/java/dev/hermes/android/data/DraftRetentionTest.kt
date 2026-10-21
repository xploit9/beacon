package dev.hermes.android.data
import dev.hermes.android.core.Draft
import org.junit.Assert.*
import org.junit.Test
class DraftRetentionTest {
    @Test fun onlySavedInactiveValuesCanLeaveMemory() {
        val savedValue = Draft("saved protected draft")
        val newer = Draft("newer unsaved edit")
        val drafts = mutableMapOf("active" to savedValue, "saved" to savedValue, "failed" to newer, "changed" to newer)
        val persisted = mutableMapOf("active" to savedValue.encode(), "saved" to savedValue.encode(), "changed" to savedValue.encode())
        pruneSavedDrafts(drafts, persisted, "active")
        assertEquals(setOf("active", "failed", "changed"), drafts.keys)
        assertEquals(newer, drafts["failed"])
        assertEquals(newer, drafts["changed"])
        assertEquals(savedValue, drafts["active"])
    }
    @Test fun mutableAttachmentEditsAfterPersistenceRemainRetained() {
        val attachment = dev.hermes.android.core.json("name" to "before")
        val value = Draft(attachments = listOf(attachment))
        val drafts = mutableMapOf("inactive" to value)
        val saved = mutableMapOf("inactive" to value.encode())
        attachment.put("name", "new unsaved value")
        pruneSavedDrafts(drafts, saved, null)
        assertSame(value, drafts["inactive"])
    }

}
