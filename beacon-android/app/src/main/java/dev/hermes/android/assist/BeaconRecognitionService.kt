package dev.hermes.android.assist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * The recognizer Android requires beside a digital assistant. It does no listening of its own: every request goes to the phone's
 * own recognizer ([recognizerChoice]), so speech input in other apps keeps working while Beacon is the assistant. With no other
 * recognizer on the phone it answers with a clean error. Android's base class already refuses callers without the microphone
 * permission; [onStartListening] checks the caller again before anything is passed on.
 */
class BeaconRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null
    private var listener: Callback? = null

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        release()
        if (checkPermission(Manifest.permission.RECORD_AUDIO, -1, callback.callingUid) != PackageManager.PERMISSION_GRANTED) {
            report { callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) }
            return
        }
        val recognizer = recognizerChoice(this)?.let { createRecognizer(this, it) }
        if (recognizer == null) { report { callback.error(SpeechRecognizer.ERROR_CLIENT) }; return }
        delegate = recognizer
        listener = callback
        recognizer.setRecognitionListener(object : RecognitionListener {
            fun live() = delegate === recognizer
            override fun onReadyForSpeech(params: Bundle?) { if (live()) report { callback.readyForSpeech(params ?: Bundle()) } }
            override fun onBeginningOfSpeech() { if (live()) report { callback.beginningOfSpeech() } }
            override fun onRmsChanged(rmsdB: Float) { if (live()) report { callback.rmsChanged(rmsdB) } }
            override fun onBufferReceived(buffer: ByteArray?) { if (live() && buffer != null) report { callback.bufferReceived(buffer) } }
            override fun onEndOfSpeech() { if (live()) report { callback.endOfSpeech() } }
            override fun onError(error: Int) { if (live()) { report { callback.error(error) }; release() } }
            override fun onResults(results: Bundle?) { if (live()) { report { callback.results(results ?: Bundle()) }; release() } }
            override fun onPartialResults(partialResults: Bundle?) { if (live()) report { callback.partialResults(partialResults ?: Bundle()) } }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        runCatching { recognizer.startListening(recognizerIntent ?: Intent()) }.onFailure {
            report { callback.error(SpeechRecognizer.ERROR_CLIENT) }
            release()
        }
    }

    override fun onStopListening(callback: Callback) { if (listener === callback) runCatching { delegate?.stopListening() } }

    override fun onCancel(callback: Callback) { if (listener === callback) { runCatching { delegate?.cancel() }; release() } }

    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        val recognizer = delegate
        delegate = null; listener = null
        recognizer?.let { runCatching { it.destroy() } }
    }

    /** The caller may have gone away; its binder then throws, which is not this service's failure. */
    private inline fun report(block: () -> Unit) { runCatching(block) }
}
