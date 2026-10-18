package dev.hermes.android

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeHistoryNoticeTest {
    @Test fun onlyAFullHistoryWindowShowsTheTruncationNotice() {
        assertEquals(
            "Only the latest 500 messages are loaded; older history may be incomplete.",
            historyTruncationMessage(JSONObject().put("history_truncated", true).put("history_limit", 500)),
        )
        assertNull(historyTruncationMessage(JSONObject().put("history_truncated", false)))
    }
}
