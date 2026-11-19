package dev.hermes.android

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * An approval or question card takes no tap that began before, or lands just after, a move of its buttons (CARDS-121, backlog 17 and 19): a card that moves under the finger
 * (follow-scroll, the live row, a notice making room, a new row, the keyboard) must never put Allow where the finger was heading. The window is the phone's real clock, passed in.
 */
class PromptSettleTest {
    private val W = 400L
    private fun settle() = PromptSettle { W }
    private fun PromptSettle.at(y: Float, now: Long) = placed(16f, y, 300, 120, now)
    @After fun restore() { PromptSettleWindow.ms = 400L }

    @Test fun theProductionWindowIsFourHundredMilliseconds() = assertEquals(400L, PromptSettleWindow.ms)

    @Test fun aCardNothingHasMovedTakesTaps() {
        val card = settle(); card.at(500f, 0)
        assertTrue(card.admits(10)); assertTrue(card.admits(10_000))
    }

    @Test fun theFirstPlaceIsNotAMove() {
        val card = settle(); card.at(500f, 5_000)
        assertTrue("a card that has just been laid out is answered as it always was", card.admits(5_001))
    }

    @Test fun aMoveStopsTapsUntilTheWindowHasPassedThenTheyGoThrough() {
        val card = settle(); card.at(500f, 0); card.at(460f, 1_000)
        assertFalse(card.admits(1_000)); assertFalse(card.admits(1_399))
        assertTrue(card.admits(1_400))
    }

    @Test fun everyFurtherMoveRestartsTheWindow() {
        val card = settle(); card.at(500f, 0)
        for (step in 1..10) card.at(500f - step * 12f, 1_000L + step * 50L)
        assertFalse("still moving 49 ms ago", card.admits(1_500 + 349)); assertFalse(card.admits(1_899))
        assertTrue(card.admits(1_900))
    }

    @Test fun aChangeOfSizeMovesTheButtonsToo() {
        val card = settle(); card.placed(16f, 500f, 300, 120, 0); card.placed(16f, 500f, 300, 168, 100)
        assertFalse(card.admits(200)); assertTrue(card.admits(500))
    }

    @Test fun aSubPixelJitterIsNotAMove() {
        val card = settle(); card.at(500f, 0); card.at(500.2f, 100); card.at(499.9f, 200)
        assertTrue(card.admits(210))
    }

    @Test fun aPressThatWentDownBeforeTheMoveNeverLandsEvenWhenReleasedAfterTheWindow() {
        val card = settle(); card.at(500f, 0)
        card.pressed(1_000)
        card.at(494f, 1_100)           // the card moved under the finger, which is still on the button
        card.released(2_000)           // held for a second, released well after the window
        assertFalse("the press began on a card that has since moved", card.admits(2_000))
    }

    @Test fun aPressThatWentDownWhileTheFingerWasStillStayingAfterAMoveNeverLandsEvenIfTheClickComesLater() {
        val card = settle(); card.at(500f, 0); card.at(494f, 1_000)
        card.pressed(1_300)            // 300 ms after the move: the owner reacted to the old place
        card.released(1_450)           // released after the window, so the time since the move alone would let it through
        assertFalse(card.admits(1_450))
    }

    @Test fun aPressAfterTheWindowIsATap() {
        val card = settle(); card.at(500f, 0); card.at(494f, 1_000)
        card.pressed(1_450); card.released(1_520)
        assertTrue(card.admits(1_520))
    }

    @Test fun theRepeatTapAfterAnIgnoredOneGoesThrough() {
        val card = settle(); card.at(500f, 0); card.at(460f, 1_000)
        card.pressed(1_100); card.released(1_160)
        assertFalse(card.admits(1_160))
        card.pressed(1_700); card.released(1_760)
        assertTrue(card.admits(1_760))
    }

    @Test fun aSemanticClickWithNoFingerIsNotBlockedByAnOldPress() {
        val card = settle(); card.at(500f, 0)
        card.pressed(100); card.released(180); assertTrue(card.admits(181))
        card.at(460f, 5_000)           // the card moves long after that press; TalkBack then clicks (no pointer events at all)
        assertFalse("a click right after the move waits", card.admits(5_100))
        assertTrue("a click after the window is answered, the old press does not count", card.admits(5_500))
    }

    @Test fun aFingerThatIsStillDownCountsAsAPressInProgress() {
        val card = settle(); card.at(500f, 0)
        card.pressed(1_000); card.at(494f, 1_050)
        assertFalse(card.admits(3_000))
    }

    @Test fun theWindowCanBeWidenedForASlowDevice() {
        PromptSettleWindow.ms = 3_000L
        val card = PromptSettle(); card.at(500f, 0); card.at(460f, 1_000)
        assertFalse(card.admits(3_999)); assertTrue(card.admits(4_000))
    }

    @Test fun aWindowOfZeroNeverBlocks() {
        val card = PromptSettle { 0L }; card.at(500f, 0); card.at(460f, 10)
        assertTrue(card.admits(10))
    }

    // Item 18: what the owner is told when an answer did not go, or went to a request that was already settled.
    @Test fun anAnswerThatDidNotGoSaysSoAndSaysWhereToAnswerAgain() {
        assertEquals("Your answer didn't go. Try again.", lostAnswerNotice(sameChat = true))
        assertEquals("Your answer didn't go. Open that chat to answer again.", lostAnswerNotice(sameChat = false))
    }

    @Test fun onlyAnApprovalOrQuestionAnswerIsOneOfThese() {
        assertTrue("/api/approval/respond" in PROMPT_ANSWER_PATHS && "/api/clarify/respond" in PROMPT_ANSWER_PATHS)
        assertFalse("/api/session/update" in PROMPT_ANSWER_PATHS)
    }

    @Test fun aRequestThatWasAlreadySettledSaysSoWhetherHermesEndedItOrAnotherPhoneAnsweredFirst() {
        assertEquals(PROMPT_ENDED_NOTICE, promptAnswerNotice("ended"))
        assertEquals("This request was already answered.", promptAnswerNotice("already_resolved"))
        assertEquals("", promptAnswerNotice("resolved"))
        assertEquals("", promptAnswerNotice(""))
    }
}
