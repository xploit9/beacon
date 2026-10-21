package dev.hermes.android.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.core.string
import org.json.JSONObject

val NativeSkins = listOf("default", "ares", "mono", "graphite", "github", "codex", "terracotta", "slate", "poseidon", "sisyphus", "charizard", "sienna", "catppuccin", "hepburn", "nous", "neon", "neon-soft", "neon-paint", "geist-contrast", "zeus", "verdigris")
const val GlassSurfaceOpacity = .72f
const val GlassHighlightAlpha = .09f
const val GlassElevation = 0

fun nativeGlassFill(scheme: ColorScheme): Color = if (scheme.background.luminance() > .5f)
    Color.White.copy(alpha = .44f) else scheme.surfaceContainer.copy(alpha = GlassSurfaceOpacity)
fun nativeGlassHighlightAlpha(scheme: ColorScheme): Float = if (scheme.background.luminance() > .5f) .55f else GlassHighlightAlpha

fun nativeAmbientColors(scheme: ColorScheme): List<Color> = listOf(
    lerp(scheme.background, scheme.primary, .07f),
    scheme.background,
    lerp(scheme.background, scheme.onSurfaceVariant, .04f),
)

/** One ember of the chat backdrop: where it sits, how big it is and how much of the beacon's light it catches. */
internal data class BeaconEmber(val x: Float, val y: Float, val radius: Float, val alpha: Float)

/** A fixed, sparse drift of embers that thins out away from the light at the top; the same size always gives the same field. */
internal fun beaconEmbers(width: Float, height: Float, spacing: Float, maxRadius: Float): List<BeaconEmber> {
    require(width.isFinite() && height.isFinite() && spacing.isFinite() && maxRadius.isFinite() && width >= 0f && height >= 0f && spacing > 0f && maxRadius > 0f)
    val columns = (width / spacing).toInt() + 1
    val rows = (height / spacing).toInt() + 1
    var seed = 0x2545F491L
    fun next(): Float { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) and 0xFFFFFF).toFloat() / 0xFFFFFF }
    val embers = ArrayList<BeaconEmber>(columns * rows / 3 + 1)
    for (row in 0 until rows) for (column in 0 until columns) {
        val keep = next(); val dx = next(); val dy = next(); val size = next(); val glint = next()
        if (keep > .34f) continue
        val y = (row + dy) * spacing
        if (y > height) continue
        val light = 1f - (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f) * .75f
        embers += BeaconEmber(((column + dx) * spacing).coerceAtMost(width), y, maxRadius * (.35f + .65f * size), (.05f + .13f * glint) * light)
    }
    return embers
}

/** The chat backdrop: the beacon's light falling from the top, with embers drifting through it. */
@Composable fun Modifier.chatBeaconBackground(): Modifier {
    val light = MaterialTheme.colorScheme.primary
    return drawWithCache {
        val glow = Brush.radialGradient(listOf(light.copy(alpha = .13f), light.copy(alpha = .04f), Color.Transparent),
            center = Offset(size.width / 2f, -size.height * .04f), radius = maxOf(size.width, size.height) * .72f)
        val embers = beaconEmbers(size.width, size.height, 34.dp.toPx(), 1.6.dp.toPx())
        onDrawBehind {
            drawRect(glow)
            embers.forEach { drawCircle(light.copy(alpha = it.alpha), it.radius, Offset(it.x, it.y)) }
        }
    }
}

/** Times, durations and step details: small type with even-width digits so columns of numbers line up. */
@Composable fun nativeMetadataStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.Medium, letterSpacing = .2.sp)

/** Motion follows the phone's "Remove animations" setting and stops while the screen is not started. */
internal fun nativeMotionAllowed(systemAnimationsEnabled: Boolean, lifecycleStarted: Boolean): Boolean =
    systemAnimationsEnabled && lifecycleStarted

@Composable fun rememberNativeMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    fun enabled() = android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var systemEnabled by remember(resolver) { mutableStateOf(enabled()) }
    DisposableEffect(resolver) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemEnabled = enabled() }
        }
        resolver.registerContentObserver(android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return nativeMotionAllowed(systemEnabled, lifecycle.isAtLeast(Lifecycle.State.STARTED))
}
fun nativeContrast(a: Color, b: Color): Float = (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)
fun nativeRoleBackgrounds(scheme: ColorScheme): List<Color> {
    val containers = listOf(scheme.background, scheme.surface, scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.surfaceDim, scheme.surfaceBright, scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer, scheme.errorContainer) + listOf(1, 2, 3, 6, 12, 24).map { scheme.surfaceColorAtElevation(it.dp) }
    // Glass uses these exact constants. Include its underlying pane and both highlight ends.
    val backdrops = containers + nativeAmbientColors(scheme)
    val glass = backdrops.map { nativeGlassFill(scheme).compositeOver(it) }
    return backdrops + glass + glass.map { Color.White.copy(alpha = nativeGlassHighlightAlpha(scheme)).compositeOver(it) }
}
private fun readableInk(color: Color, backgrounds: (Color) -> List<Color>): Color {
    fun score(ink: Color) = backgrounds(ink).minOf { nativeContrast(ink, it) }
    if (score(color) >= 4.55f) return color
    val targets = listOf(Color.Black, Color.White).sortedByDescending(::score)
    for (step in 1..100) for (target in targets) {
        val candidate = lerp(color, target, step / 100f)
        if (score(candidate) >= 4.55f) return candidate
    }
    return targets.first()
}
fun nativeSkinColorScheme(values: JSONObject, dark: Boolean): ColorScheme {
    fun color(key: String, fallback: Color, depth: Int = 0): Color = runCatching {
        require(depth < 5)
        val value = values.string(key).trim()
        when {
            value.startsWith("var(--") -> color(value.removePrefix("var(--").substringBefore(')'), fallback, depth + 1)
            value.startsWith('#') -> { val hex = if (value.length == 4) value.drop(1).flatMap { listOf(it, it) }.joinToString("") else value.drop(1); require(hex.length in setOf(6, 8)); Color(hex.toLong(16) or if (hex.length == 6) 0xff000000L else 0L) }
            value.startsWith("rgb") -> { val parts = value.substringAfter('(').substringBefore(')').split(',').map { it.trim().toFloat() }; Color(parts[0] / 255f, parts[1] / 255f, parts[2] / 255f, parts.getOrElse(3) { 1f }) }
            else -> fallback
        }
    }.getOrDefault(fallback)
    val defaults = if (dark) darkColorScheme() else lightColorScheme()
    val background = color("bg", defaults.background)
    val surface = color("surface", background)
    val chrome = color("sidebar", surface)
    fun containers(primary: Color) = defaults.copy(background = background, surface = background, surfaceContainerLowest = background, surfaceContainerLow = lerp(background, chrome, .5f), surfaceContainer = chrome, surfaceContainerHigh = surface, surfaceContainerHighest = surface, surfaceDim = chrome, surfaceBright = surface, primary = primary, surfaceTint = primary, primaryContainer = lerp(chrome, primary, .13f), secondaryContainer = lerp(chrome, primary, .13f))
    val primary = readableInk(color("accent-text", color("accent", defaults.primary))) { nativeRoleBackgrounds(containers(it)) }
    val base = containers(primary)
    val backgrounds = nativeRoleBackgrounds(base)
    val foreground = readableInk(color("text", defaults.onSurface)) { backgrounds }
    val muted = readableInk(color("muted", defaults.onSurfaceVariant)) { backgrounds }
    val error = readableInk(color("error", defaults.error)) { backgrounds }
    fun on(ink: Color) = if (nativeContrast(Color.Black, ink) > nativeContrast(Color.White, ink)) Color.Black else Color.White
    return base.copy(onBackground = foreground, onSurface = foreground, onSurfaceVariant = muted, onPrimary = on(primary), onPrimaryContainer = foreground, onSecondaryContainer = foreground, secondary = muted, onSecondary = on(muted), outline = color("border2", muted), outlineVariant = color("border", chrome), error = error, onError = on(error))
}
@Composable fun NativeSkinTheme(dark: Boolean, skin: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val palettes = remember { context.assets.open("native-skins.json").bufferedReader().use { JSONObject(it.readText()) } }
    val values = palettes.optJSONObject(skin)?.optJSONObject(if (dark) "dark" else "light") ?: palettes.getJSONObject("default").getJSONObject(if (dark) "dark" else "light")
    val scheme = remember(values, dark) { nativeSkinColorScheme(values, dark) }
    val family = if (values.string("font-ui").contains("monospace")) FontFamily.Monospace else FontFamily.SansSerif
    val weight = if (skin in setOf("graphite", "github", "codex", "terracotta")) FontWeight(430) else FontWeight.Normal
    val spacing = if (skin == "geist-contrast") (-.16).sp else 0.sp
    val base = Typography()
    val typography = base.copy(displayLarge = base.displayLarge.copy(fontFamily = family), displayMedium = base.displayMedium.copy(fontFamily = family), displaySmall = base.displaySmall.copy(fontFamily = family), headlineLarge = base.headlineLarge.copy(fontFamily = family), headlineMedium = base.headlineMedium.copy(fontFamily = family), headlineSmall = base.headlineSmall.copy(fontFamily = family), bodyLarge = base.bodyLarge.copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing, fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = base.bodyMedium.copy(fontFamily = family, fontWeight = weight, letterSpacing = spacing, fontSize = 15.sp, lineHeight = 22.sp), bodySmall = base.bodySmall.copy(fontFamily = family, letterSpacing = spacing), titleLarge = base.titleLarge.copy(fontFamily = family), titleMedium = base.titleMedium.copy(fontFamily = family), titleSmall = base.titleSmall.copy(fontFamily = family), labelLarge = base.labelLarge.copy(fontFamily = family), labelMedium = base.labelMedium.copy(fontFamily = family), labelSmall = base.labelSmall.copy(fontFamily = family))
    MaterialTheme(colorScheme = scheme, typography = typography, shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp), extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp))) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
    }
}
