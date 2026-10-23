package dev.hermes.android.data

// Adapted from Hermes Relay app/src/main/kotlin/com/hermesandroid/relay/network/upstream/NativeDashboardAuthService.kt (MIT).
// Beacon keeps the sign-in going when the promotion is refused instead of failing it.

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.hermes.android.BeaconActivity
import dev.hermes.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps Beacon in the foreground while the browser sign-in is open; the last unbind stops it. */
class NativeSignInService : Service() {
    internal inner class SignInBinder : Binder() {
        fun promote() {
            startService(Intent(this@NativeSignInService, NativeSignInService::class.java))
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Sign-in", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
            val notification = NotificationCompat.Builder(this@NativeSignInService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_beacon_notification)
                .setContentTitle("Signing in to Hermes")
                .setContentIntent(PendingIntent.getActivity(
                    this@NativeSignInService, NOTIFICATION_ID,
                    Intent(this@NativeSignInService, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent): IBinder = SignInBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onUnbind(intent: Intent?): Boolean {
        stopSelf()
        return false
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    internal companion object {
        const val CHANNEL_ID = "beacon_sign_in"
        const val NOTIFICATION_ID = 4716
    }
}

/**
 * Android freezes a cached app, and Hermes signs a phone in through a loopback callback that the frozen app can't
 * answer: a slow sign-in then hangs in the browser. Holding a foreground service for the sign-in keeps it reachable.
 */
internal suspend fun <T> withNativeSignInForeground(context: Context, block: suspend () -> T): T {
    val app = context.applicationContext
    val ready = CompletableDeferred<Boolean>()
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ready.complete(runCatching { (binder as NativeSignInService.SignInBinder).promote() }.isSuccess)
        }
        override fun onServiceDisconnected(name: ComponentName) = Unit
        override fun onNullBinding(name: ComponentName) { ready.complete(false) }
    }
    val bound = withContext(Dispatchers.Main.immediate) {
        runCatching { app.bindService(Intent(app, NativeSignInService::class.java), connection, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
    }
    try {
        if (bound) withTimeoutOrNull(2_000) { ready.await() }
        return block()
    } finally {
        if (bound) withContext(NonCancellable + Dispatchers.Main.immediate) { runCatching { app.unbindService(connection) } }
    }
}
