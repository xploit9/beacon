package dev.hermes.android.ux2glyph

import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.BeaconMotionMode
import dev.hermes.android.ui.ChoiceStyle
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.nativeMotionAllowed
import dev.hermes.android.ui.resolveChoiceStyle
import dev.hermes.android.ui.settingsGlyphMoves
import dev.hermes.android.ui.settingsSegmentsFit
import dev.hermes.android.ui.settingsStatusMotion
import dev.hermes.android.ui.settingsThemeLabels
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * UI/UX round 2, lane ux2-glyph: one working glyph (the gold dust core), "System" as the theme's third word, and the missing-plugin
 * card said in the owner's terms. Source structure is read from the files, the catalog from native-locales.json.
 */
class Ux2GlyphTest {
    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog by lazy { JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8)) }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText(Charsets.UTF_8)
    private fun languages() = catalog.keys().asSequence().filter { it != "en" }.toList()
    private fun normalized(value: String) = value.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim()

    // ---- N-6: one working glyph

    /** The retired mode's name, split so this file never contains it. */
    private val retiredMode = "BeaconMotionMode." + "Sweep"

    @Test fun noSourceFileUsesTheSweepMode() {
        val files = listOf("main", "test", "androidTest").flatMap { set -> File(app, "src/$set").walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue("Found only ${files.size} sources, so the scan is broken.", files.size > 300)
        val using = files.filter { retiredMode in it.readText(Charsets.UTF_8) }.map { it.name }
        assertTrue("These files still use the ring-and-hand Sweep glyph: $using", using.isEmpty())
        // The mode is gone from its declaration too, so nothing can draw it again.
        val declaration = Regex("""enum class BeaconMotionMode \{([^}]*)}""").find(source("ui/Components.kt"))
        assertEquals(listOf("Pulse", "Ripple"), declaration?.groupValues?.get(1)?.split(",")?.map { it.trim() })
        assertEquals(setOf("Pulse", "Ripple"), BeaconMotionMode.entries.map { it.name }.toSet())
    }

    @Test fun everyWorkingPlaceDrawsTheDustCoreAtItsOldSize() {
        val places = mapOf(
            "BeaconActivity.kt" to "if (ui.loading) BeaconDustCore(18.dp)",
            "ui/Components.kt" to "BeaconDustCore(if (compact) 64.dp else 108.dp)",
            "ui/NativeCatalog.kt" to "BeaconDustCore(28.dp)",
            "ui/NativeProjects.kt" to "BeaconDustCore(56.dp)",
            "ui/BeaconPresenceCard.kt" to "BeaconDustCore(size, halo)",
            "ui/SettingsKit.kt" to "BeaconDustCore(size, color = ink)",
            "updates/UpdateScreen.kt" to "StepState.Active -> BeaconDustCore(24.dp, color = gold)",
        )
        places.forEach { (file, call) -> assertTrue("$file draws the dust core as `$call`", call in source(file)) }
    }

    @Test fun theStepperKeepsNoDustOfItsOwn() {
        val stepper = source("updates/UpdateScreen.kt")
        assertFalse("The stepper's own copy of the core is gone", "specks" in stepper)
        assertFalse(Regex("""StepGlyph\([^)]*clock""").containsMatchIn(stepper))
    }

    @Test fun workingIsTheCoreAndTheOtherStatesKeepTheirSignals() {
        assertNull("Working is the gold dust core, not a signal mode", settingsStatusMotion(SettingsStatus.Working))
        assertEquals(BeaconMotionMode.Pulse, settingsStatusMotion(SettingsStatus.Attention))
        assertEquals(BeaconMotionMode.Ripple, settingsStatusMotion(SettingsStatus.Ok))
        // Working still moves where it did before.
        assertTrue(settingsGlyphMoves(SettingsStatus.Working, signal = false, motion = true))
    }

    @Test fun reducedMotionHoldsTheCoreStill() {
        // The core reads its clock from the same place as the rest of Beacon's dust, and that clock stops at zero without motion.
        val core = source("ui/DustCore.kt")
        assertTrue("rememberBeaconClock(rememberNativeMotionEnabled())" in core)
        assertTrue("if (!motion) { clock.floatValue = 0f; return@LaunchedEffect }" in source("ui/BeaconSignal.kt"))
        assertFalse("Animations off means no motion", nativeMotionAllowed(systemAnimationsEnabled = false, lifecycleStarted = true))
        assertTrue(nativeMotionAllowed(systemAnimationsEnabled = true, lifecycleStarted = true))
        // A pose at time zero is a still gold dust core (disc, ring, specks, centre), never a ring with a hand drawn as a line.
        assertFalse("drawLine" in core)
        // With animations off, a Settings status shows its still icon instead of any moving glyph.
        SettingsStatus.entries.forEach { assertFalse("$it", settingsGlyphMoves(it, signal = true, motion = false)) }
    }

    // ---- N-7: the theme says "System" and never cuts a word

    private val systemWord = mapOf(
        "it" to "Sistema", "ja" to "システム", "ru" to "Система", "es" to "Sistema", "de" to "System", "zh" to "系统", "zh-Hant" to "系統", "pt" to "Sistema",
        "ko" to "시스템", "fr" to "Système", "cs" to "Systém", "tr" to "Sistem", "pl" to "System", "vi" to "Hệ thống", "ar" to "النظام",
    )

    @Test fun theThemeOptionIsSystem() {
        assertEquals(mapOf("system" to "System", "dark" to "Dark", "light" to "Light"), settingsThemeLabels)
        assertFalse("Follow device" in settingsThemeLabels.values)
    }

    @Test fun systemIsAndroidsOwnWordInAllSixteenLanguages() {
        assertEquals(16, catalog.length())
        assertEquals(languages().toSet(), systemWord.keys)
        systemWord.forEach { (language, word) -> assertEquals(language, word, catalog.getJSONObject(language).optString("system")) }
    }

    @Test fun everySystemWordFitsASegment() {
        // 14 characters is the longest label a segment takes, so no language sends the theme row to a menu for length alone.
        systemWord.forEach { (language, word) -> assertTrue("$language $word", word.length <= 14) }
        assertEquals(ChoiceStyle.Segmented, resolveChoiceStyle(ChoiceStyle.Auto, listOf("System", "Dark", "Light"), 1f))
        assertEquals(ChoiceStyle.Menu, resolveChoiceStyle(ChoiceStyle.Auto, listOf("System", "Dark", "Light"), 1.3f))
    }

    @Test fun theAppearanceRowFallsBackToTheMenuWhenSegmentsDoNotFit() {
        // A 411 dp phone: 32 dp of padding on each side leaves three segments room for an 8-character word; a 280 dp one does not.
        assertTrue(settingsSegmentsFit(widestLabel = 60.dp, count = 3, available = 411.dp - 32.dp - 32.dp))
        assertFalse(settingsSegmentsFit(widestLabel = 60.dp, count = 3, available = 280.dp - 32.dp - 32.dp))
        val row = source("ui/MoreScreen.kt").lines().first { "choiceTags(\"Theme\", themes)" in it }
        assertTrue("The theme row measures its segments", "fitSegments = true" in row)
    }

    // ---- UX-10: the missing-plugin card and its dialog

    private val newKeys = mapOf(
        "the beacon plugin is ready." to "The Beacon plugin is ready.",
        "update the beacon plugin" to "Update the Beacon plugin",
        "add the beacon plugin to hermes" to "Add the Beacon plugin to Hermes",
        "finish setting up the beacon plugin, then retry." to "Finish setting up the Beacon plugin, then retry.",
        "hermes downloads the beacon plugin from github and turns it on." to "Hermes downloads the Beacon plugin from GitHub and turns it on.",
        "install beacon" to "Install Beacon",
    )

    @Test fun theCardAndDialogSayBeaconPluginInTheOwnersTerms() {
        val card = source("ui/Components.kt")
        for (text in listOf("The Beacon plugin is ready.", "Update the Beacon plugin", "Add the Beacon plugin to Hermes", "Hermes downloads the Beacon plugin from GitHub and turns it on.", "Install Beacon"))
            assertTrue("Components.kt says \"$text\"", "uiText(\"$text\")" in card)
        for (old in listOf("Beacon is ready on your Hermes.", "Update Beacon on your Hermes", "Add Beacon to your Hermes", "Hermes will download Beacon"))
            assertFalse("Components.kt no longer says \"$old\"", old in card)
        val settings = source("ui/BeaconSettings.kt")
        assertTrue("uiText(\"Finish setting up the Beacon plugin, then retry.\")" in settings)
        assertTrue("uiText(\"Update the Beacon plugin\")" in settings)
        for (old in listOf("Finish setting up Beacon on your Hermes dashboard", "Update Beacon on your Hermes")) assertFalse(old, old in settings)
    }

    @Test fun theDialogNamesNoAddress() {
        val dialog = Regex("""text = \{ Text\(uiText\("([^"]*)"\)\) \}""").findAll(source("ui/Components.kt")).map { it.groupValues[1] }.filter { "Beacon" in it }.toList()
        assertEquals(listOf("Hermes downloads the Beacon plugin from GitHub and turns it on."), dialog)
        dialog.forEach { text -> assertFalse(text, "github.com" in text || "http" in text || "xploit9" in text) }
        // The button keeps its short word.
        assertTrue("confirmButton" in source("ui/Components.kt") && "Text(uiText(\"Install Beacon\"))" in source("ui/Components.kt"))
    }

    @Test fun theCopyIsShort() {
        newKeys.values.forEach { text -> assertTrue("\"$text\" is one short line", text.length <= 70 && '\n' !in text) }
    }

    @Test fun theCopyIsTranslatedInEveryLanguage() {
        languages().forEach { language ->
            val strings = catalog.getJSONObject(language)
            newKeys.forEach { (key, english) ->
                assertTrue("$language has no \"$key\"", strings.has(key))
                val value = strings.getString(key)
                assertTrue("$language \"$key\" is empty", value.isNotBlank())
                assertTrue("$language keeps \"$english\" in English", normalized(value) != normalized(english))
            }
        }
        val english = catalog.getJSONObject("en")
        listOf("the beacon plugin is ready.", "finish setting up the beacon plugin, then retry.").forEach { key -> assertEquals(newKeys.getValue(key), english.getString(key)) }
    }

    @Test fun arabicReadsNaturallyAndKeepsTheLatinNames() {
        val ar = catalog.getJSONObject("ar")
        assertEquals("ملحق Beacon جاهز.", ar.getString("the beacon plugin is ready."))
        assertEquals("أكمل إعداد ملحق Beacon ثم أعد المحاولة.", ar.getString("finish setting up the beacon plugin, then retry."))
        assertEquals("النظام", ar.getString("system"))
        languages().forEach { language ->
            val value = catalog.getJSONObject(language).getString("the beacon plugin is ready.")
            assertTrue("$language keeps the name Beacon", "Beacon" in value)
        }
    }

    @Test fun theRetiredSentencesAreShownNowhere() {
        // The old sentences may stay in the catalog, but no screen passes them to uiText any more.
        val shown = listOf("Beacon is ready on your Hermes.", "Finish setting up Beacon on your Hermes dashboard, then retry.")
        val code = File(app, "src/main/java").walkTopDown().filter { it.extension == "kt" }.map { it.readText(Charsets.UTF_8) }.toList()
        shown.forEach { text -> assertTrue("\"$text\" is still shown", code.none { "uiText(\"$text\")" in it }) }
    }
}
