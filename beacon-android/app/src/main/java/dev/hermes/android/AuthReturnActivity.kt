package dev.hermes.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.hermes.android.data.NativeAuth

class AuthReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No code, token, or state is on this URI; it only returns Beacon to the foreground. Hermes sends it only
        // after the callback, so an earlier launch is another app's and must not clear the sign-in tab.
        if (!NativeAuth.awaitingSignInCallback()) {
            startActivity(Intent(this, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        finish()
    }
}
