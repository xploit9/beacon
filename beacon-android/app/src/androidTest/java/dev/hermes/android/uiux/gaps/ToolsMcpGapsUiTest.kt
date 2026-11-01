package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.ui.NativeCatalog
import dev.hermes.android.ui.NativeToolSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

/**
 * The Tools and MCP servers pages on a device: first load, failed read with Retry, a saved copy under a failed refresh, empty lists, search with
 * Clear search, and Refresh. The page's own read ends at once with the offline message, since the ViewModel has no client.
 */
class ToolsMcpGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val offline = "Reconnect to refresh this page."
    private fun key(mcp: Boolean) = if (mcp) "native-mcp" else "native-tools"
    private fun list(mcp: Boolean) = if (mcp) "servers" else "items"
    private fun tag(mcp: Boolean) = if (mcp) "mcp-settings" else "tool-settings"
    private fun rows(mcp: Boolean, vararg names: String) = json(list(mcp) to JSONArray(names.map { name ->
        if (mcp) json("name" to name, "enabled" to true, "transport" to "stdio", "source" to "config")
        else json("name" to name, "label" to "Tool $name", "description" to "Does $name", "enabled" to true, "configured" to true, "tools" to JSONArray(listOf("${name}_run")))
    }))
    private fun empty(mcp: Boolean) = json(list(mcp) to JSONArray())

    private fun show(vm: BeaconViewModel, mcp: Boolean) {
        compose.showPage { NativeToolSettings(vm, mcp = mcp) }
        compose.waitForIdle()
    }
    private fun serve(vm: BeaconViewModel, mcp: Boolean, payload: JSONObject) = compose.runOnIdle { vm.storeData(key(mcp), payload) }
    private fun search(mcp: Boolean) = compose.onNode(hasSetTextAction() and hasText(if (mcp) "Search MCP servers" else "Search tools"))

    // The same behaviour on both pages

    private fun firstRead(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        compose.runOnIdle { vm.forget(key(mcp)) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithText(offline).assertDoesNotExist()
        compose.onNodeWithText(if (mcp) "No MCP servers" else "No tools yet").assertDoesNotExist()
    }
    @Test fun toolsFirstReadShowsPlaceholderRows() = firstRead(false)
    @Test fun mcpFirstReadShowsPlaceholderRows() = firstRead(true)

    private fun failedRead(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        compose.onNodeWithTag(tag(mcp)).assertIsDisplayed()
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.onNodeWithTag("tools-retry").assertIsDisplayed()
        compose.onNodeWithText(if (mcp) "No MCP servers" else "No tools yet").assertDoesNotExist()
        serve(vm, mcp, json("_error" to "Synthetic read failure"))
        compose.onNodeWithText("Synthetic read failure").assertIsDisplayed()
        compose.onNodeWithTag("tools-retry").performClick()
        compose.onNodeWithText("Synthetic read failure").assertDoesNotExist()
        compose.onNodeWithText(offline).assertIsDisplayed()
    }
    @Test fun toolsFailedReadSaysWhyAndRetryReadsAgain() = failedRead(false)
    @Test fun mcpFailedReadSaysWhyAndRetryReadsAgain() = failedRead(true)

    private fun savedCopyUnderFailure(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        serve(vm, mcp, rows(mcp, "alpha").put("_error", "Synthetic refresh failure"))
        compose.onNodeWithText("Couldn't refresh").assertIsDisplayed()
        compose.onNodeWithText("Synthetic refresh failure").assertDoesNotExist()
        compose.onNodeWithTag("tool-row:alpha").assertIsDisplayed()
        compose.onNodeWithTag("tools-retry").assertIsDisplayed()
    }
    @Test fun toolsKeepTheirRowsUnderAFailedRefresh() = savedCopyUnderFailure(false)
    @Test fun mcpKeepsItsRowsUnderAFailedRefresh() = savedCopyUnderFailure(true)

    private fun emptyPage(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        serve(vm, mcp, empty(mcp))
        compose.onNodeWithText(if (mcp) "No MCP servers" else "No tools yet").assertIsDisplayed()
        if (mcp) compose.onNodeWithText("Add servers on your computer").assertIsDisplayed() else compose.onNodeWithText("Add servers on your computer").assertDoesNotExist()
        compose.onNodeWithText(offline).assertDoesNotExist()
        // Refresh in the empty row reads again: offline, that reads as the failure message.
        compose.onNodeWithText("Refresh").performClick()
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.onNodeWithText(if (mcp) "No MCP servers" else "No tools yet").assertDoesNotExist()
    }
    @Test fun toolsEmptyListOffersRefresh() = emptyPage(false)
    @Test fun mcpEmptyListOffersRefreshAndSaysWhereServersAreAdded() = emptyPage(true)

    private fun searching(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        serve(vm, mcp, rows(mcp, "alpha", "beta"))
        compose.onNodeWithTag("tool-row:alpha").assertIsDisplayed()
        compose.onNodeWithTag("tool-row:beta").assertIsDisplayed()
        search(mcp).performTextReplacement("beta")
        compose.onNodeWithTag("tool-row:beta").assertIsDisplayed()
        compose.onNodeWithTag("tool-row:alpha").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("beta")).performTextReplacement("zzz")
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("Add servers on your computer").assertDoesNotExist()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("No matches").assertDoesNotExist()
        compose.onNodeWithTag("tool-row:alpha").assertIsDisplayed()
        compose.onNodeWithTag("tool-row:beta").assertIsDisplayed()
    }
    @Test fun toolsSearchNarrowsAndClearSearchRestores() = searching(false)
    @Test fun mcpSearchNarrowsAndClearSearchRestores() = searching(true)

    private fun refreshing(mcp: Boolean) = withClientlessVm(compose) { vm ->
        show(vm, mcp)
        serve(vm, mcp, json("_error" to "Synthetic read failure"))
        compose.onNodeWithTag("tools-refresh").assertIsEnabled().performClick()
        compose.onNodeWithText("Synthetic read failure").assertDoesNotExist()
        serve(vm, mcp, rows(mcp, "alpha"))
        compose.runOnIdle { vm.refreshingFlow.value = setOf(key(mcp)) }
        compose.onNodeWithTag("tools-refresh").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("tool-row:alpha").assertIsDisplayed()
        compose.runOnIdle { vm.refreshingFlow.value = emptySet() }
        compose.onNodeWithTag("tools-refresh").assertIsEnabled()
        compose.onNodeWithTag("settings-refreshing").assertDoesNotExist()
    }
    @Test fun toolsRefreshReadsAgainAndWaitsWhileAReadRuns() = refreshing(false)
    @Test fun mcpRefreshReadsAgainAndWaitsWhileAReadRuns() = refreshing(true)

    // The chat's Tools sheet hosts the same two pages

    @Test fun theChatsToolsSheetSwitchesBetweenToolsAndMcpServers() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.catalogFlow.value = "Toolsets" }
        compose.showPage { NativeCatalog(vm, containedActions = true) }
        compose.waitForIdle()
        compose.onNodeWithTag("chat-toolsets").assertIsDisplayed()
        compose.onNodeWithTag("chat-toolsets-tools").assertIsSelected()
        compose.onNodeWithTag("tool-settings").assertIsDisplayed()
        compose.onNodeWithText("Changes apply to this profile's new chats.").assertDoesNotExist()
        compose.onNodeWithTag("chat-toolsets-mcp").performClick()
        compose.onNodeWithTag("chat-toolsets-mcp").assertIsSelected()
        compose.onNodeWithTag("mcp-settings").assertIsDisplayed()
        compose.onNodeWithText("Changes apply to this profile's new chats.").assertIsDisplayed()
        compose.onNodeWithTag("tool-settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Close picker").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("chat-toolsets").assertDoesNotExist()
    }
}
