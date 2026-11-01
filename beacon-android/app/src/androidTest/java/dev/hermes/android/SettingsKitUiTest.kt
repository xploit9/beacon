package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsKitUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() } }
    }
    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test fun switchRowTogglesFromTheWholeRowAndReportsItsState() {
        var checked by mutableStateOf(false)
        show {
            SettingsSwitchRow("Keep connected", checked, { checked = it }, icon = Icons.Rounded.SettingsInputAntenna, summary = "Calls while Beacon is closed", testTag = "row")
            SettingsSwitchRow("Notifications", true, {}, busy = true, testTag = "busy-row")
        }
        compose.onNodeWithTag("row").assert(role(Role.Switch)).assertIsOff().assertTextContains("Keep connected").performClick()
        compose.onNodeWithTag("row").assertIsOn()
        assertTrue(checked)
        compose.onNodeWithTag("row").performClick().assertIsOff()
        assertFalse(checked)
        compose.onNodeWithTag("busy-row").assertIsOn().assertIsNotEnabled()
    }

    @Test fun segmentedChoiceSelectsWithOneTapAndKeepsTheSelectTags() {
        var delivery by mutableStateOf("notify")
        show {
            SettingsChoiceRow("Delivery", delivery, listOf("notify", "call"), { delivery = it }, icon = Icons.Rounded.Notifications,
                optionLabels = mapOf("notify" to "Notify", "call" to "Call"), optionIcons = mapOf("notify" to Icons.Rounded.Notifications, "call" to Icons.Rounded.Call))
        }
        compose.onNodeWithTag("choice-option:Delivery:notify").assertIsSelected()
        compose.onNodeWithTag("choice-option:Delivery:call").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("call", delivery) }
        compose.onNodeWithTag("choice-option:Delivery:call").assertIsSelected()
        compose.onNodeWithTag("choice-option:Delivery:notify").assertIsNotSelected()
    }

    @Test fun choiceStaysAMenuByDefaultAndShowsSegmentsWhenAsked() {
        var theme by mutableStateOf("system")
        show {
            Choice("Theme", theme, listOf("system", "dark", "light"), { theme = it }, optionLabels = mapOf("system" to "Follow device", "dark" to "Dark", "light" to "Light"))
            Choice("Send shortcut", "enter", listOf("enter", "ctrl_enter"), {}, optionLabels = mapOf("enter" to "Enter", "ctrl_enter" to "Ctrl + Enter"), style = ChoiceStyle.Segmented)
        }
        // Menu: the options exist only once the select is opened.
        compose.onAllNodesWithTag("choice-option:Theme:dark").assertCountEquals(0)
        compose.onNode(hasText("Theme") and hasClickAction()).performClick()
        compose.onNodeWithTag("choice-option:Theme:dark").performClick()
        compose.runOnIdle { assertEquals("dark", theme) }
        compose.onNodeWithTag("choice-option:Send shortcut:enter").assertIsSelected()
        compose.onNodeWithTag("choice-option:Send shortcut:ctrl_enter").assertIsNotSelected()
    }

    @Test fun menuChoiceRowOpensFromItsAnchorAndKeepsOptionTags() {
        var days by mutableStateOf("0")
        show {
            SettingsChoiceRow("Keep call and task history", days, listOf("0", "365", "90", "30"), { days = it }, icon = Icons.Rounded.History,
                optionLabels = mapOf("0" to "Forever", "365" to "1 year", "90" to "90 days", "30" to "30 days"), style = ChoiceStyle.Menu, anchorTestTag = "history-retention-choice")
        }
        compose.onNodeWithTag("history-retention-choice").assert(role(Role.DropdownList)).assertTextContains("Forever").performClick()
        compose.onNodeWithTag("choice-option:Keep call and task history:30").performClick()
        compose.runOnIdle { assertEquals("30", days) }
        compose.onNodeWithTag("history-retention-choice").assertTextContains("30 days")
    }

    @Test fun optionCardsAreRadioButtonsAndKeepTheSelectedCardVisible() {
        var voice by mutableStateOf("v5")
        var expanded by mutableStateOf(false)
        val options = (1..8).map { SettingsOption("v$it", "Voice $it", "Style $it", testTag = "voice-option-voice-v$it") }
        show { SettingsOptionCards(options, voice, { voice = it }, columns = 2, collapsedCount = 2, moreLabel = "More voices (5)", moreTestTag = "voice-more-voices", expanded = expanded, onExpand = { expanded = true }) }
        compose.onNodeWithTag("voice-option-voice-v5").assert(role(Role.RadioButton)).assertIsSelected()
        compose.onAllNodesWithTag("voice-option-voice-v4").assertCountEquals(0)
        compose.onNodeWithTag("voice-option-voice-v2").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("v2", voice) }
        compose.onNodeWithTag("voice-option-voice-v2").assertIsSelected()
        compose.onNodeWithTag("voice-more-voices").performClick()
        compose.onNodeWithTag("voice-option-voice-v8").assertExists()
        compose.onAllNodesWithTag("voice-more-voices").assertCountEquals(0)
    }

    @Test fun destructiveRowOnlyActsAfterConfirmation() {
        var asking by mutableStateOf(false)
        var removed by mutableStateOf(false)
        show {
            SettingsDestructiveRow("Remove key", { asking = true }, testTag = "remove")
            if (asking) SettingsConfirmDialog("Remove the key?", "Calls stop until you add a key.", "Remove key", onConfirm = { asking = false; removed = true }, onDismiss = { asking = false }, confirmTestTag = "remove-confirm")
        }
        compose.onNodeWithTag("remove").assert(role(Role.Button)).performClick()
        compose.onNodeWithTag("remove-confirm").assertIsDisplayed()
        assertFalse(removed)
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag("remove-confirm").assertCountEquals(0)
        assertFalse(removed)
        compose.onNodeWithTag("remove").performClick()
        compose.onNodeWithTag("remove-confirm").performClick()
        compose.runOnIdle { assertTrue(removed) }
    }

    @Test fun heroAnnouncesItsVerdictAsAHeadingAndPillsCarryTheirWord() {
        var connected by mutableStateOf(true)
        show {
            SettingsStatusHero(if (connected) SettingsStatus.Ok else SettingsStatus.Error, if (connected) "Connected" else "Can't reach Hermes", line = "example.com",
                meta = { SettingsBadge("maya"); SettingsStatusPill(SettingsStatus.Ok, "Linked", testTag = "pill") },
                primary = if (connected) null else SettingsAction("Reconnect", { connected = true }, testTag = "hero-reconnect"), testTag = "hero")
        }
        compose.onNodeWithTag("hero-title").assertTextEquals("Connected")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("example.com").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithTag("pill").assertTextEquals("Linked")
        compose.onAllNodesWithTag("hero-reconnect").assertCountEquals(0)
        compose.runOnIdle { connected = false }
        compose.onNodeWithTag("hero-title").assertTextEquals("Can't reach Hermes")
        compose.onNodeWithTag("hero-reconnect").assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("hero-title").assertTextEquals("Connected")
    }

    @Test fun everyInteractiveKitPartIsAtLeast48dp() {
        show {
            SettingsSection("Delivery", trailing = { SettingsIconButton(Icons.Rounded.Refresh, "Refresh devices", {}) }, footer = "After a force stop, open Beacon once") {
                SettingsSwitchRow("Notifications", true, {}, icon = Icons.Rounded.Notifications)
                SettingsDivider()
                SettingsNavRow("Schedules", {}, icon = Icons.Rounded.Schedule, value = "3 jobs", status = SettingsStatus.Ok, statusLabel = "On")
                SettingsNavRow("Notification types", {}, icon = Icons.Rounded.Tune, external = true)
                SettingsActionRow("Send a test", {}, icon = Icons.AutoMirrored.Rounded.Send)
                SettingsDestructiveRow("Sign out", {})
                SettingsChoiceRow("Check every", "15", listOf("5", "15", "30"), {}, icon = Icons.Rounded.Timer, optionLabels = mapOf("5" to "5 min", "15" to "15 min", "30" to "30 min"))
                SettingsChoiceRow("Profile", "work", listOf("work", "home"), {}, icon = Icons.Rounded.SmartToy, style = ChoiceStyle.Menu)
                SettingsTextFieldRow("Name", "Beacon 2", {}, icon = Icons.Rounded.Face, saveLabel = "Save", onSave = {}, dirty = true)
                SettingsSecretField("Gemini API key", "", {}, savedMask = "••••AB12", onPaste = {}, visible = false, onToggleVisible = {}, pasteLabel = "Paste key", showLabel = "Show key", hideLabel = "Hide key", savedLabel = "Saved")
                SettingsEmpty("No rules yet", Icons.Rounded.Flag, action = SettingsAction("Add a rule", {}))
                SettingsErrorRow("Couldn't refresh", {}, "Retry")
            }
            SettingsStatusHero(SettingsStatus.Attention, "Update available", line = "1.1.22", primary = SettingsAction("Update", {}), secondary = SettingsAction("Later", {}))
            SettingsOptionCards(listOf(SettingsOption("balanced", "Balanced"), SettingsOption("warm", "Warm")), "balanced", {}, columns = 2)
            SettingsSwatchPicker(listOf(SettingsSwatch("default", "Beacon", Color(0xFFE7B579), Color(0xFF0C1014)), SettingsSwatch("mono", "Mono", null, null)), "default", {})
        }
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("Only ${nodes.size} interactive nodes were found", nodes.size >= 18)
        nodes.forEach { node ->
            val width = with(compose.density) { node.size.width.toDp() }
            val height = with(compose.density) { node.size.height.toDp() }
            assertTrue("${node.config} is $width x $height", width >= 47.5.dp && height >= 47.5.dp)
        }
    }

    @Test fun swatchShowsANeutralTileUntilItsColoursAreRead() {
        var skin by mutableStateOf("default")
        show { SettingsSwatchPicker(listOf(SettingsSwatch("default", "Beacon", null, null), SettingsSwatch("mono", "Mono", Color.LightGray, Color.Black)), skin, { skin = it }) }
        compose.onAllNodesWithTag("settings-swatch-placeholder", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("settings-swatch-colors", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("skin-option:default").assert(role(Role.RadioButton)).assertIsSelected().assertTextContains("Beacon")
        compose.onNodeWithTag("skin-option:mono").assertIsNotSelected().performClick()
        compose.runOnIdle { assertEquals("mono", skin) }
        compose.onNodeWithTag("skin-option:mono").assertIsSelected()
    }

    @Test fun skeletonIsSilentAndRefreshBarSitsOverContent() {
        show {
            SettingsSection("Devices") { SettingsSkeletonRows(2) }
            // A lazy list needs a bounded height inside the scrolling test column.
            SettingsList(Modifier.height(320.dp), refreshing = true, testTag = "list") { item { SettingsSection("Server") { SettingsNavRow("Server address", {}, value = "example.com") } } }
        }
        compose.onNodeWithTag("settings-skeleton").assertExists().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Text))
        // TalkBack reads the merged tree: nothing under the placeholder may speak.
        compose.onAllNodes(hasAnyAncestor(hasTestTag("settings-skeleton"))).assertCountEquals(0)
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithText("example.com").assertExists()
    }
}
