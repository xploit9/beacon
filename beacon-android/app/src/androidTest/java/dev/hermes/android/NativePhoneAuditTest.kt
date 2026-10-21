package dev.hermes.android

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.ui.recordError
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real Activity + native backend reads. No personal notes, skills or server updates are saved. */
class NativePhoneAuditTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private lateinit var vm: BeaconViewModel
    private val steps = mutableListOf<String>()
    private fun waitFor(condition: () -> Boolean) = compose.waitUntil(90_000, condition)
    private fun visible(match: SemanticsMatcher): SemanticsNodeInteraction {
        if (compose.onAllNodes(match).fetchSemanticsNodes().isEmpty()) {
            val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
            compose.onNode(SemanticsMatcher("Main scroll container") { it.id == list.id }).performScrollToNode(match)
        }
        return compose.onAllNodes(match).onFirst().performScrollTo()
    }
    private fun click(label: String) = visible(hasText(label, ignoreCase = true) and hasClickAction()).performClick()
    private fun field(label: String) = visible(hasSetTextAction() and hasText(label, ignoreCase = true))
    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = File(compose.activity.filesDir, "phone-audit/${args.getString("auditCase", "compact")}").apply { mkdirs() }
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        File(dir, "steps.json").writeText(org.json.JSONArray(steps).toString(), Charsets.UTF_8)
    }
    private fun navigate(name: String) {
        if (!vm.ui.value.showSessions) {
            waitFor { compose.onAllNodesWithTag("navigation-toggle").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty() }
            repeat(8) {
                if (compose.onAllNodesWithTag("navigation-toggle").fetchSemanticsNodes().isEmpty()) compose.onNodeWithContentDescription("Back").performClick()
            }
            compose.onNodeWithTag("navigation-toggle").performClick()
        }
        compose.onNodeWithTag("navigation-${name.lowercase()}").performScrollTo().performClick()
        compose.waitForIdle()
        steps.add("Navigation: $name")
    }
    @Test(timeout = 600_000) fun nativeSettingsAndConversationJourneys() {
        assumeTrue(args.getString("beaconLiveUi") == "1")
        require(compose.activity.packageName.endsWith(".debug"))
        val credentials = JSONObject(File(compose.activity.filesDir, "live-auth.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val app = compose.activity.application as BeaconApplication
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        runBlocking { loginLiveTest(app, BuildConfig.DEFAULT_ORIGIN, credentials) }
        compose.runOnIdle { vm.connect(BuildConfig.DEFAULT_ORIGIN) }
        waitFor { vm.ui.value.authenticated && vm.client != null && !vm.ui.value.loading }
        assertEquals("default", vm.ui.value.profile)
        args.getString("auditTheme")?.let { theme ->
            require(theme in setOf("dark", "light"))
            lateinit var saved: kotlinx.coroutines.Job
            compose.runOnIdle { saved = vm.nativePreferences(json("theme" to theme, "reduced_motion" to true)) }
            runBlocking { saved.join() }
        }
        try {
            navigate("Skills")
            waitFor { vm.phoneSettings.skills.value.skills.isNotEmpty() && !vm.phoneSettings.skills.value.loading }
            assertNull(vm.phoneSettings.skills.value.error)
            capture("skills")
            field("Search skills").performTextReplacement("hermes-agent")
            compose.onAllNodesWithText("Read & adjust").onFirst().performScrollTo().performClick()
            waitFor { vm.phoneSettings.skills.value.content != null && !vm.phoneSettings.skills.value.loading }
            click("Adjust instructions")
            field("Skill instructions").performTextReplacement("Unsaved phone audit draft")
            click("Cancel")
            compose.onNodeWithText("Keep editing").performClick()
            assertEquals("Unsaved phone audit draft", vm.phoneSettings.skills.value.draft)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click("Cancel"); compose.onNodeWithText("Discard changes").performClick()
            assertFalse(vm.phoneSettings.skills.value.editing)
            steps.add("Skills read/adjust/cancel and dirty-draft confirmation")

            navigate("Memory")
            waitFor { vm.phoneSettings.memory.value.documents.size == 2 && !vm.phoneSettings.memory.value.loading }
            assertEquals("", vm.phoneSettings.memory.value.error)
            waitFor { compose.onAllNodesWithText("About you").fetchSemanticsNodes().isNotEmpty() }
            capture("memory")
            click("Edit agent notes")
            field("Edit agent notes").performTextReplacement("Unsaved memory audit draft")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("Keep editing").performClick()
            assertEquals("Unsaved memory audit draft", vm.phoneSettings.memory.value.draft)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click("Cancel edit"); compose.onNodeWithText("Discard changes").performClick()
            steps.add("Memory reads both documents; cancel protects unsaved edits")

            navigate("Settings"); click("Profiles")
            waitFor { vm.phoneSettings.bots.value.profiles.isNotEmpty() && !vm.phoneSettings.bots.value.loading }
            capture("profiles")
            compose.onAllNodesWithText("View profile").onFirst().performScrollTo().performClick()
            waitFor { vm.phoneSettings.bots.value.selected != null && !vm.phoneSettings.bots.value.loading }
            click("Change default model")
            waitFor { vm.phoneSettings.botModels.value.isNotEmpty() }
            field("Search models or providers").performTextReplacement("no-model-matches-audit")
            compose.onNodeWithText("No models match this search.").assertExists()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click("Cancel edit")
            steps.add("Profile purpose/model reader, configured search and cancel")

            navigate("Settings")
            click("Tools & MCP"); click("Tools & commands")
            waitFor { vm.data.value["native-tools"] != null }
            assertEquals("", recordError(vm.data.value["native-tools"]))
            capture("tools")
            compose.onNodeWithContentDescription("Back").performClick()
            click("MCP servers")
            waitFor { vm.data.value["native-mcp"] != null }
            assertEquals("", recordError(vm.data.value["native-mcp"]))
            field("Search connections").performTextReplacement("tool_gateway_research")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            click("Test connection")
            waitFor { vm.data.value["mcp-test:tool_gateway_research"] != null }
            assertTrue(vm.data.value.getValue("mcp-test:tool_gateway_research").optBoolean("ok"))
            capture("connections")
            steps.add("Profile-scoped tool groups and MCP connection controls")

            navigate("Settings")
            click("Server & support"); click("Health")
            waitFor { vm.phoneSettings.server.value.version.isNotBlank() && !vm.phoneSettings.server.value.loading }
            assertEquals("", vm.phoneSettings.server.value.error)
            capture("health")
            compose.onNodeWithContentDescription("Back").performClick(); click("Logs")
            waitFor { !vm.phoneSettings.server.value.loading }
            compose.onNodeWithText("Search log entries").performScrollTo().performTextReplacement("beacon-audit-no-such-entry")
            click("Refresh"); waitFor { !vm.phoneSettings.server.value.loading }
            assertTrue(vm.phoneSettings.server.value.logs.isEmpty())
            compose.onNodeWithContentDescription("Back").performClick(); click("Server updates")
            waitFor { vm.phoneSettings.server.value.update != null && !vm.phoneSettings.server.value.loading }
            assertEquals("", vm.phoneSettings.server.value.error)
            capture("server-updates")
            compose.onNodeWithContentDescription("Back").performClick(); click("Session checkpoints")
            waitFor { !vm.phoneSettings.server.value.loading }
            assertEquals("", vm.phoneSettings.server.value.error)
            steps.add("Health, log search, update check/status, checkpoint reads")

            navigate("Settings"); click("Notifications")
            waitFor { vm.data.value["push-status"] != null }
            assertEquals("", recordError(vm.data.value["push-status"]))
            waitFor { compose.onAllNodes(hasText("Checking notification status", substring = true)).fetchSemanticsNodes().isEmpty() }
            capture("notifications")
            navigate("Cron"); waitFor { vm.data.value["crons"] != null }
            compose.onNodeWithContentDescription("Refresh tasks").performClick()
            waitFor { vm.data.value["task-capabilities"] != null && vm.data.value["cron-delivery"] != null }
            capture("cron")
            navigate("Kanban"); compose.onNodeWithTag("kanban-screen").assertExists()
            capture("kanban")
            navigate("Projects"); waitFor { vm.ui.value.tab == "Projects" }
            capture("projects")
            steps.add("Notifications, schedules refresh, Kanban and Projects routes")

            if (args.getString("auditCase", "compact") == "compact") {
                if (!vm.ui.value.showSessions) compose.onNodeWithTag("navigation-toggle").performClick()
                val previousSession = vm.chat.value.sessionId
                compose.onNodeWithTag("navigation-new-chat").performScrollTo().performClick()
                waitFor { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previousSession && !vm.ui.value.newChatOpening }
                val stored = vm.chat.value.sessionId
                File(compose.activity.filesDir, "phone-audit-owned.json").writeText(JSONObject().put("session_id", stored).put("prompt", "Reply with the word verified.").toString())
                try {
                    compose.onNodeWithText("Message ${dev.hermes.android.data.currentAssistantName}").performTextInput("Reply with the word verified.")
                    compose.onNodeWithTag("message-send").performClick()
                    waitFor { vm.chat.value.messages.any { it.role == "user" && it.text == "Reply with the word verified." } }
                    steps.add("Sent user message appears after native acknowledgement")
                    waitFor { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.any { it.role == "assistant" && it.text.contains("verified", true) } }
                    assertFalse(vm.chat.value.status.contains("reconnect", true))
                    capture("chat-completed")
                    steps.add("Native text response completes and clears working state")
                } finally {
                    runBlocking { vm.client?.detached()?.let { c -> try { c.nativeSession(stored).let { binding -> c.nativeRpc("session.interrupt", json("session_id" to binding.runtimeSessionId)); c.nativeRpc("session.set_hidden", json("session_id" to stored, "hidden" to true)); c.nativeRpc("session.close", json("session_id" to binding.runtimeSessionId)) } } finally { c.closeNative() } } }
                }
            }
            capture("completed")
            assertTrue("No native identity error may surface during navigation", !vm.ui.value.error.contains("session identity", true))
        } catch (error: Throwable) { capture("failure"); throw error }
    }
}
