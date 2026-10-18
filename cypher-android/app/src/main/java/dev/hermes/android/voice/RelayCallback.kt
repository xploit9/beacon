package dev.hermes.android.voice

import java.util.UUID
import org.json.JSONObject

/*
 * Adapted from Hermes Relay IncomingVoiceCall.kt, IncomingVoiceCallHandler.kt and
 * models/Envelope.kt. Cypher changes use org.json and explicit trusted scope binding.
 * MIT License
 * Copyright (c) 2026 Axiom Labs
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 * The surrounding Cypher repository retains its GPL-3.0-only license and notices;
 * see cypher-android/LICENSE and cypher-android/THIRD-PARTY-NOTICES.md.
 */

data class RelayCallbackEnvelope(val type: String, val id: String?, val payload: JSONObject)

object RelayCallbackWire {
    fun parse(message: JSONObject): RelayCallbackEnvelope? {
        if (message.opt("channel") != "proactive") return null
        val type = message.opt("type") as? String ?: return null
        if (type !in setOf("phone.call.invite", "phone.call.action", "phone.call.state", "phone.call.error")) return null
        val id = message.opt("id") as? String
        if (message.has("id") && (id == null || id.isBlank() || id.length > 256)) return null
        return RelayCallbackEnvelope(type, id, message.opt("payload") as? JSONObject ?: return null)
    }

    fun envelope(type: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().put("channel", "proactive").put("type", type).put("id", id).put("payload", payload)
}

enum class RelayCallbackPhase { Ringing, Answering, Accepted, Starting, Connected, Finished }

data class RelayCallback(
    val callId: String, val binding: RelayCallbackBinding, val title: String, val reason: String,
    val createdAt: Long, val expiresAt: Long, val arrivedAt: Long, val arrivedElapsed: Long,
    val ringDeadlineElapsed: Long, val actionToken: String = UUID.randomUUID().toString(),
    val phase: RelayCallbackPhase = RelayCallbackPhase.Ringing, val startDeadlineElapsed: Long = 0,
) {
    fun remainingRingMillis(now: Long, elapsed: Long): Long {
        if (now < arrivedAt || elapsed < arrivedElapsed) return 0
        return minOf(expiresAt - now, ringDeadlineElapsed - elapsed, RING_WINDOW_MS).coerceAtLeast(0)
    }

    companion object {
        const val RING_WINDOW_MS = 20_000L
        const val START_WINDOW_MS = 30_000L
        const val MAX_CLOCK_SKEW_MS = 5_000L
        internal val TERMINAL = setOf("declined", "cancelled", "missed", "failed", "ended")
        private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val EVENT_ID_PATTERN = Regex("[0-9a-fA-F]{32}")

        internal fun callId(value: Any?): String? = (value as? String)
            ?.let {
                when {
                    UUID_PATTERN.matches(it) -> UUID.fromString(it).toString()
                    EVENT_ID_PATTERN.matches(it) -> it.lowercase()
                    else -> null
                }
            }

        fun parse(payload: JSONObject, binding: RelayCallbackBinding, now: Long, elapsed: Long): RelayCallback? {
            val id = callId(payload.opt("call_id")) ?: return null
            if (!binding.valid || payload.opt("device_id") != binding.deviceId) return null
            fun string(key: String): String? = payload.opt(key) as? String
            for (key in listOf("profile", "title", "reason")) {
                if (payload.has(key) && string(key) == null) return null
            }
            val profile = string("profile") ?: "default"
            val session = payload.opt("session_id").takeUnless { it == JSONObject.NULL }
            if (session != null && session !is String) return null
            if (profile != binding.profile || session != binding.sessionId) return null
            // getLong/optLong coerce strings and truncate decimals; wire timestamps must be integers.
            fun millis(key: String): Long? = when (val value = payload.opt(key)) {
                is Long -> value
                is Int -> value.toLong()
                else -> null
            }
            val created = millis("created_at") ?: return null
            val expires = millis("expires_at") ?: return null
            if (now < 0 || elapsed < 0 || elapsed > Long.MAX_VALUE - START_WINDOW_MS || created < 0 ||
                created > Long.MAX_VALUE - RING_WINDOW_MS || created - now > MAX_CLOCK_SKEW_MS ||
                expires != created + RING_WINDOW_MS || expires <= now || string("status") != "ringing") return null
            return RelayCallback(id, binding, string("title")?.take(120)?.ifBlank { "Hermes" } ?: "Hermes",
                string("reason")?.take(1000).orEmpty(), created, expires, now, elapsed,
                elapsed + (expires - now).coerceAtMost(RING_WINDOW_MS))
        }
    }
}

/** Effects only: the reducer owns no Android service, notification, permission prompt or microphone. */
data class RelayCallbackTransition(
    val call: RelayCallback, val outgoing: JSONObject? = null, val stopAlerting: Boolean = false,
    val stopVoice: Boolean = false, val missed: Boolean = false,
)

/**
 * One instance per authenticated pairing lifetime; keep it across socket reconnects.
 * Serialize effects with the existing call controller. Retired UUIDs are never evicted.
 * shortcut: at 256 invitations fail closed until re-pairing; add durable replay storage
 * before supporting more invitations per pairing lifetime.
 * Process death retires all local action tokens; never restore an Answer/Accepted state.
 */
class RelayCallbackProtocol {
    @Volatile var current: RelayCallback? = null
        private set
    private val seen = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    private var answerId: String? = null
    private var startupDispatched = false

    private fun authorized(call: RelayCallback, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?): Boolean =
        binding != null && binding.valid && binding == call.binding &&
            RelayCallbackPolicy.callsAllowed(prefs, binding.connectionId)

    private fun action(call: RelayCallback, verb: String): JSONObject = RelayCallbackWire.envelope(
        "phone.call.action", JSONObject().put("call_id", call.callId).put("action", verb),
    ).also { if (current?.actionToken == call.actionToken && call.phase != RelayCallbackPhase.Finished) pending.add(it.getString("id")) }

    private fun finish(call: RelayCallback, verb: String? = null, missed: Boolean = false): RelayCallbackTransition {
        val ended = call.copy(phase = RelayCallbackPhase.Finished)
        current = ended
        pending.clear(); answerId = null; startupDispatched = false
        return RelayCallbackTransition(ended, verb?.let { action(ended, it) }, stopAlerting = true,
            stopVoice = call.phase in setOf(RelayCallbackPhase.Starting, RelayCallbackPhase.Connected), missed = missed)
    }

    /** Call on sign-out, re-pairing, scope changes and opt-out, even without incoming traffic. */
    @Synchronized fun invalidate(binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?): RelayCallbackTransition? {
        val call = current?.takeUnless { it.phase == RelayCallbackPhase.Finished } ?: return null
        if (binding == null || !binding.valid || binding != call.binding) return finish(call, missed = call.phase == RelayCallbackPhase.Ringing)
        if (prefs == null) return null
        if (RelayCallbackPolicy.callsAllowed(prefs, binding.connectionId)) return null
        return finish(call, missed = call.phase == RelayCallbackPhase.Ringing)
    }

    @Synchronized fun receive(message: JSONObject, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        now: Long, elapsed: Long): RelayCallbackTransition? {
        invalidate(binding, prefs)?.let { return it }
        if (binding == null || !binding.valid || !RelayCallbackPolicy.callsAllowed(prefs, binding.connectionId)) return null
        val envelope = RelayCallbackWire.parse(message) ?: return null
        if (envelope.type == "phone.call.invite") {
            val call = RelayCallback.parse(envelope.payload, binding, now, elapsed) ?: return null
            if (seen.size >= 256 || !seen.add(call.callId)) return null
            if (current?.phase?.let { it != RelayCallbackPhase.Finished } == true) {
                return RelayCallbackTransition(call.copy(phase = RelayCallbackPhase.Finished), action(call, "decline"))
            }
            current = call
            return RelayCallbackTransition(call, action(call, "ringing"))
        }
        val call = current?.takeUnless { it.phase == RelayCallbackPhase.Finished } ?: return null
        expire(now, elapsed, binding, prefs)?.let { return it }
        val payload = envelope.payload
        // State inherits the trusted invitation binding; supplied scope fields must also agree.
        if (payload.has("profile") && payload.opt("profile") != call.binding.profile ||
            payload.has("session_id") && payload.opt("session_id").takeUnless { it == JSONObject.NULL } != call.binding.sessionId ||
            payload.has("device_id") && payload.opt("device_id") != call.binding.deviceId ||
            payload.has("call_id") && RelayCallback.callId(payload.opt("call_id")) != call.callId) return null
        if (envelope.type == "phone.call.error") {
            return if (envelope.id != null && pending.remove(envelope.id)) finish(call) else null
        }
        if (envelope.type != "phone.call.state" || RelayCallback.callId(payload.opt("call_id")) != call.callId ||
            payload.opt("device_id") != call.binding.deviceId) return null
        val status = payload.opt("status") as? String ?: return null
        if (status in RelayCallback.TERMINAL) return finish(call, missed = status == "missed")
        if (status == "accepted" && call.phase == RelayCallbackPhase.Answering && envelope.id == answerId) {
            envelope.id?.let { pending.remove(it) }
            val accepted = call.copy(phase = RelayCallbackPhase.Accepted)
            current = accepted
            return RelayCallbackTransition(accepted)
        }
        return null
    }

    @Synchronized fun answer(id: String?, token: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        now: Long, elapsed: Long): RelayCallbackTransition? {
        val call = current ?: return null
        val retrying = call.phase == RelayCallbackPhase.Answering
        val canAnswer = when (call.phase) {
            RelayCallbackPhase.Ringing -> call.remainingRingMillis(now, elapsed) > 0L &&
                elapsed <= Long.MAX_VALUE - RelayCallback.START_WINDOW_MS
            RelayCallbackPhase.Answering -> now >= call.arrivedAt && elapsed >= call.arrivedElapsed &&
                elapsed < call.startDeadlineElapsed
            else -> false
        }
        if (id != call.callId || token != call.actionToken || !authorized(call, binding, prefs) || !canAnswer) return null
        if (retrying) return RelayCallbackTransition(call)
        val answering = call.copy(phase = RelayCallbackPhase.Answering,
            startDeadlineElapsed = elapsed + RelayCallback.START_WINDOW_MS)
        current = answering
        val outgoing = action(answering, "answer")
        answerId = outgoing.getString("id")
        return RelayCallbackTransition(answering, outgoing, stopAlerting = true)
    }

    @Synchronized fun decline(id: String?, token: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        now: Long, elapsed: Long): RelayCallbackTransition? {
        val call = current ?: return null
        val canDecline = when (call.phase) {
            RelayCallbackPhase.Ringing -> call.remainingRingMillis(now, elapsed) > 0L
            RelayCallbackPhase.Answering -> now >= call.arrivedAt && elapsed >= call.arrivedElapsed && elapsed < call.startDeadlineElapsed
            else -> false
        }
        if (id != call.callId || token != call.actionToken || !authorized(call, binding, prefs) ||
            !canDecline) return null
        return finish(call, "decline")
    }

    @Synchronized fun expire(now: Long, elapsed: Long, binding: RelayCallbackBinding?,
        prefs: RelayCallbackPreferences?): RelayCallbackTransition? {
        invalidate(binding, prefs)?.let { return it }
        val call = current ?: return null
        if (call.phase == RelayCallbackPhase.Ringing && call.remainingRingMillis(now, elapsed) == 0L) return finish(call, missed = true)
        if (call.phase in setOf(RelayCallbackPhase.Answering, RelayCallbackPhase.Accepted, RelayCallbackPhase.Starting) &&
            (now < call.arrivedAt || elapsed < call.arrivedElapsed || elapsed >= call.startDeadlineElapsed)) {
            return finish(call, if (authorized(call, binding, prefs)) "failed" else null)
        }
        return null
    }

    @Synchronized fun permissionTarget(pendingId: String?, navigationHydrated: Boolean, binding: RelayCallbackBinding?,
        prefs: RelayCallbackPreferences?, now: Long, elapsed: Long): String? {
        val call = current ?: return null
        return pendingId?.takeIf { it == call.callId && navigationHydrated && authorized(call, binding, prefs) &&
            call.phase == RelayCallbackPhase.Accepted && now >= call.arrivedAt &&
            elapsed >= call.arrivedElapsed && elapsed < call.startDeadlineElapsed }
    }

    /** Reserve once after Accepted + permission + trusted conversation validation, before foreground launch. */
    @Synchronized fun beginStart(id: String?, token: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        permission: Boolean, ownerReady: Boolean, now: Long, elapsed: Long): Boolean {
        val call = current ?: return false
        if (!permission || !ownerReady || token != call.actionToken ||
            permissionTarget(id, true, binding, prefs, now, elapsed) == null) return false
        current = call.copy(phase = RelayCallbackPhase.Starting)
        return true
    }

    @Synchronized fun canContinue(id: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        permission: Boolean, ownerReady: Boolean, now: Long, elapsed: Long): Boolean {
        val call = current ?: return false
        return id == call.callId && authorized(call, binding, prefs) && permission && ownerReady &&
            now >= call.arrivedAt && elapsed >= call.arrivedElapsed &&
            (call.phase == RelayCallbackPhase.Connected || call.phase == RelayCallbackPhase.Starting && elapsed < call.startDeadlineElapsed)
    }

    /** Invoke only after existing foreground/admission protection is ready; true dispatches capture once. */
    @Synchronized fun foregroundReady(id: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        permission: Boolean, ownerReady: Boolean, now: Long, elapsed: Long): Boolean {
        if (current?.phase != RelayCallbackPhase.Starting || startupDispatched ||
            !canContinue(id, binding, prefs, permission, ownerReady, now, elapsed)) return false
        startupDispatched = true
        return true
    }

    @Synchronized fun captureStarted(id: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        permission: Boolean, ownerReady: Boolean, now: Long, elapsed: Long): RelayCallbackTransition? {
        val call = current ?: return null
        if (!startupDispatched || call.phase != RelayCallbackPhase.Starting ||
            !canContinue(id, binding, prefs, permission, ownerReady, now, elapsed)) return null
        val connected = call.copy(phase = RelayCallbackPhase.Connected)
        current = connected
        return RelayCallbackTransition(connected, action(connected, "started"))
    }

    /** Local teardown remains possible after authority loss; never send on a foreign socket. */
    @Synchronized fun end(id: String?, token: String?, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?): RelayCallbackTransition? {
        val call = current ?: return null
        if (id != call.callId || token != call.actionToken || call.phase == RelayCallbackPhase.Finished) return null
        val verb = if (!authorized(call, binding, prefs)) null else if (call.phase == RelayCallbackPhase.Connected) "hangup" else "failed"
        return finish(call, verb)
    }
}
