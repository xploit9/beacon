package dev.hermes.android.core

import org.json.JSONObject

fun sessionRowId(row: JSONObject): String = row.string("session_id", row.string("id"))

fun visibleSessionRows(rows: List<JSONObject>, confirmed: List<JSONObject>, query: String, archived: Boolean): List<JSONObject> {
    if (archived || query.isNotBlank()) return rows
    val ids = confirmed.map(::sessionRowId).toSet()
    return confirmed + rows.filterNot { sessionRowId(it) in ids }
}

fun sessionIndexCurrent(request: Long, latest: Long, revision: Long, currentRevision: Long): Boolean = request == latest && revision == currentRevision
