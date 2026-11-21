package dev.hermes.android.agentphone

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneApi
import dev.hermes.android.data.AgentPhoneEndpoint
import dev.hermes.android.ui.AgentPhoneController
import dev.hermes.android.ui.AgentPhonePage
import dev.hermes.android.ui.AgentPhonePageActions
import dev.hermes.android.ui.NativeLocalization
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class AgentPhoneDriverTaskUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val requests = mutableListOf<JSONObject>()
    private var cancellations = 0
    private var opened = 0
    private var serverTask = ""
    private var serverMessage: String? = null

    @Test fun arabicAtLargeTextCanSendRetryAndCancelThePhoneTask() {
        show("ar", width = 320, fontScale = 2f, loseFirstReply = true)
        reveal("agent-phone-give-task")
        val give = compose.onNodeWithTag("agent-phone-give-task")
        give.assertIsDisplayed().assertIsEnabled().assertHasClickAction().assertTextContains("إسناد مهمة")
        assertEquals(Role.Button, give.fetchSemanticsNode().config[SemanticsProperties.Role])
        assertTrue(give.fetchSemanticsNode().size.height >= 48 * densityFor(320) - 1)
        give.performClick()

        val send = compose.onNodeWithTag("agent-phone-send-task")
        send.assertIsNotEnabled()
        val goal = "افتح الإعدادات"
        compose.onNodeWithTag("agent-phone-task-goal").assertIsDisplayed().performTextInput(goal)
        send.assertIsEnabled().performClick()
        compose.onNodeWithTag("agent-phone-task-send-problem").assertIsDisplayed()
        send.performClick()

        reveal("agent-phone-driver-state")
        val state = compose.onNodeWithTag("agent-phone-driver-state")
        state.assertTextContains("قيد التشغيل")
        assertEquals(LiveRegionMode.Polite, state.fetchSemanticsNode().config[SemanticsProperties.LiveRegion])
        compose.runOnIdle {
            assertEquals(listOf(goal, goal), requests.map { it.getString("task") })
            val id = requests.map { it.getString("request_id") }.distinct().single()
            assertTrue(id.substringBefore('-').toLong() > 0)
            val uuid = id.substringAfter('-')
            assertEquals(uuid, UUID.fromString(uuid).toString())
        }
        reveal("agent-phone-cancel-task")
        compose.onNodeWithTag("agent-phone-cancel-task").assertIsEnabled().assertTextContains("إلغاء المهمة").performClick()
        state.assertTextContains("تم الإلغاء")
        compose.runOnIdle { assertEquals(1, cancellations) }
    }

    @Test fun widePageShowsTheServerOutcomeAndStillOpensThePhone() {
        serverTask = "completed"
        show("en", width = 900)
        reveal("agent-phone-driver-state")
        compose.onNodeWithTag("agent-phone-driver-state").assertIsDisplayed().assertTextContains("Done")
        compose.onNodeWithTag("agent-phone-cancel-task").assertDoesNotExist()
        reveal("agent-phone-open")
        compose.onNodeWithTag("agent-phone-open").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun anUnavailableVisionModelHasAnArabicOutcome() {
        serverTask = "failed"
        serverMessage = "Configure a working vision model for this Hermes profile."
        show("ar", width = 320, fontScale = 2f)
        reveal("agent-phone-driver-state")
        compose.onNodeWithTag("agent-phone-driver-state").assertTextContains("فشل")
            .assertTextContains("اضبط نموذجًا للرؤية يعمل مع ملف Hermes هذا.")
    }

    @Test fun olderPluginKeepsTheManualControls() {
        show("en", width = 360, capability = false)
        reveal("agent-phone-open")
        compose.onNodeWithTag("agent-phone-open").assertIsEnabled().performClick()
        compose.onNodeWithTag("agent-phone-turn-off").assertIsEnabled()
        compose.onNodeWithTag("agent-phone-give-task").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun reveal(tag: String) =
        compose.onNodeWithTag("agent-phone-page").performScrollToNode(hasTestTag(tag))

    private fun densityFor(width: Int) = compose.activity.resources.displayMetrics.widthPixels.toFloat() / width

    private fun show(language: String, width: Int, fontScale: Float = 1f, capability: Boolean = true, loseFirstReply: Boolean = false) {
        val api = AgentPhoneApi(AgentPhoneEndpoint { method, route, body, _ ->
            when (method to route) {
                "GET" to "" -> AgentPhoneAnswer(200, JSONObject("""{"installed":true,"yours":true,"phone":"on"}""").apply {
                    if (capability) put("control_driver", "phonedriver")
                    if (serverTask.isNotBlank()) put("driver_task", summary(serverTask))
                })
                "GET" to "/policy" -> AgentPhoneAnswer(200, JSONObject("""{"enabled":true}"""))
                "POST" to "/task" -> {
                    requests += requireNotNull(body)
                    if (loseFirstReply && requests.size == 1) AgentPhoneAnswer(0, JSONObject())
                    else {
                        serverTask = "running"
                        AgentPhoneAnswer(202, summary(serverTask))
                    }
                }
                "POST" to "/task/synthetic-task/cancel" -> {
                    cancellations++
                    serverTask = "cancelled"
                    AgentPhoneAnswer(200, summary(serverTask))
                }
                else -> AgentPhoneAnswer(200, JSONObject())
            }
        })
        compose.setContent {
            val scope = rememberCoroutineScope()
            val controller = remember { AgentPhoneController(api, scope, ticks = null, cached = null) }
            val state by controller.state.collectAsState()
            LaunchedEffect(controller) { controller.refresh() }
            CompositionLocalProvider(
                LocalDensity provides Density(densityFor(width), fontScale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                NativeLocalization(language) {
                    BeaconTheme(dark = true) {
                        AgentPhonePage(state, "default", AgentPhonePageActions(open = { opened++ },
                            giveTask = controller::giveTask, cancelTask = controller::cancelTask))
                    }
                }
            }
        }
    }

    private fun summary(state: String) = JSONObject("""{"id":"synthetic-task","state":"$state","steps":1}""").apply {
        serverMessage?.let { put("message", it) }
    }
}
