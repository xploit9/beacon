// The frame loop, the two-pole colour easing and the voice modulation are adapted from Hermes Relay (MIT)
// app/src/main/kotlin/com/hermesandroid/relay/ui/components/MorphingSphere.kt; the noise is MorphingSphereCore.kt's.
package dev.hermes.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.sphere.fbm
import dev.hermes.android.ui.sphere.lerp
import dev.hermes.android.voice.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Two colour poles as 0xRRGGBB. Each speck bucket mixes them by its own slow phase, so no state sits on one flat colour. */
internal class PresencePoles(val a: Int, val b: Int)

private val CalmPoles = PresencePoles(0x9C7C3E, 0x6F6A74)
private val ListeningPoles = PresencePoles(0x3D8BFF, 0x7CC4FF)
private val ThinkingPoles = PresencePoles(0xFFA21F, 0xFFD27A)
private val SpeakingPoles = PresencePoles(0x1FE0B0, 0x5CF2A0)
private val AlertPoles = PresencePoles(0xFF4040, 0xFF8A5C)

internal fun presencePoles(state: SphereState): PresencePoles = when (state) {
    SphereState.Idle -> CalmPoles
    SphereState.Listening -> ListeningPoles
    SphereState.Thinking -> ThinkingPoles
    SphereState.Speaking, SphereState.Streaming -> SpeakingPoles
    SphereState.Error -> AlertPoles
}

/** Follows [callStatusLabel]'s precedence, except that an inactive call is calm and an error turns the cloud red while the error text is on screen. */
internal fun presenceState(call: CallState): SphereState = when {
    !call.active -> SphereState.Idle
    call.error.isNotBlank() -> SphereState.Error
    call.held || call.state == "connecting" || call.state == "reconnecting" -> SphereState.Idle
    call.toolReceipts.any { it.statusLabel() != null } || call.state == "thinking" -> SphereState.Thinking
    call.remoteSpeaking || call.state == "speaking" -> SphereState.Speaking
    call.muted -> SphereState.Idle
    call.userSpeaking || call.state == "listening" -> SphereState.Listening
    else -> SphereState.Idle
}

internal fun presenceEnergy(amplitude: Float): Float = if (amplitude.isFinite()) amplitude.coerceIn(0f, 1f) else 0f
/** How much faster the flow runs, how far the cloud opens and how bright it gets at a given energy. */
internal fun presenceStir(energy: Float): Float = 1f + 3.5f * energy
internal fun presenceSpread(energy: Float): Float = 1f + .22f * energy
internal fun presenceGain(energy: Float): Float = .62f + .38f * energy

/** About one speck per 8 dp², in steps of 500 so a small resize does not refit the cloud. */
internal fun presenceParticleCount(widthDp: Float, heightDp: Float): Int =
    (widthDp * heightDp / 8f).toInt().coerceIn(600, PRESENCE_MAX_SPECKS) / 500 * 500

internal const val PRESENCE_MAX_SPECKS = 16000

internal const val PRESENCE_BUCKETS = 4
/** Bucket edges into the seeded arrays: most specks are dim and tinted, a few are hot and nearly white. */
internal fun presenceBucketEdges(count: Int): IntArray = intArrayOf(0, count / 2, count * 4 / 5, count * 19 / 20, count)

/** Interleaved x,y in unit space (-1..1 is the drawn area); the same seed always gives the same cloud. */
internal fun seedParticles(seed: Long, count: Int): FloatArray {
    val random = Random(seed)
    val xy = FloatArray(count * 2)
    var i = 0
    while (i < xy.size) {
        // Three uniforms sum to a soft bell, so the cloud thins out with no edge.
        val x = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) * .8f
        val y = (random.nextFloat() + random.nextFloat() + random.nextFloat() - 1.5f) * .8f
        // Specks gather on one contour of the noise, which reads as wispy strands with dark voids between them.
        val ridge = 1f - abs(2f * fbm(x * 1.8f + 4.1f, y * 1.8f - 2.7f, 4) / .9375f - 1f)
        if (random.nextFloat() < .04f + .96f * ridge * ridge * ridge * ridge) { xy[i] = x; xy[i + 1] = y; i += 2 }
    }
    return xy
}

/** ARGB for one speck bucket: the two eased poles mixed by [pulse], then lifted toward white by [lift]. */
internal fun presenceArgb(pole: FloatArray, pulse: Float, lift: Float, alpha: Float): Int {
    fun channel(i: Int) = (lerp(lerp(pole[i], pole[i + 3], pulse), 1f, lift).coerceIn(0f, 1f) * 255f + .5f).toInt()
    return ((alpha.coerceIn(0f, 1f) * 255f + .5f).toInt() shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
}

/** The specks as primitive arrays, so a frame allocates nothing per speck. */
internal class PresenceField(private val seed: Long) {
    var count = 0; private set
    /** Where each speck rests on its strand. */
    var home = FloatArray(0); private set
    /** Where each speck is now: home bent by the current flow. */
    var xy = FloatArray(0); private set
    var screen = FloatArray(0); private set
    var edges = presenceBucketEdges(0); private set
    /** The eased pole channels r1,g1,b1,r2,g2,b2, filled at draw time. */
    val pole = FloatArray(6)
    // The flow is sampled on a small grid once per frame and read back per speck, so thousands of specks cost a lookup each.
    private val grid = FloatArray(GRID * GRID * 2)

    /** Seeds once at the cap; a smaller area uses the first [count] specks, which are the same cloud at that count. */
    fun fit(count: Int) {
        if (count == this.count) return
        if (home.isEmpty()) {
            home = seedParticles(seed, PRESENCE_MAX_SPECKS)
            xy = home.copyOf()
            screen = FloatArray(home.size)
        }
        this.count = count.coerceAtMost(PRESENCE_MAX_SPECKS)
        edges = presenceBucketEdges(this.count)
    }

    /** Bends the strands with a slowly turning, divergence-free noise flow; voice bends them further. */
    fun step(time: Float, energy: Float) {
        val reach = .09f + .05f * presenceStir(energy)
        val drift = time * .05f
        for (gy in 0 until GRID) for (gx in 0 until GRID) {
            val u = (gx * CELL - GRID_EXTENT) * 1.4f + drift
            val v = (gy * CELL - GRID_EXTENT) * 1.4f - drift * .7f
            val n = fbm(u, v, 2)
            val k = (gy * GRID + gx) * 2
            grid[k] = (fbm(u, v + FLOW_STEP, 2) - n) / FLOW_STEP * reach
            grid[k + 1] = (n - fbm(u + FLOW_STEP, v, 2)) / FLOW_STEP * reach
        }
        val turn = time * .04f
        val cos = cos(turn)
        val sin = sin(turn)
        var i = 0
        while (i < count * 2) {
            val hx = home[i]
            val hy = home[i + 1]
            val fx = ((hx + GRID_EXTENT) / CELL).coerceIn(0f, GRID - 1.001f)
            val fy = ((hy + GRID_EXTENT) / CELL).coerceIn(0f, GRID - 1.001f)
            val x0 = fx.toInt()
            val y0 = fy.toInt()
            val tx = fx - x0
            val ty = fy - y0
            val a = (y0 * GRID + x0) * 2
            val b = a + GRID * 2
            val x = hx + lerp(lerp(grid[a], grid[a + 2], tx), lerp(grid[b], grid[b + 2], tx), ty)
            val y = hy + lerp(lerp(grid[a + 1], grid[a + 3], tx), lerp(grid[b + 1], grid[b + 3], tx), ty)
            xy[i] = x * cos - y * sin
            xy[i + 1] = x * sin + y * cos
            i += 2
        }
    }
}

private const val GRID = 33
private const val GRID_EXTENT = 1.4f
private const val CELL = GRID_EXTENT * 2 / (GRID - 1)
private const val FLOW_STEP = .03f
private const val PRESENCE_SEED = 1729L
private val BucketAlpha = floatArrayOf(.3f, .42f, .6f, .9f)
private val BucketLift = floatArrayOf(.2f, .5f, .7f, .9f)
private val BucketSize = floatArrayOf(1f, 1.1f, 1.3f, 1.8f)

private fun poleChannel(rgb: Int, shift: Int) = ((rgb shr shift) and 255) / 255f

/** A drifting dust cloud that takes its colour from the call state and stirs and brightens with voice. */
@Composable internal fun VoicePresence(call: CallState, modifier: Modifier, motionEnabled: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val motionAllowed = motionEnabled && call.active && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val poles = presencePoles(presenceState(call))
    val ease: FiniteAnimationSpec<Float> = if (motionAllowed) tween(800, easing = FastOutSlowInEasing) else snap()
    val r1 = animateFloatAsState(poleChannel(poles.a, 16), ease, label = "Presence colour")
    val g1 = animateFloatAsState(poleChannel(poles.a, 8), ease, label = "Presence colour")
    val b1 = animateFloatAsState(poleChannel(poles.a, 0), ease, label = "Presence colour")
    val r2 = animateFloatAsState(poleChannel(poles.b, 16), ease, label = "Presence colour")
    val g2 = animateFloatAsState(poleChannel(poles.b, 8), ease, label = "Presence colour")
    val b2 = animateFloatAsState(poleChannel(poles.b, 0), ease, label = "Presence colour")
    // Reduced motion, a hidden screen or an ended call settle the cloud: no stirring, only the state colour.
    val energy = animateFloatAsState(if (motionAllowed) presenceEnergy(call.voiceAmplitude()) else 0f,
        if (motionAllowed) tween(110, easing = FastOutSlowInEasing) else snap(), label = "Presence voice")
    val field = remember { PresenceField(PRESENCE_SEED) }
    // The drawn height changes as captions grow: the first height lands at once, later ones ease instead of jumping.
    val halfHeight = remember { mutableFloatStateOf(0f) }
    val easedHalfHeight = remember { Animatable(0f) }
    LaunchedEffect(halfHeight.floatValue, motionAllowed) {
        val target = halfHeight.floatValue
        if (target <= 0f) return@LaunchedEffect
        if (easedHalfHeight.value <= 0f || !motionAllowed) easedHalfHeight.snapTo(target)
        else easedHalfHeight.animateTo(target, tween(450, easing = FastOutSlowInEasing))
    }
    val time = remember { mutableFloatStateOf(0f) }
    val paint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = android.graphics.Paint.Cap.ROUND
            blendMode = android.graphics.BlendMode.PLUS
        }
    }
    // Specks of a pixel or two look the same as squares without anti-aliasing, and cost far less to draw.
    val fine = remember {
        android.graphics.Paint().apply {
            strokeCap = android.graphics.Paint.Cap.SQUARE
            blendMode = android.graphics.BlendMode.PLUS
        }
    }
    LaunchedEffect(motionAllowed) {
        if (!motionAllowed) return@LaunchedEffect
        // An infinite animation, like rememberInfiniteTransition: UI tests can idle while the clock runs itself.
        var last = withInfiniteAnimationFrameNanos { it }
        var pending = 0f
        while (true) {
            val now = withInfiniteAnimationFrameNanos { it }
            pending += (now - last).coerceIn(0L, 100_000_000L) / 1_000_000_000f
            last = now
            val e = energy.value
            // Silent drift is slow, so about 15 fps is enough and the loop sleeps between steps; voice gets every frame.
            if (!voiceMotionActive(e, true, true)) {
                if (pending < .066f) { kotlinx.coroutines.delay(((.066f - pending) * 1000).toLong().coerceAtLeast(1)); continue }
            }
            // Voice runs the flow's clock faster, so the strands stir while someone speaks.
            time.floatValue += pending * presenceStir(e)
            field.step(time.floatValue, e)
            pending = 0f
        }
    }

    Canvas(modifier.clipToBounds().testTag("beacon-voice-presence").semantics {
        contentDescription = dev.hermes.android.data.currentAssistantName + " voice activity"
        stateDescription = callStatusLabel(call)
    }) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // Reduced motion draws the current state directly, without easing, so nothing changes between frames.
        val still = !motionAllowed
        val t = time.floatValue
        val e = if (still) 0f else energy.value
        val wanted = presenceParticleCount(w / density, h / density)
        if (field.count != wanted) {
            field.fit(wanted)
            field.step(t, e)
        }
        val pole = field.pole
        if (still) {
            pole[0] = poleChannel(poles.a, 16); pole[1] = poleChannel(poles.a, 8); pole[2] = poleChannel(poles.a, 0)
            pole[3] = poleChannel(poles.b, 16); pole[4] = poleChannel(poles.b, 8); pole[5] = poleChannel(poles.b, 0)
        } else {
            pole[0] = r1.value; pole[1] = g1.value; pole[2] = b1.value
            pole[3] = r2.value; pole[4] = g2.value; pole[5] = b2.value
        }

        val cx = w / 2f
        val cy = h / 2f
        if (halfHeight.floatValue != cy) halfHeight.floatValue = cy
        val spread = presenceSpread(e)
        val sx = cx * .95f * spread
        val sy = (easedHalfHeight.value.takeIf { it > 0f && !still } ?: cy) * .95f * spread
        val gain = presenceGain(e)
        val phase = t * .6f

        // A broad haze and a small hot core in the state colour; the core wanders a little inside the cloud.
        val haze = Color(presenceArgb(pole, sin(phase) * .5f + .5f, .05f, (.14f + .2f * e).coerceAtMost(1f)))
        scale(1f, sy / sx, Offset(cx, cy)) {
            drawCircle(Brush.radialGradient(listOf(haze, Color.Transparent), Offset(cx, cy), sx * .62f), sx * .62f, Offset(cx, cy), blendMode = BlendMode.Plus)
        }
        val heart = Offset(cx + sx * .12f * sin(t * .23f), cy + sy * .1f * sin(t * .31f + 1f))
        val core = Color(presenceArgb(pole, sin(phase + 1f) * .5f + .5f, .15f, (.5f + .35f * e).coerceAtMost(1f)))
        drawCircle(Brush.radialGradient(listOf(core, Color.Transparent), heart, sx * .16f), sx * .16f, heart, blendMode = BlendMode.Plus)

        val xy = field.xy
        val out = field.screen
        var i = 0
        while (i < field.count * 2) {
            out[i] = cx + xy[i] * sx
            out[i + 1] = cy + xy[i + 1] * sy
            i += 2
        }
        val dot = (minOf(w, h) / 520f).coerceIn(.9f, 2.2f)
        val canvas = drawContext.canvas.nativeCanvas
        // A faint wide pass over a quarter of the dimmest specks turns the strands into smoke.
        paint.color = presenceArgb(pole, sin(phase) * .5f + .5f, .1f, .1f * gain)
        paint.strokeWidth = dot * 6f
        canvas.drawPoints(out, 0, 2 * (field.edges[1] / 4), paint)
        for (b in 0 until PRESENCE_BUCKETS) {
            val shimmer = 1f + .12f * sin(t * 1.1f + b * 2.3f)
            // Only the brightest, largest specks are round.
            val brush = if (b == PRESENCE_BUCKETS - 1) paint else fine
            brush.color = presenceArgb(pole, sin(phase + b * 1.7f) * .5f + .5f, BucketLift[b], BucketAlpha[b] * gain * shimmer)
            brush.strokeWidth = dot * BucketSize[b]
            canvas.drawPoints(out, 2 * field.edges[b], 2 * (field.edges[b + 1] - field.edges[b]), brush)
        }
    }
}
