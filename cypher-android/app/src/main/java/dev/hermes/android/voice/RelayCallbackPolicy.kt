package dev.hermes.android.voice

import dev.hermes.android.core.trustedOrigin
import org.json.JSONObject

// Adapted from Hermes Relay ProactiveSubscription.kt / data/ProactivePrefs.kt.
// Copyright (c) 2026 Axiom Labs, MIT; full notice in RelayCallback.kt.

/** A hydrated preference snapshot. Null means storage has not loaded, never consent. */
data class RelayCallbackPreferences(
    val connectionId: String?, val messages: Boolean, val calls: Boolean, val keepConnected: Boolean,
)

/**
 * Supplied by the authenticated pairing owner, never copied from an invitation.
 * The integrator must first verify native origin/owner/profile authority and retire this
 * binding on account, endpoint, pairing or conversation changes. Session null is exact.
 */
data class RelayCallbackBinding(
    val connectionId: String, val deviceId: String, val profile: String, val sessionId: String?,
    val origin: String, val owner: String,
) {
    val valid: Boolean get() = connectionId.isNotBlank() && deviceId.isNotBlank() &&
        owner.isNotBlank() && runCatching { trustedOrigin(origin) == origin }.getOrDefault(false) &&
        profile.isNotBlank() && profile.length <= 256 &&
        (sessionId == null || sessionId.isNotBlank() && sessionId.length <= 512)
}

object RelayCallbackPolicy {
    private fun bound(prefs: RelayCallbackPreferences?, connectionId: String?): RelayCallbackPreferences? =
        prefs?.takeIf { !connectionId.isNullOrBlank() && it.connectionId == connectionId }

    fun messagesAllowed(prefs: RelayCallbackPreferences?, connectionId: String?): Boolean =
        bound(prefs, connectionId)?.messages == true

    fun callsAllowed(prefs: RelayCallbackPreferences?, connectionId: String?): Boolean =
        bound(prefs, connectionId)?.let { it.calls && it.keepConnected } == true

    /** Unknown/unbound storage must not change the subscription on a different connection. */
    fun subscriptionEnvelope(prefs: RelayCallbackPreferences?, connectionId: String?): JSONObject? {
        val snapshot = bound(prefs, connectionId) ?: return null
        val calls = snapshot.calls && snapshot.keepConnected
        return if (!snapshot.messages && !calls) RelayCallbackWire.envelope("proactive.unsubscribe", JSONObject())
        else RelayCallbackWire.envelope("proactive.subscribe", JSONObject()
            .put("messages", snapshot.messages).put("voice_calls", calls))
    }

    fun canSetCallsConsent(enabled: Boolean, paired: Boolean, keepConnected: Boolean): Boolean =
        !enabled || paired && keepConnected
}
