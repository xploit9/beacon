package dev.hermes.android.assist

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * The overlay asks the phone to unlock before anything private opens. It draws nothing: it sits over the lock screen only long
 * enough to ask Android for the unlock, then does what the overlay asked and leaves. Cancelling the unlock does nothing.
 * Not exported, so only the overlay can start it.
 */
class AssistUnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        else { @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
        // A recreation after the unlock was asked must not ask again.
        if (savedInstanceState != null) { finish(); return }
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
            THEN_PERMISSIONS -> openBeaconPermissions(this)
            else -> assistTargetOf(then, intent.getStringExtra(EXTRA_ASSIST_SESSION), intent.getStringExtra(EXTRA_ASSIST_PROFILE))?.let { openBeacon(this, it) }
        }
        finish()
    }

    companion object {
        private const val EXTRA_THEN = "dev.hermes.android.extra.ASSIST_THEN"
        private const val THEN_REVEAL = "reveal"
        private const val THEN_PERMISSIONS = "permissions"

        /** Unlock, then open the app for [target]; the token is issued only after the unlock, so a slow PIN never expires it. */
        internal fun open(context: Context, target: AssistTarget) = start(context, target.purpose.name) {
            putExtra(EXTRA_ASSIST_SESSION, target.session).putExtra(EXTRA_ASSIST_PROFILE, target.profile)
        }

        /** Unlock, then bring the overlay back with the answer it held. */
        internal fun reveal(context: Context) = start(context, THEN_REVEAL) {}

        /** Unlock, then open Beacon's permissions. */
        internal fun permissions(context: Context) = start(context, THEN_PERMISSIONS) {}

        private fun start(context: Context, then: String, extras: Intent.() -> Unit) {
            context.startActivity(Intent(context, AssistUnlockActivity::class.java).putExtra(EXTRA_THEN, then).apply(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
