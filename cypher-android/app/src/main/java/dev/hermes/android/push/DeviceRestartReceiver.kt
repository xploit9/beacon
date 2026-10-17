package dev.hermes.android.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.hermes.android.CypherApplication
import kotlinx.coroutines.*

internal fun restoreAfterRestart(reconcileCalls: () -> Unit, scheduleUpdates: () -> Unit, schedulePush: () -> Unit) {
    runCatching(reconcileCalls)
    runCatching(scheduleUpdates)
    runCatching(schedulePush)
}

class DeviceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app=context.applicationContext as? CypherApplication ?: return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { restoreAfterRestart({ app.push.reconcileIncomingDeadlines() }, { app.updates.schedule() }, { PushRegistrationJob.schedule(app) }) }
            finally { pending.finish();cancel() }
        }
    }
}
