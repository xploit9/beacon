package dev.hermes.android.assist

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import java.lang.ref.WeakReference

/**
 * Beacon as the phone's digital assistant. Android keeps this service bound while Beacon is the chosen assistant; the assist
 * gesture (hold power, corner swipe) then opens a [BeaconAssistSession] over whatever is on screen, the lock screen included.
 */
class BeaconVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        running = WeakReference(this)
        // The overlay opens with the owner's theme, name and language: read them now, not on the gesture.
        AssistPreferences.warm(applicationContext)
    }

    /** The lock screen's own voice-assist shortcut, where a phone has one: the same overlay, over the lock screen. */
    override fun onLaunchVoiceAssistFromKeyguard() { runCatching { showSession(Bundle(), 0) } }

    override fun onShutdown() {
        if (running?.get() === this) running = null
        super.onShutdown()
    }

    companion object {
        @Volatile private var running: WeakReference<BeaconVoiceInteractionService>? = null

        /** Brings the overlay back with what it held, after an unlock. False when Beacon is no longer the assistant. */
        fun reshow(): Boolean = showWith(Bundle().apply { putBoolean(EXTRA_RESHOW, true) })

        private fun showWith(args: Bundle): Boolean {
            val service = running?.get() ?: return false
            return runCatching { service.showSession(args, 0) }.isSuccess
        }
    }
}

/** Makes one overlay session per show request; Android keeps it while hidden, so the next gesture opens warm. */
class BeaconAssistSessionService : VoiceInteractionSessionService() {
    // The window is clear from the start (it must be set before the session is created): a first show that draws late never
    // shows the phone's default pale window at its edges (owner, 4 Nov 2026: "the screen sides turn white and nothing appears").
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = BeaconAssistSession(this).apply { setTheme(dev.hermes.android.R.style.Theme_Beacon_Assist) }
}

/** Set on a show that brings back the same answer after an unlock, instead of a fresh pill. */
internal const val EXTRA_RESHOW = "dev.hermes.android.extra.ASSIST_RESHOW"
