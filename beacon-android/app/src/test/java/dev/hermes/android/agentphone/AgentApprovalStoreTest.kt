package dev.hermes.android.agentphone

import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneEndpoint
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The board against a fake of L2's mounted routes (approvals-v1 §6): every answer the route gives, and what the phone does with it. */
class AgentApprovalStoreTest {
    private var now = 10_000L
    private val board = AgentApprovalBoard { now }
    private val account = "https://hermes.example.com\u0000\u0000owner-1"
    private fun id(n: Int) = n.toString(16).padStart(32, '0')

    /** The routes as the plugin answers them (relative to AGENT_PHONE_PATH, as L4's endpoint takes them): a list of open records, and one scripted answer per decision. */
    private inner class FakeRoutes : AgentPhoneEndpoint {
        val open = mutableListOf<JSONObject>()
        var listStatus = 200
        var decision: (String, JSONObject) -> AgentPhoneAnswer = { _, _ -> AgentPhoneAnswer(200, JSONObject().put("approved", true)) }
        val calls = mutableListOf<Triple<String, String, JSONObject?>>()
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer {
            calls += Triple(method, route, body?.let { JSONObject(it.toString()) })
            hold?.await()
            if (method == "GET") return if (listStatus == 200) AgentPhoneAnswer(200, JSONObject().put("approvals", JSONArray(open))) else AgentPhoneAnswer(listStatus, JSONObject())
            val record = route.removePrefix("$APPROVALS_ROUTE/").removeSuffix("/decision")
            return decision(record, body!!)
        }
        fun add(n: Int, kind: String = "agent_phone_action", left: Int = 100, risk: String? = "payment") {
            open += JSONObject().put("id", id(n)).put("kind", kind).put("expires_in_seconds", left).apply { if (kind == "agent_phone_action") put("risk", risk) }
        }
    }

    @Test fun aReadReplacesTheAccountsRecordsAndSaysWhatIsNewAndWhatWentAway() = runTest {
        val routes = FakeRoutes().apply { add(1); add(2, "vault_save") }
        val first = board.refresh(account, routes)
        assertEquals(listOf(id(1), id(2)), first.fresh.map { it.id })
        assertTrue(first.reached && first.supported)
        routes.open.removeAt(0); routes.add(3, "needs_you")
        val second = board.refresh(account, routes)
        assertEquals(listOf(id(3)), second.fresh.map { it.id })
        assertEquals(listOf(id(1)), second.gone)
        assertEquals(listOf(id(2), id(3)), board.queue(account).map { it.id })
        assertEquals("GET" to APPROVALS_ROUTE, routes.calls.first().first to routes.calls.first().second)
    }

    @Test fun anOlderHermesWithoutTheRouteHasNothingAndNoAnswerKeepsWhatWasShown() = runTest {
        val routes = FakeRoutes().apply { add(1) }
        board.refresh(account, routes)
        routes.listStatus = 0
        val offline = board.refresh(account, routes)
        assertFalse(offline.reached)
        assertEquals(listOf(id(1)), board.queue(account).map { it.id })
        routes.listStatus = 404
        val old = board.refresh(account, routes)
        assertFalse(old.supported)
        assertTrue(board.queue(account).isEmpty())
    }

    @Test fun aRecordKeepsTheDeadlineItWasFirstReadWithAndLeavesTheQueueWhenItsTimeIsUp() = runTest {
        val routes = FakeRoutes().apply { add(1, left = 100) }
        board.refresh(account, routes)
        now += 50_000
        board.refresh(account, routes) // the same record read again, still saying 100 s: its time doesn't start over
        assertEquals(10_000L + 100_000, board.held.value.single().record.deadline)
        now += 50_000
        assertTrue(board.queue(account).isEmpty())
    }

    @Test fun anAnswerTheRouteTakesRemovesTheRecordWithJustTheDecisionInTheBody() = runTest {
        val routes = FakeRoutes().apply { add(1) }
        board.refresh(account, routes)
        val record = board.queue(account).single()
        assertEquals(DecisionResult.Sent, board.decide(account, record, approved = false, endpoint = routes))
        val (method, path, body) = routes.calls.last()
        assertEquals("POST", method)
        assertEquals("$APPROVALS_ROUTE/${id(1)}/decision", path)
        assertEquals("{\"approved\":false}", body.toString())
        assertTrue(board.queue(account).isEmpty())
    }

    @Test fun eachRefusalIsItsOwnOutcomeAndOnlyAnOverQuestionLeavesTheList() = runTest {
        val cases = listOf(
            AgentPhoneAnswer(409, JSONObject().put("detail", "expired")) to DecisionResult.Ended,
            AgentPhoneAnswer(409, JSONObject().put("detail", "settled")) to DecisionResult.Elsewhere,
            AgentPhoneAnswer(404, JSONObject().put("detail", "Approval not found")) to DecisionResult.Ended,
            AgentPhoneAnswer(400, JSONObject().put("detail", "Invalid decision")) to DecisionResult.Invalid,
            AgentPhoneAnswer(403, JSONObject().put("detail", "removed")) to DecisionResult.NotLinked,
            AgentPhoneAnswer(0, JSONObject()) to DecisionResult.Retry,
            AgentPhoneAnswer(503, JSONObject().put("detail", "Busy, try again.")) to DecisionResult.Retry,
        )
        for ((index, case) in cases.withIndex()) {
            val (answer, expected) = case
            val routes = FakeRoutes().apply { add(index + 1); decision = { _, _ -> answer } }
            board.refresh(account, routes)
            val record = board.queue(account).single { it.id == id(index + 1) }
            assertEquals(expected, board.decide(account, record, approved = true, endpoint = routes))
            val stays = expected !in setOf(DecisionResult.Ended, DecisionResult.Elsewhere)
            assertEquals("$expected", stays, board.queue(account).any { it.id == record.id })
            board.forget(account)
        }
    }

    @Test fun aCodeIsSentOnceInTheBodyAndAMalformedOneNeverLeavesThePhone() = runTest {
        val routes = FakeRoutes().apply { add(1, "vault_code") }
        board.refresh(account, routes)
        val record = board.queue(account).single()
        assertEquals(DecisionResult.Invalid, board.decide(account, record, approved = true, endpoint = routes, code = "12"))
        assertEquals(DecisionResult.Invalid, board.decide(account, record, approved = true, endpoint = routes, code = null))
        assertEquals(1, routes.calls.size) // only the read: neither bad code was sent
        assertEquals(DecisionResult.Sent, board.decide(account, record, approved = true, endpoint = routes, code = "482913"))
        assertEquals("482913", routes.calls.last().third!!.getString("code"))
        // A no to a code carries no code at all.
        routes.add(2, "vault_code"); board.refresh(account, routes)
        board.decide(account, board.queue(account).single { it.id == id(2) }, approved = false, endpoint = routes, code = "999999")
        assertFalse(routes.calls.last().third!!.has("code"))
    }

    @Test fun aSecondTapWhileTheFirstAnswerIsOnItsWayNeverSendsASecondAnswer() = runTest {
        val routes = FakeRoutes().apply { add(1) }
        board.refresh(account, routes)
        val record = board.queue(account).single()
        routes.hold = CompletableDeferred()
        val first = async { board.decide(account, record, approved = true, endpoint = routes) }
        yield()
        assertNull(board.decide(account, record, approved = true, endpoint = routes))
        routes.hold!!.complete(Unit)
        assertEquals(DecisionResult.Sent, first.await())
        assertEquals(1, routes.calls.count { it.first == "POST" })
    }

    @Test fun aCardThatJustTookTheFrontTakesNoAnswerForAMoment() {
        assertFalse(approvalAnswerable(shownAt = 1_000, now = 1_000))
        assertFalse(approvalAnswerable(shownAt = 1_000, now = 1_000 + APPROVAL_GUARD_MS - 1))
        assertTrue(approvalAnswerable(shownAt = 1_000, now = 1_000 + APPROVAL_GUARD_MS))
    }

    @Test fun aPutOffQuestionStaysOpenButIsMarkedAndAnotherAccountsRecordsAreItsOwn() = runTest {
        val routes = FakeRoutes().apply { add(1) }
        board.refresh(account, routes)
        board.refresh("other", FakeRoutes().apply { add(9) })
        board.putOff(id(1))
        assertTrue(id(1) in board.later.value)
        assertEquals(listOf(id(1)), board.queue(account).map { it.id })
        assertEquals(listOf(id(9)), board.queue("other").map { it.id })
        routes.open.clear(); board.refresh(account, routes)
        assertFalse(id(1) in board.later.value)
    }

    @Test fun theQuestionsAreNeverFilteredByPhoneAccess() {
        // Phone access is about this phone; these questions are about the agent's own phone and reach every linked device (§5.4).
        val dir = listOf("src/main/java/dev/hermes/android/agentphone", "app/src/main/java/dev/hermes/android/agentphone", "beacon-android/app/src/main/java/dev/hermes/android/agentphone")
            .map(::File).first { it.isDirectory }
        val sources = dir.listFiles()!!.filter { it.name.startsWith("AgentApproval") }
        assertTrue(sources.size >= 4)
        for (file in sources) assertFalse(file.name, "PhoneAccess" in file.readText())
    }
}
