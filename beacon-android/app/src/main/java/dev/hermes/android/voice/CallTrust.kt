package dev.hermes.android.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dev.hermes.android.phone.deviceLockedNow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * Whether one conversation may read or do anything private. It is trusted when it started on an unlocked phone, or the phone has been
 * unlocked at any time since (the live lock read at the start, an unlock broadcast, or a read when a tool runs). Once trusted it
 * stays trusted for the rest of that conversation, even if the screen locks again (a call in a pocket keeps working); each new
 * conversation starts from the lock screen as it finds it. Not trusted, the conversation may talk but no tool runs and the call
 * brief carries no memory, history or profile ([withholdPersonal]); approvals still need the unlocked screen to tap. The side button
 * starts a conversation on a locked phone all the same ("talk now, unlock for private"): what it asks for that is private is refused
 * ([refused]), the model says to unlock, and the unlock makes the conversation trusted ([whenTrusted] lets it carry on with the whole brief).
 */
internal class CallTrust(private val locked: () -> Boolean) {
    @Volatile private var unlockedSinceStart = !locked()
    @Volatile private var asked = false
    @Volatile private var onTrusted: (() -> Unit)? = null
    private var receiver: BroadcastReceiver? = null

    /** True once the phone has been unlocked since this conversation started. Reads the lock screen again while it is not. */
    fun trusted(): Boolean {
        if (unlockedSinceStart) return true
        if (!locked()) trust()
        return unlockedSinceStart
    }

    /** An unlock signal (the user is present). */
    fun unlocked() = trust()

    /** The first moment this conversation becomes trusted, whichever way it is seen, tells [whenTrusted] once. Started trusted, it never does. */
    private fun trust() {
        val first = synchronized(this) { !unlockedSinceStart.also { unlockedSinceStart = true } }
        if (first) onTrusted?.invoke()
    }

    /** [block] runs once, when a conversation that was not trusted becomes trusted. It runs on whichever thread saw the unlock. */
    fun whenTrusted(block: () -> Unit) { onTrusted = block }

    /** Something private was asked for and refused: the owner has to unlock for it. The overlay says so ([lockedAsks]). */
    fun refused() {
        asked = true
        asks.update { it + 1 }
    }

    /** Whether anything private was refused in this conversation, so the unlock has a request to carry out. */
    fun wasRefused(): Boolean = asked

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
        private val asks = MutableStateFlow(0)
        /** How many times, in this process, a locked conversation refused something private; only ever grows. The overlay watches it. */
        val lockedAsks: StateFlow<Int> get() = asks

        /** A conversation that starts now, on this phone's lock screen as it is, and the owner's lock-screen choice. */
        fun forPhone(context: Context) = CallTrust { heldBackWhileLocked(deviceLockedNow(context), LockScreenAccess.allowed(context)) }
        /** Never held back; for tests and conversations that have no phone to lock. */
        fun open() = CallTrust { false }
    }
}

/** Held back: the phone is locked and the owner has not let the assistant use their memory and tools while it is. */
internal fun heldBackWhileLocked(locked: Boolean, allowedWhileLocked: Boolean): Boolean = locked && !allowedWhileLocked

/**
 * Settings › Assistant › "Personal info when locked" (owner, 4 Nov 2026). Off, the default: on a locked phone the assistant talks at once
 * and anything personal waits for the unlock. On (owner, 4 Nov 2026: control even on the lock screen): the owner's memory, chats and
 * tools work there too, the phone's own actions run (PhoneEngine) and an approval can be given on the lock screen; a phone call still
 * waits for its tap or countdown.
 */
internal object LockScreenAccess {
    private const val FILE = "beacon_lock_screen"
    private const val KEY = "personal_while_locked"
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun allowed(context: Context): Boolean = prefs(context).getBoolean(KEY, false)
    fun set(context: Context, on: Boolean) { prefs(context).edit().putBoolean(KEY, on).apply() }
}

/**
 * What a Hermes tool answers while the conversation is not trusted: nothing was read or done, and the model asks the owner to unlock.
 * The wording is the model's instruction, so it says what to tell the owner and that the conversation goes on after the unlock.
 */
internal fun lockedToolResult(): JSONObject = JSONObject().put("status", "unavailable").put("error_code", "locked").put("message", LOCKED_TOOL_MESSAGE)

/** The words of a locked refusal, Hermes' tools and the phone's alike. */
internal const val LOCKED_TOOL_MESSAGE = "Unlock your phone first. Nothing was read or done; ask the owner to unlock the phone, in one short sentence in their language, " +
    "and say you will do it as soon as they have. The screen shows an Unlock button, and this conversation carries on after it. Do not try this again until then."

/** The parts of the plugin's call context that say nothing about the owner: which profile and chat it is, the time zone and what the profile can do. */
private val CONTEXT_KEPT = setOf("backend", "profile", "selected_session_id", "zone", "capabilities")

/**
 * What a conversation that is not trusted is told about itself, in the place of the profile's persona: the phone is locked, so it talks
 * normally and answers what it can. For anything private it tries the tool it would use, which is refused before anything is read and
 * puts the Unlock button on the owner's screen ([CallTrust.refused]), and then asks the owner to unlock.
 */
internal const val LOCKED_BRIEF = "The owner's phone is locked. You know nothing private about the owner, and nothing of their memory, mail, calendar, " +
    "contacts, messages, calls, the phone or Hermes' tools is available until they unlock it. Talk with them naturally and answer what you can yourself. " +
    "When they ask for anything personal or for an action, call the tool you would normally use for it (hermes_memory for what you know about them, " +
    "otherwise discover_tools): while the phone is locked it only answers that the owner must unlock, which also puts an Unlock button on their screen. " +
    "Then tell them in one short sentence, in the language they speak, to unlock their phone first and that you will do it as soon as they have. " +
    "The conversation carries on after the unlock."

/**
 * [context] as a conversation that is not trusted may be told: no memory, history, earlier voice dialogue, profile persona, name,
 * callback, greeting or tool catalogue (all of it is private to the owner). Only what [CONTEXT_KEPT] names and the spoken language
 * stay, so a part the plugin adds later is withheld too, and [LOCKED_BRIEF] says why. The call can still talk.
 */
internal fun withholdPersonal(context: JSONObject): JSONObject {
    val kept = JSONObject()
    for (key in CONTEXT_KEPT) if (context.has(key)) kept.put(key, context.get(key))
    context.optJSONObject("context")?.optString("language")?.takeIf { it.isNotBlank() }?.let { kept.put("context", JSONObject().put("language", it)) }
    return kept.put("system_instruction", LOCKED_BRIEF)
}

/** The context a call's live session is set up with: the whole of it once the conversation is trusted, else [withholdPersonal]. */
internal fun callBrief(context: JSONObject, trusted: Boolean): JSONObject = if (trusted) context else withholdPersonal(context)

/**
 * The note a conversation gets, as a turn, once its session was replaced by one set up with the whole brief after the unlock, when a
 * private request was turned away meanwhile: it is done now, or the owner is asked to say it again in one short line. A conversation
 * that was never turned away is replaced without a word (nothing is owed).
 */
internal const val TRUSTED_CUE = "[Note from the app, not the owner: the owner unlocked the phone. You now have their memory, tools and phone actions, and this " +
    "conversation goes on. If their last request needed them, do it now; if you are not sure what it was, ask them in one short line, in their language, " +
    "to say it again. Do not greet again.]"
