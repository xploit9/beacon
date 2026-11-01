package dev.hermes.android.uiux.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.json
import dev.hermes.android.ui.DrawerMode
import dev.hermes.android.ui.DrawerModeSwitch
import dev.hermes.android.ui.ProfileSwitcherMenu
import dev.hermes.android.ui.UpdateReadyChip
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The drawer's new parts on their own: state is passed in and nothing here talks to Hermes or changes a setting. */
class DrawerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showSwitch(botMode: Boolean, switching: Boolean = false, taps: MutableList<DrawerMode> = mutableListOf()) {
        compose.setContent { MaterialTheme { DrawerModeSwitch(botMode, switching, { taps += it }, Modifier.width(320.dp)) } }
    }

    @Test fun chatsIsChosenAndBotsEntersBotMode() {
        val taps = mutableListOf<DrawerMode>()
        showSwitch(botMode = false, taps = taps)
        compose.onNodeWithTag("navigation-mode-chats").assertIsSelected().assertTextContains("Chats").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("navigation-mode-bots").assertIsNotSelected().assertTextContains("Bots").performClick()
        assertEquals(listOf(DrawerMode.Bots), taps)
    }

    @Test fun inBotModeBotsIsChosenChatsLeavesAndBotsGoesHome() {
        val taps = mutableListOf<DrawerMode>()
        showSwitch(botMode = true, taps = taps)
        compose.onNodeWithTag("navigation-bot-mode-home").assertIsSelected().performClick()
        compose.onNodeWithTag("navigation-leave-bot-mode").assertIsNotSelected().assertTextContains("Chats").performClick()
        assertEquals(listOf(DrawerMode.Bots, DrawerMode.Chats), taps)
    }

    @Test fun bothSegmentsWaitWhileTheModeIsSwitching() {
        showSwitch(botMode = false, switching = true)
        compose.onNodeWithTag("navigation-mode-chats").assertIsNotEnabled()
        compose.onNodeWithTag("navigation-mode-bots").assertIsNotEnabled()
    }

    @Test fun theUpdateChipIsOneLabelledRowThatOpensTheUpdate() {
        var opened = 0
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { UpdateReadyChip({ opened++ }) } } }
        compose.onNodeWithTag("navigation-update-ready").assertHasClickAction().assertTextContains("Update ready").assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, opened)
    }

    private val catalog = json("profiles" to JSONArray().put(json("name" to "default", "display_name" to "Personal")).put(json("name" to "coder", "display_name" to "Code profile")))

    @Test fun theProfileMenuChecksTheCurrentProfileAndEndsWithManageProfiles() {
        val chosen = mutableListOf<String>()
        var managed = 0
        val rows = catalog.getJSONArray("profiles").let { list -> (0 until list.length()).map { list.getJSONObject(it) } }
        compose.setContent {
            MaterialTheme { Box(Modifier.width(320.dp)) { ProfileSwitcherMenu(true, 320.dp, catalog, rows, "default", {}, { chosen += it }, {}, { managed++ }) } }
        }
        compose.onNodeWithTag("navigation-profile-option:default").assertIsSelected().assertTextContains("Personal").assertHeightIsAtLeast(56.dp)
        compose.onNodeWithTag("navigation-profile-option:coder").assertIsNotSelected().assertTextContains("Code profile").performClick()
        assertEquals(listOf("coder"), chosen)
        compose.onNodeWithTag("navigation-profile-manage").assertTextContains("Manage profiles").assertHeightIsAtLeast(56.dp).performClick()
        assertEquals(1, managed)
        // The menu is as wide as the pill it hangs from, not the 280dp of a plain menu item.
        compose.onNodeWithTag("navigation-profile-menu").assertWidthIsEqualTo(320.dp)
    }

    @Test fun aProfileListThatIsLoadingOrFailedShowsOneRowAndStillOffersManage() {
        val state = mutableStateOf<org.json.JSONObject?>(null)
        var retried = 0
        compose.setContent {
            MaterialTheme { Box(Modifier.width(320.dp)) { ProfileSwitcherMenu(true, 320.dp, state.value, emptyList(), "default", {}, {}, { retried++ }, {}) } }
        }
        compose.onNodeWithTag("navigation-profile-loading").assertExists()
        compose.onNodeWithTag("navigation-profile-manage").assertExists()
        compose.runOnIdle { state.value = json("_error" to "offline") }
        compose.onNodeWithTag("navigation-profile-retry").assertTextContains("Retry profiles").performClick()
        assertEquals(1, retried)
    }
}
