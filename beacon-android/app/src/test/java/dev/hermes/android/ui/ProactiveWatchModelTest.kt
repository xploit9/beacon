package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.data.AppPreferences
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The watch's own model, per profile: saved as watch_model/watch_provider; the assistant's model stays for nightly upkeep. */
class ProactiveWatchModelTest {
    private val assistant = AppPreferences(assistantModel = "assistant-model", assistantProvider = "assistant-provider")

    private fun body(watchModelKey: String, prefs: AppPreferences = assistant) =
        proactiveSettingsBody(true, "notify", 15, "22:00", "07:00", prefs, watchModelKey = watchModelKey)

    @Test fun sameAsAssistantKeepsTheAssistantModelAndSavesNoWatchModel() {
        val saved = body("")
        assertEquals("assistant-model", saved.getString("model"))
        assertEquals("assistant-provider", saved.getString("provider"))
        assertEquals("", saved.getString("watch_model"))
        assertEquals("", saved.getString("watch_provider"))
    }

    @Test fun sameAsAssistantWithNoAssistantModelSavesNoModel() {
        val saved = body("", AppPreferences())
        assertFalse(saved.has("model"))
        assertFalse(saved.has("provider"))
        assertEquals("", saved.getString("watch_model"))
    }

    @Test fun anExplicitWatchModelIsSavedAsTheWatchesOwnAndTheAssistantModelStays() {
        val saved = body("watch-provider\twatch-model")
        assertEquals("assistant-model", saved.getString("model"))
        assertEquals("assistant-provider", saved.getString("provider"))
        assertEquals("watch-model", saved.getString("watch_model"))
        assertEquals("watch-provider", saved.getString("watch_provider"))
    }

    @Test fun pickingTheAssistantsOwnModelSavesSameAsAssistant() {
        val saved = body(proactiveModelKey("assistant-model", "assistant-provider"))
        assertEquals("", saved.getString("watch_model"))
        assertEquals("", saved.getString("watch_provider"))
    }

    @Test fun theChoiceShowsSameAsAssistantWhenTheSavedModelIsTheAssistantsOwn() {
        val assistantKey = proactiveModelKey("assistant-model", "assistant-provider")
        assertEquals("", proactiveWatchModelChoice(assistantKey, assistantKey))
        assertEquals("", proactiveWatchModelChoice("", assistantKey))
        assertEquals("watch-provider\twatch-model", proactiveWatchModelChoice("watch-provider\twatch-model", assistantKey))
    }

    // Device check 8 Nov 2026 (F2): the row shows "provider · name" once the pick is saved, so a check for the bare name never found it.
    // What the row reads is pinned here for the save, a reopen and a list that has not loaded yet.
    private val listedGroups = listOf(json("provider_id" to "fixture", "provider" to "Configured provider", "models" to JSONArray()
        .put(json("id" to "synthetic-model", "label" to "synthetic-model")).put(json("id" to "other-model", "label" to "other-model"))))

    @Test fun theRowReadsTheSavedModelByItsProviderAndNameAndSameAsAssistantWhenNoneIsChosen() {
        val listed = proactiveModelLabels(listedGroups)
        val assistantKey = proactiveModelKey(assistant.assistantModel, assistant.assistantProvider)
        val saved = body("fixture\tother-model")
        // The row re-reads the plugin's answer, so the label is looked up under the key built from the saved pair.
        val choice = proactiveWatchModelChoice(proactiveModelKey(saved.getString("watch_model"), saved.getString("watch_provider")), assistantKey)
        val labels = proactiveWatchModelLabels("Same as assistant", listed, choice)
        assertEquals("Configured provider · Other Model", labels.getValue(choice))
        assertEquals("Same as assistant", labels.getValue(""))
        assertEquals("Configured provider · Synthetic Model", labels.getValue("fixture\tsynthetic-model"))
    }

    @Test fun aSavedPickTheListDoesNotHoldYetReadsAsItsNameNeverAsAnId() {
        val labels = proactiveWatchModelLabels("Same as assistant", emptyMap(), "fixture\tother-model")
        assertEquals("Other Model", labels.getValue("fixture\tother-model"))
        // A blank choice adds nothing beside Same as assistant.
        assertEquals(mapOf("" to "Same as assistant"), proactiveWatchModelLabels("Same as assistant", emptyMap(), ""))
    }

    @Test fun theModelChoicesReadAsNamesWithTheirProvider() {
        val group = json("provider_id" to "claude-cli", "provider" to "Claude CLI",
            "models" to JSONArray().put(json("id" to "claude-cli/claude-opus-5-5", "label" to "claude-cli/claude-opus-5-5")))
        assertEquals("Claude CLI · Claude Opus 5.5", proactiveModelLabels(listOf(group))["claude-cli\tclaude-cli/claude-opus-5-5"])
    }
}
