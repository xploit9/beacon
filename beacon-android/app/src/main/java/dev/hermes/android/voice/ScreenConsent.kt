package dev.hermes.android.voice

import java.util.concurrent.atomic.AtomicLong

internal class ScreenConsentGate {
    private val generation = AtomicLong()
    fun request() = generation.incrementAndGet()
    fun invalidate() { generation.incrementAndGet() }
    fun current(token: Long) = token > 0 && token == generation.get()
    fun permits(token: Long, expectedId: String, call: CallState, closed: Boolean, owns: Boolean): Boolean =
        token > 0 && token == generation.get() && expectedId.isNotBlank() && call.callId == expectedId &&
            call.active && !call.held && !call.cameraStopPending && !call.screenStopPending && !closed && owns
}
internal fun cameraStopIsNoop(requested: Boolean, attempted: Boolean, state: CallState) =
    !requested && !attempted && !state.cameraSharing && !state.cameraStopPending
internal fun backgroundNeedsBubble(call: CallState, visible: Boolean) = call.active && !visible && call.callId.isNotBlank()
