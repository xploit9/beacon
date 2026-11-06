package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GrokWireTest {
    @Test fun typedInputWaitsForTheResponseBoundaryWithoutBeingConsumed() {
        val wire=GrokWire()
        val input=JSONObject().put("clientContent",JSONObject().put("turns",JSONArray().put(JSONObject().put("role","user")
            .put("parts",JSONArray().put(JSONObject().put("text","Synthetic typed request"))))).put("turnComplete",true))
        wire.inbound(JSONObject().put("type","response.created"))
        assertNull(wire.outboundTyped(input))
        wire.inbound(JSONObject().put("type","response.done"))
        assertNull("A rejected typed input did not queue a hidden response",wire.continueResponse())
        val sent=wire.outboundTyped(input)!!
        assertEquals(listOf("conversation.item.create","response.create"),sent.map { it.getString("type") })
        assertEquals("Synthetic typed request",sent.first().getJSONObject("item").getJSONArray("content").getJSONObject(0).getString("text"))
        assertNull(wire.outboundTyped(input))
    }
    private fun input(type:String,item:String)=JSONObject().put("type",type).put("item_id",item)
    private fun transcription(item:String,text:String,status:String?="completed",index:Int=0):JSONObject =
        input("conversation.item.input_audio_transcription.completed",item).put("content_index",index).put("transcript",text)
            .apply { if(status!=null) put("status",status) }

    @Test fun openAiBridgeAcceptsItsOwnFinalWithoutGrokStatusOrCommit() {
        val wire=GrokWire("OpenAI")
        val result=wire.inbound(transcription("openai-item","OpenAI request",status=null))!!
            .getJSONObject("serverContent").getJSONObject("inputTranscription")
        assertEquals("OpenAI request",result.getString("text"));assertTrue(result.getBoolean("final"))
    }

    @Test fun grokInputWordsReachTheCallCardWithoutRelayBookkeeping() {
        val wire=GrokWire()
        val first=wire.inbound(transcription("grok-1","What time is it",status="in_progress"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription")
        assertEquals("What time is it",first.getString("text"));assertEquals("grok-1",first.getString("item_id"))
        assertNull("An unchanged snapshot is not shown twice",wire.inbound(transcription("grok-1","What time is it")))
        // A model answer starts on its own: nothing waits for a committed item or a status.
        val speech=wire.inbound(JSONObject().put("type","input_audio_buffer.speech_started").put("item_id","grok-2"))!!.getJSONObject("serverContent")
        assertTrue(speech.getBoolean("interrupted"))
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
        assertTrue(setup.getString("instructions").contains("You answer the owner yourself, in your own voice"))
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

    private fun toolItem(item:String="item-1",id:String="call-1",name:String="discover_tools",arguments:String="{\"query\":\"mail\"}") =
        JSONObject().put("id",item).put("type","function_call").put("status","completed").put("call_id",id).put("name",name).put("arguments",arguments)
    private fun itemEvent(type:String,response:String,item:JSONObject,index:Int=0) =
        JSONObject().put("type",type).put("response_id",response).put("output_index",index).put("item",item)
    private fun done(response:String,status:String="completed",items:JSONArray=JSONArray()) =
        JSONObject().put("type","response.done").put("response",JSONObject().put("id",response).put("status",status).put("output",items))
    private fun calls(frame:JSONObject?) = frame?.optJSONObject("toolCall")?.optJSONArray("functionCalls") ?: JSONArray()
    private fun startResponse(wire:GrokWire,id:String) { wire.inbound(JSONObject().put("type","response.created").put("response",JSONObject().put("id",id))) }

    // Defensive shapes: the current xAI and OpenAI Realtime examples include both fields; neither proves what the owner's call sent.
    @Test fun partialArgumentDoneWaitsForACompleteItemAndDispatchesExactlyOnce() {
        for(missing in listOf(listOf("name"),listOf("call_id"),listOf("name","call_id"))) {
            val wire=GrokWire();startResponse(wire,"response-1")
            wire.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress").put("arguments","")))
            val partial=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
                .put("item_id","item-1").put("output_index",0).put("call_id","call-1").put("name","discover_tools")
                .put("arguments","{\"query\":\"mail\"}")
            missing.forEach { partial.remove(it) }
            assertEquals("A partial envelope cannot authorize a tool before response completion",0,calls(wire.inbound(partial)).length())
            val completed=done("response-1",items=JSONArray().put(toolItem()))
            val found=calls(wire.inbound(completed))
            assertEquals(1,found.length());assertEquals("call-1",found.getJSONObject(0).getString("id"))
            assertEquals("discover_tools",found.getJSONObject(0).getString("name"));assertEquals("mail",found.getJSONObject(0).getJSONObject("args").getString("query"))
            assertEquals(0,calls(wire.inbound(completed)).length())
            val answer=wire.outbound(GeminiWire.tool("call-1","discover_tools",JSONObject().put("tools",JSONArray())))
            assertEquals("function_call_output",answer.first().getJSONObject("item").getString("type"))
            assertEquals("call-1",answer.first().getJSONObject("item").getString("call_id"))
        }
    }

    @Test fun completedItemIsRetainedWhenTheCompletedResponseHasNoOutputItems() {
        val wire=GrokWire();startResponse(wire,"response-1")
        wire.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress").put("arguments","")))
        assertEquals(0,calls(wire.inbound(JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
            .put("item_id","item-1").put("output_index",0).put("arguments","{\"query\":\"mail\"}"))).length())
        assertEquals(0,calls(wire.inbound(itemEvent("response.output_item.done","response-1",toolItem()))).length())
        val found=calls(wire.inbound(done("response-1")))
        assertEquals(1,found.length());assertEquals("call-1",found.getJSONObject(0).getString("id"))
        assertEquals("mail",found.getJSONObject(0).getJSONObject("args").getString("query"))
        assertEquals(0,calls(wire.inbound(done("response-1",items=JSONArray().put(toolItem())))).length())
    }

    @Test fun cancelledPartialResponseDoesNotDispatchOrLeakIntoTheNextResponse() {
        val wire=GrokWire();startResponse(wire,"response-1")
        wire.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress")))
        assertEquals(0,calls(wire.inbound(JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
            .put("item_id","item-1").put("arguments","{\"query\":\"mail\"}"))).length())
        assertEquals(0,calls(wire.inbound(itemEvent("response.output_item.done","response-1",toolItem()))).length())
        val cancelled=wire.inbound(done("response-1","cancelled"))
        assertEquals(0,calls(cancelled).length());assertTrue(cancelled!!.getJSONObject("serverContent").getBoolean("interrupted"))
        startResponse(wire,"response-2")
        assertEquals(0,calls(wire.inbound(done("response-2"))).length())
        startResponse(wire,"response-3")
        val fresh=calls(wire.inbound(done("response-3",items=JSONArray().put(toolItem("item-2","call-2",arguments="{\"query\":\"files\"}")))))
        assertEquals(1,fresh.length());assertEquals("call-2",fresh.getJSONObject(0).getString("id"))
        assertEquals("files",fresh.getJSONObject(0).getJSONObject("args").getString("query"))
    }

    @Test fun mismatchedResponseOrCallIdentityCannotBorrowAnEarlierItem() {
        val wire=GrokWire();startResponse(wire,"response-1")
        wire.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress")))
        val wrongResponse=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-2")
            .put("item_id","item-1").put("arguments","{\"query\":\"private\"}")
        assertEquals(0,calls(wire.inbound(wrongResponse)).length())
        val wrongCall=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
            .put("item_id","item-1").put("call_id","call-2").put("name","discover_tools").put("arguments","{\"query\":\"private\"}")
        assertEquals(0,calls(wire.inbound(wrongCall)).length())
        assertEquals(0,calls(wire.inbound(itemEvent("response.output_item.done","response-2",toolItem()))).length())
        assertEquals(0,calls(wire.inbound(done("response-1"))).length())
    }

    @Test fun malformedPartialArgumentsNeverBecomeAnExecutableCall() {
        for(bad in listOf("{","[1]")) {
            val wire=GrokWire();startResponse(wire,"response-1")
            wire.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress")))
            val partial=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1").put("item_id","item-1").put("arguments",bad)
            assertEquals(0,calls(wire.inbound(partial)).length())
            assertEquals(0,calls(wire.inbound(itemEvent("response.output_item.done","response-1",toolItem(arguments=bad)))).length())
            assertEquals(0,calls(wire.inbound(done("response-1"))).length())
            startResponse(wire,"response-2")
            assertEquals(1,calls(wire.inbound(done("response-2",items=JSONArray().put(toolItem("item-2","call-2"))))).length())
        }
    }

    @Test fun parallelPartialCallsWaitForBothResultsBeforeContinuing() {
        val wire=GrokWire();startResponse(wire,"response-1")
        listOf(Triple("item-1","call-1","mail"),Triple("item-2","call-2","files")).forEachIndexed { index,(item,id,query) ->
            wire.inbound(itemEvent("response.output_item.added","response-1",toolItem(item,id).put("status","in_progress"),index))
            val partial=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
                .put("item_id",item).put("output_index",index).put("arguments",JSONObject().put("query",query).toString())
            assertEquals(0,calls(wire.inbound(partial)).length())
            assertEquals(0,calls(wire.inbound(itemEvent("response.output_item.done","response-1",toolItem(item,id,arguments=JSONObject().put("query",query).toString()),index))).length())
        }
        val found=calls(wire.inbound(done("response-1")))
        assertEquals(2,found.length())
        assertEquals(setOf("call-1","call-2"),(0 until found.length()).map { found.getJSONObject(it).getString("id") }.toSet())
        val first=wire.outbound(GeminiWire.tool("call-1","discover_tools",JSONObject().put("tools",JSONArray())))
        assertEquals(listOf("conversation.item.create"),first.map { it.getString("type") });assertNull(wire.continueResponse())
        val second=wire.outbound(GeminiWire.tool("call-2","discover_tools",JSONObject().put("tools",JSONArray())))
        assertEquals(listOf("conversation.item.create","response.create"),second.map { it.getString("type") })
    }

    @Test fun replacementProviderSessionCannotInheritAnUnfinishedPartialCall() {
        val old=GrokWire();startResponse(old,"response-1")
        old.inbound(itemEvent("response.output_item.added","response-1",toolItem().put("status","in_progress")))
        val partial=JSONObject().put("type","response.function_call_arguments.done").put("response_id","response-1")
            .put("item_id","item-1").put("arguments","{\"query\":\"mail\"}")
        assertEquals(0,calls(old.inbound(partial)).length())
        assertEquals(0,calls(old.inbound(itemEvent("response.output_item.done","response-1",toolItem()))).length())
        val replacement=GrokWire();startResponse(replacement,"response-1")
        assertEquals(0,calls(replacement.inbound(done("response-1"))).length())
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

    // xAI's Voice Agent API (Grok Voice 3.0, 14 Oct 2026): with parallel calls, response.create waits until every function_call_output
    // of that response is in, so the reply is built from all of the results.
    @Test fun parallelCallsAreAnsweredOnceEveryResultIsIn() {
        val wire=GrokWire()
        fun call(id:String)=JSONObject().put("type","response.function_call_arguments.done").put("call_id",id).put("name","invoke_tool").put("arguments",JSONObject().put("tool_id","opaque").toString())
        wire.inbound(call("call-a"));wire.inbound(call("call-b"))
        wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject().put("status","completed")))
        val first=wire.outbound(GeminiWire.tool("call-a","invoke_tool",JSONObject().put("status","completed").put("result","one")))
        assertEquals(listOf("conversation.item.create"),first.map { it.getString("type") })
        assertNull("call-b has no result yet",wire.continueResponse())
        val second=wire.outbound(GeminiWire.tool("call-b","invoke_tool",JSONObject().put("status","completed").put("result","two")))
        assertEquals(listOf("conversation.item.create","response.create"),second.map { it.getString("type") })
    }

    @Test fun theOwnerSpeakingNeverLeavesTheReplyWaitingOnAnOlderCall() {
        val wire=GrokWire()
        fun call(id:String)=JSONObject().put("type","response.function_call_arguments.done").put("call_id",id).put("name","invoke_tool").put("arguments",JSONObject().put("tool_id","opaque").toString())
        wire.inbound(call("call-a"));wire.inbound(call("call-b"))
        wire.inbound(JSONObject().put("type","response.done").put("response",JSONObject().put("status","completed")))
        wire.inbound(JSONObject().put("type","input_audio_buffer.speech_started"))
        val rows=wire.outbound(GeminiWire.tool("call-a","invoke_tool",JSONObject().put("status","completed").put("result","one")))
        assertEquals("response.create",rows.last().getString("type"))
    }

    @Test fun transcriptionModelsAreNeverOfferedAsVoices() {
        val listed=JSONObject().put("data",org.json.JSONArray()
            .put(JSONObject().put("id","grok-voice-3.0").put("aliases",org.json.JSONArray().put("grok-voice-latest")))
            .put(JSONObject().put("id","grok-voice-transcribe-2.0")))
        assertEquals(listOf("grok-voice-3.0","grok-voice-latest"),GrokWire.models(listed))
    }
}
