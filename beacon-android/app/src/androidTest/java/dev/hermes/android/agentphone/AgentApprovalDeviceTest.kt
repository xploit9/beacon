package dev.hermes.android.agentphone

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneEndpoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The real AgentApprovalActivity on a test emulator, against a fake of the approvals routes (approvals-v1 §6): its window is secure
 * before any content and a screenshot of it is black; each kind asks and sends its answer; a code is sent once; an answer that comes
 * back expired or settled says so; a second tap never answers the next question. Synthetic records only (example.com, no real card).
 */
class AgentApprovalDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private var account = 0
    private fun id(n: Int) = n.toString(16).padStart(32, '0')

    /** The plugin's two routes: the open records, and one scripted answer per decision; every call is kept. */
    private class Fake : AgentPhoneEndpoint {
        val open = CopyOnWriteArrayList<JSONObject>()
        val decisions = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        @Volatile var answer: (String) -> AgentPhoneAnswer = { AgentPhoneAnswer(200, JSONObject().put("approved", true)) }
        override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer {
            if (method == "GET") return AgentPhoneAnswer(200, JSONObject().put("approvals", JSONArray(open.toList())))
            val record = route.removePrefix("/approvals/").removeSuffix("/decision")
            decisions += record to JSONObject(body.toString())
            val reply = answer(record)
            if (reply.ok) open.removeAll { it.getString("id") == record }
            return reply
        }
        fun add(id: String, kind: String, vararg fields: Pair<String, Any>) {
            open += JSONObject().put("id", id).put("kind", kind).put("expires_in_seconds", 110).apply { fields.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun open(fake: Fake): ActivityScenario<AgentApprovalActivity> {
        account += 1
        AgentApprovals.testLink = ApprovalLink("device-test-${System.nanoTime()}-$account", fake)
        return ActivityScenario.launch(AgentApprovalActivity.intent(app, null, null))
    }

    @After fun clear() { AgentApprovals.testLink = null }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    /** A new card takes no answer for a moment (APPROVAL_GUARD_MS): wait it out before a deliberate tap. */
    private fun settle() { Thread.sleep(APPROVAL_GUARD_MS + 150); compose.waitForIdle() }

    @Test fun theWindowIsSecureBeforeAnyContentAndAScreenshotOfItIsBlack() {
        var flagsAtCreate = -1
        var childrenAtCreate = -1
        val created = CountDownLatch(1)
        val watch = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity !is AgentApprovalActivity) return
                flagsAtCreate = activity.window.attributes.flags
                childrenAtCreate = activity.findViewById<ViewGroup>(android.R.id.content)?.childCount ?: 0
                created.countDown()
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(watch)
        val fake = Fake().apply { add(id(1), "agent_phone_action", "risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40") }
        try {
            open(fake).use { scenario ->
                assertTrue(created.await(10, TimeUnit.SECONDS))
                assertTrue("FLAG_SECURE before content", flagsAtCreate and WindowManager.LayoutParams.FLAG_SECURE != 0)
                assertEquals("no content yet when the flag was checked", 0, childrenAtCreate)
                waitFor("agent-approval-amount")
                scenario.onActivity { assertTrue(it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
                // Android leaves a secure window out of every screenshot: where the card is, the capture is black.
                InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(300, 3_000)
                val shot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val samples = listOf(shot.width / 2 to shot.height * 3 / 4, shot.width / 2 to shot.height / 2, shot.width / 4 to shot.height * 7 / 8)
                for ((x, y) in samples) {
                    val pixel = shot.getPixel(x, y)
                    assertTrue("pixel $x,$y is black", Color.red(pixel) < 8 && Color.green(pixel) < 8 && Color.blue(pixel) < 8)
                }
                shot.recycle()
            }
        } finally {
            app.unregisterActivityLifecycleCallbacks(watch)
        }
    }

    @Test fun allowSendsYesForExactlyThatRecordAndTheDialogClosesWhenNoneIsLeft() {
        val fake = Fake().apply { add(id(2), "agent_phone_action", "risk" to "send", "app" to "Messages", "label" to "Send", "purpose" to "reply to the shop") }
        open(fake).use { scenario ->
            waitFor("agent-approval-yes")
            settle()
            compose.onNodeWithTag("agent-approval-yes").performClick()
            compose.waitUntil(10_000) { fake.decisions.isNotEmpty() }
            assertEquals(id(2) to "{\"approved\":true}", fake.decisions.single().let { it.first to it.second.toString() })
            compose.waitUntil(10_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
        }
    }

    @Test fun denySendsNoAndAPaymentSaysNothingWasPaid() {
        val fake = Fake().apply { add(id(3), "agent_phone_action", "risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40") }
        open(fake).use {
            waitFor("agent-approval-no")
            settle()
            compose.onNodeWithTag("agent-approval-no").performClick()
            waitFor("agent-approval-answered")
            assertEquals("{\"approved\":false}", fake.decisions.single().second.toString())
        }
    }

    @Test fun aCodeIsSentOnceAndTheFieldIsEmptyAfter() {
        val fake = Fake().apply {
            add(id(4), "vault_code", "app" to "Amazon", "label" to "Amazon", "line" to "Enter the code for Amazon.")
            answer = { AgentPhoneAnswer(400, JSONObject().put("detail", "Invalid decision")) }
        }
        open(fake).use {
            waitFor("agent-approval-code")
            settle()
            compose.onNodeWithTag("agent-approval-yes").assertIsNotEnabled()
            compose.onNodeWithTag("agent-approval-code").performTextInput("48 29 13")
            // With the keyboard up the whole card stays on screen above it: its heading is not pushed off the top.
            compose.waitForIdle()
            val title = compose.onNodeWithTag("agent-approval-title").getUnclippedBoundsInRoot()
            assertTrue("the heading stays on screen with the keyboard up (top ${title.top})", title.top >= 0.dp)
            compose.onNodeWithTag("agent-approval-yes").performClick()
            compose.waitUntil(10_000) { fake.decisions.isNotEmpty() }
            assertEquals("482913", fake.decisions.single().second.getString("code"))
            // Refused: the card says so, and the code typed is gone (never shown again); Enter waits for a new one.
            waitFor("agent-approval-line")
            compose.onNodeWithTag("agent-approval-yes").assertIsNotEnabled()
            assertTrue(compose.onAllNodesWithTag("agent-approval-code").fetchSemanticsNodes().single().config
                .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.isNullOrEmpty())
        }
    }

    @Test fun anAnswerThatCameTooLateOrWasGivenElsewhereSaysSoAndCloses() {
        for ((detail, line) in listOf("expired" to decisionLine(DecisionResult.Ended, code = false)!!, "settled" to "Answered on another device.")) {
            val fake = Fake().apply {
                add(id(5), "vault_save", "label" to "Amazon", "identifier" to "k${'@'}example.com", "line" to "Save the new Amazon login?")
                answer = { AgentPhoneAnswer(409, JSONObject().put("detail", detail)) }
            }
            open(fake).use { scenario ->
                waitFor("agent-approval-yes")
                settle()
                compose.onNodeWithTag("agent-approval-yes").performClick()
                waitFor("agent-approval-close")
                assertTrue(compose.onAllNodesWithTag("agent-approval-line").fetchSemanticsNodes().single().config
                    .getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString { it.text }.contains(line))
                compose.onNodeWithTag("agent-approval-close").performClick()
                compose.waitUntil(10_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            }
        }
    }

    @Test fun aSecondTapNeverAnswersTheNextQuestion() {
        val fake = Fake().apply {
            add(id(6), "agent_phone_action", "risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40")
            add(id(7), "agent_phone_action", "risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£99.00")
        }
        open(fake).use {
            waitFor("agent-approval-count")
            settle()
            compose.onNodeWithTag("agent-approval-yes").performClick()
            // The same thumb lands again at once: the next card holds still for its guard.
            compose.waitUntil(10_000) { fake.decisions.size == 1 }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("agent-approval-amount").fetchSemanticsNodes().any { node ->
                node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Text) { emptyList() }.joinToString { it.text }.contains("99.00") } }
            runCatching { compose.onNodeWithTag("agent-approval-yes").performClick() }
            Thread.sleep(400)
            assertEquals(listOf(id(6)), fake.decisions.map { it.first })
        }
    }

    @Test fun openThePhoneAnswersYesAndTheDialogClosesAtOnce() {
        val fake = Fake().apply { add(id(9), "needs_you", "app" to "Amazon", "line" to "The Amazon login didn't work.") }
        open(fake).use { scenario ->
            waitFor("agent-approval-yes")
            settle()
            compose.onNodeWithTag("agent-approval-yes").performClick()
            compose.waitUntil(10_000) { fake.decisions.isNotEmpty() }
            assertEquals(id(9) to true, fake.decisions.single().let { it.first to it.second.getBoolean("approved") })
            // It leaves for the setup view (L5's, when installed) without lingering on an answered card.
            val answered = System.currentTimeMillis()
            compose.waitUntil(10_000) { scenario.state == androidx.lifecycle.Lifecycle.State.DESTROYED }
            assertTrue("closed at once", System.currentTimeMillis() - answered < answeredShowMillis(CardPhase.Answered("", true)))
        }
    }

    @Test fun aNeedsYouNoticeCanBeDismissed() {
        val fake = Fake().apply { add(id(8), "needs_you", "app" to "Amazon", "line" to "The Amazon login didn't work.") }
        open(fake).use {
            waitFor("agent-approval-no")
            settle()
            compose.onNodeWithTag("agent-approval-no").performClick()
            compose.waitUntil(10_000) { fake.decisions.isNotEmpty() }
            assertEquals(id(8) to false, fake.decisions.single().let { it.first to it.second.getBoolean("approved") })
            assertFalse(fake.decisions.single().second.has("code"))
        }
    }
}
