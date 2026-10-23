package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import org.junit.Assert.*
import org.junit.Test

class AuditR8aDraftTest {
    private fun draft(id: String = "task-a") = json("id" to id, "key" to "draft-key", "title" to "First", "body" to "Notes", "assignee" to "default",
        "base" to json("title" to "First", "body" to "Notes", "assignee" to "default"))

    @Test fun unchangedEditAndEmptyNewDraftCanBeDiscardedOnCancel() {
        assertFalse(kanbanDraftChanged(draft()))
        assertFalse(kanbanDraftChanged(json("id" to "", "key" to "new-draft", "title" to "", "body" to "", "assignee" to "")))
        for (field in listOf("title", "body", "assignee")) assertTrue(kanbanDraftChanged(draft().copy().put(field, "edited")))
    }

    @Test fun taskDraftsAndNewTaskDraftHaveSeparateIdentities() {
        val first = draft()
        val second = draft("task-b").put("title", "Second")
        val records = kanbanDraftRecords(first.toString())
        records.put(kanbanDraftKey("task-b"), second)
        records.put(kanbanDraftKey(""), json("id" to "", "title" to "New task"))
        val restored = kanbanDraftRecords(records.toString())
        assertEquals("First", restored.getJSONObject(kanbanDraftKey("task-a")).getString("title"))
        assertEquals("Second", restored.getJSONObject(kanbanDraftKey("task-b")).getString("title"))
        assertEquals("New task", restored.getJSONObject(kanbanDraftKey("")).getString("title"))
        restored.remove(kanbanDraftKey("task-a"))
        assertFalse(restored.has(kanbanDraftKey("task-a")))
        assertTrue(restored.has(kanbanDraftKey("task-b")))
        assertNotEquals(kanbanDraftKey(""), kanbanDraftKey("new"))
    }
}
