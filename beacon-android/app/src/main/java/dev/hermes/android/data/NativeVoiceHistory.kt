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
    val archivedIds = archived.map { it.eventId }.toSet()
    for (event in archived + live) {
        // History uses call/role/turn; a live provider item keeps its own ID in that archive slot.
        val archiveId = if (event.turnId.isBlank()) event.eventId else "${event.eventId.substringBeforeLast(':')}:${event.turnId}"
        val slot = merged[archiveId]
        val key = when {
            event.eventId in merged -> event.eventId
            archiveId in archivedIds && (slot?.eventId == archiveId || slot?.eventId == event.eventId) -> archiveId
            else -> event.eventId
        }
        val previous = merged[key]
        if (previous?.final == true && !event.final) continue
        val saved = previous?.takeIf { key in archivedIds }
        val timed = event.copy(at = saved?.at?.takeIf { it > 0 } ?: event.at.takeIf { it > 0 } ?: previous?.at ?: 0,
            turnId = saved?.turnId?.takeIf { it.isNotBlank() } ?: event.turnId)
        merged[key] = if (timed.interrupted && !timed.final) timed.copy(text = "", final = true) else timed
    }
    return merged.filter { (key, event) -> event.text.isNotBlank() && key !in messageIds && event.eventId !in messageIds && (event.messageId.isBlank() || event.messageId !in messageIds) }.values.toList()
}
