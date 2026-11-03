package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.hermes.android.data.NativeSkill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * Lane V2A: the full-pass visual fixes that live in shared components. Each test names the screen it protects. The text sizes are
 * set with a local Density, so "large text" is the same 1.3x the owner's tour uses.
 */
class LaneV2aVisualUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(dark: Boolean = true, fontScale: Float = 1f, scroll: Boolean = true, content: @Composable () -> Unit) = compose.setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                if (scroll) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } else Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }

    private fun heightOf(text: String) = compose.onNodeWithText(text, useUnmergedTree = true).getUnclippedBoundsInRoot().height.value

    // One search field everywhere (More, Skills, Tools, Models, Commands, Logs)

    @Test fun theSearchFieldIsOnePillWithAPlaceholderAndAClearButtonThatAppearsWithText() {
        var query by mutableStateOf("")
        show { SettingsSearchField(query, { query = it }, "Search anything", testTag = "kit-search") }
        compose.onNodeWithTag("kit-search").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Search anything").assertExists()
        compose.onAllNodesWithContentDescription("Clear search").assertCountEquals(0)
        compose.onNodeWithTag("kit-search").performTextInput("abc")
        compose.runOnIdle { assertEquals("abc", query) }
        compose.onNodeWithContentDescription("Clear search").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle { assertEquals("", query) }
        compose.onAllNodesWithContentDescription("Clear search").assertCountEquals(0)
        compose.onNodeWithText("Search anything").assertExists()
    }

    // Status hero (Connection, Health, Server updates): a glyph in the state's colour, not a dot in an empty tile

    private fun inkShare(tag: String, ink: Color): Float {
        val pixels = compose.onNodeWithTag(tag).captureToImage().toPixelMap()
        var hits = 0
        for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
            val c = pixels[x, y]
            if (abs(c.red - ink.red) < .1f && abs(c.green - ink.green) < .1f && abs(c.blue - ink.blue) < .1f) hits++
        }
        return hits.toFloat() / (pixels.width * pixels.height)
    }

    private fun heroTileShowsTheGlyph(dark: Boolean) {
        show(dark) {
            SettingsStatusHero(SettingsStatus.Ok, "Connected", line = "example.com", testTag = "hero-ok")
            SettingsStatusHero(SettingsStatus.Attention, "Update available", line = "1.1.26", testTag = "hero-attention")
            SettingsStatusHero(SettingsStatus.Error, "Can't reach Hermes", line = "example.com", testTag = "hero-error")
        }
        compose.waitForIdle()
        val palette = settingsStatusPalette(if (dark) darkColorScheme() else lightColorScheme())
        for ((tag, ink) in listOf("hero-ok" to palette.ok, "hero-attention" to palette.attention, "hero-error" to palette.error)) {
            val share = inkShare("$tag-tile", ink)
            assertTrue("$tag tile: only ${(share * 100).toInt()}% of it is the status colour, so it reads as an empty tile with a dot", share > .04f)
        }
    }

    @Test fun theHeroTileShowsTheStateGlyphInItsColourInDark() = heroTileShowsTheGlyph(dark = true)
    @Test fun theHeroTileShowsTheStateGlyphInItsColourInLight() = heroTileShowsTheGlyph(dark = false)

    // Large text keeps the meaning (Appearance, Skills)

    private val longDetail = "A much longer detail that explains this choice in enough words to wrap onto a second line at large text, and then some more words after it"

    @Test fun anOptionCardsDetailTakesTwoLinesAtLargeText() {
        show(fontScale = 1.3f) {
            SettingsOptionCards(listOf(SettingsOption("a", "Short", "Brief detail"), SettingsOption("b", "Long", longDetail)), "a", {})
        }
        val one = heightOf("Brief detail")
        val two = heightOf(longDetail)
        assertTrue("A long detail is $two dp tall against $one dp for a short one: it must show two lines, not one", two >= one * 1.8f)
    }

    @Test fun aSwatchLabelTakesTwoLinesAtLargeText() {
        val swatches = listOf(
            SettingsSwatch("a", "Sky", Color(0xFF3B82F6), Color(0xFF0B1220)),
            SettingsSwatch("b", "Evening Harbour Lights", Color(0xFFF59E0B), Color(0xFF1B1409)),
        )
        show(fontScale = 1.3f) { SettingsSwatchPicker(swatches, "a", {}) }
        val one = heightOf("Sky")
        val two = heightOf("Evening Harbour Lights")
        assertTrue("A long style name is $two dp tall against $one dp for a short one: it must show two lines, not one", two >= one * 1.8f)
    }

    private val longSkill = "Plans trips from verified sources, compares routes and prices, and keeps every booking detail in one itinerary you can review before you go"
    private fun showSkills(fontScale: Float) = show(fontScale = fontScale, scroll = false) {
        val state = NativeSkillsState(skills = listOf(
            NativeSkill("quick-note", "Jots a note", "notes", true, "built-in"),
            NativeSkill("travel-planning", longSkill, "notes", true, "built-in"),
        ))
        NativeSkillsScreen(state, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun aSkillDescriptionStaysOneLineAtNormalTextSize() {
        showSkills(1f)
        assertTrue("A long description stays on one line at normal size", heightOf(longSkill) < heightOf("Jots a note") * 1.5f)
    }

    @Test fun aSkillDescriptionTakesTwoLinesAtLargeText() {
        showSkills(1.3f)
        assertTrue("A long description shows two lines at large text", heightOf(longSkill) >= heightOf("Jots a note") * 1.8f)
    }

    // Files: a folded trail starts with the home icon; only a fold of middle levels keeps the ellipsis

    private val deepPath = "D:\\Shared\\Team\\Documents\\Atelier relaunch"

    @Test fun aFoldedTrailStartsWithTheHomeIconAndOpensTheFullPath() {
        show(scroll = false) { Box(Modifier.width(300.dp)) { FileTrailRow(fileBreadcrumbs(deepPath), {}) } }
        compose.onNodeWithTag("files-crumb-more").assertExists().assertContentDescriptionEquals("Show full path")
        compose.onAllNodesWithText("…").assertCountEquals(0)
        compose.onNodeWithTag("files-crumb-more").performClick()
        compose.onNodeWithTag("files-crumb-home-menu").assertIsDisplayed()
    }

    @Test fun aTrailThatFitsKeepsItsHomeChipAndTheEllipsisForTheMiddleLevels() {
        // The row is forced wider than the phone, so the whole trail fits without folding.
        show(scroll = false) { Box(Modifier.requiredWidth(1200.dp)) { FileTrailRow(fileBreadcrumbs(deepPath), {}) } }
        compose.onNodeWithTag("files-crumb-home").assertExists()
        compose.onAllNodesWithText("…").assertCountEquals(1)
    }
}
