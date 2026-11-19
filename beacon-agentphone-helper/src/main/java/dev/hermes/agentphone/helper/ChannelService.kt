package dev.hermes.agentphone.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * The channel service (contract §1). Exported only behind android.permission.DUMP, which the shell and the system hold
 * and no app can, so only Beacon's adb command starts it:
 *   am start-foreground-service -n dev.hermes.agentphone.helper/.ChannelService --ei port <P> --es token <T>
 * It then dials 10.0.2.2:<P> and proves the token (Sessions and Link hold the rules). Neither the port nor the token
 * is logged.
 */
class ChannelService : Service() {
    private lateinit var runtime: Runtime

    override fun onCreate() {
        super.onCreate()
        runtime = Runtime.of(this)
        runtime.onIdle = { if (runtime.sessions.isIdle) stop() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground-service start must reach startForeground even when the intent is ignored.
        startForeground(NOTIFICATION_ID, notification())
        val port = intent?.getIntExtra(EXTRA_PORT, -1) ?: -1
        val token = intent?.getStringExtra(EXTRA_TOKEN)
        intent?.removeExtra(EXTRA_TOKEN)
        val started = runtime.sessions.start(port, token)
        Log.i(Runtime.TAG, if (started) "channel start" else "channel start ignored")
        if (!started && runtime.sessions.isIdle) stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runtime.onIdle = null
        runtime.sessions.stopAll()
        super.onDestroy()
    }

    private fun stop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_beacon)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_PORT = "port"
        const val EXTRA_TOKEN = "token"
        private const val CHANNEL = "beacon"
        private const val NOTIFICATION_ID = 1
    }
}
