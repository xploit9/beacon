package dev.hermes.android.voice

import dev.hermes.android.phone.PhoneTools
import org.json.JSONObject
import org.json.JSONArray

/** What a phone request's card says, already in the phone's language: the headline, who or where, more detail lines and a notes or message preview. */
data class PhoneApprovalText(val action: String, val target: String = "", val details: String = "", val preview: String = "",
    /** Seconds the card counts down before it places the call by itself; 0 means the owner must tap. Only a call can have one. */ val countdown: Int = 0,
    /** When the card was made on the phone's elapsed clock; a card never stamped, or one that waited too long to be drawn, does not count down. */ val askedAt: Long = 0)

data class CallToolApproval(val requestId: String, val proposalId: String, val toolName: String, val arguments: String, val busy: Boolean = false, val error: String = "", val allowCall: Boolean = true, val phone: PhoneApprovalText? = null) {
    companion object {
        fun from(value: JSONObject): CallToolApproval {
            val request = value.getString("request_id")
            val proposal = value.getString("proposal_id")
            val name = value.getString("tool_name")
            val arguments = value.getJSONObject("arguments").toString(2)
            require(request.matches(Regex("[A-Za-z0-9_-]{1,160}")) && proposal.matches(Regex("[A-Za-z0-9_-]{32,80}")))
            require(name.isNotBlank() && name.length <= 200 && arguments.toByteArray(Charsets.UTF_8).size <= 65536)
            // Older plugins omit the field and keep offering the call-long allowance; any present value other than true withholds it.
            // Only this phone's own tools carry a card; a Hermes proposal never can, whatever its fields say.
            val card = if (name in PhoneTools.names) value.optJSONObject("phone_card")?.let {
                // The notes or message arrive whole (up to the phone tools' own bounds): a card must not hide what will be written.
                PhoneApprovalText(clipped(it.optString("action"), 300), clipped(it.optString("target"), 100), it.optString("details").take(1200), it.optString("preview").take(1000),
                    if (name == "phone_call") it.optInt("countdown").coerceIn(0, 5) else 0, it.optLong("asked_at"))
            }?.takeIf { it.action.isNotBlank() } else null
            return CallToolApproval(request, proposal, name, arguments, allowCall = !value.has("allow_call") || value.opt("allow_call") == true, phone = card)
        }

        /** [text] within [limit] characters; a cut one ends in an ellipsis, so the card never hides that there is more. */
        private fun clipped(text: String, limit: Int) = if (text.length <= limit) text else text.take(limit - 1) + "…"
    }
}

/**
 * Which approvals have a card really on screen, counted per request id (the app's dialog and the overlay can each show one). The
 * call posts a notification for every approval that has none, so an approval is never left with no card and no notification.
 */
internal class ApprovalCardsOnScreen {
    private val counts = HashMap<String, Int>()

    @Synchronized fun shown(requestId: String): Boolean = (counts[requestId] ?: 0) > 0

    /** A card of [requestId] arrived ([on]) or left. True when the request went from having no card on screen to having one, or back. */
    @Synchronized fun set(requestId: String, on: Boolean): Boolean {
        val before = counts[requestId] ?: 0
        val after = (before + if (on) 1 else -1).coerceAtLeast(0)
        if (after == 0) counts.remove(requestId) else counts[requestId] = after
        return (before > 0) != (after > 0)
    }
}

/**
 * The approval a notification should ask about: the first one still waiting that has no card on screen. Null when the card on screen
 * is the approval (the app shows its first one) or nothing waits, so no notification stands beside a card and none is missing without one.
 */
internal fun approvalNeedingNotification(approvals: List<CallToolApproval>, cardShown: (String) -> Boolean): CallToolApproval? {
    val first = approvals.firstOrNull() ?: return null
    if (cardShown(first.requestId)) return null
    return approvals.firstOrNull { !it.busy && !cardShown(it.requestId) }
}

/** Pending work shares the gateway's 64-request bound; only cosmetic recent receipts may be clipped. */
/**
 * Whether the phone answers [approval] itself (an older plugin, which still asks): the owner chose Full access in Approvals, the phone is unlocked (a change made on a
 * locked phone still needs the unlock), and it is one of Hermes' own tools. This phone's own actions keep their rules (a call still
 * waits for the tap or its countdown).
 */
/**
 * The owner's Approvals choice as a call's tool request carries it to Hermes: ask (Manual: every tool), changes (Auto: changes only)
 * or owner (Full access: Hermes runs it at once, no card). A phone that holds back what is personal while locked asks as Auto does.
 */
internal fun callApprovalMode(choice: dev.hermes.android.phone.ApprovalChoice, heldBack: Boolean): String = when {
    choice == dev.hermes.android.phone.ApprovalChoice.Always -> "ask"
    choice == dev.hermes.android.phone.ApprovalChoice.Never && !heldBack -> "owner"
    else -> "changes"
}

internal fun approvesItself(choice: dev.hermes.android.phone.ApprovalChoice, locked: Boolean, approval: CallToolApproval): Boolean =
    choice == dev.hermes.android.phone.ApprovalChoice.Never && !locked && approval.phone == null && approval.toolName !in PhoneTools.names

internal fun pendingCallToolApprovals(current: List<CallToolApproval>, event: JSONObject): List<CallToolApproval> = when (event.optString("type")) {
    "tool_approval" -> CallToolApproval.from(event).let { approval ->
        if (current.any { it.requestId == approval.requestId }) current.map { if (it.requestId == approval.requestId) approval else it } else current + approval
    }
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
    // A phone request shows exactly what will run: the headline, who or where, the details and the notes or message.
    approval.phone?.let { return CallApprovalSummary(it.action, it.target, it.preview, "", it.details) }
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
    // A command, a note or a message is shown nearly whole and a clipped preview always says so, so nothing hides behind "Always allow".
    val preview = previewText.clipWithEllipsis(APPROVAL_PREVIEW_MAX)
    val service = approval.toolName.takeIf { it.startsWith("mcp__") }
        ?.removePrefix("mcp__")?.substringBefore("__")?.split('_')
        ?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }.orEmpty()
    val fields = listOf("to", "cc", "bcc", "subject", "query", "title", "name", "filename", "url")
        .mapNotNull { key ->
            val value = arguments.opt(key).takeIf { it is String || it is Number || it is Boolean || it is JSONArray }
                // A list (several recipients) reads as the owner would write it, not as JSON.
                ?.let { if (it is JSONArray) (0 until it.length()).map { index -> it.opt(index).toString() }.filter(String::isNotBlank).joinToString(", ") else it.toString() }
                ?.takeIf(String::isNotBlank)?.clipWithEllipsis(APPROVAL_FIELD_MAX) ?: return@mapNotNull null
            "$key: $value"
        }.joinToString(" · ")
    return CallApprovalSummary(action, target, preview, service, fields)
}

/** The longest preview of a command, note or message an approval shows; only text beyond it is cut, and the cut says so. */
internal const val APPROVAL_PREVIEW_MAX = 1000

/** The longest a field of an approval (a subject, a list of recipients) shows before it ends with an ellipsis. */
internal const val APPROVAL_FIELD_MAX = 400

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

private fun CallToolReceipt.service(): String? = toolName.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")
    ?.split('_')?.joinToString(" ") { it.replaceFirstChar { char -> char.titlecase() } }?.takeIf(String::isNotBlank)

/** What the activity names: the service for an MCP tool, else the tool. It is the {0} of [activityTemplate]. */
internal fun CallToolReceipt.activitySubject(): String = service() ?: toolName.substringAfterLast("__").lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() }

/**
 * The activity with its subject left as {0}, so uiText can translate the wording and the subject goes in after. The card, the overlay's
 * tool line and the call status all read this one wording: a service is "Using", never "Checking", because the tool may write as well as read.
 */
internal fun CallToolReceipt.activityTemplate(): String? {
    if (status !in setOf("ready", "running")) return null
    val tool = toolName.substringAfterLast("__").lowercase()
    return when {
        service() != null -> "Using {0}…"
        tool in setOf("write_file", "create_file") -> "Writing a file…"
        tool in setOf("edit_file", "patch_file") -> "Updating a file…"
        tool in setOf("read_file", "list_directory") -> "Reading files…"
        else -> "Using {0}…"
    }
}

internal fun CallToolReceipt.activityLabel(): String? = activityTemplate()?.replace("{0}", activitySubject())

/** The same wording as [activityTemplate]: the status line and the tool card never differ in their verb. */
internal fun CallToolReceipt.statusTemplate(): String? = activityTemplate()

internal fun CallToolReceipt.statusLabel(): String? = statusTemplate()?.replace("{0}", activitySubject())

/** The receipt texts that reach uiText whole or as templates; the tool chip's "<tool> complete" forms are built from the tool's own name. */
internal val CallToolStrings = listOf(
    "Using {0}…", "Writing a file…", "Updating a file…", "Reading files…",
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
