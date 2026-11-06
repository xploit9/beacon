package dev.hermes.android.push

import android.app.Notification
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BackgroundActivityFixture
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.json
import dev.hermes.android.ui.arrivalConfirmed
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class BackgroundActivityNotificationDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Test fun actualWorkingPushAndLatePreviewNeverPostOrChangeAlreadyCreatedChannels() {
        BackgroundActivityFixture(app).use { fixture ->
            fixture.postLegacyWorkingCard()
            val channels = fixture.channelSettings()
            listOf(false, true).forEach { visible ->
                app.calls.uiVisible = visible
                DeliveryLog.recordArrival(app, "completion", "synthetic-prior-alert", 0L)
                DeliveryLog.markHeard(app, 0L)
                val dropped = System.currentTimeMillis() - 1_000
                DeliveryLog.recordDropped(app, dropped)
                val contactStarted = System.currentTimeMillis()
                assertNull(receivePush(app, fixture.data("working"), null, deferPreview = true))
                assertEquals("A suppressed working event cannot certify an alert", 0L, DeliveryLog.lastAlertAt(app))
                assertFalse(arrivalConfirmed(DeliveryLog.lastAlertAt(app), System.currentTimeMillis()))
                assertTrue("Working still records contact", DeliveryLog.accountPreferences(app).getLong("heard_at", 0L) >= contactStarted)
                assertEquals("Working preserves a missed-alert marker", dropped, DeliveryLog.droppedAt(app))
                assertTrue(DeliveryLog.droppedAt(app) > DeliveryLog.lastAlertAt(app))
                fixture.await { fixture.manager.activeNotifications.none { it.tag == fixture.nativeTag && it.id == 3 } }
                assertNull(fixture.card(fixture.event))
                presentNativeActivity(app, fixture.source, fixture.event, fixture.activity, "working", System.currentTimeMillis() / 1000 + 120,
                    json("preview" to json("title" to "Synthetic work", "body" to "Still working")))
                assertTrue(fixture.manager.activeNotifications.none { it.tag == fixture.nativeTag && it.id == 3 })
            }
            assertEquals("Start suppression preserves every existing channel setting", channels, fixture.channelSettings())
        }
    }

    @Test fun repliesFailuresResultsAndActionRequiredCardsRemainAvailableAndSettleNormally() {
        BackgroundActivityFixture(app).use { fixture ->
            app.calls.uiVisible = true
            listOf("completion", "failure", "task_result").forEach { kind ->
                val id = "$kind-${fixture.event}"
                DeliveryLog.recordArrival(app, "completion", "synthetic-prior-alert", 0L)
                val sentAt = System.currentTimeMillis()
                assertNotNull(receivePush(app, fixture.data(kind, id).minus("activity_ref"), null, deferPreview = true))
                assertTrue("$kind remains a real alert arrival", DeliveryLog.lastAlertAt(app) >= sentAt)
                assertTrue(arrivalConfirmed(DeliveryLog.lastAlertAt(app), System.currentTimeMillis()))
                fixture.await { fixture.card(id) != null }
                val posted = checkNotNull(fixture.card(id))
                assertNotNull(posted.contentIntent)
                assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
            }
            app.calls.uiVisible = false
            val backgroundReply = "background-${fixture.event}"
            assertNotNull(receivePush(app, fixture.data("completion", backgroundReply).minus("activity_ref"), null, deferPreview = true))
            fixture.await { fixture.card(backgroundReply) != null }
            assertTrue(silentPushNotification("completion", true))
            assertFalse(silentPushNotification("completion", false))
            listOf("approval", "clarify", "incoming_call").forEach { assertFalse(silentPushNotification(it, false)) }
            listOf("approval", "clarify").forEach { kind ->
                DeliveryLog.recordArrival(app, "completion", "synthetic-prior-alert", 0L)
                val sentAt = System.currentTimeMillis()
                val id = "$kind-${fixture.event}"
                assertNotNull(receivePush(app, fixture.data(kind, id).plus("activity_ref" to "${fixture.activity}-$kind"), null, deferPreview = true))
                assertTrue("$kind still records an action-required arrival", DeliveryLog.lastAlertAt(app) >= sentAt)
                fixture.await { fixture.manager.activeNotifications.any { it.id == 3 && it.notification.extras.getString("native_source") == fixture.source } }
            }
            val route = json("kind" to "approval", "preview" to json("complete" to true, "title" to "Synthetic approval", "body" to "Review synthetic action"),
                "actions" to JSONArray().put(json("value" to "deny", "label" to "Deny")))
            presentNativeActivity(app, fixture.source, fixture.event, fixture.activity, "approval", System.currentTimeMillis() / 1000 + 120, route)
            fixture.await { fixture.manager.activeNotifications.any { it.tag == fixture.nativeTag && it.id == 3 } }
            val approval = fixture.manager.activeNotifications.single { it.tag == fixture.nativeTag && it.id == 3 }.notification
            assertTrue(approval.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertEquals(listOf("Deny"), approval.actions.map { it.title.toString() })
            presentNativeActivity(app, fixture.source, fixture.event, fixture.activity, "activity_end", System.currentTimeMillis() / 1000 + 120)
            fixture.await { fixture.manager.activeNotifications.none { it.tag == fixture.nativeTag && it.id == 3 } }
            presentNativeActivity(app, fixture.source, fixture.event, fixture.activity, "approval", System.currentTimeMillis() / 1000 + 120, route)
            assertTrue("Settled actions cannot be resurrected", fixture.manager.activeNotifications.none { it.tag == fixture.nativeTag && it.id == 3 })
        }
    }

    @Test fun syntheticDeliveryLogPreferencesAreRestoredAfterTheFixtureCloses() {
        val prefs = DeliveryLog.accountPreferences(app)
        val previous = prefs.all.toMap()
        BackgroundActivityFixture(app).use { fixture ->
            app.calls.uiVisible = true
            val id = "test-${fixture.event}"
            assertNotNull(receivePush(app, fixture.data("test", id).minus("activity_ref"), null, deferPreview = true))
            fixture.await { fixture.card(id) != null }
            assertNotNull(DeliveryLog.testArrivedAt(app, id))
            DeliveryLog.markHeard(app, System.currentTimeMillis())
            DeliveryLog.recordDropped(app, System.currentTimeMillis() - 1)
        }
        assertEquals("The fixture must leave no synthetic delivery state", previous, prefs.all)
    }
}
