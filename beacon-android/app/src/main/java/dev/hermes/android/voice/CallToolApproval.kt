package dev.hermes.android.voice

import org.json.JSONObject

data class CallToolApproval(val requestId: String, val proposalId: String, val toolName: String, val arguments: String, val busy: Boolean = false, val error: String = "") {
    companion object {
        fun from(value: JSONObject): CallToolApproval {
            val request = value.getString("request_id")
            val proposal = value.getString("proposal_id")
            val name = value.getString("tool_name")
            val arguments = value.getJSONObject("arguments").toString(2)
            require(request.matches(Regex("[A-Za-z0-9_-]{1,160}")) && proposal.matches(Regex("[A-Za-z0-9_-]{32,80}")))
            require(name.isNotBlank() && name.length <= 200 && arguments.toByteArray(Charsets.UTF_8).size <= 65536)
            return CallToolApproval(request, proposal, name, arguments)
        }
    }
}
