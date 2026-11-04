package dev.hermes.android.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether Hermes has answered this phone lately, per origin. A request that cannot connect (no route, refused, a connect timeout,
 * as when a VPN such as Tailscale drops on its own) marks the origin unreachable until any answer comes back, so the app can say so
 * instead of only putting an unsent message back (owner, 4 Nov 2026).
 */
object HermesReach {
    private val unreachable = MutableStateFlow<Set<String>>(emptySet())
    val origins: StateFlow<Set<String>> = unreachable.asStateFlow()

    fun failed(origin: String, error: Throwable) { if (origin.isNotBlank() && requestNeverSent(error)) unreachable.update { it + origin } }

    fun answered(origin: String) { if (origin in unreachable.value) unreachable.update { it - origin } }
}
