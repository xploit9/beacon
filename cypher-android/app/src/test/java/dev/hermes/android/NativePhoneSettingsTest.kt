package dev.hermes.android

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class NativePhoneSettingsTest {
    @Test(timeout = 10_000) fun editingVisibleMemoryWhileRefreshingPreservesTheDraftAndBase() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
            builder.addInterceptor { chain ->
                val req = chain.request()
                val result = if (req.url.encodedPath == "/api/memory") json("active" to "builtin") else {
                    val fresh = reads.incrementAndGet() > 1
                    if (fresh) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                    json("documents" to org.json.JSONArray().put(json("target" to "memory", "content" to if(fresh) "new server notes" else "original notes", "revision" to (if(fresh) "b" else "a").repeat(64), "enabled" to true, "limit" to 2000)))
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NativePhoneSettings(worker, SavedStateHandle()) { client }
            editor.loadMemory(); withTimeout(5000) { editor.memory.first { it.documents.isNotEmpty() && !it.loading } }
            editor.loadMemory(); assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            editor.editMemory("memory"); editor.memoryDraft("my unsaved notes"); release.countDown()
            worker.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals("memory", editor.memory.value.editing)
            assertEquals("my unsaved notes", editor.memory.value.draft)
            assertEquals("a".repeat(64), editor.memory.value.documents.single().revision)
            editor.editMemory("")
            withTimeout(5000) { editor.memory.first { it.documents.singleOrNull()?.revision == "b".repeat(64) } }
        } finally { release.countDown(); worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 10_000) fun aRecoveredMemoryDraftWinsOverAnInFlightRefresh() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
            builder.addInterceptor { chain ->
                val req = chain.request()
                val result = if (req.url.encodedPath == "/api/memory") json("active" to "builtin") else {
                    entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    json("documents" to org.json.JSONArray().put(json("target" to "memory", "content" to "new server notes", "revision" to "d".repeat(64), "enabled" to true, "limit" to 2000)))
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            // No document is visible yet: the first read is in flight with no known base.
            controller.bind(); controller.loadMemory()
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            assertTrue(controller.memory.value.loading && controller.memory.value.documents.isEmpty())
            controller.editMemory("memory")
            // Editing a visible, known base during a refresh is allowed (see editingVisibleMemoryWhileRefreshing...);
            // an editor without any base is not.
            assertTrue("Do not enter a blank-base editor from a refresh in flight", controller.memory.value.editing.isBlank())
            assertEquals("", controller.memory.value.draft)
            controller.restoreDraft(draftFixture(client.scope.key))
            assertEquals("memory", controller.memory.value.editing)
            release.countDown()
            worker.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            // The released read (new content, revision d) must not replace the recovered draft or its original revision.
            assertEquals("memory", controller.memory.value.editing)
            assertEquals("edited notes", controller.memory.value.draft)
            assertEquals("original notes", controller.memory.value.documents.single().content)
            assertEquals("b".repeat(64), controller.memory.value.documents.single().revision)
            assertEquals("b".repeat(64), controller.memory.value.documents.single().saveBody(controller.memory.value.draft).getString("revision"))
            // A newer edit also survives a repeated recovery.
            controller.memoryDraft("newer notes"); controller.restoreDraft(draftFixture(client.scope.key))
            assertEquals("newer notes", controller.memory.value.draft)
        } finally { release.countDown(); worker.cancel(); client.closeNative() }
    }

    private fun draftFixture(scope: String) = json("version" to 1, "scope" to scope,
        "skill" to json("name" to "test-skill", "description" to "Test", "category" to "test", "provenance" to "test", "requirements" to org.json.JSONArray().put("Set up service"), "enabled" to true, "essential" to false, "base" to "original instructions", "draft" to "edited instructions", "revision" to "a".repeat(64)),
        "memory" to json("target" to "memory", "base" to "original notes", "draft" to "edited notes", "revision" to "b".repeat(64), "enabled" to true, "limit" to 2000, "provider" to "", "providerReady" to true),
        "bot" to json("profile" to "academic", "title" to "Academic", "description" to "Study helper", "soul" to "Original behavior", "baseProvider" to "configured", "baseModel" to "original-model", "draftProvider" to "configured", "draftModel" to "edited-model", "revision" to "c".repeat(64)))

    @Test fun draftRoundTripKeepsOriginalBasesAndSignalsExplicitDiscard() {
        val client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var changed = 0
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle(), draftChanged = { changed++ }) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            val snapshot = controller.snapshotDraft()
            val restored = NativePhoneSettings(worker, SavedStateHandle()) { client }
            restored.bind(); restored.restoreDraft(snapshot)
            assertEquals("original instructions", restored.skills.value.content!!.content)
            assertEquals("a".repeat(64), restored.skills.value.content!!.revision)
            assertEquals("edited instructions", restored.skills.value.draft)
            assertEquals(listOf("Set up service"), restored.skills.value.selected!!.requirements)
            assertEquals("original notes", restored.memory.value.documents.single().content)
            assertEquals("b".repeat(64), restored.memory.value.documents.single().saveBody("edited notes").getString("revision"))
            assertEquals("original-model", restored.bots.value.selected!!.model)
            assertEquals("Original behavior", restored.bots.value.selected!!.soul)
            assertEquals("c".repeat(64), restored.bots.value.selected!!.saveModel("configured", "edited-model").getString("revision"))
            assertEquals("edited-model", restored.bots.value.draftModel)
            controller.skillEdit(false); controller.editMemory(""); controller.editBot(false)
            assertEquals(setOf("version", "scope"), controller.snapshotDraft().keys().asSequence().toSet())
            assertEquals(4, changed)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test fun malformedForeignAndOversizedDraftsAreRejectedAtomically() {
        val client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }; controller.bind()
            controller.restoreDraft(draftFixture(client.scope.key))
            val existing = controller.snapshotDraft().toString()
            val wrongScope = draftFixture(client.scope.key).put("scope", "other-owner")
            val wrongVersion = draftFixture(client.scope.key).put("version", 2)
            val missingBase = draftFixture(client.scope.key).apply { getJSONObject("memory").remove("base") }
            val badRevision = draftFixture(client.scope.key).apply { getJSONObject("bot").put("revision", "fresh") }
            val coercedFlag = draftFixture(client.scope.key).apply { getJSONObject("skill").put("enabled", "true") }
            val oversized = draftFixture(client.scope.key).put("extra", "x".repeat(1_048_577))
            for (payload in listOf(wrongScope, wrongVersion, missingBase, badRevision, coercedFlag, oversized)) {
                controller.restoreDraft(payload)
                assertEquals(existing, controller.snapshotDraft().toString())
            }
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test fun restoredDraftCannotClobberNewerEditAndProfileMapsRemainIsolated() {
        var client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val first = client
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }; controller.bind()
            val snapshot = draftFixture(client.scope.key); controller.restoreDraft(snapshot)
            controller.skillDraft("newer instructions"); controller.memoryDraft("newer notes"); controller.botDraft("configured", "newer-model")
            controller.restoreDraft(snapshot)
            assertEquals("newer instructions", controller.skills.value.draft)
            assertEquals("newer notes", controller.memory.value.draft)
            assertEquals("newer-model", controller.bots.value.draftModel)
            client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "other")); controller.bind()
            controller.restoreDraft(snapshot)
            assertFalse(controller.skills.value.editing)
            assertEquals("", controller.memory.value.editing)
            client.closeNative(); client = first; controller.bind()
            assertEquals("newer instructions", controller.skills.value.draft)
            assertEquals("newer notes", controller.memory.value.draft)
            assertEquals("newer-model", controller.bots.value.draftModel)
        } finally { worker.cancel(); client.closeNative(); first.closeNative() }
    }
    @Test fun memoryConflictPreservesDraftAndSuccessfulReadbackBecomesBaseline() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        var conflict = true
        var saved = "initial"
        val revision = "a".repeat(64)
        val confirmedRevision = "b".repeat(64)
        val client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
            builder.addInterceptor { chain ->
                val req = chain.request(); requests.add(req)
                val doc = json("target" to "memory", "content" to saved, "revision" to revision, "enabled" to true, "limit" to 2000)
                var code = 200
                val body = when {
                    req.url.encodedPath == "/api/memory" -> json("active" to "builtin")
                    req.method == "PUT" && conflict -> { code = 409; json("detail" to "Changed on host") }
                    req.method == "PUT" -> { val buffer = okio.Buffer(); req.body!!.writeTo(buffer); val input = org.json.JSONObject(buffer.readUtf8()); assertEquals(revision, input.getString("revision")); saved = input.getString("content"); json("ok" to true, "document" to doc.put("content", saved).put("revision", confirmedRevision)) }
                    else -> json("documents" to org.json.JSONArray().put(doc))
                }
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("Test response").body(body.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.loadMemory()
            withTimeout(5000) { controller.memory.first { !it.loading && it.documents.isNotEmpty() } }
            controller.editMemory("memory"); controller.memoryDraft("my changed notes"); controller.saveMemory()
            withTimeout(5000) { controller.memory.first { !it.saving && it.error.isNotBlank() } }
            assertEquals("my changed notes", controller.memory.value.draft)
            assertEquals(revision, controller.memory.value.documents.single().revision)
            assertEquals("initial", saved)
            conflict = false; controller.saveMemory()
            withTimeout(5000) { controller.memory.first { !it.saving && it.message.isNotBlank() } }
            assertEquals("my changed notes", controller.memory.value.documents.single().content)
            assertEquals(confirmedRevision, controller.memory.value.documents.single().revision)
            assertEquals("", controller.memory.value.editing)
            assertTrue(requests.all { it.url.queryParameter("profile") == "default" })
        } finally { worker.cancel(); client.closeNative() }
    }
    @Test fun unresolvedHostUpdateSurvivesProfileSwitchAndBlocksAnotherApply() {
        var client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val saved = SavedStateHandle(mapOf("server-update:https://native.example:owner:at" to "2026-10-14T10:00:00Z", "server-update:https://native.example:owner:id" to "active"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = NativePhoneSettings(worker, saved) { client }
        controller.bind()
        assertEquals("active", controller.server.value.requestedActionId)
        client = CypherClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "academic"))
        controller.bind()
        assertEquals("active", controller.server.value.requestedActionId)
        assertFalse(controller.server.value.copy(update = json("can_apply" to true, "update_available" to true)).canApply)
        worker.cancel(); client.closeNative()
    }
    @Test fun boundedRecoveryKeepsLongUnsavedEditsForTheOwnerToShorten() {
        val client=CypherClient(AuthContext("https://native.example",emptyList(),owner="owner"))
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val original=NativePhoneSettings(worker,SavedStateHandle()) { client };original.bind()
            original.restoreDraft(draftFixture(client.scope.key));original.skillDraft("x".repeat(260_001))
            val restored=NativePhoneSettings(worker,SavedStateHandle()) { client };restored.bind();restored.restoreDraft(original.snapshotDraft())
            assertEquals(original.skills.value.draft,restored.skills.value.draft)
            original.skillDraft("x".repeat(1_048_576))
            assertTrue(runCatching { original.snapshotDraft() }.exceptionOrNull() is IllegalArgumentException)
        } finally { worker.cancel();client.closeNative() }
    }

}
