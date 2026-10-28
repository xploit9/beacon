package dev.hermes.android.data

/** Closed allowlist: an old operation identifier is never itself an HTTP fallback. */
internal object NativeOperationPolicy {
    private val paths = setOf(
        "/", "/api/auth/providers", "/auth/password-login", "/auth/logout", "/auth/native/authorize", "/auth/native/token", "/auth/native/refresh", "/api/auth/me", "/api/auth/ws-ticket", "/api/ws",
        "/api/skills", "/api/skills/content", "/api/skills/toggle", "/api/memory",
        "/api/status", "/api/logs", "/api/ops/checkpoints", "/api/hermes/update/check", "/api/mcp/servers",
        "/api/model/info", "/api/model/options", "/api/model/set", "/api/profiles", "/api/profiles/active",
        "/api/sessions", "/api/sessions/search",
        "/api/tools/toolsets", "/api/files", "/api/files/read", "/api/files/download", "/api/files/upload", "/api/files/mkdir",
        "/api/fs/list", "/api/fs/read-text", "/api/fs/write-text", "/api/fs/download", "/api/cron/jobs", "/api/cron/delivery-targets"
    )
    private val cronPath = Regex("/api/cron/jobs/[A-Za-z0-9_-]+(?:/(?:pause|resume|trigger|runs))?")
    private val kanbanPath = Regex("/api/plugins/kanban/(?:board|boards|tasks(?:/[A-Za-z0-9_-]+)?)")
    private val toolPath = Regex("/api/tools/toolsets/[A-Za-z0-9_.%~-]+")
    private val mcpPath = Regex("/api/mcp/servers/[A-Za-z0-9_.%~-]+/(?:test|enabled)")
    private const val beaconPluginUpdatePath = "/api/dashboard/agent-plugins/beacon/update"
    // Beacon's own install from the app, and the gateway restart that finishes it: both POST only, both asked of the owner first.
    private const val beaconPluginInstallPath = "/api/dashboard/agent-plugins/install"
    private const val beaconPluginEnablePath = "/api/dashboard/agent-plugins/beacon/enable"
    private const val gatewayRestartPath = "/api/gateway/restart"
    private const val deviceLinkPath = "/api/plugins/beacon/devices/link"
    private const val devicesPath = "/api/plugins/beacon/devices"
    private val devicePath = Regex("/api/plugins/beacon/devices/[A-Za-z0-9_-]{1,128}")
    // Supported upstream history reads only (latest-descendant verifies a compression chain); mutations stay in the native /api/ws RPC surface.
    private val sessionPath = Regex("/api/sessions/[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}(?:/(?:messages(?:/around)?|timeline|latest-descendant|mutation-snapshot))?")
    // Compatibility for stock Hermes without the optional gateway-safe pin route.
    private val sessionFlagsPath = Regex("/api/sessions/(?!search$|owner-backfill$)[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
    // The /api/plugins/beacon/* namespace is the optional extension's own routes, not upstream API.
    fun direct(path: String, method: String): Boolean {
        if (path == "/api/files/upload-stream") return method == "POST"
        if (path == beaconPluginUpdatePath || path == beaconPluginInstallPath || path == beaconPluginEnablePath || path == gatewayRestartPath) return method == "POST"
        if (path == "/api/plugins/beacon/sessions/pin") return method == "POST"
        if (path == deviceLinkPath) return method == "POST"
        if (path == devicesPath) return method == "GET"
        if (path.startsWith("/api/plugins/beacon/devices/")) return method == "DELETE" && path.matches(devicePath)
        if ((path == "/api/sessions" || path.startsWith("/api/sessions/")) && method != "GET") return method == "PATCH" && path.matches(sessionFlagsPath)
        return path.startsWith("/api/plugins/beacon/") || path in paths || path.matches(cronPath) || path.matches(kanbanPath) || path.matches(toolPath) || path.matches(mcpPath) || path.matches(sessionPath)
    }
}

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
