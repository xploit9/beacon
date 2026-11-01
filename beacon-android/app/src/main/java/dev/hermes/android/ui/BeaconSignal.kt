package dev.hermes.android.ui

import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import dev.hermes.android.ui.sphere.fbm
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/** What Beacon's assistant dust is doing. The overlay picks one; the dust eases between them. */
enum class BeaconDustMood { Summon, Idle, Listening, Thinking, Speaking, Dismiss }

/** How long the dust takes to rise into the pill, and to fall away when the overlay closes. */
const val BEACON_DUST_SUMMON_MILLIS = 280
const val BEACON_DUST_DISMISS_MILLIS = 220

/**
 * Beacon's assistant dust: a luminous gold aurora that rises from the bottom edge like warm light through dust, with embers at three
 * depths drifting up through it, and a swarm that condenses into the glass pill and answers the voice.
 *
 * [pill] is the pill's bounds and [focus] the point listening dust streams to (the mic), both in this canvas's pixels. [crown] is the
 * top of what the overlay shows (the answer card, or the chips above the pill): thinking turns a slow vortex above it and speaking
 * breathes there, unless [presence] is off because another surface (the live call band) carries its own. [level] is the voice or reply
 * envelope, 0..1, read every frame. With [motion] off the dust holds a still constellation and only its glow follows [mood].
 * [scrim] is the colour the lower part of the screen deepens to under the dust (transparent for none), read while drawing so a
 * fading scrim does not recompose its caller. [onFrame] runs after every drawn frame, so a caller can time the first one.
 */
@Composable fun BeaconDust(
    mood: BeaconDustMood,
    pill: Rect,
    focus: Offset,
    level: () -> Float,
    modifier: Modifier = Modifier,
    motion: Boolean = rememberNativeMotionEnabled(),
    crown: Rect = Rect.Zero,
    presence: Boolean = true,
    scrim: () -> androidx.compose.ui.graphics.Color = { androidx.compose.ui.graphics.Color.Transparent },
    onFrame: () -> Unit = {},
) {
    val palette = if (MaterialTheme.colorScheme.background.luminance() < .5f) DarkDust else LightDust
    val field = remember { DustField(DUST_SEED) }
    val frame = remember { mutableLongStateOf(0L) }
    val drawn by rememberUpdatedState(onFrame)
    val currentLevel by rememberUpdatedState(level)
    val currentScrim by rememberUpdatedState(scrim)
    // The glow is the one thing reduced motion keeps: it eases with the mood, or snaps when motion is off.
    val glow by animateFloatAsState(dustGlow(mood), if (motion) tween(420) else snap(), label = "Dust glow")
    LaunchedEffect(motion) {
        if (!motion) return@LaunchedEffect
        // An infinite animation, like rememberInfiniteTransition: UI tests can idle while it runs.
        while (true) withInfiniteAnimationFrameNanos { frame.longValue = it }
    }
    Canvas(modifier.testTag("beacon-dust")) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val box = if (pill.width > 0f) pill else defaultPill(w, h, density)
        val top = if (crown.width > 0f) crown else box
        val target = if (focus == Offset.Unspecified) Offset(box.right - 40f * density, box.center.y) else focus
        val voice = if (motion) currentLevel().coerceIn(0f, 1f) else 0f
        field.step(frame.longValue, mood, motion, w, h, box, top, target, voice, presence, density)
        field.draw(drawContext.canvas.nativeCanvas, palette, glow, w, h, box, target, voice, density, currentScrim().toArgb())
        drawn()
    }
}

/** How bright the bloom is for each mood: calm at rest, warmer while listening, brightest while it speaks. */
fun dustGlow(mood: BeaconDustMood): Float = when (mood) {
    BeaconDustMood.Summon -> .7f
    BeaconDustMood.Idle -> .35f
    BeaconDustMood.Listening -> .8f
    BeaconDustMood.Thinking -> .55f
    BeaconDustMood.Speaking -> 1f
    BeaconDustMood.Dismiss -> 0f
}

/**
 * Seconds since the caller first showed, advanced every frame while [motion] is on; it stands still (at zero) with reduced motion.
 * Read it only while drawing, so a frame redraws without recomposing.
 */
@Composable fun rememberBeaconClock(motion: Boolean): FloatState {
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(motion) {
        if (!motion) { clock.floatValue = 0f; return@LaunchedEffect }
        var last = withInfiniteAnimationFrameNanos { it }
        while (true) {
            val now = withInfiniteAnimationFrameNanos { it }
            clock.floatValue += frameSeconds(now - last)
            last = now
        }
    }
    return clock
}

/**
 * How far one frame moves the dust, the glass and the presence on, in seconds: the real time since the last frame, so a slow frame
 * keeps pace instead of slowing everything into a stutter. A gap of over half a second is a pause (hidden or asleep) and moves nothing.
 */
internal fun frameSeconds(gapNanos: Long): Float = if (gapNanos <= 0L || gapNanos > FRAME_PAUSE_NANOS) 0f else gapNanos / 1e9f

private const val FRAME_PAUSE_NANOS = 500_000_000L

/**
 * Recolours whatever it wraps in Beacon gold, keeping its light and shade. The live band uses it on the app's call dust, so the
 * overlay's call is gold in every state while the call screen in the app keeps its own colours.
 */
fun Modifier.beaconGold(dark: Boolean): Modifier = drawWithContent {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), if (dark) GoldOnDark else GoldOnLight)
        drawContent()
        canvas.restore()
    }
}

/** Luminance to gold: bright specks turn pale champagne, mid tones rich gold, the haze deep amber. */
private fun goldPaint(r: Float, g: Float, b: Float, alpha: Float) = Paint().apply {
    fun row(k: Float) = floatArrayOf(.2126f * k, .7152f * k, .0722f * k, 0f, 0f)
    colorFilter = ColorFilter.colorMatrix(ColorMatrix(row(r) + row(g) + row(b) + floatArrayOf(0f, 0f, 0f, alpha, 0f)))
}
private val GoldOnDark = goldPaint(1.3f, .98f, .5f, 1f)
// On a light glass the dust is drawn darker and a little denser, so amber reads on cream.
private val GoldOnLight = goldPaint(.95f, .6f, .18f, 1.3f)

/** Where the pill sits before it is measured: 16 dp from the sides, 64 dp high, above the gesture bar. */
private fun defaultPill(w: Float, h: Float, density: Float) = Rect(16f * density, h - 112f * density, w - 16f * density, h - 48f * density)

private fun dustArgb(rgb: Int, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f + .5f).toInt() shl 24) or (rgb and 0xFFFFFF)

/**
 * One theme's dust. [additive] light adds to a dark screen; on a light one the colours are deeper and drawn over it, since adding
 * light to white shows nothing. Colours are 0xRRGGBB; the three specks are the far, middle and near layers.
 */
internal class DustPalette(
    val additive: Boolean,
    core: Int, deep: Int, rose: Int, horizon: Int,
    val specks: IntArray, val speckAlpha: FloatArray,
    val streak: Int,
    /** Scales every aurora light. */
    val aurora: Float,
) {
    private fun tint(rgb: Int) = PorterDuffColorFilter(rgb or 0xFF000000.toInt(), PorterDuff.Mode.SRC_IN)
    val core = tint(core)
    val deep = tint(deep)
    val rose = tint(rose)
    val horizon = tint(horizon)
    val ember = tint(specks[2])
}

private val DarkDust = DustPalette(
    additive = true, core = 0xFFBE5C, deep = 0xFF8A2E, rose = 0xFFA878, horizon = 0xFFEFC8,
    specks = intArrayOf(0xFFCF85, 0xFFBE55, 0xFFF2D2), speckAlpha = floatArrayOf(.45f, .66f, .95f), streak = 0xFFE2A0, aurora = 1f,
)
private val LightDust = DustPalette(
    additive = false, core = 0xF2A633, deep = 0xE0761A, rose = 0xE88E58, horizon = 0xF6BF52,
    specks = intArrayOf(0xC98A1E, 0xB4690F, 0xD27D0C), speckAlpha = floatArrayOf(.6f, .78f, .95f), streak = 0xC07A16, aurora = .8f,
)

/**
 * A soft round glow: white, falling off like a Gaussian to clear at its edge. Every bloom the dust draws is this one small texture,
 * tinted and stretched, so the aurora, the halos and the embers' bloom cost a textured quad each instead of a blur pass per frame.
 */
private val GlowSprite: Bitmap by lazy {
    val n = 96
    val c = (n - 1) / 2f
    val pixels = IntArray(n * n) { p ->
        val r = hypot(p % n - c, p / n - c) / c
        val a = if (r >= 1f) 0f else exp(-4.2f * r * r) * (1f - r.pow(8))
        ((a * 255f + .5f).toInt() shl 24) or 0xFFFFFF
    }
    Bitmap.createBitmap(pixels, n, n, Bitmap.Config.ARGB_8888)
}

private const val DUST_SEED = 4049L
/** The aurora's embers at three depths: many fine and far, fewer near and hot. */
private const val DUST_FAR = 250
private const val DUST_MID = 140
private const val DUST_NEAR = 44
private const val DUST_AURORA = DUST_FAR + DUST_MID + DUST_NEAR
private const val DUST_PILL = 220
private const val DUST_LAYERS = 3
/** An ember fades in as it leaves the edge and out near the top of the band, in this many steps. */
private const val DUST_LEVELS = 3
private val LayerStart = intArrayOf(0, DUST_FAR, DUST_FAR + DUST_MID, DUST_AURORA)
private val LayerSize = floatArrayOf(1.1f, 1.75f, 2.6f)
/** Parallax: the far layer drifts sideways slowest, the near one fastest. */
private val LayerDrift = floatArrayOf(.3f, .6f, 1f)
/** How fast each layer's embers rise through the band, in bands per second (scaled by each ember's own pace). */
private val LayerRise = floatArrayOf(.045f, .065f, .09f)
private val PillSize = floatArrayOf(1.3f, 1.9f, 2.8f)

/**
 * The specks as primitive arrays, so a frame allocates nothing. Aurora embers live in the bottom band; the pill's swarm condenses
 * into the pill, drifts inside it and answers the voice. Every speck has seeded parameters that place it for each mood.
 */
internal class DustField(seed: Long) {
    private val au = FloatArray(DUST_AURORA)
    private val av = FloatArray(DUST_AURORA)
    private val aphase = FloatArray(DUST_AURORA)
    private val apace = FloatArray(DUST_AURORA)
    private val ax = FloatArray(DUST_AURORA)
    private val ay = FloatArray(DUST_AURORA)
    private val alevel = IntArray(DUST_AURORA)
    /** Aurora embers sorted by layer and fade step, interleaved x,y; bucket b spans [auroraEdges] b..b+1. */
    val auroraPoints = FloatArray(DUST_AURORA * 2)
    val auroraEdges = IntArray(DUST_LAYERS * DUST_LEVELS + 1)
    private val cursor = IntArray(DUST_LAYERS * DUST_LEVELS)

    private val pu = FloatArray(DUST_PILL)
    private val pv = FloatArray(DUST_PILL)
    private val pphase = FloatArray(DUST_PILL)
    private val ppace = FloatArray(DUST_PILL)
    private val x = FloatArray(DUST_PILL)
    private val y = FloatArray(DUST_PILL)
    private val fromX = FloatArray(DUST_PILL)
    private val fromY = FloatArray(DUST_PILL)
    private val order = IntArray(DUST_PILL)
    /** The pill's swarm in size buckets (mostly fine, a few hot), interleaved x,y. */
    val pillPoints = FloatArray(DUST_PILL * 2)
    val pillEdges = intArrayOf(0, DUST_PILL * 70 / 100, DUST_PILL * 93 / 100, DUST_PILL)
    val lines = FloatArray(DUST_PILL * 4)
    var streaks = 0; private set

    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap = android.graphics.Paint.Cap.ROUND }
    /** The far layer's specks are a pixel or two: as squares without anti-aliasing they look the same and cost far less to draw. */
    private val fine = android.graphics.Paint().apply { strokeCap = android.graphics.Paint.Cap.SQUARE }
    private val sprite = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()

    var alpha = 1f; private set
    var auroraHeight = 0f; private set
    /** How much of the speaking presence and of the thinking vortex shows above the crown, eased. */
    var presence = 0f; private set
    var vortex = 0f; private set
    private var crownX = 0f
    private var crownY = 0f
    private var mood: BeaconDustMood? = null
    private var listening = false
    /** Seconds in the current mood and in total, counted from frame to frame so a paused or hidden window never skips ahead. */
    private var age = 0f
    private var clock = 0f
    private var last = 0L
    private var placed = false
    private var fallFrom = 0f
    private var t = 0f
    private var quickFirst = false

    init {
        val random = Random(seed)
        for (i in 0 until DUST_AURORA) { au[i] = random.nextFloat(); av[i] = random.nextFloat(); aphase[i] = random.nextFloat() * 6.2832f; apace[i] = .7f + .6f * random.nextFloat() }
        for (i in 0 until DUST_PILL) { pu[i] = random.nextFloat(); pv[i] = random.nextFloat(); pphase[i] = random.nextFloat() * 6.2832f; ppace[i] = .6f + .8f * random.nextFloat() }
        // A fixed mix of sizes, so the swarm always has a few hot specks among the fine ones.
        (0 until DUST_PILL).shuffled(random).forEachIndexed { slot, index -> order[slot] = index }
    }

    fun step(nanos: Long, next: BeaconDustMood, motion: Boolean, w: Float, h: Float, pill: Rect, crown: Rect, focus: Offset, level: Float, showPresence: Boolean, density: Float) {
        if (mood != next) {
            // The new mood starts from wherever every speck is now, so nothing jumps.
            mood = next; age = 0f; fallFrom = auroraHeight
            if (placed) { x.copyInto(fromX); y.copyInto(fromY) }
        }
        val dt = if (!placed || !motion) 0f else frameSeconds(nanos - last)
        last = nanos
        age += dt; clock += dt
        t = if (motion) clock else 0f
        val ageMillis = if (motion) age * 1000f else Float.MAX_VALUE
        // Tall enough to rise past the pill and the chips that sit on it, so the light blooms around them.
        val band = 230f * density
        val breathe = .84f + .16f * sin(t * .8f)
        val want = band * breathe * when (next) {
            BeaconDustMood.Listening -> 1f + .8f * level
            BeaconDustMood.Speaking -> 1.12f + .45f * level
            BeaconDustMood.Thinking -> .95f
            // The band blooms up from the edge, past its height and back, as the swarm condenses into the pill.
            BeaconDustMood.Summon -> easeOutBack((ageMillis / BEACON_DUST_SUMMON_MILLIS).coerceIn(0f, 1f))
            BeaconDustMood.Dismiss -> 0f
            BeaconDustMood.Idle -> 1f
        }
        auroraHeight = when {
            !motion || !placed || next == BeaconDustMood.Summon -> want
            next == BeaconDustMood.Dismiss -> fallFrom * (1f - (ageMillis / BEACON_DUST_DISMISS_MILLIS).coerceIn(0f, 1f)).pow(2)
            else -> auroraHeight + (want - auroraHeight) * (1f - exp(-5f * dt))
        }
        val ease = 1f - exp(-4f * dt)
        val speaking = if (showPresence && next == BeaconDustMood.Speaking) 1f else 0f
        val thinking = if (showPresence && next == BeaconDustMood.Thinking) 1f else 0f
        presence = if (!motion) speaking else presence + (speaking - presence) * ease
        vortex = if (!motion) thinking else vortex + (thinking - vortex) * ease
        // With reduced motion the summon is one frame and the open overlay the next, so that first frame (timed from the gesture)
        // draws only the specks; the soft light follows a frame later.
        quickFirst = !motion && next == BeaconDustMood.Summon
        alpha = when (next) {
            // The first frame already shows light at the bottom edge; it brightens as the band rises.
            BeaconDustMood.Summon -> if (motion) .5f + .5f * (ageMillis / 140f).coerceIn(0f, 1f) else 1f
            BeaconDustMood.Dismiss -> if (motion) 1f - (ageMillis / BEACON_DUST_DISMISS_MILLIS).coerceIn(0f, 1f) else 0f
            else -> 1f
        }
        stepAurora(w, h, motion, density)
        crownX = crown.center.x
        crownY = crown.top - 58f * density
        stepPill(next, motion, w, h, pill, focus, level, showPresence, ageMillis, dt, density)
        placed = true
    }

    /** Embers rise from the bottom edge through the band, sway on a slow flow and drift sideways at their layer's pace. */
    private fun stepAurora(w: Float, h: Float, motion: Boolean, density: Float) {
        val span = w + 40f * density
        val drift = t * 7f * density
        cursor.fill(0)
        for (layer in 0 until DUST_LAYERS) for (i in LayerStart[layer] until LayerStart[layer + 1]) {
            var life = av[i] + t * LayerRise[layer] * apace[i]
            life -= kotlin.math.floor(life)
            val flow = (fbm(au[i] * 3f + t * .05f, life * 2f - t * .03f, 2) - .45f) * 46f * density
            val sway = sin(t * (.4f + .3f * layer) + aphase[i]) * (5f + 4f * layer) * density
            var px = (au[i] * span + drift * LayerDrift[layer] + flow + sway) % span
            if (px < 0f) px += span
            ax[i] = px - 20f * density
            ay[i] = h - 1.5f * density - auroraHeight * 1.2f * life.pow(1.35f)
            // Faded in as it leaves the edge and out toward the top of the band; a still field keeps every ember it can show.
            val shown = smoothstep(0f, .1f, life) * (1f - smoothstep(.5f, 1f, life))
            val step = if (motion) min(DUST_LEVELS, (shown * (DUST_LEVELS + .999f)).toInt()) else max(1, min(DUST_LEVELS, (shown * (DUST_LEVELS + .999f)).toInt()))
            alevel[i] = step
            if (step > 0) cursor[layer * DUST_LEVELS + step - 1]++
        }
        auroraEdges[0] = 0
        for (b in cursor.indices) { auroraEdges[b + 1] = auroraEdges[b] + cursor[b]; cursor[b] = auroraEdges[b] }
        for (i in 0 until DUST_AURORA) {
            val step = alevel[i]
            if (step == 0) continue
            val layer = if (i < LayerStart[1]) 0 else if (i < LayerStart[2]) 1 else 2
            val k = cursor[layer * DUST_LEVELS + step - 1]++
            auroraPoints[k * 2] = ax[i]; auroraPoints[k * 2 + 1] = ay[i]
        }
    }

    private fun stepPill(next: BeaconDustMood, motion: Boolean, w: Float, h: Float, pill: Rect, focus: Offset, level: Float, showPresence: Boolean, ageMillis: Float, dt: Float, density: Float) {
        // With another surface carrying the voice (the live band), the swarm rests in the pill whatever the call is doing.
        val own = if (!showPresence && next in VoiceMoods) BeaconDustMood.Idle else next
        listening = own == BeaconDustMood.Listening
        streaks = 0
        val pad = 10f * density
        for (i in 0 until DUST_PILL) {
            val inside = insidePoint(i, pill, pad, density)
            var tx = inside.x
            var ty = inside.y
            when (own) {
                BeaconDustMood.Listening -> {
                    // Specks stream in to the mic like filings to a magnet; more of them join as the voice rises.
                    if (pv[i] < .25f + .75f * level) {
                        val pull = .45f + .5f * level
                        tx += (focus.x - tx) * pull; ty += (focus.y - ty) * pull
                    }
                }
                BeaconDustMood.Thinking -> {
                    // A slow vortex above the crown, a tilted disc whose inner specks turn faster.
                    val angle = pphase[i] + t * (.45f + .9f * (1f - pv[i])) * ppace[i]
                    val r = (14f + 70f * pv[i]) * density
                    tx = crownX + cos(angle) * r * 1.35f; ty = crownY + sin(angle) * r * .4f
                }
                BeaconDustMood.Speaking -> {
                    // A living presence above the crown that breathes outward in waves with the reply.
                    val angle = pphase[i] + t * .18f * ppace[i]
                    val wave = 1f + .16f * sin(t * 2.4f - pv[i] * 7f) + .45f * level
                    val r = (18f + 70f * pv[i]) * density * wave
                    tx = crownX + cos(angle) * r * 1.25f; ty = crownY + sin(angle) * r * .55f
                }
                else -> Unit
            }
            when {
                next == BeaconDustMood.Summon && motion -> {
                    // The swarm rises from the bottom edge and condenses onto the pill's outline, then settles inside.
                    val q = easeOut((ageMillis / BEACON_DUST_SUMMON_MILLIS * (.75f + .25f * ppace[i])).coerceIn(0f, 1f))
                    val outline = outlinePoint(pu[i], pill)
                    val startX = if (placed) fromX[i] else pu[i] * w
                    val startY = if (placed && fromY[i] < h) fromY[i] else h - (2f + 26f * pv[i]) * density
                    x[i] = startX + (outline.x - startX) * q
                    y[i] = startY + (outline.y - startY) * q
                }
                next == BeaconDustMood.Dismiss && motion -> {
                    // The pill comes apart into dust that falls to the bottom edge and fades.
                    val q = (ageMillis / BEACON_DUST_DISMISS_MILLIS).coerceIn(0f, 1f)
                    x[i] = fromX[i] + sin(pphase[i]) * 14f * density * q
                    y[i] = fromY[i] + (h - fromY[i] + 20f * density) * q * q * (.6f + .4f * ppace[i])
                }
                !motion || !placed -> { x[i] = tx; y[i] = ty }
                else -> {
                    val rate = if (own == BeaconDustMood.Listening) 9f else 3.2f
                    val k = 1f - exp(-rate * ppace[i] * dt)
                    val ox = x[i]; val oy = y[i]
                    x[i] += (tx - x[i]) * k; y[i] += (ty - y[i]) * k
                    if (own == BeaconDustMood.Listening && streaks < DUST_PILL) {
                        val s = streaks * 4
                        lines[s] = ox - (x[i] - ox) * 3f; lines[s + 1] = oy - (y[i] - oy) * 3f; lines[s + 2] = x[i]; lines[s + 3] = y[i]
                        streaks++
                    }
                }
            }
        }
        for (slot in 0 until DUST_PILL) { val i = order[slot]; pillPoints[slot * 2] = x[i]; pillPoints[slot * 2 + 1] = y[i] }
    }

    /** A resting place inside the pill that wanders slowly on the noise flow. */
    private fun insidePoint(i: Int, pill: Rect, pad: Float, density: Float): Offset {
        val wx = (fbm(pu[i] * 4f + t * .07f * ppace[i], pv[i] * 4f, 2) - .45f) * 30f * density
        val wy = (fbm(pv[i] * 4f - t * .06f * ppace[i], pu[i] * 4f + 3f, 2) - .45f) * 10f * density
        return Offset(
            (pill.left + pad + pu[i] * (pill.width - 2 * pad) + wx).coerceIn(pill.left + pad / 2, pill.right - pad / 2),
            (pill.top + pad + pv[i] * (pill.height - 2 * pad) + wy).coerceIn(pill.top + pad / 2, pill.bottom - pad / 2),
        )
    }

    /**
     * The soft layer first: the scrim, the aurora, the embers' bloom and the halos all go into one small buffer at a sixth of the
     * screen's resolution, which is then drawn once, stretched. Soft light loses nothing at that size (stretching it is itself a blur),
     * and the screen is filled once instead of by every overlapping glow: on a phone or emulator that renders in software, fill is
     * what a frame costs. Then the specks and the listening streaks, sharp, at full size. [scrim] is an ARGB colour.
     */
    fun draw(canvas: android.graphics.Canvas, p: DustPalette, glow: Float, w: Float, h: Float, pill: Rect, focus: Offset, voice: Float, density: Float, scrim: Int = 0) {
        val mode = if (p.additive) android.graphics.BlendMode.PLUS else android.graphics.BlendMode.SRC_OVER
        paint.blendMode = mode
        fine.blendMode = mode
        sprite.blendMode = mode
        val a = alpha
        if (!quickFirst) softLayer(canvas, p, glow, w, h, pill, focus, voice, density, scrim, mode, a)
        sharpLayer(canvas, p, glow, density, a)
    }

    private fun softLayer(canvas: android.graphics.Canvas, p: DustPalette, glow: Float, w: Float, h: Float, pill: Rect, focus: Offset, voice: Float, density: Float, scrim: Int, mode: android.graphics.BlendMode, a: Float) {
        val glows = lights(w, h)
        if (scrim ushr 24 != 0) {
            // The lower part of the screen deepens toward the theme's background, so the glass and the dust read over any app.
            val top = h * SCRIM_TOP
            if (scrimArgb != scrim || scrimHeight != h) {
                scrimArgb = scrim; scrimHeight = h
                scrimPaint.shader = android.graphics.LinearGradient(0f, top, 0f, h, scrim and 0xFFFFFF, scrim, android.graphics.Shader.TileMode.CLAMP)
            }
            glows.drawRect(0f, top, w, h, scrimPaint)
            lit = min(lit, top)
        }
        val warm = (.72f + .28f * glow) * a * p.aurora
        val band = max(auroraHeight, 1f)
        // Light through dust: a wide base, three curtains that drift and breathe out of step, and a hot line along the very edge.
        light(glows, p.core, w * .5f, h, w * .82f, band * .8f, .4f * warm)
        light(glows, p.deep, w * (.2f + .06f * sin(t * .11f)), h, w * .44f, band * 1.25f * (.9f + .1f * sin(t * .37f)), .4f * warm)
        light(glows, p.core, w * (.55f + .07f * sin(t * .083f + 1.7f)), h, w * .5f, band * 1.5f * (.9f + .1f * sin(t * .29f + 2f)), .48f * warm)
        light(glows, p.rose, w * (.84f + .05f * sin(t * .097f + 3.1f)), h, w * .42f, band * 1.15f * (.9f + .1f * sin(t * .41f + 4f)), .38f * warm)
        light(glows, p.horizon, w * .5f, h, w * .7f, 26f * density + band * .12f, .75f * warm)
        // The near embers carry a soft bloom of their own.
        val bloom = (.6f + .4f * glow) * a * p.aurora.coerceAtLeast(.8f)
        for (b in (DUST_LAYERS - 1) * DUST_LEVELS until DUST_LAYERS * DUST_LEVELS) {
            val step = b % DUST_LEVELS + 1
            val r = (8f + 3f * step) * density
            for (k in auroraEdges[b] until auroraEdges[b + 1]) light(glows, p.ember, auroraPoints[k * 2], auroraPoints[k * 2 + 1], r, r, .2f * step / DUST_LEVELS * bloom)
        }
        // A warm halo round the pill, the glow of the vortex and the presence above the crown, and the mic's glow while it listens.
        light(glows, p.core, pill.center.x, pill.center.y, pill.width * .58f, pill.height * 1.3f, (.1f + .14f * glow) * a)
        if (vortex > .01f) light(glows, p.deep, crownX, crownY, 120f * density, 44f * density, .3f * vortex * a)
        if (presence > .01f) {
            val r = (64f + 40f * voice) * density
            light(glows, p.core, crownX, crownY, r * 1.35f, r * .8f, .36f * presence * a)
            light(glows, p.horizon, crownX, crownY, r * .5f, r * .32f, .3f * presence * a)
        }
        if (listening) light(glows, p.horizon, focus.x, focus.y, (26f + 30f * voice) * density, (26f + 30f * voice) * density, (.3f + .45f * voice) * a)
        glows.restore()
        // Only the rows the light reached are drawn.
        val buffer = glowBuffer!!
        val top = (lit / GLOW_SCALE).toInt().coerceIn(0, buffer.height - 1)
        src.set(0, top, buffer.width, buffer.height)
        rect.set(0f, top * GLOW_SCALE, buffer.width * GLOW_SCALE, buffer.height * GLOW_SCALE)
        // Over a scrim the light was already added inside the buffer; on its own it adds to the screen.
        composite.blendMode = if (scrim ushr 24 != 0) android.graphics.BlendMode.SRC_OVER else mode
        canvas.drawBitmap(buffer, src, rect, composite)
    }

    private fun sharpLayer(canvas: android.graphics.Canvas, p: DustPalette, glow: Float, density: Float, a: Float) {
        for (layer in 0 until DUST_LAYERS) for (step in 1..DUST_LEVELS) {
            val b = layer * DUST_LEVELS + step - 1
            val count = auroraEdges[b + 1] - auroraEdges[b]
            if (count == 0) continue
            val brush = if (layer == 0) fine else paint
            brush.strokeWidth = LayerSize[layer] * density
            brush.color = dustArgb(p.specks[layer], p.speckAlpha[layer] * step / DUST_LEVELS * a * (.78f + .22f * glow))
            canvas.drawPoints(auroraPoints, 2 * auroraEdges[b], 2 * count, brush)
        }
        if (streaks > 0) {
            paint.strokeWidth = 1.2f * density
            paint.color = dustArgb(p.streak, .55f * a)
            canvas.drawLines(lines, 0, streaks * 4, paint)
        }
        for (b in 0 until 3) {
            paint.strokeWidth = PillSize[b] * density
            paint.color = dustArgb(p.specks[b], p.speckAlpha[b] * a * (.75f + .25f * glow))
            canvas.drawPoints(pillPoints, 2 * pillEdges[b], 2 * (pillEdges[b + 1] - pillEdges[b]), paint)
        }
    }

    private var glowBuffer: Bitmap? = null
    private var glowCanvas: android.graphics.Canvas? = null
    private val src = android.graphics.Rect()
    private val composite = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val scrimPaint = android.graphics.Paint()
    private var scrimArgb = 0
    private var scrimHeight = 0f
    /** The highest point any light reached this frame, in screen pixels. */
    private var lit = 0f

    /** The cleared light buffer for a [w] by [h] screen, scaled so lights are placed in screen pixels; restore it when done. */
    private fun lights(w: Float, h: Float): android.graphics.Canvas {
        val bw = kotlin.math.ceil(w / GLOW_SCALE).toInt().coerceAtLeast(1)
        val bh = kotlin.math.ceil(h / GLOW_SCALE).toInt().coerceAtLeast(1)
        val existing = glowBuffer
        val buffer = if (existing != null && existing.width == bw && existing.height == bh) existing
            else Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { glowBuffer = it; glowCanvas = android.graphics.Canvas(it) }
        buffer.eraseColor(0)
        lit = h
        val c = glowCanvas!!
        c.save()
        c.scale(1f / GLOW_SCALE, 1f / GLOW_SCALE)
        return c
    }

    /** One soft light: the glow sprite tinted with [tint], stretched to radii [rx] by [ry] round ([cx], [cy]). */
    private fun light(canvas: android.graphics.Canvas, tint: PorterDuffColorFilter, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Float) {
        if (alpha <= .004f || rx <= 0f || ry <= 0f) return
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        lit = min(lit, cy - ry)
        sprite.colorFilter = tint
        sprite.alpha = (alpha.coerceIn(0f, 1f) * 255f + .5f).toInt()
        canvas.drawBitmap(GlowSprite, null, rect, sprite)
    }
}

/** The light buffer is a sixth of the screen's resolution in each direction. */
private const val GLOW_SCALE = 6f
/** Where the scrim starts, as a fraction of the height from the top: the screen above it stays as it is. */
private const val SCRIM_TOP = .42f

private val VoiceMoods = setOf(BeaconDustMood.Listening, BeaconDustMood.Thinking, BeaconDustMood.Speaking)

private fun easeOut(q: Float) = 1f - (1f - q) * (1f - q) * (1f - q)

/** Rises past 1 and settles back: the aurora's bloom on summon. */
private fun easeOutBack(q: Float): Float { val s = 1.6f; val p = q - 1f; return 1f + (s + 1f) * p * p * p + s * p * p }

private fun smoothstep(from: Float, to: Float, v: Float): Float { val q = ((v - from) / (to - from)).coerceIn(0f, 1f); return q * q * (3f - 2f * q) }

/** The point at fraction [f] of the way round a fully rounded pill's outline. */
internal fun outlinePoint(f: Float, pill: Rect): Offset {
    val r = min(pill.height, pill.width) / 2f
    val straight = max(pill.width - 2 * r, 0f)
    val arc = (Math.PI * r).toFloat()
    val total = 2 * straight + 2 * arc
    var d = (f - kotlin.math.floor(f)) * total
    if (d < straight) return Offset(pill.left + r + d, pill.top)
    d -= straight
    if (d < arc) { val a = -Math.PI.toFloat() / 2 + d / r; return Offset(pill.right - r + cos(a) * r, pill.center.y + sin(a) * r) }
    d -= arc
    if (d < straight) return Offset(pill.right - r - d, pill.bottom)
    d -= straight
    val a = Math.PI.toFloat() / 2 + d / r
    return Offset(pill.left + r + cos(a) * r, pill.center.y + sin(a) * r)
}
