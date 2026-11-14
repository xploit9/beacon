package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import java.util.UUID

/**
 * Only what was sent to the agent is in the chat. A message the phone still holds waits in the box above the composer; a message Hermes
 * never started goes back to that box as "Not sent". These rules decide which of the two places a held message belongs to,
 * and the chat timeline and the box both read them, so a message is never in both places and never in neither.
 *
 * The phone has proof Hermes never took a message from one receipt only: outcome `cancelled`. Hermes settles `completed`, `interrupted`,
 * `rejected` and `failed` only for an admission it had started (settle_session_input), so those messages reached the agent, stay in the
 * chat and show two blue ticks. `cancelled` is reached only by cancelling a queued admission that never started (cancel_session_input). A refusal before any admission
 * never gets a settled state on the phone: the send returns to "queued" and waits in the box (BeaconViewModel.send and sendSteer).
 */
internal val NOT_SENT_OUTCOMES = setOf("cancelled")

/** The outcomes Hermes settles only for a turn it started (settle_session_input): the message was started, whether the turn went well or not. */
internal val STARTED_OUTCOMES = setOf("completed", "interrupted", "rejected", "failed")

/**
 * Hermes cancelled this message before it started. A row id from Hermes means Hermes holds the message, whatever its turn did afterwards,
 * so only a message with no row id can be "not sent"; one with a row is shown as itself, in the chat.
 */
internal fun QueuedMessage.notSent(): Boolean = state == "settled" && outcome in NOT_SENT_OUTCOMES && rowId.isBlank()

/** In the box: waiting to be sent, or not sent. */
internal fun QueuedMessage.inBox(): Boolean = state == "queued" || notSent()

/** The box's rows, oldest first. */
internal fun queueBoxItems(queued: List<QueuedMessage>): List<QueuedMessage> = queued.filter { it.inBox() }.sortedBy { it.createdAt }

/** A queued message can be steered into the reply that is running, if it is plain text. */
internal fun canSteerQueued(item: QueuedMessage, replying: Boolean): Boolean =
    replying && item.state == "queued" && item.attachments.isEmpty() && item.text.isNotBlank()

/** The one main button of a box row: Steer into the running reply, Send a waiting message now, or Retry one that was not sent. Retry never appears on a message that is merely waiting. */
internal enum class QueueBoxAction { Steer, Send, Retry, None }
internal fun queueBoxAction(item: QueuedMessage, replying: Boolean): QueueBoxAction = when {
    item.notSent() -> QueueBoxAction.Retry
    canSteerQueued(item, replying) -> QueueBoxAction.Steer
    item.state == "queued" && !replying -> QueueBoxAction.Send
    else -> QueueBoxAction.None
}

/** What the phone keeps of Hermes' answer about an admission: "queued" (it waits behind another turn) or "started"; anything else says nothing. */
internal fun admissionStatusOf(receiptStatus: String): String = receiptStatus.takeIf { it == "queued" || it == "started" }.orEmpty()

/**
 * Hermes accepted this plain message but its admission is still queued: Hermes is running another turn (started on another client, say) and nothing of
 * this message has started. No reply that streams, and no reply row after it, answers it yet: it shows two grey ticks, never blue, until a frame of its
 * own turn arrives ([afterTurnFrame]) or a receipt says started ([afterReceipt]).
 */
internal fun QueuedMessage.queuedBehindAnotherTurn(): Boolean = state == "accepted" && kind == "queue" && admissionStatus == "queued"

/**
 * The copy after a frame of the turn Hermes is running, or null when the frame changes nothing. Hermes answers "queued" to every fresh send (the row waits for
 * the reply loop to claim it), an idle chat too, so that answer alone says nothing about whose turn streams. Every frame of a turn carries that turn's
 * admission (stamped when the turn is claimed: session_authority._stamp_claim, session_events.publish), so a frame that names this copy's own admission shows
 * Hermes started it. A frame of another turn running before it names another admission; the one frame that names a never-started row's admission is
 * its cancellation (session_authority._settle_cancelled), which carries outcome "cancelled".
 */
internal fun QueuedMessage.afterTurnFrame(frameAdmission: String, outcome: String = ""): QueuedMessage? =
    if (queuedBehindAnotherTurn() && admissionId.isNotBlank() && frameAdmission == admissionId && outcome != "cancelled") copy(admissionStatus = "started") else null

/**
 * The copy after a receipt check: its state and outcome, and, while Hermes still holds it as accepted, what Hermes now says about the admission (an unreadable answer changes
 * nothing). A started admission never goes back to queued: a receipt read just before Hermes claimed the row and answered after its stamped start frame is older than that frame.
 */
internal fun QueuedMessage.afterReceipt(state: String, outcome: String, receiptStatus: String): QueuedMessage {
    val said = if (state == "accepted") admissionStatusOf(receiptStatus) else ""
    return copy(state = state, outcome = outcome, admissionStatus = if (said == "queued" && admissionStatus == "started") admissionStatus else said.ifBlank { admissionStatus })
}

/** The same message, now being sent at once as a steer. */
internal fun QueuedMessage.asSteer(): QueuedMessage = copy(state = "sending", kind = "steer")

/**
 * One tap on Steer: the row as this tap leaves it, or null when it must do nothing because an earlier tap already moved the row (or a reply ended, or
 * the row has files, or it is not waiting). The caller sets its own "moved" flag only when this returns a row, so a stale second tap cannot post the
 * same steer again (`/api/chat/steer` carries no request id Hermes could drop it by).
 */
internal fun claimSteer(item: QueuedMessage, replying: Boolean): QueuedMessage? = if (canSteerQueued(item, replying)) item.asSteer() else null

/**
 * Why Send on a waiting row cannot start now, in words for the owner; null when it can. Send hands the oldest waiting message to the drain, which
 * quietly gives up while a call, an approval, a question, an unconfirmed message or a running reply is in the way, so the tap says what to do instead.
 */
internal fun queueSendBlockedNotice(callActive: Boolean, approval: Boolean, question: Boolean, uncertain: Boolean, replying: Boolean, headState: String?): String? = when {
    callActive || approval || question -> "Finish the call or answer the request first."
    uncertain || headState == "unknown" -> "Tap your message to check it."
    replying || headState == "accepted" || headState == "sending" -> "Wait for the current reply first."
    else -> null
}

/** The same message, held to be sent again as a new submission (the earlier one did not go through). */
internal fun QueuedMessage.heldAgain(): QueuedMessage =
    copy(state = "queued", kind = "queue", admissionId = "", admissionStatus = "", outcome = "", submissionId = UUID.randomUUID().toString())

/** The TalkBack label of a box button: the action and the first 40 characters of the message, so a pasted paragraph is not read twice per row. */
internal fun queueButtonLabel(action: String, words: String): String {
    val cut = if (words.length <= 40) words else words.substring(0, if (Character.isHighSurrogate(words[39])) 39 else 40).trimEnd()
    return "$action $cut"
}
