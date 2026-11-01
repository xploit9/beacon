package dev.hermes.android.uiux.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.receivePush
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import java.util.UUID

/**
 * The notification side of honest delivery on the emulator: an alert that reaches the phone late arrives quietly under one
 * summary and shows its own time, while a fresh alert and a still-open approval keep alerting. Uses a synthetic owner and source.
 */
class QuietDeliveryNotificationsTest {
    private val scope = Scope("https://notifications-lane.invalid", "synthetic-owner", "default", nativeProvider = "fixture")
    private val source = "source-" + UUID.randomUUID().toString().replace("-", "").take(16)

    private fun data(kind: String, event: String, createdAtSeconds: Long, expiresAtSeconds: Long) = mapOf(
        "source_ref" to source, "event_id" to event, "conversation_ref" to "conversation-$event", "kind" to kind,
        "created_at" to createdAtSeconds.toString(), "expires_at" to expiresAtSeconds.toString(),
    )

    @Test fun lateAlertsArriveQuietlyUnderOneSummaryAndFreshOrOpenOnesStillAlert() {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        assumeTrue("The summary text is English", Locale.getDefault().language == "en")
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val hadNotifications = android.os.Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(NotificationManager::class.java)
        val pushPrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val bindingKey = "source:${source.digest()}"
        val priorBinding = pushPrefs.getString(bindingKey, null)
        val priorAuth = app.auth.read(scope.origin)
        val events = mutableListOf<String>()
        fun tag(event: String) = "$source\u0000$event".digest()
        fun card(event: String): Notification = manager.activeNotifications.firstOrNull { it.tag == tag(event) && it.id == 1 }?.notification ?: throw AssertionError("$event was not posted")
        fun event(label: String) = (label + UUID.randomUUID().toString().replace("-", "").take(10)).also { events += it }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            app.auth.write(AuthContext(
                scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
                bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
            ))
            val binding = JSONObject().put("source", source).put("origin", scope.origin).put("owner", scope.owner).put("native_provider", scope.nativeProvider)
                .put("profile", scope.profile).put("backend", "hermes-dashboard").put("target", "fixture-target")
            persistPushBindings(pushPrefs, mapOf(bindingKey to android.util.Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), bindingKey), android.util.Base64.NO_WRAP)))
            app.calls.uiVisible = false

            val nowSeconds = System.currentTimeMillis() / 1_000
            val fresh = event("fresh")
            receivePush(app, data("task_result", fresh, nowSeconds, nowSeconds + 600))
            val freshCard = card(fresh)
            assertNull("a fresh alert stands alone", freshCard.group)
            assertTrue("it shows when it happened", kotlin.math.abs(freshCard.`when` - nowSeconds * 1_000) < 5_000)

            val lateTime = nowSeconds - 3_600
            val lateOne = event("late-a")
            val lateTwo = event("late-b")
            receivePush(app, data("task_result", lateOne, lateTime, nowSeconds + 600))
            receivePush(app, data("completion", lateTwo, lateTime - 60, nowSeconds + 600))
            listOf(lateOne to lateTime, lateTwo to lateTime - 60).forEach { (id, time) ->
                val late = card(id)
                assertEquals("$id is grouped", "beacon-alerts", late.group)
                assertTrue("$id is marked quiet", late.extras.getBoolean("beacon_quiet"))
                assertEquals("$id shows its own time", time * 1_000, late.`when`)
            }
            val summary = manager.activeNotifications.firstOrNull { it.tag == "beacon-quiet-summary" && it.id == 5 }
            assertNotNull("two quiet alerts share one summary", summary)
            assertEquals("2 alerts while you were away", summary!!.notification.extras.getString(Notification.EXTRA_TITLE))

            val approval = event("approval")
            receivePush(app, data("approval", approval, lateTime, nowSeconds + 300))
            assertNull("an approval that can still be answered keeps alerting, outside the quiet group", card(approval).group)
        } finally {
            events.forEach { manager.cancel(tag(it), 1) }
            manager.cancel("beacon-quiet-summary", 5)
            if (priorBinding == null) pushPrefs.edit().remove(bindingKey).commit() else pushPrefs.edit().putString(bindingKey, priorBinding).commit()
            if (priorAuth.owner.isBlank() && priorAuth.bearer == null && priorAuth.cookies.isEmpty()) kotlinx.coroutines.runBlocking { app.auth.remove(scope.origin) } else app.auth.write(priorAuth)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !hadNotifications) runCatching { uiAutomation.revokeRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
