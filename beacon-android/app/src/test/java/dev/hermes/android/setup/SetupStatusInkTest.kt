package dev.hermes.android.setup

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusTint
import dev.hermes.android.ui.SetupSea
import dev.hermes.android.ui.nativeContrast
import dev.hermes.android.ui.nativeRoleBackgrounds
import dev.hermes.android.ui.setupColorScheme
import dev.hermes.android.ui.settingsStatusPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-3 of the 1.2.0 UX review: the wizard is dark in both system themes, but its status chips ("Active", "Key saved", the key check)
 * took their ink from a palette that still saw the light theme's surfaces, so no ink read on all of them and the chip came out black on
 * the dark card. Every chip and tag on the wizard pages is a status pill or a primary-container badge; these pin both.
 */
class SetupStatusInkTest {
    private val light = lightColorScheme(primary = Color(0xFF80511D), error = Color(0xFFA93128))
    private val dark = darkColorScheme(primary = Color(0xFFE7B579), error = Color(0xFFFFB4AB))
    private val minimum = 4.5f

    /** What the wizard really draws on: the sea, and the cards and panes that sit on it. */
    private fun cards(scheme: ColorScheme) = listOf(SetupSea, scheme.background, scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)

    @Test fun everyStatusInkOnTheWizardReadsOnItsCardsAndItsOwnPillInBothSystemThemes() {
        for ((theme, base) in listOf("light" to light, "dark" to dark)) {
            val wizard = setupColorScheme(base)
            val palette = settingsStatusPalette(wizard)
            for (status in SettingsStatus.values()) {
                val ink = palette.ink(status)
                for (card in cards(wizard)) {
                    assertTrue("$theme $status ink $ink on card $card", nativeContrast(ink, card) >= minimum)
                    val pill = ink.copy(alpha = SettingsStatusTint).compositeOver(card)
                    assertTrue("$theme $status ink $ink on its pill over $card", nativeContrast(ink, pill) >= minimum)
                }
            }
        }
    }

    @Test fun theWizardDrawsTheSameStatusInkWhicheverSystemThemeThePhoneIsIn() {
        val inLight = settingsStatusPalette(setupColorScheme(light))
        val inDark = settingsStatusPalette(setupColorScheme(dark))
        for (status in SettingsStatus.values()) assertEquals("$status", inDark.ink(status), inLight.ink(status))
    }

    @Test fun noSurfaceTheWizardCanDrawIsALightOneInEitherSystemTheme() {
        for ((theme, base) in listOf("light" to light, "dark" to dark)) {
            val wizard = setupColorScheme(base)
            for (backdrop in nativeRoleBackgrounds(wizard)) assertTrue("$theme backdrop $backdrop is dark", backdrop.compositeOver(SetupSea).let { nativeContrast(it, SetupSea) < 3f })
        }
    }
}
