package dev.hermes.android.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * The overlay asks the phone to unlock before anything private opens, or when its conversation needs something private. It draws
 * nothing: it sits over the lock screen only long enough to ask Android for the unlock, then does what the overlay asked and leaves.
 * Cancelling the unlock does nothing: the overlay is still up with its "Unlock your phone first." line and Unlock to ask again.
 * Not exported, so only the overlay can start it.
 */
class AssistUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        else { @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        // A recreation after the unlock was asked (the process was killed while the PIN was typed, or the screen turned) never asks again;
        // if the phone is unlocked by then, the unlock's work is done once, so the owner is not left with nothing after the PIN.
        if (savedInstanceState != null) {
            if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false) proceed() else finish()
            return
        }
        setShowWhenLocked(true)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) { proceed(); return }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = proceed()
            override fun onDismissCancelled() = finish()
            override fun onDismissError() = finish()
        })
    }

    private fun proceed() {
        val then = intent.getStringExtra(EXTRA_THEN)
        when (then) {
            THEN_REVEAL -> BeaconVoiceInteractionService.reshow()
            // The conversation the overlay held goes on, now trusted: the overlay comes back as it was and nothing starts again.
            THEN_CARRY_ON -> carryOn()
            THEN_PERMISSIONS -> openBeaconPermissions(this)
            else -> assistTargetOf(then, intent.getStringExtra(EXTRA_ASSIST_SESSION), intent.getStringExtra(EXTRA_ASSIST_PROFILE))?.let { openBeacon(this, it) }
        }
        finish()
    }

    /**
     * The overlay that asked is usually still up under the unlock: it is only told ([AssistOverlayController.unlockSeen]). If the system
     * hid it meanwhile, it comes back with what it held; if it cannot (Beacon is no longer the assistant, or its service is not up
     * yet), the app shows the conversation instead.
     */
    private fun carryOn() {
        if (BeaconAssistSession.current?.get()?.controller?.unlockSeen() == true) return
        if (!BeaconVoiceInteractionService.reshow()) openBeacon(this, AssistTarget(AssistPurpose.ShowCall))
    }

    companion object {
        private const val EXTRA_THEN = "dev.hermes.android.extra.ASSIST_THEN"
        private const val THEN_REVEAL = "reveal"
        private const val THEN_CARRY_ON = "carry_on"
        private const val THEN_PERMISSIONS = "permissions"

        /** Unlock, then open the app for [target]; the token is issued only after the unlock, so a slow PIN never expires it. */
        internal fun open(context: Context, target: AssistTarget) = start(context, target.purpose.name) {
            putExtra(EXTRA_ASSIST_SESSION, target.session).putExtra(EXTRA_ASSIST_PROFILE, target.profile)
        }

        /** Unlock, then bring the overlay back with the answer it held. */
        internal fun reveal(context: Context) = start(context, THEN_REVEAL) {}

        /** Unlock, then bring the overlay back with its conversation, which goes on trusted; the overlay that asked stays up meanwhile. */
        internal fun carryOn(context: Context) = start(context, THEN_CARRY_ON) {}

        /** Unlock, then open Beacon's permissions. */
        internal fun permissions(context: Context) = start(context, THEN_PERMISSIONS) {}

        private fun start(context: Context, then: String, extras: Intent.() -> Unit) {
            context.startActivity(Intent(context, AssistUnlockActivity::class.java).putExtra(EXTRA_THEN, then).apply(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
