package dev.hermes.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    @Test fun cleanCheckpointNeverReplacesNewerServerContent() {
        assertNull(editorDraftSnapshot("old", "old", 1, 1, false))
        val oldClean = JSONObject().put("version", 1).put("text", "old").put("base", "old")
        val restored = restoreEditorDraft(oldClean, "new on computer")
        assertEquals("new on computer", restored.text)
        assertEquals(restored.text, restored.base)
        assertFalse(restored.reviewRequired)
    }
    @Test fun dirtyCheckpointRetainsOriginalConflictBaseAcrossReopen() {
        val checkpoint = editorDraftSnapshot("my changes", "original", 2, 4, false)!!
        val unchanged = restoreEditorDraft(checkpoint, "original")
        assertFalse(unchanged.reviewRequired)
        val changed = restoreEditorDraft(checkpoint, "new on computer")
        assertEquals("my changes", changed.text)
        assertEquals("original", changed.base)
        assertTrue(changed.reviewRequired)
        assertEquals(2, changed.start); assertEquals(4, changed.end)
        val again = restoreEditorDraft(editorDraftSnapshot(changed.text, changed.base, 2, 4, true), "new on computer")
        assertEquals(changed, again)
    }
    @Test fun legacyUnknownBaseRequiresExplicitReviewButMatchingContentIsClean() {
        val legacy = JSONObject().put("text", "retained draft").put("start", -50).put("end", 99999)
        val restored = restoreEditorDraft(legacy, "current server text")
        assertTrue(restored.reviewRequired)
        assertEquals("retained draft", restored.text)
        assertEquals(0, restored.start); assertEquals(restored.text.length, restored.end)
        assertFalse(restoreEditorDraft(legacy, "retained draft").reviewRequired)
        assertThrows(IllegalArgumentException::class.java) { restoreEditorDraft(JSONObject().put("text", JSONObject()), "server") }
    }
}
