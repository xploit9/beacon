package dev.hermes.android.data

/** Closed allowlist: an old operation identifier is never itself an HTTP fallback. */
internal object NativeOperationPolicy {
    private val paths = setOf(
        "/api/auth/providers", "/auth/password-login", "/auth/logout", "/api/auth/me", "/api/auth/ws-ticket", "/api/ws",
        "/api/skills", "/api/skills/content", "/api/skills/toggle", "/api/memory",
        "/api/status", "/api/logs", "/api/ops/checkpoints", "/api/hermes/update/check", "/api/mcp/servers",
        "/api/model/info", "/api/model/options", "/api/model/set", "/api/profiles", "/api/profiles/active",
        "/api/tools/toolsets", "/api/files", "/api/files/read", "/api/files/download", "/api/files/upload", "/api/files/mkdir",
        "/api/fs/list", "/api/fs/read-text", "/api/fs/write-text", "/api/fs/download", "/api/cron/jobs", "/api/cron/delivery-targets"
    )
    private val cronPath = Regex("/api/cron/jobs/[A-Za-z0-9_-]+(?:/(?:pause|resume|trigger|runs))?")
    private val kanbanPath = Regex("/api/plugins/kanban/(?:board|boards|tasks(?:/[A-Za-z0-9_-]+)?)")
    private val toolPath = Regex("/api/tools/toolsets/[A-Za-z0-9_.%~-]+")
    private val mcpPath = Regex("/api/mcp/servers/[A-Za-z0-9_.%~-]+/(?:test|enabled)")
    fun direct(path: String): Boolean = path.startsWith("/api/plugins/cypher/") || path in paths || path.matches(cronPath) || path.matches(kanbanPath) || path.matches(toolPath) || path.matches(mcpPath)
}
