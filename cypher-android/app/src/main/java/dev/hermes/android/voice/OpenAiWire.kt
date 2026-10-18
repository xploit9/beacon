package dev.hermes.android.voice

import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Do not expose authenticated OkHttp headers through the request's string representation. */
internal class VoiceProviderRequest(private val request: Request, private val provider: String) {
    internal fun rawRequest() = request
    override fun toString() = "VoiceProviderRequest(provider=$provider)"
}

/** Realtime event shapes adapted from Hermes Relay's MIT-licensed voice_lab OpenAI provider. */
internal class OpenAiWire {
    private val bridge = GrokWire("OpenAI")

    companion object {
        const val DEFAULT_VOICE = "marin"
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime"
        val VOICES = listOf("alloy", "ash", "ballad", "coral", "echo", "sage", "shimmer", "verse", "marin", "cedar")

        private fun validProviderKey(value: String) =
            value.isNotBlank() && value.length <= 8192 && value.none { it.isWhitespace() || it.isISOControl() }

        private fun safeModel(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}"))
        private fun safeCatalogValue(value: String) = value.length in 1..200 && value.all { it.isLetterOrDigit() || it in "._/-" }

        fun request(apiKey: String, model: String): VoiceProviderRequest {
            require(validProviderKey(apiKey)) { "Invalid provider key" }
            require(safeModel(model)) { "Invalid voice model" }
            val url = Request.Builder().url(REALTIME_URL).build().url.newBuilder().addQueryParameter("model", model).build()
            return VoiceProviderRequest(Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build(),"openai")
        }

        fun modelRequest(apiKey: String): VoiceProviderRequest {
            require(validProviderKey(apiKey)) { "Invalid provider key" }
            return VoiceProviderRequest(Request.Builder().url("https://api.openai.com/v1/models").header("Authorization", "Bearer $apiKey").build(),"openai")
        }

        fun models(value: JSONObject): List<String> {
            val rows = value.optJSONArray("data") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.optString("id")?.takeIf { it.startsWith("gpt-realtime") && safeCatalogValue(it) }
            }.distinct()
        }

        fun setup(gemini: JSONObject): JSONObject {
            val source = gemini.getJSONObject("setup")
            val model = source.getString("model").removePrefix("models/")
            val voice = source.optJSONObject("generationConfig")?.optJSONObject("speechConfig")
                ?.optJSONObject("voiceConfig")?.optJSONObject("prebuiltVoiceConfig")?.optString("voiceName")
                ?.takeIf(VOICES::contains) ?: DEFAULT_VOICE
            val instructions = source.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            return JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "realtime")
                .put("model", model)
                .put("output_modalities", JSONArray().put("audio"))
                .put("instructions", instructions)
                .put("audio", JSONObject()
                    .put("input", JSONObject()
                        .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                        .put("transcription", JSONObject().put("model", "gpt-4o-transcribe"))
                        .put("turn_detection", JSONObject().put("type", "semantic_vad")))
                    .put("output", JSONObject()
                        .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24000))
                        .put("voice", voice)))
                .put("tools", GrokWire.functionTools(source))
                .put("tool_choice", "auto"))
        }

        fun audio(base64: String) = JSONObject().put("type", "input_audio_buffer.append").put("audio", base64)
    }

    fun inbound(frame: JSONObject): JSONObject? = bridge.inbound(frame)

    fun outbound(frame: JSONObject): List<JSONObject> =
        if (frame.optString("type") == "session.update" || frame.optString("type").startsWith("input_audio_buffer.")) listOf(frame)
        else bridge.outbound(frame)

    fun continueResponse(): JSONObject? = bridge.continueResponse()
}
