// Adapted from Hermes Relay (MIT) app/src/main/kotlin/com/hermesandroid/relay/ui/components/SphereSkin.kt
package dev.hermes.android.ui.sphere

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Hot-swappable sphere "skin" — the color + parameter layer that sits on top of
 * the shared core algorithm in [MorphingSphereCore] (`forEachSphereCell`).
 *
 * The core math is intentionally untouched (it stays byte-for-byte mirrored in
 * `preview/web/sphere.js` for the docs-site embed and the JVM parity test); a
 * skin only supplies the per-[SphereState] [SphereColors]/[SphereParams] and
 * declares which reactive inputs it honors. That keeps skins pure data, makes
 * them safe to load from user JSON, and lets the host feed only what a skin
 * actually responds to.
 *
 * Skins come from three sources:
 *  - [SphereSkinSource.ADAPTIVE] — colors derived live from the active theme's
 *    [ColorScheme] (the default; "auto-follow the theme"),
 *  - [SphereSkinSource.BUILT_IN] — fixed palettes shipped with the app,
 *  - [SphereSkinSource.USER] — loaded from a user-authored JSON spec
 *    (see [SphereSpec] + `docs/sphere-spec.md`).
 */
@Immutable
data class SphereReactivity(
    /** Listening/speaking amplitude modulation. */
    val voice: Boolean = true,
    /** Tool-call burst pulses. */
    val tools: Boolean = true,
    /** Generic activity intensity (turbulence/ripple/flow). */
    val intensity: Boolean = true,
    /** Gaze bias — aim the bright spot. Declared for completeness; not every
     *  renderer feeds it (the chat/voice [MorphingSphere] does not). */
    val gaze: Boolean = false,
) {
    /** Ordered list of enabled capability flags (locale-independent so test
     *  assertions stay stable); [reactivityLabels] formats their names. */
    fun flags(): List<ReactivityFlag> = buildList {
        if (voice) add(ReactivityFlag.VOICE)
        if (tools) add(ReactivityFlag.TOOLS)
        if (intensity) add(ReactivityFlag.ACTIVITY)
        if (gaze) add(ReactivityFlag.GAZE)
    }

    /** Legacy locale-independent summary for backward compat. Prefer [flags] +
     *  [reactivityLabels] in UI code. */
    fun summary(): String {
        val on = flags().map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        return if (on.isEmpty()) "Static" else on.joinToString(" · ")
    }
}

/** Individual capability flags exposed by a [SphereReactivity]. Used as
 *  locale-independent keys; the UI maps each to a localized label. */
enum class ReactivityFlag { VOICE, TOOLS, ACTIVITY, GAZE }

/** Pre-composed localized summary string from a list of [ReactivityFlag],
 *  joined with the middle-dot separator. */
/** Human-readable capability summary from a list of [ReactivityFlag]. */
fun reactivityLabels(flags: List<ReactivityFlag>): String = if (flags.isEmpty()) "Static" else flags.joinToString(" \u00B7 ") {
    when (it) {
        ReactivityFlag.VOICE -> "Voice"
        ReactivityFlag.TOOLS -> "Tools"
        ReactivityFlag.ACTIVITY -> "Activity"
        ReactivityFlag.GAZE -> "Gaze"
    }
}
enum class SphereSkinSource { ADAPTIVE, BUILT_IN, USER }

@Immutable
data class SphereSkin(
    val id: String,
    val label: String,
    val description: String,
    val source: SphereSkinSource,
    val reactivity: SphereReactivity,
    /** When true, [colorsFor] derives poles from the active [ColorScheme]. */
    val adaptive: Boolean,
    /** Explicit per-state colors. Used when [adaptive] is false. */
    val stateColors: Map<SphereState, SphereColors> = emptyMap(),
    /** Optional per-state param overrides; unset states fall back to [paramsFor]. */
    val stateParams: Map<SphereState, SphereParams> = emptyMap(),
) {
    fun colorsFor(state: SphereState, scheme: ColorScheme): SphereColors = when {
        adaptive -> adaptiveColors(state, scheme)
        else -> stateColors[state] ?: colorsFor(state)
    }

    fun paramsForState(state: SphereState): SphereParams =
        stateParams[state] ?: paramsFor(state)

    /** Two representative colors for the picker swatch (idle poles). */
    fun swatch(scheme: ColorScheme): List<Color> {
        val c = colorsFor(SphereState.Idle, scheme)
        return listOf(Color(c.r1, c.g1, c.b1), Color(c.r2, c.g2, c.b2))
    }
}

// ── Color helpers ───────────────────────────────────────────────────────

internal fun poles(a: Color, b: Color): SphereColors =
    SphereColors(a.red, a.green, a.blue, b.red, b.green, b.blue)

/**
 * Derive emissive sphere poles from Cypher's active Material color scheme so
 * the backdrop follows both dark and light themes.
 */
internal fun adaptiveColors(state: SphereState, scheme: ColorScheme): SphereColors = when (state) {
    SphereState.Idle -> poles(scheme.primary, scheme.tertiary)
    SphereState.Thinking -> poles(scheme.primary, scheme.secondary)
    SphereState.Streaming -> poles(scheme.secondary, scheme.tertiary)
    SphereState.Listening -> poles(scheme.primary, scheme.secondary)
    SphereState.Speaking -> poles(scheme.tertiary, scheme.secondary)
    SphereState.Error -> poles(scheme.error, scheme.tertiary)
}

/** Build a full 6-state color map for a fixed skin from a few seed colors. */
internal fun fixedSkinColors(
    poleA: Color,
    poleB: Color,
    accent: Color,
    errorA: Color = Color(0xFFE5482E),
    errorB: Color = Color(0xFFF2A53C),
): Map<SphereState, SphereColors> = mapOf(
    SphereState.Idle to poles(poleA, poleB),
    SphereState.Thinking to poles(accent, poleB),
    SphereState.Streaming to poles(poleB, accent),
    SphereState.Listening to poles(poleA, accent),
    SphereState.Speaking to poles(accent, poleB),
    SphereState.Error to poles(errorA, errorB),
)

// ── Registry ──────────────────────────────────────────────────────────────

/**
 * Built-in skins + runtime-loaded user skins. The picker reads [builtIns]; the
 * full available set (built-ins + user) is provided via [LocalAvailableSphereSkins].
 */
object SphereRegistry {
    const val AUTO_ID = "auto"

    /** Recolors to match the active theme — the default when the pref is "auto". */
    val Adaptive = SphereSkin(
        id = "adaptive",
        label = "Adaptive",
        description = "Follows your theme's colors",
        source = SphereSkinSource.ADAPTIVE,
        reactivity = SphereReactivity(voice = true, tools = true, intensity = true),
        adaptive = true,
    )

    /** The original shipped look — exact per-state colors from the core. */
    val Classic = SphereSkin(
        id = "classic",
        label = "Classic",
        description = "The original green-violet orb",
        source = SphereSkinSource.BUILT_IN,
        reactivity = SphereReactivity(voice = true, tools = true, intensity = true),
        adaptive = false,
        stateColors = SphereState.entries.associateWith { colorsFor(it) },
    )

    val Aurora = SphereSkin(
        id = "aurora",
        label = "Aurora",
        description = "Cool northern-lights teal and violet",
        source = SphereSkinSource.BUILT_IN,
        reactivity = SphereReactivity(voice = true, tools = true, intensity = true),
        adaptive = false,
        stateColors = fixedSkinColors(
            poleA = Color(0xFF3BE0C2),
            poleB = Color(0xFF8A6BFF),
            accent = Color(0xFF63B8FF),
        ),
    )

    val Solar = SphereSkin(
        id = "solar",
        label = "Solar",
        description = "Warm amber, orange, and ember",
        source = SphereSkinSource.BUILT_IN,
        reactivity = SphereReactivity(voice = true, tools = true, intensity = true),
        adaptive = false,
        stateColors = fixedSkinColors(
            poleA = Color(0xFFFFC24B),
            poleB = Color(0xFFFF7A3C),
            accent = Color(0xFFFFE08A),
            errorA = Color(0xFFFF4D4D),
            errorB = Color(0xFFFF8A3C),
        ),
    )

    val Mono = SphereSkin(
        id = "mono",
        label = "Mono",
        description = "Calm grayscale — minimal, no color shift",
        source = SphereSkinSource.BUILT_IN,
        reactivity = SphereReactivity(voice = true, tools = false, intensity = true),
        adaptive = false,
        stateColors = fixedSkinColors(
            poleA = Color(0xFFE6E6E6),
            poleB = Color(0xFF9A9A9A),
            accent = Color(0xFFC4C4C4),
            errorA = Color(0xFFE89090),
            errorB = Color(0xFFC0C0C0),
        ),
    )

    val builtIns: List<SphereSkin> = listOf(Adaptive, Classic, Aurora, Solar, Mono)

    /**
     * Resolve the effective skin from the user's pref id, the active theme's
     * preferred skin, and the available set (built-ins + user). "auto" yields
     * the theme's [com.hermesandroid.relay.ui.theme.AppTheme.defaultSphereSkinId]
     * if set, otherwise [Adaptive].
     */
    fun resolve(
        selectedId: String,
        themeDefaultSkinId: String?,
        available: List<SphereSkin>,
    ): SphereSkin {
        val effectiveId = if (selectedId == AUTO_ID) themeDefaultSkinId ?: Adaptive.id else selectedId
        return available.firstOrNull { it.id == effectiveId } ?: Adaptive
    }
}

/**
 * The resolved active skin. Defaults to [SphereRegistry.Classic] so previews and
 * any composable outside the app root render the original look. The app root
 * (`RelayApp`) provides the user's resolved choice.
 */
val LocalSphereSkin = staticCompositionLocalOf { SphereRegistry.Adaptive }

/** Built-ins + any loaded user skins. Provided at the app root for the picker. */
val LocalAvailableSphereSkins = staticCompositionLocalOf { SphereRegistry.builtIns }

/** Default brand for swatch rendering when no palette is in scope. */
