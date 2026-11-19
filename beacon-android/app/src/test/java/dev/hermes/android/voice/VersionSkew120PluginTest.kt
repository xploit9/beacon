package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version skew, 1.2.1 app on a 1.2.0 plugin (CALLS-CONTRACT.md section 8): without call_contract the call is built the 1.2.0 way,
 * and only the app-side improvements apply. The tool requests and polling on such a plugin are in
 * CallToolFlowV3Test.aPluginWithoutTheV3ContractGetsThe120Requests; start diagnostics wait unsent (CallStartDiagnosticsTest); a
 * turn-on route without `auto` gives the Turn on line (ProfileTurnOnTest).
 */
class VersionSkew120PluginTest {
    private fun fixture(name: String) = JSONObject(checkNotNull(javaClass.getResourceAsStream("/calls_v3/$name")).bufferedReader().use { it.readText() })

    @Test fun aCallOnA120PluginUsesItsPersonaTextDiscoversToolsAndKeepsHermesFollow() {
        val v121 = fixture("context_v3.json")
        val context = v120Context()
        val setup = GeminiWire.setup("model", "", null, context, emptySet(), "changes").getJSONObject("setup")
        val text = setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        val functions = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until functions.length()).map { functions.getJSONObject(it).getString("name") }.toSet()
        assertTrue("the 1.2.0 context's own text takes the persona slot", text.contains("Persona:\n" + context.getString("system_instruction")))
        assertTrue("nothing is declared, so the model may discover", text.contains(CallInstruction.DISCOVER.trim()))
        assertTrue(text.contains("Tool catalogue (reference data):\n" + context.getString("tool_catalogue")))
        assertTrue(names.containsAll(setOf("hermes_work", "hermes_follow", "discover_tools", "invoke_tool")))
        val declared = v121.getJSONArray("tool_declarations").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("name") } }
        assertTrue(declared.isNotEmpty())
        assertFalse("a 1.2.0 plugin declares no tools", names.any { it in declared })
        // The 1.2.0 plugin's own language field (no preferred_language) still sets the language before the owner speaks.
        context.getJSONObject("context").put("language", "Arabic")
        assertEquals("Arabic", CallInstruction.language(context))
        assertTrue(CallInstruction.brief(context, emptySet(), "changes", allDeclared = false).contains("otherwise Arabic"))
    }

    companion object {
        /** Every field lane A added to the call contexts in 1.2.1 (contract sections 0, 1 and 7); a 1.2.0 plugin sends none of them. */
        val V121_CONTEXT_ONLY = listOf("call_contract", "call_capabilities", "tool_declarations", "tool_declarations_complete",
            "tool_declarations_omitted", "tool_declarations_digest", "persona", "preferred_language", "local_time")

        /** A 1.2.0 plugin's call context: the canonical 1.2.1 fixture without the 1.2.1 fields. */
        fun v120Context(): JSONObject = JSONObject(checkNotNull(VersionSkew120PluginTest::class.java.getResourceAsStream("/calls_v3/context_v3.json"))
            .bufferedReader().use { it.readText() }).apply { V121_CONTEXT_ONLY.forEach(::remove) }
    }
}
