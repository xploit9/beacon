package dev.hermes.android.voice

import java.util.UUID
import org.json.JSONObject

/*
 * Adapted from Hermes Relay IncomingVoiceCall.kt, IncomingVoiceCallHandler.kt and
 * models/Envelope.kt. Beacon changes use org.json and explicit trusted scope binding.
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
 * The surrounding Beacon repository retains its GPL-3.0-only license and notices;
 * see beacon-android/LICENSE and beacon-android/THIRD-PARTY-NOTICES.md.
 */

data class RelayCallbackEnvelope(val type: String, val id: String?, val payload: JSONObject)

object RelayCallbackWire {
    fun parse(message: JSONObject): RelayCallbackEnvelope? {
        if (message.opt("channel") != "proactive") return null
        val type = message.opt("type") as? String ?: return null
        if (type != "phone.call.invite") return null
        val id = message.opt("id") as? String
        if (message.has("id") && (id == null || id.isBlank() || id.length > 256)) return null
        return RelayCallbackEnvelope(type, id, message.opt("payload") as? JSONObject ?: return null)
    }

    fun envelope(type: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): JSONObject =
        JSONObject().put("channel", "proactive").put("type", type).put("id", id).put("payload", payload)
}

enum class RelayCallbackPhase { Ringing, Finished }

data class RelayCallback(
    val callId: String, val binding: RelayCallbackBinding, val title: String, val reason: String,
    val expiresAt: Long, val arrivedAt: Long, val arrivedElapsed: Long,
    val ringDeadlineElapsed: Long, val actionToken: String = UUID.randomUUID().toString(),
    val phase: RelayCallbackPhase = RelayCallbackPhase.Ringing,
) {
    fun remainingRingMillis(now: Long, elapsed: Long): Long {
        if (now < arrivedAt || elapsed < arrivedElapsed) return 0
        return minOf(expiresAt - now, ringDeadlineElapsed - elapsed, RING_WINDOW_MS).coerceAtLeast(0)
    }

    companion object {
        const val RING_WINDOW_MS = 20_000L
        const val MAX_CLOCK_SKEW_MS = 5_000L
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
            if (now < 0 || elapsed < 0 || elapsed > Long.MAX_VALUE - RING_WINDOW_MS || created < 0 ||
                created > Long.MAX_VALUE - RING_WINDOW_MS || created - now > MAX_CLOCK_SKEW_MS ||
                expires != created + RING_WINDOW_MS || expires <= now || string("status") != "ringing") return null
            return RelayCallback(id, binding, string("title")?.take(120)?.ifBlank { "Hermes" } ?: "Hermes",
                string("reason")?.take(1000).orEmpty(), expires, now, elapsed,
                elapsed + (expires - now).coerceAtMost(RING_WINDOW_MS))
        }
    }
}

/** The call after one protocol step; the reducer owns no Android service, notification, permission prompt or microphone. */
data class RelayCallbackTransition(val call: RelayCallback)

/**
 * One instance per authenticated pairing lifetime; keep it across socket reconnects.
 * Serialize effects with the existing call controller. Retired UUIDs are never evicted.
 * shortcut: at 256 invitations fail closed until re-pairing; add durable replay storage
 * before supporting more invitations per pairing lifetime.
 * Process death retires all local action tokens.
 */
class RelayCallbackProtocol {
    @Volatile var current: RelayCallback? = null
        private set
    private val seen = mutableSetOf<String>()

    private fun finish(call: RelayCallback): RelayCallbackTransition {
        val ended = call.copy(phase = RelayCallbackPhase.Finished)
        current = ended
        return RelayCallbackTransition(ended)
    }

    /** Call on sign-out, re-pairing, scope changes and opt-out, even without incoming traffic. */
    @Synchronized fun invalidate(binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?): RelayCallbackTransition? {
        val call = current?.takeUnless { it.phase == RelayCallbackPhase.Finished } ?: return null
        if (binding == null || !binding.valid || binding != call.binding) return finish(call)
        if (prefs == null) return null
        if (RelayCallbackPolicy.callsAllowed(prefs, binding.connectionId)) return null
        return finish(call)
    }

    @Synchronized fun receive(message: JSONObject, binding: RelayCallbackBinding?, prefs: RelayCallbackPreferences?,
        now: Long, elapsed: Long): RelayCallbackTransition? {
        invalidate(binding, prefs)?.let { return it }
        if (binding == null || !binding.valid || !RelayCallbackPolicy.callsAllowed(prefs, binding.connectionId)) return null
        val envelope = RelayCallbackWire.parse(message) ?: return null
        val call = RelayCallback.parse(envelope.payload, binding, now, elapsed) ?: return null
        if (seen.size >= 256 || !seen.add(call.callId)) return null
        if (current?.phase?.let { it != RelayCallbackPhase.Finished } == true) {
            return RelayCallbackTransition(call.copy(phase = RelayCallbackPhase.Finished))
        }
        current = call
        return RelayCallbackTransition(call)
    }

    @Synchronized fun expire(now: Long, elapsed: Long, binding: RelayCallbackBinding?,
        prefs: RelayCallbackPreferences?): RelayCallbackTransition? {
        invalidate(binding, prefs)?.let { return it }
        val call = current ?: return null
        if (call.phase == RelayCallbackPhase.Ringing && call.remainingRingMillis(now, elapsed) == 0L) return finish(call)
        return null
    }
}
