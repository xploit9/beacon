package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrokWireTest {
    private fun input(type:String,item:String)=JSONObject().put("type",type).put("item_id",item)
    private fun transcription(item:String,text:String,status:String?="completed",index:Int=0):JSONObject =
        input("conversation.item.input_audio_transcription.completed",item).put("content_index",index).put("transcript",text)
            .apply { if(status!=null) put("status",status) }

    private fun assertNotFinal(frame:JSONObject?) {
        assertFalse(frame?.optJSONObject("serverContent")?.optJSONObject("inputTranscription")?.optBoolean("final") == true)
    }

    @Test fun inProgressCompletionsWaitForTheMatchingCommittedFinal() {
        val wire=GrokWire();val id="owner-1"
        wire.inbound(input("input_audio_buffer.speech_started",id))
        for(text in listOf("Without","Without using any tools","Without using any tools what is two plus three?")) {
            assertNotFinal(wire.inbound(input("conversation.item.input_audio_transcription.updated",id).put("transcript",text).put("content_index",0)))
            assertNotFinal(wire.inbound(transcription(id,text,"in_progress")))
        }
        wire.inbound(input("input_audio_buffer.speech_stopped",id))
        val full="Without using any tools what is two plus three?"
        assertNotFinal(wire.inbound(transcription(id,full,"in_progress")))
        assertNull(wire.inbound(input("input_audio_buffer.committed",id)))
        val committed=wire.inbound(transcription(id,full))!!.getJSONObject("serverContent").getJSONObject("inputTranscription")
        assertEquals(full,committed.getString("text"));assertEquals(id,committed.getString("item_id"))
        assertTrue(committed.getBoolean("final"))
        assertNull("A duplicate completed item must not be emitted twice",wire.inbound(transcription(id,full)))
    }

    @Test fun completedBeforeCommitDoesNotPoisonTheLaterIdenticalFinal() {
        val wire=GrokWire();val id="owner-2"
        wire.inbound(input("input_audio_buffer.speech_started",id))
        assertNotFinal(wire.inbound(transcription(id,"Full request")))
        wire.inbound(input("input_audio_buffer.committed",id))
        assertEquals("Full request",wire.inbound(transcription(id,"Full request"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription").getString("text"))
    }

    @Test fun newerSpeechAndForeignCommitsCannotFinishTheAbandonedItem() {
        val wire=GrokWire()
        wire.inbound(input("input_audio_buffer.speech_started","one"))
        wire.inbound(input("input_audio_buffer.speech_started","two"))
        wire.inbound(input("input_audio_buffer.committed","one"))
        assertNull("A stale commit cannot make an abandoned item actionable",wire.inbound(transcription("one","stale partial")))
        wire.inbound(input("input_audio_buffer.committed","two"))
        assertEquals("actual request",wire.inbound(transcription("two","actual request"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription").getString("text"))
    }

    @Test fun aCommittedItemMayFinishAfterTheNextItemStarts() {
        val wire=GrokWire()
        wire.inbound(input("input_audio_buffer.speech_started","one"))
        wire.inbound(input("input_audio_buffer.committed","one"))
        wire.inbound(input("input_audio_buffer.speech_started","two"))
        assertEquals("first request",wire.inbound(transcription("one","first request"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription").getString("text"))
        wire.inbound(input("input_audio_buffer.committed","two"))
        assertEquals("second request",wire.inbound(transcription("two","second request"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription").getString("text"))
    }

    @Test fun clearAndReconnectCannotReviveThePriorItem() {
        val before=GrokWire()
        before.inbound(input("input_audio_buffer.speech_started","one"))
        before.inbound(JSONObject().put("type","input_audio_buffer.cleared"))
        assertNull("A cleared pending item cannot later be admitted",before.inbound(transcription("one","old")))
        before.inbound(input("input_audio_buffer.speech_started","one"))
        before.inbound(input("input_audio_buffer.committed","one"))
        assertNull("A stale start cannot reactivate a cleared identity",before.inbound(transcription("one","old")))
        val after=GrokWire()
        after.inbound(input("input_audio_buffer.committed","one"))
        assertNull("A new socket cannot inherit another socket's item",after.inbound(transcription("one","old")))
        val outbound=GrokWire()
        outbound.inbound(input("input_audio_buffer.speech_started","two"))
        assertEquals("input_audio_buffer.clear",outbound.outbound(GeminiWire.endAudio()).single().getString("type"))
        assertNull("A client clear invalidates pending input before its acknowledgement",outbound.inbound(transcription("two","old")))
    }

    @Test fun clearDiscardsOnlyUncommittedAudioWhileEarlierCommitCanFinish() {
        for(clientClear in listOf(false,true)) {
            val wire=GrokWire()
            wire.inbound(input("input_audio_buffer.speech_started","committed"))
            wire.inbound(input("input_audio_buffer.committed","committed"))
            wire.inbound(input("input_audio_buffer.speech_started","still-speaking"))
            if(clientClear) wire.outbound(GeminiWire.endAudio()) else wire.inbound(JSONObject().put("type","input_audio_buffer.cleared"))
            val finished=wire.inbound(transcription("committed","finished request"))
                ?.optJSONObject("serverContent")?.optJSONObject("inputTranscription")
            assertNotNull("Clear must preserve the already committed item",finished)
            assertTrue(finished!!.getBoolean("final"))
            assertEquals("finished request",finished.getString("text"))
            assertNull("The retained item may finish only once",wire.inbound(transcription("committed","finished request")))
            wire.inbound(input("input_audio_buffer.committed","still-speaking"))
            assertNull("The uncommitted cleared item cannot become actionable",wire.inbound(transcription("still-speaking","partial")))
        }
    }

    @Test fun malformedNewSpeechCannotFinishThePreviousUncommittedItem() {
        val wire=GrokWire()
        wire.inbound(input("input_audio_buffer.speech_started","one"))
        wire.inbound(JSONObject().put("type","input_audio_buffer.speech_started"))
        wire.inbound(input("input_audio_buffer.committed","one"))
        assertNull(wire.inbound(transcription("one","old partial")))
    }

    @Test fun missingOrUnknownFinalStatusCannotBeTreatedAsACompletedUtterance() {
        for(status in listOf(null,"mystery","in_progress")) {
            val wire=GrokWire();wire.inbound(input("input_audio_buffer.speech_started","one"));wire.inbound(input("input_audio_buffer.committed","one"))
            assertNotFinal(runCatching { wire.inbound(transcription("one","do work",status)) }.getOrNull())
        }
    }

    @Test fun oneCommittedAudioItemCannotProduceMultipleNativeTurns() {
        val wire=GrokWire();val id="same"
        wire.inbound(input("input_audio_buffer.speech_started",id));wire.inbound(input("input_audio_buffer.committed",id))
        assertNull("A nonzero content part cannot stand in for an entire audio item",wire.inbound(transcription(id,"part only",index=1)))
        assertNotNull(wire.inbound(transcription(id,"whole",index=0)))
        assertNull(wire.inbound(transcription(id,"another part",index=1)))
        wire.inbound(input("input_audio_buffer.speech_started",id));wire.inbound(input("input_audio_buffer.committed",id))
        assertNull("A changed snapshot must not resubmit an already handled item",wire.inbound(transcription(id,"different")))
    }

    @Test fun outstandingCommittedItemLimitFailsClosedWithoutEvictingAnEarlierItem() {
        val wire=GrokWire()
        repeat(512) { i ->
            val id="pending-$i";wire.inbound(input("input_audio_buffer.speech_started",id));wire.inbound(input("input_audio_buffer.committed",id))
        }
        val overflow=runCatching {
            wire.inbound(input("input_audio_buffer.speech_started","overflow"))
            wire.inbound(input("input_audio_buffer.committed","overflow"))
        }.exceptionOrNull()
        assertTrue("Outstanding committed items must be bounded without dropping old identities",overflow is VoiceInputLimit)
    }

    @Test fun openAiBridgeAcceptsItsOwnFinalWithoutGrokStatusOrCommit() {
        val wire=GrokWire("OpenAI")
        val result=wire.inbound(transcription("openai-item","OpenAI request",status=null))!!
            .getJSONObject("serverContent").getJSONObject("inputTranscription")
        assertEquals("OpenAI request",result.getString("text"));assertTrue(result.getBoolean("final"))
    }

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
        assertTrue(setup.getString("instructions").contains("Every owner request goes to hermes_work"))
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
    @Test fun incompleteResponsesSkipCallsAndMalformedCompletedItemsDoNotPoisonCallIds() {
        val wire=GrokWire()
        val malformed=JSONObject().put("type","function_call").put("call_id","call-1").put("name","invoke_tool").put("arguments","{")
        val incomplete=wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject()
            .put("status","cancelled").put("output",JSONArray().put(malformed))))
        assertTrue(incomplete!!.getJSONObject("serverContent").getBoolean("interrupted"))
        assertFalse(incomplete.has("toolCall"))

        val malformedComplete=wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject()
            .put("status","completed").put("output",JSONArray().put(malformed))))
        assertNotNull(malformedComplete)
        assertFalse(malformedComplete!!.has("toolCall"))

        val valid=malformed.put("arguments",JSONObject().put("tool_id","opaque").toString())
        val recovered=wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject()
            .put("status","completed").put("output",JSONArray().put(valid))))
        assertEquals("call-1",recovered!!.getJSONObject("toolCall").getJSONArray("functionCalls").getJSONObject(0).getString("id"))
    }
    @Test fun malformedFunctionArgumentDeltasAreSkippedAndAValidItemStillCompletes() {
        val wire=GrokWire()
        assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta")))
        assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta").put("item_id","item-1")))
        assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta").put("item_id",1).put("delta","ignored")))
        assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta").put("item_id","item-1").put("delta","{")))
        assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta").put("item_id","item-1").put("delta","\"tool_id\":\"opaque\"}")))

        val call=wire.inbound(JSONObject().put("type","response.function_call_arguments.done").put("item_id","item-1")
            .put("call_id","call-1").put("name","invoke_tool"))!!.getJSONObject("toolCall").getJSONArray("functionCalls").getJSONObject(0)
        assertEquals("opaque",call.getJSONObject("args").getString("tool_id"))
    }
    @Test fun whitespaceArgumentFragmentsAreKeptInTheCallArguments() {
        val wire=GrokWire()
        for(part in listOf("{\"query\":\"hello"," ","world\"}")) assertNull(wire.inbound(JSONObject().put("type","response.function_call_arguments.delta").put("item_id","item-1").put("delta",part)))
        val call=wire.inbound(JSONObject().put("type","response.function_call_arguments.done").put("item_id","item-1")
            .put("call_id","call-1").put("name","invoke_tool"))!!.getJSONObject("toolCall").getJSONArray("functionCalls").getJSONObject(0)
        assertEquals("hello world",call.getJSONObject("args").getString("query"))
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
