package dev.hermes.android.core

/** One admission rule serves the Xara presentation and every conversation write. */
/** [assistantProfile]: the profile Settings › Assistant binds the assistant tab to; its conversation lives only there. */
fun canonicalConversationReady(owner: Scope?, visible: Scope?, sessionId: String, xara: Boolean, canonicalXara: String?, opening: Boolean = false, resolutionError: Boolean = false, assistantProfile: String = "default"): Boolean {
    if (owner == null || visible == null || owner != visible.copy(session = "") || sessionId.isBlank() || sessionId != visible.session) return false
    return !xara || owner.profile == assistantProfile && !opening && !resolutionError && !canonicalXara.isNullOrBlank() && sessionId == canonicalXara
}

/** A detached request may finish on its original server, but only its live dispatch
 * identity may settle the visible state or its errors. Client uses reference identity. */
data class ScopeSettlement(val scope: Scope, val client: Any, val originGeneration: Int, val sessionGeneration: Int) {
    fun current(scope: Scope?, client: Any?, originGeneration: Int, sessionGeneration: Int): Boolean =
        this.scope == scope && this.client === client && this.originGeneration == originGeneration && this.sessionGeneration == sessionGeneration
}

/** ARGB8888 previews retain at most 16 MiB per paragraph, reserved before allocation. */
class MathBitmapBudget(private val limit: Long = MAX_PIXELS) {
    var pixels: Long = 0; private set
    fun reserve(width: Int, height: Int): Boolean {
        if (width !in 1..4096 || height !in 1..2048) return false
        val requested = width.toLong() * height
        if (requested > limit - pixels) return false
        pixels += requested
        return true
    }
    companion object { const val MAX_PIXELS = 4L * 1024 * 1024 }
}
