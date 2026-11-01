package dev.hermes.android.uiux.hermes

import dev.hermes.android.ui.ProfileApprovalChoices
import dev.hermes.android.ui.ProfileModelsCollapsed
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.ToolPill
import dev.hermes.android.ui.ToolPillKind
import dev.hermes.android.ui.approvalChoiceLabel
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.profileApprovalsOff
import dev.hermes.android.ui.profileModelOptionId
import dev.hermes.android.ui.profileModelsMatching
import dev.hermes.android.ui.toolRowPill
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Profiles, approvals, Tools and MCP servers: the pure decisions behind the redesigned pages, and the words they add to the catalog. */
class HermesSettingsTest {
    @Test fun aServerTestOutranksEveryOtherStateOnItsRow() {
        assertEquals(ToolPill(ToolPillKind.TestFailed, SettingsStatus.Error), toolRowPill(mcp = true, managedByPlugin = true, configured = false, testedTools = 3, testFailed = true))
        assertEquals(ToolPill(ToolPillKind.Tested, SettingsStatus.Ok, 3), toolRowPill(mcp = true, managedByPlugin = false, configured = true, testedTools = 3, testFailed = false))
        assertEquals(ToolPill(ToolPillKind.Tested, SettingsStatus.Ok, 0), toolRowPill(mcp = true, managedByPlugin = false, configured = true, testedTools = 0, testFailed = false))
    }

    @Test fun aPluginManagedGroupSaysWhoOwnsItAndASetupGroupSaysSoOnlyWhenNotManaged() {
        assertEquals(ToolPill(ToolPillKind.FromPlugin, SettingsStatus.Off), toolRowPill(mcp = false, managedByPlugin = true, configured = false, testedTools = null, testFailed = false))
        assertEquals(ToolPill(ToolPillKind.SetupNeeded, SettingsStatus.Attention), toolRowPill(mcp = false, managedByPlugin = false, configured = false, testedTools = null, testFailed = false))
        assertNull(toolRowPill(mcp = false, managedByPlugin = false, configured = true, testedTools = null, testFailed = false))
    }

    @Test fun anMcpServerNeverShowsSetupNeededAndATestResultIgnoresTheToolsPage() {
        assertNull(toolRowPill(mcp = true, managedByPlugin = false, configured = false, testedTools = null, testFailed = false))
        // Tool groups never carry a test result, even if a stale one is passed in.
        assertNull(toolRowPill(mcp = false, managedByPlugin = false, configured = true, testedTools = 4, testFailed = true))
    }

    @Test fun approvalsOffIsTheOnlyValueThatShowsTheOffPillAndBothModesRemainChoices() {
        assertTrue(profileApprovalsOff("off"))
        listOf("manual", "smart", "", "yolo").forEach { assertFalse(it, profileApprovalsOff(it)) }
        assertEquals(listOf("manual", "smart"), ProfileApprovalChoices)
        assertEquals(listOf("Manual", "Auto"), ProfileApprovalChoices.map(::approvalChoiceLabel))
    }

    @Test fun modelSearchMatchesProviderOrModelAnywhereAndShowsRepeatsOnce() {
        val models = listOf("openai" to "gpt-5", "anthropic" to "claude-sonnet", "openai" to "gpt-5", "local" to "llama")
        assertEquals(listOf("openai" to "gpt-5", "anthropic" to "claude-sonnet", "local" to "llama"), profileModelsMatching(models, ""))
        assertEquals(listOf("openai" to "gpt-5"), profileModelsMatching(models, "OPENAI"))
        assertEquals(listOf("anthropic" to "claude-sonnet"), profileModelsMatching(models, "claude"))
        assertEquals(listOf("anthropic" to "claude-sonnet"), profileModelsMatching(models, "anthropic claude"))
        assertEquals(emptyList<Pair<String, String>>(), profileModelsMatching(models, "nothing like this"))
    }

    @Test fun aModelCardKeepsTheProviderAndModelApartEvenWhenTheModelHasAColon() {
        assertEquals("openai:gpt-5", profileModelOptionId("openai", "gpt-5"))
        assertTrue("A long catalog starts collapsed", ProfileModelsCollapsed in 4..12)
    }

    @Test fun everyWordTheseFourPagesAddedHasAnEntryInAllFifteenOtherLanguages() {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
            "Run this check from the Beacon app, Android project or repository root."
        }
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8))
        val added = listOf(
            "Your profiles", "Couldn't load approvals", "Approvals off", "No purpose", "Active", "Default model", "Instructions", "No instructions", "For this profile", "Behavior",
            "Use a model ID", "Hide model ID", "Test", "Test failed", "Chat commands", "Saved for new chats", "From {0}", "1 tool", "{0} tools", "No MCP servers",
            "Add servers on your computer", "Set up a provider on your computer", "Couldn't refresh", "More models ({0})", "Default model saved for new chats.",
        )
        val languages = catalog.keys().asSequence().filter { it != "en" }.toList()
        assertEquals(15, languages.size)
        val missing = languages.flatMap { language -> added.filter { catalog.getJSONObject(language).optString(normalized(it)).isBlank() }.map { "$language \"$it\"" } }
        assertEquals("Untranslated: $missing", emptyList<String>(), missing)
        // A placeholder survives translation, or the count or plugin name would never appear.
        languages.forEach { language ->
            listOf("From {0}", "{0} tools", "More models ({0})").forEach { assertTrue("$language $it", catalog.getJSONObject(language).getString(normalized(it)).contains("{0}")) }
        }
    }
}
