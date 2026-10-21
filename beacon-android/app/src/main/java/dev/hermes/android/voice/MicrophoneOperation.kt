package dev.hermes.android.voice

internal data class MicrophoneIntent(val generation: Long, val muted: Boolean, val held: Boolean) {
    val enabled get() = !muted && !held
}
internal class MicrophoneIntentGate {
    private var intent = MicrophoneIntent(0,false,false)
    @Synchronized fun request(muted: Boolean? = null, held: Boolean? = null): MicrophoneIntent {
        intent = MicrophoneIntent(intent.generation+1,muted ?: intent.muted,held ?: intent.held)
        return intent
    }
    @Synchronized fun snapshot() = intent
    @Synchronized fun current(expected: MicrophoneIntent) = intent === expected
}
