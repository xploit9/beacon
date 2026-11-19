package dev.hermes.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The welcome (§8, Root's follow-up 1): the first thing the owner sees before {name} has a phone. Beacon's dust gathers into a phone,
 * then the promise in three short rows, then Get started or Not now. It fills the page; the rows scroll and the two answers stay put
 * at any text size.
 */
@Composable internal fun AgentPhoneWelcome(actions: AgentPhonePageActions, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize().testTag("agent-phone-intro")) {
        // On a wide window (the unfolded phone) the dust spreads across the whole width and grows; the words keep a readable column.
        val wide = maxWidth >= 600.dp
        val dust = (maxHeight * (if (wide) .42f else .36f)).coerceIn(168.dp, if (wide) 380.dp else 300.dp)
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val area = maxHeight
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = area).padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        DustPhone(Modifier.fillMaxWidth().height(dust))
                        Column(Modifier.widthIn(max = 480.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(24.dp))
                            Text(uiText("Give {name} its own phone").named(), Modifier.semantics { heading() },
                                style = MaterialTheme.typography.headlineMedium.copy(lineBreak = LineBreak.Heading), textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(32.dp))
                            Column(Modifier.widthIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                WelcomeRow(Icons.AutoMirrored.Rounded.Login, uiText("Signs in to apps for you"))
                                WelcomeRow(Icons.Rounded.ShoppingBag, uiText("Shops with your OK"))
                                WelcomeRow(Icons.Rounded.Key, uiText("Logins stay in your vault"))
                            }
                        }
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Button(actions.getStarted, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 52.dp).testTag("agent-phone-get-started")) {
                    Text(uiText("Get started"), textAlign = TextAlign.Center)
                }
                TextButton(actions.notNow, Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 48.dp).testTag("agent-phone-intro-not-now")) {
                    Text(uiText("Not now"), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable private fun WelcomeRow(icon: ImageVector, text: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * Beacon's gold dust drifting in from all around and gathering into the outline of a phone, which then breathes: one light runs once
 * around its edge, the specks shimmer and a few loose ones keep circling it. Reduced motion draws the phone gathered and still.
 * Decoration only: TalkBack skips it.
 */
@Composable internal fun DustPhone(modifier: Modifier = Modifier, motion: Boolean = rememberNativeMotionEnabled()) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val clock = rememberBeaconClock(motion)
    val field = remember { DustPhoneField() }
    val specks = if (dark) DUST_PHONE_DARK else DUST_PHONE_LIGHT
    val glowColor = Color(if (dark) 0xFFFFBE5C else 0xFFF2A633)
    Canvas(modifier.testTag("agent-phone-welcome-dust")) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val t = clock.floatValue
        val layout = dustPhoneLayout(size.width, size.height, density)
        val box = layout.box
        val settle = if (motion) dustPhoneSettle(t) else 1f
        val breath = if (motion) .85f + .15f * sin(t * .6f) else 1f
        // A warm glow behind the phone, and its screen lit faintly once the dust has landed.
        drawCircle(Brush.radialGradient(listOf(glowColor.copy(alpha = (if (dark) .2f else .12f) * breath * (.35f + .65f * settle)), Color.Transparent),
            center = layout.center, radius = box.height * .78f), radius = box.height * .78f, center = layout.center)
        drawRoundRect(Brush.verticalGradient(listOf(glowColor.copy(alpha = (if (dark) .11f else .08f) * settle), glowColor.copy(alpha = .015f * settle)), box.top, box.bottom),
            topLeft = box.topLeft, size = Size(box.width, box.height), cornerRadius = CornerRadius(layout.corner))
        val blend = if (dark) BlendMode.Plus else BlendMode.SrcOver
        for (i in 0 until field.count) {
            val p = field.position(i, t, motion, layout)
            val land = if (motion) field.landed(i, t) else 1f
            val boost = if (motion) field.sweep(i, t) else 0f
            val twinkle = if (motion && field.kind(i) != DustPhoneField.LOOSE) .72f + .28f * sin(t * 1.7f + field.phase(i)) else 1f
            val alpha = (field.alpha(i) * twinkle * (.25f + .75f * land) + (if (dark) .45f else .1f) * boost).coerceIn(0f, 1f)
            val radius = field.radius(i) * density * (1f + (if (dark) .5f else .15f) * boost)
            val color = Color(specks[i % specks.size])
            if (boost > 0f) drawCircle(glowColor.copy(alpha = (if (dark) .3f else .4f) * boost), radius * 4.5f, p, blendMode = blend)
            if (field.kind(i) != DustPhoneField.LOOSE) drawCircle(color.copy(alpha = alpha * .16f), radius * 2.8f, p, blendMode = blend)
            drawCircle(color.copy(alpha = alpha), radius, p, blendMode = blend)
        }
    }
}

private val DUST_PHONE_DARK = longArrayOf(0xFFFFCF85, 0xFFFFBE55, 0xFFFFF2D2)
private val DUST_PHONE_LIGHT = longArrayOf(0xFFC98A1E, 0xFFB4690F, 0xFFD27D0C)

/** Where the phone sits in a [width] x [height] canvas: centred, half as wide as it is tall, with rounded corners. */
internal class DustPhoneLayout(val box: Rect, val corner: Float, val center: Offset, val reach: Float, val density: Float)

internal fun dustPhoneLayout(width: Float, height: Float, density: Float): DustPhoneLayout {
    val tall = min(height * .78f, width * .7f * 2f)
    val wide = tall * .5f
    val center = Offset(width / 2f, height / 2f)
    val box = Rect(center.x - wide / 2f, center.y - tall / 2f, center.x + wide / 2f, center.y + tall / 2f)
    return DustPhoneLayout(box, wide * .2f, center, max(width, height) * .62f, density)
}

/** How far the phone's light has come on, 0 while the dust gathers to 1 once it has landed. */
internal fun dustPhoneSettle(seconds: Float): Float = smooth((seconds - 1.6f) / 1f)

private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

/** The point a [fraction] (0..1) of the way round a rounded rectangle, clockwise from the start of its top edge. */
internal fun roundedRectPoint(fraction: Float, box: Rect, corner: Float): Offset {
    val r = corner.coerceAtMost(min(box.width, box.height) / 2f)
    val across = box.width - 2f * r
    val down = box.height - 2f * r
    val arc = (PI.toFloat() / 2f) * r
    var d = ((fraction % 1f + 1f) % 1f) * (2f * across + 2f * down + 4f * arc)
    fun corner(cx: Float, cy: Float, from: Float, along: Float): Offset {
        val a = from + along / r
        return Offset(cx + r * cos(a), cy + r * sin(a))
    }
    if (d < across) return Offset(box.left + r + d, box.top)
    d -= across
    if (d < arc) return corner(box.right - r, box.top + r, -PI.toFloat() / 2f, d)
    d -= arc
    if (d < down) return Offset(box.right, box.top + r + d)
    d -= down
    if (d < arc) return corner(box.right - r, box.bottom - r, 0f, d)
    d -= arc
    if (d < across) return Offset(box.right - r - d, box.bottom)
    d -= across
    if (d < arc) return corner(box.left + r, box.bottom - r, PI.toFloat() / 2f, d)
    d -= arc
    if (d < down) return Offset(box.left, box.bottom - r - d)
    d -= down
    return corner(box.left + r, box.top + r, PI.toFloat(), d.coerceAtMost(arc))
}

/**
 * The specks of [DustPhone]: [OUTLINE] ones land evenly on the phone's edge, [SPEAKER] ones make its speaker slit, [LOOSE] ones keep
 * circling it. Positions depend only on the seconds since the welcome showed, so a slow frame never stutters (see frameSeconds).
 */
internal class DustPhoneField(seed: Long = 0x5EED_0F_A6E7L, outline: Int = 120, speaker: Int = 6, loose: Int = 60) {
    val count = outline + speaker + loose
    private val outlineCount = outline
    private val speakerCount = speaker
    private val kinds = IntArray(count) { if (it < outline) OUTLINE else if (it < outline + speaker) SPEAKER else LOOSE }
    private val along = FloatArray(count)
    private val startAngle = FloatArray(count)
    private val startReach = FloatArray(count)
    private val delays = FloatArray(count)
    private val phases = FloatArray(count)
    private val sizes = FloatArray(count)
    private val alphas = FloatArray(count)
    private val spin = FloatArray(count)
    private val orbitX = FloatArray(count)
    private val orbitY = FloatArray(count)

    init {
        val random = Random(seed)
        for (i in 0 until count) {
            along[i] = if (i < outline) (i + random.nextFloat() * .35f) / outline else 0f
            startAngle[i] = random.nextFloat() * 2f * PI.toFloat()
            startReach[i] = .55f + .45f * random.nextFloat()
            delays[i] = random.nextFloat() * .8f
            phases[i] = random.nextFloat() * 2f * PI.toFloat()
            when (kinds[i]) {
                LOOSE -> {
                    sizes[i] = .7f + .7f * random.nextFloat()
                    alphas[i] = .25f + .3f * random.nextFloat()
                    spin[i] = (.06f + .1f * random.nextFloat()) * if (random.nextBoolean()) 1f else -1f
                    orbitX[i] = random.nextFloat()
                    orbitY[i] = random.nextFloat()
                }
                else -> {
                    sizes[i] = 1.4f + 1f * random.nextFloat()
                    alphas[i] = .7f + .3f * random.nextFloat()
                }
            }
        }
    }

    fun kind(i: Int) = kinds[i]
    fun phase(i: Int) = phases[i]
    fun radius(i: Int) = sizes[i]
    fun alpha(i: Int) = alphas[i]

    /** Where speck [i] comes to rest. */
    fun target(i: Int, layout: DustPhoneLayout): Offset = when (kinds[i]) {
        OUTLINE -> roundedRectPoint(along[i], layout.box, layout.corner)
        SPEAKER -> {
            val j = i - outlineCount
            val span = layout.box.width * .24f
            Offset(layout.center.x + (j / (speakerCount - 1f).coerceAtLeast(1f) - .5f) * span, layout.box.top + layout.corner * .75f)
        }
        else -> loose(i, 0f, layout)
    }

    /** How far speck [i] has come in, 0 at its start to 1 on the phone, easing out. */
    fun landed(i: Int, seconds: Float): Float {
        if (kinds[i] == LOOSE) return smooth(seconds / 1.2f)
        val x = ((seconds - delays[i]) / GATHER_SECONDS).coerceIn(0f, 1f)
        return 1f - (1f - x) * (1f - x) * (1f - x)
    }

    /** The one light that runs once round the edge as the phone completes. */
    fun sweep(i: Int, seconds: Float): Float {
        if (kinds[i] != OUTLINE) return 0f
        val head = (seconds - SWEEP_START) / SWEEP_SECONDS
        if (head !in 0f..1f) return 0f
        val gap = abs(along[i] - head).let { min(it, 1f - it) }
        return (1f - gap / .06f).coerceIn(0f, 1f)
    }

    /** Where speck [i] is [seconds] after the welcome showed; with [motion] off, where it rests. */
    fun position(i: Int, seconds: Float, motion: Boolean, layout: DustPhoneLayout): Offset {
        if (kinds[i] == LOOSE) return loose(i, if (motion) seconds else 0f, layout)
        val target = target(i, layout)
        if (!motion) return target
        val e = landed(i, seconds)
        val start = layout.center + Offset(cos(startAngle[i]), sin(startAngle[i])) * (startReach[i] * layout.reach)
        val turn = SWIRL * (1f - e)
        val rel = start - layout.center
        val from = layout.center + Offset(rel.x * cos(turn) - rel.y * sin(turn), rel.x * sin(turn) + rel.y * cos(turn))
        val shimmer = SHIMMER_DP * layout.density * e
        return lerp(from, target, e) + Offset(sin(seconds * 1.3f + phases[i]) * shimmer, cos(seconds * 1.1f + phases[i]) * shimmer)
    }

    private fun loose(i: Int, seconds: Float, layout: DustPhoneLayout): Offset {
        val box = layout.box
        val rx = min(box.width * .78f + (layout.center.x * .92f - box.width * .78f) * orbitX[i], layout.center.x * .94f)
        val ry = min(box.height * .42f + (layout.center.y * .92f - box.height * .42f) * orbitY[i], layout.center.y * .94f)
        val angle = startAngle[i] + spin[i] * seconds
        val swell = 1f + .04f * sin(seconds * .5f + phases[i])
        return layout.center + Offset(cos(angle) * rx * swell, sin(angle) * ry * swell)
    }

    companion object {
        const val OUTLINE = 0
        const val SPEAKER = 1
        const val LOOSE = 2
        const val GATHER_SECONDS = 1.7f
        const val SWEEP_START = 2.5f
        const val SWEEP_SECONDS = 1.2f
        const val SHIMMER_DP = .7f
        const val SWIRL = 1.1f
    }
}
