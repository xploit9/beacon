package dev.hermes.android.journey

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.io.File

/** The coordinator excludes this QA profile from repair dispatch before opting in. */
class OwnerIncidentsLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 120_000) fun encryptedQueueAndDuplicateAcknowledgementUseTheRealServer() = runBlocking {
        val h = OwnerJourneyHarness(compose, "incidents")
        h.checked {
            qa(healing = true)
            val endpoint = "/api/plugins/beacon/mobile/incidents"
            val capabilities = client.nativeRequest("/api/plugins/beacon/mobile/healing/capabilities")
            check(capabilities.opt("supported") == true)
            suspend fun matching() = withTimeout(15_000) { client.nativeRequest(endpoint).rows("incidents") }.filter {
                it.string("component") == "app" && it.string("operation") == "render" && it.string("error_code") == "http_418"
            }
            check(matching().isEmpty())
            current()
            val canary = "qa-message-must-never-be-saved-${runId}"
            val path = File(app.noBackupFilesDir, "beacon-incidents-v1")
            app.incidents.record(client, "app", "render", ApiFailure(418, canary))
            var event: JSONObject? = null
            // A concurrent ordinary drain may win; that is reported as unobserved rather than inventing an offline pass.
            try {
                await(5_000) {
                    val sealed = runCatching { path.readBytes() }.getOrNull() ?: return@await false
                    if (sealed.size > 132_000 || String(sealed, Charsets.UTF_8).contains(canary)) return@await false
                    val clear = runCatching { app.cipher.decrypt(sealed, "beacon-incidents-v1") }.getOrNull() ?: return@await false
                    val text = String(clear, Charsets.UTF_8)
                    check(!text.contains(canary))
                    val rows = JSONObject(text).rows("items")
                    event = rows.firstOrNull { item -> ownsJournalItem(item) && item.optJSONObject("incident")?.let {
                        it.string("component") == "app" && it.string("operation") == "render" && it.string("error_code") == "http_418"
                    } == true }
                    event != null
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                mark("X-02", "incident_offline_capture", "needs_human")
                assumeTrue(false)
            }
            current()
            val captured = checkNotNull(event)
            val incident = captured.getJSONObject("incident")
            check(incident.keys().asSequence().toSet() == setOf("component", "operation", "error_code", "app_version_code"))
            val eventId = captured.getString("incident_id")
            mark("X-02", "incident_encrypted_metadata", "passed", qaId = eventId)
            app.incidents.drain(client)
            current()
            var server = matching().singleOrNull()
            await(15_000) { matching().singleOrNull().also { server = it } != null }
            val before = checkNotNull(server)
            val occurrenceCount = before.getInt("occurrences")
            val serverId = before.getString("id")
            mark("X-02", "incident_server_recorded", "passed", qaId = serverId)
            // Only metadata with its already acknowledged UUID is replayable; a chat or Vault write is never retried here.
            val ack = client.nativeRequest(endpoint, "POST", json("incident_id" to eventId, "incident" to incident))
            current()
            check(ack.optString("incident_id") == eventId && ack.opt("recorded") == true)
            val after = matching().single()
            check(after.getString("id") == serverId && after.getInt("occurrences") == occurrenceCount && !after.toString().contains(canary))
            mark("X-02", "incident_duplicate_idempotent", "passed", qaId = eventId)
            mark("X-02", "incident_offline_retry", "needs_human")
            mark("X-02", "incident_coordinator_cleanup", "needs_human", qaId = serverId)
        }
    }
}
