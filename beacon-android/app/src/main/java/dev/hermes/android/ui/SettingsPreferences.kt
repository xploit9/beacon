package dev.hermes.android.ui


import org.json.JSONObject
import dev.hermes.android.core.string

internal fun privateStructuredKey(key: String): Boolean {
    val normalized = key.replace("_", "").replace("-", "").lowercase(java.util.Locale.ROOT)
    return normalized.endsWith("token") ||
        listOf("password", "authorization", "bearer", "secret", "cookie", "apikey").any(normalized::contains)
}

internal val skinLabels = NativeSkins.associateWith { key -> if (key == "default") "Beacon" else if (key == "github") "GitHub" else key.split('-').joinToString(" ") { it.replaceFirstChar(Char::titlecase) } }

internal fun displayedBoolean(field: Field, stored: String) = if (field.inverse) !stored.toBoolean() else stored.toBoolean()
internal fun storedBoolean(field: Field, displayed: Boolean) = (if (field.inverse) !displayed else displayed).toString()
internal fun recordError(value: JSONObject?): String = when {
    value == null -> ""
    value.string("_error").isNotBlank() -> value.string("_error")
    value.has("error") && !value.isNull("error") && value.string("error").isNotBlank() -> value.string("error")
    value.has("success") && !value.optBoolean("success") -> value.string("message", "The server could not load this content. Try again.")
    else -> ""
}

internal fun validateSetting(field: Field, value: Any) {
    val bounds = when (field.key) {
        "tts_rate" -> .5 to 2.0
        "tts_pitch" -> 0.0 to 2.0
        "voice_silence_ms" -> 200.0 to 60000.0
        "pinned_sessions_limit" -> 1.0 to 99.0
        "inflight_state_max_sessions" -> 1.0 to 25.0
        "structured_code_auto_tree_lines" -> 1.0 to 1000.0
        else -> null
    }
    if (bounds != null) require(value is Number && value.toDouble() in bounds.first..bounds.second) {
        "${field.label} must be between ${bounds.first} and ${bounds.second}."
    }
    if (field.key == "max_tokens") require(value == JSONObject.NULL || value is Number && value.toLong() > 0) { "Max tokens must be positive, or blank for the server default." }
    if (field.key == "provider_cost_budget") require(value == JSONObject.NULL || value is Number && value.toDouble() >= .01 && value.toDouble() < 1e9) { "Provider budget must be at least 0.01 and less than 1,000,000,000, or blank." }
    if (field.key == "tts_voice") require(value.toString().length <= 200 && !value.toString().contains('\u0000')) { "Voice name must be at most 200 characters." }
}
