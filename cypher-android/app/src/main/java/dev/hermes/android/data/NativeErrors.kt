package dev.hermes.android.data

import org.json.JSONObject

internal fun missingNativeConversation(error: Throwable): Boolean = error is ApiFailure &&
    (error.status == 404 || (error.status == 4007 && error.message == "session not found"))

internal fun nativeErrorMessage(result: JSONObject, status: Int): String {
    if (status in setOf(502, 504) || status == 503 && !result.has("detail") && !result.has("error")) return "Hermes is reconnecting or temporarily busy. Retry in a moment; your draft is kept."
    val detail = result.opt("detail")
    return when (detail) {
        is String -> detail
        is org.json.JSONArray -> (0 until detail.length()).mapNotNull { detail.optJSONObject(it)?.optString("msg")?.takeIf(String::isNotBlank) }.joinToString("; ")
        else -> result.optString("error").ifBlank { result.optString("message").ifBlank { "Server returned $status" } }
    }.ifBlank { "Server returned $status" }
}
