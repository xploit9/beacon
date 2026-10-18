package dev.hermes.android.core

import org.json.JSONObject
import org.json.JSONArray

// Timestamp normalization follows Hermes Relay's FlexibleTimestampSerializer and
// ChatHandler history projection (MIT; see licenses/Hermes-Relay-MIT.txt). Missing/invalid times stay unknown.
internal fun recordedMessageTime(value: Any?): Long {
    val raw = when (value) {
        is Number -> value.toString()
        is String -> value.trim()
        else -> return 0L
    }
    val numeric = raw.toDoubleOrNull()
    if (numeric != null) {
        val millis = if (numeric < 100_000_000_000.0) numeric * 1000.0 else numeric
        return if (millis.isFinite() && millis > 0 && millis < Long.MAX_VALUE.toDouble()) millis.toLong() else 0L
    }
    return runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()?.takeIf { it > 0 } ?: 0L
}

data class ChatPresentation(val activity: Boolean, val text: String, val summary: String)

data class LiveAssistantBody(val text: String, val reasoning: String)
fun interimAssistantBody(text: String, reasoning: String, payload: JSONObject): LiveAssistantBody {
    val visible = payload.string("text", text)
    return LiveAssistantBody(if (payload.optBoolean("already_streamed")) text else visible,
        if (payload.optBoolean("reasoning_echo")) removeCurrentReasoningEcho(reasoning, visible) else reasoning)
}

private fun removeCurrentReasoningEcho(reasoning: String, echo: String): String {
    if (echo.isBlank()) return reasoning
    var r = reasoning.lastIndex
    var e = echo.lastIndex
    while (e >= 0) {
        while (e >= 0 && echo[e].isWhitespace()) e--
        while (r >= 0 && reasoning[r].isWhitespace()) r--
        if (e < 0) break
        if (r < 0 || reasoning[r] != echo[e]) return reasoning
        r--; e--
    }
    return reasoning.substring(0, r + 1).trimEnd()
}

private fun boundedBoundaryIds(ids: Iterable<String>): Set<String> = ids.filter { it.length in 1..512 }.takeLast(256).toSet()

data class LiveAssistantBoundary(val closed: Boolean = false, val toolIds: Set<String> = emptySet()) {
    fun after(event: String, payload: JSONObject = JSONObject()): LiveAssistantBoundary = when (event) {
        "token", "reasoning" -> LiveAssistantBoundary()
        "interim_assistant" -> if (!payload.optBoolean("already_streamed") && payload.string("text").isNotBlank()) LiveAssistantBoundary(closed = true) else copy(closed = true)
        "tool" -> copy(closed = true, toolIds = boundedBoundaryIds(toolIds + toolIdentity(payload)))
        else -> this
    }
    fun saveInto(frame: JSONObject): JSONObject = frame.put("boundary_closed", closed).put("boundary_tool_ids", JSONArray(toolIds.toList()))
    companion object {
        fun from(frame: JSONObject): LiveAssistantBoundary {
            val closed = frame.optBoolean("boundary_closed")
            val values = frame.optJSONArray("boundary_tool_ids")
            val ids = if (values != null) (0 until values.length()).mapNotNull { values.opt(it) as? String }
                else if (closed) frame.rows("tools").map(::toolIdentity) else emptyList()
            return LiveAssistantBoundary(closed, boundedBoundaryIds(ids))
        }
    }
}

fun messageContentText(role: String, content: Any?): String = when (content) {
    is String -> content
    is JSONArray -> content.objects().mapNotNull { it.string("text").takeIf(String::isNotEmpty) }.joinToString("\n")
    is JSONObject -> if (role in setOf("tool", "function")) content.toString() else ""
    else -> ""
}

fun chatPresentation(role: String, text: String, raw: JSONObject = JSONObject(), tools: List<JSONObject> = emptyList()): ChatPresentation {
    val activity = role in setOf("tool", "function")
    val name = raw.string("name", raw.optJSONObject("function")?.string("name").orEmpty())
    return ChatPresentation(activity, text, if (activity) name.ifBlank { "Tool result" } else if (tools.isNotEmpty() && text.isBlank()) "Tool activity" else "")
}

fun toolIdentity(tool: JSONObject): String = sequenceOf("tid", "id", "tool_call_id").map { tool.string(it) }.firstOrNull { it.isNotBlank() }.orEmpty()

fun mergeLiveTool(tools: List<JSONObject>, tool: JSONObject): List<JSONObject> {
    val id = toolIdentity(tool)
    if (id.isBlank()) return tools + tool
    val merged = tools.firstOrNull { toolIdentity(it) == id }?.copy() ?: JSONObject()
    tool.keys().forEach { merged.put(it, tool.opt(it)) }
    return tools.filterNot { toolIdentity(it) == id } + merged
}

fun historyToolIdentities(messages: List<JSONObject>): Set<String> = messages.flatMap { message ->
    message.rows("tool_calls").map(::toolIdentity) + message.string("tool_call_id")
}.filter(String::isNotBlank).toSet()

// A tool boundary is the server's message boundary; repeated prose is never identity.
fun historyOwnsLiveBoundary(messages: List<JSONObject>, tools: List<JSONObject>, boundaryClosed: Boolean, sealedToolIds: Set<String>? = null): Boolean {
    val history = historyToolIdentities(messages)
    return boundaryClosed && (sealedToolIds ?: tools.map(::toolIdentity).filter(String::isNotBlank).toSet()).any { it in history }
}

fun currentTurnMessages(messages: List<JSONObject>): List<JSONObject> = messages.drop(messages.indexOfLast { it.string("role") == "user" }.coerceAtLeast(0))
