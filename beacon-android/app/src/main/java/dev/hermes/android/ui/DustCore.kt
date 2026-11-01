package dev.hermes.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

private const val DustCoreSpecks = 10

/**
 * A turning core of gold dust: Beacon's one "working" glyph. It shows on the update step that is running, a Settings status that is
 * working, the reasoning and project placeholders, the offline banner while it reconnects, the drawer's presence mark and behind the
 * plugin card. A soft disc and ring, specks that orbit and twinkle, and a pulsing centre. It scales from the stepper's 24 dp, and it
 * holds still when the system turns animations off.
 */
@Composable
fun BeaconDustCore(size: Dp, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val clock = rememberBeaconClock(rememberNativeMotionEnabled())
    val specks = remember { val random = kotlin.random.Random(11); FloatArray(DustCoreSpecks * 3) { random.nextFloat() } }
    Canvas(modifier.size(size)) {
        // Read the clock only here, so a frame redraws without recomposing.
        val t = clock.floatValue
        val r = this.size.minDimension / 2f
        val unit = this.size.minDimension / 24.dp.toPx()
        drawCircle(color.copy(alpha = .16f), r)
        drawCircle(color.copy(alpha = .45f), r - .75.dp.toPx() * unit, style = Stroke(1.5.dp.toPx() * unit))
        for (i in 0 until DustCoreSpecks) {
            val orbit = r * (.32f + .5f * specks[i * 3])
            val angle = specks[i * 3 + 1] * 6.2832f + t * (1.2f + 1.4f * (1f - specks[i * 3]))
            val twinkle = .5f + .5f * (.5f + .5f * sin(t * 3.2f + specks[i * 3 + 2] * 6.2832f))
            val speck = ((.9f + .8f * specks[i * 3 + 2]) * unit).dp.toPx().coerceAtLeast(.8.dp.toPx())
            drawCircle(color.copy(alpha = twinkle), speck, center + Offset(cos(angle) * orbit, sin(angle) * orbit))
        }
        drawCircle(color, r * .2f * (1f + .18f * sin(t * 3f)))
    }
}
