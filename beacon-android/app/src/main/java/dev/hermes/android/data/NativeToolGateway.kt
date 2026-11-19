package dev.hermes.android.data

import dev.hermes.android.core.json
import org.json.JSONObject

/** Direct MCP receipts stay bound to the original owner, profile and call. */
class NativeToolGateway(private val client: BeaconClient, private val callId: String) {
    private val binding = client.scope
    private val call: String
    private val path: String
    init {
        require(callId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid call identity" }
        call = "/api/plugins/beacon/mobile/calls/$callId"
        path = "$call/tools"
    }
    private fun bound() { require(client.scope == binding) { "Tool call belongs to another account or profile" } }
    suspend fun discover(query: String = ""): JSONObject {
        bound(); require(query.length <= 240)
        return client.nativeGet(path, mapOf("query" to query), maxBytes = 65536)
    }
    /** [approval] is the owner's Approvals choice (ask, changes or owner), sent only to a plugin that takes it. */
    suspend fun invoke(requestId: String, toolId: String, arguments: JSONObject, approval: String? = null): JSONObject {
        bound(); requireRequestId(requestId); require(toolId.matches(Regex("[a-f0-9]{64}")))
        requireArguments(arguments); require(approval == null || approval in setOf("ask", "changes", "owner"))
        return client.nativeRequest("$path/invoke", "POST", json("request_id" to requestId, "tool_id" to toolId, "arguments" to arguments)
            .apply { approval?.let { put("approval", it) } })
    }
    /**
     * Contract v3: [tool] is a declared name, a tool_id or Hermes' own name; Hermes answers when the tool finishes or after [waitMs]
     * ("running"), whichever is first, so a quick tool is one request.
     */
    suspend fun invokeAndWait(requestId: String, tool: String, arguments: JSONObject, approval: String?, waitMs: Long): JSONObject {
        bound(); requireRequestId(requestId); require(tool.matches(TOOL)) { "Invalid tool" }
        requireArguments(arguments); require(approval == null || approval in setOf("ask", "changes", "owner")); require(waitMs in 0..8000)
        return client.nativeRequest("$path/invoke", "POST", json("request_id" to requestId, "tool" to tool, "arguments" to arguments, "wait_ms" to waitMs)
            .apply { approval?.let { put("approval", it) } })
    }
    suspend fun status(requestId: String): JSONObject {
        bound(); requireRequestId(requestId)
        return client.nativeGet("$path/requests/$requestId", maxBytes = 65536)
    }
    suspend fun cancel(requestId: String): JSONObject {
        bound(); requireRequestId(requestId)
        return client.nativeRequest("$path/requests/$requestId/cancel", "POST", JSONObject())
    }
    /** Called only by the owner's confirmation UI; never expose this as a Gemini function. [waitMs] only for a v3 plugin. */
    suspend fun decide(requestId: String, proposalId: String, decision: String, waitMs: Long? = null): JSONObject {
        bound(); requireRequestId(requestId); require(proposalId.matches(Regex("[A-Za-z0-9_-]{32,80}")))
        require(decision in setOf("approve", "approve_call", "deny")) { "Invalid tool approval decision" }
        require(waitMs == null || waitMs in 0..8000)
        return client.nativeRequest("$path/requests/$requestId/decision", "POST", json("proposal_id" to proposalId, "decision" to decision)
            .apply { waitMs?.let { put("wait_ms", it) } })
    }
    /** Contract v3 section 3: the call's events after [after]; Hermes holds the answer up to [waitMs] while there are none. */
    suspend fun updates(after: Long, waitMs: Long): JSONObject {
        bound(); require(after >= 0 && waitMs in 0..20000)
        return client.nativeGet("$call/updates", mapOf("after" to after.toString(), "wait_ms" to waitMs.toString()), maxBytes = 1 shl 20)
    }
    /** Contract v3 section 4: one hand-over into Hermes' own queue for this call's chat. */
    suspend fun handover(requestId: String, text: String, mode: String, callback: Boolean?, frameJpeg: String?): JSONObject {
        bound(); requireRequestId(requestId); require(text.length in 1..16000 && mode in setOf("background", "inline"))
        require(frameJpeg == null || frameJpeg.length <= 65536)
        return client.nativeRequest("$call/handover", "POST", json("request_id" to requestId, "text" to text, "mode" to mode)
            .apply { callback?.let { put("callback", it) }; frameJpeg?.let { put("frame_jpeg", it) } })
    }
    companion object {
        private val TOOL = Regex("[a-f0-9]{64}|[A-Za-z0-9_.:/-]{1,128}")
        internal fun requireRequestId(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid tool request identity" } }
        private fun requireArguments(arguments: JSONObject) = require(arguments.toString().toByteArray(Charsets.UTF_8).size <= 16384) { "Tool arguments are too large" }
    }
}
