package dev.hermes.android.data

import org.json.JSONObject

internal fun missingNativeConversation(error: Throwable): Boolean = error is ApiFailure &&
    (error.status == 404 || (error.status == 4007 && error.detail == "session not found"))

internal const val APP_CONNECTION_ERROR = "Can't reach Hermes. Check your connection, then try again."
private const val APP_OPERATION_ERROR = "Hermes couldn't do that. Try again."

/** UI presentation only: transport and model-facing tool results retain their original details and types. */
internal fun apiFailureText(status: Int, detail: String): String {
    nativeErrorCodeText(detail)?.let { return it }
    if (detail in publicErrorText) return detail
    return when (status) {
        401 -> "Sign in with Hermes again, then try again."
        403 -> if (detail.equals("Owner not authorized", true)) "Finish setup on your Hermes dashboard: open the Beacon tab." else "Hermes didn't allow that."
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
    "This phone was removed from Beacon on your PC. Pair it again in Settings › Devices.",
    "That chat belongs to another profile.",
    "Hermes is restarting. Try again in a moment.",
    "Choose an owned assistant conversation before enabling Proactive",
    "Hermes couldn't do that. Try again.",
    "Reconnect before changing this chat.",
    "This file can't be edited here.",
    "Stop the current reply before deleting this conversation.",
    "End this call before changing its conversation.",
) + listOf("pin", "archive or restore", "rename", "branch", "load or change the model for", "load or change reasoning for", "check or change approvals for", "load the defaults for", "load toolsets for", "load the history for")
    .map { "Couldn't $it this chat. Refresh and try again." }

internal fun appErrorText(error: Throwable): String = when (error) {
    is ApiFailure -> apiFailureText(error.status, error.detail)
    is LoopbackSessionRefreshFailed -> "Hermes restarted. Scan the Connect QR again."
    is NativeAuthExpiredException -> "Sign-in expired. Sign in again."
    is DeviceRemovedFailure -> "This phone was removed from Beacon on your PC. Pair it again in Settings › Devices."
    is PairingFailure -> safeLocalErrorText(error.message.orEmpty())
    is java.io.IOException -> APP_CONNECTION_ERROR
    else -> safeLocalErrorText(error.message.orEmpty())
}

private fun safeLocalErrorText(message: String): String {
    if (message.length > 240 || Regex("(?i)([a-z]:[\\\\/]|https?://|[/\\\\]|[\\r\\n]|\\[errno|exception|expecting value|json|native|traceback)").containsMatchIn(message)) return APP_OPERATION_ERROR
    return if (message in publicErrorText || listOf("Enter ", "Choose ", "Use ", "Add ", "Keep ", "Pair ", "Finish ", "Sign in ", "Reconnect ", "Refresh ", "This file changed", "The phone editor supports", "File preview is", "Saved file preview is", "Attachment exceeds", "Download exceeds", "That code ", "Too many attempts.").any(message::startsWith)) message else APP_OPERATION_ERROR
}

/** Hermes answers some refusals with a bare machine code; the owner sees plain words instead. Unknown codes stay generic. */
internal fun nativeErrorCodeText(detail: String): String? = when (detail) {
    "mutation_identity_required", "revision_conflict", "stale_generation", "retained_receipts" -> "This chat changed. Refresh and try again."
    "session_busy", "admission_conflict", "runtime_coordination_required" -> "This chat is busy with a reply. Try again when it finishes."
    "permission_denied" -> "Hermes didn't allow that."
    "device_removed" -> "This phone was removed from Beacon on your PC. Pair it again in Settings › Devices."
    "profile_mismatch" -> "That chat belongs to another profile."
    "runtime_draining" -> "Hermes is restarting. Try again in a moment."
    else -> if (Regex("[a-z]+(?:_[a-z]+)+").matches(detail)) "Hermes couldn't do that. Try again." else null
}

internal fun nativeErrorMessage(result: JSONObject, status: Int): String {
    if (status in setOf(502, 504) || status == 503 && !result.has("detail") && !result.has("error")) return "Hermes is reconnecting or temporarily busy. Retry in a moment; your draft is kept."
    val detail = result.opt("detail")
    return when (detail) {
        is String -> nativeErrorCodeText(detail) ?: detail
        is org.json.JSONArray -> (0 until detail.length()).mapNotNull { detail.optJSONObject(it)?.optString("msg")?.takeIf(String::isNotBlank) }.joinToString("; ")
        else -> result.optString("error").ifBlank { result.optString("message").ifBlank { "Server returned $status" } }
    }.ifBlank { "Server returned $status" }
}
