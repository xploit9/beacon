package dev.hermes.android

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.core.digest
import dev.hermes.android.data.AuthContext
import dev.hermes.android.push.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

/** Android system notifications only, synthetic identities; no service/model/mail/network call. */
class NativeActivityDeviceTest {
    @Test fun ongoingRequestHasPrivateActionsFallbackAndLifecycle() = exercise("approval")
    @Test fun questionOffersChoicesAndFreeTextWithPrivateLifecycle() = exercise("clarify")
    private fun exercise(kind:String) {
        val context = ApplicationProvider.getApplicationContext<CypherApplication>()
        val manager = context.getSystemService(NotificationManager::class.java)
        val source = "synthetic-activity-source"
        val event = "synthetic-approval-event"
        val activity = "synthetic-activity-${System.currentTimeMillis()}"
        val origin = "https://notification-fixture.invalid"
        val previousAuth = context.auth.read(origin)
        val auth = AuthContext(origin, emptyList(), "synthetic-owner", "default", backend = "hermes-dashboard")
        val prefs = context.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE)
        val key = "source:${source.digest()}"
        val route = json("kind" to kind,"text_reply" to (kind=="clarify"),"preview" to json("complete" to true,"title" to "Beacon needs approval", "body" to "Read the synthetic test folder"),
            "actions" to JSONArray(listOf("once", "session", "always", "deny").map { json("value" to it, "label" to it) }))
        try {
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000+120, route)
            assertTrue("An unregistered source cannot post", manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            context.auth.write(auth)
            val binding = json("source" to source, "origin" to origin, "owner" to auth.owner, "profile" to auth.profile, "backend" to "hermes-dashboard")
            assertTrue(prefs.edit().putString(key, android.util.Base64.encodeToString(context.cipher.encrypt(binding.toString().toByteArray(), key), android.util.Base64.NO_WRAP)).commit())
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000+120, route)
            val deadline = System.currentTimeMillis()+3000
            while (manager.activeNotifications.none { it.notification.extras.getString("native_source") == source } && System.currentTimeMillis()<deadline) Thread.sleep(20)
            val posted = manager.activeNotifications.single { it.notification.extras.getString("native_source") == source }
            val notification = posted.notification
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals("Urgent requests must be eligible for heads-up popups",NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(notification.channelId).importance)
            assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
            assertEquals("Read the synthetic test folder", notification.extras.getString(Notification.EXTRA_TEXT))
            assertFalse(notification.publicVersion.extras.getString(Notification.EXTRA_TEXT).orEmpty().contains("folder"))
            assertEquals(if(kind=="approval") listOf("once", "session", "deny") else listOf("once","session","Answer"), notification.actions.map { it.title.toString() })
            if(kind=="clarify") assertEquals("answer",notification.actions.last().remoteInputs.single().resultKey)
            assertTrue(notification.actions.all { it.actionIntent.creatorPackage == context.packageName })
            if(Build.VERSION.SDK_INT>=31) assertTrue(notification.actions.all { it.isAuthenticationRequired })
            if (Build.VERSION.SDK_INT >= 36) {
                assertTrue(notification.extras.getBoolean("android.requestPromotedOngoing"))
                if(manager.canPostPromotedNotifications()) assertTrue(notification.hasPromotableCharacteristics())
            }
            route.getJSONObject("preview").put("complete",false)
            presentNativeActivity(context,source,event,activity,kind,System.currentTimeMillis()/1000+120,route)
            val incompleteDeadline=System.currentTimeMillis()+3000
            while(manager.activeNotifications.firstOrNull { it.notification.extras.getString("native_source")==source }?.notification?.actions?.none { it.title.toString()=="Review" }==true&&System.currentTimeMillis()<incompleteDeadline) Thread.sleep(20)
            val incomplete=manager.activeNotifications.single { it.notification.extras.getString("native_source")==source }.notification
            assertEquals(if(kind=="approval") listOf("deny","Review") else listOf("Review"),incomplete.actions.map { it.title.toString() })
            assertTrue("A hidden question must not offer blind text input",incomplete.actions.all { it.remoteInputs.isNullOrEmpty() })
            presentNativeActivity(context, source, "synthetic-settled-event", activity, "activity_end", System.currentTimeMillis()/1000+120)
            val cancelledDeadline = System.currentTimeMillis()+3000
            while (manager.activeNotifications.any { it.notification.extras.getString("native_source") == source } && System.currentTimeMillis()<cancelledDeadline) Thread.sleep(20)
            assertTrue("Android must remove the settled activity", manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            // A delayed authenticated preview or duplicate push cannot resurrect an answered request.
            presentNativeActivity(context,source,event,activity,kind,System.currentTimeMillis()/1000+120,route)
            Thread.sleep(100)
            assertTrue("Settled activity must remain gone after a late refresh",manager.activeNotifications.none { it.notification.extras.getString("native_source")==source })
            presentNativeActivity(context, source, event, activity, kind, System.currentTimeMillis()/1000-1, route)
            assertTrue(manager.activeNotifications.none { it.notification.extras.getString("native_source") == source })
            java.io.File(context.filesDir, "native-activity-probe.json").writeText(json("sdk" to Build.VERSION.SDK_INT, "actions" to JSONArray(notification.actions.map { it.title.toString() }), "private_visibility" to (notification.visibility == Notification.VISIBILITY_PRIVATE), "promotion_allowed" to if(Build.VERSION.SDK_INT>=36) manager.canPostPromotedNotifications() else false, "promotion_requested" to notification.extras.getBoolean("android.requestPromotedOngoing"), "promotable" to if(Build.VERSION.SDK_INT>=36) notification.hasPromotableCharacteristics() else false, "unauthorized_rejected" to true, "expired_rejected" to true, "settled_removed" to true).toString())
        } finally { cancelNativeActivities(context, source); prefs.edit().remove(key).commit(); context.auth.write(previousAuth) }
    }
}
