package dev.hermes.android.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolStepsTest {
    private fun tool(name: String, args: JSONObject? = null) = JSONObject().put("name", name).apply { args?.let { put("arguments", it.toString()) } }
    private fun batch(vararg names: String) = tool("tool_call", JSONObject().put("calls", JSONArray().apply { names.forEach { put(JSONObject().put("name", it).put("arguments", JSONObject())) } }))

    @Test fun batchedToolCallIsNamedByTheToolsItRan() {
        assertEquals("list calendar events · personal", toolDisplayName(batch("mcp__personal__list_calendar_events")))
        assertEquals("search inbox · personal and 1 more", toolDisplayName(batch("mcp__personal__search_inbox", "mcp__personal__list_tasks")))
        assertEquals("read file", toolDisplayName(tool("tool_call", JSONObject().put("name", "read_file"))))
        assertEquals("tool call", toolDisplayName(tool("tool_call")))
    }

    @Test fun aStepSummaryNamesTheWorkNotHermesLookups() {
        val steps = listOf(tool("skills_list"), tool("tool_search"), tool("tool_describe"),
            batch("mcp__personal__list_calendar_events"), batch("mcp__personal__search_inbox"), batch("mcp__personal__list_tasks"))
        assertEquals("6 steps · List calendar events · personal, search inbox · personal, list tasks · personal", toolStepsSummary(steps))
        assertEquals("3 steps", toolStepsSummary(listOf(tool("tool_search"), tool("tool_describe"), tool("skills_list"))))
    }

    @Test fun consecutiveToolOnlyRoundsShowAsOneActivity() {
        fun m(id: String, role: String, text: String = "", vararg tools: String) =
            dev.hermes.android.Message(id, role, text, tools = tools.map { tool(it) }, recordedAt = id.hashCode().toLong())
        val rows = listOf(m("u", "user", "Plan tomorrow"), m("a1", "assistant", "", "skills_list"), m("a2", "assistant", "", "tool_search"),
            m("a3", "assistant", "", "tool_call", "tool_call"), m("a4", "assistant", "Tomorrow: review at 14:00"), m("a5", "assistant", "", "read_file"))
        val merged = mergeToolOnlyRuns(rows.withIndex().toList())
        assertEquals(listOf("u", "a1", "a4", "a5"), merged.map { it.value.id })
        assertEquals(listOf("skills_list", "tool_search", "tool_call", "tool_call"), merged[1].value.tools.map { it.getString("name") })
        assertEquals(1, merged[1].index)
    }
}
