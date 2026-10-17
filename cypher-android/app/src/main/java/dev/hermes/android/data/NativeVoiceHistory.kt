package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.voice.CallTranscript
import org.json.JSONObject

internal fun archivedVoiceTranscripts(scope: Scope, archive: JSONObject): List<CallTranscript> {
    require(archive.string("session_id") == scope.session && archive.string("profile") == scope.profile) { "Voice history belongs to another native scope" }
    return archive.rows("events").mapNotNull { row ->
        require(row.string("session_id") == scope.session) { "Voice history row belongs to another session" }
        CallTranscript.from(row, scope.session, row.string("call_id"))
    }
}

internal fun mergeVoiceTranscripts(archived: List<CallTranscript>, live: List<CallTranscript>, messageIds: Set<String>): List<CallTranscript> {
    val merged = linkedMapOf<String, CallTranscript>()
    for (event in archived + live) {
        val previous = merged[event.eventId]
        if (previous?.final == true && !event.final) continue
        merged[event.eventId] = if (event.interrupted && !event.final) event.copy(text = "", final = true) else event
    }
    return merged.values.filter { it.text.isNotBlank() && it.eventId !in messageIds && (it.messageId.isBlank() || it.messageId !in messageIds) }
}
