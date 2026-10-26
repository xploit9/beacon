package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelPickerProviderTest {
    private fun group(id: String, vararg models: String, aliases: List<String> = emptyList()): JSONObject =
        json("provider_id" to id, "provider" to id, "aliases" to JSONArray(aliases), "models" to JSONArray(models.map { json("id" to it, "label" to it) }))

    private val groups = listOf(
        group("nous", "hermes-4"),
        group("copilot", "gpt-6-luna", "claude-x"),
        group("opencodex", "gpt-6-luna"),
        group("acme-llm", "acme-1", aliases = listOf("custom:acme-llm", "Acme LLM")),
    )

    @Test fun theGroupNamedByTheChatsProviderIsCurrent() {
        assertEquals("opencodex", currentPickerProvider(groups, "opencodex", "gpt-6-luna"))
        assertEquals("Hermes writes some ids in other letter cases", "nous", currentPickerProvider(groups, "Nous", "hermes-4"))
    }

    @Test fun aCustomProviderIsFoundByTheNamesHermesListsForIt() {
        assertEquals("acme-llm", currentPickerProvider(groups, "custom:acme-llm", "acme-1"))
        assertEquals("acme-llm", currentPickerProvider(groups, "acme llm", "acme-1"))
        assertEquals("acme-llm", pickerGroupNamed(groups, "custom:acme-llm"))
    }

    // Live 2026-10-26: a Copilot chat reported "github-copilot", the picker group is "copilot", and the model is also offered by another group.
    @Test fun aProviderHermesNamesDifferentlyIsFoundThroughTheRowHermesMarkedForTheChat() {
        assertEquals("No group carries that name", "", pickerGroupNamed(groups, "github-copilot"))
        assertEquals("copilot", currentPickerProvider(groups, "github-copilot", "gpt-6-luna", hermesRow = "copilot"))
        assertEquals("Without Hermes' answer the model is not enough to tell the groups apart", "", currentPickerProvider(groups, "github-copilot", "gpt-6-luna"))
        assertEquals("A row that is not in the list marks nothing", "", currentPickerProvider(groups, "github-copilot", "gpt-6-luna", hermesRow = "gone"))
        assertEquals("The name beats Hermes' answer", "opencodex", currentPickerProvider(groups, "opencodex", "gpt-6-luna", hermesRow = "copilot"))
    }

    @Test fun withNoProviderReportedTheGroupHoldingTheModelIsCurrent() {
        assertEquals("nous", currentPickerProvider(groups, "", "hermes-4"))
        assertEquals("", currentPickerProvider(groups, "", "unlisted-model"))
        assertEquals("", currentPickerProvider(groups, "", ""))
        assertEquals("", currentPickerProvider(emptyList(), "copilot", "gpt-6-luna"))
    }

    @Test fun aBrowsedChatWithAnUnknownProviderNameUsesTheOnlyGroupListingItsModel() {
        val groups = listOf(
            org.json.JSONObject().put("provider_id", "copilot").put("models", org.json.JSONArray().put(org.json.JSONObject().put("id", "claude-haiku-5.5"))),
            org.json.JSONObject().put("provider_id", "opencodex").put("models", org.json.JSONArray().put(org.json.JSONObject().put("id", "gpt-6-luna"))),
        )
        org.junit.Assert.assertEquals("copilot", currentPickerProvider(groups, "github-copilot", "claude-haiku-5.5"))
        val shared = groups + org.json.JSONObject().put("provider_id", "nous").put("models", org.json.JSONArray().put(org.json.JSONObject().put("id", "claude-haiku-5.5")))
        org.junit.Assert.assertEquals("Two groups list the model: nothing is guessed", "", currentPickerProvider(shared, "github-copilot", "claude-haiku-5.5"))
    }
}
