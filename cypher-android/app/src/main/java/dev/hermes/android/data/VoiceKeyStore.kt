package dev.hermes.android.data

import android.content.Context
import android.content.SharedPreferences
import java.nio.charset.StandardCharsets
import java.util.Base64

internal fun voiceProviderSupported(provider: String) = provider in setOf("gemini", "grok", "openai")

internal data class VoicePreferences(
    val provider: String = "gemini",
    val model: String = defaultVoiceModel(provider),
    val voice: String = defaultVoiceName(provider),
    val personality: String = "balanced",
)

internal fun defaultVoiceModel(provider: String): String = when (provider) {
    "grok" -> "grok-voice-latest"
    "openai" -> "gpt-realtime-2.1"
    else -> "models/gemini-3.8-live"
}

internal fun defaultVoiceName(provider: String): String = when (provider) {
    "grok" -> "eve"
    "openai" -> "marin"
    else -> "Aoede"
}

/** The API key stays out of generated value representations and Hermes request bodies. */
internal class VoiceSessionCredentials(
    val provider: String,
    internal val apiKey: String,
    val model: String,
    val voice: String,
    val personality: String,
) {
    val personalityInstruction: String
        get() = when (personality) {
            "concise" -> "Speak concisely and directly. Prefer short answers unless detail is requested."
            "warm" -> "Speak warmly and patiently, with a friendly conversational tone."
            else -> "Use a natural, balanced conversational tone."
        }

    override fun toString() = "VoiceSessionCredentials(provider=$provider, model=$model, voice=$voice, apiKey=<redacted>)"
}

internal class MissingVoiceKeyException(provider: String) : IllegalStateException(
    "Add the " + when (provider) { "grok" -> "Grok"; "openai" -> "OpenAI"; else -> "Gemini" } +
        " API key in Voice & calls settings."
)

internal class VoiceKeyStore private constructor(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val encrypt: (ByteArray, String) -> ByteArray,
    private val decrypt: (ByteArray, String) -> ByteArray,
) {
    constructor(context: Context) : this(
        context.getSharedPreferences("voice-private", Context.MODE_PRIVATE),
        AppCipher(),
    )

    private constructor(prefs: SharedPreferences, cipher: AppCipher) : this(
        { prefs.getString(it, null) },
        { name, value ->
            val editor = prefs.edit()
            if (value == null) editor.remove(name) else editor.putString(name, value)
            editor.commit()
        },
        cipher::encrypt,
        cipher::decrypt,
    )

    internal constructor(
        storage: MutableMap<String, String>,
        encrypt: (ByteArray, String) -> ByteArray,
        decrypt: (ByteArray, String) -> ByteArray,
    ) : this(
        { storage[it] },
        { name, value -> if (value == null) storage.remove(name) else storage[name] = value; true },
        encrypt,
        decrypt,
    )

    fun key(provider: String): String? {
        require(voiceProviderSupported(provider)) { "Unsupported voice provider" }
        val stored = read("key.$provider") ?: return null
        return runCatching {
            String(decrypt(Base64.getDecoder().decode(stored), "voice-key:$provider"), StandardCharsets.UTF_8)
                .takeIf { it.isNotBlank() && it.none { char -> char.isISOControl() } }
        }.getOrNull()
    }

    fun hasKey(provider: String): Boolean {
        require(voiceProviderSupported(provider)) { "Unsupported voice provider" }
        return !read("key.$provider").isNullOrBlank()
    }

    fun maskedKey(provider: String): String? = key(provider)?.takeLast(4)?.let { "••••$it" }

    fun saveKey(provider: String, value: String) {
        require(voiceProviderSupported(provider)) { "Unsupported voice provider" }
        val normalized = value.trim()
        require(normalized.isNotEmpty() && normalized.length <= 8192 && normalized.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter a valid API key"
        }
        val encrypted = encrypt(normalized.toByteArray(StandardCharsets.UTF_8), "voice-key:$provider")
        check(write("key.$provider", Base64.getEncoder().encodeToString(encrypted))) { "Voice key could not be saved" }
    }

    fun removeKey(provider: String) {
        require(voiceProviderSupported(provider)) { "Unsupported voice provider" }
        check(write("key.$provider", null)) { "Voice key could not be removed" }
    }

    fun settings(providerOverride: String? = null): VoicePreferences {
        val provider = providerOverride?.also { require(voiceProviderSupported(it)) }
            ?: read("provider")?.takeIf(::voiceProviderSupported) ?: "gemini"
        return VoicePreferences(
            provider,
            read("model.$provider")?.takeIf(::validVoiceOption) ?: defaultVoiceModel(provider),
            read("voice.$provider")?.takeIf(::validVoiceOption) ?: defaultVoiceName(provider),
            read("personality.$provider")?.takeIf { it in VOICE_PERSONALITIES } ?: "balanced",
        )
    }

    fun setSelection(value: VoicePreferences) {
        require(voiceProviderSupported(value.provider)) { "Unsupported voice provider" }
        require(validVoiceOption(value.model) && validVoiceOption(value.voice)) { "Invalid voice selection" }
        require(value.personality in VOICE_PERSONALITIES) { "Invalid voice personality" }
        check(write("provider", value.provider)) { "Voice provider could not be saved" }
        check(write("model." + value.provider, value.model)) { "Voice model could not be saved" }
        check(write("voice." + value.provider, value.voice)) { "Voice could not be saved" }
        check(write("personality." + value.provider, value.personality)) { "Voice personality could not be saved" }
    }

    fun session(provider: String = settings().provider): VoiceSessionCredentials? {
        require(voiceProviderSupported(provider)) { "Unsupported voice provider" }
        val apiKey = key(provider) ?: return null
        val selection = settings(provider)
        return VoiceSessionCredentials(provider, apiKey, selection.model, selection.voice, selection.personality)
    }

    private fun validVoiceOption(value: String) =
        value.isNotBlank() && value.length <= 200 && value.none { it.isWhitespace() || it.isISOControl() }

    private companion object {
        val VOICE_PERSONALITIES = setOf("balanced", "concise", "warm")
    }
}
