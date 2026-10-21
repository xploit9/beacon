package dev.hermes.android.voice

import org.json.JSONObject

internal fun CallState.withServerContextNotice(event: JSONObject, authorizedCallId: String): CallState {
    if (!active || callId.isBlank() || callId != authorizedCallId || event.opt("type") != "context_notice") return this
    if (event.has("call_id") && (event.opt("call_id") !is String || event.opt("call_id") != callId)) return this
    val phase = event.opt("phase") as? String ?: return this
    val message = event.opt("message") as? String ?: return this
    if (message.length > 500 || phase !in setOf("approaching_limit", "clear") || phase == "approaching_limit" && (held || message.isBlank())) return this
    val notice = if (phase == "clear") "" else message
    return if (notice == contextNotice) this else copy(contextNotice=notice)
}
