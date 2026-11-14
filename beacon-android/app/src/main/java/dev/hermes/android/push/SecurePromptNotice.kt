package dev.hermes.android.push

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.BeaconActivity
import dev.hermes.android.R

/** One line per kind of question Hermes can hold open. No site, name or code ever goes on a lock screen. */
internal fun securePromptNoticeTitle(method: String): String = when (method) {
    "vault.unlock_prompt" -> "Unlock your password manager"
    "vault.save_login" -> "Save a login"
    "vault.code" -> "Enter a verification code"
    else -> "Hermes needs you"
}

/**
 * Hermes keeps a secret question open for its own timeout. When it cannot be shown (the app is away, the phone is locked) this notice
 * brings the person back; the question itself appears in the app, never in the notification.
 */
internal object SecurePromptNotice {
    private const val TAG = "secure-input"
    private const val ID = 1

    fun post(context: Context, method: String, windowMs: Long) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureActivityChannels(manager)
        val channel = promptNotificationChannel(manager)
        val open = Intent(context, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle("Hermes needs you").setContentText("Open Beacon to answer.").build()
        val notice = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(securePromptNoticeTitle(method)).setContentText("Open Beacon to answer.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setTimeoutAfter(windowMs).build()
        try { manager.notify(TAG, ID, notice) } catch (_: SecurityException) { }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(TAG, ID)
    }
}
