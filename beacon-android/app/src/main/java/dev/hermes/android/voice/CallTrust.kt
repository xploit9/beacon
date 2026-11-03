package dev.hermes.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.hermes.android.phone.deviceLockedNow
import org.json.JSONObject

/**
 * Whether one conversation may read or do anything private. It is trusted when it started on an unlocked phone, or the phone has been
 * unlocked at any time since (the live lock read at the start, an unlock broadcast, or a read when a tool runs). Once trusted it
 * stays trusted for the rest of that conversation, even if the screen locks again (a call in a pocket keeps working); each new
 * conversation starts from the lock screen as it finds it. Not trusted, the conversation may talk but no tool runs and the call
 * brief carries no memory, history or profile ([withholdPersonal]); approvals still need the unlocked screen to tap.
 */
internal class CallTrust(private val locked: () -> Boolean) {
    @Volatile private var unlockedSinceStart = !locked()
    private var receiver: BroadcastReceiver? = null

    /** True once the phone has been unlocked since this conversation started. Reads the lock screen again while it is not. */
    fun trusted(): Boolean {
        if (unlockedSinceStart) return true
        if (!locked()) unlockedSinceStart = true
        return unlockedSinceStart
    }

    /** An unlock signal (the user is present). */
    fun unlocked() { unlockedSinceStart = true }

    /** Listens for the unlock broadcast until [close]: an unlock between two tool calls, then a lock again, is still seen. */
    @Synchronized fun watch(context: Context) {
        if (receiver != null || unlockedSinceStart) return
        val listener = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = unlocked() }
        receiver = listener
        runCatching { ContextCompat.registerReceiver(context.applicationContext, listener, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { receiver = null }
    }

    @Synchronized fun close(context: Context) {
        val listener = receiver ?: return
        receiver = null
        runCatching { context.applicationContext.unregisterReceiver(listener) }
    }

    companion object {
        /** A conversation that starts now, on this phone's lock screen as it is. */
        fun forPhone(context: Context) = CallTrust { deviceLockedNow(context) }
        /** Never held back; for tests and conversations that have no phone to lock. */
        fun open() = CallTrust { false }
    }
}

/** What a Hermes tool answers while the conversation is not trusted: nothing was read or done, and the model asks the owner to unlock. */
internal fun lockedToolResult(): JSONObject = JSONObject().put("status", "unavailable").put("error_code", "locked")
    .put("message", "Unlock your phone first. Nothing was read or done; ask the owner to unlock the phone, then try again.")

/** The parts of the plugin's call context that say nothing about the owner: which profile and chat it is, the time zone and what the profile can do. */
private val CONTEXT_KEPT = setOf("backend", "profile", "selected_session_id", "zone", "capabilities")

/**
 * [context] as a conversation that is not trusted may be told: no memory, history, earlier voice dialogue, profile persona, name,
 * callback, greeting or tool catalogue (all of it is private to the owner). Only what [CONTEXT_KEPT] names and the spoken language
 * stay, so a part the plugin adds later is withheld too. The call can still talk.
 */
internal fun withholdPersonal(context: JSONObject): JSONObject {
    val kept = JSONObject()
    for (key in CONTEXT_KEPT) if (context.has(key)) kept.put(key, context.get(key))
    context.optJSONObject("context")?.optString("language")?.takeIf { it.isNotBlank() }?.let { kept.put("context", JSONObject().put("language", it)) }
    return kept
}

/** The context a call's live session is set up with: the whole of it once the conversation is trusted, else [withholdPersonal]. */
internal fun callBrief(context: JSONObject, trusted: Boolean): JSONObject = if (trusted) context else withholdPersonal(context)
