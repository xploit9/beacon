package dev.hermes.android.uiux.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.BotModePreview
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeController
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.preferences
import dev.hermes.android.ui.NativeBotModeSidebar
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SidebarNavigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The Bot Mode drawer on its own: New bot and New space at the top, no tile grid, one switch to leave, the bots with a real status,
 * and suggested roles. The roster is set directly, so nothing here talks to Hermes. Leaving by the switch is in NativeExperienceUiTest,
 * where a Hermes fixture answers the toggle.
 */
class BotModeDrawerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tiles = listOf("skills", "memory", "cron", "files", "projects", "kanban", "settings", "more")
    private val twoBots = listOf(BotModeBot("default", "Default", "Your main assistant"), BotModeBot("coder", "Coder", "Reviews code"))
    private val oneSpace = listOf(BotModeSpace("room-1", "Review room", listOf("default", "coder"), updatedAt = 1_700_000_000L, last = BotModePreview("coder", "Looks good to me")))

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    @Suppress("UNCHECKED_CAST")
    private fun roster(vm: BeaconViewModel) = NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm.botMode) as MutableStateFlow<NativeBotModeState>

    private fun withDrawer(
        state: NativeBotModeState = NativeBotModeState(bots = twoBots, spaces = oneSpace),
        language: String = "en", rtl: Boolean = false, fontScale: Float = 1f, block: (BeaconViewModel) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val savedPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit {
                    it[stringPreferencesKey("origin")] = ""
                    it[booleanPreferencesKey("setup_complete")] = true
                    it[booleanPreferencesKey("setup_active")] = false
                }
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                ui(vm).value = AppState(origin = "", tab = "Chat", connected = false, initialChatReady = true, restoringSession = false, setupComplete = true, botModeEnabled = true)
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    NativeLocalization(language) { BeaconTheme {
                        Box(Modifier.requiredSize(340.dp, 720.dp)) { NativeBotModeSidebar(vm, Modifier.fillMaxSize(), beforeRoster = { SidebarNavigation(vm) }) }
                    } }
                }
            }
            // The sidebar reads the roster once when it opens (and, with no Hermes behind it, clears it); the roster is set after that.
            compose.waitForIdle()
            compose.runOnIdle { roster(vm).value = state }
            compose.waitForIdle()
            block(vm)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { savedPreferences } }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()

    private fun assertSingleUnclippedLine(text: String) {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("Expected a label $text", count > 0)
        for (index in 0 until count) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Expected one layout for $text #$index", 1, layouts.size)
            assertEquals("$text #$index wraps", 1, layouts.single().lineCount)
            assertTrue("$text #$index is ellipsized", !layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun newBotAndNewSpaceLeadTheDrawerAndNoTileGridPadsIt() = withDrawer { _ ->
        val newBot = compose.onNodeWithTag("bot-mode-sidebar-new-bot").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertTextContains("New bot").getUnclippedBoundsInRoot()
        val newSpace = compose.onNodeWithTag("bot-mode-sidebar-new-chat").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertTextContains("New space").getUnclippedBoundsInRoot()
        // Side by side at normal text, New bot first, one size; both above the first bot.
        assertTrue("New bot is left of New space", newBot.right <= newSpace.left)
        assertEquals(newBot.top.value, newSpace.top.value, 0.5f)
        assertEquals(newBot.width.value, newSpace.width.value, 1f)
        assertTrue("The buttons sit above the bots", newSpace.bottom <= bounds("bot-row-default").top)
        tiles.forEach { compose.onAllNodesWithTag("navigation-$it").assertCountEquals(0) }
        // The profile pill and the chat buttons belong to the normal drawer.
        listOf("navigation-profile", "navigation-new-chat", "navigation-new-thread").forEach { compose.onAllNodesWithTag(it).assertCountEquals(0) }
        // One compact, clearly-on control leaves Bot Mode.
        compose.onNodeWithTag("navigation-bots").assertIsDisplayed().assertIsSelected().assertIsOn().assertHeightIsAtLeast(48.dp).assertTextContains("Bot Mode")
        assertTrue("The switch sits under the buttons", bounds("navigation-bots").top >= newSpace.bottom)
    }

    @Test fun theRosterHeadersNoLongerCarryNewBotOrNewSpace() = withDrawer { _ ->
        compose.onAllNodesWithTag("bot-mode-sidebar-new-bot").assertCountEquals(1)
        compose.onAllNodesWithTag("bot-mode-sidebar-new-chat").assertCountEquals(1)
        compose.onAllNodesWithText("New bot").assertCountEquals(1)
        compose.onAllNodesWithText("New space").assertCountEquals(1)
        compose.onNodeWithText("Bots").assertIsDisplayed()
        compose.onNodeWithText("Shared spaces").assertIsDisplayed()
    }

    @Test fun newBotOpensTheNewBotFormAndNewSpaceOpensTheSpaceSheet() = withDrawer { _ ->
        compose.onNodeWithTag("bot-mode-sidebar-new-bot").performClick()
        compose.onNodeWithTag("new-profile-name").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-create").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag("new-profile-name").assertCountEquals(0)
        compose.onNodeWithTag("bot-mode-sidebar-new-chat").performClick()
        compose.onNodeWithTag("new-chat-sheet").assertIsDisplayed()
        compose.onNodeWithTag("new-chat-bot:coder").assertIsDisplayed()
    }

    @Test fun botRowsShowTheirRoleAndARealStatus() = withDrawer(
        NativeBotModeState(bots = twoBots, spaces = oneSpace, roster = listOf(BotModeMember("m-coder", "coder", "coder")), typing = listOf("m-coder")),
    ) { vm ->
        // The coder is typing in the open space; the default bot is in one shared space.
        compose.onNodeWithTag("bot-row-coder").assertTextContains("Reviews code").assertTextContains("Typing…")
        compose.onNodeWithTag("bot-row-default").assertTextContains("Your main assistant").assertTextContains("Spaces: 1")
        compose.runOnIdle { roster(vm).value = roster(vm).value.copy(typing = emptyList()) }
        compose.onNodeWithTag("bot-row-coder").assertTextContains("Spaces: 1")
        // The recent-spaces list is cut: a count would be too low, so none is shown.
        compose.runOnIdle { roster(vm).value = roster(vm).value.copy(moreSpaces = true) }
        compose.onNodeWithTag("bot-row-default").assertTextContains("Your main assistant")
        compose.onAllNodesWithText("Spaces: 1", substring = true).assertCountEquals(0)
    }

    @Test fun aSpaceRowShowsItsNameMembersAndNewestLine() = withDrawer { _ ->
        compose.onNodeWithTag("bot-space-room-1").assertTextContains("Review room").assertTextContains("coder: Looks good to me")
        compose.onNodeWithTag("bot-space-room-1").assertHeightIsAtLeast(48.dp)
        // The row merges its children for TalkBack; the avatars are inside it.
        compose.onAllNodesWithTag("group-avatars", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun aSuggestedRoleOpensNewBotFilledIn() = withDrawer { _ ->
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        compose.onNodeWithText("Suggested roles").assertIsDisplayed()
        listOf("researcher", "planner", "writer", "inbox-helper").forEach { compose.onNodeWithTag("bot-mode-suggested-role:$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        compose.onNodeWithTag("bot-mode-suggested-role:researcher").assertTextContains("Researcher").performClick()
        // The name is the profile id; the role is the line the bot's row shows. Create is open: the name is valid and free.
        compose.onNodeWithTag("new-profile-name").assertTextContains("researcher")
        compose.onNodeWithTag("new-profile-role").assertTextContains("Finds sources and sums them up.")
        compose.onNodeWithTag("new-profile-create").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag("new-profile-name").assertCountEquals(0)
        compose.onNodeWithTag("bot-mode-suggested-role:inbox-helper").performClick()
        compose.onNodeWithTag("new-profile-name").assertTextContains("inbox-helper")
        compose.onNodeWithTag("new-profile-create").assertIsEnabled()
    }

    @Test fun suggestedRolesStopOnceTheTeamIsBig() {
        val four = listOf("default", "coder", "ops", "docs").map { BotModeBot(it, it.replaceFirstChar(Char::uppercase), "") }
        withDrawer(NativeBotModeState(bots = four)) { _ -> compose.onAllNodesWithTag("bot-mode-suggested-roles").assertCountEquals(0) }
    }

    @Test fun aSuggestedRoleThatIsAlreadyABotIsNotOffered() {
        val withResearcher = twoBots + BotModeBot("researcher", "Researcher", "Finds sources")
        withDrawer(NativeBotModeState(bots = withResearcher)) { _ ->
            compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
            compose.onAllNodesWithTag("bot-mode-suggested-role:researcher").assertCountEquals(0)
            compose.onNodeWithTag("bot-mode-suggested-role:planner").assertIsDisplayed()
        }
    }

    @Test fun theControlsAreOffWhileBotModeSwitches() = withDrawer { vm ->
        compose.runOnIdle { ui(vm).value = ui(vm).value.copy(botModeSwitching = true) }
        compose.onNodeWithTag("navigation-bots").assertIsNotEnabled()
        compose.onNodeWithTag("bot-mode-sidebar-new-bot").assertIsNotEnabled()
        compose.onNodeWithTag("bot-mode-sidebar-new-chat").assertIsNotEnabled()
    }

    @Test fun atLargeTextTheButtonsStackFullWidthWithoutOverlapAndLabelsStayOnOneLine() = withDrawer(fontScale = 1.3f) { _ ->
        val newBot = compose.onNodeWithTag("bot-mode-sidebar-new-bot").assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val newSpace = compose.onNodeWithTag("bot-mode-sidebar-new-chat").assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertTrue("New space is under New bot ($newBot, $newSpace)", newBot.bottom <= newSpace.top)
        assertEquals(newBot.left.value, newSpace.left.value, 0.5f)
        assertEquals(newBot.width.value, newSpace.width.value, 0.5f)
        val switch = compose.onNodeWithTag("navigation-bots").assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertTrue("The switch is under New space", newSpace.bottom <= switch.top)
        listOf("New bot", "New space", "Bot Mode").forEach(::assertSingleUnclippedLine)
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        listOf("Researcher", "Planner", "Writer", "Inbox helper").forEach(::assertSingleUnclippedLine)
        tiles.forEach { compose.onAllNodesWithTag("navigation-$it").assertCountEquals(0) }
    }

    @Test fun arabicRunsRightToLeftWithNewBotFirstAtTheStart() = withDrawer(language = "ar", rtl = true) { _ ->
        compose.waitUntil(5_000) { compose.onAllNodesWithText("روبوت جديد").fetchSemanticsNodes().isNotEmpty() }
        val newBot = compose.onNodeWithTag("bot-mode-sidebar-new-bot").assertTextContains("روبوت جديد").getUnclippedBoundsInRoot()
        val newSpace = compose.onNodeWithTag("bot-mode-sidebar-new-chat").assertTextContains("مساحة جديدة").getUnclippedBoundsInRoot()
        // The start is the right: New bot is the right-hand button, and the buttons keep one size and do not overlap.
        assertTrue("New bot is right of New space ($newBot, $newSpace)", newBot.left >= newSpace.right)
        assertEquals(newBot.width.value, newSpace.width.value, 1f)
        compose.onNodeWithTag("navigation-bots").assertTextContains("وضع الروبوتات").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("bot-mode-sidebar").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        // The first suggested role starts at the right edge and the next follows it to the left.
        val first = bounds("bot-mode-suggested-role:researcher")
        val second = bounds("bot-mode-suggested-role:planner")
        if (first.top == second.top) assertTrue("Chips flow from the right ($first, $second)", first.right > second.right)
    }
}
