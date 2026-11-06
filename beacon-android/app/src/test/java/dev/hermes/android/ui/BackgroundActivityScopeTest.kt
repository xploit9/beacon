package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.BackgroundActivityFixture
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class BackgroundActivityScopeTest {
    @Test(timeout = 15_000) fun replacementClientForTheSameScopeIsReadOnTheNextPollWithoutRestartingTheLoop() = runBlocking {
        BackgroundActivityFixture().use { first -> BackgroundActivityFixture().use { second ->
            assertNotSame(first.client, second.client)
            assertEquals(first.client.scope, second.client.scope)
            val scope = first.client.scope
            second.rosters["runtime-a"] = json("subagents" to JSONArray().put(json("subagent_id" to "reconnected-worker", "status" to "running")))
            var selected = first.client
            val received = mutableListOf<BackgroundActivitySummary>()
            var polls = 0
            try {
                pollBackgroundActivity("default", "stored-a", client = { selected.takeIf { it.scope == scope } }, isCurrent = { true }, update = received::add,
                    pause = {
                        polls++
                        if (polls == 2) throw CancellationException("Synthetic poll limit")
                        selected = second.client
                    })
            } catch (_: CancellationException) { }
            assertEquals(2, polls)
            assertEquals("Both observations must come from the current client", listOf("agent:worker-a", "agent:reconnected-worker"), received.map { it.items.single().id })
            assertEquals(1, first.requests.count { it.first == "subagent.list" })
            assertEquals(1, second.requests.count { it.first == "subagent.list" })
        } }
    }

    @Test(timeout = 15_000) fun aReplacementBeforeTheOldReadReturnsKeepsPreviousWorkUnknownUntilTheNextPoll() = runBlocking {
        BackgroundActivityFixture().use { first -> BackgroundActivityFixture().use { second ->
            val scope = first.client.scope
            var selected = first.client
            second.rosters["runtime-a"] = json("subagents" to JSONArray().put(json("subagent_id" to "reconnected-worker", "status" to "running")))
            first.beforeReply = { method -> if (method == "process.list") selected = second.client }
            var shown = conversationBackgroundActivity(first.rosters["runtime-a"], first.processes["runtime-a"], 150)
            val observations = mutableListOf<BackgroundActivitySummary>()
            var polls = 0
            try {
                pollBackgroundActivity("default", "stored-a", client = { selected.takeIf { it.scope == scope } }, isCurrent = { true },
                    update = { current -> shown = reconcileBackgroundActivity(shown, current); observations += shown },
                    pause = { if (++polls == 2) throw CancellationException("Synthetic poll limit") })
            } catch (_: CancellationException) { }
            assertEquals(2, observations.size)
            val stale = observations.first()
            assertFalse(stale.available)
            assertFalse("An old client's running result must not be displayed", stale.working)
            assertEquals("Status unknown", stale.items.single().status)
            assertEquals(50L, stale.items.single().elapsedSeconds)
            val fresh = observations.last()
            assertTrue(fresh.available)
            assertEquals(listOf("agent:reconnected-worker"), fresh.items.filter { it.running }.map { it.id })
            assertEquals("No longer listed", fresh.items.single { it.id == "agent:worker-a" }.status)
        } }
    }

    @Test(timeout = 15_000) fun aTemporarilyMissingClientDoesNotEndPollingBeforeItsReplacementArrives() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            val scope = fixture.client.scope
            var selected: BeaconClient? = null
            val observations = mutableListOf<BackgroundActivitySummary>()
            var polls = 0
            try {
                pollBackgroundActivity("default", "stored-a", client = { selected?.takeIf { it.scope == scope } }, isCurrent = { true }, update = observations::add,
                    pause = {
                        if (++polls == 2) throw CancellationException("Synthetic poll limit")
                        selected = fixture.client
                    })
            } catch (_: CancellationException) { }
            assertEquals(listOf(false, true), observations.map { it.available })
            assertEquals(listOf("agent:worker-a"), observations.last().items.map { it.id })
            assertEquals(1, fixture.requests.count { it.first == "subagent.list" })
        }
    }

    @Test(timeout = 15_000) fun aConversationChangeBeforeTheReadReturnsStillRejectsTheOldResult() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            val scope = fixture.client.scope
            var conversation = "stored-a"
            fixture.beforeReply = { method -> if (method == "process.list") conversation = "stored-b" }
            var shown = conversationBackgroundActivity(fixture.rosters["runtime-a"], fixture.processes["runtime-a"], 150)
            try {
                pollBackgroundActivity("default", "stored-a", client = { fixture.client.takeIf { it.scope == scope } }, isCurrent = { conversation == "stored-a" },
                    update = { shown = reconcileBackgroundActivity(shown, it) }, pause = { throw CancellationException("Synthetic poll limit") })
            } catch (_: CancellationException) { }
            assertFalse(shown.available)
            assertFalse(shown.working)
            assertEquals("Status unknown", shown.items.single().status)
        }
    }

    @Test fun canonicalConversationRosterDoesNotDependOnRegistryWideDelegationStatus() {
        val conversation = json("subagents" to JSONArray().put(json("subagent_id" to "worker-a", "status" to "running")))
        assertEquals("The owned live roster is authoritative", 1, sessionScopedSubagentCount(conversation))
    }

    @Test fun endedChildrenAreNotReportedAsRunning() {
        val conversation = json("subagents" to JSONArray().put(json("subagent_id" to "worker-a", "status" to "completed")))
        assertEquals("A terminal child is not active work", 0, sessionScopedSubagentCount(conversation))
    }

    @Test fun unrelatedRegistryChildrenNeverBecomeConversationWork() {
        assertEquals(0, sessionScopedSubagentCount(json("subagents" to JSONArray())))
    }

    @Test(timeout = 15_000) fun twoChatsReadOnlyTheirCanonicalRuntimeOwnersWithoutBindingTheSharedClient() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            val a = fetchBackgroundActivity(fixture.client, "default", "stored-a")
            val b = fetchBackgroundActivity(fixture.client, "default", "stored-b")
            assertTrue(a.available && b.available)
            assertEquals(listOf("agent:worker-a"), a.items.map { it.id })
            assertEquals(listOf("process:process-b"), b.items.map { it.id })
            assertEquals(0, fixture.client.nativeSessionSnapshot().length())
            val scopedReads = fixture.requests.filter { it.first in setOf("subagent.list", "process.list") }
            assertEquals(setOf("runtime-a", "runtime-b"), scopedReads.map { it.second.getString("session_id") }.toSet())
            assertTrue(scopedReads.all { it.second.getString("profile") == "default" })
            assertTrue(fixture.requests.none { it.first in setOf("session.resume", "session.create", "prompt.submit", "delegation.status") })
        }
    }

    @Test(timeout = 15_000) fun enabledProfileWatchesAndRunningSchedulesDoNotAppearInAnIdleConversation() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.processes["runtime-b"] = json("processes" to JSONArray())
            assertEquals(1, fixture.profileWatches.length())
            assertEquals(1, fixture.profileSchedules.length())
            val current = fetchBackgroundActivity(fixture.client, "default", "stored-b")
            assertTrue(current.available)
            assertFalse(current.active)
            assertTrue(current.items.isEmpty())
            assertTrue(fixture.httpPaths.all { it in setOf("/api/auth/ws-ticket", "/api/plugins/beacon/mobile/gateway-ticket") })
            assertEquals(setOf("client.capabilities", "session.active_list", "subagent.list", "process.list"), fixture.requests.map { it.first }.toSet())
        }
    }

    @Test(timeout = 15_000) fun emptyChatAndAnotherProfileNeverReadARoster() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            assertFalse(fetchBackgroundActivity(fixture.client, "default", "").available)
            assertFalse(fetchBackgroundActivity(fixture.client, "another-profile", "stored-a").available)
            assertTrue(fixture.requests.isEmpty())
            assertTrue(fixture.httpPaths.isEmpty())
        }
    }

    @Test fun storedAndRuntimeIdentitiesMustMatchOneUnambiguousInventoryRow() {
        fun inventory(vararg rows: JSONObject) = json("sessions" to JSONArray(rows.toList()))
        val row = json("id" to "runtime-a", "session_key" to "stored-a")
        assertEquals("runtime-a", backgroundActivityRuntime(inventory(row), "stored-a"))
        assertEquals("stored-a", backgroundActivityRuntime(inventory(json("id" to "stored-a", "session_key" to "stored-a")), "stored-a"))
        assertNull(backgroundActivityRuntime(inventory(row), "runtime-a"))
        assertNull(backgroundActivityRuntime(inventory(row, row), "stored-a"))
        assertNull(backgroundActivityRuntime(inventory(row, json("id" to "runtime-a", "session_key" to "stored-b")), "stored-a"))
        assertNull(backgroundActivityRuntime(inventory(json("id" to 7, "session_key" to "stored-a")), "stored-a"))
        assertNull(backgroundActivityRuntime(JSONObject(), "stored-a"))
    }

    @Test(timeout = 15_000) fun anUnknownOrRetiredRuntimeDoesNotFallBackToAnotherChat() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            val current = fetchBackgroundActivity(fixture.client, "default", "stored-missing")
            assertFalse(current.available)
            assertTrue(current.items.isEmpty())
            assertTrue(fixture.requests.none { it.first in setOf("subagent.list", "process.list") })
        }
    }

    @Test(timeout = 15_000) fun unsupportedWorkerProjectionRetainsTheKnownProcessAndReportsIncompleteStatus() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.failMethod = "subagent.list"
            val current = fetchBackgroundActivity(fixture.client, "default", "stored-b")
            assertFalse(current.available)
            assertEquals(1, current.processes)
            assertEquals("1 process · More may be active", backgroundActivityStatus(current))
        }
    }

    @Test(timeout = 15_000) fun lifecycleCancellationIsPropagatedInsteadOfBecomingAnEmptySuccess() = runBlocking {
        BackgroundActivityFixture().use { fixture ->
            fixture.holdMethod = "process.list"
            var returned: BackgroundActivitySummary? = null
            val pending = async { returned = fetchBackgroundActivity(fixture.client, "default", "stored-a") }
            fixture.held.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertNull("Cancellation must not return an empty status", returned)
            assertEquals(1, fixture.requests.count { it.first == "process.list" })
        }
    }

    @Test fun actualWorkerProcessAndFailedDelegationDetailsHaveStatusesAndMeasuredElapsedTime() {
        val roster = json("subagents" to JSONArray().put(json("subagent_id" to "child", "goal" to "Read synthetic notes", "status" to "running", "started_at" to 100)),
            "delegations" to JSONArray().put(json("delegation_id" to "failed-task", "task_index" to 2, "goal" to "Check synthetic build", "status" to "failed", "dispatched_at" to 90, "completed_at" to 120)))
        val processes = json("processes" to JSONArray()
            .put(json("session_id" to "done", "command" to "synthetic success", "status" to "exited", "exit_code" to 0, "started_epoch" to 100, "exited_at" to 140))
            .put(json("session_id" to "failed", "command" to "synthetic failure", "status" to "exited", "exit_code" to 2, "started_epoch" to 101, "exited_at" to 141))
            .put(json("session_id" to "stopped", "status" to "exited", "exit_code" to 1, "completion_reason" to "killed")))
        val current = conversationBackgroundActivity(roster, processes, nowSeconds = 150)
        assertTrue(current.available)
        assertEquals(1, current.agents)
        assertEquals(0, current.processes)
        assertEquals(listOf("Running", "Failed", "Completed", "Failed", "Cancelled"), current.items.map { it.status })
        assertEquals(listOf(50L, 30L, 40L, 40L, null), current.items.map { it.elapsedSeconds })
        assertEquals("Read synthetic notes", current.items.first().title)
    }

    @Test fun unknownAndMalformedDataNeverMeansCompletedOrZeroKnownWork() {
        val unknown = conversationBackgroundActivity(json("subagents" to JSONArray().put(json("subagent_id" to "child", "status" to "new-server-status", "started_at" to 200))), json("processes" to JSONArray()), 150)
        assertFalse(unknown.available)
        assertTrue(unknown.active)
        assertEquals("Status unknown", unknown.items.single().status)
        assertNull(unknown.items.single().elapsedSeconds)
        assertEquals("Background status unavailable", backgroundActivityStatus(unknown))
        assertFalse(conversationBackgroundActivity(JSONObject(), JSONObject()).available)
        assertFalse(conversationBackgroundActivity(json("subagents" to JSONArray().put("unreadable")), json("processes" to JSONArray())).available)
    }

    @Test fun disconnectAndReconnectKeepHonestStatusAndNeverInventCompletionFromDisappearance() {
        val initial = conversationBackgroundActivity(json("subagents" to JSONArray().put(json("subagent_id" to "child", "status" to "running", "started_at" to 100))), json("processes" to JSONArray()), 150)
        val offline = reconcileBackgroundActivity(initial, BackgroundActivitySummary(available = false))
        assertTrue(offline.active)
        assertFalse(offline.working)
        assertEquals("Status unknown", offline.items.single().status)
        assertEquals(50L, offline.items.single().elapsedSeconds)
        val empty = conversationBackgroundActivity(json("subagents" to JSONArray()), json("processes" to JSONArray()), 200)
        val online = reconcileBackgroundActivity(offline, empty)
        assertFalse(online.active)
        assertEquals("No longer listed", online.items.single().status)
        val completed = initial.copy(agents = 0, items = initial.items.map { it.copy(status = "Completed") })
        assertEquals("Completed", reconcileBackgroundActivity(initial, completed).items.single().status)
    }
}
