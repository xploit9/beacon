package dev.hermes.android.assist

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import dev.hermes.android.BeaconActivity
import dev.hermes.android.phone.deviceLockedNow

/**
 * The phone's assist gesture (hold power, corner swipe, assistant button) lands here when Beacon is the default assistant.
 * It draws nothing, shows nothing over the lock screen and leaves at once; BeaconActivity opens in front and starts the call.
 * On a locked phone it asks for the unlock first, because the full app cannot show over the lock screen (the overlay talks at once instead):
 * nothing is heard or said until then.
 */
class AssistEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated entry has already handed its gesture over; only a fresh launch makes a token.
        if (savedInstanceState == null && deviceLockedNow(this)) {
            AssistUnlockActivity.open(this, AssistTarget())
        } else if (savedInstanceState == null) {
            startActivity(
                Intent(this, BeaconActivity::class.java)
                    .setAction(ACTION_ASSIST_CALL)
                    .putExtra(EXTRA_ASSIST_TOKEN, AssistGestures.issue())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        }
    }
}
