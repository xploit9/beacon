package dev.hermes.android.agentphone

import android.app.Application
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneEndpoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The real AgentPhoneLogActivity ("What {name} did on its phone") against a fake of `GET /mobile/agent-phone/activity`: it reads the
 * rows itself, opens at the row a done notice names (EXTRA_SEQ), and Back closes it. Synthetic rows only.
 */
class AgentApprovalLogScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    @After fun clear() { AgentApprovals.testLink = null }

    @Test fun theListReadsTheRowsOpensAtTheNoticesRowAndBackCloses() {
        val now = System.currentTimeMillis() / 1000
        val rows = JSONArray((1..30).map { n ->
            JSONObject().put("seq", n).put("at", now - (30 - n) * 3600).put("app", "Kanto Foods").put("action", "tap")
                .put("label", "Step $n").put("approved", n == 3).put("outcome", "done")
        }.reversed())
        val routes = CopyOnWriteArrayList<String>()
        AgentApprovals.testLink = ApprovalLink("log-test-${System.nanoTime()}", object : AgentPhoneEndpoint {
            override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer {
                routes += "$method $route"
                return AgentPhoneAnswer(200, JSONObject().put("activity", rows))
            }
        })
        // Row 3 is old enough to sit past the first page of 20: the notice's row is still shown, and marked.
        ActivityScenario.launch<AgentPhoneLogActivity>(AgentPhoneLogActivity.intent(app, 3L)).use { scenario ->
            // The list scrolls itself to the notice's day; the row is there (a lazy list composes only what is on screen).
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("agent-phone-log-row:3").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("GET /activity?limit=200"), routes.toList())
            assertTrue(compose.onAllNodesWithTag("agent-phone-log-title").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("agent-phone-log-back").performClick()
            compose.waitUntil(10_000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }
}
