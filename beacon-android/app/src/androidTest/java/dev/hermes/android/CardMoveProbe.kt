package dev.hermes.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import dev.hermes.android.core.json

/**
 * Moves an open approval or question card without the owner touching it, and taps it the way a finger does, for the cases that prove a card cannot be answered by a tap that
 * began before, or lands just after, a move (CARDS-121, backlog 17 and 19). The movers are real ones: a notice making room above the composer, and the transcript scrolling under the finger.
 */
internal object CardMoveProbe {
    /** The settle window the cases run with: wide enough that a slow emulator cannot outrun it. The production value (400 ms) is pinned by the JVM tests. */
    const val WIDE_WINDOW_MS = 3_000L

    fun withWindow(ms: Long, block: () -> Unit) { val production = PromptSettleWindow.ms; PromptSettleWindow.ms = ms; try { block() } finally { PromptSettleWindow.ms = production } }

    fun position(rule: ComposeTestRule, text: String): Offset = rule.onNodeWithText(text).fetchSemanticsNode().positionInRoot
    fun center(rule: ComposeTestRule, text: String): Offset = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center
    fun contains(rule: ComposeTestRule, text: String, point: Offset): Boolean = rule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.contains(point)

    /** Enough rows that the transcript scrolls and the card is its last row, as in a chat that has been going a while. */
    fun fillTranscript(rule: ComposeTestRule, chat: () -> ChatState, set: (ChatState) -> Unit) {
        rule.runOnIdle {
            val state = chat()
            set(state.copy(messages = state.messages + (1..14).map { i ->
                Message.from(json("row_id" to "filler-$i", "role" to "assistant", "content" to "Synthetic filler row $i. It only gives the transcript some height."), state.messages.size + i, state.sessionId)
            }))
        }
        rule.waitForIdle()
    }

    /** Scrolls the transcript by [px] (negative = the card moves down), the way follow-scroll, a growing row above or the keyboard would. True when the card's buttons really moved. */
    fun scrollBy(rule: ComposeTestRule, text: String, px: Float): Boolean {
        val before = position(rule, text)
        rule.onNodeWithTag("conversation-transcript").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        rule.waitForIdle()
        return position(rule, text) != before
    }

    /** Shows a notice above the composer: the transcript makes room for it, so the card moves by its height. True when the card's buttons really moved. */
    fun showNotice(rule: ComposeTestRule, text: String, show: () -> Unit): Boolean {
        val before = position(rule, text)
        rule.runOnIdle { show() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 4_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) { rule.waitForIdle(); if (position(rule, text) != before) return true; Thread.sleep(50) }
        return false
    }

    /** The card's buttons have stood still for the whole window plus a margin: a card that is still moving, or just moved, takes no tap. */
    fun waitToSettle(rule: ComposeTestRule, text: String) {
        rule.waitUntil(8_000) { runCatching { rule.onNodeWithText(text).assertIsEnabled() }.isSuccess }
        var last = position(rule, text)
        var since = android.os.SystemClock.elapsedRealtime()
        while (android.os.SystemClock.elapsedRealtime() - since < PromptSettleWindow.ms + 300L) {
            Thread.sleep(50)
            val now = position(rule, text)
            if (now != last) { last = now; since = android.os.SystemClock.elapsedRealtime() }
        }
    }

    /** Taps [text] the two ways a card is answered: by coordinates (a real touch, which found a real bug in #276) and by its semantic click. */
    fun tapBothWays(rule: ComposeTestRule, text: String) {
        rule.onNodeWithText(text).performScrollTo().performClick()
        runCatching { rule.onNodeWithText(text).performSemanticsAction(SemanticsActions.OnClick) }
    }

    /** Taps [text] until [done], so a card that has not yet settled (it ignores taps for a moment after a move) is answered once it has; a tap that went through is not repeated. */
    fun answerWhenSettled(rule: ComposeTestRule, text: String, semantic: Boolean = false, done: () -> Boolean) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
        while (!done() && android.os.SystemClock.elapsedRealtime() < deadline) {
            runCatching {
                if (semantic) rule.onNodeWithText(text).performScrollTo().performSemanticsAction(SemanticsActions.OnClick) else rule.onNodeWithText(text).performScrollTo().performClick()
            }
            rule.waitForIdle()
            val until = android.os.SystemClock.elapsedRealtime() + 300
            while (!done() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(20)
        }
        check(done()) { "The card was not answered within 12 s of tapping" }
    }
}
