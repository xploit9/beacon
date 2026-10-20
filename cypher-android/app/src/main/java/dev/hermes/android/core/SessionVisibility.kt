package dev.hermes.android.core

import org.json.JSONObject

fun sessionRowId(row: JSONObject): String = row.string("session_id", row.string("id"))
/** A row without a profile (an older cached index) belongs to [profile], so it matches its refreshed, profile-tagged copy. */
fun sessionRowKey(row: JSONObject, profile: String = ""): String = "${row.string("profile", profile)}:${sessionRowId(row)}"

fun visibleSessionRows(rows: List<JSONObject>, confirmed: List<JSONObject>, query: String, archived: Boolean, profile: String = ""): List<JSONObject> {
    if (archived || query.isNotBlank()) return rows
    val ids = confirmed.map { sessionRowKey(it, profile) }.toSet()
    return confirmed + rows.filterNot { sessionRowKey(it, profile) in ids }
}

fun sessionIndexCurrent(request: Long, latest: Long, revision: Long, currentRevision: Long): Boolean = request == latest && revision == currentRevision
