package dev.hermes.android.assist

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Which speech recognizer does the listening: the phone's on-device one, or another app's recognition service, named as a
 * flattened component ("package/class"). Never Beacon's own.
 */
internal sealed interface RecognizerChoice {
    data object OnDevice : RecognizerChoice
    data class Component(val name: String) : RecognizerChoice
}

/**
 * Beacon only lists a recognizer because Android requires one beside an assistant; the listening is always done by the phone's own.
 * The owner's chosen default comes first (unless it is Beacon's), then the on-device recognizer, then any other installed one.
 * Recognizers are flattened component names.
 */
internal fun pickRecognizer(ownPackage: String, default: String?, onDevice: Boolean, installed: List<String>): RecognizerChoice? {
    fun theirs(name: String) = name.substringBefore('/') != ownPackage
    return when {
        default != null && theirs(default) -> RecognizerChoice.Component(default)
        onDevice -> RecognizerChoice.OnDevice
        else -> installed.firstOrNull(::theirs)?.let(RecognizerChoice::Component)
    }
}

internal fun recognizerChoice(context: Context): RecognizerChoice? {
    // The default recognizer setting is not readable everywhere; without it the on-device or first other recognizer is used.
    val default = runCatching { android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service") }
        .getOrNull()?.takeIf { it.isNotBlank() }?.let(ComponentName::unflattenFromString)?.flattenToString()
    val onDevice = Build.VERSION.SDK_INT >= 31 && runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    val installed = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString() }
    }.getOrDefault(emptyList())
    // A default that is no longer installed is skipped; when the list itself could not be read, the default is trusted.
    return pickRecognizer(context.packageName, default?.takeIf { installed.isEmpty() || it in installed }, onDevice, installed)
}

/** A recognizer for [choice]; must be made, used and destroyed on the main thread. */
internal fun createRecognizer(context: Context, choice: RecognizerChoice): SpeechRecognizer? = runCatching {
    when (choice) {
        RecognizerChoice.OnDevice -> if (Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context) else null
        is RecognizerChoice.Component -> SpeechRecognizer.createSpeechRecognizer(context, ComponentName.unflattenFromString(choice.name))
    }
}.getOrNull()

/** rmsdB as recognizers report it (about -2 to 10) as a 0..1 level for the dust. */
internal fun dictationLevel(rmsdB: Float): Float = if (rmsdB.isFinite()) ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) else 0f

internal sealed interface DictationEvent {
    data class Level(val value: Float) : DictationEvent
    data class Heard(val text: String) : DictationEvent
    data class Done(val text: String) : DictationEvent
    data class Failed(val code: Int) : DictationEvent
}

/** The overlay's mic: one dictation at a time, written into the question; the reply comes back as text. Main thread only. */
internal class AssistDictation(private val context: Context, private val onEvent: (DictationEvent) -> Unit) {
    private var recognizer: SpeechRecognizer? = null
    val active get() = recognizer != null

    /** False when the phone has no recognizer Beacon may use. */
    fun start(): Boolean {
        cancel()
        val choice = recognizerChoice(context) ?: return false
        val created = createRecognizer(context, choice) ?: return false
        recognizer = created
        created.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { if (recognizer === created) onEvent(DictationEvent.Level(dictationLevel(rmsdB))) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { if (recognizer === created) onEvent(DictationEvent.Level(0f)) }
            override fun onError(error: Int) { if (recognizer === created) { release(); onEvent(DictationEvent.Failed(error)) } }
            override fun onResults(results: Bundle?) { if (recognizer === created) { release(); onEvent(DictationEvent.Done(best(results))) } }
            override fun onPartialResults(partialResults: Bundle?) { if (recognizer === created) best(partialResults).takeIf { it.isNotBlank() }?.let { onEvent(DictationEvent.Heard(it)) } }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        return runCatching {
            created.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName))
        }.onFailure { release() }.isSuccess
    }

    /** Stops listening; the recognizer still delivers what it heard. */
    fun stop() { runCatching { recognizer?.stopListening() } }

    /** Drops the dictation without a result. */
    fun cancel() { recognizer?.let { runCatching { it.cancel() } }; release() }

    private fun release() { val r = recognizer; recognizer = null; r?.let { runCatching { it.destroy() } } }

    private fun best(results: Bundle?) = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
}
