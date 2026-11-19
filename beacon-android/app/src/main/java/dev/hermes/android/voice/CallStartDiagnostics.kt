package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.requestNeverSent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * How long a call's grant may go without an answer before the call says Hermes can't be reached. The plugin answers a grant in
 * well under a second and its answer is one small JSON body, so the first byte and the whole answer arrive together.
 */
internal const val GRANT_REACH_MS = 3_000L
/** A callback's answer does more than a grant and Hermes takes the one-time invitation with it: a slow answer is waited for, as in 1.2.0. */
internal const val CALLBACK_ANSWER_REACH_MS = 15_000L
/** The reach budget of a call's first request: the grant, or the answer to a callback ([callbackAnswer]). */
internal fun callStartReachMs(callbackAnswer: Boolean): Long = if (callbackAnswer) CALLBACK_ANSWER_REACH_MS else GRANT_REACH_MS

/** The grant had no answer within [GRANT_REACH_MS]: from this phone, Hermes cannot be reached right now. */
internal class HermesUnreachable : java.net.SocketTimeoutException("Hermes did not answer the call's grant in time")

/**
 * Sends a call's first request to Hermes. No answer within [reachMs], or a connection that never opened, is reported to
 * [unreachable] and thrown, so the call ends at once saying Hermes can't be reached. Hermes' own refusals pass through.
 */
internal suspend fun reachHermes(reachMs: Long = GRANT_REACH_MS, unreachable: (Throwable) -> Unit = {}, request: suspend () -> JSONObject): JSONObject {
    val failure: Throwable = try {
        return withTimeoutOrNull(reachMs) { request() } ?: throw HermesUnreachable()
    } catch (error: CancellationException) { throw error }
    catch (error: HermesUnreachable) { error }
    catch (error: Throwable) { if (requestNeverSent(error)) error else throw error }
    unreachable(failure)
    throw failure
}

/** What the owner reads when Hermes refuses, never answers, or cannot be reached for a call's admission. */
internal fun grantFailureText(error: Throwable): String = when {
    error is ApiFailure && error.status in setOf(401, 403) -> CALL_SIGN_IN_EXPIRED
    error is ApiFailure && error.status in 400..499 && error.status !in setOf(408, 429) && !error.message.isNullOrBlank() -> error.message.orEmpty()
    error is HermesUnreachable || requestNeverSent(error) -> APP_CONNECTION_ERROR
    else -> CALL_HERMES_NO_ANSWER
}

internal const val CALL_DIAGNOSTICS_PATH = "/api/plugins/beacon/mobile/call-diagnostics"

/** The plugin's call stages (mobile.py CALL_STAGES), in order. */
internal val CALL_STAGE_NAMES = listOf("grant_sent", "grant_ok", "context_sent", "context_ok", "provider_open", "provider_setup_ok", "mic_streaming", "first_model_audio")

/** The stages the diagnostics route takes: the plugin's DIAGNOSTIC_STAGES (mobile.py) and this route's own `reach_failed`. */
internal val START_DIAGNOSTIC_STAGES = setOf("capture_started", "capture_stopped", "playout_started", "playout_stopped", "capture_error", "playout_error",
    "media_timeout", "timestamp_overlap_corrected", "transport_started", "transport_open", "audio_ready", "transport_timeout", "voice_connecting",
    "context_ready", "token_ready", "token_error", "provider_key_ready", "provider_ready", "provider_error", "voice_error", "provider_setup_sent",
    "provider_setup_failed", "reach_failed") + CALL_STAGE_NAMES

private val DIAGNOSTIC_ERROR_CODE = Regex("http_[45][0-9]{2}|timeout|dns|tls|network|invalid_response")
private val CAUSE_CLASSES = setOf("ssl_handshake", "ssl_peer", "ssl_protocol", "ssl_transport", "socket_timeout", "dns", "connect", "eof", "io", "other")
private const val MAX_STAGES = 32

/** A failure's diagnostic code, in the plugin's allow list. */
internal fun diagnosticErrorCode(error: Throwable): String = when (error) {
    is ApiFailure -> if (error.status in 400..599) "http_${error.status}" else "invalid_response"
    is HermesUnreachable, is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> "timeout"
    is java.net.UnknownHostException -> "dns"
    is javax.net.ssl.SSLException -> "tls"
    is java.io.IOException -> "network"
    else -> "invalid_response"
}

/** The key of the failure line a start showed (contract section 5 `shown`); [line] is the line before translation. */
internal fun shownKey(line: String): String = when (line) {
    APP_CONNECTION_ERROR -> "hermes_unreachable"
    CALL_HERMES_NO_ANSWER -> "hermes_no_answer"
    CALL_VOICE_NO_ANSWER, CALL_VOICE_STOPPED -> "voice_no_answer"
    CALL_MIC_FAILED -> "mic_failed"
    CALL_SIGN_IN_EXPIRED -> "sign_in_expired"
    else -> "start_failed"
}

/** How a start ended: every start stage reached is a connected call; otherwise a failure, or the owner's own hang-up. */
internal fun startOutcome(stages: List<JSONObject>, failed: Boolean): String = when {
    CALL_START_STAGES.all { name -> stages.any { it.optString("stage") == name } } -> "connected"
    failed -> "failed"
    else -> "cancelled"
}

/** The stage a start that did not connect stopped at: the one its failure names, else the first it never reached. */
internal fun startFailedStage(stages: List<JSONObject>): String? =
    stages.lastOrNull { it.optString("stage") == "voice_error" }?.optString("failed_stage")?.takeIf { it in CALL_STAGE_NAMES }
        ?: CALL_START_STAGES.firstOrNull { name -> stages.none { it.optString("stage") == name } }

private fun startStage(value: JSONObject): JSONObject? {
    val stage = value.optString("stage").takeIf { it in START_DIAGNOSTIC_STAGES } ?: return null
    val elapsed = value.optLong("elapsed_ms", -1).takeIf { it >= 0 } ?: return null
    return JSONObject().put("stage", stage).put("elapsed_ms", elapsed).apply {
        value.optString("error_code").takeIf { DIAGNOSTIC_ERROR_CODE.matches(it) }?.let { put("error_code", it) }
        value.optString("cause_class").takeIf { it in CAUSE_CLASSES }?.let { put("cause_class", it) }
    }
}

/**
 * One call start as the diagnostics route stores it (contract section 5): stages and outcome only. No words, audio, tool names,
 * addresses or identifiers beyond the call's own id go into it.
 */
internal fun callStartRow(eventId: String, callId: String?, tappedAtEpochMs: Long, provider: String, trigger: String, appVersion: String,
    contract: Int, stages: List<JSONObject>, failed: Boolean, shownLine: String?): JSONObject {
    val clean = stages.mapNotNull(::startStage).let { if (it.size <= MAX_STAGES) it else it.take(MAX_STAGES / 2) + it.takeLast(MAX_STAGES / 2) }
    val outcome = startOutcome(stages, failed)
    return JSONObject().put("client_event_id", eventId).put("call_id", callId?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
        .put("tapped_at", tappedAtEpochMs).put("provider", provider.takeIf { it in setOf("gemini", "grok", "openai") } ?: "gemini")
        .put("trigger", trigger.takeIf { it in setOf("tap", "side_button", "callback_answer", "overlay") } ?: "tap")
        .put("app_version", appVersion.take(32)).put("call_contract", contract).put("outcome", outcome)
        .apply {
            if (outcome != "connected") startFailedStage(stages)?.let { put("failed_stage", it) }
            if (outcome == "failed") put("shown", shownKey(shownLine.orEmpty()))
        }
        .put("stages", JSONArray(clean))
}

/** The contract version and whether start diagnostics are taken, as a pre-call or call context advertises them (contract section 0). */
internal fun takesStartDiagnostics(context: JSONObject): Boolean =
    context.optInt("call_contract", 0) >= 3 && context.optJSONObject("call_capabilities")?.optBoolean("call_diagnostics") == true

/**
 * Call-start rows kept on the phone until a Hermes that takes them has them: at most [maxRows] for [maxAgeMs], per account and
 * profile. Rows of a Hermes that never advertised the route wait; nothing probes for it.
 */
internal class CallStartDiagnostics(private val file: File, private val now: () -> Long = System::currentTimeMillis,
    private val maxRows: Int = 50, private val maxAgeMs: Long = 7 * 24 * 3_600_000L) {
    private val lock = Any()
    private val sending = Mutex()
    private val capable = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private fun key(scope: Scope) = scope.copy(session = "").key

    private fun load(): MutableList<JSONObject> {
        val rows = runCatching { JSONArray(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return mutableListOf()
        val at = now()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("scope").isNotBlank() && it.optJSONObject("row") != null && at - it.optLong("saved_at") in 0..maxAgeMs }
            .takeLast(maxRows).toMutableList()
    }
    private fun save(rows: List<JSONObject>) {
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(JSONArray(rows).toString(), Charsets.UTF_8)
        try { java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
        catch (_: java.io.IOException) { java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
    }

    fun record(scope: Scope, row: JSONObject) = synchronized(lock) {
        save((load() + JSONObject().put("scope", key(scope)).put("saved_at", now()).put("row", row)).takeLast(maxRows))
    }
    fun pending(scope: Scope): List<JSONObject> = synchronized(lock) { key(scope).let { k -> load().filter { it.optString("scope") == k }.map { it.getJSONObject("row") } } }
    private fun remove(scope: Scope, ids: Set<String>) = synchronized(lock) {
        val k = key(scope)
        save(load().filterNot { it.optString("scope") == k && it.getJSONObject("row").optString("client_event_id") in ids })
    }

    /** What a context read from [scope]'s Hermes says about this route; remembered while the app runs. */
    fun learned(scope: Scope, context: JSONObject) { capable[key(scope)] = if (takesStartDiagnostics(context)) context.optInt("call_contract") else 0 }
    /** The call contract [scope]'s Hermes last advertised; 2 when none is known. */
    fun contract(scope: Scope): Int = capable[key(scope)]?.takeIf { it >= 3 } ?: 2
    fun takes(scope: Scope): Boolean = (capable[key(scope)] ?: 0) >= 3

    /**
     * Sends [scope]'s rows in batches of at most 20 rows and 16 KiB. True once none is left; false when Hermes is not known to
     * take them or a batch did not arrive (its rows stay for the next try). A batch Hermes refuses as invalid is dropped.
     */
    suspend fun flush(scope: Scope, request: suspend (String, String, JSONObject?) -> JSONObject, timeoutMs: Long = 5_000): Boolean = sending.withLock {
        var sent = true
        while (sent) {
            if (!takes(scope)) return@withLock pending(scope).isEmpty()
            val batch = batch(pending(scope))
            if (batch.isEmpty()) return@withLock true
            sent = try { withTimeoutOrNull(timeoutMs) { request(CALL_DIAGNOSTICS_PATH, "POST", JSONObject().put("diagnostics", JSONArray(batch))) }?.has("stored") == true }
                catch (error: CancellationException) { throw error }
                // Refused as invalid: retrying the same rows can never succeed.
                catch (error: ApiFailure) { error.status in setOf(400, 413, 422) }
                catch (_: Exception) { false }
            if (sent) remove(scope, batch.map { it.optString("client_event_id") }.toSet())
        }
        false
    }

    private fun batch(rows: List<JSONObject>): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var bytes = "{\"diagnostics\":[]}".length
        for (row in rows) {
            val size = row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (out.size == 20 || out.isNotEmpty() && bytes + size > 16_384) break
            out += row; bytes += size
        }
        return out
    }
}

/** A provider's close reason as the call's diagnostics keep it: printable, at most 120 characters. */
internal fun providerCloseReason(reason: String): String? = reason.filter { it >= ' ' && it != '\u007f' }.trim().take(120).ifBlank { null }

/**
 * One provider frame for a debug build's frame log: its kind, tool responses (id, status, scheduling, willContinue), tool calls, turn
 * flags, transcription text and text parts (with their thought flag), never audio.
 */
internal fun voiceFrameSummary(direction: String, frame: org.json.JSONObject, atMs: Long): org.json.JSONObject {
    val out = org.json.JSONObject().put("dir", direction).put("t", atMs).put("keys", org.json.JSONArray(frame.keys().asSequence().toList()))
    frame.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.let { rows ->
        out.put("responses", org.json.JSONArray((0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.map { row ->
            val response = row.optJSONObject("response") ?: org.json.JSONObject()
            org.json.JSONObject().put("id", row.optString("id").take(64)).put("name", row.optString("name").take(80)).put("status", response.optString("status"))
                .put("scheduling", row.optString("scheduling")).put("willContinue", row.opt("willContinue") ?: org.json.JSONObject.NULL)
                .put("detail", response.optString("detail").take(80))
        }))
    }
    frame.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { calls ->
        out.put("calls", org.json.JSONArray((0 until calls.length()).mapNotNull { calls.optJSONObject(it) }.map { call ->
            org.json.JSONObject().put("id", call.optString("id").take(64)).put("name", call.optString("name").take(80)).put("args", call.optJSONObject("args")?.toString()?.take(300) ?: "")
        }))
    }
    frame.optJSONObject("clientContent")?.let { out.put("client_text", it.toString().take(300)) }
    frame.optJSONObject("serverContent")?.let { content ->
        for (flag in listOf("turnComplete", "interrupted", "generationComplete", "waitingForInput")) if (content.optBoolean(flag)) out.put(flag, true)
        content.optString("turnCompleteReason").takeIf(String::isNotBlank)?.let { out.put("turnCompleteReason", it) }
        content.optString("interactionStatus").takeIf(String::isNotBlank)?.let { out.put("interactionStatus", it) }
        content.optJSONObject("outputTranscription")?.optString("text")?.let { out.put("said", it.take(300)) }
        content.optJSONObject("inputTranscription")?.optString("text")?.let { out.put("heard", it.take(300)) }
        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            var audio = 0
            val texts = org.json.JSONArray()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("inlineData")?.let { audio += it.optString("data").length }
                part.optString("text").takeIf(String::isNotBlank)?.let { texts.put(org.json.JSONObject().put("thought", part.optBoolean("thought")).put("text", it.take(300))) }
            }
            if (audio > 0) out.put("audio_b64", audio)
            if (texts.length() > 0) out.put("texts", texts)
        }
        val known = setOf("turnComplete", "interrupted", "generationComplete", "waitingForInput", "turnCompleteReason", "interactionStatus", "outputTranscription", "inputTranscription", "modelTurn")
        val other = content.keys().asSequence().filter { it !in known }.toList()
        if (other.isNotEmpty()) out.put("other", org.json.JSONArray(other))
    }
    frame.optJSONObject("goAway")?.let { out.put("goAway", it.toString().take(120)) }
    frame.optJSONObject("sessionResumptionUpdate")?.let { out.put("resumable", it.optBoolean("resumable")).put("handle", it.optString("newHandle").isNotBlank()) }
    frame.optJSONObject("usageMetadata")?.let { out.put("usage", it.toString().take(200)) }
    if (frame.has("setup")) out.put("setup_resume", frame.optJSONObject("setup")?.optJSONObject("sessionResumption")?.has("handle") == true)
    return out
}

/**
 * The model's notes in its transcript, left out of the captions and the chat. Speech transcription never carries markdown: device round 2
 * got "*   **Analysis:** ...", the final live check "---" and a blank line. The notes start at a mark (bold, a rule, a blank line; a list
 * bullet just before it goes too) and run to where the model's text runs into its speech with no space after a sentence's end
 * ("...in the chat.Actually, ..."), to a sentence's end, spaces or line breaks and a capital or a non-Latin letter with no mark between
 * (REVIEW-E4-2 P3-C), or to the turn's end. Words before the mark stay (REVIEW-E4 P3-5), and a mark split across two pieces
 * is still found (the piece's last characters wait for the next one). Audio is not touched. Speech is never eaten, even at the cost of a
 * note shown: "...look in the notes. I found three notes." lost the spoken sentence, and device round 2's notes, which ran on over several
 * sentences (". I need to tell the user ..."), now show from their second sentence on.
 */
internal class NotesFilter {
    private var carry = ""
    private var inNotes = false
    private var sentenceEnded = false
    private var spaced = false

    fun text(piece: String): String {
        val s = carry + piece
        carry = ""
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (inNotes) {
                val c = s[i]
                // Spaces and line breaks after a sentence's end are no mark: speech after them is never eaten. A bullet or bold is one.
                if (sentenceEnded && c.isLetter() && (!spaced || c.isUpperCase() || !latin(c))) {
                    inNotes = false; sentenceEnded = false; spaced = false; continue
                }
                if (sentenceEnded && c.isWhitespace()) { spaced = true; i++; continue }
                sentenceEnded = c in ".!?"; spaced = false
                i++; continue
            }
            val mark = MARKS.firstOrNull { s.startsWith(it, i) }
            if (mark != null) {
                while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '*' || out.last() == '\t')) out.setLength(out.length - 1)
                inNotes = true; sentenceEnded = false; spaced = false; i += mark.length; continue
            }
            val rest = s.substring(i)
            if (rest.length < 3 && MARKS.any { it.length > rest.length && it.startsWith(rest) }) { carry = rest; break }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** The turn ended: its notes end with it, and a held piece that never became a mark (a lone "*" or "-") goes. */
    fun turnEnded() { carry = ""; inNotes = false; sentenceEnded = false; spaced = false }

    private fun latin(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    private companion object { val MARKS = listOf("**", "---", "\n\n") }
}

/** A provider frame that carries only the model's audio (the frame log keeps the rest). */
internal fun voiceAudioOnly(frame: org.json.JSONObject): Boolean {
    if (frame.length() != 1) return false
    val content = frame.optJSONObject("serverContent") ?: return false
    if (content.length() != 1) return false
    val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return false
    return (0 until parts.length()).all { parts.optJSONObject(it)?.let { part -> part.length() == 1 && part.has("inlineData") } == true }
}
