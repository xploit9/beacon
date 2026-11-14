package dev.hermes.android.ui

import dev.hermes.android.Message
import dev.hermes.android.NOT_SENT_OUTCOMES
import dev.hermes.android.STARTED_OUTCOMES
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.queuedBehindAnotherTurn

/** Where an own message stands: still on the phone (one grey tick), with Hermes (two grey), answered or being answered (two blue). */
internal enum class SendTick { Pending, Delivered, Answered }

internal fun sendTickLabel(tick: SendTick): String = when (tick) {
    // One grey tick also covers a send whose outcome is not known, when Hermes may already have the message: never say "not sent".
    SendTick.Pending -> "Not confirmed yet"
    SendTick.Delivered -> "Delivered"
    SendTick.Answered -> "Answered"
}
internal fun sendTickCount(tick: SendTick): Int = if (tick == SendTick.Pending) 1 else 2
internal fun sendTickBlue(tick: SendTick): Boolean = tick == SendTick.Answered

private const val BLUE_FOR_DARK_BUBBLES = 0xFF2196F3L
private const val BLUE_FOR_LIGHT_BUBBLES = 0xFF1565C0L

/** WCAG relative luminance of an ARGB colour (alpha ignored). */
internal fun relativeLuminance(argb: Long): Double {
    fun channel(shift: Int): Double {
        val v = ((argb shr shift) and 0xFF) / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** WCAG contrast ratio of two ARGB colours. */
internal fun contrastRatio(a: Long, b: Long): Double {
    val la = relativeLuminance(a); val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/** The blue of the two ticks of an answered message: the one of two blues that reads better on this bubble colour (a light bubble needs the darker blue). */
internal fun answeredTickBlueArgb(bubbleArgb: Long): Long =
    if (contrastRatio(BLUE_FOR_LIGHT_BUBBLES, bubbleArgb) > contrastRatio(BLUE_FOR_DARK_BUBBLES, bubbleArgb)) BLUE_FOR_LIGHT_BUBBLES else BLUE_FOR_DARK_BUBBLES

/** The tick of a message Hermes has saved. */
internal fun historySendTick(answered: Boolean): SendTick = if (answered) SendTick.Answered else SendTick.Delivered

/**
 * The tick of a message the phone still tracks. Until Hermes is known to hold it, it is one tick: a message of unknown outcome is not
 * called delivered, and one Hermes cancelled before it started (it lives in the queue box as "Not sent") is never shown as delivered or answered.
 * A turn that completed, failed, was rejected or was interrupted was started by Hermes (it settles those only for a turn it started), so the message
 * shows two blue ticks, with or without a reply row after it.
 */
internal fun localSendTick(message: QueuedMessage, answered: Boolean): SendTick = when (message.state) {
    // Blue needs real data for this message. One Hermes still holds as queued behind another turn has none: two grey ticks, whatever streams or follows it.
    // It turns blue when a frame of its own turn arrives (QueuedMessage.afterTurnFrame) or a receipt says started.
    "accepted" -> if (message.queuedBehindAnotherTurn()) SendTick.Delivered else historySendTick(answered)
    "sent" -> historySendTick(answered)
    "settled" -> when {
        message.outcome in STARTED_OUTCOMES -> SendTick.Answered
        // A row id from Hermes means Hermes holds the message, whatever its turn did afterwards.
        message.outcome in NOT_SENT_OUTCOMES && message.rowId.isBlank() -> SendTick.Pending
        else -> historySendTick(answered)
    }
    else -> SendTick.Pending
}

/** Rows that are Beacon's own words to the owner but not a reply to a message: alerts, call lines, and results of background work. */
private val NOT_A_REPLY = setOf("async_delegation_complete", "process_complete")

private fun isBeaconReply(message: Message): Boolean =
    message.role == "assistant" && !message.id.startsWith("alert:") && !message.id.startsWith("call:") && message.displayKind !in NOT_A_REPLY

/**
 * The own messages Beacon has answered: an assistant reply comes after them in the chat, or [replying] (a reply is streaming now, below
 * everything shown). Read from the rows the chat shows, never guessed from a timer.
 *
 * This is only about the rows and the live reply. A phone copy Hermes still holds as queued behind another turn is held back by [localSendTick] itself,
 * from the admission status Hermes gave, so neither this streaming reply nor a reply row after it makes that copy blue.
 */
internal fun answeredOwnMessageIds(messages: List<Message>, replying: Boolean): Set<String> {
    val answered = HashSet<String>()
    var replyFollows = replying
    for (message in messages.asReversed()) {
        if (isBeaconReply(message)) replyFollows = true
        else if (replyFollows && isOwnMessage(message)) answered += message.id
    }
    return answered
}

/** A steer is marked in its bubble; Hermes' saved row names it, and so does the phone's own copy. */
internal fun isSteerMessage(message: Message): Boolean = message.displayKind == "steer"
internal fun isSteerMessage(message: QueuedMessage): Boolean = message.kind == "steer"

/** The empty composer's hint while a reply runs: what Send will do, in the same words as the pill. */
internal fun busyComposerPlaceholder(hasFiles: Boolean, busyAction: String): String =
    if (hasFiles || busyAction == "queue") "Queue a message…" else "Change the reply…"

/** The small pill above the composer while a reply runs: what Send will do. */
internal fun busyComposerCaption(slash: Boolean, hasFiles: Boolean, busyAction: String, explicitQueue: Boolean): String = when {
    explicitQueue -> "Queue"
    slash -> "Run command"
    hasFiles || busyAction == "queue" -> "Queue"
    else -> "Send now"
}
