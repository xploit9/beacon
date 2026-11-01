package dev.hermes.android.uiux.gaps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.push.DeliveryLog
import dev.hermes.android.push.KeepConnectedService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * What the phone itself remembers about alerts (Notifications > Last alert, Heard from Hermes, held alerts). It writes the app's own
 * `notification-delivery` file, so each test starts from an empty file and puts the earlier contents back afterwards.
 */
class DeliveryLogGapsDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("notification-delivery", Context.MODE_PRIVATE)
    private var saved: Map<String, Any?> = emptyMap()

    // The Keep connected poll's own "heard" time counts as hearing from Hermes too; clearing the log clears it, so the test puts it back.
    private val keep get() = context.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
    private var keptHeard: Long? = null

    @Before fun startEmpty() {
        saved = prefs.all
        keptHeard = keep.all[KeepConnectedService.KEY_HEARD_AT] as? Long
        assertTrue(prefs.edit().clear().commit())
    }

    @After fun putBackWhatWasThere() {
        keep.edit().apply { keptHeard?.let { putLong(KeepConnectedService.KEY_HEARD_AT, it) } ?: remove(KeepConnectedService.KEY_HEARD_AT) }.commit()
        val edit = prefs.edit().clear()
        saved.forEach { (key, value) ->
            when (value) {
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
            }
        }
        edit.commit()
    }

    @Test fun anEmptyLogHasNothingToReport() {
        assertEquals(0L, DeliveryLog.lastAlertAt(context))
        assertEquals(0, DeliveryLog.held(context))
        assertEquals(0L, DeliveryLog.droppedAt(context))
        assertNull(DeliveryLog.lastStop(context))
        assertNull(DeliveryLog.testArrivedAt(context, "any-event"))
    }

    @Test fun anAlertRemembersWhenItArrivedAndATestAlsoRemembersItsEvent() {
        DeliveryLog.recordArrival(context, "alert", "event-1", now = 1_000)
        assertEquals(1_000L, DeliveryLog.lastAlertAt(context))
        assertNull("A real alert is not a test arrival", DeliveryLog.testArrivedAt(context, "event-1"))
        DeliveryLog.recordArrival(context, "test", "event-2", now = 2_000)
        assertEquals(2_000L, DeliveryLog.lastAlertAt(context))
        assertEquals(2_000L, DeliveryLog.testArrivedAt(context, "event-2"))
    }

    @Test fun onlyTheTestsOwnEventConfirmsItAndALaterAlertDoesNotForgetIt() {
        DeliveryLog.recordArrival(context, "test", "event-2", now = 2_000)
        assertNull("Another event is not this test", DeliveryLog.testArrivedAt(context, "event-1"))
        assertNull("A blank event never matches", DeliveryLog.testArrivedAt(context, ""))
        DeliveryLog.recordArrival(context, "alert", "event-3", now = 3_000)
        assertEquals(3_000L, DeliveryLog.lastAlertAt(context))
        assertEquals("The test arrival stays confirmed after a later alert", 2_000L, DeliveryLog.testArrivedAt(context, "event-2"))
    }

    @Test fun aNewerTestReplacesTheOlderOnesEvent() {
        DeliveryLog.recordArrival(context, "test", "event-2", now = 2_000)
        DeliveryLog.recordArrival(context, "test", "event-4", now = 4_000)
        assertNull(DeliveryLog.testArrivedAt(context, "event-2"))
        assertEquals(4_000L, DeliveryLog.testArrivedAt(context, "event-4"))
    }

    @Test fun heardFromHermesIsTheNewestSignOfLife() {
        DeliveryLog.markHeard(context, now = Long.MAX_VALUE / 2)
        assertEquals("The newest of the poll answer, the status read and the last alert wins", Long.MAX_VALUE / 2, DeliveryLog.heardAt(context))
        DeliveryLog.markHeard(context, now = 100)
        DeliveryLog.recordArrival(context, "alert", "event-5", now = 9_000)
        assertTrue("An alert counts as hearing from Hermes", DeliveryLog.heardAt(context) >= 9_000)
    }

    @Test fun heldAlertsNeverCountBelowZeroAndDropsRememberTheirTime() {
        DeliveryLog.setHeld(context, 4)
        assertEquals(4, DeliveryLog.held(context))
        DeliveryLog.setHeld(context, -3)
        assertEquals(0, DeliveryLog.held(context))
        DeliveryLog.recordDropped(context, now = 777)
        assertEquals(777L, DeliveryLog.droppedAt(context))
    }
    @Test fun clearingForgetsTheAccountsTimesButNotWhyTheProcessStopped() {
        assertTrue(keep.edit().putLong(KeepConnectedService.KEY_HEARD_AT, 5_000).commit())
        DeliveryLog.recordArrival(context, "test", "event-9", now = 9_000)
        DeliveryLog.markHeard(context, now = 8_000)
        DeliveryLog.setHeld(context, 2)
        DeliveryLog.recordDropped(context, now = 7_000)
        assertTrue(prefs.edit().putString("stop_label", "Closed by Android").putLong("stop_at", 6_000).commit())
        assertEquals(9_000L, DeliveryLog.heardAt(context))
        DeliveryLog.clearAccountTimes(context)
        assertEquals(0L, DeliveryLog.lastAlertAt(context))
        assertEquals("Neither the status read, the poll's answer nor an alert is left to hear", 0L, DeliveryLog.heardAt(context))
        assertEquals(0, DeliveryLog.held(context))
        assertEquals(0L, DeliveryLog.droppedAt(context))
        assertNull(DeliveryLog.testArrivedAt(context, "event-9"))
        assertEquals("Why the process stopped belongs to the phone, not the account", "Closed by Android" to 6_000L, DeliveryLog.lastStop(context))
    }

    @Test fun signingOutClearsThemEvenWhenKeepConnectedWasNeverOn() {
        DeliveryLog.recordArrival(context, "alert", "event-10", now = 3_000)
        DeliveryLog.markHeard(context, now = 4_000)
        // An origin Keep connected never ran for: it stops nothing, but the next account must not read this account's times.
        KeepConnectedService.onUserSignedOut(context, "https://gaps.example.com")
        assertEquals(0L, DeliveryLog.lastAlertAt(context))
        assertEquals(0L, DeliveryLog.heardAt(context))
    }
}
