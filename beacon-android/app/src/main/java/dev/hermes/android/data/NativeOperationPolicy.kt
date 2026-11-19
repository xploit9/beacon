package dev.hermes.android.data

import dev.hermes.android.core.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

/** The plugin's owner-only route that turns Beacon on for one profile through Hermes' own activation (hermes-plugin admin_api.py). */
internal const val PROFILE_PLUGIN_ENABLE_PATH = "/api/plugins/beacon/admin/profile-plugin/enable"
/** Hermes' activation can take tens of seconds the first time (the PM transaction); the plugin itself gives up after 120 s. */
internal const val PROFILE_TURN_ON_TIMEOUT_MS = 130_000L

/** Closed allowlist: an old operation identifier is never itself an HTTP fallback. */
internal object NativeOperationPolicy {
    private val paths = setOf(
        "/", "/api/auth/providers", "/auth/password-login", "/auth/logout", "/auth/native/authorize", "/auth/native/token", "/auth/native/refresh", "/api/auth/me", "/api/auth/ws-ticket", "/api/ws",
        "/api/skills", "/api/skills/content", "/api/skills/toggle", "/api/memory",
        "/api/status", "/api/logs", "/api/ops/checkpoints", "/api/hermes/update/check", "/api/mcp/servers",
        "/api/model/info", "/api/model/options", "/api/model/set",
        "/api/sessions", "/api/sessions/search",
        "/api/tools/toolsets", "/api/files", "/api/files/read", "/api/files/download", "/api/files/upload", "/api/files/mkdir",
        "/api/fs/list", "/api/fs/read-text", "/api/fs/write-text", "/api/fs/download", "/api/cron/jobs", "/api/cron/delivery-targets"
    )
    private val cronPath = Regex("/api/cron/jobs/[A-Za-z0-9_-]+(?:/(?:pause|resume|trigger|runs))?")
    private val kanbanPath = Regex("/api/plugins/kanban/(?:board|boards|tasks(?:/[A-Za-z0-9_-]+)?)")
    private val toolPath = Regex("/api/tools/toolsets/[A-Za-z0-9_.%~-]+")
    private val mcpPath = Regex("/api/mcp/servers/[A-Za-z0-9_.%~-]+/(?:test|enabled)")
    // Beacon's own install from the app, and the gateway restart that finishes it: both POST only, both asked of the owner first.
    private const val beaconPluginInstallPath = "/api/dashboard/agent-plugins/install"
    // What Hermes lists for Beacon (installed, on, version on disk): the one read a repair needs before Beacon's own routes answer.
    private const val pluginHubPath = "/api/dashboard/plugins/hub"
    private const val beaconPluginEnablePath = "/api/dashboard/agent-plugins/beacon/enable"
    private const val gatewayRestartPath = "/api/gateway/restart"
    // Hermes' own update: start it (POST), read its action log and the latest receipt (GET). Only the update's own action is open.
    private const val hermesUpdatePath = "/api/hermes/update"
    private const val updateStatusPath = "/api/actions/hermes-update/status"
    private const val updateReceiptPath = "/api/hermes/update/receipt"
    private const val deviceLinkPath = "/api/plugins/beacon/devices/link"
    private const val devicesPath = "/api/plugins/beacon/devices"
    // Hermes' profile id (hermes_constants.PROFILE_ID_RE) as the one field the turn-on route takes.
    private val profileIdOnly = Regex("[a-z0-9][a-z0-9_-]{0,63}")
    private val devicePath = Regex("/api/plugins/beacon/devices/[A-Za-z0-9_-]{1,128}")
    // Hermes' profile id (hermes_constants.PROFILE_ID_RE). Only the routes the app uses for profiles are open (read and write a profile's role text, write its default model), each with its own method.
    private val profilePath = Regex("/api/profiles/[a-z0-9][a-z0-9_-]{0,63}")
    private val profileRolePath = Regex("/api/profiles/[a-z0-9][a-z0-9_-]{0,63}/(?:description|soul|model)")
    private val profileSoulPath = Regex("/api/profiles/[a-z0-9][a-z0-9_-]{0,63}/soul")
    // Supported upstream history reads only (latest-descendant verifies a compression chain); mutations stay in the native /api/ws RPC surface.
    private val sessionPath = Regex("/api/sessions/[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}(?:/(?:messages(?:/around)?|timeline|latest-descendant|mutation-snapshot))?")
    // Pin, unpin, archive and hide: Hermes' own PATCH, which reaches a running gateway itself.
    private val sessionFlagsPath = Regex("/api/sessions/(?!search$|owner-backfill$)[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
    /**
     * The one body the install route takes from this app: Beacon's fixed source, `force` and `enable` true, and `ref` a full lowercase 40-hex
     * commit. Any other key, value or source is refused here, before a request exists. Every other path has no body rule.
     */
    fun installBodyAllowed(path: String, body: org.json.JSONObject?): Boolean {
        // The turn-on route takes exactly {profile: <a Hermes profile id>}, plus auto: true when the phone asks by itself; the client further requires it to be the profile the
        // app is signed in to.
        if (path == PROFILE_PLUGIN_ENABLE_PATH) return body != null && (body.length() == 1 || body.length() == 2 && body.opt("auto") == true) &&
            (body.opt("profile") as? String)?.matches(profileIdOnly) == true
        if (path != beaconPluginInstallPath) return true
        return body != null && body.length() == 4 && body.opt("identifier") == dev.hermes.android.updates.BEACON_PLUGIN_SOURCE &&
            body.opt("enable") == true && body.opt("force") == true && (body.opt("ref") as? String)?.let(dev.hermes.android.updates.COMMIT_SHA::matches) == true
    }
    // The /api/plugins/beacon/* namespace is the optional extension's own routes, not upstream API.
    fun direct(path: String, method: String): Boolean {
        if (path == "/api/files/upload-stream") return method == "POST"
        if (path == hermesUpdatePath) return method == "POST"
        if (path == updateStatusPath || path == updateReceiptPath) return method == "GET"
        if (path == pluginHubPath) return method == "GET"
        if (path == beaconPluginInstallPath || path == beaconPluginEnablePath || path == gatewayRestartPath) return method == "POST"
        if (path == deviceLinkPath || path == PROFILE_PLUGIN_ENABLE_PATH) return method == "POST"
        if (path == devicesPath) return method == "GET"
        if (path.startsWith("/api/plugins/beacon/devices/")) return method == "DELETE" && path.matches(devicePath)
        if (path == "/api/profiles") return method == "GET" || method == "POST"
        if (path == "/api/profiles/active") return method == "GET"
        if (path.startsWith("/api/profiles/")) return method == "DELETE" && path != "/api/profiles/default" && path.matches(profilePath) || method == "PUT" && path.matches(profileRolePath) || method == "GET" && path.matches(profileSoulPath)
        if ((path == "/api/sessions" || path.startsWith("/api/sessions/")) && method != "GET") return method == "PATCH" && path.matches(sessionFlagsPath)
        return path.startsWith("/api/plugins/beacon/") || path in paths || path.matches(cronPath) || path.matches(kanbanPath) || path.matches(toolPath) || path.matches(mcpPath) || path.matches(sessionPath)
    }
}

/**
 * The catalog entries that are the owner's own, as name to description: Hermes lists quick commands under "User commands", plugin commands
 * under "Plugin commands" and skills in `skills` (tui_gateway/command_discovery.py). The phone never hides these, whatever their name.
 */
internal fun ownCommandEntries(catalog: JSONObject): Set<Pair<String, String>> {
    val own = mutableSetOf<Pair<String, String>>()
    val categories = catalog.optJSONArray("categories")
    for (i in 0 until (categories?.length() ?: 0)) {
        val category = categories?.optJSONObject(i) ?: continue
        if (category.string("name") !in setOf("User commands", "Plugin commands")) continue
        val pairs = category.optJSONArray("pairs") ?: continue
        for (j in 0 until pairs.length()) pairs.optJSONArray(j)?.let { own += it.optString(0) to it.optString(1) }
    }
    val skills = catalog.optJSONObject("skills")?.keys()?.asSequence()?.toSet().orEmpty()
    val pairs = catalog.optJSONArray("pairs")
    for (i in 0 until (pairs?.length() ?: 0)) pairs?.optJSONArray(i)?.let { if (it.optString(0) in skills) own += it.optString(0) to it.optString(1) }
    return own
}

/**
 * Whether a shared Hermes gateway (session authority) runs built-in [name] for a phone's chat. It runs its reviewed reads, controls, prompt commands,
 * per-chat settings and reports (gateway/session_commands.py:22-55, judged by `command_verdict` :133-145, Hermes 2bd378ed) and refuses every other
 * registry command with `unsupported_command` (:456-458), from slash.exec and command.dispatch alike (gateway/session_controls.py:441-447). Its command
 * catalog does not say which (tui_gateway/command_discovery.py:205-249), so the names are copied from that source. The phone runs model and queue itself.
 */
internal fun sharedGatewayRuns(name: String): Boolean = name.lowercase() in sharedGatewayCommands
private val sharedGatewayCommands = setOf(
    "help", "commands", "status", "context", "version", "whoami", "usage", "insights", "profile", "diff", "memory", "kanban", "topup", "suggestions", "bundles", "skills",
    "title", "goal", "subgoal", "loop", "heartbeat", "review", "refine", "debug", "curator", "login", "autoprune", "plan", "learn", "init", "moa", "blueprint", "egress",
    "reasoning", "voice", "approvals", "codex-runtime", "footer", "busy", "verbose", "yolo", "fast", "personality", "approve", "deny", "toolsets", "platforms", "cron",
    "model", "queue",
)

/** Chat commands must not route profile configuration writes through command.dispatch. */
internal fun chatModelChoice(argument: String): Pair<String, String> {
    val parts = argument.trim().split(Regex("\\s+"))
    require(parts.size == 1 || parts.size == 3 && parts[1] == "--provider" && parts[2].matches(Regex("[A-Za-z0-9_.:-]+"))) { "Choose the model and provider in the chat's model picker." }
    require(parts[0].isNotBlank() && !parts[0].startsWith("-")) { "Choose a model in this chat." }
    return parts[0] to parts.getOrElse(2) { "" }
}

internal fun chatCommandChangesProfile(name: String, argument: String): Boolean =
    name.lowercase() in setOf("approvals", "approval", "config", "tools", "toolsets", "personality", "codex-runtime", "codex_runtime", "statusbar", "sb", "battery", "timestamps", "ts", "verbose", "focus", "footer", "skin", "indicator", "voice", "wake", "busy", "autoprune", "import") ||
        name.lowercase() in setOf("model", "reasoning", "fast", "yolo") && argument.split(Regex("\\s+")).any { it.lowercase() in setOf("--global", "--persist") || it.startsWith("--global=", true) } ||
        name.lowercase() in setOf("memory", "skills") && argument.substringBefore(' ').lowercase() in setOf("approval", "mode") ||
        name.lowercase() in setOf("snapshot", "snap") && argument.substringBefore(' ').equals("restore", true)

internal fun conversationActionFailure(path: String, error: Throwable): Throwable {
    if (error is NativeAuthExpiredException || error is java.io.IOException && error !is ApiFailure) return error
    val verb = when (path) {
        "/api/session/pin" -> "pin"
        "/api/session/archive" -> "archive or restore"
        "/api/session/rename" -> "rename"
        "/api/session/branch" -> "branch"
        "/api/session/update", "/api/models" -> "load or change the model for"
        "/api/reasoning", "/api/mobile/beacon/reasoning" -> "load or change reasoning for"
        "/api/approval/mode", "/api/profile/approval-mode" -> "check or change approvals for"
        "/api/profile/defaults" -> "load the defaults for"
        "/api/tools/toolsets" -> "load toolsets for"
        "/api/sessions", "/api/sessions/search" -> "load the history for"
        else -> return error
    }
    val message = if (error is ApiFailure && error.status == 401 && !error.plugin) "Reconnect before changing this chat." else "Couldn't $verb this chat. Refresh and try again."
    return if (error is ApiFailure) ApiFailure(error.status, message, error.plugin) else IllegalStateException(message)
}

internal fun conversationPendingLabel(path: String): String = when (path) {
    "/api/session/branch" -> "Branching…"
    "/api/session/pin" -> "Updating pin…"
    "/api/session/archive" -> "Updating archive…"
    "/api/session/rename" -> "Renaming…"
    "/api/session/update" -> "Applying model…"
    "/api/session/delete" -> "Deleting…"
    else -> "Working…"
}

internal const val PROFILE_DEFAULTS_PATH = "/api/profile/defaults"
/** How long a busy Hermes gets before the chat's defaults are asked for once more. */
internal const val PROFILE_DEFAULTS_RETRY_MS = 2_000L

/**
 * Reads the chat's defaults, and once more after [pauseMs] when the first read fails. Every failed attempt goes to [failed] (1 or 2),
 * so a retry that works leaves no trace on screen. An ended sign-in is not a busy Hermes and is never retried.
 */
internal suspend fun readDefaultsAgain(pauseMs: Long = PROFILE_DEFAULTS_RETRY_MS, failed: (Int, Throwable) -> Unit = { _, _ -> }, read: suspend () -> JSONObject): JSONObject {
    try { return read() }
    catch (first: Throwable) {
        if (first is CancellationException || first is NativeAuthExpiredException || first is ApiFailure && first.status in setOf(401, 403)) throw first
        failed(1, first)
    }
    delay(pauseMs)
    try { return read() }
    catch (second: Throwable) { if (second !is CancellationException) failed(2, second); throw second }
}

/**
 * What the chat shows for its defaults once both reads failed: the last good answer for this account and profile when there is one
 * (the defaults belong to the profile, not to a chat), otherwise a short line for the model picker. Never an app-wide error.
 */
internal fun profileDefaultsAfterFailure(previous: JSONObject?, scopeKey: String, profile: String, session: String, error: Throwable? = null): JSONObject {
    val usable = previous != null && !previous.has("_error") && previous.string("scope_key") == scopeKey && previous.string("profile") == profile &&
        previous.opt("model") is String && previous.string("model").isNotBlank() && previous.opt("reasoning_effort") is String && previous.string("reasoning_effort").isNotBlank()
    return if (usable) previous!!.let { JSONObject(it.toString()) }.put("session_id", session).put("_stale", true)
    else JSONObject().put("_error", if (error != null && beaconOffForProfile(error)) beaconOffLine(error) else "Couldn't load models").put("profile", profile).put("scope_key", scopeKey).put("session_id", session)
}

/** A failed read puts the app-wide error on screen only for a read the owner asked for and not for the chat defaults, which fall back to what was last good. */
internal fun loadFailureRaisesBanner(path: String, force: Boolean, quiet: Boolean, cacheable: Boolean = false): Boolean =
    path != PROFILE_DEFAULTS_PATH && (force || !cacheable) && !quiet
