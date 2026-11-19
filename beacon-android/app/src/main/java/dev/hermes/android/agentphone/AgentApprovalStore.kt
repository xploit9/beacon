package dev.hermes.android.agentphone

import dev.hermes.android.core.json
import dev.hermes.android.data.AgentPhoneEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The approvals routes (approvals-v1 §6, mounted by L2), relative to AGENT_PHONE_PATH as L4's AgentPhoneEndpoint takes them. */
internal const val APPROVALS_ROUTE = "/approvals"
internal const val ACTIVITY_ROUTE = "/activity"

/** As L4's AgentPhoneApi.CALL_MS: one route call waits at most this long. */
internal const val APPROVAL_CALL_MS = 20_000L

/** A record waiting on this phone, with the account it belongs to ("<origin>\u0000<owner>": the records are the owner's, any profile). */
internal data class HeldApproval(val account: String, val record: AgentApproval)

/** What a read of the open records came to. [supported] is false when this Hermes has no approvals route (an older plugin). */
internal data class ApprovalsRead(val fresh: List<AgentApproval>, val gone: List<String>, val reached: Boolean, val supported: Boolean = true)

/**
 * The records this phone knows about, for every account it has asked for. One read replaces an account's list; an answer removes
 * the record once the plugin has it, or once it ended or was answered elsewhere. [later] holds the records the owner closed without
 * answering: they stay in the list (and in the notification shade) but don't open the dialog again by themselves.
 */
internal class AgentApprovalBoard(private val clock: () -> Long) {
    private val lock = Mutex()
    private val _held = MutableStateFlow<List<HeldApproval>>(emptyList())
    val held: StateFlow<List<HeldApproval>> = _held.asStateFlow()
    private val _later = MutableStateFlow<Set<String>>(emptySet())
    val later: StateFlow<Set<String>> = _later.asStateFlow()
    /** Ids an answer is on its way for: a second tap, or a tap on the next card in the same instant, never sends a second answer. */
    private val answering = HashSet<String>()

    /** Reads the account's open records. A plugin without the route (404) has none; no answer leaves the list as it was. */
    suspend fun refresh(account: String, endpoint: AgentPhoneEndpoint): ApprovalsRead = lock.withLock {
        val answer = endpoint.call("GET", APPROVALS_ROUTE, null, APPROVAL_CALL_MS)
        val records = when {
            answer.ok -> parseAgentApprovals(answer.body, clock())
            answer.status == 404 || answer.status == 403 -> emptyList()
            else -> return@withLock ApprovalsRead(emptyList(), emptyList(), reached = false)
        }
        val before = _held.value.filter { it.account == account }
        val fresh = records.filter { record -> before.none { it.record.id == record.id } }
        val gone = before.map { it.record.id }.filter { id -> records.none { it.id == id } }
        // A record keeps the deadline it was first read with: a later read can only end it, never stretch it.
        val kept = records.map { record -> before.firstOrNull { it.record.id == record.id }?.record?.let { record.copy(deadline = minOf(it.deadline, record.deadline)) } ?: record }
        _held.value = _held.value.filter { it.account != account } + kept.map { HeldApproval(account, it) }
        _later.value = _later.value - gone.toSet()
        ApprovalsRead(fresh, gone, reached = true, supported = answer.status != 404)
    }

    /**
     * The owner's answer to [record]: yes or no, with the code for a yes to a vault code. The code is sent once and dropped here; the
     * caller clears its field. Null when an answer for this record is already on its way.
     */
    suspend fun decide(account: String, record: AgentApproval, approved: Boolean, endpoint: AgentPhoneEndpoint, code: String? = null): DecisionResult? {
        synchronized(answering) { if (!answering.add(record.id)) return null }
        try {
            if (!APPROVAL_ID.matches(record.id)) return DecisionResult.Ended
            val body = json("approved" to approved)
            if (record.kind == ApprovalKind.Code && approved) {
                if (code == null || !codeReady(code)) return DecisionResult.Invalid
                body.put("code", code)
            }
            val answer = endpoint.call("POST", "$APPROVALS_ROUTE/${record.id}/decision", body, APPROVAL_CALL_MS)
            val result = decisionResult(answer.status, answer.body)
            if (result in setOf(DecisionResult.Sent, DecisionResult.Ended, DecisionResult.Elsewhere)) remove(account, record.id)
            return result
        } finally {
            synchronized(answering) { answering.remove(record.id) }
        }
    }

    /** The record ended on this phone (its time ran out, or Hermes said it is gone): it leaves the list. */
    suspend fun remove(account: String, id: String) = lock.withLock {
        _held.value = _held.value.filterNot { it.account == account && it.record.id == id }
        _later.value = _later.value - id
    }

    /** Closed without an answer: it stays open on Hermes and in the shade, and the dialog doesn't come back for it by itself. */
    fun putOff(id: String) { _later.value = _later.value + id }

    /** Everything of [account] (a sign-out): nothing of that account is shown any more. */
    suspend fun forget(account: String) = lock.withLock { _held.value = _held.value.filter { it.account != account } }

    /** The records of [account] the dialog shows, in the order it asks: decisions first, then Needs-you notices; ended ones left out. */
    fun queue(account: String, now: Long = clock()): List<AgentApproval> =
        _held.value.filter { it.account == account && it.record.deadline > now }.map { it.record }
            .sortedBy { if (it.kind == ApprovalKind.NeedsYou) 1 else 0 }
}

/** How long a card that just took the front stays unanswerable, so the tap that answered the last card can't answer this one. */
internal const val APPROVAL_GUARD_MS = 700L

/** Whether a card shown at [shownAt] may take an answer at [now]. */
internal fun approvalAnswerable(shownAt: Long, now: Long): Boolean = now - shownAt >= APPROVAL_GUARD_MS
