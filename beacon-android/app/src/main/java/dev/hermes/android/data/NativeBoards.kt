package dev.hermes.android.data

import dev.hermes.android.core.*
import org.json.JSONObject

internal val boardStatuses = linkedMapOf("triage" to "Triage", "todo" to "To do", "scheduled" to "Scheduled", "ready" to "Ready", "running" to "Running", "blocked" to "Blocked", "review" to "Review", "done" to "Done", "archived" to "Archived")
internal data class NativeBoardChoice(val slug: String, val name: String)
internal data class NativeBoardCard(val id: String, val title: String, val body: String, val status: String, val assignee: String, val priority: Int, val summary: String = "") {
    fun json(): JSONObject = json("id" to id, "title" to title, "body" to body, "status" to status, "assignee" to assignee, "priority" to priority)
}
internal fun nativeBoardCard(row: JSONObject): NativeBoardCard {
    val id = row.getString("id")
    require(id.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid task identity" }
    val status = row.getString("status")
    require(status in boardStatuses) { "This task uses an unsupported status" }
    return NativeBoardCard(id, row.string("title"), row.string("body"), status, row.string("assignee"), row.optInt("priority"), row.string("latest_summary"))
}
internal fun nativeBoardCards(document: JSONObject): List<NativeBoardCard> = document.rows("columns").flatMap { column ->
    column.rows("tasks").map { row -> nativeBoardCard(row.copy().put("status", column.getString("name"))) }
}.distinctBy { it.id }

internal fun boardTaskFields(title: String, body: String, assignee: String): JSONObject {
    require(title.isNotBlank() && title.length <= 160) { "Enter a title of up to 160 characters" }
    require(body.length <= 16_000) { "Keep the description under 16,000 characters" }
    require(assignee.length <= 80 && assignee.none { it.isISOControl() }) { "Invalid bot profile" }
    // Hermes rejects an empty profile name, so an unassigned task leaves the field out.
    return json("title" to title.trim(), "body" to body).apply { assignee.trim().takeIf(String::isNotEmpty)?.let { put("assignee", it) } }
}
internal fun boardMoveFields(status: String): JSONObject {
    require(status in boardStatuses && status != "running") { "Hermes starts a task when its worker claims it" }
    return json("status" to status)
}

/** Same native Kanban records and workflow rules as the dashboard; no secondary board database. */
internal const val KANBAN_BOARDS_KEY = "kanban:boards"
internal fun kanbanBoardKey(board: String) = "kanban:board:$board"
internal fun nativeBoardChoices(result: JSONObject): Pair<List<NativeBoardChoice>, String> = result.rows("boards").map { row ->
    val slug = row.getString("slug"); require(validBoardSlug(slug)) { "Invalid board identity" }
    NativeBoardChoice(slug, row.string("name", slug))
} to result.string("current", "default")

/** [keep] receives each parsed read so the next open can start from it. */
internal class NativeBoards(private val client: BeaconClient, private val keep: (String, JSONObject) -> Unit = { _, _ -> }) {
    suspend fun choices(): Pair<List<NativeBoardChoice>, String> {
        val result = client.nativeGet("/api/plugins/kanban/boards", maxBytes = 1024 * 1024)
        return nativeBoardChoices(result).also { keep(KANBAN_BOARDS_KEY, result) }
    }
    private suspend fun request(board: String, path: String, method: String = "GET", data: JSONObject? = null): JSONObject {
        require(validBoardSlug(board)) { "Choose an available board" }
        return client.nativeGet("/api/plugins/kanban/$path", mapOf("board" to board), method, data, maxBytes = 8 * 1024 * 1024)
    }
    suspend fun read(board: String): List<NativeBoardCard> {
        val document = request(board, "board")
        return nativeBoardCards(document).also { keep(kanbanBoardKey(board), document) }
    }
    suspend fun detail(board: String, id: String): NativeBoardCard = nativeBoardCard(request(board, taskPath(id)).getJSONObject("task"))
    suspend fun create(board: String, fields: JSONObject, key: String): NativeBoardCard {
        require(key.matches(Regex("[a-f0-9-]{36}"))) { "Invalid saved draft identity" }
        return nativeBoardCard(request(board, "tasks", "POST", fields.copy().put("idempotency_key", "beacon-$key")).getJSONObject("task"))
    }
    suspend fun update(board: String, id: String, fields: JSONObject): NativeBoardCard = nativeBoardCard(request(board, taskPath(id), "PATCH", fields).getJSONObject("task"))
    suspend fun delete(board: String, id: String) { require(request(board, taskPath(id), "DELETE").optBoolean("deleted")) { "Deletion was not confirmed" } }
    private fun taskPath(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]{1,160}"))); return "tasks/$id" }
}
internal fun validBoardSlug(slug: String): Boolean = slug.matches(Regex("[a-z0-9][a-z0-9_-]{0,79}"))
