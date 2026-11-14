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

/** Hermes refused the Beacon plugin for this profile (its own config does not list it): nothing was sent, and trying again cannot help until it is turned on. */
internal const val PROFILE_PLUGIN_OFF_ERROR = "Beacon isn't turned on for this profile."
internal fun beaconOffForProfile(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(8).any {
    it is ApiFailure && it.plugin && it.status == 403 && it.detail.equals("Plugin not authorized", ignoreCase = true)
}

/** The line over the command to run when the plugin cannot turn Beacon on from the phone: its route is missing, Beacon needs installing or updating there, or Hermes could not turn it on. */
internal const val PROFILE_PLUGIN_COMMAND_HEAD = "Couldn't turn Beacon on from here. Run this on your Hermes computer:"
/** No release commit is known, so no pinned command can be shown: the plugin is updated first, then the card looks again. */
internal const val PROFILE_UPDATE_FIRST_ERROR = "Update Beacon on Hermes first, then tap Check again."
/** Hermes was busy, updating or slow while turning Beacon on, and may have finished: look again, no command to run. */
internal const val PROFILE_TURN_ON_BUSY_ERROR = "Hermes is still working. Tap Check again in a moment."
/** Hermes no longer has the profile the card was opened for (the plugin answers 422): turning Beacon on cannot help and there is nothing to run. */
internal const val PROFILE_GONE_ERROR = "Hermes no longer has this profile."
/** The Attach notice on a first chat that has not opened: it names the button the card really shows. */
internal const val OPEN_CHAT_NOTICE_RETRY = "Tap Retry to open the chat. Your draft is kept."
internal const val OPEN_CHAT_NOTICE_TURN_ON = "Tap Turn on to open the chat. Your draft is kept."
internal const val OPEN_CHAT_NOTICE_CHECK_AGAIN = "Tap Check again to open the chat. Your draft is kept."
private val HERMES_PROFILE_ID = Regex("[a-z0-9][a-z0-9_-]{0,63}")
/**
 * Hermes' own install-and-enable for one profile, pinned like every Beacon install (hermes-plugin `beacon_install.py`: `install SOURCE --force --ref <commit> --enable`): without
 * `--ref` Hermes clones the branch tip and a later `plugins update` follows the branch. Null for anything that is not a Hermes profile id, and for anything that is not the full
 * 40-hex commit of the latest release ([commit]); with no commit there is no command to show.
 */
internal fun profileInstallCommand(profile: String, commit: String?): String? =
    if (commit != null && dev.hermes.android.updates.COMMIT_SHA.matches(commit) && HERMES_PROFILE_ID.matches(profile))
        "hermes -p $profile plugins install ${dev.hermes.android.updates.BEACON_PLUGIN_SOURCE} --force --ref $commit --enable" else null
internal fun profileTurnOnFailedText(profile: String, commit: String?): String = when {
    !HERMES_PROFILE_ID.matches(profile) -> APP_OPERATION_ERROR
    else -> profileInstallCommand(profile, commit)?.let { "$PROFILE_PLUGIN_COMMAND_HEAD\n$it" } ?: PROFILE_UPDATE_FIRST_ERROR
}
/**
 * What the card says when the plugin's turn-on route failed ([failure]). Each status of `admin_api.py` means one thing. 404 = only the route is missing (an older plugin), 501 =
 * Beacon is not installed in the dashboard profile or needs an update, 500 = Hermes could not turn Beacon on: the exact command to run (or, with no release known, to update the
 * plugin first). 409 = another change is running, 503 = an update is in progress, 504 = slow: Hermes is still working and may have finished, so nothing to run, only Check again.
 * 422 = Hermes no longer has this profile: one plain line. Anything else (403 not authorized included) gets Hermes' own plain line.
 */
internal fun profileTurnOnFailure(profile: String, commit: String?, failure: Throwable): String = when {
    failure is ApiFailure && failure.status in setOf(404, 500, 501) -> profileTurnOnFailedText(profile, commit)
    failure is ApiFailure && failure.status in setOf(409, 503, 504) -> PROFILE_TURN_ON_BUSY_ERROR
    failure is ApiFailure && failure.status == 422 -> PROFILE_GONE_ERROR
    else -> appErrorText(failure)
}
/** The command inside a failure text from [profileTurnOnFailedText], for the Copy button; null for any other text. */
internal fun profileTurnOnCommand(text: String): String? = text.takeIf { it.startsWith("$PROFILE_PLUGIN_COMMAND_HEAD\n") }?.substringAfter('\n')

internal const val APP_CONNECTION_ERROR = "Can't reach Hermes. Check your connection, then try again."
internal const val THREAD_UNSUPPORTED_ERROR = "Threads aren't available on this Hermes yet."
/** A thread create the connected Hermes cannot take. Refused before anything is sent, and never reported as an unreachable Hermes. */
internal class NativeThreadUnsupported : java.io.IOException("Native authority cannot create this thread type")
/** No open server request of this id is held on the connection: Hermes ended it (or never sent it here). Still an IOException for every caller that treats it as one. */
internal class NativeRequestEnded : java.io.IOException("Native server request is no longer active")
private const val APP_OPERATION_ERROR = "Hermes couldn't do that. Try again."
private const val MODEL_REJECTED_ERROR = "The model rejected this request. Try again or pick another model."
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

/** The line for a failed turn. Hermes' `error_surface.code` says the provider turned the chosen model down; anything else keeps the generic text. */
internal fun turnErrorText(event: JSONObject): String =
    if (event.optJSONObject("error_surface")?.optString("code") in modelRejectedCodes) MODEL_REJECTED_ERROR
    else apiFailureText(event.optInt("code", 500), event.string("message", event.string("error")))

/** UI presentation only: transport and model-facing tool results retain their original details and types. */
internal fun apiFailureText(status: Int, detail: String): String {
    nativeErrorCodeText(detail)?.let { return it }
    if (detail in publicErrorText) return detail
    return when (status) {
        401 -> "Sign in with Hermes again, then try again."
        403 -> if (detail.equals("Owner not authorized", true)) "Finish setup on your Hermes dashboard: open the Beacon tab."
            else if (detail.equals("Plugin not authorized", true)) PROFILE_PLUGIN_OFF_ERROR else "Hermes didn't allow that."
        404, 4007 -> "This item is no longer available. Refresh and try again."
        409 -> "This item changed. Refresh and try again."
        413 -> "This file is too large. Choose a smaller file."
        400, 422 -> "Hermes couldn't use those details. Check them and try again."
        429 -> "Hermes is busy. Wait a moment and try again."
        502, 503, 504 -> "Hermes is reconnecting. Try again in a moment."
        else -> APP_OPERATION_ERROR
    }
}

private val publicErrorText = setOf(
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
    "End the call before changing this chat.",
) + listOf("pin", "archive or restore", "rename", "branch", "load or change the model for", "load or change reasoning for", "check or change approvals for", "load the defaults for", "load toolsets for", "load the history for")
    .map { "Couldn't $it this chat. Refresh and try again." }

/** Every fixed sentence this file returns, for the translation test: one answer per HTTP error status covers each branch of [apiFailureText]. */
internal val ErrorStrings: List<String>
    get() = (publicErrorText + listOf(APP_CONNECTION_ERROR, THREAD_UNSUPPORTED_ERROR, APP_OPERATION_ERROR, MODEL_REJECTED_ERROR, LOOPBACK_RESTARTED_ERROR, SIGN_IN_EXPIRED_ERROR, apiFailureText(403, "Owner not authorized"), PROFILE_PLUGIN_OFF_ERROR, PROFILE_PLUGIN_COMMAND_HEAD,
        PROFILE_UPDATE_FIRST_ERROR, PROFILE_TURN_ON_BUSY_ERROR, PROFILE_GONE_ERROR, dev.hermes.android.ACCESS_DENIED_RETAINED, OPEN_CHAT_NOTICE_RETRY, OPEN_CHAT_NOTICE_TURN_ON, OPEN_CHAT_NOTICE_CHECK_AGAIN) +
        (400..599).map { apiFailureText(it, "") }).distinct()

internal fun appErrorText(error: Throwable): String = when (error) {
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
