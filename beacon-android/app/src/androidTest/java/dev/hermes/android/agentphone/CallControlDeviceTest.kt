package dev.hermes.android.agentphone

import android.app.Service
import android.app.Instrumentation.ActivityMonitor
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.ui.touchCallControl
import dev.hermes.android.voice.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** Actual control activity and approval service, with an in-memory phone socket and a local HTTP interceptor. No live call or provider. */
class CallControlDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as BeaconApplication
    private val callId = "synthetic-call-x2"
    private val request = "synthetic-request-x2"
    private val proposal = "p".repeat(32)

    private class Phone(private val http: BeaconViewTransport) : ViewTransport, ViewConnection {
        lateinit var listener: ViewListener
        @Volatile var held = false
        @Volatile var replaceOnDone = false
        @Volatile var epoch = 7L
        val handBacks = CopyOnWriteArrayList<Long>()
        val messages = CopyOnWriteArrayList<String>()
        override suspend fun ticket() = ViewTicket("synthetic")
        override fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection {
            this.listener = listener
            held = true
            listener.onOpen()
            listener.onText("""{"t":"state","lease":"you","epoch":7,"rotation":0,"helper":"ready","keyboard":false}""")
            return this
        }
        override fun send(text: String): Boolean {
            val type = JSONObject(text).getString("t")
            messages += type
            if (type == "done") {
                if (replaceOnDone) epoch++ else {
                    held = false
                    listener.onText("""{"t":"bye","reason":"done"}""")
                }
                listener.onClosed(1000)
            }
            return true
        }
        override fun close(code: Int) = Unit
        override fun cancel() = Unit
        override suspend fun phone() = "on" to ""
        override suspend fun start(): StartAnswer = StartAnswer.On
        override suspend fun handBack(epoch: Long) = http.handBack(epoch)
    }

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun withControl(check: (Phone, List<String>, ActivityMonitor, ActivityScenario<AgentPhoneActivity>) -> Unit) {
        val account = AuthContext("https://x2.example.com", emptyList(), "synthetic-x2", backend = "hermes-dashboard", nativeProvider = "fixture")
        val receipts = CopyOnWriteArrayList<String>()
        lateinit var phone: Phone
        val client = BeaconClient(account, null, persistContext = false, configureHttp = { http ->
            http.addInterceptor { chain ->
                val req = chain.request()
                var status = 200
                val body = if (req.url.encodedPath.endsWith("/requests/$request/decision")) {
                    val answer = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8())
                    require(answer.getString("proposal_id") == proposal)
                    receipts += answer.getString("decision")
                    json("request_id" to request, "tool_name" to "agent_phone", "status" to "failed",
                        "error_code" to "phone_in_use", "reason" to "You're using the phone", "ran_on_hermes" to true)
                } else if (req.url.encodedPath == ViewProtocol.HAND_BACK_PATH) {
                    val epoch = JSONObject(Buffer().also { req.body!!.writeTo(it) }.readUtf8()).getLong("epoch")
                    if (epoch == phone.epoch) phone.held = false else status = 409
                    phone.handBacks += epoch
                    JSONObject()
                } else JSONObject()
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        })
        val calls = app.calls
        val previous = calls.state.value
        val previousAccount = calls.ownerScope
        require(!previous.active && calls.service == null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val service = BeaconCallService()
        // Attach the service without starting Android Telecom, a microphone or a provider connection.
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply { isAccessible = true }.invoke(service, app)
        Service::class.java.getDeclaredField("mApplication").apply { isAccessible = true }.set(service, app)
        val pending = CallToolApproval(request, proposal, "agent_phone", """{"action":"open_app","package":"com.example.notes"}""")
        phone = Phone(BeaconViewTransport(client))
        val direct = DirectVoice(client, callId, "synthetic", scope, {}, { event ->
            CallToolReceipt.from(event)?.let { receipt -> calls.update { it.copy(toolApprovals = emptyList(), toolReceipts = listOf(receipt)) } }
        }, {}, VoiceSessionCredentials("gemini", "synthetic", "synthetic", "synthetic", ""))
        val ledger = field(direct, "gatewayReceipts").get(direct) as DirectGatewayReceipts
        ledger.reserve(request, "function", "agent_phone", JSONObject(pending.arguments))
        ledger.record(request, json("request_id" to request, "tool_name" to "agent_phone", "status" to "needs_approval",
            "proposal" to json("id" to proposal, "tool_name" to "agent_phone", "arguments" to JSONObject(pending.arguments))))
        field(service, "spec").set(service, CallSpec(client, json("call_id" to callId)))
        field(service, "directVoice").set(service, direct)
        val monitor = instrumentation.addMonitor(BeaconActivity::class.java.name, null, true)
        try {
            app.auth.write(account)
            calls.ownerScope = client.scope
            calls.service = service
            val intent = Intent(app, AgentPhoneActivity::class.java).putExtra(AgentPhoneActivity.EXTRA_CONTROL, true)
                .putExtra(BeaconCallService.EXTRA_CALL_ID, callId)
            ActivityScenario.launch<AgentPhoneActivity>(intent).use { viewer ->
                viewer.onActivity { activity ->
                    val model = ViewModelProvider(activity)[AgentPhoneViewModel::class.java]
                    field(model, "signedIn").set(model, client)
                    val session = model.session
                    field(session, "transport").set(session, phone)
                    session.retry()
                    calls.update { CallState(active = true, callId = callId, conversationScope = client.scope, toolApprovals = listOf(pending)) }
                }
                compose.waitUntil(10_000) { phone.held && calls.approvalCardOnScreen(request) }
                assertFalse(calls.uiVisible)
                assertFalse(calls.approvalUiVisible("other-request", client.scope))
                assertFalse(calls.approvalUiVisible(request, client.scope.copy(profile = "another-profile")))
                check(phone, receipts, monitor, viewer)
            }
        } finally {
            calls.service = null
            calls.update { previous }
            calls.ownerScope = previousAccount
            app.auth.clear(account.origin)
            scope.cancel()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun allowOnceOverTakeControlReachesTheDecisionEndpointAndShowsTheHold() = answerOverControl(byTouch = false)
    @Test fun aRealTouchOverTakeControlAnswersTheSameCard() = answerOverControl(byTouch = true)

    private fun answerOverControl(byTouch: Boolean) = withControl { phone, decisions, _, _ ->
        if (byTouch) compose.touchCallControl("beacon-tool-allow") else compose.onNodeWithTag("beacon-tool-allow").performClick()
        compose.waitUntil(5_000) { decisions.isNotEmpty() }
        assertEquals(listOf("approve"), decisions)
        assertTrue(phone.held)
        compose.waitUntil(5_000) { app.calls.state.value.toolApprovals.isEmpty() }
        assertEquals("You're using the phone", app.calls.state.value.toolReceipts.single().chipLabel())
    }

    @Test fun switchingAppsDuringControlKeepsTheHoldUntilReturnToCall() = withControl { phone, _, monitor, viewer ->
        viewer.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5_000) { !phone.held || "background" in phone.messages }
        assertTrue("The owner still holds the phone while checking another app", phone.held)
        assertEquals(0, monitor.hits)
        viewer.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { "foreground" in phone.messages }
        assertTrue(phone.held)
        compose.onNodeWithTag("agent-phone-show-call").performClick()
        compose.waitUntil(5_000) { !phone.held && monitor.hits == 1 }
        assertEquals(1, phone.messages.count { it == "done" })
    }

    @Test fun returnToCallEndsTheOwnersHold() = withControl { phone, _, monitor, _ ->
        compose.onNodeWithTag("agent-phone-show-call").performClick()
        compose.waitUntil(5_000) { !phone.held && monitor.hits == 1 }
    }

    @Test fun doneEndsTheOwnersHoldWithoutOpeningTheCall() = withControl { phone, _, monitor, _ ->
        compose.onNodeWithTag("agent-phone-done").performClick()
        compose.waitUntil(5_000) { !phone.held }
        assertEquals(1, phone.messages.count { it == "done" })
        assertEquals(0, monitor.hits)
    }

    @Test fun lostDonePostsOnlyTheOldAcquisitionAfterAnotherTakeover() = withControl { phone, _, monitor, _ ->
        phone.replaceOnDone = true
        compose.onNodeWithTag("agent-phone-show-call").performClick()
        compose.waitUntil(5_000) { phone.handBacks.isNotEmpty() && monitor.hits == 1 }
        assertEquals(listOf(7L), phone.handBacks)
        assertEquals(8L, phone.epoch)
        assertTrue(phone.held)
    }
}
