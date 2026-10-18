package dev.hermes.android.voice

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Only the provider protocol differs; ownership, tool authority and queues live in DirectVoice. */
internal class GrokWire(private val providerName: String = "Grok") {
    private val calls=linkedMapOf<String,String>()
    private val inputSnapshots=linkedMapOf<String,String>()
    private val outputTranscriptItems=linkedSetOf<String>()
    private val argumentDeltas=linkedMapOf<String,String>()
    private val emittedCalls=linkedSetOf<String>()
    private val delivered=linkedMapOf<String,String>()
    // DirectVoice admits at most 64 mutations; unfinished receipts outlive the recent read cache.
    private val pendingDelivered=mutableMapOf<String,String>()
    private var responding=false
    private var responsePending=false

    companion object {
        fun endpoint(value:String):String {
            val uri=URI(value)
            require(uri.scheme=="wss"&&uri.host=="api.x.ai"&&uri.port in setOf(-1,443)&&uri.path=="/v1/realtime"&&uri.userInfo==null&&uri.rawQuery==null&&uri.fragment==null)
            return value
        }
        private const val REALTIME_URL = "wss://api.x.ai/v1/realtime"
        private fun validProviderKey(value: String) = value.isNotBlank() && value.length <= 8192 && value.none { it.isWhitespace() || it.isISOControl() }
        private fun safeModel(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
        private fun safeCatalogValue(value: String) = value.length in 1..200 && value.all { it.isLetterOrDigit() || it in "._/-" }

        fun request(apiKey: String, model: String): VoiceProviderRequest {
            require(validProviderKey(apiKey)) { "Invalid provider key" }
            require(safeModel(model)) { "Invalid voice model" }
            val url = Request.Builder().url(endpoint(REALTIME_URL)).build().url.newBuilder().addQueryParameter("model", model).build()
            return VoiceProviderRequest(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build(),"grok")
        }

        fun modelRequest(apiKey: String): VoiceProviderRequest {
            require(validProviderKey(apiKey)) { "Invalid provider key" }
            return VoiceProviderRequest(Request.Builder().url("https://api.x.ai/v1/models").header("Authorization", "Bearer $apiKey").build(),"grok")
        }

        fun voiceRequest(apiKey: String): VoiceProviderRequest {
            require(validProviderKey(apiKey)) { "Invalid provider key" }
            return VoiceProviderRequest(Request.Builder().url("https://api.x.ai/v1/tts/voices").header("Authorization", "Bearer $apiKey").build(),"grok")
        }

        fun models(value: JSONObject): List<String> {
            val rows = value.optJSONArray("data") ?: value.optJSONArray("models") ?: return emptyList()
            return buildList {
                for (i in 0 until rows.length()) {
                    val row = rows.optJSONObject(i) ?: continue
                    val id = row.optString("id")
                    if (id.startsWith("grok-voice") && safeCatalogValue(id)) add(id)
                    val aliases = row.optJSONArray("aliases") ?: continue
                    for (j in 0 until aliases.length()) {
                        val alias = aliases.optString(j)
                        if (alias.startsWith("grok-voice") && safeCatalogValue(alias)) add(alias)
                    }
                }
            }.distinct()
        }

        fun voices(value: JSONObject): List<String> {
            val rows = value.optJSONArray("voices") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { i ->
                rows.optJSONObject(i)?.optString("voice_id")?.takeIf(::safeCatalogValue)
            }.distinct()
        }
        private fun schema(value:Any?):Any? = when(value) {
            is JSONObject -> JSONObject().apply { value.keys().forEach { key -> val child=value.get(key);put(key,if(key=="type"&&child is String) child.lowercase() else schema(child)) } }
            is JSONArray -> JSONArray().apply { for(i in 0 until value.length()) put(schema(value.get(i))) }
            else -> value
        }
        fun functionTools(gemini: JSONObject): JSONArray {
            val tools=gemini.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
            return JSONArray().also { functions ->
                for(i in 0 until tools.length()) {
                    val tool=tools.getJSONObject(i)
                    functions.put(JSONObject().put("type","function").put("name",tool.getString("name"))
                        .put("description",tool.getString("description")).put("parameters",schema(tool.getJSONObject("parameters"))))
                }
            }
        }
        fun setup(gemini:JSONObject,voice:String):JSONObject {
            val instruction=gemini.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            return JSONObject().put("type","session.update").put("session",JSONObject().put("voice",voice.ifBlank { "eve" })
                .put("instructions",instruction+"\nThis Grok voice session has no camera or screen input. Current visual access is unavailable.")
                .put("turn_detection",JSONObject().put("type","server_vad"))
                .put("audio",JSONObject().put("input",JSONObject().put("format",JSONObject().put("type","audio/pcm").put("rate",16000)).put("transcription",JSONObject().put("model","grok-transcribe")))
                    .put("output",JSONObject().put("format",JSONObject().put("type","audio/pcm").put("rate",24000))))
                .put("tools",functionTools(gemini)))
        }
        fun text(value:String)=JSONObject().put("type","conversation.item.create").put("item",JSONObject().put("type","message").put("role","user")
            .put("content",JSONArray().put(JSONObject().put("type","input_text").put("text",value))))
    }

    // Serialise state with socket callbacks: a result arriving mid-response is spoken after response.done.
    @Synchronized fun outbound(frame:JSONObject):List<JSONObject> {
        frame.optJSONObject("setup")?.let { setup ->
            val voice=setup.optJSONObject("generationConfig")?.optJSONObject("speechConfig")?.optJSONObject("voiceConfig")?.optJSONObject("prebuiltVoiceConfig")?.optString("voiceName").orEmpty()
            return listOf(GrokWire.setup(setup,voice))
        }
        frame.optJSONObject("realtimeInput")?.let { input ->
            input.optJSONObject("audio")?.let { return listOf(JSONObject().put("type","input_audio_buffer.append").put("audio",it.getString("data"))) }
            if(input.optBoolean("audioStreamEnd")) return listOf(JSONObject().put("type","input_audio_buffer.clear"))
            return emptyList()
        }
        val result=mutableListOf<JSONObject>()
        var continueTurn=false
        frame.optJSONObject("toolResponse")?.getJSONArray("functionResponses")?.let { rows ->
            for(i in 0 until rows.length()) {
                val row=rows.getJSONObject(i);val id=row.getString("id");val receipt=row.getJSONObject("response").toString()
                if((pendingDelivered[id]?:delivered[id])==receipt) continue
                result+=if(id in calls&&id !in delivered&&id !in pendingDelivered) JSONObject().put("type","conversation.item.create").put("item",JSONObject().put("type","function_call_output").put("call_id",id).put("output",receipt))
                    else text("Verified tool receipt update for ${row.getString("name")} (call $id): $receipt")
                calls.remove(id)
                val status=row.getJSONObject("response").optString("status")
                if(!directToolIsReadOnly(row.optString("name"))&&status in setOf("ready","running","needs_approval","uncertain","waiting_for_idle","submitting","submitting_native","submitted","accepted","accepted_unlinked","submission_unconfirmed")) {
                    pendingDelivered[id]=receipt;delivered.remove(id)
                } else {
                    pendingDelivered.remove(id);delivered[id]=receipt
                    if(delivered.size>128) delivered.remove(delivered.keys.first())
                }
                // Progress receipts update context silently; only a result or initial approval needs speech.
                if(status !in setOf("ready","running") && !(row.optString("name")=="tool_status"&&status=="needs_approval")) continueTurn=true
            }
        }
        frame.optJSONObject("clientContent")?.optJSONArray("turns")?.let { turns ->
            for(i in 0 until turns.length()) {
                val parts=turns.getJSONObject(i).optJSONArray("parts")?:continue
                for(j in 0 until parts.length()) parts.getJSONObject(j).optString("text").takeIf { it.isNotBlank() }?.let { result+=text(it);continueTurn=true }
            }
        }
        if(result.isNotEmpty()&&continueTurn) {
            responsePending=true
            if(!responding) { responding=true;responsePending=false;result+=JSONObject().put("type","response.create") }
        }
        return result
    }

    /** Normalise provider events to the existing media/transcript/tool lifecycle. */
    @Synchronized fun inbound(frame:JSONObject):JSONObject? {
        val content=JSONObject()
        val functionCalls=JSONArray()
        when(frame.optString("type")) {
            "session.updated" -> return JSONObject().put("setupComplete",JSONObject())
            "response.created" -> { responding=true;return null }
            "input_audio_buffer.speech_started" -> content.put("interrupted",true)
            "response.output_audio.delta","response.audio.delta" -> content.put("modelTurn",JSONObject().put("parts",JSONArray().put(JSONObject().put("inlineData",JSONObject().put("mimeType","audio/pcm;rate=24000").put("data",frame.getString("delta"))))))
            "conversation.item.input_audio_transcription.completed" -> {
                val item=frame.getString("item_id");require(item.isNotBlank()&&item.length<=200)
                val key=item+":"+frame.optInt("content_index",0);val text=frame.getString("transcript")
                if(inputSnapshots[key]==text) return null
                inputSnapshots[key]=text
                if(inputSnapshots.size>128) inputSnapshots.remove(inputSnapshots.keys.first())
                content.put("inputTranscription",JSONObject().put("text",text)
                    .put("item_id",item).put("content_index",frame.optInt("content_index",0)).put("final",true))
            }
            "response.output_audio_transcript.delta","response.audio_transcript.delta" -> {
                frame.optString("item_id").takeIf(String::isNotBlank)?.let(::rememberOutputTranscript)
                content.put("outputTranscription",JSONObject().put("text",frame.getString("delta")))
            }
            "response.output_audio_transcript.done","response.audio_transcript.done" -> {
                val item=frame.optString("item_id")
                if(item.isNotBlank()&&!rememberOutputTranscript(item)) return null
                content.put("outputTranscription",JSONObject().put("text",frame.optString("transcript")).put("final",true)
                    .apply { item.takeIf(String::isNotBlank)?.let { put("item_id",it).put("content_index",frame.optInt("content_index",0)) } })
            }
            "response.output_item.added" -> {
                val item=frame.optJSONObject("item")
                if(item?.optString("type")=="function_call") {
                    val id=item.optString("call_id")
                    if(id.isNotBlank()) calls[id]=item.optString("name")
                }
                return null
            }
            "response.function_call_arguments.delta" -> {
                val item=frame.getString("item_id")
                require(item.isNotBlank()&&item.length<=200)
                val accumulated=argumentDeltas.getOrDefault(item,"")+frame.getString("delta")
                require(accumulated.length<=20000)
                argumentDeltas[item]=accumulated
                if(argumentDeltas.size>128) argumentDeltas.remove(argumentDeltas.keys.first())
                frame.optString("call_id").takeIf(String::isNotBlank)?.let { id ->
                    frame.optString("name").takeIf(String::isNotBlank)?.let { name -> calls[id]=name }
                }
                return null
            }
            "response.function_call_arguments.done" -> {
                decodeCall(frame)?.let(functionCalls::put)
            }
            "response.done" -> {
                responding=false
                val response=frame.optJSONObject("response")
                val status=response?.optString("status")
                if(status=="failed") error("$providerName response failed")
                val output=response?.optJSONArray("output")
                if(output!=null) for(i in 0 until output.length()) {
                    val item=output.optJSONObject(i)?:continue
                    if(item.optString("type")=="function_call") decodeCall(item)?.let(functionCalls::put)
                }
                content.put("turnComplete",true)
                if(status=="cancelled") content.put("interrupted",true)
            }
            "error" -> error("$providerName voice protocol error") // Provider messages may echo secrets or private payloads.
            else -> return null
        }
        return JSONObject().put("serverContent",content).apply {
            if(functionCalls.length()>0) put("toolCall",JSONObject().put("functionCalls",functionCalls))
        }
    }

    private fun decodeCall(frame:JSONObject):JSONObject? {
        val id=frame.optString("call_id")
        val name=frame.optString("name")
        require(id.isNotBlank()&&id.length<=200&&name.isNotBlank()&&name.length<=128)
        if(!emittedCalls.add(id)) return null
        val arguments=frame.optString("arguments").takeIf(String::isNotBlank)
            ?:argumentDeltas[frame.optString("item_id")].orEmpty()
        require(arguments.isNotBlank()&&arguments.length<=20000)
        val args=JSONObject(arguments)
        calls[id]=name
        if(calls.size>128) {
            val oldestRead=calls.entries.firstOrNull { directToolIsReadOnly(it.value) }?.key
            // This is protocol matching only; DirectVoice retains mutation authority and receipts.
            val remove=oldestRead?:calls.keys.first()
            calls.remove(remove);emittedCalls.remove(remove)
        }
        if(emittedCalls.size>128) emittedCalls.remove(emittedCalls.first())
        frame.optString("item_id").takeIf(String::isNotBlank)?.let(argumentDeltas::remove)
        return JSONObject().put("id",id).put("name",name).put("args",args)
    }

    private fun rememberOutputTranscript(item:String):Boolean {
        require(item.isNotBlank()&&item.length<=200)
        val fresh=outputTranscriptItems.add(item)
        if(outputTranscriptItems.size>128) outputTranscriptItems.remove(outputTranscriptItems.first())
        return fresh
    }
    @Synchronized fun continueResponse():JSONObject? = if(responsePending&&!responding) {
        responsePending=false;responding=true;JSONObject().put("type","response.create")
    } else null
}
