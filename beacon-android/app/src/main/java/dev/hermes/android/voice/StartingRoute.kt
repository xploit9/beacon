package dev.hermes.android.voice

import androidx.core.telecom.CallEndpointCompat

/**
 * Where a call starts: the speaker when it sits on the earpiece (or is not routed yet), the phone has a speaker, and nothing else could carry it.
 * A wired headset, Bluetooth or a streaming route that is available keeps the call as Android placed it.
 */
internal fun initialEndpoint(current: CallEndpointCompat?, available: List<CallEndpointCompat>): CallEndpointCompat? {
    if (current != null && current.type != CallEndpointCompat.TYPE_EARPIECE) return null
    if (available.any { it.type in PRIVATE_ROUTES }) return null
    return available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
}

private val PRIVATE_ROUTES = setOf(CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_BLUETOOTH, CallEndpointCompat.TYPE_STREAMING)

/**
 * One call's starting route, chosen once. It settles when the call is active and the first non-empty endpoint list has arrived:
 * [initialEndpoint] then says where to go, and nothing more is ever asked for this call. So the owner's own choice and Android's
 * own switch to a headset connected mid-call always win. [active] and [available] return the endpoint to request, or null.
 */
internal class StartingRoute {
    private var active = false
    private var settled = false
    private var current: CallEndpointCompat? = null
    private var available = emptyList<CallEndpointCompat>()

    @Synchronized fun active(): CallEndpointCompat? { active = true; return settle() }
    @Synchronized fun available(list: List<CallEndpointCompat>): CallEndpointCompat? { available = list; return settle() }
    @Synchronized fun current(endpoint: CallEndpointCompat) { current = endpoint }
    /** The owner picked a route: this call is never moved again. */
    @Synchronized fun chosenByOwner() { settled = true }

    private fun settle(): CallEndpointCompat? {
        if (settled || !active || available.isEmpty()) return null
        settled = true
        return initialEndpoint(current, available)
    }
}
