package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReattachRunTest {
    @Test fun onlyAPromptBeaconNeverSawEndEarnsTheQuestion() {
        assertTrue("Sent here, no end seen, nothing shown", shouldAskRunning(marked = true, shownStream = ""))
        assertFalse("Ordinary browsing never resumes a chat", shouldAskRunning(marked = false, shownStream = ""))
        assertFalse("A shown stream is followed by the warm path, not asked twice", shouldAskRunning(marked = true, shownStream = "runtime-1"))
        assertFalse(shouldAskRunning(marked = false, shownStream = "runtime-1"))
    }

    @Test fun aReplyStillRunningOrWaitingForTheOwnerIsFollowedAgain() {
        assertEquals(ColdRun.Follow, coldRunAction("working"))
        assertEquals("Stop and the approval card must come back", ColdRun.Follow, coldRunAction("waiting"))
    }

    @Test fun anEndedReplyIsSettledFromHistoryWhichDropsTheMarker() {
        assertEquals(ColdRun.Settle, coldRunAction("idle"))
    }

    @Test fun anUnreadableAnswerLeavesTheChatAndTheMarkerAlone() {
        assertEquals(ColdRun.Keep, coldRunAction(null))
        assertEquals(ColdRun.Keep, coldRunAction(""))
        assertEquals(ColdRun.Keep, coldRunAction("streaming"))
    }

    @Test fun theColdKillStoryAsksOnceThenStopsAsking() {
        // Send: marker written. Process killed: the reopened chat shows nothing, so Hermes is asked.
        var marked = true
        assertTrue(shouldAskRunning(marked, shownStream = ""))
        // Still working: the stream is followed, so later looks leave it to the warm path.
        assertEquals(ColdRun.Follow, coldRunAction("working"))
        assertFalse(shouldAskRunning(marked, shownStream = "runtime-1"))
        // The reply ends (heard or settled): the marker is gone and the next open is passive again.
        marked = false
        assertFalse(shouldAskRunning(marked, shownStream = ""))
    }
}
