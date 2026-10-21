package dev.hermes.android.data

import org.json.JSONObject

internal fun missingNativeConversation(error: Throwable): Boolean = error is ApiFailure &&
    (error.status == 404 || (error.status == 4007 && error.message == "session not found"))

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
