package dev.hermes.android.data

import dev.hermes.android.core.string
import org.json.JSONObject

/**
 * Hermes' own answer that it does not hold a session: the legacy gateway's 4007 "session not found" (tui_gateway/methods_session.py:815) or the canonical authority's
 * 4001 "not_found" (gateway/session_controls.py:372-374, raised for a missing session by hermes_state_runtime.py:75 and gateway/session_local_recovery.py:300). A REST 404
 * is not this answer. Only for a call about one session: Hermes also says not_found for a missing admission (hermes_state_runtime.py:85), which is not a missing conversation.
 */
internal fun nativeSessionNotFound(error: Throwable): Boolean = error is ApiFailure &&
    (error.status == 4007 && error.detail == "session not found" || error.status == 4001 && error.detail == "not_found")

internal fun missingNativeConversation(error: Throwable): Boolean = error is ApiFailure && error.status == 404 || nativeSessionNotFound(error)

/**
 * Hermes refused the Beacon plugin for this profile (its own config does not list it, `canonical_transport.native_policy`). Nothing was done, so the client turns Beacon on by itself
 * and runs the request once more ([BeaconClient]); this reaches a screen only as a [BeaconOffFailure], when that did not work.
 */
internal fun beaconOffForProfile(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    it is ApiFailure && it.plugin && it.status == 403 && it.detail.equals("Plugin not authorized", ignoreCase = true)
}

/**
 * Hermes still refuses Beacon for this profile after the phone tried to turn it on. [reason] is why the turn-on failed, or null when the profile itself turned Beacon off on Hermes
 * (`plugins.disabled`, the owner's choice): only the owner's own Turn on changes that. Still the plugin's 403, so every page that keeps a chat and its draft for it does so.
 */
internal class BeaconOffFailure(val reason: Throwable?) : ApiFailure(403, "Plugin not authorized", plugin = true) {
    init { reason?.let(::initCause) }
}

/** The profile turned Beacon off on Hermes: the one case the person is asked, with Turn on. */
internal const val PROFILE_PLUGIN_OFF_ERROR = "Beacon isn't turned on for this profile."
/** The phone could not turn Beacon on for the profile (Hermes busy, slow, unreachable or refusing): Retry runs the turn-on and the refused step again. */
internal const val PROFILE_TURN_ON_FAILED_ERROR = "Couldn't turn on Beacon for this profile."
/** Hermes no longer has the profile (the plugin answers 422): nothing to turn on and nothing to tap. */
internal const val PROFILE_GONE_ERROR = "Hermes no longer has this profile."
/** Every line a refused profile can show; a chat page with one of them stays that chat and keeps its draft. */
internal val BeaconOffLines = setOf(PROFILE_PLUGIN_OFF_ERROR, PROFILE_TURN_ON_FAILED_ERROR, PROFILE_GONE_ERROR)
/**
 * The one line for a request Hermes refused for this profile, told by why turning Beacon on did not work. A 400 is a plugin older than the phone's `auto` turn-on (1.2.0 takes
 * exactly `{profile}`; this app never sends a body the route refuses): the Turn on line, whose button sends no `auto`, works there.
 */
internal fun beaconOffLine(error: Throwable): String {
    val failure = generateSequence(error) { it.cause }.take(8).filterIsInstance<BeaconOffFailure>().firstOrNull()
    val reason = failure?.reason
    return when {
        failure != null && reason == null -> PROFILE_PLUGIN_OFF_ERROR
        reason is ApiFailure && reason.status == 400 -> PROFILE_PLUGIN_OFF_ERROR
        reason is ApiFailure && reason.status == 422 -> PROFILE_GONE_ERROR
        else -> PROFILE_TURN_ON_FAILED_ERROR
    }
}
/** The button beside [line]: Turn on for a profile that turned Beacon off, nothing for a profile Hermes no longer has, else Retry. */
internal fun beaconOffAction(line: String): String = when (line) {
    PROFILE_PLUGIN_OFF_ERROR -> "Turn on"
    PROFILE_GONE_ERROR -> ""
    else -> "Retry"
}
/** The Attach notice on a first chat that has not opened: it names the button the card really shows. */
internal const val OPEN_CHAT_NOTICE_RETRY = "Tap Retry to open the chat. Your draft is kept."
internal const val OPEN_CHAT_NOTICE_TURN_ON = "Tap Turn on to open the chat. Your draft is kept."
/** The connected Hermes refused this file (it has no file.attach for this chat, or refused the file itself); {0} is the file's name. */
internal const val ATTACHMENT_REFUSED_LINE = "This Hermes can't take “{0}”. The message stays on this phone."
/** The file cannot fit in one frame of the Beacon plugin's relay, which carries a chat with session authority; {0} is the file's name. */
internal const val ATTACHMENT_TOO_LARGE_LINE = "“{0}” is too large to send to this Hermes ({1} MB at most). The message stays on this phone."
/**
 * A message that was not sent because of one of its files, named in [line] ({0}: [file]). Nothing of the message reached Hermes; the draft and
 * its attachments stay, and a queued message waits for the owner instead of being asked again.
 */
/** The same limit at attach time, on a chat with session authority: the file's row already shows its name. */
internal const val ATTACHMENT_TOO_LARGE_ROW = "Too large to send to this Hermes ({0} MB at most)."
/** [limitMb]: the largest file this chat's connection sends, in MB ({1} of [ATTACHMENT_TOO_LARGE_LINE]). */
internal class AttachmentRefused(val file: String, val line: String, cause: Throwable? = null, val limitMb: String = "") :
    NativeTurnNotSent(line.replace("{0}", file).replace("{1}", limitMb), cause)
/** The plugin's answer to a file whose pieces stopped coming in time (canonical_transport.ATTACH_STALLED): it gave the file's buffer back. */
internal const val ATTACHMENT_STALLED = "File upload stalled"
/** How many stalls a file's send takes, each try in smaller pieces, before it is not sent (CHAT-121 follow-up 8). */
internal const val ATTACHMENT_STALL_TRIES = 3
/** Whether [response] is the plugin's own "stalled" answer for a file frame. */
internal fun attachmentStalled(response: JSONObject): Boolean = response.optJSONObject("error")?.let {
    it.optInt("code") == 408 && it.optString("message") == ATTACHMENT_STALLED && it.optJSONObject("data")?.optString("source") == "beacon"
} == true
/** A file the plugin stalled on [ATTACHMENT_STALL_TRIES] times: not sent, and a queued message with it waits for the owner instead of being tried again. */
internal class AttachmentStalled(cause: Throwable? = null) : NativeTurnNotSent("Not sent. Your draft is kept.", cause)
/** A picked file over what this chat's connection sends ([limitBytes]); its row says so, with the limit ([ATTACHMENT_TOO_LARGE_ROW]). */
internal class AttachmentTooLarge(val limitBytes: Long) : IllegalArgumentException(ATTACHMENT_TOO_LARGE_ROW)
/** A size limit as the lines state it: whole MB, never 0. */
internal fun megabytes(bytes: Long): String = (bytes / 1_000_000).coerceAtLeast(1).toString()

internal const val APP_CONNECTION_ERROR = "Can't reach Hermes. Check your connection, then try again."
internal const val THREAD_UNSUPPORTED_ERROR = "Threads aren't available on this Hermes yet."
/** A reply Hermes lost in a restart (its turn is `unknown`) that Beacon could not close: Hermes refuses the chat's queue and its Delete until it is. */
internal const val LOST_REPLY_ERROR = "Hermes lost this chat's last reply in a restart. Try again in a moment."
/** Hermes refuses Delete (unknown_execution) while a task its restart cut off holds the chat; a lost background task only goes with Hermes' own reset. */
internal const val LOST_TASK_ERROR = "A task cut off by a Hermes restart still holds this chat."
/** Delete withdrew the messages waiting behind a lost reply, then could not finish: they are back in the phone's queue ([DeleteKeptWaiting]). */
internal const val DELETE_KEPT_WAITING_ERROR = "Delete didn't finish. Your waiting message is back in the queue."
/** Hermes refuses a change (unknown_execution) while a reply its restart cut still holds the chat: the chat's Send (or Delete) lets it go. */
internal const val LOST_REPLY_HELD_ERROR = "Hermes restarted during the last reply. Send or delete it first."
/** A thread create the connected Hermes cannot take. Refused before anything is sent, and never reported as an unreachable Hermes. */
internal class NativeThreadUnsupported : java.io.IOException("Native authority cannot create this thread type")
/** No open server request of this id is held on the connection: Hermes ended it (or never sent it here). Still an IOException for every caller that treats it as one. */
internal class NativeRequestEnded : java.io.IOException("Native server request is no longer active")
private const val APP_OPERATION_ERROR = "Hermes couldn't do that. Try again."
private const val MODEL_REJECTED_ERROR = "The model rejected this request. Pick another model."
// A failed turn's line, by what Hermes says went wrong (error_surface: its code, then its family, agent/error_surface.py). Only what trying again can fix says "Try again".
private const val LIMIT_REACHED_ERROR = "The model's usage limit is reached."
private const val LIMIT_RESETS = "Resets at {0}."
private const val MODEL_BUSY_ERROR = "The model is busy. Try again in a moment."
private const val CHAT_TOO_LONG_ERROR = "This chat is too long for the model. Start a new chat."
private const val MODEL_TIMED_OUT_ERROR = "The model took too long to answer. Try again."
private const val MODEL_NO_ANSWER_ERROR = "The model couldn't answer this request."
private const val MODEL_SIGN_IN_ERROR = "The model's sign-in or API key was refused. Check it in Hermes."
private const val MODEL_CONNECTION_ERROR = "The connection to the model dropped. Try again."
private const val HERMES_DISK_FULL_ERROR = "Hermes' disk is full."
private const val OUT_OF_CREDITS_ERROR = "Out of credits for this model."
private const val LOOPBACK_RESTARTED_ERROR = "Hermes restarted. Paste the link again."
private const val SIGN_IN_EXPIRED_ERROR = "Sign-in expired. Sign in again."

/**
 * The failure happened while connecting (no network, DNS, refused or timed-out connect), so the message cannot have left the phone.
 * A lost reply or a timeout after the prompt was written stays uncertain. shortcut: a read timeout before the prompt step also stays uncertain, refine only if that shows up.
 */
internal fun requestNeverSent(error: Throwable): Boolean {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    if (chain.any { it.message == NATIVE_OUTCOME_UNKNOWN }) return false
    return chain.any {
        it is java.net.UnknownHostException || it is java.net.ConnectException || it is java.net.NoRouteToHostException ||
            it is java.net.SocketTimeoutException && it.message.orEmpty().contains("connect", ignoreCase = true)
    }
}

private val modelRejectedCodes = setOf("model_not_found", "model_entitlement", "provider_policy_blocked", "format_error")
// Hermes' failure codes (agent/error_classifier.py FailoverReason, agent/turn_failure_copy.py SITE_FAILURE_CODES, agent/error_surface.py free_tier_<kind>).
private val rateLimitCodes = setOf("rate_limit", "upstream_rate_limit", "free_tier_rate_limited")
private val busyCodes = rateLimitCodes + setOf("overloaded", "server_error", "free_tier_at_capacity", "free_tier_outage", "invalid_response", "empty_response")
private val tooLongCodes = setOf("context_overflow", "payload_too_large")
private val timeoutCodes = setOf("timeout")
/** Hermes' chat copy names slash commands, CLI steps, codes and provider dumps; such a line is not for the phone. */
private val notAPhoneLine = Regex("""(?i)(^|[\s(])/[a-z]|`|https?://|provider said|details:|traceback|exception|error code|\bhttp\s*\d{3}\b|\b[45]\d\d\b|\bhermes\s+(?:auth|setup|doctor|fallback|model|portal|login|config)\b|[{}<>\\]|~/|\.log\b|[a-z]_[a-z]""")

/** Hermes' own sentence for the person (error_surface.message): one short line with no commands, codes or provider details. */
private fun hermesOwnLine(value: String?): String? = value?.lineSequence()?.firstOrNull(String::isNotBlank)?.trim()
    ?.takeIf { it.length in 8..160 && !notAPhoneLine.containsMatchIn(it) }

/**
 * A failed turn's line as the chat keeps it (its status and the saved live frame): the phone's own sentence, whether Hermes says trying again can help, when the limit
 * resets (epoch ms, drawn when the line is shown) and Hermes' own sentence for the person. A line with none of that is the phone's sentence alone, as any other status.
 */
internal data class TurnLine(val phone: String, val retry: Boolean = false, val resetsAtMs: Long = 0L, val hermes: String = "") {
    fun encode(): String = if (!retry && resetsAtMs <= 0L && hermes.isBlank()) phone else listOf(phone, if (retry) "1" else "0", resetsAtMs.toString(), hermes).joinToString(SEPARATOR)
    companion object {
        private const val SEPARATOR = "\u001F"
        fun decode(line: String): TurnLine = line.split(SEPARATOR).let { parts ->
            if (parts.size != 4) TurnLine(line) else TurnLine(parts[0], parts[1] == "1", parts[2].toLongOrNull() ?: 0L, parts[3])
        }
    }
}

/**
 * The line for a failed turn, from Hermes' `error_surface` {layer, code, retryable, resets_at, message} (agent/error_surface.py) on its `message.complete`
 * (gateway/session_settlement_recovery.py completion_payload). Never from the frame's `text`: on a turn that failed after words were shown, Hermes keeps those words as the
 * reply (agent/turn_recovery.py _with_delivered_partial) and that path sends no `partial` flag, so the reason and the reply's words stay apart. A used-up limit (with when it
 * resets), a busy model, a chat too long for the model, a timeout, a refused sign-in or key, a dropped connection, Hermes' full disk and no credits each have their own line.
 */
internal fun turnErrorText(event: JSONObject, nowMs: Long = System.currentTimeMillis()): String {
    val surface = event.optJSONObject("error_surface")
    val code = surface?.optString("code").orEmpty().ifBlank { event.string("failure_reason") }
    val layer = surface?.optString("layer").orEmpty()
    val resetsAt = surface?.optDouble("resets_at", 0.0)?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }?.takeIf { it > nowMs } ?: 0L
    val phone = when {
        code in modelRejectedCodes -> MODEL_REJECTED_ERROR
        code == "usage_window" || code in rateLimitCodes && resetsAt - nowMs >= 120_000 -> LIMIT_REACHED_ERROR
        layer == "billing" -> OUT_OF_CREDITS_ERROR
        code in busyCodes -> MODEL_BUSY_ERROR
        code in tooLongCodes -> CHAT_TOO_LONG_ERROR
        code in timeoutCodes -> MODEL_TIMED_OUT_ERROR
        layer == "auth" -> MODEL_SIGN_IN_ERROR
        layer == "streaming" || code == "stream_drop" -> MODEL_CONNECTION_ERROR
        layer == "disk" || code == "disk_full" -> HERMES_DISK_FULL_ERROR
        // A failure in Hermes itself (its gateway layer) is not the model's: the generic line, and trying again can help.
        surface != null && layer != "gateway" && surface.has("retryable") && !surface.optBoolean("retryable") -> MODEL_NO_ANSWER_ERROR
        else -> apiFailureText(event.optInt("code", 500), event.string("message", event.string("error")))
    }
    if (surface == null) return phone
    return TurnLine(phone, retry = surface.optBoolean("retryable"), resetsAtMs = resetsAt, hermes = hermesOwnLine(surface.optString("message")).orEmpty()).encode()
}

/** A failure line's own fixed sentence, which a whole-string translation finds (the snackbar, a side question). */
internal fun turnErrorHeadline(line: String): String = TurnLine.decode(line).phone

/** Whether the turn that ended with [line] may be followed at once: Hermes said trying again can help and no reset lies ahead. */
internal fun retryCanHelp(line: String, nowMs: Long = System.currentTimeMillis()): Boolean = TurnLine.decode(line).let { it.retry && it.resetsAtMs <= nowMs }

/**
 * A failure line as the owner reads it now: the phone's sentence in their language (Hermes' own English sentence only in an English UI), then when the limit resets in the
 * translated "Resets at {0}." with the phone's own clock ([clock], its 12/24-hour setting), only while that time is still ahead.
 */
internal fun localizedTurnLine(line: String, translate: (String) -> String, clock: (Long) -> String, english: Boolean, nowMs: Long = System.currentTimeMillis()): String {
    val turn = TurnLine.decode(line)
    val head = if (english && turn.hermes.isNotBlank()) turn.hermes else translate(turn.phone)
    val limit = turn.phone == LIMIT_REACHED_ERROR || english && turn.hermes.isNotBlank()
    if (!limit || turn.resetsAtMs <= nowMs || english && turn.hermes.contains("reset", ignoreCase = true)) return head
    return head + " " + translate(LIMIT_RESETS).replace("{0}", clock(turn.resetsAtMs))
}

/** UI presentation only: transport and model-facing tool results retain their original details and types. */
internal fun apiFailureText(status: Int, detail: String): String {
    nativeErrorCodeText(detail)?.let { return it }
    if (detail in publicErrorText) return detail
    return when (status) {
        401 -> "Sign in with Hermes again, then try again."
        403 -> if (detail.equals("Owner not authorized", true)) "Finish setup on your Hermes dashboard: open the Beacon tab."
            else if (detail.equals("Plugin not authorized", true)) PROFILE_TURN_ON_FAILED_ERROR else "Hermes didn't allow that."
        404, 4007 -> "This item is no longer available. Refresh and try again."
        409 -> "This item changed. Refresh and try again."
        413 -> "This file is too large. Choose a smaller file."
        400, 422 -> "Hermes couldn't use those details. Check them and try again."
        429 -> "Hermes is busy. Wait a moment and try again."
        502, 503, 504 -> "Hermes is reconnecting. Try again in a moment."
        else -> APP_OPERATION_ERROR
    }
}

/** The local store is full (ProjectionDb refuses with this line): the owner's own words, not "Hermes couldn't do that" (REVIEW-FIXCHAT-121 P3). */
internal const val DRAFT_STORE_FULL_ERROR = "Phone storage for drafts is full. Send or clear a draft."
/** A save failed because the phone's store is full (REVIEW-FIXCHAT-121 R2 #9): its catch says so, since "try again" would fail the same way. */
internal fun draftStoreFull(error: Throwable) = appErrorText(error) == DRAFT_STORE_FULL_ERROR

private val publicErrorText = setOf(
    ATTACHMENT_TOO_LARGE_ROW,
    DRAFT_STORE_FULL_ERROR,
    "This chat changed. Refresh and try again.",
    "This chat is busy with a reply. Try again when it finishes.",
    "Hermes didn't allow that.",
    "This phone was removed on Hermes. Sign in again in Settings › Connection.",
    "That chat belongs to another profile.",
    "Hermes is restarting. Try again in a moment.",
    "Open your assistant's chat first.",
    "This command works only on a computer.",
    "Hermes couldn't do that. Try again.",
    "Reconnect before changing this chat.",
    "This file can't be edited here.",
    "Stop the reply before deleting this chat.",
    LOST_REPLY_ERROR,
    LOST_TASK_ERROR,
    DELETE_KEPT_WAITING_ERROR,
    LOST_REPLY_HELD_ERROR,
    "End the call before changing this chat.",
) + listOf("pin", "archive or restore", "rename", "branch", "load or change the model for", "load or change reasoning for", "check or change approvals for", "load the defaults for", "load toolsets for", "load the history for")
    .map { "Couldn't $it this chat. Refresh and try again." }

/** Every fixed sentence this file returns, for the translation test: one answer per HTTP error status covers each branch of [apiFailureText]. */
internal val ErrorStrings: List<String>
    get() = (publicErrorText + listOf(APP_CONNECTION_ERROR, THREAD_UNSUPPORTED_ERROR, APP_OPERATION_ERROR, MODEL_REJECTED_ERROR, LIMIT_REACHED_ERROR, LIMIT_RESETS, MODEL_BUSY_ERROR,
        CHAT_TOO_LONG_ERROR, MODEL_TIMED_OUT_ERROR, MODEL_NO_ANSWER_ERROR, MODEL_SIGN_IN_ERROR, MODEL_CONNECTION_ERROR, HERMES_DISK_FULL_ERROR, OUT_OF_CREDITS_ERROR, LOOPBACK_RESTARTED_ERROR, SIGN_IN_EXPIRED_ERROR, apiFailureText(403, "Owner not authorized"), PROFILE_PLUGIN_OFF_ERROR, PROFILE_TURN_ON_FAILED_ERROR,
        PROFILE_GONE_ERROR, dev.hermes.android.ACCESS_DENIED_RETAINED, OPEN_CHAT_NOTICE_RETRY, OPEN_CHAT_NOTICE_TURN_ON, ATTACHMENT_REFUSED_LINE, ATTACHMENT_TOO_LARGE_LINE) +
        (400..599).map { apiFailureText(it, "") }).distinct()

/** The wizard's sign-in: its own words (the tab was closed, it timed out, Hermes declined) instead of the connection error every IOException reads as. */
internal fun signInErrorText(error: Throwable): String = (error as? NativeAuthException)?.message?.takeIf(String::isNotBlank) ?: appErrorText(error)
/**
 * Hermes is up and only busy (REVIEW-121B FU-P3-2): a 1.2.1 plugin refused the canonical socket with 429 after waiting for a worker, or its
 * gateway ticket answered "Canonical gateway busy". Not "Can't reach Hermes" or "Hermes is reconnecting".
 */
internal fun nativeGatewayBusy(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    it is NativeAdmissionRefused && it.status == 429 || it is ApiFailure && it.plugin && it.status == 503 && it.detail == "Canonical gateway busy"
}

internal fun appErrorText(error: Throwable): String = if (nativeGatewayBusy(error)) apiFailureText(429, "") else when (error) {
    is BeaconOffFailure -> beaconOffLine(error)
    is ApiFailure -> apiFailureText(error.status, error.detail)
    is LoopbackSessionRefreshFailed -> LOOPBACK_RESTARTED_ERROR
    is NativeAuthExpiredException -> SIGN_IN_EXPIRED_ERROR
    is DeviceRemovedFailure -> "This phone was removed on Hermes. Sign in again in Settings › Connection."
    is PairingFailure -> safeLocalErrorText(error.message.orEmpty())
    is NativeThreadUnsupported -> THREAD_UNSUPPORTED_ERROR
    is java.io.IOException -> APP_CONNECTION_ERROR
    // The call service already wrote this line for the owner, in their language.
    is dev.hermes.android.voice.CallStartRefused -> error.message.orEmpty()
    else -> safeLocalErrorText(error.message.orEmpty())
}

private fun safeLocalErrorText(message: String): String {
    if (message.length > 240 || Regex("(?i)([a-z]:[\\\\/]|https?://|[/\\\\]|[\\r\\n]|\\[errno|exception|expecting value|json|native|traceback)").containsMatchIn(message)) return APP_OPERATION_ERROR
    return if (message in publicErrorText || listOf("Enter ", "Choose ", "Use ", "Add ", "Keep ", "Finish ", "Sign in ", "Reconnect ", "Refresh ", "This file changed", "The phone editor supports", "File preview is", "Saved file preview is", "Attachment exceeds", "Download exceeds", "Attachments are too large", "This file is too large", "This file can't be edited", "Only part of this file", "Reopen or download", "The time zone changed", "That code ", "Too many attempts.").any(message::startsWith)) message else APP_OPERATION_ERROR
}

/** Hermes answers some refusals with a bare machine code; the owner sees plain words instead. Unknown codes stay generic. */
internal fun nativeErrorCodeText(detail: String): String? = when (detail) {
    "mutation_identity_required", "revision_conflict", "stale_generation", "retained_receipts" -> "This chat changed. Refresh and try again."
    "session_busy", "admission_conflict", "runtime_coordination_required" -> "This chat is busy with a reply. Try again when it finishes."
    "permission_denied" -> "Hermes didn't allow that."
    "device_removed" -> "This phone was removed on Hermes. Sign in again in Settings › Connection."
    "profile_mismatch" -> "That chat belongs to another profile."
    "runtime_draining" -> "Hermes is restarting. Try again in a moment."
    // A turn Hermes lost across its restart holds the chat (hermes_state_runtime.claim_session_input, the delete guard).
    "unknown_execution" -> LOST_REPLY_HELD_ERROR
    "Choose an owned assistant conversation before enabling Proactive" -> "Open your assistant's chat first."
    // A shared gateway's refusal of a command it does not run for this chat (gateway/session_commands.py:456-458).
    "unsupported_command" -> "This command works only on a computer."
    else -> if (Regex("[a-z]+(?:_[a-z]+)+").matches(detail)) "Hermes couldn't do that. Try again." else null
}

internal fun nativeErrorMessage(result: JSONObject, status: Int): String {
    if (status in setOf(502, 504) || status == 503 && !result.has("detail") && !result.has("error")) return "Hermes is busy. Try again in a moment; your draft is kept."
    val detail = result.opt("detail")
    return when (detail) {
        is String -> nativeErrorCodeText(detail) ?: detail
        is org.json.JSONArray -> (0 until detail.length()).mapNotNull { detail.optJSONObject(it)?.optString("msg")?.takeIf(String::isNotBlank) }.joinToString("; ")
        else -> result.optString("error").ifBlank { result.optString("message") }
    }
}
