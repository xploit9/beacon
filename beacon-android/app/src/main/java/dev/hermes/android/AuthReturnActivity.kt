package dev.hermes.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class AuthReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No code, token, or state is on this URI; it only returns Beacon to the foreground.
        startActivity(Intent(this, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
