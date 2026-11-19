package dev.hermes.agentphone.helper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import dev.hermes.agentphone.helper.core.Note

/**
 * Keeps the title and text of recent notifications in memory only (at most 20, at most 10 minutes) so the helper can
 * fill a code itself or hand a temporary password to the host (contract §6.4-6.5). Codes never leave the helper.
 * Enabled from the shell with the full component name:
 *   cmd notification allow_listener dev.hermes.agentphone.helper/dev.hermes.agentphone.helper.CodeListener
 */
class CodeListener : NotificationListenerService() {
    override fun onListenerConnected() {
        Runtime.listening = true
        try { activeNotifications?.forEach(::keep) } catch (e: RuntimeException) { }
        Log.i(Runtime.TAG, "notification reader connected")
    }

    override fun onListenerDisconnected() {
        Runtime.listening = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        keep(sbn)
    }

    private fun keep(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        Runtime.of(this).notes.add(Note(sbn.packageName, sbn.postTime, title, text))
    }
}
