package dev.hermes.android

/** How long (ms, the phone's real clock) taps on an approval or question card are ignored after its buttons move. Device tests widen it so a slow emulator cannot outrun it. */
internal object PromptSettleWindow { @Volatile var ms = 400L }

/**
 * A card must never move under a real finger (backlog 17 and 19). Whatever moves its buttons (follow-scroll, the live row, a notice making room, a new row, the keyboard),
 * a tap is taken only when the buttons have stood still for the window, and the finger went down after they had: a press that began before a move, or inside its window,
 * never lands, however late it is released. Reports come from the card's layout and its pointer events; every time is the phone's real clock.
 *
 * It decides at click time and never disables the buttons, so scrolling past a card does not make them flicker; a click with no finger (an accessibility click) counts only the time since the last move.
 */
internal class PromptSettle(private val window: () -> Long = { PromptSettleWindow.ms }) {
    @Volatile private var placed: FloatArray? = null
    @Volatile private var movedAt = NEVER
    @Volatile private var pressedAt = NEVER
    @Volatile private var releasedAt = NEVER

    /** The buttons' place in the window. The first report is where they are, not a move; any later change is. */
    fun placed(x: Float, y: Float, width: Int, height: Int, now: Long) {
        val before = placed
        placed = floatArrayOf(x, y, width.toFloat(), height.toFloat())
        if (before != null && (kotlin.math.abs(before[0] - x) > JITTER || kotlin.math.abs(before[1] - y) > JITTER || before[2] != width.toFloat() || before[3] != height.toFloat())) movedAt = now
    }
    fun pressed(now: Long) { pressedAt = now }
    fun released(now: Long) { releasedAt = now }

    /** Whether a click at [now] may be taken. */
    fun admits(now: Long): Boolean {
        val wait = window()
        if (now - movedAt < wait) return false
        // A finger that is down, or was lifted this instant (the click follows its release), began this tap: it must have gone down after the buttons had stood still.
        val fingerInvolved = pressedAt != NEVER && (pressedAt > releasedAt || now - releasedAt <= RELEASE_FRESH_MS)
        return !(fingerInvolved && pressedAt < movedAt + wait)
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE / 4
        const val JITTER = 0.5f
        const val RELEASE_FRESH_MS = 150L
    }
}

/** The paths that answer an approval or a question: the answer of a failed one must never be silent. */
internal val PROMPT_ANSWER_PATHS = setOf("/api/approval/respond", "/api/clarify/respond")

internal const val PROMPT_ALREADY_ANSWERED_NOTICE = "This request was already answered."

/** What the owner is told when an answer did not go and the chat it was for is no longer the one this call belongs to (it was reselected, or the connection was replaced). */
internal fun lostAnswerNotice(sameChat: Boolean): String =
    if (sameChat) "Your answer didn't go. Try again." else "Your answer didn't go. Open that chat to answer again."

/** Hermes took no answer from this tap because the request was already settled: it ended, or another phone answered first. Blank when the answer was taken. */
internal fun promptAnswerNotice(status: String): String = when (status) {
    "ended" -> PROMPT_ENDED_NOTICE
    "already_resolved" -> PROMPT_ALREADY_ANSWERED_NOTICE
    else -> ""
}
