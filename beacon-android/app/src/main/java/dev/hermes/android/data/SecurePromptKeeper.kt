package dev.hermes.android.data

import dev.hermes.android.core.Scope

/** How long the sheet on screen may be missing from a live listing before it is taken as withdrawn: a new connection replays the question a moment after it opens. */
internal const val SECURE_UNLISTED_GRACE_MS = 8_000L

/** What decides whether a prompt may be on screen right now. */
internal data class SecureContext(val scope: Scope?, val originGeneration: Int, val sessionGeneration: Int,
    val foreground: Boolean, val authenticated: Boolean, val locked: Boolean, val now: Long)

/**
 * Hermes' open questions (a login to save, a code, a manager to unlock) while the person may be away. A prompt that cannot be shown
 * yet (app in the background, phone locked) is held, never answered: Hermes keeps asking until its own timeout, and the person is told
 * once. Only an explicit "no", a sign-out or a change of account/profile/chat answers it empty.
 */
internal class SecurePromptKeeper(
    /** Sends [value] for [prompt]; an empty value skips it. Returns whether Hermes took it. */
    private val answer: (SecurePrompt, String) -> Boolean,
    private val notifyHeld: (SecurePrompt) -> Unit,
    private val clearNotice: (SecurePrompt) -> Unit,
) {
    var shown: SecurePrompt? = null
        private set
    // The held questions the person was told about, by key. There is one notice for all of them.
    private val noticed = LinkedHashMap<String, SecurePrompt>()
    // Hermes' request id is the same when the question returns on a new connection; the connection marker is not.
    private fun key(prompt: SecurePrompt) = securePromptQuestionKey(prompt)
    // When the sheet on screen was last missing from a LIVE listing (0: it is listed, or nothing is known).
    private var unlistedSince = 0L

    private fun disposition(prompt: SecurePrompt, ctx: SecureContext) =
        securePromptDisposition(prompt, ctx.scope, ctx.originGeneration, ctx.sessionGeneration, ctx.foreground, ctx.authenticated, ctx.locked, ctx.now)

    private fun hold(prompt: SecurePrompt) {
        if (shown == prompt) shown = null
        if (noticed.put(key(prompt), prompt) == null) notifyHeld(prompt)
        if (noticed.size > 64) noticed.remove(noticed.keys.first())
    }

    /** Held questions Hermes no longer asks (an interrupted turn withdrew them). The one notice goes with the last of them. */
    private fun forgetHeld(gone: List<String>) {
        val last = gone.mapNotNull { noticed.remove(it) }.lastOrNull() ?: return
        if (noticed.isEmpty()) clearNotice(last)
    }

    private fun drop(prompt: SecurePrompt, ctx: SecureContext) {
        if (shown == prompt) shown = null
        clearNotice(prompt)
        // Hermes' own time ran out: nothing is waiting. Anything else (signed out, another chat) is told "skip".
        if (ctx.now < prompt.expiresAt) answer(prompt, "")
    }

    private fun show(prompt: SecurePrompt) {
        shown = prompt
        unlistedSince = 0L
        noticed.remove(key(prompt))
        clearNotice(prompt)
    }

    /** One prompt has just arrived. */
    fun offer(prompt: SecurePrompt, ctx: SecureContext): SecureDisposition = disposition(prompt, ctx).also {
        when (it) {
            // A second question waits its turn, still open at Hermes; the same question on a newer connection takes the sheet over.
            SecureDisposition.Show -> if (shown == null || shown == prompt || shown?.let(::key) == key(prompt)) show(prompt)
            SecureDisposition.Hold -> hold(prompt)
            SecureDisposition.Drop -> drop(prompt, ctx)
        }
    }

    /**
     * The prompts Hermes still has open for this chat, polled while it is on screen and when the app returns. [linkLive] says whether [open] comes
     * from a live connection: with none, an empty list means nothing is known (the stream is reconnecting), not that Hermes withdrew the question.
     */
    fun settle(open: List<SecurePrompt>, ctx: SecureContext, linkLive: Boolean = true) {
        // This chat's list is the whole truth for this chat only: a question held for another chat is not withdrawn by it.
        val listed = open.mapTo(HashSet<String>()) { key(it) }
        forgetHeld(noticed.filter { (key, held) -> held.scope == ctx.scope && key !in listed }.keys.toList())
        // What the person typed lives in the sheet, so the sheet is not taken away by a gap between connections: it stays while the stream
        // reconnects and is replaced by the same question when it comes back on the new connection. Only a live listing that keeps
        // missing it for a moment (Hermes answered or withdrew it) takes it down.
        val current = shown
        if (current != null) {
            val same = open.firstOrNull { key(it) == key(current) }
            when {
                same != null -> { unlistedSince = 0L; if (same != current) shown = same }
                !linkLive -> Unit
                unlistedSince == 0L -> unlistedSince = ctx.now
                ctx.now - unlistedSince >= SECURE_UNLISTED_GRACE_MS -> { shown = null; unlistedSince = 0L }
            }
        }
        // The one on screen can no longer stay (phone locked, app left): hide it and keep it open.
        val settled = shown
        settled?.let { on ->
            when (disposition(on, ctx)) {
                SecureDisposition.Show -> Unit
                SecureDisposition.Hold -> hold(on)
                SecureDisposition.Drop -> drop(on, ctx)
            }
        }
        for (prompt in open) {
            if (prompt == settled) continue
            when (disposition(prompt, ctx)) {
                SecureDisposition.Show -> if (shown == null) show(prompt)
                SecureDisposition.Hold -> hold(prompt)
                SecureDisposition.Drop -> drop(prompt, ctx)
            }
        }
    }

    /** The system closed the sheet (app paused, screen off, phone locked). Not an answer. */
    fun suspend(prompt: SecurePrompt) { if (shown == prompt) hold(prompt) }

    /** The person pressed Cancel. */
    fun cancel(prompt: SecurePrompt) {
        if (shown == prompt) shown = null
        clearNotice(prompt)
        answer(prompt, "")
    }

    /** The person answered; the sheet already sent it. */
    fun finished(prompt: SecurePrompt) {
        if (shown == prompt) shown = null
        clearNotice(prompt)
    }

    /** Hermes withdrew or answered it elsewhere. */
    fun withdrawn(scope: Scope, id: String, method: String) {
        shown?.takeIf { it.scope == scope && it.id == id && it.method == method }?.let { shown = null; clearNotice(it) }
        forgetHeld(noticed.filter { (_, held) -> held.scope == scope && held.id == id && held.method == method }.keys.toList())
    }

    /**
     * The stream that carried the question stopped. It is still open at Hermes and returns with the next stream: no answer, no notice. The sheet
     * stays with what was typed (a Continue meanwhile is refused and says so); Hermes' own time, a withdrawal or a live listing without it ends it.
     */
    fun release(scope: Scope) { if (shown?.scope == scope) unlistedSince = 0L }

    /** Sign-out or a switch of account: whatever is on screen is skipped. */
    fun clear() { shown?.let { shown = null; clearNotice(it); answer(it, "") }; noticed.clear() }
}
