package dev.hermes.android.data

import dev.hermes.android.core.json
import org.json.JSONObject

/** Direct MCP receipts stay bound to the original owner, profile and call. */
class NativeToolGateway(private val client: BeaconClient, private val callId: String) {
    private val binding = client.scope
    private val path: String
    init {
        require(callId.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid call identity" }
        path = "/api/plugins/beacon/mobile/calls/$callId/tools"
    }
    private fun bound() { require(client.scope == binding) { "Tool call belongs to another account or profile" } }
    suspend fun discover(query: String = ""): JSONObject {
        bound(); require(query.length <= 240)
        return client.nativeGet(path, mapOf("query" to query), maxBytes = 65536)
    }
    suspend fun invoke(requestId: String, toolId: String, arguments: JSONObject): JSONObject {
        bound(); requireRequestId(requestId); require(toolId.matches(Regex("[a-f0-9]{64}")))
        require(arguments.toString().toByteArray(Charsets.UTF_8).size <= 16384)
        return client.nativeRequest("$path/invoke", "POST", json("request_id" to requestId, "tool_id" to toolId, "arguments" to arguments))
    }
    suspend fun status(requestId: String): JSONObject {
        bound(); requireRequestId(requestId)
        return client.nativeGet("$path/requests/$requestId", maxBytes = 65536)
    }
    suspend fun cancel(requestId: String): JSONObject {
        bound(); requireRequestId(requestId)
        return client.nativeRequest("$path/requests/$requestId/cancel", "POST", JSONObject())
    }
    /** Called only by the owner's confirmation UI; never expose this as a Gemini function. */
    suspend fun decide(requestId: String, proposalId: String, decision: String): JSONObject {
        bound(); requireRequestId(requestId); require(proposalId.matches(Regex("[A-Za-z0-9_-]{32,80}")))
        require(decision in setOf("approve", "approve_call", "deny")) { "Invalid tool approval decision" }
        return client.nativeRequest("$path/requests/$requestId/decision", "POST", json("proposal_id" to proposalId, "decision" to decision))
    }
    companion object {
        internal fun requireRequestId(value: String) { require(value.matches(Regex("[A-Za-z0-9_-]{1,160}"))) { "Invalid tool request identity" } }
    }
}
