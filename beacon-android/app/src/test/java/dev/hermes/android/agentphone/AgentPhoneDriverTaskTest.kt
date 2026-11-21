package dev.hermes.android.agentphone

import dev.hermes.android.data.*
import dev.hermes.android.ui.AgentPhoneController
import dev.hermes.android.ui.agentPhonePageCopy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPhoneDriverTaskTest {
    private val running = """{"id":"server-id","state":"running","steps":0}"""
    private val owned = """{"installed":true,"yours":true,"control_driver":"phonedriver","phone":"off"}"""

    @Test fun theCacheKeepsCapabilityButDriverTaskStatusComesFromTheServer() {
        val status = JSONObject("""{"control_driver":"phonedriver","driver_task":{"id":"abc","state":"running","steps":0}}""")
        val cached = agentPhonePageCopy(status, null)
        assertEquals("phonedriver", cached.getString("control_driver"))
        assertFalse(cached.has("driver_task"))
        assertTrue(status.has("driver_task"))
    }

    @Test fun driverCapabilityIsOptionalAndDoesNotChangePowerOrSetup() {
        val noSetup = parseAgentPhoneStatus(JSONObject("""{"control_driver":"phonedriver"}"""))
        assertTrue(noSetup.supportsPhoneDriver)
        assertFalse(noSetup.installed)
        assertEquals(AgentPhonePower.Off, noSetup.power)
        assertNull(noSetup.driverTask)

        val body = JSONObject(owned).put("driver_task", JSONObject(running))
        assertEquals(AgentPhoneDriverTask("server-id", AgentPhoneDriverState.Running, 0, null), parseAgentPhoneStatus(body).driverTask)
        body.put("yours", false)
        assertNull(parseAgentPhoneStatus(body).driverTask)
        body.remove("control_driver")
        assertFalse(parseAgentPhoneStatus(body).supportsPhoneDriver)
        body.put("control_driver", "another-driver")
        assertFalse(parseAgentPhoneStatus(body).supportsPhoneDriver)
    }

    @Test fun unknownStateOrUnsafeTaskIdIsNotCancelable() {
        assertNull(parseAgentPhoneDriverTask(JSONObject(running).put("state", "waiting")))
        assertNull(parseAgentPhoneDriverTask(JSONObject(running).put("id", "../stop")))
        assertNull(parseAgentPhoneDriverTask(JSONObject(running).put("steps", 0.5)))
        assertFalse(validAgentPhoneTask(" \n"))
        val unicode = "📱".repeat(2_000)
        assertTrue(validAgentPhoneTask(unicode))
        assertFalse(validAgentPhoneTask(unicode + "x"))
    }

    @Test fun disabledPolicyAndLegacyStatusDoNotSubmitTasks() = runTest {
        val phone = FakePhone().on("GET", "", 200, owned)
            .on("GET", "/policy", 200, """{"enabled":false}""")
        val controller = AgentPhoneController(AgentPhoneApi(phone), this, ticks = null, cached = null)
        controller.refresh()
        assertFalse(controller.state.value.canGiveTask)
        assertTrue(controller.giveTask("req", "Open Settings") is AgentPhoneResult.Refused)

        phone.now("GET", "", 200, """{"installed":true,"yours":true,"phone":"on"}""")
        phone.now("GET", "/policy", 200, """{"enabled":true}""")
        controller.refresh()
        assertTrue(controller.giveTask("req", "Open Settings") is AgentPhoneResult.Refused)
        assertFalse(phone.calls.any { it.method == "POST" })
    }

    @Test fun aReadStartedBeforeSubmissionDoesNotOverwriteTheTaskReceipt() = runTest {
        val oldReadStarted = CompletableDeferred<Unit>()
        val finishOldRead = CompletableDeferred<Unit>()
        var pauseStatus = false
        val endpoint = AgentPhoneEndpoint { method, route, _, _ ->
            when (method to route) {
                "GET" to "" -> {
                    if (pauseStatus) {
                        oldReadStarted.complete(Unit)
                        finishOldRead.await()
                    }
                    AgentPhoneAnswer(200, JSONObject(owned))
                }
                "GET" to "/policy" -> AgentPhoneAnswer(200, JSONObject("""{"enabled":true}"""))
                "POST" to "/task" -> AgentPhoneAnswer(202, JSONObject(running))
                else -> AgentPhoneAnswer(200, JSONObject())
            }
        }
        val controller = AgentPhoneController(AgentPhoneApi(endpoint), this, ticks = null, cached = null)
        controller.refresh()
        assertTrue(controller.state.value.canGiveTask)

        pauseStatus = true
        val poll = async { controller.refresh(all = false) }
        oldReadStarted.await()
        assertTrue(controller.giveTask("req", "Open Settings") is AgentPhoneResult.Done)
        finishOldRead.complete(Unit)
        poll.await()

        assertEquals("server-id", controller.state.value.status?.driverTask?.id)
        assertTrue(controller.state.value.driverTaskRunning)
        assertEquals(2_000L, controller.pollMillis())
    }
}
