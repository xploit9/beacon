package dev.hermes.android.data

import org.json.JSONObject

internal data class RestoredEditorDraft(val text: String, val base: String, val start: Int, val end: Int, val reviewRequired: Boolean)

/** A recovered edit keeps the version it was based on; a fresh read cannot silently rebase it. */
internal fun restoreEditorDraft(saved: JSONObject?, current: String): RestoredEditorDraft {
    if (saved == null) return RestoredEditorDraft(current, current, current.length, current.length, false)
    require(saved.opt("text") is String) { "Saved draft is unreadable; it has been retained on this device" }
    val text = saved.getString("text")
    val base = saved.opt("base") as? String
    if (base != null) require(saved.optInt("version") == 1) { "Saved draft version is unsupported" }
    if (text == current || base != null && text == base && !saved.optBoolean("review_required")) {
        return RestoredEditorDraft(current, current, current.length, current.length, false)
    }
    val start = saved.optInt("start", text.length).coerceIn(0, text.length)
    val end = saved.optInt("end", start).coerceIn(0, text.length)
    return RestoredEditorDraft(text, base ?: current, start, end,
        saved.optBoolean("review_required") || base == null || base != current)
}

internal fun editorDraftSnapshot(text: String, base: String, start: Int, end: Int, reviewRequired: Boolean): JSONObject? =
    if (text == base && !reviewRequired) null else JSONObject().put("version", 1).put("text", text).put("base", base)
        .put("start", start.coerceIn(0, text.length)).put("end", end.coerceIn(0, text.length)).put("review_required", reviewRequired)
