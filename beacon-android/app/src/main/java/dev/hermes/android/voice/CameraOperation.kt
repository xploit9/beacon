package dev.hermes.android.voice

import java.util.concurrent.atomic.AtomicLong

internal data class CameraOperation(val generation: Long, val enabled: Boolean, val rear: Boolean)
internal class CameraOperationGate {
    private val generation = AtomicLong()
    fun request(enabled: Boolean, rear: Boolean) = CameraOperation(generation.incrementAndGet(), enabled, rear)
    fun current(operation: CameraOperation) = operation.generation == generation.get()
    fun invalidate() { generation.incrementAndGet() }
}
internal fun CallState.afterCameraFailure(stopped: Boolean) = copy(
    cameraSharing = false, cameraStopPending = !stopped, cameraBusy = false, cameraCanFlip = false,
    error = if (stopped) "Camera unavailable. The call goes on."
        else "Camera may still be on. Tap Camera off again or end the call."
)
internal fun requireCameraReleasedForNewCall(state: CallState) {
    check(!state.cameraStopPending) { "Camera cleanup is still unconfirmed. Another call cannot start yet." }
    check(!state.screenStopPending) { "Screen capture cleanup is still unconfirmed. Another call cannot start yet." }
}
