package dev.hermes.android.data

import dev.hermes.android.core.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class NativeIncidentsTest {
    private val owner = Scope("https://native.example", "owner-canary", "default", nativeProvider = "basic")
    private val first = IncidentAuthority(owner, "11111111-1111-4111-8111-111111111111", 7)
    private val second = IncidentAuthority(owner.copy(profile = "work"), "22222222-2222-4222-8222-222222222222", 7)
    private val returned = IncidentAuthority(owner, "33333333-3333-4333-8333-333333333333", 7)

    private class Store {
        var encrypted: ByteArray? = null
        var writable = true
        private val key = SecretKeySpec(ByteArray(16) { (it + 1).toByte() }, "AES")
        fun seal(value: ByteArray, binding: String): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(binding.toByteArray()) }
            return cipher.iv + cipher.doFinal(value)
        }
        fun open(value: ByteArray, binding: String): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12)))
            updateAAD(binding.toByteArray());doFinal(value.copyOfRange(12, value.size))
        }
        fun journal() = IncidentJournal({ encrypted?.copyOf() }, { value ->
            if (writable) { encrypted = value.copyOf(); true } else false
        }, ::seal, ::open)
    }

    private fun acknowledged(body: JSONObject) = JSONObject().put("incident_id", body.getString("incident_id")).put("recorded", true)

    @Test fun exceptionClassificationNeverUsesMessagesOrTreatsCancellationAsFailure() {
        assertEquals("timeout", incidentErrorCode(SocketTimeoutException("secret timeout")))
        assertEquals("dns", incidentErrorCode(UnknownHostException("secret host")))
        assertEquals("http_503", incidentErrorCode(ApiFailure(503, "secret response")))
        assertEquals("network", incidentErrorCode(IOException("secret network detail")))
        assertEquals("unknown", incidentErrorCode(IllegalStateException("secret error")))
        assertNull(incidentErrorCode(CancellationException("normal cancellation")))
    }

    @Test fun queueIsEncryptedAndTheWireContainsOnlyBoundedMetadata() = runBlocking {
        val store = Store();val journal = store.journal();assertTrue(journal.activate(first))
        val error = IllegalStateException("exception-secret-canary").apply {
            stackTrace = arrayOf(StackTraceElement("secret-class-canary", "secret-method-canary", "secret-file-canary", 42))
        }
        assertTrue(journal.record(first, "chat", "send", error, appVersion = 141))
        val disk = store.encrypted!!
        assertFalse(String(disk, Charsets.ISO_8859_1).contains("owner-canary"))
        assertFalse(String(disk, Charsets.ISO_8859_1).contains("exception-secret-canary"))
        var sent: JSONObject? = null
        assertEquals(1, journal.drain(first, { true }) { body -> sent = JSONObject(body.toString());acknowledged(body) })
        val body = requireNotNull(sent)
        assertEquals(setOf("incident_id", "incident"), body.keys().asSequence().toSet())
        val incident = body.getJSONObject("incident")
        assertEquals(setOf("component", "operation", "error_code", "app_version_code"), incident.keys().asSequence().toSet())
        assertEquals("chat", incident.getString("component"));assertEquals("send", incident.getString("operation"))
        assertEquals("outcome_unknown", incident.getString("error_code"));assertEquals(141, incident.getInt("app_version_code"))
        for (canary in listOf("exception-secret-canary", "secret-class-canary", "secret-method-canary", "secret-file-canary", "owner-canary", "native.example"))
            assertFalse(canary, body.toString().contains(canary))
    }

    @Test fun only_fixed_categories_and_positive_versions_can_be_queued() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        for ((component, operation) in listOf("private-canary" to "send", "chat" to "private-canary"))
            assertFalse(journal.record(first, component, operation, IOException("private-canary"), appVersion = 141))
        assertFalse(journal.record(first, "chat", "send", IOException(), provider = "private-canary", appVersion = 141))
        assertFalse(journal.record(first, "chat", "send", IOException(), appVersion = 0))
        assertFalse(journal.record(first, "chat", "send", CancellationException("normal"), appVersion = 141))
        assertEquals(0, journal.drain(first, { true }) { fail("Unexpected upload");JSONObject() })
    }

    @Test fun mutation_failures_remain_unknown_while_read_failures_keep_precise_codes() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        val mutations = listOf("send", "save", "update", "fill", "signup", "payment", "invoke_tool")
        for (operation in mutations) assertTrue(journal.record(first, "app", operation, SocketTimeoutException("private")))
        assertTrue(journal.record(first, "app", "load", SocketTimeoutException("private")))
        val found = mutableMapOf<String, String>()
        assertEquals(8, journal.drain(first, { true }) { body ->
            val incident = body.getJSONObject("incident");found[incident.getString("operation")] = incident.getString("error_code")
            acknowledged(body)
        })
        assertTrue(mutations.all { found[it] == "outcome_unknown" });assertEquals("timeout", found["load"])
    }

    @Test fun acknowledgement_must_match_the_same_incident_and_retry_reuses_its_identity() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "voice", "discover_tools", SocketTimeoutException(), appVersion = 141))
        var identity = ""
        assertEquals(0, journal.drain(first, { true }) { body -> identity = body.getString("incident_id");JSONObject().put("incident_id", "foreign").put("recorded", true) })
        assertEquals(0, journal.drain(first, { true }) { body -> assertEquals(identity, body.getString("incident_id"));JSONObject().put("incident_id", identity).put("recorded", false) })
        assertEquals(1, journal.drain(first, { true }) { body -> assertEquals(identity, body.getString("incident_id"));acknowledged(body) })
        assertEquals(0, journal.drain(first, { true }) { fail("Acknowledged item replayed");JSONObject() })
    }

    @Test fun transport_failure_keeps_the_same_item_without_recording_another_incident() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "app", "request", IOException("private")))
        var identity = ""
        assertEquals(0, journal.drain(first, { true }) { body -> identity = body.getString("incident_id");throw IOException("upload-secret-canary") })
        val bodies = mutableListOf<JSONObject>()
        assertEquals(1, journal.drain(first, { true }) { body -> bodies += body;acknowledged(body) })
        assertEquals(1, bodies.size);assertEquals(identity, bodies.single().getString("incident_id"))
        assertFalse(bodies.single().toString().contains("upload-secret-canary"))
    }

    @Test fun failed_acknowledgement_commit_retries_the_same_identity() = runBlocking {
        val store = Store();val journal = store.journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "chat", "load", IOException()))
        var identity = ""
        assertEquals(0, journal.drain(first, { true }) { body ->
            identity = body.getString("incident_id");store.writable = false;acknowledged(body)
        })
        store.writable = true
        assertEquals(1, journal.drain(first, { true }) { body -> assertEquals(identity, body.getString("incident_id"));acknowledged(body) })
    }

    @Test fun concurrent_drains_cannot_submit_the_same_unacknowledged_item_twice() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "chat", "load", IOException()))
        val entered = CompletableDeferred<Unit>();val release = CompletableDeferred<Unit>();var sends = 0
        val one = async { journal.drain(first, { true }) { body -> sends++;entered.complete(Unit);release.await();acknowledged(body) } }
        entered.await()
        val two = async { journal.drain(first, { true }) { body -> sends++;acknowledged(body) } }
        release.complete(Unit)
        assertEquals(1, one.await());assertEquals(0, two.await());assertEquals(1, sends)
    }

    @Test fun account_and_profile_aba_never_deliver_a_prior_admission() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "app", "load", IOException()))
        assertTrue(journal.activate(second))
        assertFalse(journal.record(first, "app", "load", IOException()))
        assertTrue(journal.record(second, "settings", "load", IOException()))
        assertTrue(journal.activate(returned))
        assertEquals(0, journal.drain(first, { true }) { fail("Stale admission sent");JSONObject() })
        assertEquals(0, journal.drain(returned, { true }) { fail("Prior profile generation sent");JSONObject() })
    }

    @Test fun no_origin_owner_profile_or_provider_change_can_read_another_scope() = runBlocking {
        for (scope in listOf(owner.copy(origin = "https://other.example"), owner.copy(owner = "other"),
                owner.copy(profile = "work"), owner.copy(nativeProvider = "other"))) {
            val journal = Store().journal();assertTrue(journal.activate(first))
            assertTrue(journal.record(first, "chat", "load", IOException()))
            val foreign = IncidentAuthority(scope, "44444444-4444-4444-8444-444444444444", 7)
            assertTrue(journal.activate(foreign))
            assertEquals(0, journal.drain(foreign, { true }) { fail("Foreign scope was delivered");JSONObject() })
        }
    }

    @Test fun valid_unicode_identity_is_supported_but_unpaired_surrogates_are_rejected() = runBlocking {
        val journal = Store().journal()
        val valid = first.copy(scope = owner.copy(owner = "owner-\uD83D\uDE80"))
        assertTrue(journal.activate(valid));assertTrue(journal.record(valid, "chat", "load", IOException()))
        assertEquals(1, journal.drain(valid, { true }) { acknowledged(it) })
        assertFalse(journal.activate(first.copy(scope = owner.copy(owner = "owner-\uD83D"))))
    }

    @Test fun safe_origin_forms_remain_exactly_bound_and_unsafe_origins_are_rejected() = runBlocking {
        val journal = Store().journal()
        val valid = first.copy(scope = owner.copy(origin = "https://Native.Example/"))
        assertTrue(journal.activate(valid));assertTrue(journal.record(valid, "chat", "load", IOException()))
        val other = first.copy(scope = owner.copy(origin = "https://native.example"))
        assertTrue(journal.activate(other));assertEquals(0, journal.drain(other, { true }) { fail("Origin crossed");JSONObject() })
        for (origin in listOf("http://native.example", "https://secret" + 64.toChar() + "native.example", "https://native.example/path", " https://native.example "))
            assertFalse(journal.activate(first.copy(scope = owner.copy(origin = origin))))
    }

    @Test fun offline_items_resume_after_restart_only_for_the_same_durable_admission() = runBlocking {
        val store = Store();val before = store.journal();assertTrue(before.activate(first))
        assertTrue(before.record(first, "chat", "load", IOException()))
        val after = store.journal();val restored = first.copy(epoch = 0)
        assertTrue(after.activate(restored))
        assertEquals(1, after.drain(restored, { true }) { acknowledged(it) })
        assertTrue(after.record(restored, "chat", "load", IOException()))
        val replaced = store.journal();assertTrue(replaced.activate(returned))
        assertEquals(0, replaced.drain(returned, { true }) { fail("Fresh sign-in inherited old work");JSONObject() })
    }

    @Test fun account_change_during_upload_cannot_acknowledge_or_replay_old_work() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "chat", "load", IOException()))
        val sent = CompletableDeferred<JSONObject>();val release = CompletableDeferred<Unit>()
        val pending = async { journal.drain(first, { true }) { body -> sent.complete(body);release.await();acknowledged(body) } }
        sent.await();assertTrue(journal.activate(second));assertTrue(journal.activate(returned));release.complete(Unit)
        assertEquals(0, pending.await())
        assertEquals(0, journal.drain(returned, { true }) { fail("Stale work sent to a new admission");JSONObject() })
    }

    @Test fun failed_or_corrupt_storage_is_never_silently_overwritten() = runBlocking {
        val store = Store();val journal = store.journal();assertTrue(journal.activate(first))
        assertTrue(journal.record(first, "chat", "load", IOException()))
        val before = store.encrypted!!.copyOf();store.writable = false
        assertFalse(journal.record(first, "chat", "send", IOException()))
        assertArrayEquals(before, store.encrypted)
        store.writable = true;store.encrypted = byteArrayOf(1, 2, 3)
        val corrupt = store.encrypted!!.copyOf();val restarted = store.journal()
        assertFalse(restarted.activate(first));assertFalse(restarted.record(first, "chat", "load", IOException()))
        assertArrayEquals(corrupt, store.encrypted)
    }

    @Test fun bounded_queue_refuses_extra_unacknowledged_events_and_preserves_existing_ones() = runBlocking {
        val journal = Store().journal();assertTrue(journal.activate(first))
        for (status in 400 until 464) assertTrue(journal.record(first, "app", "request", ApiFailure(status, "private")))
        assertFalse(journal.record(first, "app", "request", ApiFailure(599, "private")))
        assertEquals(64, journal.drain(first, { true }) { acknowledged(it) })
    }

    @Test fun prior_process_exit_is_recorded_only_for_the_matching_verified_admission() = runBlocking {
        val store = Store();val previous = store.journal();assertTrue(previous.activate(first))
        val restarted = store.journal();restarted.noteProcessExit(4, 12345);assertTrue(restarted.activate(first.copy(epoch = 0)))
        val sent = mutableListOf<JSONObject>()
        assertEquals(1, restarted.drain(first.copy(epoch = 0), { true }) { body -> sent += body;acknowledged(body) })
        assertEquals("process_crash", sent.single().getJSONObject("incident").getString("error_code"))
        val again = store.journal();again.noteProcessExit(4, 12345);assertTrue(again.activate(first.copy(epoch = 0)))
        assertEquals(0, again.drain(first.copy(epoch = 0), { true }) { fail("Exit recorded twice");JSONObject() })
        val foreign = store.journal();foreign.noteProcessExit(4, 67890);assertTrue(foreign.activate(returned))
        assertEquals(0, foreign.drain(returned, { true }) { fail("Prior owner exit relabelled");JSONObject() })
    }

    @Test fun repeated_activation_does_not_lose_the_matching_prior_process_exit() = runBlocking {
        val store = Store();assertTrue(store.journal().activate(first, appVersion = 141))
        val restarted = store.journal();val restored = first.copy(epoch = 0);val refreshed = first.copy(epoch = 2)
        assertTrue(restarted.activate(restored, appVersion = 142));assertTrue(restarted.activate(refreshed, appVersion = 142))
        restarted.noteProcessExit(4, 12345)
        val sent = mutableListOf<JSONObject>()
        assertEquals(1, restarted.drain(refreshed, { true }) { body -> sent += body;acknowledged(body) })
        assertEquals("process_crash", sent.single().getJSONObject("incident").getString("error_code"))
        assertEquals(141, sent.single().getJSONObject("incident").getInt("app_version_code"))
    }

    @Test fun prior_exit_waits_when_the_queue_is_full_and_is_saved_after_drain() = runBlocking {
        val store = Store();val previous = store.journal();assertTrue(previous.activate(first))
        for (status in 400 until 464) assertTrue(previous.record(first, "app", "request", ApiFailure(status, "private")))
        val restarted = store.journal();restarted.noteProcessExit(4, 12345)
        val restored = first.copy(epoch = 0);assertTrue(restarted.activate(restored))
        assertEquals(64, restarted.drain(restored, { true }) { acknowledged(it) })
        val sent = mutableListOf<JSONObject>()
        assertEquals(1, restarted.drain(restored, { true }) { body -> sent += body;acknowledged(body) })
        assertEquals("process_crash", sent.single().getJSONObject("incident").getString("error_code"))
    }

    @Test fun normal_exit_and_force_stop_are_not_recorded_as_failures() = runBlocking {
        val store = Store();assertTrue(store.journal().activate(first))
        val restarted = store.journal();restarted.noteProcessExit(1, 12345);restarted.noteProcessExit(10, 12346)
        assertTrue(restarted.activate(first.copy(epoch = 0)))
        assertEquals(0, restarted.drain(first.copy(epoch = 0), { true }) { fail("Normal exit was recorded");JSONObject() })
    }

    @Test fun uncaught_handler_always_delegates_even_when_recording_fails() {
        var called = 0;var received: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, error -> called++;received = error }
        val handler = incidentCrashHandler(previous) { throw IOException("secret") }
        val error = IllegalStateException("private crash")
        handler.uncaughtException(Thread.currentThread(), error)
        assertEquals(1, called);assertSame(error, received)
    }
}
