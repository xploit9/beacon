package dev.hermes.android.ui

import dev.hermes.android.data.*
import dev.hermes.android.core.json
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RedesignKanbanTest {
    private val card = """{"id":"t_1","title":"Native task","body":"Details","assignee":"work","status":"todo","priority":2}"""
    @Test fun canonicalNativeBoardCrudAlwaysPinsTheSelectedBoard() = runBlocking<Unit> {
        val requests = mutableListOf<Request>()
        val c = CypherClient(AuthContext("https://native.example", emptyList(), owner="owner", profile="work", backend="hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests.add(request)
                val body = when {
                    request.url.encodedPath.endsWith("/boards") -> """{"boards":[{"slug":"default","name":"Personal"},{"slug":"work","name":"Work"}],"current":"work"}"""
                    request.url.encodedPath.endsWith("/board") -> """{"columns":[{"name":"todo","tasks":[$card]}]}"""
                    request.method == "DELETE" -> """{"deleted":true}"""
                    else -> """{"task":$card}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }
        val boards = NativeBoards(c)
        val choices = boards.choices(); assertEquals("work", choices.second); assertEquals("Personal", choices.first.first().name)
        assertEquals("t_1", boards.read("work").single().id)
        assertEquals("work", boards.detail("work", "t_1").assignee)
        val key = "d47e7c8c-2c24-483a-88ba-30ecf7c8e153" // gitleaks:allow -- deterministic test idempotency UUID, not a credential
        val fields = boardTaskFields("New", "Body", "work")
        boards.create("work", fields, key); boards.create("work", fields, key)
        boards.update("work", "t_1", boardMoveFields("ready")); boards.delete("work", "t_1")
        assertTrue(requests.drop(1).all { it.url.queryParameter("board") == "work" })
        assertTrue(requests.all { it.url.encodedPath.startsWith("/api/plugins/kanban/") })
        val posts = requests.filter { it.method == "POST" }.map { val buffer = okio.Buffer(); it.body!!.writeTo(buffer); JSONObject(buffer.readUtf8()) }
        assertEquals(posts[0].getString("idempotency_key"), posts[1].getString("idempotency_key"))
        assertFalse(posts[0].has("status"))
        val before = requests.size
        assertTrue(runCatching { boards.read("../other") }.isFailure)
        assertTrue(runCatching { boards.delete("work", "../other") }.isFailure)
        assertEquals(before, requests.size)
    }
    @Test fun nativeWorkflowAndLimitsFailClosed() {
        assertTrue(runCatching { boardMoveFields("running") }.isFailure)
        assertTrue(runCatching { boardMoveFields("imagined") }.isFailure)
        assertEquals("done", boardMoveFields("done").getString("status"))
        assertTrue(runCatching { boardTaskFields(" ", "", "") }.isFailure)
        assertTrue(runCatching { boardTaskFields("Task", "x".repeat(16_001), "") }.isFailure)
        assertTrue(runCatching { boardTaskFields("Task", "", "evil\nprofile") }.isFailure)
        assertEquals("Task", boardTaskFields(" Task ", "Body", "work").getString("title"))
        assertFalse(NativeOperationPolicy.direct("/api/plugins/kanban/tasks/t_1/force"))
        assertFalse(NativeOperationPolicy.direct("/api/plugins/kanban/../settings"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/kanban/tasks/t_1"))
    }
    @Test fun boardParserPreservesRealStatusAndDeduplicates() {
        val rows = json("columns" to org.json.JSONArray().put(json("name" to "review", "tasks" to org.json.JSONArray().put(JSONObject(card)).put(JSONObject(card)))))
        assertEquals("review", nativeBoardCards(rows).single().status)
        rows.getJSONArray("columns").getJSONObject(0).put("name", "unsupported")
        assertTrue(runCatching { nativeBoardCards(rows) }.isFailure)
    }
}
