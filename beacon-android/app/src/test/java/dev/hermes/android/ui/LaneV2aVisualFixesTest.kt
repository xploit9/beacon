package dev.hermes.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeComponent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane V2A: the pure decisions behind the full-pass visual fixes. The screens themselves are covered by LaneV2aVisualUiTest and BeaconOpeningUiTest. */
class LaneV2aVisualFixesTest {
    // 1. Beacon's conversation opening

    @Test fun theSkeletonShowsOnlyWhileBeaconOpensWithoutAnError() {
        assertTrue(beaconOpeningSkeletonShown(beacon = true, ready = false, opening = true, error = ""))
        assertFalse("an error keeps its own page", beaconOpeningSkeletonShown(beacon = true, ready = false, opening = true, error = "Offline"))
        assertFalse("a stalled open keeps Retry", beaconOpeningSkeletonShown(beacon = true, ready = false, opening = false, error = ""))
        assertFalse("a ready conversation shows its messages", beaconOpeningSkeletonShown(beacon = true, ready = true, opening = true, error = ""))
        assertFalse("another chat is not Beacon's", beaconOpeningSkeletonShown(beacon = false, ready = false, opening = true, error = ""))
    }

    // 3. Tools list: function names are not a description

    @Test fun aDescriptionThatOnlyListsFunctionNamesIsLeftOutWhenViewToolsHasThem() {
        for (names in listOf("web_search, web_extract", "navigate, click, type, scroll", "terminal, process", "execute_code", "vision_analyze", "read, write, patch, search")) {
            assertEquals("'$names' is only identifiers", "", toolRowSummary(names, hasToolList = true))
        }
    }

    @Test fun aRealDescriptionStaysAndNothingIsHiddenWithoutAToolList() {
        for (description in listOf("Read public pages", "Web search and scraping", "Runs code, safely", "Search the web, extract pages", "web_search; web_extract", "Browser 2")) {
            assertEquals(description, toolRowSummary(description, hasToolList = true))
        }
        assertEquals("With no tool list to show, the names are all there is", "web_search, web_extract", toolRowSummary("web_search, web_extract", hasToolList = false))
        assertEquals("", toolRowSummary("", hasToolList = true))
    }

    // 4. Large text

    @Test fun aSkillDescriptionTakesTwoLinesFromJustPastOnePointOneFiveText() {
        assertEquals(1, skillDescriptionLines(1f))
        assertEquals(1, skillDescriptionLines(1.15f))
        assertEquals(2, skillDescriptionLines(1.16f))
        assertEquals(2, skillDescriptionLines(1.3f))
        assertEquals(2, skillDescriptionLines(2f))
    }

    // 5. Status hero: a still glyph in the state's colour

    @Test fun theHeroDrawsTheStateGlyphForOkAttentionAndError() {
        assertSame(Icons.Rounded.CheckCircle, settingsStaticGlyph(SettingsStatus.Ok))
        assertSame(Icons.Rounded.ErrorOutline, settingsStaticGlyph(SettingsStatus.Attention))
        assertSame(Icons.Rounded.Error, settingsStaticGlyph(SettingsStatus.Error))
        for (status in listOf(SettingsStatus.Ok, SettingsStatus.Attention, SettingsStatus.Error, SettingsStatus.Off)) {
            assertFalse("$status is drawn as its glyph, not as the beacon's resting dot", settingsGlyphMoves(status, signal = false, motion = true))
        }
        assertTrue("Working keeps the turning dust core", settingsGlyphMoves(SettingsStatus.Working, signal = false, motion = true))
    }

    // Extra 1. Session checkpoints are File backups

    @Test fun bothCheckpointRoutesAreTheBackupsPage() {
        assertTrue(serverCheckpointPage("Session checkpoints"))
        assertTrue(serverCheckpointPage("Rollback checkpoints"))
        for (other in listOf("Health", "Logs", "Server updates", "Profiles")) assertFalse(other, serverCheckpointPage(other))
    }

    // Extra 2. Health: one status per row

    private fun part(name: String, status: String, detail: String = "") = NativeComponent(name, status, detail)

    @Test fun aHealthyHermesKeepsOnlyTheRowsAboutTheOutsideWorld() {
        val all = listOf(part("gateway", "ok", "running"), part("dashboard", "ok"), part("storage", "ok"), part("platforms", "ok", "1 / 1 connected"))
        assertEquals(listOf("platforms"), serverComponentsShown(all).map { it.name })
        assertTrue("with no Gateway row the tile says the gateway", serverGatewayMetricShown(serverComponentsShown(all)))
    }

    @Test fun oneInternalPartThatNeedsAttentionBringsBackEveryRow() {
        for (status in listOf("degraded", "error", "down", "unknown", "")) {
            val all = listOf(part("gateway", "ok", "running"), part("dashboard", status), part("storage", "ok"), part("platforms", "ok", "1 / 1 connected"))
            assertEquals(status, listOf("gateway", "dashboard", "storage", "platforms"), serverComponentsShown(all).map { it.name })
            assertFalse("with a Gateway row the tile leaves the gateway out", serverGatewayMetricShown(serverComponentsShown(all)))
        }
    }

    @Test fun aPlatformsRowAloneNeverBringsTheInternalRowsBack() {
        val all = listOf(part("gateway", "ok", "running"), part("platforms", "degraded", "0 / 1 connected"))
        assertEquals(listOf("platforms"), serverComponentsShown(all).map { it.name })
        assertEquals(emptyList<NativeComponent>(), serverComponentsShown(emptyList()))
    }

    @Test fun aHealthyRowDropsTheStateWordItsChipAlreadySays() {
        assertFalse(serverComponentDetailShown("running", SettingsStatus.Ok))
        assertFalse(serverComponentDetailShown(" Running ", SettingsStatus.Ok))
        assertTrue("a stopped gateway keeps saying so beside its chip", serverComponentDetailShown("stopped", SettingsStatus.Error))
        assertTrue(serverComponentDetailShown("starting", SettingsStatus.Attention))
        assertTrue("the platforms count is news, not a repeat", serverComponentDetailShown("1 / 1 connected", SettingsStatus.Ok))
        assertFalse(serverComponentDetailShown("", SettingsStatus.Ok))
    }

    @Test fun theResultNextToTestIsAFailureTheToolsFoundOrNotTestedYet() {
        assertEquals(ToolPill(ToolPillKind.NotTested, SettingsStatus.Off), mcpTestPill(testedTools = null, testFailed = false))
        assertEquals(ToolPill(ToolPillKind.Tested, SettingsStatus.Ok, 3), mcpTestPill(testedTools = 3, testFailed = false))
        assertEquals(ToolPill(ToolPillKind.Tested, SettingsStatus.Ok, 0), mcpTestPill(testedTools = 0, testFailed = false))
        assertEquals("a failed run wins over an older result", ToolPill(ToolPillKind.TestFailed, SettingsStatus.Error), mcpTestPill(testedTools = 3, testFailed = true))
    }

    // 9. Dark theme: the assistant's bubble has an edge

    @Test fun theAssistantBubbleGetsAFullOneDpOutlineInDarkAndAHairlineInLight() {
        val outline = Color(0xFF444B5A)
        val dark = assistantBubbleBorder(dark = true, outline = outline)
        assertEquals(1.dp, dark.width)
        assertEquals(SolidColor(outline), dark.brush)
        val light = assistantBubbleBorder(dark = false, outline = outline)
        assertEquals(.5.dp, light.width)
        assertEquals(SolidColor(outline.copy(alpha = .6f)), light.brush)
    }

    @Test fun theDarkOutlineIsNotFadedLikeTheLightOne() {
        val outline = Color(0xFF444B5A)
        val dark = assistantBubbleBorder(dark = true, outline = outline).brush as SolidColor
        val light = assistantBubbleBorder(dark = false, outline = outline).brush as SolidColor
        assertEquals(1f, dark.value.alpha, 0f)
        assertTrue(light.value.alpha < 1f)
    }
}
