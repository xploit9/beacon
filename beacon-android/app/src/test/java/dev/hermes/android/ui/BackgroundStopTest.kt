package dev.hermes.android.ui

import dev.hermes.android.BackgroundActivityFixture
import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Stop action's request, receipt and refresh, against synthetic Hermes answers only. */
class BackgroundStopTest {
    private fun item(id: String, status: String, title: String = "synthetic work") =
        BackgroundActivityItem(id, if (id.startsWith("agent:")) "Background agent" else "Background process", title, status)

    @Test fun aRunningProcessOrAgentOfThisChatHasOneSupportedStop() {
        val process = requireNotNull(backgroundStopRequest(item("process:proc-1", "Running"), "runtime-a", "default"))
        assertEquals("process.kill", process.method)
        assertEquals("runtime-a", process.params.getString("session_id"))
        assertEquals("proc-1", process.params.getString("process_id"))
        assertEquals("default", process.params.getString("profile"))

        val agent = requireNotNull(backgroundStopRequest(item("agent:worker-a", "Running"), "runtime-a", "default"))
        assertEquals("subagent.interrupt", agent.method)
        assertEquals("runtime-a", agent.params.getString("session_id"))
        assertEquals("worker-a", agent.params.getString("subagent_id"))
        assertFalse("Never the registry-wide kill_all", agent.params.has("process_id"))
    }

    @Test fun finishedQueuedAndUnidentifiedRowsNeverOfferStop() {
        assertNull("a finished process is not running", backgroundStopRequest(item("process:p", "Completed"), "runtime-a", "default"))
        assertNull("a failed process is not running", backgroundStopRequest(item("process:p", "Failed"), "runtime-a", "default"))
        assertNull("a queued agent has not started", backgroundStopRequest(item("agent:a", "Queued"), "runtime-a", "default"))
        assertNull("a delegation is not a child agent", backgroundStopRequest(item("delegation:d:0", "Running"), "runtime-a", "default"))
        assertNull("no runtime means no owner", backgroundStopRequest(item("process:p", "Running"), "", "default"))
        assertNull("a malformed process id has no target", backgroundStopRequest(item("process:", "Running"), "runtime-a", "default"))
    }

    @Test fun onlyHermesOwnReceiptCountsAsStopped() {
        assertTrue(backgroundStopAccepted("process.kill", json("status" to "killed")))
        assertTrue("a process that exited first is already gone", backgroundStopAccepted("process.kill", json("status" to "already_exited")))
        assertTrue("a registry miss is already gone", backgroundStopAccepted("process.kill", json("status" to "not_found")))
        assertFalse("survivors are a failed kill", backgroundStopAccepted("process.kill",
            json("status" to "error", "error" to "Kill incomplete: 1 process(es) still alive", "process_running" to true)))
        assertFalse("a missing status proves nothing", backgroundStopAccepted("process.kill", JSONObject()))
        assertTrue(backgroundStopAccepted("subagent.interrupt", json("found" to true)))
        assertFalse("an unreached child was not cancelled", backgroundStopAccepted("subagent.interrupt", json("found" to false)))
        assertFalse("a child that ended before the interrupt was not cancelled by it", backgroundStopAlreadyGone("subagent.interrupt", json("found" to true)))
        assertTrue("found=false is already gone, not a refusal", backgroundStopAlreadyGone("subagent.interrupt", json("found" to false)))
        assertFalse("only a child interrupt has this answer", backgroundStopAlreadyGone("process.kill", json("status" to "killed")))
        assertFalse("an unknown verb is never success", backgroundStopAccepted("process.stop", json("killed" to 1)))
    }

    @Test fun aKilledProcessReadsStoppedAndIsNoLongerRunning() {
        val before = conversationBackgroundActivity(json("subagents" to JSONArray()),
            json("processes" to JSONArray().put(json("session_id" to "p", "command" to "sleep 300", "status" to "running", "started_epoch" to 100))), 150)
        assertTrue(before.items.single().running)
        val after = conversationBackgroundActivity(json("subagents" to JSONArray()),
            json("processes" to JSONArray().put(json("session_id" to "p", "command" to "sleep 300", "status" to "exited", "exit_code" to 1,
                "completion_reason" to "killed", "started_epoch" to 100, "exited_at" to 140))), 150)
        assertEquals("Stopped", after.items.single().status)
        assertFalse(after.items.single().running)
        assertEquals(0, after.processes)
        assertEquals(40L, after.items.single().elapsedSeconds)
    }

    @Test(timeout = 30_000) fun stopSendsHermesOwnProcessKillAndSettlesTheSheet() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.onStop = { method, _ ->
                if (method == "process.kill") fixture.processes["runtime-b"] = json("processes" to JSONArray().put(json(
                    "session_id" to "process-b", "command" to "synthetic build", "status" to "exited", "exit_code" to 1,
                    "completion_reason" to "killed", "started_epoch" to 100, "exited_at" to 140)))
            }
            val before = fetchBackgroundActivity(fixture.client, "default", "stored-b")
            val row = before.items.single()
            assertEquals("process:process-b", row.id)
            val listsBefore = fixture.requests.count { it.first == "process.list" }
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-b", row, before)
            assertEquals("", outcome.failure)
            val sent = fixture.requests.single { it.first == "process.kill" }
            assertEquals("runtime-b", sent.second.getString("session_id"))
            assertEquals("process-b", sent.second.getString("process_id"))
            assertEquals("default", sent.second.getString("profile"))
            assertEquals("the sheet's runtime is used, with no read before the kill", 1, fixture.requests.count { it.first == "session.active_list" })
            assertEquals("the receipt settles the row; the poll refreshes later", listsBefore, fixture.requests.count { it.first == "process.list" })
            assertEquals("Stopped", outcome.activity?.items?.single()?.status)
            assertFalse(outcome.activity?.working ?: true)
            assertTrue("never the registry-wide kill_all", fixture.requests.none { it.first == "process.stop" })
            assertTrue("no extra session machinery", fixture.requests.none { it.first in setOf("session.resume", "prompt.submit") })
        }
    }

    @Test(timeout = 30_000) fun aRefusedKillKeepsTheRowRunningAndSaysSo() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.killReply = json("status" to "error", "error" to "Kill incomplete: 1 process(es) still alive", "process_running" to true)
            val row = fetchBackgroundActivity(fixture.client, "default", "stored-b").items.single()
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-b", row)
            assertEquals(backgroundStopFailureMessage(row), outcome.failure)
            assertNull("a refusal does not claim a refreshed all-clear", outcome.activity)
            assertTrue(fetchBackgroundActivity(fixture.client, "default", "stored-b").items.single().running)
            assertTrue(fixture.requests.any { it.first == "process.kill" })
        }
    }

    @Test(timeout = 30_000) fun aProcessHermesNoLongerOwnsIsTreatedAsGone() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.processes["runtime-b"] = json("processes" to JSONArray())
            fixture.stopError = json("code" to 4044, "message" to "no such process: process-b")
            val row = BackgroundActivityItem("process:process-b", "Background process", "synthetic build", "Running")
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-b", row)
            assertEquals("", outcome.failure)
            assertNotNull(outcome.activity)
            assertTrue(outcome.activity?.items.orEmpty().isEmpty())
        }
    }

    @Test(timeout = 30_000) fun aCanonicalProcessThatLeftTheRegistryIsTreatedAsGone() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.processes["runtime-b"] = json("processes" to JSONArray())
            fixture.stopError = json("code" to 4001, "message" to "process_not_found")
            val row = BackgroundActivityItem("process:process-b", "Background process", "synthetic build", "Running")
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-b", row)
            assertEquals("Hermes said the process is no longer live, not a refusal", "", outcome.failure)
            assertNotNull(outcome.activity)
            assertTrue(outcome.activity?.items.orEmpty().isEmpty())
        }
    }

    @Test(timeout = 30_000) fun aChildThatEndedBeforeTheInterruptIsTreatedAsGoneAndRefreshed() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.interruptReply = json("found" to false)
            fixture.onStop = { method, _ ->
                if (method == "subagent.interrupt") fixture.rosters["runtime-a"] = json("subagents" to JSONArray().put(json(
                    "subagent_id" to "worker-a", "goal" to "Check synthetic notes", "status" to "completed", "started_at" to 100)))
            }
            val before = fetchBackgroundActivity(fixture.client, "default", "stored-a")
            val row = before.items.single()
            assertEquals("agent:worker-a", row.id)
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-a", row, before)
            assertEquals("a child that ended first is already gone", "", outcome.failure)
            assertEquals("No longer listed", outcome.activity?.items?.single()?.status)
            assertTrue(fixture.requests.any { it.first == "subagent.interrupt" })
        }
    }

    @Test(timeout = 30_000) fun aRunningAgentIsCancelledThroughSubagentInterrupt() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.onStop = { method, _ ->
                if (method == "subagent.interrupt") fixture.rosters["runtime-a"] = json("subagents" to JSONArray().put(json(
                    "subagent_id" to "worker-a", "goal" to "Check synthetic notes", "status" to "completed", "started_at" to 100)))
            }
            val before = fetchBackgroundActivity(fixture.client, "default", "stored-a")
            val row = before.items.single()
            assertEquals("agent:worker-a", row.id)
            val outcome = stopBackgroundActivity(fixture.client, "default", "stored-a", row, before)
            assertEquals("", outcome.failure)
            val sent = fixture.requests.single { it.first == "subagent.interrupt" }
            assertEquals("runtime-a", sent.second.getString("session_id"))
            assertEquals("worker-a", sent.second.getString("subagent_id"))
            assertEquals("Cancelled", outcome.activity?.items?.single()?.status)
            assertFalse(outcome.activity?.working ?: true)
            assertTrue("agents never use the process verbs", fixture.requests.none { it.first in setOf("process.kill", "process.stop") })
        }
    }

    @Test fun theReceiptNamesTheRowWithoutARead() {
        val process = item("process:process-b", "Running")
        val before = BackgroundActivitySummary(processes = 1, items = listOf(process), runtimeSessionId = "runtime-b")
        val killed = backgroundActivityAfterStop(before, process, "process.kill", json("status" to "killed"))
        assertEquals("Stopped", killed.items.single().status)
        assertEquals(0, killed.processes)
        assertFalse(killed.working)
        assertEquals("No longer listed", backgroundActivityAfterStop(before, process, "process.kill", json("status" to "already_exited")).items.single().status)
        assertEquals("No longer listed", backgroundActivityAfterStop(before, process, "process.kill", json("status" to "not_found")).items.single().status)
        val agent = item("agent:worker-a", "Running")
        val agents = BackgroundActivitySummary(agents = 1, items = listOf(agent), runtimeSessionId = "runtime-a")
        assertEquals("Cancelled", backgroundActivityAfterStop(agents, agent, "subagent.interrupt", json("found" to true)).items.single().status)
        assertEquals("No longer listed", backgroundActivityAfterStop(agents, agent, "subagent.interrupt", json("found" to false)).items.single().status)
    }
}
