package dev.hermes.android.ui

import dev.hermes.android.Message
import dev.hermes.android.core.runningTurnMessages
import dev.hermes.android.core.string
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R23 Should 1 (lane D-REOPEN): Hermes saves the model's message with its tool call before it runs the tool, so a chat opened mid-turn holds the call and no result. That stored call used to read
 * "No result recorded" whatever the chat showed. It reads Running while the chat shows the turn it belongs to running (the same signal as Stop), and only then: an earlier turn's call, or any
 * call once the turn has ended, keeps "No result recorded".
 */
class RunningTurnToolRowTest {
    private fun user(id: String, text: String = "Run the long check.", kind: String = "") = Message(id, "user", text, displayKind = kind)
    private fun assistant(id: String, vararg tools: JSONObject) = Message(id, "assistant", "", tools = tools.toList())
    private fun call(id: String, vararg extra: Pair<String, Any>) = JSONObject().put("tool_call_id", id).put("name", "terminal").also { o -> extra.forEach { o.put(it.first, it.second) } }

    /** What a stored card of [message] reads, the way the chat screen draws it: the call's own record, and whether its message belongs to the turn running now. */
    private fun reads(message: Message, tool: JSONObject, running: Set<String>): ToolOutcome {
        val explicit = tool.has("result") || tool.has("is_error")
        return toolOutcome("terminal", tool.optString("status"), tool.optBoolean("is_error"), tool.optString("result").ifBlank { null }, explicit, streaming = message.id in running)
    }

    @Test fun aStoredCallOfTheTurnRunningNowReadsRunning() {
        val saved = call("t1")
        val messages = listOf(user("1", "First question"), assistant("2", call("old", "result" to "done")), user("3"), assistant("4", saved))
        val running = runningTurnMessageIds(messages, turnRunning = true)
        assertTrue("the last turn's saved message belongs to the running turn", "4" in running)
        assertEquals(ToolOutcome.Running, reads(messages[3], saved, running))
    }

    @Test fun anEarlierTurnIsNeverRunningWhileALaterOneRuns() {
        // The first turn ended without a result for its call (a stopped or failed turn); a second turn runs now.
        val orphan = call("orphan")
        val live = call("live")
        val messages = listOf(user("1", "First question"), assistant("2", orphan), user("3", "Second question"), assistant("4", live))
        val running = runningTurnMessageIds(messages, turnRunning = true)
        assertFalse("an earlier turn is not the running one", "2" in running)
        assertEquals(ToolOutcome.NoResult, reads(messages[1], orphan, running))
        assertTrue("4" in running)
        assertEquals(ToolOutcome.Running, reads(messages[3], live, running))
    }

    @Test fun aSettledChatKeepsNoResultRecorded() {
        val saved = call("t1")
        val messages = listOf(user("1"), assistant("2", saved))
        // The turn ended (done, stopped or failed): the chat shows no stream, and a call that never got a result reads "No result recorded" as before.
        val running = runningTurnMessageIds(messages, turnRunning = false)
        assertTrue(running.isEmpty())
        assertEquals(ToolOutcome.NoResult, reads(messages[1], saved, running))
    }

    @Test fun aSteeringNoteOrASteerContinuesTheTurn() {
        // A "[System: ...]" note and a steer the person typed during the turn are not new turns: the messages before them still belong to the running one.
        val a = call("a")
        val b = call("b")
        val c = call("c")
        val messages = listOf(user("1"), assistant("2", a), user("3", "[System: Continue now. Execute the required tool calls.]"), assistant("4", b),
            user("5", "Also check the other folder.", kind = "steer"), assistant("6", c))
        val running = runningTurnMessageIds(messages, turnRunning = true)
        assertEquals(setOf("1", "2", "3", "4", "5", "6"), running)
        listOf(1 to a, 3 to b, 5 to c).forEach { (index, tool) -> assertEquals(ToolOutcome.Running, reads(messages[index], tool, running)) }
    }

    @Test fun aCallWithAResultOrAnErrorKeepsItsOutcomeInTheRunningTurn() {
        val done = call("done", "result" to "total 0")
        val failed = call("failed", "is_error" to true, "result" to "exit 1")
        val emptyButFinished = call("empty", "result" to "", "is_error" to false)
        val open = call("open")
        val messages = listOf(user("1"), assistant("2", done, failed, emptyButFinished, open))
        val running = runningTurnMessageIds(messages, turnRunning = true)
        assertEquals(ToolOutcome.Completed, reads(messages[1], done, running))
        assertEquals(ToolOutcome.Failed, reads(messages[1], failed, running))
        assertEquals(ToolOutcome.Completed, reads(messages[1], emptyButFinished, running))
        assertEquals("only the call with no result reads Running", ToolOutcome.Running, reads(messages[1], open, running))
    }

    @Test fun withNoUserMessageTheWholeChatIsTheRunningTurn() {
        // The same boundary core.runningTurnMessages draws for the saved rows: with no message that begins a turn, nothing before the running turn can be told apart.
        val messages = listOf(assistant("1", call("a")), assistant("2", call("b")))
        assertEquals(setOf("1", "2"), runningTurnMessageIds(messages, turnRunning = true))
    }

    @Test fun theBoundaryAgreesWithTheRowsTheViewModelUses() {
        // The view model finds the running turn's rows with runningTurnMessages (core); the chat screen must draw the same turn.
        val messages = listOf(user("1"), assistant("2"), user("3"), assistant("4"), user("5", "[System: Continue now.]"), assistant("6"))
        val rows = messages.map { JSONObject().put("row_id", it.id).put("role", it.role).put("text", it.text) }
        assertEquals(runningTurnMessages(rows).map { it.string("row_id") }.toSet(), runningTurnMessageIds(messages, turnRunning = true))
    }
}
