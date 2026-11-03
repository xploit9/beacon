package dev.hermes.android.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** How tall the fade at a scrolling card's edge is. */
internal val VerticalScrollFadeHeight = 28.dp

/** Which edges of a vertically scrolling area fade right now. */
internal data class VerticalFades(val top: Boolean, val bottom: Boolean)

/**
 * The fade shows only while there is more to scroll to: at the bottom while the area can still move forward, at the top (when the
 * area asks for one, [topToo]) once it has moved. An area whose text fits, or one scrolled to its end, shows no fade at all.
 */
internal fun verticalFades(canScrollBackward: Boolean, canScrollForward: Boolean, topToo: Boolean = false): VerticalFades =
    VerticalFades(top = topToo && canScrollBackward, bottom = canScrollForward)

/**
 * A soft fade at the bottom of a card that scrolls (and, with [top], at its top once it has scrolled), so text running past the edge
 * reads as "more" and not as cut off. Put it before `verticalScroll(state)` with the same [state]. The fade removes the content's own
 * pixels (an offscreen layer, [BlendMode.DstOut]), so it works on any background and in both themes.
 */
internal fun Modifier.verticalScrollFade(state: ScrollState, top: Boolean = false, height: Dp = VerticalScrollFadeHeight): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        val fades = verticalFades(state.canScrollBackward, state.canScrollForward, top)
        val fade = height.toPx().coerceAtMost(size.height / 2f)
        if (fades.bottom && fade > 0f) drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = size.height - fade, endY = size.height),
            Offset(0f, size.height - fade), Size(size.width, fade), blendMode = BlendMode.DstOut,
        )
        if (fades.top && fade > 0f) drawRect(
            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = 0f, endY = fade),
            Offset.Zero, Size(size.width, fade), blendMode = BlendMode.DstOut,
        )
    }
