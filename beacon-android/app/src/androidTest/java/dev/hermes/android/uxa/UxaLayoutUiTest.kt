package dev.hermes.android.uxa

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.hermes.android.AppState
import dev.hermes.android.BeaconTheme
import dev.hermes.android.ui.CommandRow
import dev.hermes.android.ui.HistoryView
import dev.hermes.android.ui.HistoryViewChoices
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusTint
import dev.hermes.android.ui.SetupSeaTheme
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.ui.approvalChoiceMeaning
import dev.hermes.android.phone.ApprovalChoice
import dev.hermes.android.ui.nativeContrast
import dev.hermes.android.ui.rememberSettingsStatusPalette
import dev.hermes.android.ui.vault.VaultHarness
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The layout and theme P1s of the 1.2.0 UX review (lane UX-A), on the device: nothing cut at a large font, the add-on said once, the Vault header clear of the status bar. */
class UxaLayoutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val addOnLine = "Calls need Beacon on Hermes."

    /** A picture of the screen as the person sees it, in the app's external files under uxa-shots/ (pulled by the lane after the run). */
    private fun shot(name: String) {
        compose.waitForIdle()
        val app = compose.activity.applicationContext
        val directory = File(app.getExternalFilesDir(null) ?: app.filesDir, "uxa-shots").apply { mkdirs() }
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /**
     * [text] is laid out whole: its last visible line ends at its last character (a word cut by the width or by a line limit ends earlier)
     * and nothing is ellipsized. hasVisualOverflow is not used: the layout this semantics action rebuilds reports it for labels that fit
     * (as LaneR8KWideLayoutTest found), so the line ends are read instead.
     */
    private fun assertWhole(text: String, minLines: Int = 1) {
        val result = layout(compose.onNodeWithText(text, useUnmergedTree = true))
        val last = result.lineCount - 1
        assertEquals("\"$text\" shows every character (lines ${result.lineCount})", text.length, result.getLineEnd(last, visibleEnd = true))
        assertFalse("\"$text\" ends in an ellipsis", result.isLineEllipsized(last))
        assertTrue("\"$text\" takes ${result.lineCount} lines, expected at least $minLines", result.lineCount >= minLines)
    }

    @Composable private fun Scaled(fontScale: Float, width: Dp? = null, content: @Composable () -> Unit) {
        val base = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
            Box(if (width == null) Modifier.fillMaxSize() else Modifier.width(width)) { content() }
        }
    }

    // P1-5: Settings > Phone access caption, and every section footer, wraps at a large font.
    @Test fun aSettingsSectionFooterWrapsAtTheLargestFontInsteadOfEndingInAnEllipsis() {
        val caption = approvalChoiceMeaning(ApprovalChoice.Always)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = false) { Scaled(2f, 360.dp) {
            SettingsSection("Beacon can use", footer = caption) { Text("Calendar") }
        } } } }
        assertWhole(caption, minLines = 2)
        shot("p1-5-footer-font-2.0")
    }

    // P1-6: All / Pinned / Threads read in full at every font size, in the drawer's width.
    @Test fun theHistoryFilterNeverCutsALabelAtAnyFontSize() {
        var scale by mutableFloatStateOf(1f)
        var view by mutableStateOf(HistoryView.All)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = false) { Scaled(scale, 258.dp) { HistoryViewChoices(view, { view = it }) } } } }
        for (size in listOf(1f, 1.3f, 1.5f, 2f)) {
            compose.runOnIdle { scale = size }
            for (selected in HistoryView.entries) {
                compose.runOnIdle { view = selected }
                for (tag in listOf("history-view-all", "history-view-pinned", "history-threads")) compose.onNodeWithTag(tag).assertIsDisplayed()
                for (label in listOf("All", "Pinned", "Threads")) assertWhole(label)
            }
            shot("p1-6-history-filter-font-$size")
        }
    }

    // P1-7 and P1-4: the last wizard page at the largest font, without the add-on and then with it.
    @Test fun theLastWizardPageReadsInFullAndSaysWhatCallsNeedOnce() {
        var state by mutableStateOf(AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false, setupPluginMessage = addOnLine))
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = false) { Scaled(2f) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } } }
        compose.onNodeWithText("Chat is ready").assertIsDisplayed()
        assertEquals("the line is said once", 1, compose.onAllNodesWithText(addOnLine).fetchSemanticsNodes().size)
        compose.onNodeWithTag("missing-plugin-install").assertExists()
        compose.onNodeWithText("Add it").assertExists()
        for (gone in listOf("You're connected", "Install Beacon", "Add Beacon to Hermes")) assertEquals(gone, 0, compose.onAllNodesWithText(gone).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("plugin not installed", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        for (row in listOf("Calendar, alarms, calls and more", "Alerts when something needs you")) assertWhole(row)
        shot("p1-4-p1-7-wizard-done-addon-missing-light-font-2.0")
        // With Beacon on Hermes the page says so, with no install prompt.
        compose.runOnIdle { state = state.copy(setupPluginMessage = "") }
        compose.onNodeWithText("You're connected").assertIsDisplayed()
        for (gone in listOf("Chat is ready", addOnLine, "Add it")) assertEquals(gone, 0, compose.onAllNodesWithText(gone).fetchSemanticsNodes().size)
        for (row in listOf("Calendar, alarms, calls and more", "Alerts when something needs you")) assertWhole(row)
        shot("p1-7-wizard-done-connected-light-font-2.0")
    }

    // UX re-check of F9, NEW-P2-2: "Add it" sat centred under the page's start-aligned title and line; it now starts where they start.
    @Test fun theLastWizardPagesAddItStartsUnderItsLine() {
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false, setupPluginMessage = addOnLine)
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { Scaled(1f) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } } }
        val line = compose.onNodeWithText(addOnLine).getUnclippedBoundsInRoot()
        val button = compose.onNodeWithTag("missing-plugin-install").getUnclippedBoundsInRoot()
        assertEquals("Add it starts where the line starts", line.left.value, button.left.value, 1f)
        shot("ux-recheck-wizard-done-add-it-dark")
    }

    // UX re-check of F9, NEW-P2-3: at the tour's large font a command's description ended in "..."; it now wraps to a second line.
    @Test fun aCommandsDescriptionWrapsInsteadOfBeingCutAtLargeText() {
        val descriptions = listOf("Repeat a prompt on a schedule", "Write a plan without running it")
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = false) { Scaled(1.3f, 360.dp) { Column {
            CommandRow("loop", descriptions[0]) {}
            CommandRow("plan", descriptions[1]) {}
        } } } } }
        for (text in descriptions) assertWhole(text, minLines = 2)
        shot("ux-recheck-commands-font-1.3")
    }

    // P1-4: the three permission pages never repeat the add-on notice.
    @Test fun thePermissionPagesDoNotRepeatTheAddOnNotice() {
        var state by mutableStateOf(AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "permission-microphone", restoringSession = false, setupPluginMessage = addOnLine))
        compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) {
            SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
        } } }
        for (step in listOf("permission-microphone", "permission-notifications", "permission-full-screen")) {
            compose.runOnIdle { state = state.copy(setupStep = step) }
            compose.onNodeWithTag("setup-permission-primary").assertIsDisplayed()
            assertEquals(step, 0, compose.onAllNodesWithText(addOnLine).fetchSemanticsNodes().size)
        }
    }

    // P1-3: the wizard's status chips use the wizard's own dark ink in the light theme too ("Active" was black on the dark card).
    @Test fun theWizardsStatusInkIsTheSameInTheLightThemeAndReadsOnItsCard() {
        val light = mutableMapOf<SettingsStatus, Color>(); val dark = mutableMapOf<SettingsStatus, Color>()
        var card = Color.Unspecified
        compose.setContent { NativeLocalization("en") {
            BeaconTheme(dark = false) { SetupSeaTheme { val palette = rememberSettingsStatusPalette(); SettingsStatus.entries.forEach { light[it] = palette.ink(it) }; card = MaterialTheme.colorScheme.surfaceContainer } }
            BeaconTheme(dark = true) { SetupSeaTheme { val palette = rememberSettingsStatusPalette(); SettingsStatus.entries.forEach { dark[it] = palette.ink(it) } } }
        } }
        compose.waitForIdle()
        for (status in SettingsStatus.entries) {
            val ink = light.getValue(status)
            // The dark wizard's own ink (light on the dark card), not the light theme's dark ink: "Active" was drawn black there.
            assertTrue("$status ink $ink is the dark theme's ${dark[status]}", kotlin.math.abs(ink.luminance() - dark.getValue(status).luminance()) < .05f)
            assertTrue("$status ink on the wizard card", nativeContrast(ink, card) >= 4.5f)
            assertTrue("$status ink on its own pill", nativeContrast(ink, ink.copy(alpha = SettingsStatusTint).compositeOver(card)) >= 4.5f)
        }
    }

    // P1-1: in the app's shell the Vault title sits under the status bar exactly where Skills' does, and the Vault shots' host keeps that inset.
    @Test fun theVaultHeaderSitsBelowTheStatusBarLikeEveryOtherSettingsPage() = VaultHarness(compose).run(chat = true) { vm, _ ->
        val statusBar = statusBarTop()
        compose.runOnIdle { vm.navigate("Skills") }
        val skills = headingTop("Skills")
        compose.runOnIdle { vm.navigate("Vault") }
        val vault = headingTop("Vault")
        assertTrue("the status bar is $statusBar px; the Vault title starts at $vault px", vault >= statusBar)
        assertEquals("the Vault title sits where Skills' does", skills, vault, 2f)
        shot("p1-1-vault-in-the-app-shell")
    }

    @Test fun theVaultShotsHostLeavesTheStatusBarClear() = VaultHarness(compose).run { vm, _ ->
        val statusBar = statusBarTop()
        compose.runOnIdle { vm.navigate("Vault") }
        val vault = headingTop("Vault")
        assertTrue("the status bar is $statusBar px; the Vault title starts at $vault px", vault >= statusBar)
        shot("p1-1-vault-shots-host")
    }

    private fun statusBarTop(): Int = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
    }

    private fun headingTop(title: String): Float {
        val matcher = isHeading() and hasText(title)
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        return compose.onAllNodes(matcher).fetchSemanticsNodes().minOf { it.boundsInRoot.top }
    }
}
