package dev.hermes.android.agentphone

import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneEndpoint
import org.json.JSONObject

/**
 * The plugin's agent-phone routes as L2's contract v1 answers them (emu-l2/.unlazy/emu-l2/CONTRACT.md, agent_phone_api.py at 17 Nov),
 * with Root's additions (msg_c579205379f9, msg_ed1d395edfa5) and L3's approvals v1. Each route answers its queued answers in order and
 * then keeps the last one; a route nobody set answers FastAPI's own 404. Every call is recorded with its body.
 */
internal class FakePhone : AgentPhoneEndpoint {
    data class Call(val method: String, val route: String, val body: JSONObject?, val timeoutMillis: Long)
    val calls = mutableListOf<Call>()
    private val answers = mutableMapOf<String, ArrayDeque<AgentPhoneAnswer>>()

    fun on(method: String, route: String, status: Int, body: String): FakePhone {
        answers.getOrPut("$method $route") { ArrayDeque() }.addLast(AgentPhoneAnswer(status, JSONObject(body)))
        return this
    }

    /** From now on [method] [route] answers this (then whatever is queued after it). */
    fun now(method: String, route: String, status: Int, body: String): FakePhone {
        answers.remove("$method $route")
        return on(method, route, status, body)
    }

    fun offline(method: String, route: String): FakePhone {
        answers.getOrPut("$method $route") { ArrayDeque() }.addLast(AgentPhoneAnswer(0, JSONObject()))
        return this
    }

    override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer {
        calls += Call(method, route, body, timeoutMillis)
        val queue = answers["$method $route"] ?: return AgentPhoneAnswer(404, JSONObject("""{"detail":"Not Found"}"""))
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    fun routes(): List<String> = calls.map { "${it.method} ${it.route}" }
}

/** Contract bodies, synthetic: example.com sites and made-up apps only. */
internal object Contract {
    const val NOT_SET_UP = """{"installed": false, "profile": null, "yours": false, "phone": "off", "lease": null, "warning": null, "error": null,
        "install": {"state": "idle"}}"""
    const val DOWNLOADING = """{"installed": false, "profile": null, "yours": false, "phone": "off", "lease": null, "warning": null, "error": null,
        "install": {"state": "running", "phase": "download", "done_bytes": 1200000000, "total_bytes": 2400000000, "line": null, "retry": false}}"""
    const val OFF = """{"installed": true, "profile": "default", "yours": true, "phone": "off", "lease": {"holder": "agent", "you": false, "epoch": 2, "away": false},
        "warning": null, "error": null, "install": {"state": "idle"}}"""
    const val ON_FULL = """{"installed": true, "profile": "default", "yours": true, "phone": "on",
        "lease": {"holder": "agent", "you": false, "epoch": 3, "away": false},
        "warning": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
        "error": null, "install": {"state": "idle"},
        "setup": {"google": true, "chrome": 133, "chrome_autofill": null, "logins": 0},
        "storage_bytes": 9800000000, "helper": "update", "in_use": true}"""
    const val STARTING = """{"installed": true, "profile": "default", "yours": true, "phone": "starting", "lease": null, "warning": null, "error": null, "install": {"state": "idle"}}"""
    const val START_FAILED = """{"installed": true, "profile": "default", "yours": true, "phone": "off", "lease": null, "warning": null,
        "error": "The phone's process could not start outside Hermes' job.", "install": {"state": "idle"}}"""
    const val ON = """{"installed": true, "profile": "default", "yours": true, "phone": "on", "lease": null, "warning": null, "error": null, "install": {"state": "idle"}}"""
    const val OTHER_PROFILE = """{"installed": true, "profile": "work", "yours": false, "phone": "on", "lease": null, "warning": null, "error": null, "install": {"state": "idle"}}"""

    const val CHECKS_SIGN_IN_OFF = """{"ok": false, "needs_continue": true, "rows": [
        {"id": "platform", "status": "ready", "line": null, "fix": null, "how": null, "needs_continue": false},
        {"id": "virtualisation", "status": "ready", "line": null, "fix": null, "how": null, "needs_continue": false},
        {"id": "memory", "status": "warn", "line": "This computer has less than 16 GB memory, so the phone may be slow.", "fix": null, "how": null, "needs_continue": false},
        {"id": "disk", "status": "fail", "line": "Not enough free space. Free up 9 GB on the Hermes computer.", "fix": null, "how": null, "needs_continue": false},
        {"id": "sign_in", "status": "fail", "line": "Turn on Hermes sign-in first.",
            "fix": "Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes.",
            "how": "https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard#authentication-gated-mode", "needs_continue": false},
        {"id": "developer_tools", "status": "warn",
            "line": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
            "fix": null, "how": null, "needs_continue": true}]}"""
    const val CHECKS_WARN_ONLY = """{"ok": true, "needs_continue": true, "rows": [
        {"id": "sign_in", "status": "ready", "line": null, "fix": null, "how": null, "needs_continue": false},
        {"id": "developer_tools", "status": "warn",
            "line": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
            "fix": null, "how": null, "needs_continue": true}]}"""
    const val CHECKS_READY = """{"ok": true, "needs_continue": false, "rows": [
        {"id": "sign_in", "status": "ready", "line": null, "fix": null, "how": null, "needs_continue": false},
        {"id": "developer_tools", "status": "ready", "line": null, "fix": null, "how": null, "needs_continue": false}]}"""

    const val TERMS = """{"licences": [{"id": "android-sdk-license", "text": "Terms and Conditions\n\nThis is the Android Software Development Kit License Agreement."}],
        "sizes": {"download_bytes": 2400000000, "installed_bytes": 3400000000, "peak_bytes": 5500000000}}"""
    const val TERMS_OFFLINE = """{"line": "Download stopped. Check the Hermes computer's internet.", "fix": null, "retry": true}"""

    const val INSTALL_STARTED = """{"state": "running", "phase": null, "done_bytes": null, "total_bytes": null, "line": null, "retry": false}"""
    const val INSTALL_DONE = """{"state": "done", "phase": "set up", "done_bytes": 2400000000, "total_bytes": 2400000000}"""

    const val APPROVALS = """{"approvals": [
        {"id": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "kind": "agent_phone_action", "risk": "payment", "app": "Kanto Foods", "label": "Place order",
            "amount": "£12.40", "created_at": 1794916000, "expires_at": 1794916120, "expires_in_seconds": 120},
        {"id": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "kind": "needs_you", "app": "Kanto Foods", "line": "The Kanto Foods login didn't work.",
            "created_at": 1794916000, "expires_at": 1795002400, "expires_in_seconds": 86400}]}"""
    const val ACTIVITY = """{"activity": [
        {"at": 1794916000.5, "app": "Kanto Foods", "action": "tap", "label": "Place order", "approved": true, "outcome": "done"},
        {"at": 1794915000, "app": "Kanto Foods", "action": "fill", "label": "", "approved": false, "outcome": "done"}]}"""
    const val POLICY = """{"enabled": true, "ask": "payments_and_sends"}"""
}
