package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import dev.hermes.android.BeaconViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import dev.hermes.android.BeaconTheme
import dev.hermes.android.preferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LaneR8KWideLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    private fun withViewModel(block: (BeaconViewModel) -> Unit) {
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
                vm = ViewModelProvider(store, viewModelFactory {
                    initializer { BeaconViewModel(app, SavedStateHandle()) }
                })[BeaconViewModel::class.java]
                ui(vm).value = AppState(
                    origin = "", tab = "More", connected = false,
                    initialChatReady = true, restoringSession = false,
                    setupComplete = true, morePage = "App updates",
                )
            }
            block(vm)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { savedPreferences } }
        }
    }

    @androidx.compose.runtime.Composable
    private fun targetWindow(fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            Box(Modifier.requiredSize(870.dp, 940.dp).testTag("lane-r8k-window")) { content() }
        }
    }

    private fun widthDp(tag: String): Float = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().boundsInRoot.width / compose.activity.resources.displayMetrics.density

    @Suppress("UNCHECKED_CAST")
    private fun data(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_data")
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<Map<String, JSONObject>>

    private fun assertSingleUnclippedLine(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals("Expected one layout for $text", 1, layouts.size)
        assertEquals("$text wraps", 1, layouts.single().lineCount)
        assertTrue("$text is ellipsized", !layouts.single().isLineEllipsized(0))
    }

    @Test fun settingsUsesAListAndCappedDetailAtTheTargetWindow() = withViewModel { vm ->
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                targetWindow { MoreScreen(vm) }
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("lane-r8k-window").assertExists()
        compose.onNodeWithTag("settings-wide-list").assertIsDisplayed()
        compose.onNodeWithTag("settings-wide-detail").assertExists()
        compose.onNodeWithTag("settings-row:App updates").assertIsSelected()
        val appUpdateTitles = compose.onAllNodesWithText("App updates", useUnmergedTree = true).fetchSemanticsNodes()
        // The 870 dp window can overflow a phone-sized test screen, so compare with the list's right edge, not x = 0.
        val listRight = compose.onNodeWithTag("settings-wide-list").fetchSemanticsNode().boundsInRoot.right
        assertTrue("Selected Settings page did not render in the detail pane", appUpdateTitles.any { it.boundsInRoot.left >= listRight })
        assertTrue("Settings hub exceeds 360 dp: ${widthDp("settings-wide-list")}", widthDp("settings-wide-list") <= 360f)
        assertTrue("Settings detail exceeds 640 dp: ${widthDp("settings-wide-detail")}", widthDp("settings-wide-detail") <= 640f)
    }

    @Test fun drawerLabelsFitAtDefaultAndLargeText() = withViewModel { vm ->
        val fontScale = mutableFloatStateOf(1f)
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                targetWindow(fontScale.floatValue) {
                    AdaptiveCallNavigation(
                        drawer = rememberDrawerState(DrawerValue.Open),
                        showNavigation = true,
                        navigation = {
                            SessionList(vm, Modifier.fillMaxSize().testTag("navigation-menu"), beforeHistory = { SidebarNavigation(vm) })
                        },
                        content = { Box(Modifier.fillMaxSize()) },
                    )
                }
            } }
        }
        fun assertDrawer(fontScaleValue: Float, panelTag: String) {
            compose.onNodeWithTag("navigation-bots").assertTextEquals("Bot Mode")
            compose.onNodeWithTag("history-view-all").assertTextEquals("All")
            compose.onNodeWithTag("history-view-pinned").assertTextEquals("Pinned")
            compose.onNodeWithTag("history-threads").assertTextEquals("Threads")
            listOf("Bot Mode", "All", "Pinned", "Threads").forEach(::assertSingleUnclippedLine)
            compose.onNodeWithTag(panelTag).assertIsDisplayed()
            val panelWidth = widthDp(panelTag)
            assertTrue("Drawer is narrower than 320 dp at font scale $fontScaleValue: $panelWidth", panelWidth >= 320f)
            assertTrue("Drawer exceeds 360 dp at font scale $fontScaleValue: $panelWidth", panelWidth <= 360f)
        }
        compose.waitForIdle()
        assertDrawer(1f, "beacon-persistent-navigation")
        compose.runOnIdle { fontScale.floatValue = 1.3f }
        compose.waitForIdle()
        assertDrawer(1.3f, "navigation-menu")
    }

    @Test fun filesKeepsItsWideBrowserWithinTheContentCap() = withViewModel { vm ->
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                targetWindow { SpacesScreen(vm) }
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("files-content").assertIsDisplayed()
        assertTrue("Files content exceeds 720 dp: ${widthDp("files-content")}", widthDp("files-content") <= 720f)
    }

    @Test fun filesPathEllipsizesOnOneLineAtPhoneWidth() = withViewModel { vm ->
        val path = "C:\\Users\\someone\\Documents\\very-long-project-name\\nested\\last-folder"
        data(vm).value = mapOf("files" to JSONObject().put("_requested_path", "").put("path", path).put("entries", JSONArray()))
        compose.setContent {
            NativeLocalization("en") { BeaconTheme {
                Box(Modifier.requiredSize(360.dp, 740.dp)) { SpacesScreen(vm) }
            } }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("files-path", useUnmergedTree = true).assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("files-path", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertTrue("Long Files path should ellipsize rather than wrap", layouts.single().isLineEllipsized(0))
    }
}
