package dev.hermes.android.data

import dev.hermes.android.core.*
import org.json.JSONObject

/** Native project records own membership and explicit exclusions; folders remain native. */
internal class NativeProjects(private val client: BeaconClient) {
    private suspend fun folder(path: String): String {
        require(path.isNotBlank() && path.length <= 4096 && path.none { it.isISOControl() }) { "Choose an existing PC folder" }
        return client.nativeGet("/api/files", mapOf("path" to path), maxBytes = 4 * 1024 * 1024).getString("path")
    }
    /** The project that owns the chat's folder, as Hermes files it (projects.for_cwd); null when none does. */
    private suspend fun projectOf(stored: String): JSONObject? {
        require(stored.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}"))) { "Choose a conversation" }
        val cwd = client.nativeGet("/api/sessions/$stored", mapOf("profile" to client.auth.profile)).string("cwd")
        return if (cwd.isBlank()) null else client.nativeRpc("projects.for_cwd", json("cwd" to cwd)).optJSONObject("project")
    }
    suspend fun call(path: String, method: String, data: JSONObject): JSONObject {
        val id = data.string("id")
        fun identity(): JSONObject { require(id.matches(Regex("p_[A-Za-z0-9_-]{1,100}"))) { "Choose a saved project" }; return json("id" to id) }
        fun description(): String = data.string("description").also { require(it.length <= 2000) { "Keep the project description under 2,000 characters. Your draft is retained." } }
        fun name(): String = data.string("name").trim().also { require(it.isNotEmpty() && it.length <= 120 && it.none(Char::isISOControl)) { "Enter a project name of up to 120 characters" } }
        when (path) {
            "/api/projects" -> {
                require(method == "GET")
                return client.nativeRpc("projects.list")
            }
            "/api/projects/detail" -> {
                require(method == "GET")
                val project = client.nativeRpc("projects.get", identity()).getJSONObject("project")
                val tree = client.nativeRpc("projects.project_sessions", json("project_id" to id, "session_limit" to 200))
                return json("project" to project, "tree" to tree.optJSONObject("project"))
            }
            "/api/projects/create" -> {
                require(method == "POST")
                val request = data.string("request_id")
                require(runCatching { java.util.UUID.fromString(request).toString() == request }.getOrDefault(false)) { "Reopen New project before saving" }
                return client.nativeRequest("/api/plugins/beacon/mobile/projects", "POST", json("request_id" to request, "name" to name(), "description" to description()))
            }
            "/api/projects/update" -> {
                require(method == "POST")
                return client.nativeRpc("projects.update", identity().put("name", name()).put("description", description()))
            }
            "/api/projects/delete" -> {
                require(method == "POST")
                val result = client.nativeRpc("projects.delete", identity())
                require(nativeProjectRemovalConfirmed(result, id)) { "Project removal was not confirmed. Refresh before retrying." }
                return result
            }
            "/api/projects/folders/add", "/api/projects/folders/remove", "/api/projects/folders/primary" -> {
                require(method == "POST")
                val operation = path.substringAfterLast('/')
                val project = client.nativeRpc("projects.get", identity()).getJSONObject("project")
                val selected = data.string("path")
                val params = identity().put("path", if (operation == "add") folder(selected) else selected)
                if (operation != "add") require(project.rows("folders").any { it.string("path") == selected }) { "This folder is no longer linked. Refresh the project." }
                return client.nativeRpc("projects." + when (operation) { "add" -> "add_folder"; "remove" -> "remove_folder"; else -> "set_primary" }, params)
            }
            "/api/projects/for-session" -> {
                require(method == "GET")
                val stored = data.string("chat")
                val project = try { projectOf(stored) } catch (e: ApiFailure) { if (e.status != 404) throw e; null }
                return json("session_id" to stored, "project" to (project ?: JSONObject.NULL))
            }
            "/api/projects/detach" -> {
                require(method == "POST")
                val stored = data.string("session_id")
                require(stored.isNotBlank() && data.string("scope_key") == client.scope.session(stored).key) { "Reopen Projects in the original conversation" }
                require(projectOf(stored)?.string("id") == identity().string("id")) { "This chat is no longer in that project. Refresh and try again." }
                // Hermes files a chat under the project that owns its folder; moving it to the profile's own folder takes it out.
                val home = client.nativeRpc("projects.for_cwd")
                require(home.string("cwd").isNotBlank() && home.optJSONObject("project") == null) { "Hermes has no folder outside a project to move this chat to." }
                val moved = client.nativeRpc("session.workspace.move", json("session_key" to stored, "cwd" to home.string("cwd")))
                require(projectOf(stored)?.string("id") != id) { "Removing the chat from the project was not confirmed. Refresh before retrying." }
                return moved.put("session_id", stored).put("detached", true)
            }
            "/api/projects/assign" -> {
                require(method == "POST")
                val stored = data.string("session_id")
                require(stored.isNotBlank() && data.string("scope_key") == client.scope.session(stored).key) { "Reopen Projects in the original conversation" }
                var project = client.nativeRpc("projects.get", identity()).getJSONObject("project")
                require(!project.optBoolean("archived")) { "Choose an active project" }
                var selected = data.string("path").ifBlank { project.string("primary_path") }
                if (data.optBoolean("link") && selected.isNotBlank() && project.rows("folders").none { it.string("path") == selected }) {
                    // A project made on the phone starts without a folder: link the chosen one, then file the chat there.
                    val wanted = folder(selected)
                    // Hermes gives a folder two projects list to the older one, so a taken folder is refused before it is linked.
                    val holder = client.nativeRpc("projects.for_cwd", json("cwd" to wanted)).optJSONObject("project")
                    require(holder == null || holder.string("id") == id || holder.rows("folders").none { sameNativeFolder(it.string("path"), wanted) }) { "That folder already belongs to another project." }
                    client.nativeRpc("projects.add_folder", identity().put("path", wanted))
                    project = client.nativeRpc("projects.get", identity()).getJSONObject("project")
                    selected = linkedFolderPath(project, wanted) ?: wanted
                }
                require(selected.isNotBlank() && project.rows("folders").any { it.string("path") == selected }) { "Link a PC folder to this project first" }
                val target = folder(selected)
                val owning = client.nativeRpc("projects.for_cwd", json("cwd" to target)).optJSONObject("project")
                require(owning?.string("id") == id) { "That folder already belongs to another project." }
                val binding = client.nativeSession(stored)
                require(client.scope.session(binding.storedSessionId).key == data.string("scope_key")) { "Conversation changed. Reopen Projects." }
                val reserved = client.nativeRequest("/api/plugins/beacon/mobile/sessions/${binding.storedSessionId}/reserve", "POST", JSONObject())
                require(reserved.optBoolean("reserved") && reserved.string("stored_session_id") == binding.storedSessionId && reserved.string("profile") == client.scope.profile) { "The conversation could not be retained. Reopen Projects." }
                val result = client.nativeRpc("session.cwd.set", json("session_id" to binding.runtimeSessionId, "cwd" to target))
                val confirmed = client.nativeRpc("session.resume", json("session_id" to binding.storedSessionId, "close_on_disconnect" to false, "omit_messages" to true))
                val actual = confirmed.optJSONObject("info")?.string("cwd").orEmpty()
                require(nativeSessionBinding(client.scope, confirmed).storedSessionId == binding.storedSessionId && sameNativeFolder(actual, target)) { "The conversation folder was not confirmed. Refresh before retrying." }
                return result.put("project", project)
            }
            else -> error("Unknown native project operation")
        }
    }
}
internal fun sameNativeFolder(first: String, second: String): Boolean {
    fun normalized(path: String) = path.replace('\\', '/').trimEnd('/').let { if (it.matches(Regex("^[A-Za-z]:.*")) || it.startsWith("//")) it.lowercase(java.util.Locale.ROOT) else it }
    return first.isNotBlank() && second.isNotBlank() && normalized(first) == normalized(second)
}

/** The path Hermes stored for a folder just linked as [wanted]; null when [project] does not list it. */
internal fun linkedFolderPath(project: JSONObject, wanted: String): String? =
    project.rows("folders").map { it.string("path") }.firstOrNull { sameNativeFolder(it, wanted) }

internal fun nativeProjectRemovalConfirmed(result: JSONObject, id: String): Boolean {
    val rows = result.optJSONArray("projects") ?: return false
    return (0 until rows.length()).all { index ->
        val row = rows.optJSONObject(index)
        row != null && row.string("id").isNotBlank() && row.string("id") != id
    }
}
