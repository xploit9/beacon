package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The bottom fade of a scrolling card (an approval's note, the call's transcript): drawn only while there is more to scroll to. The
 * card is red text-like content on a blue ground, so a faded edge shows the blue through; no theme, call or provider is involved.
 */
class VerticalScrollFadeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: ScrollState

    private fun show(content: Dp, top: Boolean) {
        compose.setContent {
            state = rememberScrollState()
            Box(Modifier.width(100.dp).background(Color.Blue).testTag("fade-host")) {
                // The card is as tall as its content up to 120 dp, then it scrolls, as an approval's note does.
                Box(Modifier.heightIn(max = 120.dp).verticalScrollFade(state, top = top).verticalScroll(state)) {
                    Box(Modifier.fillMaxWidth().height(content).background(Color.Red))
                }
            }
        }
    }

    private fun image(): PixelMap = compose.onNodeWithTag("fade-host").captureToImage().toPixelMap()

    /** How much of the pixel is the card's red rather than the ground's blue, 0..1. */
    private fun redShare(pixels: PixelMap, row: Int): Float {
        val p = pixels[pixels.width / 2, row.coerceIn(0, pixels.height - 1)]
        return p.red / (p.red + p.blue).coerceAtLeast(.001f)
    }

    private fun faded(share: Float) = share < .3f
    private fun whole(share: Float) = share > .85f

    @Test fun textThatScrollsFadesAtTheBottomAndNotAtTheTop() {
        show(content = 400.dp, top = false)
        compose.waitForIdle()
        val pixels = image()
        assertTrue("the bottom edge fades to the ground: ${redShare(pixels, pixels.height - 2)}", faded(redShare(pixels, pixels.height - 2)))
        assertTrue("the middle of the card is whole: ${redShare(pixels, pixels.height / 2)}", whole(redShare(pixels, pixels.height / 2)))
        assertTrue("nothing has scrolled, so the top is whole: ${redShare(pixels, 1)}", whole(redShare(pixels, 1)))
    }

    @Test fun theFadeIsGoneOnceTheTextIsScrolledToItsEnd() {
        show(content = 400.dp, top = false)
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { state.scrollTo(state.maxValue) } }
        compose.waitForIdle()
        val pixels = image()
        assertTrue("at the end there is nothing more below: ${redShare(pixels, pixels.height - 2)}", whole(redShare(pixels, pixels.height - 2)))
        assertTrue("the top has no fade unless asked for: ${redShare(pixels, 1)}", whole(redShare(pixels, 1)))
    }

    @Test fun textThatFitsHasNoFadeAtAll() {
        show(content = 100.dp, top = true)
        compose.waitForIdle()
        val pixels = image()
        assertTrue("a short note is whole at the bottom: ${redShare(pixels, pixels.height - 2)}", whole(redShare(pixels, pixels.height - 2)))
        assertTrue("and at the top: ${redShare(pixels, 1)}", whole(redShare(pixels, 1)))
    }

    @Test fun aTopFadeShowsOnlyOnceTheCardHasScrolled() {
        show(content = 400.dp, top = true)
        compose.waitForIdle()
        assertTrue("not scrolled: the top is whole", whole(redShare(image(), 1)))
        compose.runOnIdle { runBlocking { state.scrollTo(state.maxValue) } }
        compose.waitForIdle()
        val pixels = image()
        assertTrue("scrolled to the end: the top fades, so no line is half cut: ${redShare(pixels, 1)}", faded(redShare(pixels, 1)))
        assertTrue("and the bottom, the end of the text, is whole: ${redShare(pixels, pixels.height - 2)}", whole(redShare(pixels, pixels.height - 2)))
    }
}
