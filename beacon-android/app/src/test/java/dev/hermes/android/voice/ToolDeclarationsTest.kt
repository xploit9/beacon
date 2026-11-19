package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls contract v3, section 1 on the app side: the profile's own Hermes tools are declared at setup, in each provider's form, and a
 * declaration that does not fit is left out and counted instead of making the provider refuse the session.
 */
class ToolDeclarationsTest {
    private fun fixture(name: String) = JSONObject(checkNotNull(javaClass.getResourceAsStream("/calls_v3/$name")).bufferedReader().use { it.readText() })
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> value.toDouble().let { if (it % 1.0 == 0.0 && kotlin.math.abs(it) < 1e15) it.toLong().toString() else it.toString() }
        else -> if (value == null || value == JSONObject.NULL) "null" else value.toString()
    }
    private fun setup(context: JSONObject) = GeminiWire.setup("model", "", null, context, emptySet(), "changes").getJSONObject("setup")
    private fun declared(setup: JSONObject): Map<String, JSONObject> = setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        .let { rows -> (0 until rows.length()).associate { rows.getJSONObject(it).getString("name") to rows.getJSONObject(it) } }
    private fun instruction(setup: JSONObject) = setup.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
    private val laneA = setOf("session_search", "notes_search", "t_hermes_notes_create")

    @Test fun aV3ContextDeclaresItsToolsBesideTheCallsOwnFunctions() {
        val context = fixture("context_v3.json")
        val functions = declared(setup(context))
        assertTrue(functions.keys.containsAll(laneA))
        assertTrue("omitted and newly added tools stay reachable", functions.keys.containsAll(listOf("discover_tools", "invoke_tool", "hermes_work", "hermes_callback", "get_visual_state")))
        assertFalse("a v3 invoke waits for its result and never transfers", "hermes_follow" in functions)
        val search = functions.getValue("session_search")
        assertEquals("NON_BLOCKING", search.getString("behavior"))
        assertEquals("Search past conversations of this profile.", search.getString("description"))
        val parameters = search.getJSONObject("parameters")
        assertEquals("OBJECT", parameters.getString("type"))
        assertEquals(listOf("query"), parameters.getJSONArray("required").let { r -> (0 until r.length()).map(r::getString) })
        val limit = parameters.getJSONObject("properties").getJSONObject("limit")
        assertEquals("INTEGER", limit.getString("type")); assertTrue(limit.getBoolean("nullable"))
        assertEquals(1, limit.getInt("minimum")); assertEquals(20, limit.getInt("maximum"))
        val notes = functions.getValue("notes_search").getJSONObject("parameters").getJSONObject("properties")
        val filter = notes.getJSONObject("filter")
        assertFalse("an anyOf node needs no type of its own", filter.has("type"))
        assertEquals("STRING", filter.getJSONArray("anyOf").getJSONObject(0).getString("type"))
        assertEquals("[\"open\",\"done\"]", filter.getJSONArray("anyOf").getJSONObject(0).getJSONArray("enum").toString())
        assertEquals("OBJECT", filter.getJSONArray("anyOf").getJSONObject(1).getString("type"))
        assertEquals("ARRAY", notes.getJSONObject("tags").getString("type")); assertEquals(5, notes.getJSONObject("tags").getInt("maxItems"))
        assertEquals("Allowed values: 1, 2, 3.", notes.getJSONObject("priority").getString("description"))
    }

    @Test fun onlyAnIncompleteListBringsTheDiscoverSentenceAndTheCatalogue() {
        val context = fixture("context_v3.json")
        val partial = instruction(setup(context))
        assertTrue(partial.contains(CallInstruction.DISCOVER.trim()))
        assertTrue(partial.contains("Tool catalogue (reference data):\n" + context.getString("tool_catalogue")))
        val whole = setup(JSONObject(context.toString()).put("tool_declarations_complete", true))
        assertFalse(instruction(whole).contains("discover_tools"))
        assertFalse(instruction(whole).contains("Tool catalogue"))
        assertTrue(declared(whole).keys.containsAll(listOf("discover_tools", "invoke_tool")))
        // A v2 plugin declares nothing: discover and the catalogue as before, and hermes_follow still works there.
        val v2 = setup(JSONObject().put("tool_catalogue", "notes: search"))
        assertTrue(instruction(v2).contains(CallInstruction.DISCOVER.trim())); assertTrue(instruction(v2).contains("notes: search"))
        assertTrue("hermes_follow" in declared(v2))
    }

    @Test fun anAwkwardDeclarationIsLeftOutAndCountedAndTheRestKeepOnlyThePortableKeys() {
        val parsed = ToolDeclarations.from(fixture("c_declarations_awkward.json"), GeminiWire.CALL_FUNCTIONS)
        assertEquals(listOf("weather_lookup", "notes_find"), parsed.tools.map { it.name })
        assertEquals(17, parsed.dropped)
        assertFalse("a tool left out makes the list incomplete, so the model may discover it", parsed.complete)
        assertTrue(parsed.tools[0].readOnly); assertEquals("d1".repeat(32), parsed.tools[0].toolId)
        val weather = parsed.gemini().first().getJSONObject("parameters")
        assertFalse(weather.has("additionalProperties")); assertFalse(weather.has("\$schema"))
        assertEquals("Forecast request", weather.getString("title"))
        val properties = weather.getJSONObject("properties")
        val place = properties.getJSONObject("place")
        assertEquals("STRING", place.getString("type")); assertTrue(place.getBoolean("nullable"))
        assertTrue("int64 counts stay JSON numbers", place.get("maxLength") is Number); assertEquals(200L, (place.get("maxLength") as Number).toLong())
        assertEquals("^[^\\n]+$", place.getString("pattern"))
        val units = properties.getJSONObject("units")
        assertEquals("STRING", units.getString("type")); assertTrue(units.getBoolean("nullable")); assertEquals(2, units.getJSONArray("enum").length())
        assertEquals("uri", properties.getJSONObject("source").getString("format"))
        assertEquals("https://example.com/forecast", properties.getJSONObject("source").getString("default"))
        assertEquals(14.5, properties.getJSONObject("days").getDouble("maximum"), 0.0)
        val stops = properties.getJSONObject("stops")
        assertEquals("ARRAY", stops.getString("type")); assertEquals(0, stops.getInt("minItems"))
        assertEquals("OBJECT", stops.getJSONObject("items").getString("type"))
        assertEquals("STRING", stops.getJSONObject("items").getJSONObject("properties").getJSONObject("label").getString("type"))
        assertEquals("NUMBER", properties.getJSONObject("either").getJSONArray("anyOf").getJSONObject(1).getString("type"))
        // Gemini Live probe, 14 Nov 2026: an object without properties is accepted, an array without items refuses the whole
        // setup, so that one is left out (untyped_array above) and stays reachable through discover_tools.
        assertEquals("OBJECT", properties.getJSONObject("extra").getString("type")); assertFalse(properties.getJSONObject("extra").has("properties"))
        assertFalse(properties.has("raw"))
        val oneArray = JSONObject().put("tool_declarations", JSONArray().put(JSONObject().put("name", "list_tool").put("description", "Synthetic.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().put("ids", JSONObject().put("type", "array"))))))
        assertEquals(1, ToolDeclarations.from(oneArray, emptySet()).dropped)
        oneArray.getJSONArray("tool_declarations").getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("ids").put("items", JSONObject().put("type", "string"))
        assertEquals(0, ToolDeclarations.from(oneArray, emptySet()).dropped)
    }

    @Test fun grokAndOpenAiGetThePortableSchemaExactlyAsTheContextCarriedIt() {
        for (name in listOf("context_v3.json", "c_declarations_awkward.json")) {
            val context = fixture(name)
            val gemini = setup(context)
            val grok = GrokWire.functionTools(gemini).let { rows -> (0 until rows.length()).associate { rows.getJSONObject(it).getString("name") to rows.getJSONObject(it) } }
            val openAi = OpenAiWire.setup(JSONObject().put("setup", gemini)).getJSONObject("session").getJSONArray("tools")
                .let { rows -> (0 until rows.length()).associate { rows.getJSONObject(it).getString("name") to rows.getJSONObject(it) } }
            for (tool in ToolDeclarations.from(context, GeminiWire.CALL_FUNCTIONS).tools) {
                val sent = grok.getValue(tool.name)
                assertEquals("function", sent.getString("type")); assertFalse(sent.has("behavior"))
                assertEquals(tool.description, sent.getString("description"))
                assertEquals(tool.name, canonical(tool.parameters), canonical(sent.getJSONObject("parameters")))
                assertEquals(canonical(tool.parameters), canonical(openAi.getValue(tool.name).getJSONObject("parameters")))
            }
        }
        // Lane A's schemas are already portable: Grok gets them byte for byte in meaning.
        val raw = fixture("context_v3.json").getJSONArray("tool_declarations")
        val grok = GrokWire.functionTools(setup(fixture("context_v3.json")))
        for (i in 0 until raw.length()) {
            val sent = (0 until grok.length()).map(grok::getJSONObject).single { it.getString("name") == raw.getJSONObject(i).getString("name") }
            assertEquals(canonical(raw.getJSONObject(i).getJSONObject("parameters")), canonical(sent.getJSONObject("parameters")))
        }
    }

    @Test fun lanesAsSchemasForArraysAndStringFormatsAreDeclaredAsTheyCome() {
        // Lane A's plugin output for an untyped array and for string formats (its fixtures' "portable" schemas): kept, never dropped.
        val rows = JSONArray()
        for ((name, file) in listOf("arrays_tool" to "declaration_array_without_items.json", "formats_tool" to "declaration_string_formats.json"))
            rows.put(JSONObject().put("name", name).put("description", "Synthetic tool.").put("parameters", fixture(file).getJSONObject("portable"))
                .put("read_only", true).put("tool_id", "e".repeat(64)))
        val context = fixture("context_v3.json").put("tool_declarations", rows)
        val declarations = ToolDeclarations.from(context, GeminiWire.CALL_FUNCTIONS)
        assertEquals(0, declarations.dropped)
        val formats = declared(setup(context)).getValue("formats_tool").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("date-time", formats.getJSONObject("when").getString("format"))
        assertEquals("enum", formats.getJSONObject("kind").getString("format"))
        for (key in listOf("link", "address", "day", "maybe")) assertFalse(key, formats.getJSONObject(key).has("format"))
        assertEquals("STRING", formats.getJSONObject("maybe").getString("type")); assertTrue(formats.getJSONObject("maybe").getBoolean("nullable"))
        for (tool in declarations.tools) assertEquals(tool.name, canonical(tool.parameters), canonical(ToolDeclarations.portable(ToolDeclarations.gemini(tool.parameters))))
    }

    // REVIEW-INT-121 finding 5: Google's function calling reference allows at most 128 declarations; the call's own functions count too.
    @Test fun aSessionNeverDeclaresMoreThan128FunctionsAndCountsTheToolsItLeavesOut() {
        val rows = JSONArray()
        for (i in 0 until 140) rows.put(JSONObject().put("name", "synthetic_tool_$i").put("description", "Synthetic tool $i.")
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())).put("read_only", true).put("tool_id", "e".repeat(64)))
        val context = fixture("context_v3.json").put("tool_declarations", rows).put("tool_declarations_complete", true)
        val sent = setup(context)
        assertTrue("${declared(sent).size} functions", declared(sent).size <= 128)
        val declarations = ToolDeclarations.from(context, GeminiWire.CALL_FUNCTIONS)
        assertEquals(128 - GeminiWire.CALL_FUNCTIONS.size, declarations.tools.size)
        assertEquals(140 - declarations.tools.size, declarations.dropped); assertFalse(declarations.complete)
        assertTrue("the rest stay reachable", instruction(sent).contains(CallInstruction.DISCOVER.trim()))
        assertEquals(declarations.dropped, GeminiWire.declarationsDropped(context, emptySet()))
    }

    // REVIEW-INT-121 finding 6: the plugin takes a missing enabled as on (1.2.0 apps); the model must say which.
    @Test fun theCallBackIsTurnedOnOrOffOnlyWhenTheModelSaysWhich() {
        val required = declared(setup(fixture("context_v3.json"))).getValue("hermes_callback").getJSONObject("parameters").getJSONArray("required")
        assertEquals(listOf("enabled"), (0 until required.length()).map(required::getString))
    }

    @Test fun aLockedCallDeclaresNoneOfTheOwnersToolsButKeepsTheContract() {
        val withheld = withholdPersonal(fixture("context_v3.json"))
        assertFalse(withheld.has("tool_declarations"))
        assertEquals(3, withheld.getInt("call_contract"))
        assertTrue(withheld.getJSONObject("call_capabilities").getBoolean("handover"))
        assertTrue(declared(setup(withheld)).keys.none { it in laneA })
    }

    @Test fun oneDeclarationAndTheWholeListStayWithinTheirBudgets() {
        fun declaration(name: String, description: String, property: String = "") = JSONObject().put("name", name).put("description", description)
            .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject().apply { if (property.isNotEmpty()) put("q", JSONObject().put("type", "string").put("description", property)) }))
        assertEquals(1, ToolDeclarations.from(JSONObject().put("tool_declarations", JSONArray().put(declaration("big_tool", "Big.", "d".repeat(4100)))), emptySet()).dropped)
        val many = JSONArray().apply { repeat(120) { put(declaration("tool_$it", "d".repeat(600))) } }
        val parsed = ToolDeclarations.from(JSONObject().put("tool_declarations", many).put("tool_declarations_complete", true), emptySet())
        assertTrue(parsed.dropped > 0); assertEquals(120, parsed.tools.size + parsed.dropped); assertFalse(parsed.complete)
        val sent = JSONArray(parsed.tools.map { JSONObject().put("name", it.name).put("description", it.description).put("parameters", it.parameters) }).toString()
        assertTrue("${sent.length}", sent.toByteArray().size <= 49_152)
        val all = ToolDeclarations.from(JSONObject().put("tool_declarations", JSONArray().put(declaration("only_tool", "One."))).put("tool_declarations_complete", true), emptySet())
        assertTrue("every tool sent and kept", all.complete)
    }

    @Test fun namesFollowTheProviderSafeRule() {
        for (good in listOf("a", "_x", "t_notes-2", "a".repeat(64))) assertTrue(good, good.matches(ToolDeclarations.NAME))
        for (bad in listOf("", "1a", "a.b", "a:b", "a b", "a".repeat(65), "naïve")) assertFalse(bad, bad.matches(ToolDeclarations.NAME))
    }
}
