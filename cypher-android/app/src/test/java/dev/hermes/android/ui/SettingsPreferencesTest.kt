package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsPreferencesTest {
    @Test fun openingChoiceDoesNotRewriteAnUnlistedSavedValue() {
        val seed = JSONObject().put("choice", "retained-value")
        val field = Field("choice", type = FieldType.CHOICE, options = listOf("first", "second"))
        val form = ActionForm("Choose", "/fixture", listOf(field), seed)
        assertEquals("retained-value", formBody(form.copy(values = formValues(form))).getString("choice"))
        assertEquals("first", formBody(form.copy(values = mapOf("choice" to "first"))).getString("choice"))
        assertEquals("retained-value", seed.getString("choice"))
    }
    @Test fun mcpConfigurationRequiresExactlyOneTransport() {
        val fields = listOf(Field("url"), Field("command"))
        fun form(url: String, command: String) = ActionForm("Configure server", "/api/mcp/servers/test", fields, method = "PUT", values = mapOf("url" to url, "command" to command))
        for ((url, command) in listOf("" to "", "https://fixture.invalid/mcp" to "synthetic-command")) {
            assertTrue(runCatching { formBody(form(url, command)) }.isFailure)
        }
        assertEquals("https://fixture.invalid/mcp", formBody(form("https://fixture.invalid/mcp", "")).getString("url"))
        assertEquals("synthetic-command", formBody(form("", "synthetic-command")).getString("command"))
    }
    @Test fun blankCloneSourceIsOmittedWithoutMutatingTheSeed() {
        val fields = listOf(Field("name", required = true), Field("clone_from"), Field("clone_config", type = FieldType.BOOLEAN))
        val seed = JSONObject().put("clone_from", "old-profile")
        val form = ActionForm("Create profile", "/api/profile/create", fields, seed, values = mapOf("name" to "fresh-profile", "clone_from" to "", "clone_config" to "false"))
        assertFalse(formBody(form).has("clone_from"))
        assertEquals("old-profile", seed.getString("clone_from"))
        assertEquals("default", formBody(form.copy(values = form.values + ("clone_from" to "default"))).getString("clone_from"))
    }
    @Test fun numericBoundsRemainValidatedAndOptionalLimitsCanBeCleared() {
        fun body(key: String, raw: String): JSONObject {
            val type = if (key in setOf("tts_rate", "tts_pitch", "provider_cost_budget")) FieldType.DECIMAL else FieldType.INTEGER
            return formBody(ActionForm("Preferences", "/api/settings", listOf(Field(key, type = type)), values = mapOf(key to raw)))
        }
        for ((key, value) in listOf("tts_rate" to "3", "tts_pitch" to "-1", "voice_silence_ms" to "100", "pinned_sessions_limit" to "0", "inflight_state_max_sessions" to "26", "structured_code_auto_tree_lines" to "1001", "max_tokens" to "0", "provider_cost_budget" to "0.001", "provider_cost_budget" to "0.006")) {
            assertTrue("Accepted invalid $key", runCatching { body(key, value) }.isFailure)
        }
        assertTrue(body("max_tokens", "").isNull("max_tokens"))
        assertTrue(body("provider_cost_budget", "").isNull("provider_cost_budget"))
        assertEquals(.01, body("provider_cost_budget", ".01").getDouble("provider_cost_budget"), .0)
        assertEquals(.5, body("tts_rate", ".5").getDouble("tts_rate"), .0)
    }
    @Test fun booleanPresentationRoundTripsWithoutRewritingTheStoredFlag() {
        val field = Field("hidden", type = FieldType.BOOLEAN, inverse = true)
        assertTrue(displayedBoolean(field, "false"))
        assertFalse(displayedBoolean(field, "true"))
        assertEquals("true", storedBoolean(field, false))
        assertEquals("false", storedBoolean(field, true))
    }
    @Test fun numericChoiceRetainsItsWireTypeAndRejectsInvalidInput() {
        val form = ActionForm("Choose", "/fixture", listOf(Field("count", type = FieldType.CHOICE)), JSONObject().put("count", 10), values = mapOf("count" to "20"))
        assertEquals(20, formBody(form).getInt("count"))
        assertTrue(formBody(form).get("count") is Number)
        assertTrue(runCatching { formBody(form.copy(values = mapOf("count" to "many"))) }.isFailure)
    }
    @Test fun nativeAppearanceChoicesKeepAllShippedStylesAndLanguages() {
        assertEquals(NativeSkins.toSet(), skinLabels.keys)
        assertTrue(skinLabels.values.all(String::isNotBlank))
        assertTrue(NativeLanguages.containsKey("ar"))
    }
    @Test fun theAppReadsInThePhonesLanguage() {
        assertEquals("ar", nativeLanguageFor(java.util.Locale.forLanguageTag("ar-AE")))
        assertEquals("zh-Hant", nativeLanguageFor(java.util.Locale.forLanguageTag("zh-TW")))
        assertEquals("zh-Hant", nativeLanguageFor(java.util.Locale.forLanguageTag("zh-Hant-HK")))
        assertEquals("zh", nativeLanguageFor(java.util.Locale.forLanguageTag("zh-CN")))
        assertEquals("A language without a translation reads English", "en", nativeLanguageFor(java.util.Locale.forLanguageTag("sw-KE")))
    }
    @Test fun errorPresentationDoesNotDisplayJsonNullAsAProblem() {
        assertEquals("", recordError(JSONObject().put("_error", JSONObject.NULL).put("error", JSONObject.NULL)))
        assertEquals("Unavailable", recordError(JSONObject().put("success", false).put("error", "Unavailable")))
        assertEquals("The server could not load this content. Try again.", recordError(JSONObject().put("success", false).put("message", JSONObject.NULL)))
    }
}
