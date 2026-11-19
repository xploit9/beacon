package dev.hermes.android

import dev.hermes.android.data.ApiFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Demo, fix 6 (16 Nov 2026): the Beacon chat's create was cut off before its answer came. The phone kept the marker "unknown" and refused every later
 * open ("Beacon opening outcome is unknown. Review history before creating another conversation.", shown as "Can't open Beacon · Hermes couldn't do
 * that. Try again."), its Retry too, and a side-button call then waited for a chat that never opened. Nothing ever cleared the marker. Hermes' create
 * takes a request id as its retry identity (an exact retry returns the chat the first create made), so the marker now holds that id and the next open
 * repeats the same create; a marker without one no longer blocks Beacon, and an id Hermes itself refuses (its chat deleted meanwhile) starts one new create.
 */
class BeaconOpeningTest {
    private val earlier = "6c1d0b2a-9e8f-4a7b-b6c5-d4e3f2a1b0c9"

    @Test fun aCreateCutOffBeforeItsAnswerIsRepeatedWithItsOwnRequestId() {
        assertEquals("the same create, so Hermes returns the chat it made", earlier, beaconOpeningKept(BEACON_OPENING_REQUEST + earlier))
    }

    @Test fun aMarkerThatNamesNoRequestNoLongerBlocksBeacon() {
        // What 1.2.0 (and 36c3959c) left after a create that was cut off: it once blocked the Beacon chat for good. Now a new create starts.
        assertNull(beaconOpeningKept("unknown"))
    }

    @Test fun noMarkerStartsANewCreate() {
        assertNull(beaconOpeningKept(null))
    }

    @Test fun aMarkerThatIsNotARequestIdStartsANewCreate() {
        listOf(BEACON_OPENING_REQUEST, BEACON_OPENING_REQUEST + "../../x", BEACON_OPENING_REQUEST + "short", BEACON_OPENING_REQUEST + "a".repeat(65), "request-$earlier")
            .forEach { assertNull(it, beaconOpeningKept(it)) }
    }

    @Test fun hermesRefusingAKeptIdStartsOverButALostAnswerOrARestartKeepsIt() {
        // Hermes' own refusals go out as {code: 4001, message: <reason>} (gateway/session_controls.py): a deleted chat's id ("not_found", hermes_state_local.py retires it),
        // a changed request ("invalid_params", gateway/session_local.py), a held route ("admission_conflict").
        listOf("not_found", "invalid_params", "admission_conflict", "storage_unavailable").forEach { assertTrue(it, beaconOpeningRetryRefused(ApiFailure(4001, it))) }
        // Hermes restarting: the same create works once it is back. A refusal of the connection itself (before Hermes reads the id; REVIEW-F6
        // P3-2): a new id is refused the same way, so the kept one stays.
        listOf("runtime_draining", "permission_denied", "profile_mismatch").forEach { assertFalse(it, beaconOpeningRetryRefused(ApiFailure(4001, it))) }
        // Not Hermes refusing the id: a lost or failed answer on the way, the plugin, an HTTP error.
        assertFalse(beaconOpeningRetryRefused(java.io.IOException("Connection reset")))
        assertFalse(beaconOpeningRetryRefused(ApiFailure(503, "Service unavailable")))
        assertFalse(beaconOpeningRetryRefused(ApiFailure(404, "Not found")))
        assertFalse(beaconOpeningRetryRefused(ApiFailure(4001, "Plugin not authorized", plugin = true)))
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run from the Android project"
    }

    /** resolveBeacon needs Android to run, so its wiring is read from source (as BeaconStaleChatTest does). */
    @Test fun aBeaconChatCreateNamesItsRequestIdKeepsItUntilTheAnswerAndNeverRefusesForAnOldOne() {
        val vm = File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val resolve = vm.substringAfter("private suspend fun resolveBeacon(").substringBefore("\n    }\n")
        assertFalse("an old marker never refuses the open", resolve.contains("require(readBeaconOpening(c.scope) == null)"))
        assertFalse(vm.contains("opening outcome is unknown"))
        val request = resolve.indexOf("val kept = beaconOpeningKept(readBeaconOpening(c.scope))")
        val marker = resolve.indexOf("app.cache.write(c.scope, \"beacon-opening\", BEACON_OPENING_REQUEST + request, true)")
        val create = resolve.indexOf("c.post(\"/api/beacon/open\", open(request))")
        assertTrue("the marker names the request before the create goes out", request in 0 until marker && marker < create)
        assertTrue("the create carries it", resolve.contains("put(\"request_id\", request)"))
        assertTrue("only a kept id Hermes refused starts over, once", resolve.contains("if (kept == null || !beaconOpeningRetryRefused(e)) throw e"))
        assertTrue("the answer clears the marker, as before", resolve.contains("app.cache.delete(c.scope, \"beacon-opening\")"))
    }
}
