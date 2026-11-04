package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeVoiceReadTest {
    @Test fun resumedSetupAppliesFreshNativeContextWithoutLosingPendingSessionIdentity() {
        val fresh=JSONObject().put("profile","original").put("system_instruction","Fresh native preferences")
            .put("capabilities",JSONObject().put("skills",true).put("profile_context",true))
        val setup=GeminiWire.setup("model","voice","existing-session-handle",fresh).getJSONObject("setup")
        assertEquals("existing-session-handle",setup.getJSONObject("sessionResumption").getString("handle"))
        assertTrue(setup.getJSONObject("systemInstruction").toString().contains("Fresh native preferences"))
        assertTrue(setup.getJSONObject("systemInstruction").toString().contains("original"))
        assertTrue(setup.getJSONArray("tools").toString().contains("hermes_work"))
        assertTrue(setup.getJSONArray("tools").toString().contains("hermes_skills"))
    }
    @Test fun nativeReadsRejectProfileOverridesAndKeepContextQueryClientOwned() {
        val read=GeminiWire.nativeRead("hermes_context",JSONObject())
        assertEquals("profile_context",read.capability);assertEquals("profile-context",read.route)
        assertEquals(setOf("query"),read.body.keys().asSequence().toSet());assertEquals("current profile",read.body.getString("query"))
        for(args in listOf(JSONObject().put("profile","other"),JSONObject().put("query","other"))) {
            assertTrue(runCatching { GeminiWire.nativeRead("hermes_context",args) }.isFailure)
            assertTrue(runCatching { GeminiWire.nativeRead("hermes_skills",JSONObject().put("profile","other")) }.isFailure)
        }
    }
    @Test fun skillsAcceptOnlyBoundedCatalogueOrNamedDocumentArguments() {
        assertEquals(0,GeminiWire.nativeRead("hermes_skills",JSONObject()).body.length())
        val read=GeminiWire.nativeRead("hermes_skills",JSONObject().put("query","email").put("name","mail-flow"))
        assertEquals("skills",read.capability);assertEquals("skills",read.route);assertEquals("mail-flow",read.body.getString("name"))
        for(args in listOf(JSONObject().put("name",""),JSONObject().put("name","x".repeat(161)),JSONObject().put("query","x".repeat(1025)),JSONObject().put("query",2),JSONObject().put("approve",true)))
            assertTrue(args.toString(),runCatching { GeminiWire.nativeRead("hermes_skills",args) }.isFailure)
        assertTrue(runCatching { GeminiWire.nativeRead("invented_read",JSONObject()) }.isFailure)
    }
    @Test fun bothProvidersKeepTheSameDirectToolSetWhenCapabilitiesChange() {
        val context=JSONObject().put("profile","original").put("capabilities",JSONObject().put("skills",true).put("profile_context",true))
        val gemini=GeminiWire.setup("model","",null,context).getJSONObject("setup")
        fun names(rows:JSONArray)=(0 until rows.length()).associate { rows.getJSONObject(it).getString("name") to rows.getJSONObject(it) }
        val declared=names(gemini.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations"))
        assertEquals(setOf("text","callback"),declared.getValue("hermes_work").getJSONObject("parameters").getJSONObject("properties").keys().asSequence().toSet())
        val grok=names(GrokWire.setup(gemini,"eve").getJSONObject("session").getJSONArray("tools"))
        assertEquals("object",grok.getValue("hermes_work").getJSONObject("parameters").getString("type"))
        assertEquals(declared.keys,grok.keys)
        assertTrue(grok.keys.containsAll(listOf("discover_tools","invoke_tool","tool_status","hermes_work","hermes_callback","hermes_cancel","get_visual_state","hermes_skills","hermes_context")))
        val instruction=gemini.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        for(text in listOf("You answer the owner yourself, in your own voice","Use hermes_work only for long, multi-step work","never approve your own request")) assertTrue(text,instruction.contains(text))
        val unavailable=names(GeminiWire.setup("model","",null,JSONObject().put("capabilities",JSONObject().put("skills",false).put("profile_context",false))).getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations"))
        assertFalse("hermes_skills" in unavailable);assertFalse("hermes_context" in unavailable)
    }
}
