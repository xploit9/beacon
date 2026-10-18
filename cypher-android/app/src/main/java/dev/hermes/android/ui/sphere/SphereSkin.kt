// Adapted from Hermes Relay (MIT) app/src/main/kotlin/com/hermesandroid/relay/ui/components/SphereSkin.kt
package dev.hermes.android.ui.sphere

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

internal fun adaptiveColors(state: SphereState, scheme: ColorScheme): SphereColors = when (state) {
    SphereState.Idle -> poles(scheme.primary, scheme.tertiary)
    SphereState.Thinking -> poles(scheme.primary, scheme.secondary)
    SphereState.Streaming -> poles(scheme.secondary, scheme.tertiary)
    SphereState.Listening -> poles(scheme.primary, scheme.secondary)
    SphereState.Speaking -> poles(scheme.tertiary, scheme.secondary)
    SphereState.Error -> poles(scheme.error, scheme.tertiary)
}

private fun poles(a: Color, b: Color): SphereColors =
    SphereColors(a.red, a.green, a.blue, b.red, b.green, b.blue)
