package dev.hermes.android.updates

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermes.android.CypherApplication

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? CypherApplication)?.updates?.handleInstallResult(intent)
    }
}
