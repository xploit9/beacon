package dev.hermes.android.ui

import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsKitTest {
    private val palettes by lazy {
        val file = requireNotNull(listOf(File("src/main/assets/native-skins.json"), File("app/src/main/assets/native-skins.json"), File("beacon-android/app/src/main/assets/native-skins.json")).firstOrNull { it.isFile }) {
            "Run this palette check from the Beacon app, Android project or repository root."
        }
        JSONObject(file.readText(Charsets.UTF_8))
    }

    @Test fun everyStatusInkReadsOnEverySurfaceGlassAndItsOwnPillInEverySkin() {
        assertEquals(21, NativeSkins.size)
        NativeSkins.forEach { skin -> listOf(false, true).forEach { dark ->
            val scheme = nativeSkinColorScheme(palettes.getJSONObject(skin).getJSONObject(if (dark) "dark" else "light"), dark)
            val palette = settingsStatusPalette(scheme)
            SettingsStatus.entries.forEach { status ->
                val ink = palette.ink(status)
                val backdrops = settingsStatusBackdrops(scheme, ink)
                assertTrue("$skin dark=$dark has too few backdrops", backdrops.size >= 100)
                backdrops.forEach { background ->
                    val ratio = nativeContrast(ink, background)
                    assertTrue("$skin dark=$dark $status ink=$ink background=$background ratio=$ratio", ratio >= 4.5f)
                }
            }
        } }
    }

    @Test fun okAttentionAndErrorStayDistinctInks() {
        NativeSkins.forEach { skin -> listOf(false, true).forEach { dark ->
            val palette = settingsStatusPalette(nativeSkinColorScheme(palettes.getJSONObject(skin).getJSONObject(if (dark) "dark" else "light"), dark))
            assertNotEquals("$skin dark=$dark ok vs attention", palette.ok, palette.attention)
            assertNotEquals("$skin dark=$dark ok vs error", palette.ok, palette.error)
            assertNotEquals("$skin dark=$dark attention vs error", palette.attention, palette.error)
        } }
    }

    @Test fun okStaysGreenAndAttentionStaysAmberAfterContrastAdjustment() {
        // The solver only moves an ink toward white or black, which keeps the order of its channels, so the hue family survives.
        NativeSkins.forEach { skin -> listOf(false, true).forEach { dark ->
            val palette = settingsStatusPalette(nativeSkinColorScheme(palettes.getJSONObject(skin).getJSONObject(if (dark) "dark" else "light"), dark))
            with(palette.ok) { assertTrue("$skin dark=$dark ok=$this is not green", green >= red && green >= blue) }
            with(palette.attention) { assertTrue("$skin dark=$dark attention=$this is not amber", red >= green && green >= blue) }
        } }
    }

    @Test fun paletteMapsEachStateToItsOwnInk() {
        val palette = SettingsStatusPalette(ok = androidx.compose.ui.graphics.Color.Green, attention = androidx.compose.ui.graphics.Color.Yellow,
            error = androidx.compose.ui.graphics.Color.Red, off = androidx.compose.ui.graphics.Color.Gray, working = androidx.compose.ui.graphics.Color.Blue)
        assertEquals(palette.ok, palette.ink(SettingsStatus.Ok))
        assertEquals(palette.attention, palette.ink(SettingsStatus.Attention))
        assertEquals(palette.error, palette.ink(SettingsStatus.Error))
        assertEquals(palette.off, palette.ink(SettingsStatus.Off))
        assertEquals(palette.working, palette.ink(SettingsStatus.Working))
    }

    @Test fun everyStillStateHasAGlyphAndOnlyWorkingSweeps() {
        SettingsStatus.entries.filter { it != SettingsStatus.Working }.forEach { assertNotNull(it.name, settingsStatusIcon(it)) }
        assertNull(settingsStatusIcon(SettingsStatus.Working))
        assertEquals(BeaconMotionMode.Sweep, settingsStatusMotion(SettingsStatus.Working))
        assertEquals(BeaconMotionMode.Pulse, settingsStatusMotion(SettingsStatus.Attention))
        assertEquals(BeaconMotionMode.Ripple, settingsStatusMotion(SettingsStatus.Ok))
    }

    @Test fun autoChoiceUsesSegmentsOnlyForTwoToFourShortOptionsAtNormalSize() {
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Notify", "Call"), 1f))
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Follow device", "Dark", "Light"), 1.3f))
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Forever", "1 year", "90 days", "30 days"), 1f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Only"), 1f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, listOf("a", "b", "c", "d", "e"), 1f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Notify", "A much longer label"), 1f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, listOf("Notify", "Call"), 1.5f))
        assertEquals("14 characters still fit", ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, listOf("x".repeat(ChoiceSegmentMaxChars), "y"), 1f))
    }

    @Test fun anExplicitChoiceStyleIsKept() {
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Menu, listOf("Notify", "Call"), 1f))
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Segmented, listOf("a", "b", "c", "d", "e", "f"), 2f))
    }

    @Test fun aCollapsedOptionListKeepsTheSelectedOneInItsOrder() {
        val options = (1..10).map { SettingsOption("v$it", "Voice $it") }
        assertEquals(listOf("v1", "v2", "v3"), settingsOptionsShown(options, "v2", expanded = false, limit = 3).map { it.id })
        assertEquals(listOf("v1", "v2", "v3", "v8"), settingsOptionsShown(options, "v8", expanded = false, limit = 3).map { it.id })
        assertEquals(options, settingsOptionsShown(options, "v8", expanded = true, limit = 3))
        assertEquals(options, settingsOptionsShown(options, "v1", expanded = false, limit = Int.MAX_VALUE))
    }

    @Test fun swatchGridKeepsCellsTouchableAndWidensOnLargePanes() {
        assertEquals(4, settingsSwatchColumns(356.dp))
        assertEquals(4, settingsSwatchColumns(312.dp))
        assertEquals(3, settingsSwatchColumns(300.dp))
        assertEquals(6, settingsSwatchColumns(600.dp))
        listOf(240, 300, 356, 420, 599).forEach { width ->
            val columns = settingsSwatchColumns(width.dp)
            assertTrue("$width dp gives cells under 72dp", (width.dp - 8.dp * (columns - 1)) / columns >= 72.dp || columns == 3)
        }
    }

    @Test fun kitTokensMatchTheSpec() {
        assertEquals(SettingsPagePadding, SettingsKit.PagePadding)
        assertEquals(16.dp, SettingsKit.PagePadding)
        assertEquals(72.dp, SettingsKit.DividerStart)
        assertTrue(SettingsKit.RowMinHeight >= SettingsKit.TouchMin)
        assertEquals(48.dp, SettingsKit.TouchMin)
    }
}
