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
    private const val pairingRedeemPath = "/api/plugins/beacon/pairing/redeem"
    private const val deviceLinkPath = "/api/plugins/beacon/devices/link"
    private const val devicesPath = "/api/plugins/beacon/devices"
    private val devicePath = Regex("/api/plugins/beacon/devices/[A-Za-z0-9_-]{1,128}")
    // Supported upstream history reads only (latest-descendant verifies a compression chain); mutations stay in the native /api/ws RPC surface.
    private val sessionPath = Regex("/api/sessions/[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}(?:/(?:messages|timeline|latest-descendant|mutation-snapshot))?")
    // The one exception: Hermes sets a conversation's pin only through this sidebar PATCH (no RPC exists).
    private val sessionFlagsPath = Regex("/api/sessions/(?!search$|owner-backfill$)[A-Za-z0-9][A-Za-z0-9_.:-]{0,255}")
    // The /api/plugins/beacon/* namespace is the optional extension's own routes, not upstream API.
    fun direct(path: String, method: String): Boolean {
        if (path == beaconPluginUpdatePath) return method == "POST"
        if (path == pairingRedeemPath) return method == "POST"
        if (path.startsWith("/api/plugins/beacon/pairing/")) return false
        if (path == deviceLinkPath) return method == "POST"
        if (path == devicesPath) return method == "GET"
        if (path.startsWith("/api/plugins/beacon/devices/")) return method == "DELETE" && path.matches(devicePath)
        if ((path == "/api/sessions" || path.startsWith("/api/sessions/")) && method != "GET") return method == "PATCH" && path.matches(sessionFlagsPath)
        return path.startsWith("/api/plugins/beacon/") || path in paths || path.matches(cronPath) || path.matches(kanbanPath) || path.matches(toolPath) || path.matches(mcpPath) || path.matches(sessionPath)
    }
}
