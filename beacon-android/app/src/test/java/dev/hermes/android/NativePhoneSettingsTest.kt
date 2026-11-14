package dev.hermes.android

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class NativePhoneSettingsTest {
    private val updateId = "a".repeat(32)
    private val updateRequestedAt = "2026-11-07T00:00:00Z"
    private fun updateClient(reply: (Request) -> JSONObject) = BeaconClient(AuthContext("https://updater.example", emptyList(), owner = "owner", nativeProvider = "basic"), null) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(reply(request).toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    /** Hermes' own routes: GET /api/actions/hermes-update/status (with its receipt summary) and GET /api/hermes/update/receipt (the latest full receipt). */
    private fun updateReceipt(finished: Boolean) = json("action_id" to updateId, "started_at" to "2026-11-07T00:00:01Z",
        "finished_at" to if (finished) "2026-11-07T00:10:01Z" else "", "outcome" to if (finished) "success" else "running",
        "followups" to org.json.JSONArray().put(json("step" to "gateway", "reason" to "manual restart owed")))
    private fun updateStatus(path: String, running: Boolean, finished: Boolean = false, sequence: Int = 0) =
        if (path == "/api/hermes/update/receipt") json("receipt" to updateReceipt(finished))
        else json("name" to "hermes-update", "running" to running, "exit_code" to JSONObject.NULL, "sequence" to sequence, "receipt" to json("action_id" to updateId, "outcome" to if (finished) "success" else "running"))
    private val updateRoutes = setOf("/api/actions/hermes-update/status", "/api/hermes/update/receipt")
    private fun accepted() = json("ok" to true, "pid" to 4242, "name" to "hermes-update", "action_id" to updateId)
    private fun readyUpdate() = json("can_apply" to true, "update_available" to true)
    // The client uses real IO threads; drain ready work without advancing the virtual retry delays.
    private fun TestScope.awaitUpdateIo(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate()) {
            testScheduler.runCurrent()
            check(System.nanoTime() < deadline) { "Updater fixture IO did not settle" }
            Thread.sleep(1)
        }
        testScheduler.runCurrent()
    }

    @Test(timeout = 30_000) fun tenMinuteUpdateSurvivesReloadAndVersionCheckFailureWithoutAnotherPost() = runTest {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val failCheck = java.util.concurrent.atomic.AtomicBoolean()
        val client = updateClient { request -> when (request.url.encodedPath) {
            "/api/hermes/update/check" -> { if (failCheck.get()) throw java.io.IOException("synthetic version-check outage"); readyUpdate() }
            "/api/hermes/update" -> { posts.incrementAndGet(); accepted() }
            in updateRoutes -> updateStatus(request.url.encodedPath, posts.get() > 0 && !finished.get(), finished.get(), reads.incrementAndGet())
            else -> error("Unexpected updater fixture request")
        } }
        val saved = SavedStateHandle()
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = NativePhoneSettings(worker, saved) { client }
            controller.loadServer("Server updates"); awaitUpdateIo { controller.server.value.canApply }
            controller.applyServerUpdate(); controller.applyServerUpdate()
            awaitUpdateIo { posts.get() == 1 && controller.server.value.updateStatusFresh && controller.server.value.applying }
            repeat(301) { index ->
                val nextRead = reads.get() + 1
                advanceTimeBy(2000); runCurrent()
                awaitUpdateIo { (controller.server.value.action?.optInt("sequence") ?: 0) >= nextRead }
                if (index == 30) {
                    failCheck.set(true); controller.loadServer("Server updates", force = true)
                    awaitUpdateIo { !controller.server.value.loading && controller.server.value.error.isNotBlank() }
                    assertTrue(controller.server.value.applying)
                }
            }
            assertTrue(reads.get() > 60)
            finished.set(true); advanceTimeBy(2000); runCurrent()
            awaitUpdateIo { controller.server.value.outcome == "success" }
            assertFalse(controller.server.value.applying)
            assertTrue(controller.server.value.updateWarnings.any { it.contains("manual restart owed") })
            assertEquals(updateId, controller.server.value.requestedActionId)
            assertEquals(1, posts.get())
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun boundedObservationBecomesUnconfirmedAndSavedIdentityResumesWithoutPost() = runTest {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val finished = java.util.concurrent.atomic.AtomicBoolean()
        val client = updateClient { request -> when (request.url.encodedPath) {
            "/api/hermes/update/check" -> readyUpdate()
            "/api/hermes/update" -> { posts.incrementAndGet(); accepted() }
            in updateRoutes -> updateStatus(request.url.encodedPath, posts.get() > 0 && !finished.get(), finished.get())
            else -> error("Unexpected updater fixture request")
        } }
        val saved = SavedStateHandle()
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        var resumedWorker: CoroutineScope? = null
        try {
            val controller = NativePhoneSettings(worker, saved) { client }
            controller.loadServer("Server updates"); awaitUpdateIo { controller.server.value.canApply }
            controller.applyServerUpdate(); awaitUpdateIo { controller.server.value.updateStatusFresh && controller.server.value.applying && controller.server.value.requestedActionId == updateId }
            advanceTimeBy(900_001); runCurrent()
            awaitUpdateIo { !controller.server.value.updateStatusFresh && !controller.server.value.applying }
            assertEquals("unconfirmed", controller.server.value.outcome)
            assertFalse(controller.server.value.canApply)
            assertTrue(controller.server.value.notice.isNotBlank())
            worker.cancel(); finished.set(true)
            val nextWorker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            resumedWorker = nextWorker
            val resumed = NativePhoneSettings(nextWorker, saved) { client }
            resumed.loadServer("Server updates")
            awaitUpdateIo { resumed.server.value.outcome == "success" }
            assertEquals(updateId, resumed.server.value.requestedActionId)
            assertEquals("", resumed.server.value.notice)
            assertEquals(1, posts.get())
        } finally { worker.cancel(); resumedWorker?.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aLostAcceptedReplyKeepsPendingIdentityAndNeverRetriesPost() = runTest {
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val client = updateClient { request -> when (request.url.encodedPath) {
            "/api/hermes/update/check" -> readyUpdate()
            "/api/hermes/update" -> { posts.incrementAndGet(); throw java.io.IOException("synthetic accepted reply lost") }
            in updateRoutes -> updateStatus(request.url.encodedPath, false, finished = posts.get() > 0)
            else -> error("Unexpected updater fixture request")
        } }
        val saved = SavedStateHandle()
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = NativePhoneSettings(worker, saved) { client }
            controller.loadServer("Server updates"); awaitUpdateIo { controller.server.value.canApply }
            controller.applyServerUpdate()
            awaitUpdateIo { posts.get() == 1 && !controller.server.value.applying && controller.server.value.notice.isNotBlank() }
            val requestedAt = controller.server.value.requestedAt
            assertTrue("The request marker is a time", runCatching { java.time.Instant.parse(requestedAt) }.isSuccess)
            assertEquals("", controller.server.value.requestedActionId)
            controller.resumeServerUpdate(); awaitUpdateIo { controller.server.value.updateStatusFresh }
            assertEquals("unconfirmed", controller.server.value.outcome)
            assertFalse(controller.server.value.canApply)
            controller.applyServerUpdate(); runCurrent()
            assertEquals(1, posts.get())
            assertEquals(requestedAt, saved.get<String>("server-update:${client.scope.copy(profile = "", session = "").key}:at"))
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun disconnectClearsRunningAndReadOnlyResumeRecoversTheReceipt() = runTest {
        val offline = java.util.concurrent.atomic.AtomicBoolean(true)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val client = updateClient { request -> when (request.url.encodedPath) {
            "/api/hermes/update/check" -> readyUpdate()
            in updateRoutes -> { reads.incrementAndGet(); if (offline.get()) throw java.io.IOException("synthetic disconnect"); updateStatus(request.url.encodedPath, false, finished = true) }
            else -> error("No update POST is authorized by this fixture")
        } }
        val key = client.scope.copy(profile = "", session = "").key
        val saved = SavedStateHandle(mapOf("server-update:$key:at" to updateRequestedAt, "server-update:$key:id" to updateId))
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = NativePhoneSettings(worker, saved) { client }
            controller.loadServer("Server updates")
            awaitUpdateIo { reads.get() >= 1 && controller.server.value.notice.isNotBlank() }
            assertFalse(controller.server.value.applying)
            assertEquals("unconfirmed", controller.server.value.outcome)
            offline.set(false)
            advanceTimeBy(2000); runCurrent(); controller.resumeServerUpdate()
            awaitUpdateIo { controller.server.value.outcome == "success" }
            assertEquals("", controller.server.value.notice)
            assertEquals(updateId, controller.server.value.requestedActionId)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aNewBusyHostPreventsPostWhileThePreviousTerminalReceiptRemainsVisible() = runTest {
        val busy = java.util.concurrent.atomic.AtomicBoolean()
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val client = updateClient { request -> when (request.url.encodedPath) {
            "/api/hermes/update/check" -> readyUpdate()
            in updateRoutes -> updateStatus(request.url.encodedPath, busy.get(), finished = true)
            "/api/hermes/update" -> { posts.incrementAndGet(); error("Busy host must block POST") }
            else -> error("Unexpected updater fixture request")
        } }
        val key = client.scope.copy(profile = "", session = "").key
        val saved = SavedStateHandle(mapOf("server-update:$key:at" to updateRequestedAt, "server-update:$key:id" to updateId))
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val controller = NativePhoneSettings(worker, saved) { client }
            controller.loadServer("Server updates"); awaitUpdateIo { controller.server.value.canApply }
            busy.set(true); controller.applyServerUpdate()
            awaitUpdateIo { controller.server.value.updateStatusFresh && controller.server.value.hostBusy }
            assertEquals("success", controller.server.value.outcome)
            assertFalse(controller.server.value.applying)
            assertFalse(controller.server.value.canApply)
            assertEquals(0, posts.get())
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 10_000) fun lateUpdaterReceiptCannotCrossAHostRebind() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val first = updateClient { request ->
            if (request.url.encodedPath in updateRoutes) { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); updateStatus(request.url.encodedPath, false, finished = true) }
            else readyUpdate()
        }
        val second = BeaconClient(AuthContext("https://other-updater.example", emptyList(), owner = "other", nativeProvider = "basic"), null) { builder ->
            builder.addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body((if (chain.request().url.encodedPath in updateRoutes) json("running" to false) else readyUpdate()).toString().toResponseBody("application/json".toMediaType())).build() }
        }
        val active = java.util.concurrent.atomic.AtomicReference(first)
        val key = first.scope.copy(profile = "", session = "").key
        val saved = SavedStateHandle(mapOf("server-update:$key:at" to updateRequestedAt, "server-update:$key:id" to updateId))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, saved) { active.get() }
            controller.loadServer("Server updates")
            assertTrue(withContext(Dispatchers.IO) { entered.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            active.set(second); controller.bind(); controller.loadServer("Server updates")
            withTimeout(5000) { controller.server.first { it.canApply } }
            release.countDown(); worker.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals("", controller.server.value.requestedActionId)
            assertEquals("unconfirmed", controller.server.value.outcome)
            assertTrue(controller.server.value.updateWarnings.isEmpty())
            assertEquals(updateId, saved.get<String>("server-update:$key:id"))
        } finally { release.countDown(); worker.cancel(); first.closeNative(); second.closeNative() }
    }
    @Test(timeout = 10_000) fun lateMemoryReadCannotReplaceAnotherProfileOrAccount() = runBlocking {
        val captured = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val returned = java.util.concurrent.CountDownLatch(1)
        val requests = CopyOnWriteArrayList<String>()
        fun client(origin: String, owner: String, profile: String) = BeaconClient(AuthContext(origin, emptyList(), owner = owner, profile = profile), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request()
                val scopedProfile = request.url.queryParameter("beacon_profile") ?: request.url.queryParameter("profile").orEmpty()
                val identity = "${request.url.host}/$scopedProfile"
                requests += "${request.url.encodedPath}:$identity"
                if (request.url.encodedPath.endsWith("/memory/documents") && identity == "first.example/coder") {
                    captured.countDown(); try { check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) } finally { returned.countDown() }
                }
                val result = if (request.url.encodedPath == "/api/memory") json("active" to "builtin") else
                    json("documents" to org.json.JSONArray().put(json("target" to "memory", "content" to identity, "revision" to "a".repeat(64), "enabled" to true, "limit" to 2000)))
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(result.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }
        val first = client("https://first.example", "owner", "coder")
        val second = client("https://first.example", "owner", "writer")
        val otherAccount = client("https://second.example", "another-owner", "writer")
        val active = java.util.concurrent.atomic.AtomicReference(first)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val editor = NativePhoneSettings(worker, SavedStateHandle()) { active.get() }
            editor.loadMemory()
            assertTrue(withContext(Dispatchers.IO) { captured.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            active.set(second); editor.bind(); editor.loadMemory()
            withTimeout(5000) { editor.memory.first { !it.loading && it.documents.singleOrNull()?.content == "first.example/writer" } }
            active.set(otherAccount); editor.bind(); editor.loadMemory()
            withTimeout(5000) { editor.memory.first { !it.loading && it.documents.singleOrNull()?.content == "second.example/writer" } }
            release.countDown()
            assertTrue(withContext(Dispatchers.IO) { returned.await(3, java.util.concurrent.TimeUnit.SECONDS) })
            worker.coroutineContext[Job]!!.children.toList().joinAll()
            assertEquals(otherAccount.scope.key, editor.memory.value.scope)
            assertEquals("second.example/writer", editor.memory.value.documents.single().content)
            assertTrue(requests.any { it.endsWith("first.example/coder") })
            assertTrue(requests.any { it.endsWith("first.example/writer") })
            assertTrue(requests.any { it.endsWith("second.example/writer") })
        } finally {
            release.countDown(); worker.cancel(); first.closeNative(); second.closeNative(); otherAccount.closeNative()
        }
    }

    @Test(timeout = 10_000) fun editingVisibleMemoryWhileRefreshingPreservesTheDraftAndBase() = runBlocking<Unit> {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
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
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
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
        "bot" to json("profile" to "academic", "title" to "Academic", "description" to "Study helper", "soul" to "Original behavior", "baseProvider" to "configured", "baseModel" to "original-model", "draftProvider" to "configured", "draftModel" to "edited-model"))

    @Test fun draftRoundTripKeepsOriginalBasesAndSignalsExplicitDiscard() {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
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
            assertEquals(setOf("provider", "model"), restored.bots.value.selected!!.saveModel("configured", "edited-model").keys().asSequence().toSet())
            assertEquals("edited-model", restored.bots.value.draftModel)
            controller.skillEdit(false); controller.editMemory(""); controller.editBot(false)
            assertEquals(setOf("version", "scope"), controller.snapshotDraft().keys().asSequence().toSet())
            assertEquals(4, changed)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** Hermes' own routes: the profile list and instructions read a bot (and its model now), PUT /api/profiles/{name}/model saves it and answers the stored provider and model. GET /api/model/info stays in the fixture so a test can prove it is never read. */
    private fun botModelClient(log: MutableList<String>, bodies: MutableList<JSONObject>, stored: java.util.concurrent.atomic.AtomicReference<String>,
        failReadAfterWrites: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(), normalise: (String) -> String = { it }) =
        BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "academic", nativeProvider = "basic"), null) { builder ->
            val written = java.util.concurrent.atomic.AtomicBoolean()
            builder.addInterceptor { chain ->
                val request = chain.request(); val key = request.method + " " + request.url.encodedPath; log += key
                val buffer = Buffer(); request.body?.writeTo(buffer)
                // The read right after a write fails as many times as [failReadAfterWrites] says (a network blip between the write and the read-back).
                if (key == "GET /api/profiles" && written.getAndSet(false) && failReadAfterWrites.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0)
                    return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(503).message("Fixture").body("""{"detail":"Hermes is busy"}""".toResponseBody("application/json".toMediaType())).build()
                if (key == "PUT /api/profiles/academic/model") written.set(true)
                val reply = when (key) {
                    "GET /api/profiles" -> json("profiles" to org.json.JSONArray().put(json("name" to "academic", "bot_title" to "Academic", "description" to "Study helper", "provider" to "configured", "model" to stored.get())))
                    "GET /api/plugins/beacon/admin/bots" -> json("bots" to org.json.JSONArray().put(json("profile" to "academic", "title" to "Academic", "purpose" to "Study helper")))
                    "GET /api/model/options" -> json("providers" to org.json.JSONArray())
                    "GET /api/profiles/academic/soul" -> json("content" to "Original behavior", "exists" to true)
                    // Like Hermes: the answer echoes the strings it was sent (hermes_cli/web_routers/profiles.py), what it stores went through its own normalisation first.
                    "PUT /api/profiles/academic/model" -> { val body = JSONObject(buffer.readUtf8()); bodies += body; stored.set(normalise(body.getString("model"))); json("ok" to true, "provider" to body.getString("provider"), "model" to body.getString("model")) }
                    "GET /api/model/info" -> json("model" to stored.get(), "provider" to "configured")
                    else -> error("Unexpected request " + key)
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture").body(reply.toString().toResponseBody("application/json".toMediaType())).build()
            }
        }

    /** The restored draft's own check of the model has run once its read is logged. */
    private suspend fun afterRestoreCheck(log: List<String>) = withTimeout(5000) { while ("GET /api/profiles" !in log) delay(10) }

    @Test(timeout = 15_000) fun savingABotModelReadsTheModelThenWritesThroughHermesProfileModelRouteAndShowsTheStoredModel() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice.isNotBlank() } }
            // The restore check, the save's own read of the model (the profile list has no probe), the write, then the read that shows what Hermes stored; the save never waits on the model probe route.
            assertEquals(listOf("GET /api/profiles", "GET /api/profiles", "PUT /api/profiles/academic/model", "GET /api/profiles"), log.toList())
            assertEquals(setOf("provider", "model"), bodies.single().keys().asSequence().toSet())
            assertEquals("edited-model", bodies.single().getString("model"))
            assertEquals("edited-model", controller.bots.value.selected!!.model)
            assertEquals("Original behavior", controller.bots.value.selected!!.soul)
            assertEquals("", controller.bots.value.error)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R9 Should 3: Hermes' PUT echoes the typed strings but stores them normalised (alias, vendor prefix); the page shows the stored pair, and the next Save does not meet a false "changed elsewhere". */
    @Test(timeout = 15_000) fun aSavedModelShowsWhatHermesStoredNotWhatWasTypedAndTheNextSaveIsNotAFalseConflict() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model")) { "$it-2026" }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice.isNotBlank() } }
            assertEquals("The page shows the name Hermes stored", "edited-model-2026", controller.bots.value.selected!!.model)
            assertEquals("", controller.bots.value.error)
            // The owner edits the model again: the base is what Hermes holds, so Save writes and says nothing about a change elsewhere.
            controller.editBot(true); controller.botDraft("configured", "another-model")
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && (bodies.size == 2 || it.error.isNotBlank()) } }
            assertEquals("", controller.bots.value.error)
            assertEquals(2, bodies.size)
            assertEquals("another-model", bodies.last().getString("model"))
            assertEquals("another-model-2026", controller.bots.value.selected!!.model)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aSaveWhoseStoredModelReadsBackBlankIsNotConfirmed() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model")) { "" }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.error.isNotBlank() } }
            // The page says the save did not go through (the same way the earlier "not confirmed" check does), shows no "saved" notice, and keeps the model it had.
            assertTrue(controller.bots.value.error.isNotBlank())
            assertEquals("", controller.bots.value.notice)
            assertEquals("original-model", controller.bots.value.selected!!.model)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R10 Nice 8: the write worked and only the read-back failed (a network blip): that is no error. The typed pair shows as saved, said honestly as not confirmed yet. */
    @Test(timeout = 15_000) fun aSaveWhoseReadBackFailsShowsTheTypedModelAsSavedNotConfirmedAndNoError() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"), failReadAfterWrites = java.util.concurrent.atomic.AtomicInteger(1))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && (it.notice.isNotBlank() || it.error.isNotBlank()) } }
            assertEquals("The write worked, so there is no error", "", controller.bots.value.error)
            assertEquals(MODEL_SAVED_UNCONFIRMED, controller.bots.value.notice)
            assertEquals("Not confirmed yet.", MODEL_SAVED_UNCONFIRMED.substringAfter("new chats. "))
            assertEquals("The typed pair is shown", "edited-model", controller.bots.value.selected!!.model)
            assertEquals("configured", controller.bots.value.selected!!.provider)
            assertFalse(controller.bots.value.editing)
            assertEquals(1, bodies.size)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R10 Nice 8: after that save Hermes holds the normalised name; the next Save must not call its own earlier change a change made elsewhere. */
    @Test(timeout = 15_000) fun theSaveAfterAFailedReadBackMeetsNoFalseChangedElsewhere() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"), java.util.concurrent.atomic.AtomicInteger(1)) { "$it-2026" }
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice == MODEL_SAVED_UNCONFIRMED } }
            controller.editBot(true); controller.botDraft("configured", "another-model")
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && (bodies.size == 2 || it.error.isNotBlank()) } }
            assertEquals("No false conflict for its own change", "", controller.bots.value.error)
            assertEquals(2, bodies.size)
            assertEquals("another-model", bodies.last().getString("model"))
            // This save's read-back worked: the page shows what Hermes stored and nothing is unconfirmed any more.
            assertEquals("another-model-2026", controller.bots.value.selected!!.model)
            assertEquals("Default model saved for new chats.", controller.bots.value.notice)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R11 Nice 7: the pair a save could not read back is only trusted while that bot stays open; closing it forgets the pair. */
    @Test(timeout = 15_000) fun anUnconfirmedSaveIsForgottenWhenTheBotIsClosed() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"), java.util.concurrent.atomic.AtomicInteger(1))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice == MODEL_SAVED_UNCONFIRMED } }
            assertEquals("The pair is kept for the next Save, with the bot it belongs to", Triple("academic", "configured", "edited-model"), controller.bots.value.unconfirmedModel)
            controller.closeBot()
            assertNull("Closing the bot forgets it", controller.bots.value.unconfirmedModel)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R11 Nice 7: opening a bot (the same one, or another) starts from what Hermes holds, never from a pair an earlier save left unconfirmed. */
    @Test(timeout = 15_000) fun anUnconfirmedSaveIsForgottenWhenABotIsOpened() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"), java.util.concurrent.atomic.AtomicInteger(1))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice == MODEL_SAVED_UNCONFIRMED } }
            controller.loadBots()?.join()
            assertNotNull(controller.bots.value.unconfirmedModel)
            val opening = controller.openBot("academic")
            assertNotNull("The bot list is loaded, so the bot opens", opening)
            assertNull("Opening a bot forgets it", controller.bots.value.unconfirmedModel)
            opening!!.join()
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R11 Nice 7, the chain the review named: a save whose read-back failed, then the bot is opened again and a change is made on the computer. The Save must say so, not overwrite it. */
    @Test(timeout = 15_000) fun aChangeOnTheComputerIsNotOverwrittenAfterAnUnconfirmedSaveAndAReopen() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val stored = java.util.concurrent.atomic.AtomicReference("original-model")
        // Hermes stores the typed name as it is, so after the reopen the bot holds exactly the pair the unconfirmed save wrote.
        val client = botModelClient(log, bodies, stored, java.util.concurrent.atomic.AtomicInteger(1))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice == MODEL_SAVED_UNCONFIRMED } }
            controller.loadBots()?.join()
            controller.closeBot()
            controller.openBot("academic")!!.join()
            assertEquals("edited-model", controller.bots.value.selected!!.model)
            controller.editBot(true); controller.botDraft("configured", "third-model")
            stored.set("changed-on-the-computer")
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && (bodies.size == 2 || it.error.isNotBlank()) } }
            assertEquals("The change made on the computer is reported", MODEL_CHANGED_ELSEWHERE, controller.bots.value.error)
            assertEquals("Nothing is written over it", 1, bodies.size)
            assertEquals("changed-on-the-computer", controller.bots.value.selected!!.model)
        } finally { worker.cancel(); client.closeNative() }
    }

    /** R11 Nice 7: the pair belongs to one bot. Another bot that happens to hold the same pair keeps its own base. */
    @Test fun anUnconfirmedPairIsTrustedOnlyForTheBotThatSavedIt() {
        val bot = NativeBot("writer", "Writer", "", "", "configured", "edited-model")
        val now = "configured" to "changed-on-the-computer"
        assertEquals("The same bot and pair: what Hermes holds now is the base", now, modelSaveBase(Triple("writer", "configured", "edited-model"), bot, now))
        assertEquals("Another bot with the same pair keeps its own base", "configured" to "edited-model", modelSaveBase(Triple("academic", "configured", "edited-model"), bot, now))
        assertEquals("Another pair of the same bot keeps its own base", "configured" to "edited-model", modelSaveBase(Triple("writer", "configured", "other-model"), bot, now))
        assertEquals("No unconfirmed save: the bot's own pair", "configured" to "edited-model", modelSaveBase(null, bot, now))
    }

    @Test(timeout = 15_000) fun aSavedModelIsShownFromTheStoredRowAndNeverCalledUnconfirmed() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("original-model"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && (it.notice.isNotBlank() || it.error.isNotBlank()) } }
            assertFalse("The slow optional model probe is not read", log.any { it.contains("/api/model/info") })
            assertEquals("", controller.bots.value.error)
            assertEquals("configured", controller.bots.value.selected!!.provider)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aModelChangedOnTheComputerAfterTheDraftWasMadeIsNotOverwrittenBySave() = runBlocking {
        // The phone picks a model, the app is closed, the computer changes the model, the phone opens and taps Save.
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val stored = java.util.concurrent.atomic.AtomicReference("original-model")
        val client = botModelClient(log, bodies, stored)
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log)
            stored.set("changed-on-the-computer")
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.error.isNotBlank() } }
            assertTrue("Nothing is written over the newer model", log.none { it.startsWith("PUT") } && bodies.isEmpty())
            assertEquals("The new base is shown", "changed-on-the-computer", controller.bots.value.selected!!.model)
            assertEquals("The draft is kept", "edited-model", controller.bots.value.draftModel)
            assertTrue(controller.bots.value.editing)
            assertEquals(MODEL_CHANGED_ELSEWHERE, controller.bots.value.error)
            // Having reviewed it, the next Save writes the draft over the base the owner has now seen.
            controller.saveBot()
            withTimeout(5000) { controller.bots.first { !it.saving && it.notice.isNotBlank() } }
            assertEquals("edited-model", bodies.single().getString("model"))
            assertEquals("", controller.bots.value.error)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aDraftRestoredOverAModelThatChangedSinceShowsTheNewBaseAtOnceAndKeepsTheDraft() = runBlocking {
        val log = CopyOnWriteArrayList<String>(); val bodies = CopyOnWriteArrayList<JSONObject>()
        val client = botModelClient(log, bodies, java.util.concurrent.atomic.AtomicReference("changed-on-the-computer"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            withTimeout(5000) { controller.bots.first { it.error.isNotBlank() } }
            assertEquals("changed-on-the-computer", controller.bots.value.selected!!.model)
            assertEquals("edited-model", controller.bots.value.draftModel)
            assertTrue(controller.bots.value.editing)
            assertEquals(MODEL_CHANGED_ELSEWHERE, controller.bots.value.error)
            assertEquals(listOf("GET /api/profiles"), log.toList())
            assertTrue(bodies.isEmpty())
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun aDraftRestoredOverTheSameModelStaysQuiet() = runBlocking {
        val log = CopyOnWriteArrayList<String>()
        val client = botModelClient(log, CopyOnWriteArrayList(), java.util.concurrent.atomic.AtomicReference("original-model"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind(); controller.restoreDraft(draftFixture(client.scope.key))
            afterRestoreCheck(log); delay(300)
            assertEquals("", controller.bots.value.error)
            assertEquals("original-model", controller.bots.value.selected!!.model)
            assertEquals("edited-model", controller.bots.value.draftModel)
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test(timeout = 15_000) fun openingABotReadsHermesProfileRowAndInstructions() = runBlocking {
        val log = CopyOnWriteArrayList<String>()
        val client = botModelClient(log, CopyOnWriteArrayList(), java.util.concurrent.atomic.AtomicReference("m1"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }
            controller.bind()
            val bot = NativeBot.fromNative(client.nativeGet("/api/profiles").rows("profiles").single(), client.nativeGet("/api/profiles/academic/soul").string("content"))
            assertEquals(NativeBot("academic", "Academic", "Study helper", "Original behavior", "configured", "m1"), bot)
            assertEquals(listOf("GET /api/profiles", "GET /api/profiles/academic/soul"), log.toList())
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test fun malformedForeignAndOversizedDraftsAreRejectedAtomically() {
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = NativePhoneSettings(worker, SavedStateHandle()) { client }; controller.bind()
            controller.restoreDraft(draftFixture(client.scope.key))
            val existing = controller.snapshotDraft().toString()
            val wrongScope = draftFixture(client.scope.key).put("scope", "other-owner")
            val wrongVersion = draftFixture(client.scope.key).put("version", 2)
            val missingBase = draftFixture(client.scope.key).apply { getJSONObject("memory").remove("base") }
            val badBot = draftFixture(client.scope.key).apply { getJSONObject("bot").put("draftModel", 5) }
            val coercedFlag = draftFixture(client.scope.key).apply { getJSONObject("skill").put("enabled", "true") }
            val oversized = draftFixture(client.scope.key).put("extra", "x".repeat(1_048_577))
            for (payload in listOf(wrongScope, wrongVersion, missingBase, badBot, coercedFlag, oversized)) {
                controller.restoreDraft(payload)
                assertEquals(existing, controller.snapshotDraft().toString())
            }
        } finally { worker.cancel(); client.closeNative() }
    }

    @Test fun restoredDraftCannotClobberNewerEditAndProfileMapsRemainIsolated() {
        var client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
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
            client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "other")); controller.bind()
            controller.restoreDraft(snapshot)
            assertFalse(controller.skills.value.editing)
            assertEquals("", controller.memory.value.editing)
            client.closeNative(); client = first; controller.bind()
            assertEquals("newer instructions", controller.skills.value.draft)
            assertEquals("newer notes", controller.memory.value.draft)
            assertEquals("newer-model", controller.bots.value.draftModel)
        } finally { worker.cancel(); client.closeNative(); first.closeNative() }
    }

    @Test fun legacyOtherProfileModelDraftMovesWithoutTakingChatProfileNotes() {
        val first = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"))
        val academic = first.forAuthorizedProfile("academic")
        var active = first
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val editor = NativePhoneSettings(worker, SavedStateHandle()) { active }
            editor.bind(); editor.restoreDraft(draftFixture(first.scope.key))
            active = academic; editor.bind()
            assertEquals(academic.scope.key, editor.snapshotDraft().getString("scope"))
            assertEquals("academic", editor.bots.value.selected?.profile)
            assertEquals("original-model", editor.bots.value.selected?.model)
            assertEquals("edited-model", editor.bots.value.draftModel)
            assertTrue(editor.bots.value.editing)
            assertEquals("", editor.memory.value.editing)
            assertFalse(editor.skills.value.editing)
            active = first; editor.bind()
            assertEquals("edited notes", editor.memory.value.draft)
            assertEquals("edited instructions", editor.skills.value.draft)
        } finally { worker.cancel(); academic.closeNative(); first.closeNative() }
    }
    @Test fun memoryConflictPreservesDraftAndSuccessfulReadbackBecomesBaseline() = runBlocking {
        val requests = CopyOnWriteArrayList<Request>()
        var conflict = true
        var saved = "initial"
        val revision = "a".repeat(64)
        val confirmedRevision = "b".repeat(64)
        val client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner"), null) { builder ->
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
            assertTrue(requests.all {
                if (it.url.encodedPath.startsWith("/api/plugins/beacon/")) {
                    it.url.queryParameter("beacon_profile") == "default" && it.url.queryParameter("profile") == null
                } else {
                    it.url.queryParameter("profile") == "default" && it.url.queryParameter("beacon_profile") == null
                }
            })
        } finally { worker.cancel(); client.closeNative() }
    }
    @Test fun unresolvedHostUpdateSurvivesProfileSwitchAndBlocksAnotherApply() {
        var client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", nativeProvider = "basic"))
        val account = client.scope.copy(profile = "", session = "").key
        val saved = SavedStateHandle(mapOf("server-update:$account:at" to "2026-10-14T10:00:00Z", "server-update:$account:id" to "active"))
        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = NativePhoneSettings(worker, saved) { client }
        controller.bind()
        assertEquals("active", controller.server.value.requestedActionId)
        client = BeaconClient(AuthContext("https://native.example", emptyList(), owner = "owner", profile = "academic", nativeProvider = "basic"))
        controller.bind()
        assertEquals("active", controller.server.value.requestedActionId)
        assertFalse(controller.server.value.copy(update = json("can_apply" to true, "update_available" to true)).canApply)
        client = BeaconClient(client.auth.copy(nativeProvider = "oidc")); controller.bind()
        assertEquals("", controller.server.value.requestedActionId)
        assertEquals("active", saved.get<String>("server-update:$account:id"))
        worker.cancel(); client.closeNative()
    }
    @Test fun boundedRecoveryKeepsLongUnsavedEditsForTheOwnerToShorten() {
        val client=BeaconClient(AuthContext("https://native.example",emptyList(),owner="owner"))
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
