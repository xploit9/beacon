package dev.hermes.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.DeliveryLog
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Installs only a fresh synthetic origin/source and restores the test's permission and visibility changes. */
internal class BackgroundActivityFixture(val app: BeaconApplication) : AutoCloseable {
    val scope = Scope("https://activity-notice-fixture.invalid", "synthetic-owner", "default", nativeProvider = "fixture")
    val source = "activity-" + UUID.randomUUID().toString().replace("-", "")
    val manager = app.getSystemService(NotificationManager::class.java)
    val activity = "work-" + UUID.randomUUID().toString().replace("-", "")
    val event = "event-" + UUID.randomUUID().toString().replace("-", "")
    val nativeTag get() = "native:${("$source\u0000$activity").digest()}"
    private val originalAuth = app.auth.read(scope.origin)
    private val originalVisible = app.calls.uiVisible
    private val originalQuietSummary = manager.activeNotifications.firstOrNull { it.tag == "beacon-quiet-summary" && it.id == 5 }?.notification
    private val prefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
    private val bindingKey = "source:${source.digest()}"
    private val originalBinding = prefs.getString(bindingKey, null)
    private val permission = Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private val events = mutableListOf<String>()
    private val fixtureChannel = "activity-fixture-" + UUID.randomUUID().toString()
    private val deliveryPrefs = DeliveryLog.accountPreferences(app)
    private val deliveryKeys = setOf("last_alert_at", "heard_at", "test_event", "test_at", "dropped_at")
    private val originalDelivery = deliveryPrefs.all.filterKeys { it in deliveryKeys }

    init {
        if (Build.VERSION.SDK_INT >= 33 && !permission) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.auth.write(AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture")))
        val binding = json("source" to source, "origin" to scope.origin, "owner" to scope.owner, "profile" to scope.profile,
            "native_provider" to scope.nativeProvider, "backend" to "hermes-dashboard", "target" to "synthetic-target")
        persistPushBindings(prefs, mapOf(bindingKey to android.util.Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), bindingKey), android.util.Base64.NO_WRAP)))
    }

    fun data(kind: String, id: String = event): Map<String, String> {
        events += id
        return mapOf("source_ref" to source, "event_id" to id, "conversation_ref" to "synthetic-conversation", "activity_ref" to activity,
            "kind" to kind, "expires_at" to (System.currentTimeMillis() / 1000 + 300).toString(), "created_at" to (System.currentTimeMillis() / 1000).toString())
    }

    fun postLegacyWorkingCard() {
        manager.createNotificationChannel(NotificationChannel(fixtureChannel, "Synthetic existing channel", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        val card = NotificationCompat.Builder(app, fixtureChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle("Synthetic previous work")
            .setSilent(true).setOngoing(true).addExtras(android.os.Bundle().apply { putString("native_source", source) }).build()
        manager.notify(nativeTag, 3, card)
        await { manager.activeNotifications.any { it.tag == nativeTag && it.id == 3 } }
    }

    fun channelSettings() = manager.notificationChannels.associate { channel ->
        channel.id to listOf(channel.importance, channel.sound?.toString(), channel.shouldVibrate(), channel.vibrationPattern?.toList(), channel.hasUserSetImportance())
    }

    fun card(id: String) = manager.activeNotifications.firstOrNull { it.tag == ("$source\u0000$id").digest() && it.id == 1 }?.notification

    fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        check(condition()) { "Synthetic notification condition did not settle" }
    }

    override fun close() {
        try {
            manager.activeNotifications.filter { it.notification.extras.getString("native_source") == source }.forEach { manager.cancel(it.tag, it.id) }
            events.forEach { manager.cancel(("$source\u0000$it").digest(), 1) }
            if (originalQuietSummary == null) manager.cancel("beacon-quiet-summary", 5)
            else manager.notify("beacon-quiet-summary", 5, NotificationCompat.Builder(app, originalQuietSummary).setOnlyAlertOnce(true).setSilent(true).build())
            manager.deleteNotificationChannel(fixtureChannel)
            app.getSharedPreferences("activity-dismissals", Context.MODE_PRIVATE).edit().remove(nativeTag).commit()
            persistPushBindings(prefs, mapOf(bindingKey to originalBinding))
            if (originalAuth.owner.isBlank() && originalAuth.bearer == null && originalAuth.cookies.isEmpty()) runBlocking { app.auth.remove(scope.origin) } else app.auth.write(originalAuth)
        } finally {
            app.calls.uiVisible = originalVisible
            try {
                val edit = deliveryPrefs.edit()
                deliveryKeys.forEach { edit.remove(it) }
                originalDelivery.forEach { (key, value) -> when (value) {
                    is Long -> edit.putLong(key, value)
                    is Int -> edit.putInt(key, value)
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.map { it as String }.toSet())
                    else -> error("Unsupported saved delivery preference")
                } }
                check(edit.commit()) { "Could not restore synthetic delivery-log changes" }
                check(deliveryPrefs.all.filterKeys { it in deliveryKeys } == originalDelivery) { "Delivery-log restoration was not retained" }
            } finally {
                // The external device runner already restores a denied baseline; revoking an already
                // denied runtime permission would kill this instrumentation process mid-suite.
                if (Build.VERSION.SDK_INT >= 33 && !permission &&
                    app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    InstrumentationRegistry.getInstrumentation().uiAutomation.revokeRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
