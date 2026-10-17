package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import kotlin.math.*

internal fun overlayOwnsCall(call: CallState, expectedId: String, owner: Scope?, authenticated: Boolean, unlocked: Boolean): Boolean =
    call.active && expectedId.isNotBlank() && call.callId == expectedId && authenticated && unlocked &&
        owner != null && call.conversationScope?.let { it.origin == owner.origin && it.owner == owner.owner && it.profile == owner.profile } == true
internal fun normalizedVoiceLevel(level: Float): Float? = level.takeIf { it.isFinite() && it >= 0f }?.coerceAtMost(1f)
internal fun CallState.withOverlayPermission(allowed: Boolean): CallState =
    copy(overlayPermissionMissing = active && callId.isNotBlank() && !allowed)
internal fun CallState.chipLabel(): String = when {
    !active -> "Ended"
    held -> "Hold"
    state == "connecting" -> "Start"
    remoteSpeaking || state == "speaking" -> "Speak"
    muted -> "Muted"
    else -> "Listen"
}
internal fun CallState.voiceAmplitude(): Float {
    if (!active || held || state == "connecting") return 0f
    fun speech(level: Float?, speaking: Boolean, fallback: Float): Float = if (!speaking) 0f else
        if (level == null) fallback else (sqrt(normalizedVoiceLevel(level) ?: 0f)*1.7f).coerceAtMost(1f)
    return max(speech(remoteAudioLevel,remoteSpeaking || state == "speaking",.55f),
        speech(localAudioLevel,userSpeaking && !muted,.45f))
}
internal fun voiceMotionActive(amplitude: Float, motionEnabled: Boolean, visible: Boolean): Boolean =
    motionEnabled && visible && amplitude.isFinite() && amplitude > .01f
/** Independent cadence prevents SDK local callbacks from starving remote levels, or vice versa. */
internal class VoiceLevelCadence {
    private var localAt = Long.MIN_VALUE
    private var remoteAt = Long.MIN_VALUE
    @Synchronized fun accept(local: Boolean, now: Long): Boolean {
        val last = if(local) localAt else remoteAt
        if(last != Long.MIN_VALUE && now-last < 40) return false
        if(local) localAt=now else remoteAt=now
        return true
    }
}
internal fun CallState.latestCaption(role: String): String = if (!active) "" else
    transcripts.lastOrNull { it.role == role }?.takeIf { !it.interrupted }?.text?.take(1400).orEmpty()
internal fun persistentCallNavigation(widthDp: Float, fontScale: Float): Boolean =
    widthDp >= 720f && widthDp - 280f * fontScale.coerceIn(1f, 1.2f) >= 420f * fontScale.coerceIn(1f, 1.4f)
internal fun clampBubblePosition(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int, top: Int, bottom: Int): Pair<Int, Int> =
    x.coerceIn(0, (screenWidth-width).coerceAtLeast(0)) to y.coerceIn(top.coerceAtLeast(0), (screenHeight-height-bottom).coerceAtLeast(top.coerceAtLeast(0)))
/** Abstract activity indicator geometry, not an acoustic waveform or playback measurement. */
internal fun voiceWaveY(x: Float, phase: Float, amplitude: Float, layer: Int): Float {
    val envelope = sin(PI.toFloat()*x).coerceAtLeast(0f)
    val fluid = sin(x*PI.toFloat()*2.2f-phase+layer*.8f) + .35f*sin(x*PI.toFloat()*5f+phase*.7f)
    return (.60f + layer*.065f + fluid*envelope*(.04f+amplitude.coerceIn(0f,1f)*.18f)).coerceIn(.1f,.95f)
}
