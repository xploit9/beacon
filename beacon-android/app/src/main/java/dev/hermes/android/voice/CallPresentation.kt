package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import kotlin.math.*
import java.util.Locale

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
internal fun callStatusLabel(call: CallState): String {
    val toolStatus = call.toolReceipts.lastOrNull { it.statusLabel() != null }?.statusLabel()
    return when {
        call.held -> "Call on hold"
        call.state == "connecting" || call.state == "reconnecting" -> "Connecting"
        toolStatus != null -> toolStatus
        call.state == "thinking" -> "Thinking"
        call.remoteSpeaking || call.state == "speaking" -> "Speaking"
        call.muted -> "Muted"
        call.userSpeaking && !call.muted -> "Listening"
        call.state == "listening" -> "Listening"
        else -> call.state.replaceFirstChar { it.titlecase() }
    }
}
/** Every fixed English label that reaches uiText through a variable ([callStatusLabel], [transcriptLabel], the call history error), so the translation test can read them. */
internal val CallStrings = listOf(
    "Call on hold", "Connecting", "Thinking", "Speaking", "Muted", "Listening", "Idle", "Ending",
    "Some of this call wasn't saved to the chat", "Saving to the chat", "Saved to the chat",
    "Call history unavailable",
)
internal fun callTimerLabel(startedAtMillis: Long, nowMillis: Long): String {
    val elapsedSeconds = if (startedAtMillis > 0) ((nowMillis - startedAtMillis).coerceAtLeast(0) / 1000) else 0
    return String.format(Locale.ROOT, "%02d:%02d", elapsedSeconds / 60, elapsedSeconds % 60)
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
internal class FailedCallRedirect {
    private var handledId = ""
    fun claim(call: CallState): Boolean {
        if (call.callId.isBlank() || call.callId == handledId || call.failedCallConversation(call.callId) == null) return false
        handledId = call.callId
        return true
    }
}
internal const val persistentCallNavigationWidthDp = 336f
internal fun persistentCallNavigation(widthDp: Float, fontScale: Float): Boolean =
    widthDp >= 720f && widthDp - persistentCallNavigationWidthDp >= 420f * fontScale.coerceIn(1f, 1.4f)
internal fun clampBubblePosition(x: Int, y: Int, width: Int, height: Int, screenWidth: Int, screenHeight: Int, top: Int, bottom: Int): Pair<Int, Int> =
    x.coerceIn(0, (screenWidth-width).coerceAtLeast(0)) to y.coerceIn(top.coerceAtLeast(0), (screenHeight-height-bottom).coerceAtLeast(top.coerceAtLeast(0)))
