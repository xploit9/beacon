package dev.hermes.android.voice

import org.json.JSONObject
import org.json.JSONArray

data class CallToolApproval(val requestId: String, val proposalId: String, val toolName: String, val arguments: String, val busy: Boolean = false, val error: String = "", val allowCall: Boolean = true) {
    companion object {
        fun from(value: JSONObject): CallToolApproval {
            val request = value.getString("request_id")
            val proposal = value.getString("proposal_id")
            val name = value.getString("tool_name")
            val arguments = value.getJSONObject("arguments").toString(2)
            require(request.matches(Regex("[A-Za-z0-9_-]{1,160}")) && proposal.matches(Regex("[A-Za-z0-9_-]{32,80}")))
            require(name.isNotBlank() && name.length <= 200 && arguments.toByteArray(Charsets.UTF_8).size <= 65536)
            // Older plugins omit the field and keep offering the call-long allowance; any present value other than true withholds it.
            return CallToolApproval(request, proposal, name, arguments, allowCall = !value.has("allow_call") || value.opt("allow_call") == true)
        }
    }
}

/** Pending work shares the gateway's 64-request bound; only cosmetic recent receipts may be clipped. */
internal fun pendingCallToolApprovals(current: List<CallToolApproval>, event: JSONObject): List<CallToolApproval> = when (event.optString("type")) {
    "tool_approval" -> CallToolApproval.from(event).let { approval -> current.filterNot { it.requestId == approval.requestId } + approval }
    "tool_receipt" -> if (callProposalNeedingOwnerApproval(event) == null) current.filterNot { it.requestId == event.optString("request_id") } else current
    else -> current
}

internal data class CallApprovalSummary(
    val action: String,
    val target: String = "",
    val preview: String = "",
    val service: String = "",
    val fields: String = "",
)

internal fun callToolApprovalSummary(approval: CallToolApproval): CallApprovalSummary {
    val arguments = runCatching { JSONObject(approval.arguments) }.getOrDefault(JSONObject())
    val name = approval.toolName.substringAfterLast("__").lowercase()
    val words = name.replace('_', ' ').split(' ').filter(String::isNotBlank)
    val action = when (name) {
        "write_file" -> "Write a file"
        "create_file" -> "Create a file"
        "edit_file" -> "Edit a file"
        "delete_file" -> "Delete a file"
        else -> words.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }.ifBlank { "Review this action" }
    }
    val path = listOf("path", "file_path", "filename").firstNotNullOfOrNull { key ->
        arguments.optString(key).takeIf(String::isNotBlank)
    }.orEmpty().replace('\\', '/')
    val fileName = path.substringAfterLast('/').takeIf(String::isNotBlank).orEmpty()
    val folder = path.substringBeforeLast('/', "").substringAfterLast('/').takeIf(String::isNotBlank).orEmpty()
    val target = listOf(fileName, folder).filter(String::isNotBlank).joinToString(" · ")
    val previewKey = listOf("command", "cmd", "code", "content", "text", "body").firstOrNull { arguments.optString(it).isNotBlank() }
    val previewText = previewKey?.let { arguments.optString(it) }?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
    // A command is shown nearly whole and a clipped preview always says so, so nothing hides behind "Always allow".
    val previewLimit = if (previewKey in setOf("command", "cmd", "code")) 1000 else 160
    val preview = previewText.clipWithEllipsis(previewLimit)
    val service = approval.toolName.takeIf { it.startsWith("mcp__") }
        ?.removePrefix("mcp__")?.substringBefore("__")?.split('_')
        ?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }.orEmpty()
    val fields = listOf("to", "cc", "bcc", "subject", "query", "title", "name", "filename", "url")
        .mapNotNull { key ->
            val value = arguments.opt(key).takeIf { it is String || it is Number || it is Boolean || it is JSONArray }?.toString()
                ?.takeIf(String::isNotBlank)?.clipWithEllipsis(100) ?: return@mapNotNull null
            "$key: $value"
        }.joinToString(" · ")
    return CallApprovalSummary(action, target, preview, service, fields)
}

private fun String.clipWithEllipsis(limit: Int) = if (length > limit) take(limit - 1) + "…" else this

internal fun callToolApprovalErrorMessage(message: String): String {
    val text = message.trim()
    return when {
        text.contains("Invalid approval decision", ignoreCase = true) -> "Choose Deny, Allow once, or Always allow during this call."
        text.contains("Approval proposal mismatch", ignoreCase = true) -> "The approval changed. Review the request again."
        text.contains("Call is closed or unavailable", ignoreCase = true) -> "This call ended. The action was not allowed."
        text.contains("Call tool allowance capacity reached", ignoreCase = true) -> "This call has reached its saved-tool limit. Choose Allow once."
        text.contains("Tool allowance capacity reached", ignoreCase = true) -> "The app has reached its saved-tool limit. Choose Allow once."
        text.contains("Request identity conflicts", ignoreCase = true) -> "The request changed. Review the current details."
        text.contains("Tool changed or is no longer configured", ignoreCase = true) -> "This tool changed. Refresh the list and try again."
        text.contains("Tool execution capacity reached", ignoreCase = true) || text.contains("Tool request capacity reached", ignoreCase = true) -> "The call is busy. Try again shortly."
        text.contains("Tool lookup still settling", ignoreCase = true) -> "This request is still finishing. Wait and check it again."
        else -> text.take(240)
    }
}

/** The only receipt shape that may open the phone's owner approval dialog. */
internal fun callProposalNeedingOwnerApproval(receipt: JSONObject): JSONObject? {
    if (receipt.optString("status") != "needs_approval" || (receipt.has("approved_by") && !receipt.isNull("approved_by"))) return null
    val proposal = receipt.optJSONObject("proposal") ?: return null
    val id = proposal.optString("id")
    return proposal.takeIf { id.matches(Regex("[A-Za-z0-9_-]{32,80}")) && it.optJSONObject("arguments") != null }
}

/** The event the call UI reads; allow_call travels only when the proposal states it. */
internal fun callToolApprovalEvent(requestId: String, toolName: String, proposal: JSONObject): JSONObject =
    JSONObject().put("type", "tool_approval").put("request_id", requestId).put("proposal_id", proposal.getString("id"))
        .put("tool_name", toolName).put("arguments", JSONObject(proposal.getJSONObject("arguments").toString()))
        .apply { if (proposal.has("allow_call")) put("allow_call", proposal.get("allow_call")) }

data class CallToolReceipt(
    val requestId: String,
    val toolName: String,
    val status: String,
    val approvedBy: String? = null,
    val hasResult: Boolean = false,
) {
    companion object {
        private val decisions = setOf("chat_yolo", "profile_off", "smart", "memory", "call_allowance", "owner")
        private val statuses = setOf("ready", "running", "needs_approval", "completed", "failed", "denied", "expired", "cancelled", "uncertain")

        fun from(value: JSONObject): CallToolReceipt? {
            val requestId = value.optString("request_id")
            val toolName = value.optString("tool_name")
            val status = value.optString("status")
            if (!requestId.matches(Regex("[A-Za-z0-9_-]{1,160}")) || toolName.isBlank() || toolName.length > 200 || status !in statuses) return null
            val approvedBy = if (value.has("approved_by") && !value.isNull("approved_by")) value.optString("approved_by").takeIf(decisions::contains) else null
            // Fixed reads omit private contents; gateway receipts still require their actual result.
            val fixedReadResult = status == "completed" && !value.has("tool_id") && value.opt("result_present") == true &&
                toolName in setOf("discover_tools", "hermes_search", "hermes_memory", "hermes_skills", "hermes_context")
            return CallToolReceipt(requestId, toolName, status, approvedBy, (value.has("result") && !value.isNull("result")) || fixedReadResult)
        }
    }
}

internal fun CallToolReceipt.approvalCaption(): String? = when (approvedBy) {
    "chat_yolo" -> "Approvals skipped in this chat"
    "profile_off" -> "Approvals off"
    "smart", "memory" -> "Allowed automatically"
    "call_allowance" -> "Allowed for this call"
    else -> null
}

private fun CallToolReceipt.service(): String? = toolName.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")
    ?.split('_')?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }?.takeIf(String::isNotBlank)

/** What the activity names: the service for an MCP tool, else the tool. It is the {0} of [activityTemplate]. */
internal fun CallToolReceipt.activitySubject(): String = service() ?: toolName.substringAfterLast("__").lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

/** The activity with its subject left as {0}, so uiText can translate the wording and the subject goes in after. */
internal fun CallToolReceipt.activityTemplate(): String? {
    if (status !in setOf("ready", "running")) return null
    val tool = toolName.substringAfterLast("__").lowercase()
    return when {
        service() != null -> "Checking {0}…"
        tool in setOf("write_file", "create_file") -> "Writing a file…"
        tool in setOf("edit_file", "patch_file") -> "Updating a file…"
        tool in setOf("read_file", "list_directory") -> "Reading files…"
        else -> "Using {0}…"
    }
}

internal fun CallToolReceipt.activityLabel(): String? = activityTemplate()?.replace("{0}", activitySubject())

/** [activityTemplate] as the call status line says it: a service is "Using", not "Checking". */
internal fun CallToolReceipt.statusTemplate(): String? = activityTemplate()?.let { if (it == "Checking {0}…") "Using {0}…" else it }

internal fun CallToolReceipt.statusLabel(): String? = statusTemplate()?.replace("{0}", activitySubject())

/** The receipt texts that reach uiText whole or as templates; the tool chip's "<tool> complete" forms are built from the tool's own name. */
internal val CallToolStrings = listOf(
    "Checking {0}…", "Using {0}…", "Writing a file…", "Updating a file…", "Reading files…",
    "Approvals skipped in this chat", "Approvals off", "Allowed automatically", "Allowed for this call",
    "Needs approval", "Completion not confirmed", "Action denied", "Approval expired", "Action cancelled",
)

internal fun CallToolReceipt.chipLabel(): String? {
    activityLabel()?.let { return it }
    val tool = toolName.substringAfterLast("__").lowercase()
    val service = toolName.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")
        ?.split('_')?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }
    val action = service?.let { it + " action" } ?: when (tool) {
        "write_file", "create_file" -> "File write"
        "edit_file", "patch_file" -> "File update"
        "read_file", "list_directory" -> "File lookup"
        else -> tool.replace('_', ' ').replaceFirstChar { it.titlecase() }
    }
    return when (status) {
        "needs_approval" -> "Needs approval"
        "completed" -> if (hasResult) action + " complete" else "Completion not confirmed"
        "failed" -> action + " failed"
        "denied" -> "Action denied"
        "expired" -> "Approval expired"
        "cancelled" -> "Action cancelled"
        "uncertain" -> action + " outcome unclear"
        else -> null
    }
}
