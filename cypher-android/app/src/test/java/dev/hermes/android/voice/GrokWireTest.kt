package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrokWireTest {
    @Test fun endpointPinsProviderAndNeverAcceptsEmbeddedCredentials() {
        assertEquals("wss://api.x.ai/v1/realtime",GrokWire.endpoint("wss://api.x.ai/v1/realtime"))
        for(url in listOf("ws://api.x.ai/v1/realtime","wss://api.x.ai.evil/v1/realtime","wss://secret@api.x.ai/v1/realtime","wss://api.x.ai/v1/realtime?token=secret","wss://api.x.ai/v1/realtime#secret","wss://api.x.ai:444/v1/realtime","wss://api.x.ai/v1/other")) assertTrue(url,runCatching { GrokWire.endpoint(url) }.isFailure)
        val key="xai-fixture-secret"
        val request=GrokWire.request(key,"grok-voice-think-fast-2.0")
        assertEquals("Bearer $key",request.rawRequest().header("Authorization"));assertNull(request.rawRequest().header("Cookie"))
        assertEquals("grok-voice-think-fast-2.0",request.rawRequest().url.queryParameter("model"));assertFalse(request.rawRequest().url.toString().contains(key))
        assertFalse(request.toString().contains(key))
    }
    @Test fun setupReusesExactConsentInstructionsAndToolsWithCompatiblePcm() {
        val setup=GrokWire().outbound(GeminiWire.setup("grok-voice-latest","eve",null)).single().getJSONObject("session")
        assertEquals("eve",setup.getString("voice"));assertTrue(setup.getString("instructions").contains("never approve your own request"))
        assertTrue(setup.getString("instructions").contains("no camera or screen input"))
        assertEquals(16000,setup.getJSONObject("audio").getJSONObject("input").getJSONObject("format").getInt("rate"))
        assertEquals(24000,setup.getJSONObject("audio").getJSONObject("output").getJSONObject("format").getInt("rate"))
        val functions=setup.getJSONArray("tools")
        for(i in 0 until functions.length()) { val tool=functions.getJSONObject(i);assertEquals("function",tool.getString("type"));assertFalse(tool.has("behavior"));assertEquals("object",tool.getJSONObject("parameters").getString("type")) }
        assertEquals("input_audio_buffer.append",GrokWire().outbound(GeminiWire.audio("AA==")).single().getString("type"))
    }
    @Test fun mediaVadAndFunctionArgumentsNormalizeToSharedLifecycle() {
        val wire=GrokWire()
        assertNull(wire.inbound(JSONObject().put("type","session.created")))
        assertTrue(wire.inbound(JSONObject().put("type","session.updated"))!!.has("setupComplete"))
        assertTrue(wire.inbound(JSONObject().put("type","input_audio_buffer.speech_started"))!!.getJSONObject("serverContent").getBoolean("interrupted"))
        val media=wire.inbound(JSONObject().put("type","response.output_audio.delta").put("delta","AAA="))!!.getJSONObject("serverContent").getJSONObject("modelTurn").getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/pcm;rate=24000",media.getString("mimeType"))
        val tool=wire.inbound(function())!!.getJSONObject("toolCall").getJSONArray("functionCalls").getJSONObject(0)
        assertEquals("call-1",tool.getString("id"));assertEquals("invoke_tool",tool.getString("name"));assertEquals("opaque",tool.getJSONObject("args").getString("tool_id"))
        assertTrue(runCatching { wire.inbound(JSONObject().put("type","error").put("error",JSONObject().put("message","private token"))) }.exceptionOrNull()!!.message!!.let { !it.contains("private token") })
    }
    @Test fun assistantTranscriptDoneDoesNotRepeatTextAlreadyStreamedForTheItem() {
        val wire=GrokWire()
        val delta=JSONObject().put("type","response.output_audio_transcript.delta").put("item_id","item-1").put("delta","Hello ")
        val done=JSONObject().put("type","response.output_audio_transcript.done").put("item_id","item-1").put("transcript","Hello world")
        assertEquals("Hello ",wire.inbound(delta)!!.getJSONObject("serverContent").getJSONObject("outputTranscription").getString("text"))
        assertNull("The cumulative done snapshot must not be appended after its deltas",wire.inbound(done))

        val snapshot=JSONObject().put("type","response.output_audio_transcript.done").put("item_id","item-2").put("transcript","A complete reply")
        assertEquals("A complete reply",wire.inbound(snapshot)!!.getJSONObject("serverContent").getJSONObject("outputTranscription").getString("text"))
    }
    @Test fun pendingAndFinalReceiptsProduceOneFunctionOutputAndDeferredSpokenUpdate() {
        val wire=GrokWire();wire.inbound(function());wire.inbound(JSONObject().put("type","response.created"))
        val pending=GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status","needs_approval"))
        val first=wire.outbound(pending)
        assertEquals(1,first.size);assertEquals("function_call_output",first.single().getJSONObject("item").getString("type"))
        assertTrue(wire.outbound(pending).isEmpty());assertNull(wire.continueResponse())
        wire.inbound(JSONObject().put("type","response.done"));assertEquals("response.create",wire.continueResponse()!!.getString("type"))
        val final=GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status","completed").put("result","saved"))
        val update=wire.outbound(final)
        assertEquals("message",update.single().getJSONObject("item").getString("type"));assertTrue(wire.outbound(final).isEmpty())
        wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject().put("status","cancelled")))
        assertNotNull(wire.continueResponse());assertNull(wire.continueResponse())
    }
    @Test fun reconnectReceiptUsesVerifiedUpdateWithoutOrphanFunctionId() {
        val rows=GrokWire().outbound(GeminiWire.tool("previous-call","invoke_tool",JSONObject().put("status","completed")))
        assertEquals("message",rows.first().getJSONObject("item").getString("type"));assertEquals("response.create",rows.last().getString("type"))
    }
    @Test fun propertyNamedTypeRemainsAnObjectAndMalformedArgumentsNeverExecute() {
        val setup=GeminiWire.setup("grok-voice-latest","eve",null)
        val parameters=setup.getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters")
        parameters.getJSONObject("properties").put("type",JSONObject().put("type","STRING"))
        val converted=GrokWire().outbound(setup).single().getJSONObject("session").getJSONArray("tools").getJSONObject(0)
        assertEquals("string",converted.getJSONObject("parameters").getJSONObject("properties").getJSONObject("type").getString("type"))
        assertTrue(runCatching { GrokWire().inbound(function().put("arguments","[1]")) }.isFailure)
    }
    @Test fun runningReceiptsDoNotCreateRepeatedSpokenAcknowledgements() {
        val wire=GrokWire();wire.inbound(function())
        for(status in listOf("ready","running")) {
            val frames=wire.outbound(GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status",status)))
            assertTrue(frames.none { it.optString("type")=="response.create" })
            wire.inbound(JSONObject().put("type","response.done"));assertNull(wire.continueResponse())
        }
        val terminal=wire.outbound(GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status","completed").put("result","verified")))
        assertEquals("response.create",terminal.last().getString("type"))
    }
    @Test fun hundredsOfReadToolsDoNotExhaustCallLifetimeOrReplayRecentResponses() {
        val wire=GrokWire()
        for(index in 0 until 400) {
            val id="read-$index"
            val call=function().put("call_id",id).put("name","hermes_memory").put("arguments",JSONObject().put("query","recent history").toString())
            assertNotNull(wire.inbound(call))
            val receipt=GeminiWire.tool(id,"hermes_memory",JSONObject().put("summary","Verified result $index"))
            val output=wire.outbound(receipt)
            assertEquals("function_call_output",output.first().getJSONObject("item").getString("type"))
            assertTrue("Exact receipt replay must be silent",wire.outbound(receipt).isEmpty())
            wire.inbound(JSONObject().put("type","response.done"))
        }
        val calls=GrokWire::class.java.getDeclaredField("calls").apply { isAccessible=true }.get(wire) as Map<*,*>
        val receipts=GrokWire::class.java.getDeclaredField("delivered").apply { isAccessible=true }.get(wire) as Map<*,*>
        assertTrue(calls.isEmpty());assertEquals(128,receipts.size)
    }
    @Test fun pendingUncertainMutationSurvivesReadCacheChurnAndItsTerminalReceiptIsNotReexecuted() {
        val wire=GrokWire();wire.inbound(function())
        val uncertain=GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status","uncertain").put("request_id","original"))
        assertEquals("function_call_output",wire.outbound(uncertain).first().getJSONObject("item").getString("type"))
        for(index in 0 until 300) {
            val id="lookup-$index";wire.inbound(function().put("call_id",id).put("name","tool_status").put("arguments",JSONObject().put("request_id","original").toString()))
            wire.outbound(GeminiWire.tool(id,"tool_status",JSONObject().put("status","needs_approval").put("request_id","original")))
        }
        assertTrue("An uncertain mutation receipt must remain replay-protected",wire.outbound(uncertain).isEmpty())
        val final=GeminiWire.tool("call-1","invoke_tool",JSONObject().put("status","completed").put("request_id","original"))
        val update=wire.outbound(final)
        assertEquals("message",update.first().getJSONObject("item").getString("type"))
        assertTrue(wire.outbound(final).isEmpty())
        val pending=GrokWire::class.java.getDeclaredField("pendingDelivered").apply { isAccessible=true }.get(wire) as Map<*,*>
        assertTrue(pending.isEmpty())
    }
    private fun function()=JSONObject().put("type","response.function_call_arguments.done").put("call_id","call-1").put("name","invoke_tool").put("arguments",JSONObject().put("tool_id","opaque").toString())
    @Test fun admittedVoiceAndPersonalityReachGrokSessionInstructions() {
        val setup=GrokWire().outbound(GeminiWire.setup("grok-voice-latest","ara",null,JSONObject().put("personality_instruction","Speak warmly and patiently."))).single().getJSONObject("session")
        assertEquals("ara",setup.getString("voice"))
        assertTrue(setup.getString("instructions").contains("Speak warmly and patiently."))
        assertTrue(setup.getString("instructions").contains("never approve your own request"))
    }

}
