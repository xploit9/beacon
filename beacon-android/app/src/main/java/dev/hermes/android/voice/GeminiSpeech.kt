package dev.hermes.android.voice

import dev.hermes.android.data.VoiceSessionCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Literal synthesis uses the phone key/voice; the selected Live model continues to handle listening. */
internal object GeminiSpeech {
    const val MODEL = "gemini-3.8-flash-lite-tts"
    const val MAX_JSON_BYTES = 4 * 1024 * 1024
    const val MAX_PCM_BYTES = 60 * 48000

    fun request(credentials: VoiceSessionCredentials, text: String): Request {
        require(credentials.provider == "gemini" && credentials.apiKey.isNotBlank() && credentials.apiKey.length <= 8192 &&
            credentials.apiKey.none { it.isWhitespace() || it.isISOControl() })
        require(text.isNotBlank() && text.length <= 12000)
        val style = when (credentials.personality) { "warm" -> "warm and friendly"; "concise" -> "clear and direct"; else -> "natural" }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
            .put(JSONObject().put("text", text).put("speech_metadata", JSONObject().put("style", style))))))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                .put("responseFormat", JSONObject().put("audio", JSONObject().put("mimeType", "AUDIO_L16").put("sampleRate", 24000)))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put("voice", credentials.voice))))
        return Request.Builder().url("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .header("x-goog-api-key", credentials.apiKey).post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }

    fun pcm(value: JSONObject): ByteArray {
        require(!value.has("error"))
        val block = value.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        require(block.isBlank() || block == "BLOCK_REASON_UNSPECIFIED")
        val candidates = value.getJSONArray("candidates")
        require(candidates.length() == 1)
        val candidate = candidates.getJSONObject(0)
        require(candidate.optString("finishReason") == "STOP")
        val parts = candidate.getJSONObject("content").getJSONArray("parts")
        require(parts.length() in 1..64)
        val out = ByteArrayOutputStream()
        for (index in 0 until parts.length()) {
            val part = parts.getJSONObject(index)
            require(part.keys().asSequence().toSet() == setOf("inlineData"))
            val audio = part.getJSONObject("inlineData")
            val fields = audio.getString("mimeType").lowercase(java.util.Locale.ROOT).split(';').map(String::trim)
            require(fields.first() == "audio/l16")
            val parameters = fields.drop(1).map { it.split('=', limit = 2).also { p -> require(p.size == 2) } }
            require(parameters.map { it[0] }.toSet().size == parameters.size)
            val format = parameters.associate { it[0] to it[1] }
            require(format.keys.all { it in setOf("rate", "channels", "codec") } && format["rate"] == "24000" &&
                format["channels"] in setOf(null, "1") && format["codec"] in setOf(null, "pcm"))
            val pcm = java.util.Base64.getDecoder().decode(audio.getString("data"))
            require(pcm.isNotEmpty() && pcm.size % 2 == 0 && out.size() + pcm.size <= MAX_PCM_BYTES)
            out.write(pcm)
        }
        return out.toByteArray()
    }
}
