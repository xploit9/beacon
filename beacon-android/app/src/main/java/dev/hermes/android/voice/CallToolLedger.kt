package dev.hermes.android.voice

import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

/**
 * The call's tool requests: one request_id per operation, its immutable tool and arguments, and the newest receipt Hermes gave.
 * Reading a receipt never repeats the invoke; an identical operation still pending is not sent twice.
 */
internal class DirectGatewayReceipts {
    private class Request(val functionId: String, val tool: String, val arguments: JSONObject, var receipt: JSONObject) {
        /** When Hermes' receipt said completed, on the monotonic clock. */
        var completedAt = 0L
        var settled = false
        /** A completed read whose result may answer the same request again, until anything that is not a read happens (REVIEW-E3 finding 2). */
        var reusable = false
        // A v3 plugin resolves a name to its tool_id: the first receipt that carries one pins it.
        var toolId: String? = tool.takeIf { it.matches(TOOL_ID) }
    }
    private val requests = linkedMapOf<String,Request>()
    @Synchronized fun reserve(requestId: String, functionId: String, tool: String, arguments: JSONObject): JSONObject? {
        require(requestId !in requests)
        val encoded = canonical(arguments)
        val pending = requests.values.firstOrNull { it.tool == tool && canonical(it.arguments) == encoded && it.receipt.optString("status") in setOf("ready","running","needs_approval","uncertain") }
        if (pending != null) return JSONObject().put("status","already_requested").put("request_id",pending.receipt.getString("request_id"))
            .put("current_status",pending.receipt.getString("status"))
        // The model asked again for a read it got moments ago (device round 1: the same search 3 s later, a second card): it gets that result.
        val now = System.nanoTime() / 1_000_000
        val done = requests.values.lastOrNull { it.tool == tool && canonical(it.arguments) == encoded && it.receipt.optString("status") == "completed" &&
            it.reusable && now - it.completedAt < REPEATED_READ_MS }
        if (done != null) return JSONObject(done.receipt.toString()).put("repeated_request",true)
        require(requests.size < 64)
        requests[requestId] = Request(functionId,tool,JSONObject(arguments.toString()),JSONObject().put("request_id",requestId).put("tool_id",tool).put("status","uncertain"))
        return null
    }
    @Synchronized fun record(requestId: String, value: JSONObject): JSONObject {
        val owned = requests[requestId] ?: error("Unknown tool request")
        require(value.optString("request_id") == requestId)
        val toolId = if (value.isNull("tool_id")) "" else value.optString("tool_id")
        if (toolId.isNotEmpty()) { require(owned.toolId == null || owned.toolId == toolId); owned.toolId = toolId }
        require(value.optString("status") in setOf("ready","running","needs_approval","transferred","completed","failed","denied","expired","cancelled","uncertain"))
        if (value.optString("status") == "needs_approval") {
            val proposal = value.getJSONObject("proposal")
            require(proposal.getString("id").matches(Regex("[A-Za-z0-9_-]{32,80}")))
            require(proposal.getString("tool_name") == value.getString("tool_name"))
            // Hermes fills in defaults (a GET method) and types (a number) before it asks: the card shows what will run, and every
            // argument the model gave is in it unchanged. An exact match lost such a request as an unverifiable one (owner's call, 4 Nov 2026).
            val shown = proposal.getJSONObject("arguments")
            require(owned.arguments.keys().asSequence().all { key -> shown.has(key) && sameArgument(owned.arguments.opt(key), shown.opt(key)) })
        }
        if (owned.settled) return JSONObject(owned.receipt.toString())
        owned.receipt = JSONObject(value.toString())
        owned.settled = value.getString("status") in FINAL_RECEIPTS
        // A request that is not read-only may change what a read returns: no earlier read answers again.
        if (!value.optBoolean("read_only")) forgetReads()
        else if (value.optString("status") == "completed") { owned.completedAt = System.nanoTime() / 1_000_000; owned.reusable = true }
        return JSONObject(owned.receipt.toString())
    }
    /** Something that is not a read happened in this call (a write, a hand-over, an approval decision): every read is asked of Hermes again. */
    @Synchronized fun forgetReads() { for (request in requests.values) request.reusable = false }
    @Synchronized fun decision(requestId: String, proposalId: String): JSONObject {
        val current = requests[requestId]?.receipt ?: error("Unknown tool request")
        require(current.optString("status") == "needs_approval" && current.getJSONObject("proposal").getString("id") == proposalId) { "Tool approval changed or ended" }
        return JSONObject(current.toString())
    }
    @Synchronized fun receipt(requestId: String): JSONObject? = requests[requestId]?.receipt?.let { JSONObject(it.toString()) }
    @Synchronized fun functionId(requestId: String): String? = requests[requestId]?.functionId
    @Synchronized fun settled(requestId: String) = requests[requestId]?.settled == true
    @Synchronized fun pending(): List<String> = requests.filterValues { !it.settled }.keys.toList()
    /** One argument as the model gave it and as Hermes will run it: equal, or the same plain value after Hermes typed it ("5" and 5). */
    private fun sameArgument(given: Any?, shown: Any?): Boolean = canonical(given) == canonical(shown) ||
        (given !is JSONObject && given !is JSONArray && shown !is JSONObject && shown !is JSONArray && given.toString() == shown.toString())
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",","{","}") { JSONObject.quote(it)+":"+canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",","[","]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> if (value == null || value == JSONObject.NULL) "null" else value.toString()
    }
    private companion object {
        val TOOL_ID = Regex("[a-f0-9]{64}")
        /** How long a completed read answers the same request again: round 1's repeat came 2.9 s after the first (REVIEW-E3 finding 2). */
        const val REPEATED_READ_MS = 10_000L
    }
}

/** Receipt statuses after which nothing about that request changes (contract v3, section 2). */
private val FINAL_RECEIPTS = setOf("completed","failed","denied","expired","cancelled","uncertain")

/** A closed channel means the call ended while the writer waited, so the writer ends like a cancellation, not a crash. */
internal suspend fun <T> nextDirectFrame(controls: kotlinx.coroutines.channels.Channel<T>, microphone: kotlinx.coroutines.channels.Channel<T>, visual: kotlinx.coroutines.channels.Channel<T>): T =
    controls.tryReceive().getOrNull() ?: try {
        kotlinx.coroutines.selects.select<T> { controls.onReceive { it }; microphone.onReceive { it }; visual.onReceive { it } }
    } catch (closed: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
        throw kotlinx.coroutines.CancellationException("The call ended", closed)
    }

/** Attempt deadlines are retryable; parent cancellation and rejected authority are not. */
internal suspend fun <T> directAdmissionAttempt(timeoutMs: Long, terminal: (Exception) -> Boolean = { false }, operation: suspend () -> T): T? {
    val result = try { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { operation() } }
    catch (error: kotlinx.coroutines.TimeoutCancellationException) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); null }
    catch (error: kotlinx.coroutines.CancellationException) { throw error }
    catch (error: Exception) { if (terminal(error)) throw error else null }
    kotlinx.coroutines.currentCoroutineContext().ensureActive()
    return result
}

internal fun directToolMayCancel(stage: String?): Boolean = stage == null || stage in setOf("submitting", "waiting_for_idle", "staging_native")

internal data class NativeVoiceRead(val capability:String,val route:String,val body:JSONObject)

internal fun directToolIsReadOnly(name:String)=name in setOf("discover_tools","tool_status","get_visual_state","hermes_search","hermes_memory","hermes_skills","hermes_context")||PhoneVoiceTools.directReadOnly(name)
