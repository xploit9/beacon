package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class ConversationPositionTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun newHistoryMeasuresAtTailInsteadOfFlashingTheFirstMessage() {
        assertEquals(Int.MAX_VALUE to 0, initialConversationPosition(listOf("a", "b"), "", 0, 0))
    }
    @Test fun savedDurableAnchorSurvivesInsertedMessages() {
        assertEquals(2 to 42, initialConversationPosition(listOf("new", "a", "b"), "b", 1, 42))
    }
    @Test fun removedAnchorFallsBackWithinTheCurrentHistory() {
        assertEquals(1 to 0, initialConversationPosition(listOf("a", "b"), "removed", 99, -10))
        assertEquals(0 to 0, initialConversationPosition(emptyList(), "a", 2, 40))
    }
    @Test fun anchorIdBeatsStaleIndexAndEmptyHistoryHasNoAnchor() {
        assertEquals(0, anchoredIndex(listOf("a", "b", "c"), "a", 2))
        assertEquals(2, anchoredIndex(listOf("a", "b", "c"), "gone", 9))
        assertEquals(0, anchoredIndex(listOf("a", "b", "c"), "gone", -4))
        assertNull(anchoredIndex(emptyList(), "a", 0))
    }
    @Test fun appendedMessagesDoNotMoveAReaderOfOlderMessages() {
        val before = listOf("m1", "m2", "m3")
        val after = before + listOf("m4", "m5")
        assertEquals(1 to 30, initialConversationPosition(before, "m2", 1, 30))
        // The anchor id wins over a stale saved index, offset preserved, however many messages were appended.
        assertEquals(1 to 30, initialConversationPosition(after, "m2", 99, 30))
        assertFalse(followsTail(userAtTail = true, autoFollowSetting = true, hasSavedAnchor = true))
        assertFalse(followsTail(userAtTail = true, autoFollowSetting = false, hasSavedAnchor = false))
        assertTrue(followsTail(userAtTail = true, autoFollowSetting = true, hasSavedAnchor = false))
    }
    @Test fun expandingAToolYieldsFollowAndCollapsingDoesNotRearmIt() {
        assertFalse(followAfterToolToggle(following = true, expanded = true))
        assertFalse(followAfterToolToggle(following = false, expanded = false))
        assertTrue(followAfterToolToggle(following = true, expanded = false))
    }

    private fun outcome(name: String = "terminal", status: String = "", isError: Boolean = false, result: String? = null, explicit: Boolean = false, streaming: Boolean = false) =
        toolOutcome(name, status, isError, result, explicit, streaming)

    @Test fun aStoredToolWithNoCompletionIsUnknownNotRunningOrSuccessful() {
        assertEquals(ToolOutcome.NoResult, outcome())
        assertEquals(ToolOutcome.NoResult, outcome(result = "  "))
    }
    @Test fun onlyALiveOpenToolIsRunning() {
        assertEquals(ToolOutcome.Running, outcome(streaming = true))
        // An old completed call stays completed even while a turn streams.
        assertEquals(ToolOutcome.Completed, outcome(result = "total 0", explicit = true, streaming = true))
        assertEquals(ToolOutcome.Completed, outcome(explicit = true, streaming = true))
    }
    @Test fun anExplicitButEmptyCompletionIsCompleted() {
        assertEquals(ToolOutcome.Completed, outcome(result = "", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome(status = "completed"))
    }
    @Test fun errorsComeFromIsErrorAndFailedStatesOnly() {
        assertEquals(ToolOutcome.Failed, outcome(status = "done", isError = true, result = "exit 1", explicit = true))
        assertEquals(ToolOutcome.Failed, outcome(status = "denied", result = "x"))
        // is_error=false with a completion marker is a success, not a failure.
        assertEquals(ToolOutcome.Completed, outcome(isError = false, result = "oops", explicit = true))
    }
    @Test fun runningAndPreparingStatusesShowOnlyWhileTheTurnStreams() {
        assertEquals(ToolOutcome.Running, outcome(status = "running", streaming = true))
        assertEquals(ToolOutcome.Preparing, outcome(status = "generating", streaming = true))
        assertEquals(ToolOutcome.Preparing, outcome(status = "preparing", streaming = true))
        // A persisted running/generating status with nothing terminal is unknown, not still running.
        assertEquals(ToolOutcome.NoResult, outcome(status = "running"))
        assertEquals(ToolOutcome.NoResult, outcome(status = "generating"))
    }
    @Test fun terminalEvidenceWinsOverTheStaleStartStatusItWasMergedInto() {
        // tool.start set status=running; the same-id tool.complete later merged result/is_error without clearing it.
        assertEquals(ToolOutcome.Completed, outcome(status = "running", result = "total 0", explicit = true, streaming = true))
        assertEquals(ToolOutcome.Completed, outcome(status = "running", result = "", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome(status = "generating", result = "ok", explicit = true))
        assertEquals(ToolOutcome.Failed, outcome(status = "running", isError = true, result = "boom", explicit = true, streaming = true))
        assertEquals(ToolOutcome.Dispatched, outcome("delegate_task", status = "running", result = """{"status":"dispatched"}""", explicit = true))
    }
    @Test fun aPartialPreviewIsNeverTerminalEvidence() {
        // The caller passes only result/output as [result]; a live call that has just a preview stays Running.
        assertEquals(ToolOutcome.Running, outcome(status = "running", result = null, explicit = false, streaming = true))
        assertEquals(ToolOutcome.NoResult, outcome(result = null, explicit = false, streaming = false))
    }
    @Test fun aDispatchedDelegationIsNotFinished() {
        val dispatched = """{"status":"dispatched","child":"abc"}"""
        assertEquals(ToolOutcome.Dispatched, outcome("delegate_task", "completed", result = dispatched))
        assertEquals(ToolOutcome.Dispatched, outcome("delegate_task", result = """{"mode":"background"}""", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome("delegate_task", result = """{"status":"done"}""", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome("terminal", result = dispatched, explicit = true))
        assertEquals(ToolOutcome.Failed, outcome("delegate_task", isError = true, result = dispatched, explicit = true))
    }

    @Test fun dispatchIsReadFromParsedJsonNotFromSubstrings() {
        assertEquals(ToolOutcome.Dispatched, outcome("delegate_task", result = """{ "status" : "dispatched" }""", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome("delegate_task", result = "not json at all", explicit = true))
        assertEquals(ToolOutcome.Completed, outcome("delegate_task", result = """["dispatched"]""", explicit = true))
        // The word only appears inside another field, so the call is not a dispatch.
        assertEquals(ToolOutcome.Completed, outcome("delegate_task", result = """{"note":"status\":\"dispatched"}""", explicit = true))
        assertFalse(isDetachedDispatch("delegate_task", null))
        assertFalse(isDetachedDispatch("delegate_task", "  "))
    }
    @Test fun dispatchStatusAndModeAreNormalized() {
        assertTrue(isDetachedDispatch("delegate_task", """{"status":" Dispatched "}"""))
        assertTrue(isDetachedDispatch("delegate_task", """{"mode":"BACKGROUND"}"""))
        assertFalse(isDetachedDispatch("delegate_task", """{"status":"dispatching","mode":"foreground"}"""))
        assertFalse(isDetachedDispatch("Delegate_Task", """{"status":"dispatched"}"""))
    }

    @Test fun timeLabelUsesRelayFormatForSecondsAndMillis() {
        assertEquals("10:30 AM", messageTimeLabel(1_776_421_800L, utc, Locale.US))
        assertEquals("10:30 AM", messageTimeLabel(1_776_421_800_000L, utc, Locale.US))
    }
    @Test fun missingTimesShowNothingRatherThanTheCurrentTime() {
        assertNull(messageTimeLabel(0, utc, Locale.US))
        // Same instant in another zone is a different wall-clock time, in the afternoon.
        assertEquals("3:30 PM", messageTimeLabel(1_776_421_800L, TimeZone.getTimeZone("Asia/Karachi"), Locale.US))
        assertNull(messageTimeLabel(-5, utc, Locale.US))
        assertEquals(0L, epochMillis(0))
    }
    @Test fun durationIsTheNativeDurationSecondsOrNothing() {
        assertEquals("3.1s", toolDurationLabel(3.14))
        assertEquals("0.0s", toolDurationLabel(0.0))
        assertNull(toolDurationLabel(null))
        assertNull(toolDurationLabel(-1.0))
        assertNull(toolDurationLabel(Double.NaN))
        assertNull(toolDurationLabel(Double.POSITIVE_INFINITY))
    }
    @Test fun collapsedSummaryHonorsTheExactLimit() {
        assertEquals("x".repeat(700), compactToolDetail("x".repeat(700)))
        assertEquals("x".repeat(700) + "...", compactToolDetail("x".repeat(701)))
        assertEquals("abc...", compactToolDetail("abcdef", 3))
        assertEquals("ab...", compactToolDetail("ab  cdef", 3)) // trailing space trimmed before the ellipsis
        assertEquals("...", compactToolDetail("abc", 0))
        assertEquals("", compactToolDetail("   \n "))
    }
    @Test fun truncationNeverSplitsASurrogatePair() {
        val smile = "😀"
        assertEquals("a...", compactToolDetail("a${smile}b", 2))
        assertEquals("a$smile...", compactToolDetail("a${smile}b", 3))
        val cut = boundedDetail("a${smile}b", limit = 2)
        assertEquals("a\n… 3 more characters not shown", cut)
        assertEquals("a$smile\n… 1 more characters not shown", boundedDetail("a${smile}b", limit = 3))
        assertFalse(cut.any { it.isSurrogate() })
    }
    @Test fun compactSummaryCollapsesWhitespaceButFullDetailKeepsNewlines() {
        assertEquals("a b", compactToolDetail("a \n  b"))
        assertTrue(compactToolDetail("x".repeat(900)).endsWith("..."))
        val full = (1..80).joinToString("\n") { "line $it " + "x".repeat(20) }
        assertTrue(full.length > 700)
        assertEquals(full, boundedDetail(full))
        assertTrue(boundedDetail(full).contains('\n'))
    }
    @Test fun onlyAPathologicallyLargeDetailIsCutAndSaysSo() {
        val huge = "y".repeat(ToolDetailLimit + 25)
        val shown = boundedDetail(huge)
        assertTrue(shown.endsWith("25 more characters not shown"))
        assertEquals(ToolDetailLimit, shown.takeWhile { it == 'y' }.length)
    }

    private fun snap(name: String, outcome: ToolOutcome) = ToolSnapshot(name, name, outcome)

    @Test fun activityCountsOnlyRealRunningQueuedAndWaitingWork() {
        val tools = listOf(snap("a", ToolOutcome.Running), snap("b", ToolOutcome.Preparing), snap("c", ToolOutcome.Completed), snap("d", ToolOutcome.Dispatched))
        val activity = chatActivity(tools, queued = 1, approval = true, question = false)
        assertEquals(2, activity.running.size)
        assertEquals(listOf("c", "d"), activity.recent.map { it.name })
        assertEquals(listOf("Approval needed"), activity.waiting)
        assertEquals("2 running · 1 queued · Waiting for you", chatActivityStatus(activity))
        assertTrue(activity.active)
    }
    @Test fun aWaitingQuestionAloneIsActivityAndANegativeQueueIsClamped() {
        val activity = chatActivity(emptyList(), queued = -3, approval = false, question = true)
        assertEquals(0, activity.queued)
        assertEquals(listOf("Question waiting"), activity.waiting)
        assertTrue(activity.active)
        assertEquals("Waiting for you", chatActivityStatus(activity))
    }
    @Test fun finishedToolsAloneDoNotOwnComposerSpace() {
        val activity = chatActivity(listOf(snap("c", ToolOutcome.Completed)), queued = 0, approval = false, question = false)
        assertFalse(activity.active)
        assertEquals("", chatActivityStatus(activity))
        assertFalse(chatActivity(emptyList(), 0, false, false).active)
    }
    @Test fun unsupportedProcessControlIsStatedNotHiddenAsZeroProcesses() {
        assertTrue(ProcessControlUnavailable.contains("aren’t available"))
        assertFalse(ProcessControlUnavailable.contains("0 "))
    }
}
