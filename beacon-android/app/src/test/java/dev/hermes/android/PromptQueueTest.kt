package dev.hermes.android

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Hermes can hold several open prompts for one chat (parallel tool calls each wait on their own approval; gateway/session_pending_controls.py keeps one entry per
 * prompt id). The chat shows one card at a time and keeps the others, oldest first, by prompt id: answering or a settle shows the next, a prompt that ended leaves,
 * and none is answered for another or dropped. Before this, the second request replaced the first card and the first was never shown again.
 */
class PromptQueueTest {
    private val T0 = 0L
    // The reducers take the phone's clock and, for a poll, when it began; most tests do not care, so the helpers default them.
    private fun ChatState.asked(kind: String, prompt: JSONObject?, now: Long = T0) = promptAsked(kind, prompt, now)
    private fun ChatState.ended(id: String, kind: String = "", now: Long = T0) = promptEnded(id, now, kind)
    private fun ChatState.listed(kind: String, first: JSONObject?, waiting: List<JSONObject>, now: Long = T0, since: Long = Long.MAX_VALUE) = promptsListed(kind, first, waiting, now, since)
    private fun timed(id: String, seconds: Int = 300) = approval(id).put("timeout_s", seconds)
    private fun reply(first: JSONObject?, vararg waiting: JSONObject) = json("pending" to first, "waiting" to JSONArray().apply { waiting.forEach { put(it) } })
    private fun order(state: ChatState) = listOfNotNull(state.approval).plus(state.waitingApprovals).map { it.string("id") }

    private fun approval(id: String) = json("id" to id, "approval_id" to id, "command" to "Synthetic $id", "choices" to JSONArray().put("once").put("deny"))
    private fun clarify(id: String) = json("id" to id, "clarify_id" to id, "question" to "Synthetic $id")
    private fun ids(prompts: List<JSONObject>) = prompts.map { it.string("id") }
    private fun shown(state: ChatState) = state.approval?.string("id")

    private val ab = ChatState().asked("approval", approval("A")).asked("approval", approval("B"))

    @Test fun aSecondApprovalWaitsBehindTheFirstInsteadOfReplacingIt() {
        assertEquals("A", shown(ab))
        assertEquals(listOf("B"), ids(ab.waitingApprovals))
    }

    @Test fun answeringTheShownApprovalShowsTheNextOne() {
        val afterA = ab.ended("A", "approval")
        assertEquals("B", shown(afterA))
        assertTrue(afterA.waitingApprovals.isEmpty())
        assertNull(afterA.ended("B", "approval").approval)
    }

    @Test fun aSettleForTheShownApprovalShowsTheNextOne() {
        val afterSettle = ab.ended("A")
        assertEquals("B", shown(afterSettle))
        assertTrue(afterSettle.waitingApprovals.isEmpty())
    }

    @Test fun aSettleForAWaitingApprovalLeavesTheQueueAndKeepsTheShownCard() {
        val afterSettle = ab.ended("B")
        assertEquals("A", shown(afterSettle))
        assertTrue(afterSettle.waitingApprovals.isEmpty())
    }

    @Test fun threeApprovalsComeOutOldestFirstAndNoneIsDropped() {
        val abc = ab.asked("approval", approval("C"))
        assertEquals(listOf("B", "C"), ids(abc.waitingApprovals))
        val shownInOrder = mutableListOf<String>()
        var state = abc
        while (state.approval != null) { shownInOrder += shown(state)!!; state = state.ended(shown(state)!!, "approval") }
        assertEquals(listOf("A", "B", "C"), shownInOrder)
        assertTrue(state.waitingApprovals.isEmpty())
    }

    @Test fun aSettleInTheMiddleOfTheQueueDropsOnlyThatPrompt() {
        val abc = ab.asked("approval", approval("C"))
        val withoutB = abc.ended("B")
        assertEquals("A", shown(withoutB))
        assertEquals(listOf("C"), ids(withoutB.waitingApprovals))
    }

    @Test fun anAnswerForAPromptThatIsNotInTheChatChangesNothing() {
        val unchanged = ab.ended("someone-else", "approval")
        assertEquals("A", shown(unchanged))
        assertEquals(listOf("B"), ids(unchanged.waitingApprovals))
        assertEquals("A", shown(ab.ended("")))
    }

    @Test fun theSamePromptAskedAgainIsNotQueuedTwiceAndKeepsItsPlace() {
        val replayed = ab.asked("approval", approval("A")).asked("approval", approval("B"))
        assertEquals("A", shown(replayed))
        assertEquals(listOf("B"), ids(replayed.waitingApprovals))
        val updated = ab.asked("approval", approval("B").put("command", "Changed"))
        assertEquals("Changed", updated.waitingApprovals.single().string("command"))
        assertEquals("Synthetic A", updated.approval?.string("command"))
    }

    @Test fun clarificationsQueueTheSameWayAndApartFromApprovals() {
        val state = ab.asked("clarify", clarify("Q1")).asked("clarify", clarify("Q2"))
        assertEquals("A", shown(state))
        assertEquals("Q1", state.clarification?.string("id"))
        assertEquals(listOf("B"), ids(state.waitingApprovals))
        assertEquals(listOf("Q2"), ids(state.waitingClarifications))
        val afterQ1 = state.ended("Q1", "clarify")
        assertEquals("Q2", afterQ1.clarification?.string("id"))
        assertEquals("A", shown(afterQ1))
        assertEquals(listOf("B"), ids(afterQ1.waitingApprovals))
    }

    @Test fun anAnswerOfOneKindNeverEndsAPromptOfTheOtherKind() {
        val state = ChatState().asked("approval", approval("same")).asked("clarify", clarify("same"))
        assertEquals("same", state.ended("same", "approval").clarification?.string("id"))
        assertEquals("same", state.ended("same", "clarify").approval?.string("id"))
    }

    @Test fun anExplicitNothingPendingClearsTheShownCardAndTheQueue() {
        val cleared = ab.asked("approval", null)
        assertNull(cleared.approval)
        assertTrue(cleared.waitingApprovals.isEmpty())
    }

    @Test fun aPromptWithoutAnIdCannotBeQueuedSoItReplacesAsBefore() {
        val anonymous = ChatState().asked("approval", json("command" to "no id")).asked("approval", json("command" to "still no id"))
        assertEquals("still no id", anonymous.approval?.string("command"))
        assertTrue(anonymous.waitingApprovals.isEmpty())
    }

    @Test fun hermesOwnListReplacesTheQueueExactly() {
        val listed = ab.listed("approval", approval("B"), listOf(approval("C")))
        assertEquals("B", shown(listed))
        assertEquals(listOf("C"), ids(listed.waitingApprovals))
        val same = ab.listed("approval", approval("A"), listOf(approval("B")))
        assertEquals("A", shown(same))
        assertEquals(listOf("B"), ids(same.waitingApprovals))
        val none = ab.listed("approval", null, emptyList())
        assertNull(none.approval)
        assertTrue(none.waitingApprovals.isEmpty())
    }

    @Test fun hermesListLeavesTheOtherKindAlone() {
        val state = ab.asked("clarify", clarify("Q1")).asked("clarify", clarify("Q2"))
        val listed = state.listed("approval", null, emptyList())
        assertEquals("Q1", listed.clarification?.string("id"))
        assertEquals(listOf("Q2"), ids(listed.waitingClarifications))
    }

    @Test fun aPromptOfAnOldSessionStyleIdKeyIsStillMatchedByEitherKey() {
        val legacy = json("id" to "wire-1", "approval_id" to "req-1", "choices" to JSONArray().put("once"))
        val state = ChatState().asked("approval", legacy)
        assertNull(state.ended("wire-1").approval)
        assertNull(state.ended("req-1", "approval").approval)
    }

    // ---- S2: the next card is never actionable by the tap that answered the previous one ----

    @Test fun aCardThatTakesTheFrontFromAnotherCardIsGuardedForAMoment() {
        val b = ab.ended("A", "approval", now = 5_000).approval!!
        assertEquals("B", b.string("id"))
        assertEquals(5_000L + PromptGuard.windowMs, promptGuardedUntil(b))
    }

    @Test fun theGuardWindowIsAboutSevenHundredMilliseconds() {
        assertTrue("about 600-800 ms", PromptGuard.windowMs in 600L..800L)
    }

    @Test fun aSettleOfTheFrontCardGuardsTheNextOneToo() {
        assertEquals(9_000L + PromptGuard.windowMs, promptGuardedUntil(ab.ended("A", now = 9_000).approval))
    }

    @Test fun theFirstCardOfAChatIsNeverGuarded() {
        assertEquals(0L, promptGuardedUntil(ChatState().asked("approval", approval("A"), now = 1_000).approval))
        assertEquals(0L, promptGuardedUntil(ab.approval))
    }

    @Test fun aCardWaitingBehindAnotherIsNotGuardedUntilItsTurnComes() {
        assertEquals(0L, promptGuardedUntil(ab.waitingApprovals.single()))
    }

    @Test fun aCardKeepsItsGuardStampWhileItStaysInFront() {
        val b = ab.ended("A", now = 5_000)
        val again = b.asked("approval", approval("B").put("command", "Changed"), now = 8_000)
        assertEquals("Changed", again.approval?.string("command"))
        assertEquals(5_000L + PromptGuard.windowMs, promptGuardedUntil(again.approval))
    }

    @Test fun aPollThatChangesTheFrontGuardsTheNewFrontAndOneThatKeepsItDoesNot() {
        val changed = ab.listed("approval", approval("B"), listOf(approval("C")), now = 7_000)
        assertEquals(7_000L + PromptGuard.windowMs, promptGuardedUntil(changed.approval))
        val same = ab.listed("approval", approval("A"), listOf(approval("B")), now = 7_000)
        assertEquals(0L, promptGuardedUntil(same.approval))
    }

    @Test fun aQuestionThatTakesTheFrontIsGuardedToo() {
        val state = ChatState().asked("clarify", clarify("Q1")).asked("clarify", clarify("Q2")).ended("Q1", "clarify", now = 4_000)
        assertEquals(4_000L + PromptGuard.windowMs, promptGuardedUntil(state.clarification))
    }

    // ---- S3: a dead card at the front never hides a live one behind it ----

    @Test fun aPromptThatTimedOutDoesNotHideALiveOneBehindIt() {
        val state = ChatState().asked("approval", timed("A"), now = 0).asked("approval", timed("B"), now = 301_000)
        assertEquals("B", shown(state))
        assertTrue(state.waitingApprovals.isEmpty())
    }

    @Test fun aPromptLeavesTheQueueWhenItsOwnWaitRunsOut() {
        val state = ChatState().asked("approval", timed("A"), now = 0).asked("approval", timed("B"), now = 100_000).asked("approval", timed("C"), now = 250_000)
        assertEquals("A", shown(state.promptsPruned(299_999)))
        val afterA = state.promptsPruned(360_000)
        assertEquals("B", shown(afterA))
        assertEquals(listOf("C"), ids(afterA.waitingApprovals))
        assertEquals("C", shown(state.promptsPruned(500_000)))
        assertNull(state.promptsPruned(600_000).approval)
    }

    @Test fun theCardThatComesUpWhenTheDeadOneLeavesIsGuarded() {
        val state = ChatState().asked("approval", timed("A"), now = 0).asked("approval", timed("B"), now = 100_000).promptsPruned(300_000)
        assertEquals("B", shown(state))
        assertEquals(300_000L + PromptGuard.windowMs, promptGuardedUntil(state.approval))
    }

    @Test fun theNextExpiryIsTheEarliestWaitThatWillRunOut() {
        val state = ChatState().asked("approval", timed("A"), now = 0).asked("approval", timed("B", 100), now = 50_000)
        assertEquals(150_000L, nextPromptExpiry(state))
        assertEquals(300_000L, nextPromptExpiry(state.promptsPruned(150_000)))
        assertNull(nextPromptExpiry(state.promptsPruned(400_000)))
        assertNull(nextPromptExpiry(ChatState()))
    }

    @Test fun aPromptWithoutAWaitNeverExpires() {
        val state = ChatState().asked("approval", approval("A"), now = 0).asked("clarify", clarify("Q"), now = 0)
        assertNull(nextPromptExpiry(state))
        val later = state.promptsPruned(Long.MAX_VALUE / 2)
        assertEquals("A", shown(later))
        assertEquals("Q", later.clarification?.string("id"))
    }

    @Test fun theSamePromptAskedAgainKeepsItsFirstArrival() {
        val state = ChatState().asked("approval", timed("A"), now = 0).asked("approval", timed("A"), now = 200_000)
        assertEquals(300_000L, nextPromptExpiry(state))
    }

    @Test fun hermesListKeepsTheArrivalOfAPromptTheChatAlreadyKnew() {
        val state = ChatState().asked("approval", timed("A"), now = 0).listed("approval", timed("A"), emptyList(), now = 200_000)
        assertEquals(300_000L, nextPromptExpiry(state))
    }

    // ---- S1: the rebuild from Hermes' own list ----

    @Test fun aPollReplyShowsHermesFirstPromptAndKeepsTheOnesBehindIt() {
        val polled = ChatState().promptsPolled(reply(approval("A"), approval("B")), null, 10, Long.MAX_VALUE)
        assertEquals("A", shown(polled))
        assertEquals(listOf("B"), ids(polled.waitingApprovals))
    }

    @Test fun aFailedAskIsNotAnAnswerSoTheQueueStays() {
        val polled = ab.promptsPolled(null, null, 10, Long.MAX_VALUE)
        assertEquals("A", shown(polled))
        assertEquals(listOf("B"), ids(polled.waitingApprovals))
    }

    @Test fun aReplyWithNothingPendingClearsThatKindOnly() {
        val state = ab.asked("clarify", clarify("Q1"))
        val polled = state.promptsPolled(reply(null), null, 10, Long.MAX_VALUE)
        assertNull(polled.approval)
        assertTrue(polled.waitingApprovals.isEmpty())
        assertEquals("Q1", polled.clarification?.string("id"))
    }

    @Test fun eachKindIsRebuiltFromItsOwnReply() {
        val polled = ChatState().promptsPolled(reply(approval("A"), approval("B")), reply(clarify("Q1"), clarify("Q2")), 10, Long.MAX_VALUE)
        assertEquals(listOf("A", "B"), order(polled))
        assertEquals(listOf("Q1", "Q2"), listOfNotNull(polled.clarification).plus(polled.waitingClarifications).map { it.string("id") })
    }

    @Test fun aReplyForTheOtherKindLeavesTheQueueAlone() {
        val polled = ab.promptsPolled(null, reply(clarify("Q1")), 10, Long.MAX_VALUE)
        assertEquals("A", shown(polled))
        assertEquals(listOf("B"), ids(polled.waitingApprovals))
        assertEquals("Q1", polled.clarification?.string("id"))
    }

    // ---- Nice 2: a poll answer must not drop a prompt that arrived while it was in flight ----

    @Test fun aPromptThatArrivedWhileThePollWasInFlightIsNotDropped() {
        val state = ChatState().asked("approval", approval("A"), now = 100).asked("approval", approval("B"), now = 200)
        val polled = state.promptsPolled(reply(approval("A")), null, now = 300, since = 150)
        assertEquals(listOf("A", "B"), order(polled))
    }

    @Test fun aPromptSeenBeforeThePollBeganIsDroppedWhenHermesDoesNotListIt() {
        val state = ChatState().asked("approval", approval("A"), now = 100).asked("approval", approval("B"), now = 200)
        val polled = state.promptsPolled(reply(approval("A")), null, now = 300, since = 250)
        assertEquals(listOf("A"), order(polled))
    }

    @Test fun aPromptThatArrivedDuringThePollAndIsListedIsNotShownTwice() {
        val state = ChatState().asked("approval", approval("A"), now = 100).asked("approval", approval("B"), now = 200)
        val polled = state.promptsPolled(reply(approval("A"), approval("B")), null, now = 300, since = 150)
        assertEquals(listOf("A", "B"), order(polled))
    }

    @Test fun aPromptThatArrivedDuringThePollKeepsItsPlaceAfterTheListedOnes() {
        val state = ChatState().asked("approval", approval("A"), now = 100).asked("approval", approval("C"), now = 200)
        val polled = state.promptsPolled(reply(approval("A"), approval("B")), null, now = 300, since = 150)
        assertEquals(listOf("A", "B", "C"), order(polled))
    }

    // ---- R16 Should 1: a card that appears right after the owner answered is guarded, whether or not it replaced another card ----

    /** The owner answered A (the only card) at [at]; A is gone and nothing waits behind it. */
    private fun answeredAt(at: Long) = ChatState().asked("approval", approval("A"), now = 1_000).answerTapped(at).ended("A", "approval", now = at + 50)

    @Test fun aCardThatAppearsRightAfterAnAnswerIsGuarded() {
        val state = answeredAt(10_000).asked("approval", approval("B"), now = 10_300)
        assertEquals("B", shown(state))
        assertEquals(10_300L + PromptGuard.windowMs, promptGuardedUntil(state.approval))
    }

    @Test fun aCardThatAppearsTwoSecondsAfterAnAnswerIsNotGuarded() {
        val state = answeredAt(10_000).asked("approval", approval("B"), now = 12_000)
        assertEquals(0L, promptGuardedUntil(state.approval))
    }

    @Test fun theGuardAfterAnAnswerEndsAtTheWindow() {
        val justInside = 10_000L + PromptGuard.windowMs - 1
        assertEquals(justInside + PromptGuard.windowMs, promptGuardedUntil(answeredAt(10_000).asked("approval", approval("B"), now = justInside).approval))
        assertEquals(0L, promptGuardedUntil(answeredAt(10_000).asked("approval", approval("B"), now = 10_000L + PromptGuard.windowMs).approval))
    }

    @Test fun aSettleIsNotAnAnswerSoACardAfterItIsNotGuarded() {
        val settled = ChatState().asked("approval", approval("A"), now = 1_000).ended("A", "approval", now = 10_000)
        assertEquals(0L, promptGuardedUntil(settled.asked("approval", approval("B"), now = 10_100).approval))
    }

    @Test fun aCardWithNoAnswerBeforeItIsNotGuarded() {
        assertEquals(0L, promptGuardedUntil(ChatState().asked("approval", approval("A"), now = 500).approval))
    }

    @Test fun anAnswerGuardsTheNextCardOfEitherKind() {
        val afterApproval = answeredAt(10_000).asked("clarify", clarify("Q1"), now = 10_200)
        assertEquals(10_200L + PromptGuard.windowMs, promptGuardedUntil(afterApproval.clarification))
        val afterQuestion = ChatState().asked("clarify", clarify("Q0"), now = 1_000).answerTapped(20_000).ended("Q0", "clarify", now = 20_040).asked("approval", approval("A"), now = 20_300)
        assertEquals(20_300L + PromptGuard.windowMs, promptGuardedUntil(afterQuestion.approval))
    }

    @Test fun anAnswerThatLandsLaterRestartsTheWindow() {
        // The tap at 10 s, the answer lands at 10.9 s (a slow link) and the owner's repeat tap follows: the request that appears at 11.3 s is 0.4 s after the answer.
        val state = answeredAt(10_000).answerTapped(10_900).asked("approval", approval("B"), now = 11_300)
        assertEquals(11_300L + PromptGuard.windowMs, promptGuardedUntil(state.approval))
    }

    @Test fun aCardThatArrivesByAPollRightAfterAnAnswerIsGuarded() {
        val state = answeredAt(10_000).promptsPolled(reply(approval("B")), null, now = 10_200, since = 10_100)
        assertEquals("B", shown(state))
        assertEquals(10_200L + PromptGuard.windowMs, promptGuardedUntil(state.approval))
    }

    @Test fun aCardThatTakesTheFrontIsStillGuardedWithNoRecentAnswer() {
        assertEquals(30_000L + PromptGuard.windowMs, promptGuardedUntil(ab.ended("A", "approval", now = 30_000).approval))
    }

    // ---- a poll reply older than a settle must not bring the ended prompt back ----

    @Test fun aPollReplyOlderThanASettleDoesNotBringTheEndedPromptBack() {
        // The ask began at 120; A settled at 150, before the reply (computed at Hermes before that) came back still listing it.
        val state = ChatState().asked("approval", approval("A"), now = 100).asked("approval", approval("B"), now = 110).ended("A", "approval", now = 150)
        val polled = state.promptsPolled(reply(approval("A"), approval("B")), null, now = 200, since = 120)
        assertEquals(listOf("B"), order(polled))
    }

    @Test fun aPromptTheOwnerAnsweredDuringThePollDoesNotComeBackEither() {
        val state = ChatState().asked("approval", approval("A"), now = 100).answerTapped(140).ended("A", "approval", now = 150)
        assertEquals(emptyList<String>(), order(state.promptsPolled(reply(approval("A")), null, now = 200, since = 120)))
    }

    @Test fun aPromptThatEndedBeforeThePollBeganIsListedAgainWhenHermesStillHoldsIt() {
        val state = ChatState().asked("approval", approval("A"), now = 100).ended("A", "approval", now = 110)
        assertEquals(listOf("A"), order(state.promptsPolled(reply(approval("A")), null, now = 200, since = 120)))
    }

    @Test fun anEndedQuestionDoesNotComeBackFromAnOlderReplyEither() {
        val state = ChatState().asked("clarify", clarify("Q1"), now = 100).ended("Q1", "clarify", now = 150)
        val polled = state.promptsPolled(null, reply(clarify("Q1")), now = 200, since = 120)
        assertNull(polled.clarification)
    }

    @Test fun theMemoryOfEndedPromptsIsBounded() {
        var state = ChatState()
        for (i in 1..40) state = state.asked("approval", approval("P$i"), now = 100L + i).ended("P$i", "approval", now = 100L + i)
        assertTrue("the memory holds the recent ones only", state.endedPrompts.size <= 40 && state.endedPrompts.size >= 8)
        assertEquals("a very old one is forgotten, a recent one is not", listOf("P1"), order(state.promptsPolled(reply(approval("P1"), approval("P40")), null, now = 500, since = 0)))
    }
}
