package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

class OwnerFilesSchedulesLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 180_000) fun exactQaFileBytesAreCreatedEditedAndRemoved() = runBlocking {
        val h = OwnerJourneyHarness(compose, "files")
        h.checked {
            qa()
            val filename = "beacon-qa-${runId}.txt"
            var path = ""
            var directoryPath = ""
            var expected = "QA synthetic first line\nsecond line\n"
            var writeInFlight = false
            suspend fun bytes() = client.bytes("/api/files/download", mapOf("path" to path), limit = 4096)
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                compose.onNodeWithTag("navigation-files").performClick()
                await { vm.ui.value.tab == "Spaces" }
                mark("W-01", "files_navigation", "passed")
                val directory = client.nativeGet("/api/files")
                current()
                val root = directory.getString("path")
                directoryPath = root
                check(root.isNotBlank() && directory.rows("entries").none { it.string("name") == filename })
                val separator = if ('\\' in root) "\\" else "/"
                val requested = root.trimEnd('/', '\\') + separator + filename
                current(); writeInFlight = true
                val created = client.nativeRequest("/api/plugins/beacon/mobile/files/create", "POST", json("path" to requested, "content" to expected))
                current()
                val candidate = created.getString("path")
                check(created.opt("ok") == true && candidate.replace('\\', '/') == requested.replace('\\', '/'))
                path = candidate; writeInFlight = false
                check(bytes().contentEquals(expected.toByteArray(Charsets.UTF_8)))
                mark("W-01", "file_exact_bytes", "passed")
                val edited = "QA synthetic edited\n"
                writeInFlight = true
                val saved = client.nativeRequest("/api/plugins/beacon/mobile/files/text", "POST", json("path" to path, "expected_content" to expected, "content" to edited))
                current()
                check(saved.opt("ok") == true)
                expected = edited
                check(bytes().contentEquals(expected.toByteArray(Charsets.UTF_8)))
                writeInFlight = false
                mark("W-01", "file_edit_readback", "passed")
            } finally {
                try {
                    if (writeInFlight) mark("W-01", "file_write_unconfirmed", "needs_human")
                    else if (path.isNotBlank()) {
                        current()
                        check(path.replace('\\', '/').substringAfterLast('/') == filename && bytes().contentEquals(expected.toByteArray(Charsets.UTF_8)))
                        writeInFlight = true
                        val removed = client.nativeGet("/api/files", method = "DELETE", data = json("path" to path, "recursive" to false))
                        current(); writeInFlight = false
                        check(removed.opt("ok") == true)
                        val entries = client.nativeGet("/api/files", mapOf("path" to directoryPath)).rows("entries")
                        check(entries.none { it.string("name") == filename })
                        mark("W-01", "file_cleanup", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("W-01", "file_cleanup_unconfirmed", "needs_human") }; throw error
                }
            }
        }
    }

    @Test(timeout = 180_000) fun qaScheduleIsPausedResumedAndRemovedWithReadback() = runBlocking {
        val h = OwnerJourneyHarness(compose, "schedules")
        h.checked {
            qa()
            val name = "Beacon QA ${runId}"
            val query = mapOf("profile" to scope.profile)
            var id = ""
            var writeInFlight = false
            suspend fun get() = client.nativeGet("/api/cron/jobs/$id", query).also { current() }
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                compose.onNodeWithTag("navigation-cron").performClick()
                await { vm.ui.value.tab == "Tasks" }
                mark("W-03", "schedule_navigation", "passed")
                val before = client.nativeGet("/api/cron/jobs", query).rows("items")
                check(before.none { it.string("name") == name })
                current(); writeInFlight = true
                val created = client.nativeGet("/api/cron/jobs", query, "POST", json("name" to name,
                    "prompt" to "Return QA only. Do not use tools or contact anyone.", "schedule" to "2099-01-01T00:00:00Z",
                    "deliver" to "local", "paused" to true, "repeat" to 1))
                current()
                val candidate = created.string("id")
                check(candidate.matches(Regex("[A-Za-z0-9_-]+")) && created.string("name") == name && created.string("profile") == scope.profile)
                id = candidate; writeInFlight = false
                val first = get()
                check(first.string("name") == name && first.string("deliver") == "local" && first.opt("enabled") == false && first.string("state") == "paused")
                mark("W-03", "schedule_paused_readback", "passed")
                writeInFlight = true
                client.nativeGet("/api/cron/jobs/$id/resume", query, "POST", JSONObject())
                current()
                check(get().opt("enabled") == true)
                writeInFlight = false
                mark("W-03", "schedule_resume_readback", "passed")
                writeInFlight = true
                client.nativeGet("/api/cron/jobs/$id/pause", query, "POST", JSONObject())
                current()
                check(get().opt("enabled") == false && get().string("state") == "paused")
                writeInFlight = false
                mark("W-03", "schedule_pause_readback", "passed")
                mark("W-03", "schedule_run_result", "needs_human")
            } finally {
                try {
                    if (writeInFlight) mark("W-03", "schedule_write_unconfirmed", "needs_human")
                    else if (id.isNotBlank()) {
                        val saved = get()
                        check(saved.string("name") == name && saved.string("profile") == scope.profile && saved.string("deliver") == "local")
                        current(); writeInFlight = true
                        val removed = client.nativeGet("/api/cron/jobs/$id", query, "DELETE")
                        current(); writeInFlight = false
                        check(removed.opt("ok") == true)
                        check(client.nativeGet("/api/cron/jobs", query).rows("items").none { it.string("id") == id })
                        mark("W-03", "schedule_cleanup", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("W-03", "schedule_cleanup_unconfirmed", "needs_human") }; throw error
                }
            }
        }
    }
}
