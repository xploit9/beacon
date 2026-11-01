package dev.hermes.android.uiux.gaps

import dev.hermes.android.core.json
import dev.hermes.android.ui.currentPickerProvider
import dev.hermes.android.ui.modelSearchMatches
import dev.hermes.android.ui.modelSubtitle
import dev.hermes.android.ui.pickerGroupNamed
import dev.hermes.android.ui.pinSelectedModel
import dev.hermes.android.ui.reasoningLevelDescription
import dev.hermes.android.ui.reasoningLevelLabel
import dev.hermes.android.ui.shortProviderLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test

/** Providers & models: which provider group is current, the model sheet's row text and the reasoning level words. */
class ProvidersModelsGapsTest {
    private fun model(id: String, label: String = id) = json("id" to id, "label" to label)
    private fun group(id: String, models: List<String>, extra: List<String> = emptyList(), aliases: List<String> = emptyList(), label: String = id) = json(
        "provider_id" to id, "provider" to label, "models" to JSONArray(models.map { model(it) }), "extra_models" to JSONArray(extra.map { model(it) }), "aliases" to JSONArray(aliases),
    )
    private val levels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    @Test fun aGroupIsFoundByItsSlugOrAliasWithoutCaseOrPadding() {
        val groups = listOf(group("openai", listOf("a"), aliases = listOf("oai")), group("custom", listOf("b")))
        assertEquals("openai", pickerGroupNamed(groups, " OpenAI "))
        assertEquals("openai", pickerGroupNamed(groups, "OAI"))
        assertEquals("custom", pickerGroupNamed(groups, "CUSTOM"))
    }

    @Test fun aBlankOrUnknownNameFindsNoGroup() {
        val groups = listOf(group("openai", listOf("a")))
        assertEquals("", pickerGroupNamed(groups, ""))
        assertEquals("", pickerGroupNamed(groups, "   "))
        assertEquals("", pickerGroupNamed(groups, "anthropic"))
        assertEquals("", pickerGroupNamed(emptyList(), "openai"))
    }

    @Test fun withNoProviderTheFirstGroupListingTheModelIsCurrentAndExtraModelsCount() {
        val groups = listOf(group("a", listOf("m1")), group("b", listOf("m2"), extra = listOf("m1", "z")))
        assertEquals("The first group holding the model wins", "a", currentPickerProvider(groups, "", "m1"))
        assertEquals("A model only in extra_models still counts", "b", currentPickerProvider(groups, "", "z"))
        assertEquals("", currentPickerProvider(groups, "", "unlisted"))
        assertEquals("No provider and no model has no current group", "", currentPickerProvider(groups, "", ""))
    }

    @Test fun aModelBothGroupsListIsAmbiguousUntilHermesMarksARow() {
        val groups = listOf(group("a", listOf("shared")), group("b", listOf("shared")))
        assertEquals("Two groups list it, so a browsed chat cannot say which one", "", currentPickerProvider(groups, "unknown-name", "shared"))
        assertEquals("The row Hermes marked for the running chat decides", "b", currentPickerProvider(groups, "unknown-name", "shared", hermesRow = "b"))
        assertEquals("A marked row that is not a group changes nothing", "", currentPickerProvider(groups, "unknown-name", "shared", hermesRow = "missing"))
    }

    @Test fun aNamedProviderBeatsTheModelAndTheHermesRow() {
        val groups = listOf(group("a", listOf("shared")), group("b", listOf("shared")))
        assertEquals("b", currentPickerProvider(groups, "b", "shared", hermesRow = "a"))
    }

    @Test fun aProviderLabelLosesItsQualifierWhateverTheDash() {
        assertEquals("Nous Portal", shortProviderLabel("Nous Portal – Hermes models"))
        assertEquals("Nous Portal", shortProviderLabel("Nous Portal — Hermes models"))
        assertEquals("OpenAI", shortProviderLabel("  OpenAI (API key)  "))
        assertEquals("A label that starts with a bracket stays whole", "(local)", shortProviderLabel(" (local) "))
        assertEquals("", shortProviderLabel("   "))
    }

    @Test fun aModelShowsItsIdOnlyWhenTheNameDiffers() {
        assertEquals("", modelSubtitle("gpt-6", "gpt-6"))
        assertEquals("gpt-6-luna", modelSubtitle("Luna", "gpt-6-luna"))
        assertEquals("", modelSubtitle("", ""))
    }

    @Test fun theChatsModelOnlyLeadsWhenItsProviderIsCurrent() {
        val models = listOf(model("a"), model("b"), model("c"))
        assertEquals(listOf("c", "a", "b"), pinSelectedModel(models, "c", true).map { it.getString("id") })
        assertEquals(listOf("a", "b", "c"), pinSelectedModel(models, "c", false).map { it.getString("id") })
        assertEquals(listOf("a", "b", "c"), pinSelectedModel(models, "", true).map { it.getString("id") })
    }

    @Test fun modelSearchMatchesEveryWordAcrossProviderIdAndName() {
        assertTrue(modelSearchMatches("OpenAI Codex", "gpt-6-luna", "Luna", "codex luna"))
        assertTrue(modelSearchMatches("OpenAI Codex", "gpt-6-luna", "Luna", "GPT-6"))
        assertFalse("Every word has to match", modelSearchMatches("OpenAI Codex", "gpt-6-luna", "Luna", "codex sonnet"))
        assertTrue("A blank search matches all", modelSearchMatches("p", "id", "label", "   "))
    }

    @Test fun everyReasoningLevelHasAShortWordAndASentence() {
        assertEquals("Extra high", reasoningLevelLabel("xhigh"))
        assertEquals("Low", reasoningLevelLabel("low"))
        assertEquals("", reasoningLevelLabel(""))
        for (level in levels) {
            assertTrue("$level label \"${reasoningLevelLabel(level)}\" must fit a segment", reasoningLevelLabel(level).length in 1..12)
            assertTrue("$level has its own sentence", reasoningLevelDescription(level) != reasoningLevelDescription("unknown-level"))
            assertTrue("$level sentence is short", reasoningLevelDescription(level).length <= 60)
        }
        assertEquals("Use the model's default reasoning", reasoningLevelDescription("unknown-level"))
        assertEquals("A level reads the same in any case", reasoningLevelDescription("high"), reasoningLevelDescription("HIGH"))
    }

    @Test fun theSheetsOwnTextIsTranslatedInEveryLanguage() {
        val shown = listOf(
            "Open a chat first", "Profile default", "Couldn't load models", "No matching models", "Couldn't load reasoning", "This model manages reasoning automatically.",
            "No reasoning choices are available.", "Applying model...", "Refresh models", "Close picker", "Enter a configured model ID", "Search model or provider", "Clear search",
        )
        val missing = shown.flatMap { text -> GapsAssets.languages.filter { it != "en" && GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Providers & models text without a translation:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** The level words and sentences reach `uiText` through variables, so the literal scan never sees them and no language has an entry. */
    @Test fun everyReasoningWordAndSentenceIsTranslated() {
        val texts = levels.map(::reasoningLevelLabel) + levels.map(::reasoningLevelDescription) + reasoningLevelDescription("unknown-level")
        val missing = texts.distinct().flatMap { text -> GapsAssets.languages.filter { it != "en" && GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Reasoning text without a translation:\n" + missing.take(20).joinToString("\n"), missing.isEmpty())
    }
}
