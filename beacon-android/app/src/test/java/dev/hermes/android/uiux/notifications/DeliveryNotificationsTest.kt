package dev.hermes.android.uiux.notifications

import android.app.ApplicationExitInfo
import android.app.NotificationManager
import dev.hermes.android.push.AlertBurst
import dev.hermes.android.push.BURST_ALERTS
import dev.hermes.android.push.BURST_WINDOW_MILLIS
import dev.hermes.android.push.KEEP_CONNECTED_HELD_RETRY_MILLIS
import dev.hermes.android.push.KeepConnectedCursors
import dev.hermes.android.push.KeepConnectedCycle
import dev.hermes.android.push.KeepConnectedPage
import dev.hermes.android.push.KeepConnectedPoller
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.OLD_ALERT_MILLIS
import dev.hermes.android.push.STOP_REMEMBERED_MILLIS
import dev.hermes.android.push.TEST_ARRIVAL_WAIT_MILLIS
import dev.hermes.android.push.alertTimeMillis
import dev.hermes.android.push.arrivesQuietly
import dev.hermes.android.push.awaitTestArrival
import dev.hermes.android.push.explainingStop
import dev.hermes.android.push.keepConnectedPushData
import dev.hermes.android.push.processStopLabel
import dev.hermes.android.ui.ARRIVAL_FRESH_MILLIS
import dev.hermes.android.ui.Ago
import dev.hermes.android.ui.DeliveryInputs
import dev.hermes.android.ui.DeliveryState
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.alertChannelsMuted
import dev.hermes.android.ui.arrivalConfirmed
import dev.hermes.android.ui.deliveryAgo
import dev.hermes.android.ui.deliveryState
import dev.hermes.android.ui.deliveryStatus
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeliveryNotificationsTest {
    private val now = 1_800_000_000_000L
    private val healthy = DeliveryInputs(
        firebase = true, hasTarget = true, registered = true,
        keepConnected = true, keepStatus = KeepConnectedService.STATUS_CONNECTED,
        lastAlertAt = now - 60_000, now = now,
    )

    // --- The hero: Ok only when every check holds, otherwise the first one that fails ---

    @Test fun okOnlyWhenEveryCheckHolds() {
        assertEquals(DeliveryState.Ok, deliveryState(healthy))
        assertEquals(SettingsStatus.Ok, deliveryStatus(DeliveryState.Ok))
        // Keep connected may be off: the poll check then does not apply.
        assertEquals(DeliveryState.Ok, deliveryState(healthy.copy(keepConnected = false, keepStatus = KeepConnectedService.STATUS_OFF)))
    }

    @Test fun eachFailingCheckBreaksOkAndNamesItself() {
        mapOf(
            healthy.copy(statusLoaded = false) to DeliveryState.Checking,
            healthy.copy(statusError = true) to DeliveryState.Unreachable,
            healthy.copy(serverReady = false) to DeliveryState.ServerSetup,
            healthy.copy(deviceAllowed = false) to DeliveryState.PhoneOff,
            healthy.copy(channelsMuted = true) to DeliveryState.Muted,
            healthy.copy(registered = false, hasTarget = false) to DeliveryState.Off,
            healthy.copy(registered = false, hasTarget = true) to DeliveryState.NotWorking,
            healthy.copy(backgroundLimited = true) to DeliveryState.Limited,
            healthy.copy(keepStatus = KeepConnectedService.STATUS_RECONNECTING) to DeliveryState.Reconnecting,
            healthy.copy(keepStatus = KeepConnectedService.STATUS_SIGNED_OUT) to DeliveryState.SignedOut,
            healthy.copy(keepStatus = KeepConnectedService.STATUS_STARTING) to DeliveryState.Starting,
            healthy.copy(lastAlertAt = 0) to DeliveryState.Unconfirmed,
            healthy.copy(lastAlertAt = now - ARRIVAL_FRESH_MILLIS - 1) to DeliveryState.Unconfirmed,
        ).forEach { (inputs, expected) -> assertEquals(expected, deliveryState(inputs)) }
    }

    @Test fun whenSeveralChecksFailTheEarliestInTheOrderWins() {
        val everythingWrong = healthy.copy(
            deviceAllowed = false, channelsMuted = true, backgroundLimited = true,
            keepStatus = KeepConnectedService.STATUS_RECONNECTING, lastAlertAt = 0,
        )
        assertEquals(DeliveryState.PhoneOff, deliveryState(everythingWrong))
        assertEquals(DeliveryState.Muted, deliveryState(everythingWrong.copy(deviceAllowed = true)))
        assertEquals(DeliveryState.Limited, deliveryState(everythingWrong.copy(deviceAllowed = true, channelsMuted = false)))
        assertEquals(DeliveryState.Reconnecting, deliveryState(everythingWrong.copy(deviceAllowed = true, channelsMuted = false, backgroundLimited = false)))
        assertEquals(DeliveryState.Unconfirmed, deliveryState(everythingWrong.copy(deviceAllowed = true, channelsMuted = false, backgroundLimited = false, keepStatus = KeepConnectedService.STATUS_CONNECTED)))
        // An unreachable server is reported before anything the phone could fix.
        assertEquals(DeliveryState.Unreachable, deliveryState(everythingWrong.copy(statusError = true)))
    }

    @Test fun withoutFirebaseTheHeroFollowsKeepConnectedAndNeedsNoTest() {
        val noFirebase = DeliveryInputs(firebase = false, keepConnected = true, keepStatus = KeepConnectedService.STATUS_CONNECTED, now = now)
        assertEquals(DeliveryState.Ok, deliveryState(noFirebase))
        assertEquals(DeliveryState.Off, deliveryState(noFirebase.copy(keepConnected = false, keepStatus = KeepConnectedService.STATUS_OFF)))
        // Keep connected off comes first: turning it on matters before the phone's own allowances.
        assertEquals(DeliveryState.Off, deliveryState(noFirebase.copy(keepConnected = false, deviceAllowed = false)))
        assertEquals(DeliveryState.PhoneOff, deliveryState(noFirebase.copy(deviceAllowed = false)))
        assertEquals(DeliveryState.Limited, deliveryState(noFirebase.copy(backgroundLimited = true)))
        assertEquals(DeliveryState.Reconnecting, deliveryState(noFirebase.copy(keepStatus = KeepConnectedService.STATUS_RECONNECTING)))
    }

    @Test fun everyStateHasOneOfTheFiveStatuses() {
        val expected = mapOf(
            DeliveryState.Checking to SettingsStatus.Working, DeliveryState.Starting to SettingsStatus.Working,
            DeliveryState.Unreachable to SettingsStatus.Error, DeliveryState.SignedOut to SettingsStatus.Error,
            DeliveryState.Off to SettingsStatus.Off, DeliveryState.Ok to SettingsStatus.Ok,
        )
        DeliveryState.entries.forEach { state -> assertEquals(state.name, expected[state] ?: SettingsStatus.Attention, deliveryStatus(state)) }
    }

    @Test fun anArrivalStaysConfirmedForAWeek() {
        assertFalse(arrivalConfirmed(0, now))
        assertTrue(arrivalConfirmed(now - 1_000, now))
        assertTrue(arrivalConfirmed(now - ARRIVAL_FRESH_MILLIS, now))
        assertFalse(arrivalConfirmed(now - ARRIVAL_FRESH_MILLIS - 1, now))
        assertFalse("a clock that moved back confirms nothing", arrivalConfirmed(now + 1_000, now))
    }

    @Test fun agoNamesTheRightUnit() {
        assertEquals(Ago.Never to 0L, deliveryAgo(now, 0))
        assertEquals(Ago.Now to 0L, deliveryAgo(now, now - 59_000))
        assertEquals(Ago.Minutes to 1L, deliveryAgo(now, now - 60_000))
        assertEquals(Ago.Minutes to 59L, deliveryAgo(now, now - 3_599_000))
        assertEquals(Ago.Hours to 5L, deliveryAgo(now, now - 5 * 3_600_000L))
        assertEquals(Ago.Days to 2L, deliveryAgo(now, now - 49 * 3_600_000L))
    }

    @Test fun aBlockedChannelMutesAlertsButALowOneDoesNot() {
        assertFalse(alertChannelsMuted(emptyList()))
        assertFalse(alertChannelsMuted(listOf(NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_LOW, null)))
        assertTrue(alertChannelsMuted(listOf(NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_NONE)))
    }

    // --- The test button waits for its own event ---

    @Test fun theTestWaitEndsAtOnceWhenTheEventArrives() = runTest {
        var checks = 0
        val arrival = awaitTestArrival { checks++; if (checks == 4) 1234L else null }
        assertEquals(1234L, arrival)
        assertEquals(4, checks)
        assertEquals("three steps of 250 ms", 750L, currentTime)
    }

    @Test fun theTestWaitGivesUpAfterFifteenSeconds() = runTest {
        var checks = 0
        assertNull(awaitTestArrival { checks++; null })
        assertEquals(TEST_ARRIVAL_WAIT_MILLIS, currentTime)
        assertEquals((TEST_ARRIVAL_WAIT_MILLIS / 250 + 1).toInt(), checks)
    }

    @Test fun anEventThatArrivedBeforeTheFirstCheckIsNotMissed() = runTest {
        assertEquals(7L, awaitTestArrival { 7L })
        assertEquals(0L, currentTime)
    }

    // --- Why Keep connected stopped ---

    @Test fun onlyRealStopsExplainAStoppedService() {
        listOf(ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED, ApplicationExitInfo.REASON_OTHER, ApplicationExitInfo.REASON_FREEZER)
            .forEach { assertEquals("reason $it", "Stopped by Android", processStopLabel(it)) }
        listOf(ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED).forEach { assertEquals("Force-stopped", processStopLabel(it)) }
        listOf(ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_PERMISSION_CHANGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
            .forEach { assertNull("reason $it", processStopLabel(it)) }
    }

    @Test fun aStopShowsOnlyWhileKeepConnectedIsOnDownAndTheStopIsRecent() {
        val stop = "Stopped by Android" to now - 1_000
        val down = KeepConnectedService.STATUS_RECONNECTING
        assertEquals("Stopped by Android", explainingStop(stop, true, down, now))
        assertNull("connected again", explainingStop(stop, true, KeepConnectedService.STATUS_CONNECTED, now))
        assertNull("switched off", explainingStop(stop, false, down, now))
        assertNull("no stop", explainingStop(null, true, down, now))
        assertNull("too old", explainingStop("Stopped by Android" to now - STOP_REMEMBERED_MILLIS - 1, true, down, now))
        assertNull("from the future", explainingStop("Stopped by Android" to now + 1, true, down, now))
    }

    // --- C-A: the time of an alert, and quiet delivery ---

    @Test fun theEventsCreatedTimeBecomesCreatedAtInWholeSeconds() {
        val event = JSONObject().put("source_ref", "s").put("event_id", "e").put("conversation_ref", "c").put("kind", "completion").put("created", 1_799_999_000.75)
        assertEquals("1799999000", keepConnectedPushData(event)["created_at"])
        assertEquals("a Hermes without the field sends none", false, keepConnectedPushData(JSONObject().put("event_id", "e")).containsKey("created_at"))
        listOf(0.0, -5.0).forEach { assertFalse("created=$it", keepConnectedPushData(JSONObject().put("created", it)).containsKey("created_at")) }
        assertFalse(keepConnectedPushData(JSONObject().put("created", "later")).containsKey("created_at"))
    }

    @Test fun anAlertShowsWhenItHappenedAndNeverInTheFuture() {
        assertEquals(1_799_999_000_000L, alertTimeMillis("1799999000", now))
        assertEquals(now, alertTimeMillis(null, now))
        assertEquals(now, alertTimeMillis("", now))
        assertEquals(now, alertTimeMillis("soon", now))
        assertEquals(now, alertTimeMillis("0", now))
        assertEquals(now, alertTimeMillis("-30", now))
        assertEquals("a clock ahead of the phone", now, alertTimeMillis("1800000100", now))
        assertEquals("overflow", now, alertTimeMillis(Long.MAX_VALUE.toString(), now))
    }

    @Test fun oldAlertsAndBurstsArriveQuietly() {
        listOf("completion", "failure", "task_result", "test").forEach { kind ->
            assertFalse(kind, arrivesQuietly(kind, now, null, now, 0))
            assertFalse("exactly at the limit still alerts", arrivesQuietly(kind, now - OLD_ALERT_MILLIS, null, now, 0))
            assertTrue("$kind just older than the limit", arrivesQuietly(kind, now - OLD_ALERT_MILLIS - 1, null, now, 0))
            assertTrue("$kind eight hours late", arrivesQuietly(kind, now - 8 * 3_600_000L, null, now, 0))
            assertFalse("$kind with room in the burst", arrivesQuietly(kind, now, null, now, BURST_ALERTS - 1))
            assertTrue("$kind in a burst", arrivesQuietly(kind, now, null, now, BURST_ALERTS))
        }
    }

    @Test fun approvalsAndQuestionsKeepAlertingWhileTheyCanStillBeAnswered() {
        listOf("approval", "clarify").forEach { kind ->
            val hoursAgo = now - 5 * 3_600_000L
            assertFalse("$kind open and old", arrivesQuietly(kind, hoursAgo, now / 1_000 + 60, now, BURST_ALERTS))
            assertFalse("$kind with no expiry given", arrivesQuietly(kind, hoursAgo, null, now, BURST_ALERTS))
            assertFalse("$kind with an expiry of 0", arrivesQuietly(kind, hoursAgo, 0, now, BURST_ALERTS))
            assertTrue("$kind past its expiry cannot be answered", arrivesQuietly(kind, hoursAgo, now / 1_000 - 1, now, 0))
        }
    }

    @Test fun theBurstWindowForgetsAlertsOlderThanTenSeconds() {
        val start = now + 10 * BURST_WINDOW_MILLIS
        assertEquals(0, AlertBurst.recent(start))
        AlertBurst.note(start)
        AlertBurst.note(start + 1_000)
        AlertBurst.note(start + 2_000)
        assertEquals(BURST_ALERTS, AlertBurst.recent(start + 3_000))
        assertEquals("the first fell out of the window", BURST_ALERTS - 1, AlertBurst.recent(start + BURST_WINDOW_MILLIS + 1))
        assertEquals(0, AlertBurst.recent(start + 3 * BURST_WINDOW_MILLIS))
    }

    // --- Alerts that cannot be shown are held, not consumed ---

    private fun cursorStore(values: MutableMap<String, String>) = KeepConnectedCursors(read = values::get, write = { key, value -> values[key] = value; true })

    @Test fun pagesWaitUnreadWhileAlertsCannotBeShownAndArriveOnceTheyCan() = runTest {
        val values = mutableMapOf<String, String>()
        val store = cursorStore(values)
        store.advance("https://hermes.example", "owner", "default", 3)
        var presentable = false
        val backlog = mutableListOf<Int>()
        val delivered = mutableListOf<String>()
        val polledAfter = mutableListOf<Long>()
        val page = KeepConnectedPage(listOf(JSONObject().put("event_id", "a"), JSONObject().put("event_id", "b")), 5)
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example", owner = "owner", cursors = store, profiles = { listOf("default") },
            poll = { _, after -> polledAfter += after; if (after < 5) page else KeepConnectedPage(emptyList(), after) },
            onEvent = { _, event -> delivered += event.getString("event_id") },
            canPresent = { presentable }, onBacklog = { backlog += it },
        )

        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertTrue("nothing was handed over", delivered.isEmpty())
        assertEquals("the cursor did not move", 3L, store.read("https://hermes.example", "owner", "default"))
        assertEquals(listOf(2), backlog)
        assertTrue("it waits instead of asking again at once", currentTime >= KEEP_CONNECTED_HELD_RETRY_MILLIS)

        presentable = true
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(listOf(3L, 3L), polledAfter)
        assertEquals("both arrive, in order", listOf("a", "b"), delivered)
        assertEquals(5L, store.read("https://hermes.example", "owner", "default"))
        assertEquals("the backlog is cleared", listOf(2, 0), backlog)
    }

    @Test fun anEmptyPageNeverCountsAsABacklogAndAdvancesAsBefore() = runTest {
        val values = mutableMapOf<String, String>()
        val store = cursorStore(values)
        store.advance("https://hermes.example", "owner", "default", 3)
        val backlog = mutableListOf<Int>()
        val poller = KeepConnectedPoller(
            origin = "https://hermes.example", owner = "owner", cursors = store, profiles = { listOf("default") },
            poll = { _, after -> KeepConnectedPage(emptyList(), after + 2) },
            onEvent = { _, _ -> error("no events expected") },
            canPresent = { false }, onBacklog = { backlog += it },
        )
        assertEquals(KeepConnectedCycle.Connected, poller.pollOnce())
        assertEquals(5L, store.read("https://hermes.example", "owner", "default"))
        assertTrue(backlog.isEmpty())
    }
}
