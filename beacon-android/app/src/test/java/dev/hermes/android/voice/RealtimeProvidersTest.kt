package dev.hermes.android.voice

import dev.hermes.android.voiceKeySetupPage
import dev.hermes.android.data.voiceProviderSupported
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RealtimeProvidersTest {
    @Test fun malformedProviderKeyIsReportedAsRejected() = runBlocking {
        listOf(
            "gemini" to "models/gemini-3.8-live",
            "grok" to "grok-voice-latest",
            "openai" to "gpt-realtime-2.1",
        ).forEach { (provider, model) ->
            assertEquals(VoiceProviderTestResult.KeyRejected, testVoiceProvider(provider, "bad key", model))
        }
    }

    @Test fun providerRequestsKeepKeysInHeadersAndUseSelectedModels() {
        val googleKey = "google-fixture-secret"
        val gemini = GeminiWire.request(googleKey)
        val geminiRequest=gemini.rawRequest()
        assertEquals("https",geminiRequest.url.scheme)
        assertEquals("generativelanguage.googleapis.com",geminiRequest.url.host)
        assertEquals("/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",geminiRequest.url.encodedPath)
        assertEquals(googleKey, geminiRequest.header("x-goog-api-key"))
        assertNull(geminiRequest.url.queryParameter("key"))
        assertFalse(gemini.toString().contains(googleKey))
        assertFalse(runCatching { GeminiWire.request(googleKey+"\n") }.exceptionOrNull()?.message.orEmpty().contains(googleKey))
        for(request in listOf(GeminiWire.modelRequest(googleKey),GeminiWire.voiceRequest(googleKey))) {
            assertEquals(googleKey,request.rawRequest().header("x-goog-api-key"))
            assertFalse(request.rawRequest().url.toString().contains(googleKey))
            assertFalse(request.toString().contains(googleKey))
        }

        val xaiKey = "xai-fixture-secret"
        val grok = GrokWire.request(xaiKey, "grok-voice-think-fast-2.0")
        val grokRequest=grok.rawRequest()
        assertEquals("https",grokRequest.url.scheme)
        assertEquals("api.x.ai",grokRequest.url.host)
        assertEquals("/v1/realtime",grokRequest.url.encodedPath)
        assertEquals("grok-voice-think-fast-2.0",grokRequest.url.queryParameter("model"))
        assertEquals("Bearer $xaiKey", grokRequest.header("Authorization"))
        assertFalse(grokRequest.url.toString().contains(xaiKey))
        assertFalse(grok.toString().contains(xaiKey))
        assertFalse(runCatching { GrokWire.request(xaiKey,"invalid model") }.exceptionOrNull()?.message.orEmpty().contains(xaiKey))
        for(request in listOf(GrokWire.modelRequest(xaiKey),GrokWire.voiceRequest(xaiKey))) {
            assertEquals("Bearer $xaiKey",request.rawRequest().header("Authorization"))
            assertFalse(request.rawRequest().url.toString().contains(xaiKey))
            assertFalse(request.toString().contains(xaiKey))
        }

        val openAiKey = "openai-fixture-secret"
        val openAi = OpenAiWire.request(openAiKey, "gpt-realtime-2.1")
        val openAiRequest=openAi.rawRequest()
        assertEquals("https",openAiRequest.url.scheme)
        assertEquals("api.openai.com",openAiRequest.url.host)
        assertEquals("/v1/realtime",openAiRequest.url.encodedPath)
        assertEquals("gpt-realtime-2.1",openAiRequest.url.queryParameter("model"))
        assertEquals("Bearer $openAiKey", openAiRequest.header("Authorization"))
        assertFalse(openAiRequest.url.toString().contains(openAiKey))
        assertFalse(openAi.toString().contains(openAiKey))
        assertFalse(runCatching { OpenAiWire.request(openAiKey, "invalid model") }.exceptionOrNull()?.message.orEmpty().contains(openAiKey))
        val openAiModels=OpenAiWire.modelRequest(openAiKey)
        assertEquals("Bearer $openAiKey",openAiModels.rawRequest().header("Authorization"))
        assertFalse(openAiModels.rawRequest().url.toString().contains(openAiKey))
        assertFalse(openAiModels.toString().contains(openAiKey))
    }

    @Test fun providerCatalogsParseOnlyRealtimeModelsAndUsableVoices() {
        assertEquals(listOf("models/gemini-3.8-live"), GeminiWire.models(JSONObject("""
            {"models":[
              {"name":"models/gemini-3.8-live","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent"]}
            ]}
        """)))
        assertEquals(listOf("Puck"), GeminiWire.voices(JSONObject("""
            {"voices":[
              {"id":"Puck","type":"prebuilt","display_name":"Puck"},
              {"id":"voice_custom","type":"prompted","display_name":"Custom"}
            ]}
        """)))
        // The prebuilt list also carries locale variants and lowercase copies that Live does not take as voice names.
        assertEquals(listOf("Puck", "Aoede"), GeminiWire.voices(JSONObject("""
            {"voices":[
              {"id":"Puck","type":"prebuilt"}, {"id":"aoede","type":"prebuilt"}, {"id":"Aoede","type":"prebuilt"},
              {"id":"ar-001-advisor-1","type":"prebuilt"}, {"id":"puck","type":"prebuilt"}
            ]}
        """)))
        assertEquals(listOf("grok-voice-latest", "grok-voice-think-fast-2.0"), GrokWire.models(JSONObject("""
            {"data":[{"id":"grok-voice-latest"},{"id":"grok-voice-think-fast-2.0"},{"id":"grok-4.7"}]}
        """)))
        assertEquals(listOf("ara", "eve"), GrokWire.voices(JSONObject("""
            {"voices":[{"voice_id":"ara","name":"Ara"},{"voice_id":"eve","name":"Eve"}]}
        """)))
        assertEquals(listOf("gpt-realtime-2.1", "gpt-realtime-2.1-mini"), OpenAiWire.models(JSONObject("""
            {"data":[{"id":"gpt-realtime-2.1"},{"id":"gpt-realtime-2.1-mini"},{"id":"gpt-5.4"}]}
        """)))
    }

    @Test fun openAiSessionAndFunctionCallReceiptUseTheExistingHermesToolBridge() {
        val geminiSetup = GeminiWire.setup("gpt-realtime-2.1", "marin", null,
            JSONObject().put("personality_instruction", "Speak warmly.").put("system_instruction", "Use the open native session."))
        val setup = OpenAiWire.setup(geminiSetup)
        assertEquals("session.update", setup.getString("type"))
        val session = setup.getJSONObject("session")
        assertEquals("realtime", session.getString("type"))
        assertEquals("gpt-realtime-2.1", session.getString("model"))
        assertEquals(JSONArray().put("audio").toString(), session.getJSONArray("output_modalities").toString())
        assertTrue(session.getString("instructions").contains("Speak warmly."))
        val input = session.getJSONObject("audio").getJSONObject("input")
        assertEquals("audio/pcm", input.getJSONObject("format").getString("type"))
        assertEquals(24000, input.getJSONObject("format").getInt("rate"))
        assertEquals("semantic_vad", input.getJSONObject("turn_detection").getString("type"))
        val output = session.getJSONObject("audio").getJSONObject("output")
        assertEquals(24000, output.getJSONObject("format").getInt("rate"))
        assertEquals("marin", output.getString("voice"))
        assertEquals("auto", session.getString("tool_choice"))
        assertTrue(session.getString("instructions").contains("when the owner asks again, try the relevant tool in this call"))
        assertTrue((0 until session.getJSONArray("tools").length()).any {
            session.getJSONArray("tools").getJSONObject(it).getString("name") == "hermes_work"
        })

        val wire = OpenAiWire()
        wire.inbound(JSONObject().put("type", "response.created"))
        val call = wire.inbound(JSONObject().put("type", "response.done").put("response", JSONObject()
            .put("status", "completed").put("output", JSONArray().put(JSONObject()
                .put("type", "function_call").put("call_id", "call-1").put("name", "hermes_work")
                .put("arguments", "{\"text\":\"owned native work\"}")))))!!
        val normalized = call.getJSONObject("toolCall").getJSONArray("functionCalls").getJSONObject(0)
        assertEquals("call-1", normalized.getString("id"))
        assertEquals("hermes_work", normalized.getString("name"))
        assertEquals("owned native work", normalized.getJSONObject("args").getString("text"))

        val receipt = GeminiWire.tool("call-1", "hermes_work", JSONObject().put("status", "completed").put("result", "verified"))
        val reply = wire.outbound(receipt)
        assertEquals("function_call_output", reply.first().getJSONObject("item").getString("type"))
        assertEquals("call-1", reply.first().getJSONObject("item").getString("call_id"))
        assertEquals("response.create", reply.last().getString("type"))
        assertTrue(wire.outbound(receipt).isEmpty())
    }

    @Test fun openAiStreamedFunctionArgumentsAreDeliveredOnlyOnce() {
        val wire = OpenAiWire()
        wire.inbound(JSONObject().put("type", "response.function_call_arguments.delta")
            .put("item_id", "item-1").put("call_id", "call-2").put("name", "hermes_search")
            .put("delta", "{\"query\":\"weather\"}"))
        val done = JSONObject().put("type", "response.function_call_arguments.done")
            .put("item_id", "item-1").put("call_id", "call-2").put("name", "hermes_search")
            .put("arguments", "{\"query\":\"weather\"}")
        assertEquals("weather", wire.inbound(done)!!.getJSONObject("toolCall").getJSONArray("functionCalls")
            .getJSONObject(0).getJSONObject("args").getString("query"))
        val final = JSONObject().put("type", "response.done").put("response", JSONObject().put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("call_id", "call-2")
                .put("name", "hermes_search").put("arguments", "{\"query\":\"weather\"}"))))
        assertNull(wire.inbound(final)?.optJSONObject("toolCall"))
    }

    @Test fun openAiMicrophoneAudioIsPcm24KhzAndVoiceSetupUsesLocalKeys() {
        assertEquals("input_audio_buffer.append", OpenAiWire.audio("AA==").getString("type"))
        assertEquals("AA==", OpenAiWire.audio("AA==").getString("audio"))
        assertTrue(voiceProviderSupported("openai"))
        assertEquals("Beacon", voiceKeySetupPage())
        val create = phoneKeyCallBody("openai", "default", "session-1")
        assertEquals("openai", create.getString("voice_provider"))
        assertTrue(create.getBoolean("client_key"))
        assertEquals("default", create.getString("profile"))
        assertEquals("session-1", create.getString("conversation_id"))
        assertFalse(create.has("api_key")); assertFalse(create.has("token"))
        val answer = phoneKeyAnswerBody("grok")
        assertEquals("grok", answer.getString("voice_provider")); assertTrue(answer.getBoolean("client_key"))
        assertFalse(answer.has("api_key")); assertFalse(answer.has("token"))
    }

    @Test fun openAiCaptureUsesAveraged48To24KhzPcmDecimationAcrossChunkBoundaries() {
        fun samples(vararg values: Int) = ByteArray(values.size * 2).also { bytes -> values.forEachIndexed { i, value ->
            bytes[i * 2] = value.toByte(); bytes[i * 2 + 1] = (value shr 8).toByte()
        } }
        fun read(bytes: ByteArray) = (0 until bytes.size step 2).map { (bytes[it].toInt() and 255) or (bytes[it + 1].toInt() shl 8) }
        val decimator = Pcm48To24()
        val first = decimator.convert(samples(0, 100, 200))
        val second = decimator.convert(samples(300))
        assertEquals(listOf(50), read(first))
        assertEquals(listOf(250), read(second))
    }

    @Test fun openAiMicrophoneFramesReachTheSocketAsAppendEvents() {
        val microphoneFrame = JSONObject(OpenAiWire.audio("AQIDBA==").toString())
        val sent = OpenAiWire().outbound(microphoneFrame)
        assertEquals(1, sent.size)
        assertEquals("input_audio_buffer.append", sent.single().getString("type"))
        assertEquals("AQIDBA==", sent.single().getString("audio"))
    }

    @Test fun openAiSessionEnablesInputTranscription() {
        val setup = OpenAiWire.setup(GeminiWire.setup("gpt-realtime-2.1", "marin", null))
        val input = setup.getJSONObject("session").getJSONObject("audio").getJSONObject("input")
        assertEquals("gpt-4o-transcribe", input.getJSONObject("transcription").getString("model"))
    }

    @Test fun providerCheckDistinguishesSuccessRejectionUnreachableAndProviderErrors() {
        assertEquals(VoiceProviderTestResult.Success, classifyVoiceProviderTestResponse(101))
        assertEquals(VoiceProviderTestResult.KeyRejected, classifyVoiceProviderTestResponse(400))
        assertEquals(VoiceProviderTestResult.KeyRejected, classifyVoiceProviderTestResponse(401))
        assertEquals(VoiceProviderTestResult.KeyRejected, classifyVoiceProviderTestResponse(403))
        assertEquals(VoiceProviderTestResult.Unreachable, classifyVoiceProviderTestResponse(null))
        assertEquals(VoiceProviderTestResult.ProviderError, classifyVoiceProviderTestResponse(503))
    }

    @Test fun aSetupRejectionNamesTheKeyOrTheModel() {
        // Gemini upgrades any key, then can close 1007 or 1008 at setup when the key is rejected.
        assertEquals(VoiceProviderTestResult.KeyRejected, classifyVoiceProviderClose(1007, "API key not valid."))
        assertEquals(VoiceProviderTestResult.KeyRejected, classifyVoiceProviderClose(1008, "API key not valid. Please pass a valid API key."))
        assertEquals(VoiceProviderTestResult.ProviderError, classifyVoiceProviderClose(1007, "invalid request"))
        assertEquals(VoiceProviderTestResult.ProviderError, classifyVoiceProviderClose(1008, "models/gemini-x is not found"))
        assertEquals("Gemini didn't accept the API key. Check it in Settings › Voice & calls.",
            providerRejectionMessage("gemini", "API key not valid. Please pass a valid API key."))
        assertEquals("Grok couldn't start the call. Choose another model in Settings › Voice & calls.",
            providerRejectionMessage("grok", "unknown model"))
    }
}
