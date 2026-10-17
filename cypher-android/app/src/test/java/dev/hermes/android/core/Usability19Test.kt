package dev.hermes.android.core

import dev.hermes.android.data.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class Usability19Test {
    @Test fun streamedEchoAfterHistoryPromotionCannotResurrectAnEmptyLiveBody() {
        val body = interimAssistantBody("", "", json("text" to "A synthetic prelude", "already_streamed" to true))
        assertEquals("", body.text)
    }
    @Test fun pendingTokenProseSurvivesItsStreamedCompletionUntilOwnedSnapshot() {
        val text = "A synthetic prelude"
        val body = interimAssistantBody(text, "", json("text" to text, "already_streamed" to true))
        assertEquals(text, body.text)
        val boundary = LiveAssistantBoundary().after("interim_assistant").after("tool", json("tid" to "tool-a"))
        val history = listOf(json("role" to "user"), json("role" to "assistant", "content" to text, "tool_calls" to org.json.JSONArray(listOf(json("id" to "tool-a")))))
        assertTrue(historyOwnsLiveBoundary(history, emptyList(), boundary.closed, boundary.toolIds))
    }
    @Test fun filteringAlreadyCommittedToolCardsDoesNotLoseBodyOwnershipWitness() {
        val boundary = LiveAssistantBoundary().after("tool", json("tid" to "tool-a"))
        val history = listOf(json("role" to "user"), json("role" to "assistant", "tool_calls" to org.json.JSONArray(listOf(json("id" to "tool-a")))))
        assertTrue(historyOwnsLiveBoundary(history, emptyList(), boundary.closed, boundary.toolIds))
    }
    @Test fun cachedOwnershipSurvivesReattachThenFreshTokensStartIndependentSegment() {
        val sealed = LiveAssistantBoundary().after("tool", json("tid" to "tool-a"))
        val restored = LiveAssistantBoundary.from(sealed.saveInto(json("text" to "Synthetic progress")))
        assertTrue(restored.closed)
        assertEquals(setOf("tool-a"), restored.toolIds)
        val fresh = restored.after("token", json("text" to "Same words intentionally"))
        assertFalse(fresh.closed)
        assertTrue(fresh.toolIds.isEmpty())
        assertEquals("Same words intentionally", interimAssistantBody("", "", json("text" to "Same words intentionally")).text)
    }
    @Test fun lateClosedSignalStillOwnsToolsWhoseCardsWereFilteredBySnapshot() {
        val beforeClose = LiveAssistantBoundary.from(json("boundary_closed" to false, "tools" to org.json.JSONArray(listOf(json("tid" to "tool-a")))))
        val closed = beforeClose.after("tool", json("tid" to "tool-a"))
        assertFalse(beforeClose.closed)
        val history = listOf(json("role" to "user"), json("role" to "assistant", "tool_calls" to org.json.JSONArray(listOf(json("id" to "tool-a")))))
        assertTrue(historyOwnsLiveBoundary(history, emptyList(), closed.closed, closed.toolIds))
    }
    @Test fun reasoningEchoRemovesOnlyItsFlaggedCurrentSuffix() {
        val echo = "Synthetic visible progress"
        val reason = "Independent current activity\n" + echo
        assertEquals("Independent current activity", interimAssistantBody("", reason, json("text" to echo, "already_streamed" to true, "reasoning_echo" to true)).reasoning)
        assertEquals(reason, interimAssistantBody("", reason, json("text" to echo, "already_streamed" to true)).reasoning)
        val unrelated = "Independent current activity only"
        assertEquals(unrelated, interimAssistantBody("", unrelated, json("text" to echo, "reasoning_echo" to true)).reasoning)
    }
    @Test fun equalRepliesAcrossUserTurnsAndNewStreamRemainIndependent() {
        val text = "The same legitimate reply"
        val previous = listOf(json("role" to "user"), json("role" to "assistant", "content" to text, "tool_calls" to org.json.JSONArray(listOf(json("id" to "tool-a")))))
        val next = previous + json("role" to "user", "content" to "A second request")
        assertFalse(historyOwnsLiveBoundary(currentTurnMessages(next), emptyList(), true, setOf("tool-a")))
        assertEquals(text, interimAssistantBody("", "", json("text" to text)).text)
        assertTrue(LiveAssistantBoundary().toolIds.isEmpty())
    }
    @Test fun legacyCacheWithoutOwnershipAndMissingToolIdsNeverGuessByProse() {
        val legacy = LiveAssistantBoundary.from(json("boundary_closed" to true, "text" to "Repeated text"))
        assertTrue(legacy.toolIds.isEmpty())
        val noId = legacy.after("tool", json("name" to "search"))
        assertTrue(noId.toolIds.isEmpty())
        assertFalse(historyOwnsLiveBoundary(listOf(json("role" to "assistant", "content" to "Repeated text")), emptyList(), noId.closed, noId.toolIds))
    }
    @Test fun newInterimProseCannotReuseAnEarlierSegmentsOwnership() {
        val previous = LiveAssistantBoundary().after("tool", json("tid" to "old-tool"))
        val fresh = previous.after("interim_assistant", json("text" to "A real new prelude"))
        assertTrue(fresh.closed)
        assertTrue(fresh.toolIds.isEmpty())
        val history = listOf(json("role" to "user"), json("role" to "assistant", "tool_calls" to org.json.JSONArray(listOf(json("id" to "old-tool")))))
        assertFalse(historyOwnsLiveBoundary(history, listOf(json("tid" to "old-tool")), fresh.closed, fresh.toolIds))
        val openLegacy = LiveAssistantBoundary.from(json("boundary_closed" to false, "tools" to org.json.JSONArray(listOf(json("tid" to "old-tool")))))
        assertTrue(openLegacy.toolIds.isEmpty())
    }
    @Test fun cachedOwnershipIsBoundedAndExplicitEmptyOwnershipWinsOverOldCards() {
        var boundary = LiveAssistantBoundary()
        repeat(300) { boundary = boundary.after("tool", json("tid" to "tool-$it")) }
        assertEquals(256, boundary.toolIds.size)
        val restored = LiveAssistantBoundary.from(boundary.saveInto(JSONObject()))
        assertEquals(boundary.toolIds, restored.toolIds)
        val empty = LiveAssistantBoundary.from(json("boundary_closed" to true, "boundary_tool_ids" to org.json.JSONArray(), "tools" to org.json.JSONArray(listOf(json("tid" to "old-tool")))))
        assertTrue(empty.toolIds.isEmpty())
        assertTrue(LiveAssistantBoundary().after("tool", json("tid" to "x".repeat(513))).toolIds.isEmpty())
    }
    @Test fun timelineToolsUseCallIdentityRatherThanName() {
        val first = json("tid" to "a", "name" to "search", "args" to json("q" to "one"))
        val second = json("tid" to "b", "name" to "search")
        val tools = mergeLiveTool(mergeLiveTool(emptyList(), first), second)
        assertEquals(2, tools.size)
        val completed = mergeLiveTool(tools, json("tid" to "a", "done" to true))
        assertEquals(2, completed.size)
        assertEquals("one", completed.last().optJSONObject("args")?.string("q"))
        assertTrue(completed.last().optBoolean("done"))
    }
    @Test fun timelineMissingIdentityKeepsGenuineRepeatedCalls() {
        val tool = json("name" to "search")
        assertEquals(2, mergeLiveTool(listOf(tool), tool).size)
    }
    @Test fun timelineHistoryOwnsOnlyTheClosedMatchingTurnBoundary() {
        val history = listOf(json("role" to "user"), json("role" to "assistant", "tool_calls" to org.json.JSONArray(listOf(json("id" to "a")))))
        assertTrue(historyOwnsLiveBoundary(history, listOf(json("tid" to "a")), true))
        assertFalse(historyOwnsLiveBoundary(history, listOf(json("tid" to "a")), false))
        assertFalse(historyOwnsLiveBoundary(history, listOf(json("tid" to "b")), true))
        val nextTurn = history + json("role" to "user", "content" to "same question")
        assertFalse(historyOwnsLiveBoundary(currentTurnMessages(nextTurn), listOf(json("tid" to "a")), true))
    }
    @Test fun timelineTextAndRawToolResultRemainUnmodified() {
        val literal = "Use \\n in code;\nreal line"
        assertEquals(literal, chatPresentation("assistant", literal).text)
        assertEquals(literal, chatPresentation("user", literal).text)
        val raw = "{\"output\":\"line\\nnext\"}"
        val tool = chatPresentation("tool", raw, json("name" to "search"))
        assertTrue(tool.activity)
        assertEquals("search", tool.summary)
        assertEquals(raw, tool.text)
        assertFalse(chatPresentation("assistant", "search").activity)
        val objectResult = json("stdout" to "line\nnext")
        assertEquals(objectResult.toString(), messageContentText("tool", objectResult))
        assertEquals("", messageContentText("assistant", objectResult))
        assertEquals(literal, messageContentText("user", literal))
    }
    @Test fun visibilityConfirmedCanonicalRowAppearsBeforeIndex() {
        val row = json("session_id" to "server-canonical", "message_count" to 1)
        assertEquals("server-canonical", sessionRowId(visibleSessionRows(emptyList(), listOf(row), "", false).single()))
        assertEquals(1, visibleSessionRows(listOf(row), listOf(row), "", false).size)
    }
    @Test fun visibilitySearchAndArchiveFiltersStayAuthoritative() {
        val row = json("session_id" to "confirmed")
        assertTrue(visibleSessionRows(emptyList(), listOf(row), "unmatched", false).isEmpty())
        assertTrue(visibleSessionRows(emptyList(), listOf(row), "", true).isEmpty())
    }
    @Test fun visibilityQueryAndRevisionFencesRejectOldRequests() {
        assertTrue(sessionIndexCurrent(3, 3, 7, 7))
        assertFalse(sessionIndexCurrent(2, 3, 7, 7))
        assertFalse(sessionIndexCurrent(3, 3, 6, 7))
    }
    @Test fun preloadTtlClockAndMutationInvalidation() {
        assertTrue(startupReadFresh(1000, 1001))
        assertFalse(startupReadFresh(0, 1001))
        assertFalse(startupReadFresh(1000, 999))
        assertFalse(startupReadFresh(1000, 121000))
        assertFalse(startupReadFresh(1000, 1100, 1000))
        assertTrue(startupReadFresh(1001, 1100, 1000))
    }
    @Test fun preloadAllowlistIsBoundedAndExcludesRemoteEffects() {
        assertEquals(startupReads.size, startupReads.map { it.key }.distinct().size)
        assertTrue(startupReads.isNotEmpty() && startupReads.size <= 10)
        assertEquals(3, STARTUP_READ_PARALLELISM)
        assertEquals(8000L, STARTUP_READ_BUDGET)
        assertFalse(startupReads.any { it.path.contains("provider") || it.path.contains("mcp") || it.path.contains("mail") || it.path.contains("calls") || it.path.contains("updates") || it.path.contains("models/live") })
        assertEquals(listOf("a", "b", "c"), startupRecentSessions(listOf("", "x", "a", "a", "b", "c"), "x"))
    }
}
