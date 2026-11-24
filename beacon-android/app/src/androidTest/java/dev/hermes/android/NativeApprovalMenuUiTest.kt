package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.screenRequest
import dev.hermes.android.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class NativeApprovalMenuUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(
        content: @Composable (BeaconViewModel) -> Unit = { NativeApprovalMenu(it, enabled = true) },
        prepare: (BeaconViewModel, NativeExperienceFixture) -> Unit = { _, _ -> },
        block: (BeaconViewModel, NativeExperienceFixture) -> Unit,
    ) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val originKey = stringPreferencesKey("origin")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        runBlocking { app.auth.persist(fixture.client.auth) }
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
            }
            diagnosticVm = vm; diagnosticFixture = fixture
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = fixture.scope.origin, profile = fixture.scope.profile, authenticated = true,
                    owner = fixture.scope.owner, connected = true, restoringSession = false,
                    bootstrap = json("voice" to json("available" to false)),
                    sessions = fixture.sessions.keys.sorted().map(fixture::snapshot),
                )
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") },
                )
            }
            compose.runOnIdle { prepare(vm, fixture) }
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { content(vm) } } }
            block(vm, fixture)
        } finally {
            fixture.releaseHistoryList.countDown()
            fixture.releaseReasoningOptions.countDown()
            fixture.releaseCreateAcknowledgement()
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.preferences.edit { prior[originKey]?.let { value -> it[originKey] = value } ?: it.remove(originKey) }
                app.auth.remove(fixture.scope.origin)
            }
        }
    }

    private var diagnosticVm: BeaconViewModel? = null
    private var diagnosticFixture: NativeExperienceFixture? = null
    private fun await(condition: () -> Boolean) = AwaitDiagnostics.await(compose, { waitState() }, condition)
    /** What a timed-out wait reports about the case: the chat, the notice and the last calls Hermes (the fixture) received. */
    private fun waitState(): String {
        val vm = diagnosticVm ?: return "no case running"
        val chat = vm.chat.value; val ui = vm.ui.value
        val calls = diagnosticFixture?.requests?.toList().orEmpty().takeLast(12).joinToString { "${it.string("method")} ${it.string("path")}".trim() }
        return "chat=${chat.sessionId} streamId='${chat.streamId}' status='${chat.status}' ready=${chat.projectionReady}; notice='${ui.notice}' error='${ui.error}'; last calls: $calls"
    }
    private fun open(vm: BeaconViewModel) {
        compose.onNodeWithTag("composer-approvals").performClick()
        await { vm.data.value["picker:approvals"]?.string("session_id") == vm.chat.value.sessionId && "picker:approvals" !in vm.refreshing.value }
    }

    private fun modelRows() = json("groups" to JSONArray()
        .put(json("provider_id" to "other", "provider" to "Other provider", "models" to JSONArray().put(json("id" to "other-id", "label" to "Other model"))))
        .put(json("provider_id" to "fixture", "provider" to "Current provider", "models" to JSONArray()
            .put(json("id" to "synthetic-model", "label" to "synthetic-model"))
            .put(json("id" to "other-model", "label" to "Other choice")))))

    @Test fun yoloRequiresConfirmationAndAskFirstOnlyClearsThisChatsBypass() = withFixture { vm, fixture ->
        open(vm)
        compose.onNodeWithText("Ask before actions", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Ask for risky ones", useUnmergedTree = true).assertIsDisplayed()
        // The chat menu only changes this chat: no profile-wide shortcuts in it.
        compose.onNodeWithText("Profile default", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Change for all chats").assertDoesNotExist()
        compose.onNodeWithText("Allowed toolsets").assertDoesNotExist()
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNodeWithText("Skip approvals for this chat?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(fixture.requests.none { it.string("method") == "config.set" })
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-yolo", useUnmergedTree = true).performScrollTo().assert(isSelected()).assert(hasAnyDescendant(hasContentDescription("Selected")))
        compose.onNodeWithTag("approval-ask").performScrollTo().performClick()
        await { vm.data.value["picker:approvals"]?.let { it.optBoolean("session_status_known") && !it.optBoolean("session_bypass") } == true }
        val writes = fixture.requests.filter { it.string("method") == "config.set" }.map { it.getJSONObject("params") }
        assertEquals(listOf("1", "0"), writes.map { it.string("value") })
        assertTrue(writes.all { it.string("key") == "yolo" && it.string("scope") == "session" && it.string("session_id") == "runtime-chat-a" })
        assertEquals("manual", fixture.approvalMode)
        compose.onNodeWithContentDescription("Approvals: Ask for risky ones").assertIsDisplayed()
    }

    // Live 2026-10-26: enabling YOLO in a just-created chat showed "Approvals: Unknown" for about 5 s while Hermes saved it.
    @Test fun enablingYoloShowsYoloAtOnceInsteadOfUnknownWhileItSaves() = withFixture { vm, fixture ->
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        fixture.holdConfigAcknowledgement = true
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.conversationPending.value.isNotEmpty() }
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
        compose.onNodeWithContentDescription("Approvals: Unknown").assertDoesNotExist()
        fixture.releaseCreateAcknowledgement()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
    }

    @Test fun profileOffIsHonestAndHasNoChatOffChoice() = withFixture(prepare = { _, fixture -> fixture.approvalMode = "off" }) { vm, fixture ->
        open(vm)
        compose.onNodeWithText("Approvals are off").assertIsDisplayed()
        compose.onNodeWithTag("approval-yolo").assertDoesNotExist()
        compose.onNodeWithTag("approval-ask").assertDoesNotExist()
        compose.onNodeWithTag("approval-mode-off").assertDoesNotExist()
        assertTrue(fixture.requests.none { it.string("method") == "config.set" })
    }

    @Test fun autoLoadsBeforeTheMenuIsOpenedInsteadOfDefaultingToManual() = withFixture(prepare = { _, fixture -> fixture.approvalMode = "smart" }) { vm, fixture ->
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "smart" }
        compose.onNodeWithContentDescription("Approvals: Hermes decides").assertIsDisplayed()
        open(vm)
        compose.onNodeWithText("Hermes decides", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ask only for risky actions", useUnmergedTree = true).assertDoesNotExist()
        val resumes = fixture.requests.filter { it.string("method") == "session.resume" }
        assertTrue(resumes.isNotEmpty() && resumes.all { it.getJSONObject("params").optBoolean("lazy") && it.getJSONObject("params").optBoolean("omit_messages") })
    }

    @Test fun passiveHistoryDoesNotResumeToPaintTheApprovalShield() = withFixture(prepare = { vm, _ ->
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(raw = vm.chat.value.raw.copy().apply { remove("runtime_session_id") })
    }) { vm, fixture ->
        // A browsed chat shows its own saved choice (not YOLO), so the shield shows the profile's mode instead of Unknown.
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "manual" }
        compose.onNodeWithContentDescription("Approvals: Ask for risky ones").assertIsDisplayed()
        assertTrue("Browsing never resumes, creates or sends", fixture.requests.none {
            it.string("method") in setOf("session.resume", "session.history", "session.create", "prompt.submit")
        })
    }

    @Test fun largePhotoHistoryReachesTheRealWatcherAndReconcilesTheStartupRace() = withFixture(prepare = { vm, fixture ->
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(raw = vm.chat.value.raw.copy().apply { remove("runtime_session_id") })
        val image = "data:image/jpeg;base64," + "A".repeat(12 * 1024 * 1024)
        val messages = JSONArray((1..3).map { i -> json("id" to 100 + i, "role" to "user", "content" to JSONArray()
            .put(json("type" to "text", "text" to "Photo $i\n@image:/workspace/photo-$i.jpg"))
            .put(json("type" to "image_url", "image_url" to json("url" to image)))) })
        val storedBytes = (0 until messages.length()).sumOf { messages.getJSONObject(it).toString().toByteArray().size.toLong() }
        assertTrue(storedBytes > 32L * 1024 * 1024)
        fixture.sessions.getValue("chat-b").put("messages", messages)
        (1..3).forEach { fixture.downloads["/workspace/photo-$it.jpg"] = byteArrayOf(it.toByte()) }
        fixture.afterNextHistoryPage = { messages.put(json("id" to 104, "role" to "assistant", "content" to "Arrived before watcher startup")) }
    }) { vm, fixture ->
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.messages.size == 3 && vm.chat.value.projectionReady }
        assertTrue(vm.chat.value.messages.first().text.contains("Photo 1"))
        assertTrue(vm.chat.value.raw.toString().length < 10_000)
        val receipt = vm.chat.value.messages.first().attachments.single()
        val reference = resolveAttachmentReference(receipt, fixture.scope.session("chat-b")) as NativeReference.HistoryImage
        assertEquals("chat-b", reference.sessionId)
        assertEquals(101L, reference.rowId)
        assertEquals(0, reference.imageIndex)
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }
        await { (watcher.get(vm) as? kotlinx.coroutines.Job)?.isActive == true }
        assertTrue("The actual selectSession chain must reach watchSession", (watcher.get(vm) as kotlinx.coroutines.Job).isActive)
        compose.waitUntil(25_000) { vm.chat.value.messages.any { it.text == "Arrived before watcher startup" } }
        assertEquals(4, vm.chat.value.messages.size)
        assertTrue(fixture.requests.none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
        assertEquals(500, vm.chat.value.raw.optInt("history_limit"))
        val history = fixture.sessions.getValue("chat-b").getJSONArray("messages")
        assertEquals(listOf(101L, 102L, 103L, 104L), history.objects().map { it.optLong("id") })
        val saved = runBlocking { fixture.client.historyImage(reference) }
        assertTrue(history === fixture.sessions.getValue("chat-b").getJSONArray("messages"))
        assertEquals(listOf(101L, 102L, 103L, 104L), history.objects().map { it.optLong("id") })
        assertEquals(4, vm.chat.value.messages.size)
        assertTrue((watcher.get(vm) as kotlinx.coroutines.Job).isActive)
        assertEquals("image/jpeg", saved.mime)
        assertEquals(9 * 1024 * 1024, saved.bytes.size)
        assertTrue("All bytes match the stored Base64 A fixture", saved.bytes.all { it == 0.toByte() })
        val imageRequest = fixture.requests.last { it.string("path") == "/api/sessions/chat-b/messages/around" }
        assertEquals(fixture.scope.profile, imageRequest.string("profile"))
    }

    @Test fun cachedUploadKeepsSavedImagesAndFileLabelsThroughModelChangeAndReopen() =
        savedImagesSurviveModelChangeAndReopen(cachedUpload = true)

    @Test fun modelChangeKeepsCanonicalAndLegacySavedImagesWithoutUploadCache() =
        savedImagesSurviveModelChangeAndReopen(cachedUpload = false)

    private fun savedImagesSurviveModelChangeAndReopen(cachedUpload: Boolean) {
        val priorCache = linkedMapOf<Pair<Scope, String>, String?>()
        try {
            withFixture(content = {}) { vm, fixture ->
                val scope = fixture.scope.session("chat-b")
                val photoPath = "/workspace/camera.png"
                val filePath = "/workspace/notes.txt"
                val originalPhoto = byteArrayOf(1, 2, 3)
                val originalLegacy = byteArrayOf(4, 5, 6)
                val replacementPhoto = byteArrayOf(9, 8, 7)
                val fileBytes = "Saved ordinary file".toByteArray()
                // Which step of the test each recorded request came from (the index into fixture.requests where the step began), for the resume assertion below.
                val steps = mutableListOf<Pair<Int, String>>()
                fun step(name: String) { steps += fixture.requests.size to name }
                step("setup")
                val assistantModel = vm.preferences.value.assistantModel
                val assistantProvider = vm.preferences.value.assistantProvider
                val photoCaption = if (cachedUpload) "Original photo caption" else "Canonical photo\n@image:$photoPath\n[image]"
                val photoName = if (cachedUpload) "Original camera.png" else "camera.png"
                val cacheKeys = listOf(
                    fixture.scope to listOf("selected-session", "read-invalidated-at", "conversation-index", "phone-editors"),
                    fixture.scope.copy(profile = "", session = "") to listOf("screens"),
                    fixture.scope.session("chat-a") to listOf("session", "draft", "side-question", "pending", "task-result", "client-created-shell"),
                    scope to listOf("session", "draft", "side-question", "pending", "task-result", "client-created-shell", "attachments:201", "attachments:202", "attachments:203"),
                )
                runBlocking {
                    for ((cacheScope, keys) in cacheKeys) for (key in keys) {
                        priorCache[cacheScope to key] = app.cache.read(cacheScope, key)
                        app.cache.delete(cacheScope, key)
                    }
                    if (cachedUpload) app.cache.write(scope, "attachments:201", json(
                        "display_text" to photoCaption,
                        "attachments" to JSONArray().put(json("path" to photoPath, "name" to photoName, "mime" to "image/png")),
                    ).toString(), protected = true)
                    app.cache.write(scope, "attachments:203", json(
                        "display_text" to "Original file caption",
                        "attachments" to JSONArray().put(json("path" to filePath, "name" to "Original notes.txt", "mime" to "text/plain")),
                    ).toString(), protected = true)
                }
                fixture.sessions.getValue("chat-b").put("messages", JSONArray()
                    .put(json("id" to 201, "row_id" to 201, "role" to "user", "content" to JSONArray()
                        .put(json("type" to "text", "text" to "Canonical photo\n@image:$photoPath"))
                        .put(json("type" to "image_url", "image_url" to json("url" to "data:image/png;base64,AQID")))))
                    .put(json("id" to 202, "row_id" to 202, "role" to "user", "content" to JSONArray()
                        .put(json("type" to "text", "text" to "Legacy inline photo"))
                        .put(json("type" to "image_url", "image_url" to json("url" to "data:image/jpeg;base64,BAUG")))))
                    .put(json("id" to 203, "row_id" to 203, "role" to "user", "content" to "Server file caption")))
                fixture.downloads[photoPath] = replacementPhoto
                fixture.downloads[filePath] = fileBytes
                assertArrayEquals(replacementPhoto, runBlocking { fixture.client.bytes("/api/file/raw", mapOf("path" to photoPath, "session_id" to "chat-b")) })
                assertFalse(originalPhoto.contentEquals(replacementPhoto))

                fun assertSavedRows() {
                    val chat = vm.chat.value
                    assertTrue(chat.projectionReady)
                    assertEquals("chat-b", chat.sessionId)
                    assertEquals(listOf("201", "202", "203"), chat.messages.map { it.id })
                    assertTrue(chat.messages.all { it.sessionId == "chat-b" })
                    val filesBeforeImages = fixture.fileRequests.size
                    for ((index, original) in listOf(originalPhoto, originalLegacy).withIndex()) {
                        val row = chat.messages[index]
                        assertEquals("Saved image row ${row.id} keeps its attachment", 1, row.attachments.size)
                        val reference = resolveAttachmentReference(row.attachments.single(), scope)
                        assertEquals(NativeReference.HistoryImage("chat-b", 201L + index, 0), reference)
                        val saved = runBlocking { fixture.client.historyImage(reference as NativeReference.HistoryImage) }
                        assertArrayEquals(original, saved.bytes)
                        assertEquals(if (index == 0) "image/png" else "image/jpeg", saved.mime)
                    }
                    assertEquals("Saved images must never read mutable files", filesBeforeImages, fixture.fileRequests.size)
                    assertEquals(fixture.scope.profile, chat.raw.string("profile"))
                    assertTrue(chat.raw.toString().length < 10_000)
                    assertEquals(500, chat.raw.optInt("history_limit"))
                    assertEquals(photoCaption, chat.messages[0].text)
                    assertEquals(photoName, chat.messages[0].attachments.single().string("name"))
                    assertEquals("Legacy inline photo\n[image]", chat.messages[1].text)
                    assertEquals("Image 1", chat.messages[1].attachments.single().string("name"))
                    val file = chat.messages[2]
                    assertEquals("Original file caption", file.text)
                    assertEquals("Original notes.txt", file.attachments.single().string("name"))
                    val fileReference = resolveAttachmentReference(file.attachments.single(), scope)
                    assertEquals(NativeReference.LocalMedia(filePath, "chat-b", null, "/api/file/raw"), fileReference)
                    val local = fileReference as NativeReference.LocalMedia
                    assertArrayEquals(fileBytes, runBlocking { fixture.client.bytes(local.endpoint, local.query()) })
                }

                step("1 open chat-b (passive history)")
                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.chat.value.messages.map { it.id } == listOf("201", "202", "203") }
                assertSavedRows()
                assertTrue("Opening history is passive", fixture.requests.none { it.string("method") == "session.resume" })

                step("2 pick the model (config.set held)")
                fixture.holdConfigAcknowledgement = true
                compose.runOnIdle { vm.pickModel("other-model", "fixture") }
                await { fixture.requests.any { it.string("method") == "config.set" } && vm.modelSaving.value.isNotBlank() }
                assertEquals("fixture:other-model", vm.modelSaving.value)
                assertTrue("The actual model action remains pending", vm.conversationPending.value.isNotEmpty())
                step("3 config.set released")
                fixture.releaseCreateAcknowledgement()
                await { vm.modelSaving.value.isBlank() && vm.conversationPending.value.isEmpty() && vm.chat.value.raw.string("model") == "other-model" }
                assertEquals("fixture", vm.chat.value.raw.string("model_provider"))
                assertEquals("runtime-chat-b", vm.chat.value.raw.string("runtime_session_id"))
                assertEquals("chat-b", vm.chat.value.raw.string("stored_session_id"))
                assertSavedRows()

                step("4 open chat-a")
                compose.runOnIdle { vm.selectSession("chat-a") }
                await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.messages.any { it.id == "10" } }
                step("5 open chat-b again")
                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.chat.value.raw.string("model") == "other-model" }
                assertSavedRows()
                val writes = fixture.requests.filter { it.string("method") == "config.set" }.map { it.getJSONObject("params") }
                assertEquals(1, writes.size)
                assertEquals("model", writes.single().string("key"))
                assertEquals("other-model --provider fixture", writes.single().string("value"))
                assertEquals("session", writes.single().string("scope"))
                assertEquals("runtime-chat-b", writes.single().string("session_id"))
                // Hermes keeps a pick that names its provider only on a built agent, so the chat's agent is started first.
                val sent = fixture.requests.toList()
                assertTrue(sent.take(sent.indexOfFirst { it.string("method") == "config.set" })
                    .any { it.string("method") == "approval.pending" && it.getJSONObject("params").string("session_id") == "runtime-chat-b" })
                assertEquals("synthetic-model", fixture.profileModel)
                assertEquals("medium", fixture.reasoningEffort)
                assertEquals("manual", fixture.approvalMode)
                assertEquals(assistantModel, vm.preferences.value.assistantModel)
                assertEquals(assistantProvider, vm.preferences.value.assistantProvider)
                assertEquals("synthetic-model", fixture.sessions.getValue("chat-a").getJSONObject("info").string("model"))
                step("6 final checks")
                val resumes = fixture.requests.filter { it.string("method") == "session.resume" }
                // Every session.resume the fixture recorded, with the step of the test it came from (a failure here must say which request broke the rule).
                val resumeLog = fixture.requests.toList().withIndex().filter { it.value.string("method") == "session.resume" }.joinToString("; ") { (index, request) ->
                    val p = request.getJSONObject("params")
                    "#$index step='${steps.lastOrNull { it.first <= index }?.second}' session_id=${p.opt("session_id")} inline_images=${p.opt("inline_images")} omit_messages=${p.opt("omit_messages")} params=$p"
                }
                assertTrue("The chat was resumed at least once. Recorded session.resume requests: [$resumeLog]", resumes.isNotEmpty())
                assertTrue("Every session.resume must be chat-b with inline_images=false and omit_messages=false. Recorded session.resume requests (cachedUpload=$cachedUpload): [$resumeLog]",
                    resumes.all { it.getJSONObject("params").let { p -> p.string("session_id") == "chat-b" && p.opt("inline_images") == false && p.opt("omit_messages") == false } })
                assertTrue(fixture.requests.filter { it.has("params") && it.string("method") != "client.capabilities" }.all { it.getJSONObject("params").string("profile") == scope.profile })
                assertEquals("Every read names this profile", emptyList<String>(), fixture.requests.filter { it.has("path") && it.string("profile") != scope.profile }.map { it.string("path") + " profile=" + it.string("profile") })
                assertTrue(fixture.requests.none { it.string("method") in setOf("prompt.submit", "session.create", "session.history", "session.events.since") })
                assertTrue(vm.ui.value.error.isBlank())
            }
        } finally {
            // withFixture clears the ViewModel before restoring these exact synthetic checkpoints.
            runBlocking { priorCache.forEach { (entry, value) ->
                val (scope, key) = entry
                if (value == null) app.cache.delete(scope, key) else app.cache.write(scope, key, value, protected = true)
            } }
        }
    }

    @Test fun unreportedBypassStaysUnknownAndExplicitDefaultResetsThisSession() = withFixture(prepare = { _, fixture -> fixture.omitResumeYolo = true }) { vm, fixture ->
        await { vm.data.value["picker:approvals"] != null }
        // 1.1.34 (shieldDescription): a chat whose bypass is not reported has a plain shield with no mode word, never "Unknown".
        compose.onNodeWithContentDescription("Approvals").assertIsDisplayed()
        compose.onNodeWithContentDescription("Approvals: Unknown").assertDoesNotExist()
        open(vm)
        compose.onNodeWithTag("approval-ask").assert(isNotSelected())
        compose.onNodeWithTag("approval-yolo").assert(isNotSelected())
        compose.onNodeWithText("Ask for risky ones", useUnmergedTree = true).assertIsDisplayed()
        fixture.omitResumeYolo = false
        compose.onNodeWithTag("approval-ask").performClick()
        await { fixture.requests.any { it.string("method") == "config.set" && it.getJSONObject("params").string("key") == "yolo" } }
        val reset = fixture.requests.last { it.string("method") == "config.set" }.getJSONObject("params")
        assertEquals("0", reset.string("value"))
        assertEquals("session", reset.string("scope"))
        assertEquals("runtime-chat-a", reset.string("session_id"))
        await { vm.data.value["picker:approvals"]?.optBoolean("session_status_known") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Ask for risky ones").assertIsDisplayed()
    }

    // Without the plugin an unsent chat stays unsaved: YOLO on is taken from Hermes' own confirmation, but YOLO off cannot be proven
    // and is refused, so the shield never claims approvals are back while a bypass may still be on.
    @Test fun withoutThePluginAnUnsentChatTakesYoloOnButNotAnUnprovenOff() = withFixture(prepare = { vm, fixture ->
        fixture.omitResumeYolo = true; fixture.approvalMode = "smart"; fixture.pluginStatus = 404
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = emptyList())
    }) { vm, fixture ->
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "smart" }
        fixture.unsavedRows += vm.chat.value.sessionId
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value && vm.data.value.keys.any { it.startsWith("approvals-chosen:") } }
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-ask").performScrollTo().performClick()
        await { vm.ui.value.error.isNotBlank() && vm.conversationPending.value.isEmpty() }
        assertTrue(vm.ui.value.error, vm.ui.value.error.contains("approvals", ignoreCase = true))
        compose.onNodeWithContentDescription("Approvals: Hermes decides").assertDoesNotExist()
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
    }

    @Test fun aSavedChatsYoloNeverUsesThePlugin() = withFixture(prepare = { _, fixture -> fixture.approvalMode = "smart" }) { vm, fixture ->
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "smart" }
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { fixture.requests.any { it.string("method") == "config.set" && it.getJSONObject("params").string("key") == "yolo" } && vm.conversationPending.value.isEmpty() }
        assertTrue(fixture.requests.none { it.string("path").endsWith("/reserve") })
    }

    // Live 2026-10-26 (1.1.5): YOLO enabled in an unsent chat that Hermes cannot report yet left the shield on "Unknown".
    @Test fun yoloChosenInAnUnsentChatStaysShownAfterTheSave() = withFixture(prepare = { vm, fixture ->
        fixture.omitResumeYolo = true; fixture.approvalMode = "smart"
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = emptyList())
    }) { vm, fixture ->
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "smart" }
        // Real Hermes has no saved row for an unsent chat, so it cannot read the choice back (live 27 Oct: the save failed).
        val chatId = vm.chat.value.sessionId
        fixture.unsavedRows += chatId
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value && vm.data.value.keys.any { it.startsWith("approvals-chosen:") } }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
        compose.onNodeWithContentDescription("Approvals: Unknown").assertDoesNotExist()
        // The chat is saved before YOLO is set, so Hermes keeps the choice on its row.
        val reserve = fixture.requests.indexOfFirst { it.string("path") == "/api/plugins/beacon/mobile/sessions/$chatId/reserve" }
        val set = fixture.requests.indexOfFirst { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "yolo" }
        assertTrue("reserve=$reserve set=$set", reserve in 0 until set)
    }

    // Hermes reports nothing for a chat that is not saved yet; it runs on the profile default, so the shield shows Auto and ticks it.
    @Test fun aNewUnsentChatShowsTheProfileDefaultSelected() = withFixture(prepare = { vm, fixture ->
        fixture.omitResumeYolo = true; fixture.approvalMode = "smart"
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(messages = emptyList())
    }) { vm, _ ->
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "smart" }
        compose.onNodeWithContentDescription("Approvals: Hermes decides").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-ask").assert(isSelected())
        compose.onNodeWithTag("approval-yolo").assert(isNotSelected())
    }

    @Test fun savedTrueBypassIsShownWhenTheRuntimeDoesNotReportIt() = withFixture(prepare = { _, fixture ->
        fixture.omitResumeYolo = true
        fixture.sessions.getValue("chat-a").put("model_config", json("yolo_mode" to true).toString())
    }) { vm, _ ->
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Don't ask").assertIsDisplayed()
    }

    @Test fun changingChatAKeepsProfileDefaultsAndSwitchingLoadsChatBsValues() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.pickModel("other-model", "fixture") }
        await { vm.modelSaving.value.isBlank() && vm.chat.value.raw.string("model") == "other-model" }
        compose.runOnIdle { vm.setReasoning("high") }
        await { !vm.reasoningSaving.value && vm.chat.value.sessionReasoningEffort == "high" }
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        fixture.sessions.getValue("chat-b").getJSONObject("info").put("model", "chat-b-model").put("reasoning_effort", "low").put("yolo", false)
        compose.runOnIdle { vm.selectSession("chat-b") }
        // A cached chat B also sets projectionReady; wait for its live read.
        await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.data.value["picker:approvals"]?.string("session_id") == "chat-b" && vm.chat.value.raw.string("model") == "chat-b-model" }
        assertEquals("low", vm.chat.value.sessionReasoningEffort)
        compose.onNodeWithContentDescription("Approvals: Ask for risky ones").assertIsDisplayed()  // chat B keeps its own setting: not YOLO
        val writes = fixture.requests.filter { it.string("method") == "config.set" }.map { it.getJSONObject("params") }
        assertEquals(setOf("model", "reasoning", "yolo"), writes.map { it.string("key") }.toSet())
        assertTrue(writes.all { it.string("scope") == "session" && it.string("session_id") == "runtime-chat-a" })
        assertEquals("synthetic-model", fixture.profileModel)
        assertEquals("medium", fixture.reasoningEffort)
        assertEquals("manual", fixture.approvalMode)
    }

    @Test fun aNewChatUsesProfileDefaultsInsteadOfThePreviousChatsOverrides() = withFixture(prepare = { _, fixture ->
        fixture.profileModel = "profile-model"; fixture.reasoningEffort = "low"
    }) { vm, fixture ->
        compose.runOnIdle { vm.setReasoning("max") }
        await { !vm.reasoningSaving.value && vm.chat.value.sessionReasoningEffort == "max" }
        compose.runOnIdle { vm.newChat() }
        await { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady }
        assertEquals("profile-model", vm.chat.value.raw.string("model"))
        assertEquals("", vm.chat.value.sessionReasoningEffort)
        compose.runOnIdle { vm.load("profile-defaults", "/api/profile/defaults", mapOf("session_id" to vm.chat.value.sessionId)) }
        await { vm.data.value["profile-defaults"]?.string("session_id") == vm.chat.value.sessionId }
        assertEquals("low", vm.data.value["profile-defaults"]?.string("reasoning_effort"))
        assertEquals("low", fixture.reasoningEffort)
    }

    @Test fun profileSettingsOfferManualAndAutoWithoutChangingChatYolo() = withFixture(
        content = { ProfileApprovalSettings(it) }, prepare = { _, fixture -> fixture.sessions.getValue("chat-a").getJSONObject("info").put("yolo", true) },
    ) { vm, fixture ->
        await { vm.data.value["profile:approvals"] != null }
        // The segments say what they do themselves, so the line that explained Manual and Auto is gone.
        compose.onAllNodesWithTag("profile-approvals-caption").assertCountEquals(0)
        assertTrue(compose.onAllNodesWithText("Ask for risky ones").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Hermes decides").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("profile-approval-smart").performClick()
        await { fixture.approvalMode == "smart" && vm.data.value["profile:approvals"]?.string("value") == "smart" }
        compose.onNodeWithTag("profile-approval-smart").assert(isSelected())
        compose.onNodeWithTag("profile-approval-manual").assert(isNotSelected())
        assertTrue(fixture.sessions.getValue("chat-a").getJSONObject("info").getBoolean("yolo"))
        assertTrue(fixture.requests.filter { it.string("method") == "config.set" }.all { it.getJSONObject("params").string("key") == "approvals.mode" })
    }

    // Hermes can store "off", which is not a segment: the page says so and either segment turns approvals back on.
    @Test fun profileApprovalsOffIsShownAndEitherSegmentTurnsThemBackOn() = withFixture(
        content = { ProfileApprovalSettings(it) }, prepare = { _, fixture -> fixture.approvalMode = "off" },
    ) { vm, fixture ->
        await { vm.data.value["profile:approvals"] != null }
        compose.onNodeWithTag("profile-approvals-off").assertIsDisplayed()
        compose.onNodeWithText("Approvals off").assertIsDisplayed()
        compose.onNodeWithTag("profile-approval-manual").assert(isNotSelected())
        compose.onNodeWithTag("profile-approval-smart").assert(isNotSelected())
        compose.onNodeWithTag("profile-approval-manual").assertHeightIsAtLeast(48.dp).performClick()
        await { fixture.approvalMode == "manual" && vm.data.value["profile:approvals"]?.string("value") == "manual" }
        compose.onNodeWithTag("profile-approval-manual").assert(isSelected())
        compose.onNodeWithTag("profile-approvals-off").assertDoesNotExist()
    }

    /** [text] is laid out whole: its last visible line ends at its last character and nothing is ellipsized (as UxaLayoutUiTest reads it). */
    private fun assertWhole(text: String) {
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val result = results.single(); val last = result.lineCount - 1
        assertEquals("\"$text\" shows every character", text.length, result.getLineEnd(last, visibleEnd = true))
        assertFalse("\"$text\" ends in an ellipsis", result.isLineEllipsized(last))
    }

    // UX re-check of F9, NEW-P1-1: at a large font the segment cut "Ask for risky ones"; the row now falls back to the dropdown when the words do not fit.
    @Test fun theProfilesApprovalsNeverCutAskForRiskyOnesAtLargeText() {
        var scale by mutableFloatStateOf(1f)
        withFixture(
            content = { vm -> val base = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, scale)) { ProfileApprovalSettings(vm) } },
            prepare = { _, fixture -> fixture.approvalMode = "manual" },
        ) { vm, _ ->
            await { vm.data.value["profile:approvals"]?.string("value") == "manual" }
            for (size in listOf(1f, 1.3f, 2f)) {
                compose.runOnIdle { scale = size }
                compose.waitForIdle()
                assertWhole("Ask for risky ones")
            }
            // At the largest font the two words cannot sit side by side: it is the dropdown row, whose option tags appear only when it opens.
            compose.onNodeWithTag("profile-approval-manual").assertDoesNotExist()
        }
    }

    @Test fun approvalAndModelRemainSeparateTalkBackControlsInArabicAtLargeText() {
        var width by mutableIntStateOf(360)
        withFixture(content = { vm ->
            androidx.compose.foundation.layout.BoxWithConstraints {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val scaled = androidx.compose.ui.unit.Density(maxWidth.value * density.density / width, 1.6f)
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides scaled,
                    androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                    NativeLocalization("ar") { ChatScreen(vm) }
                }
            }
        }, prepare = { _, fixture -> fixture.approvalMode = "manual" }) { vm, _ ->
            await { vm.data.value["picker:approvals"]?.string("profile_mode") == "manual" }
            for (dpWidth in listOf(360, 720)) {
                compose.runOnIdle { width = dpWidth }
                val approval = compose.onNodeWithContentDescription("الموافقات: السؤال عند الإجراءات الخطرة").assertIsDisplayed()
                val model = compose.onNodeWithTag("composer-model-effort", useUnmergedTree = true).assertIsDisplayed()
                val shieldBounds = approval.fetchSemanticsNode().boundsInRoot
                val modelBounds = model.fetchSemanticsNode().boundsInRoot
                assertTrue("Separate touch targets at ${dpWidth}dp", !shieldBounds.overlaps(modelBounds))
            }
        }
    }

    @Test fun reconnectKeepsTheVisibleReasoningSheetOnlyForTheSameChat() = withFixture(content = { NativeCatalog(it) }) { vm, _ ->
        compose.runOnIdle { vm.openCatalog("Reasoning") }
        await { vm.catalog.value == "Reasoning" && vm.data.value["picker:reasoning"]?.has("options") == true }
        compose.runOnIdle { vm.selectSession("chat-a", reopen = true) }
        runBlocking { vm.awaitSelection() }
        assertEquals("Reasoning", vm.catalog.value)
        compose.runOnIdle { vm.selectSession("chat-b") }
        runBlocking { vm.awaitSelection() }
        assertEquals("", vm.catalog.value)
    }

    // The F9 G3 crash: under this v1 rule a composition scope's coroutine resumes on the thread that woke it, so state written after a network call
    // could be written off the main thread. The approval scopes use the app's own UI dispatcher, which brings them back to the main thread.
    @Test fun theApprovalScopesResumeOnTheMainThreadAfterANetworkHop() {
        val fixed = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        val plain = java.util.concurrent.atomic.AtomicReference<Boolean?>(null)
        fun onMain() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        compose.setContent {
            val appScope = rememberCoroutineScope { MainUiDispatcher }
            val defaultScope = rememberCoroutineScope()
            LaunchedEffect(Unit) {
                appScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(50) }; fixed.set(onMain()) }
                defaultScope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Thread.sleep(50) }; plain.set(onMain()) }
            }
        }
        compose.waitUntil(5_000) { fixed.get() != null && plain.get() != null }
        assertEquals("the approval scope resumes on the main thread", true, fixed.get())
        // The control (not asserted): the rule's own scope resumes where the IO call ended.
        println("R28F_DISPATCHER_CONTROL defaultScopeResumedOnMain=${plain.get()}")
    }

    @Test fun modelLoadingHidesTheCustomEntryAction() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ -> flow<String>(vm, "_catalog").value = "Model & provider" },
    ) { _, _ ->
        compose.onNodeWithTag("models-loading").assertIsDisplayed()
        compose.onNodeWithText("Enter a configured model ID").assertDoesNotExist()
    }

    @Test fun modelPickerHasOneIdLabelAndSelectedCheckWithCurrentProviderFirst() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ -> vm.storeData("picker:models", modelRows()); flow<String>(vm, "_catalog").value = "Model & provider" },
    ) { vm, _ ->
        assertEquals("Exercises the provider fallback fixed in c24fd01", "", vm.chat.value.raw.string("model_provider"))
        compose.onAllNodesWithText("synthetic-model").assertCountEquals(1)
        compose.onNodeWithTag("model:fixture:synthetic-model", useUnmergedTree = true).assert(isSelected()).assert(hasAnyDescendant(hasContentDescription("Selected")))
        assertTrue(compose.onNodeWithText("Current provider").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Other provider").fetchSemanticsNode().boundsInRoot.top)
    }

    // Live 2026-10-26: a Copilot chat reports "github-copilot" while its picker group is "copilot", and another group offers the same model.
    @Test fun theModelPickerMarksTheRowHermesNamesForARunningChatWhoseProviderIsReportedDifferently() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ ->
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(raw = vm.chat.value.raw.copy().put("model", "synthetic-model").put("model_provider", "github-copilot").put("runtime_session_id", "runtime-chat-a"))
            vm.storeData("picker:models", json("groups" to JSONArray()
                .put(json("provider_id" to "other", "provider" to "Other provider", "models" to JSONArray().put(json("id" to "synthetic-model", "label" to "synthetic-model"))))
                .put(json("provider_id" to "copilot", "provider" to "GitHub Copilot", "models" to JSONArray().put(json("id" to "synthetic-model", "label" to "synthetic-model"))))))
            flow<String>(vm, "_catalog").value = "Model & provider"
        },
    ) { vm, fixture ->
        await { vm.data.value["picker:model-current"]?.string("provider_id") == "copilot" }
        compose.onNodeWithTag("model:copilot:synthetic-model", useUnmergedTree = true).assert(isSelected()).assert(hasAnyDescendant(hasContentDescription("Selected")))
        compose.onNodeWithTag("model:other:synthetic-model", useUnmergedTree = true).assert(isNotSelected())
        assertTrue(compose.onNodeWithText("GitHub Copilot").fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText("Other provider").fetchSemanticsNode().boundsInRoot.top)
        assertTrue("Asking Hermes which row it means changes nothing", fixture.requests.none { it.string("method") in setOf("config.set", "prompt.submit") })
    }

    @Test fun slowModelPickShowsProgressAndCannotBeSubmittedTwice() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, fixture ->
            fixture.holdConfigAcknowledgement = true
            vm.storeData("picker:models", modelRows()); flow<String>(vm, "_catalog").value = "Model & provider"
        },
    ) { vm, fixture ->
        compose.onNodeWithTag("model:fixture:other-model").performClick()
        await { vm.modelSaving.value.isNotBlank() }
        compose.onNodeWithTag("model-applying").assertIsDisplayed()
        compose.onNodeWithTag("model:fixture:synthetic-model").assertIsNotEnabled()
        compose.runOnIdle { vm.pickModel("other-model", "fixture") }
        assertEquals(1, fixture.requests.count { it.string("method") == "config.set" })
        fixture.releaseCreateAcknowledgement()
        await { vm.modelSaving.value.isBlank() && vm.catalog.value.isBlank() }
    }

    private fun refreshEffort(vm: BeaconViewModel) = runBlocking { withContext(Dispatchers.Main) { vm.refreshChatEffort() } }

    private fun effortRequest(vm: BeaconViewModel) = screenRequest("/api/reasoning", mapOf(
        "session_id" to vm.chat.value.sessionId, "model" to vm.chat.value.raw.string("model"), "provider" to vm.chat.value.raw.string("model_provider")))

    @Test fun unboundLegacyInventoryDoesNotCacheAnotherProfilesEffort() = withFixture(content = {}) { vm, fixture ->
        val request = effortRequest(vm)
        runBlocking { vm.screens.open(fixture.scope); vm.screens.remove(fixture.scope, "picker:reasoning", request) }
        // Only the other profile's copy of chat-a is live. Legacy rows omit their owning profile.
        val foreign = "runtime-other-profile"
        fixture.legacyInventory = JSONArray().put(json("session_key" to "chat-a", "id" to foreign, "status" to "idle"))
        fixture.sessionReasoning["other-profile"] = "xhigh"
        refreshEffort(vm)
        assertTrue(fixture.requests.any { it.string("method") == "session.active_list" })
        assertFalse("queried another profile's runtime", fixture.requests.any { it.string("method") == "config.get" && it.optJSONObject("params")?.string("session_id") == foreign })
        assertNull(vm.screens.exact(fixture.scope, "picker:reasoning", request))
        assertEquals("", vm.chat.value.sessionReasoningEffort)
    }

    private fun overlappingEffortReads(pickerFinishesFirst: Boolean) = withFixture(content = {}) { vm, fixture ->
        fixture.canonicalAuthority = true
        fixture.sessionReasoning["chat-a"] = "low"
        val request = effortRequest(vm)
        fixture.holdReasoningOptions.set(true)
        compose.runOnIdle { vm.openCatalog("Reasoning") }
        await { fixture.reasoningOptionsHeld.count == 0L } // The picker has Low, but still needs its options.
        fixture.sessionReasoning["chat-a"] = "high"
        fixture.holdNextReasoningRead.set(pickerFinishesFirst)
        val passive = compose.runOnIdle { vm.viewModelScope.launch { vm.refreshChatEffort() } }
        if (pickerFinishesFirst) {
            await { fixture.reasoningReadHeld.count == 0L }
            fixture.releaseReasoningOptions.countDown()
            await { "picker:reasoning" !in vm.refreshing.value }
            fixture.releaseCreateAcknowledgement()
        } else {
            runBlocking { passive.join() }
            assertEquals("high", vm.chat.value.sessionReasoningEffort)
            fixture.releaseReasoningOptions.countDown()
        }
        runBlocking { passive.join() }
        await { "picker:reasoning" !in vm.refreshing.value }
        assertEquals("newer effort on chip", "high", vm.chat.value.sessionReasoningEffort)
        val sheet = vm.data.value.getValue("picker:reasoning")
        assertEquals("high", sheet.getString("value"))
        assertEquals(fixture.reasoningOptions, sheet.getJSONArray("options").strings())
        assertEquals("high", vm.screens.exact(fixture.scope, "picker:reasoning", request)?.value?.string("value"))
    }

    @Test fun slowPickerCannotOverwriteTheForegroundEffort() = overlappingEffortReads(false)
    @Test fun foregroundEffortArrivingAfterPickerKeepsTheOptions() = overlappingEffortReads(true)

    @Test fun reloadingANewModelOrProviderLeavesEffortUnknownUntilItsOwnRead() = withFixture(content = {}) { vm, fixture ->
        fixture.canonicalAuthority = true
        fixture.sessionReasoning["chat-a"] = "xhigh"
        runBlocking { vm.screens.clear(fixture.scope); vm.screens.open(fixture.scope) }
        val info = fixture.sessions.getValue("chat-a").getJSONObject("info")
        for ((fieldName, value) in listOf("model" to "r5-model", "provider" to "r5-provider")) {
            compose.runOnIdle { vm.selectSession("chat-a", reopen = true) }
            runBlocking { vm.awaitSelection() }
            refreshEffort(vm)
            assertEquals("xhigh", vm.chat.value.sessionReasoningEffort)
            info.put(fieldName, value)
            compose.runOnIdle { vm.selectSession("chat-a", reopen = true) }
            runBlocking { vm.awaitSelection() }
            assertEquals(value, vm.chat.value.raw.string(if (fieldName == "provider") "model_provider" else fieldName))
            assertEquals("effort after changing $fieldName", "", vm.chat.value.sessionReasoningEffort)
        }
    }

    @Test fun confirmedEffortSurvivesLeavingTheChatBeforeReadback() = withFixture(content = {}) { vm, fixture ->
        fixture.canonicalAuthority = true
        fixture.persistReasoningInRow = false
        fixture.sessionReasoning["chat-a"] = "low"
        compose.runOnIdle { vm.selectSession("chat-a") }
        runBlocking { vm.awaitSelection() }
        refreshEffort(vm)
        val request = effortRequest(vm)
        assertEquals("low", vm.chat.value.sessionReasoningEffort)
        fixture.holdNextReasoningRead.set(true)
        compose.runOnIdle { vm.setReasoning("high") }
        await { fixture.reasoningReadHeld.count == 0L && fixture.sessionReasoning["chat-a"] == "high" }
        compose.runOnIdle { vm.selectSession("chat-b") }
        runBlocking { vm.awaitSelection() }
        val other = vm.chat.value.sessionReasoningEffort
        fixture.releaseCreateAcknowledgement()
        await { vm.conversationPending.value.isEmpty() }
        assertEquals("chat-b", vm.chat.value.sessionId)
        assertEquals(other, vm.chat.value.sessionReasoningEffort)
        val cache = runBlocking { app.cache.read(fixture.scope.copy(profile = ""), "screens") }
        val saved = cache?.let(::JSONObject)?.rows("e")?.singleOrNull { it.string("p") == fixture.scope.profile && it.string("r") == request }
        assertEquals("durable chat A effort", "high", saved?.optJSONObject("d")?.string("value"))
        compose.runOnIdle {
            field(vm, "cachedOwnerScope", fixture.scope)
            field(vm, "client", null)
            flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false)
            vm.selectSession("chat-a")
        }
        runBlocking { vm.awaitSelection() }
        assertEquals("high", vm.chat.value.sessionReasoningEffort)
    }

    @Test fun coldPassiveRefreshReadsSavedSignInOffTheMainThread() = withFixture(content = {}) { vm, fixture ->
        fixture.canonicalAuthority = true
        val mainThread = android.os.Looper.getMainLooper().thread
        val reads = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val prefs = app.getSharedPreferences("r5-synthetic-auth", android.content.Context.MODE_PRIVATE)
        val context = object : android.content.ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int) = object : android.content.SharedPreferences by prefs {
                override fun getString(key: String?, defValue: String?): String? {
                    reads += Thread.currentThread() === mainThread
                    return prefs.getString(key, defValue)
                }
            }
        }
        val store = AuthStore(context, app.cipher)
        val auth = fixture.client.auth.copy(cookies = listOf("__Host-hermes_session_rt=fixture-cookie; Path=/; Secure; HttpOnly"))
        runBlocking { store.persist(auth) }
        val cold = fixture.clientFor(auth, store)
        try {
            compose.runOnIdle { field(vm, "client", cold) }
            reads.clear()
            refreshEffort(vm)
            assertEquals(fixture.reasoningEffort, vm.chat.value.sessionReasoningEffort)
            assertTrue("cold admission read the encrypted record", reads.isNotEmpty())
            assertFalse("saved sign-in read on the main thread", true in reads)
        } finally {
            cold.closeNative()
            runBlocking { store.remove(auth.origin) }
        }
    }

    @Test fun readbackFromAnEarlierSignInCannotChangeTheNewSignInsCache() = withFixture(content = {}) { vm, fixture ->
        fixture.canonicalAuthority = true
        fixture.persistReasoningInRow = false
        val auth = fixture.client.auth.copy(cookies = listOf("__Host-hermes_session_rt=fixture-cookie; Path=/; Secure; HttpOnly"))
        runBlocking { app.auth.persist(auth) }
        fixture.sessionReasoning["chat-a"] = "low"
        refreshEffort(vm)
        fixture.holdNextReasoningRead.set(true)
        compose.runOnIdle { vm.setReasoning("high") }
        await { fixture.reasoningReadHeld.count == 0L }
        runBlocking { app.auth.persist(auth) } // A fresh sign-in to the same account gets its own admission.
        fixture.releaseCreateAcknowledgement()
        await { vm.conversationPending.value.isEmpty() }
        assertEquals("high", fixture.sessionReasoning["chat-a"])
        assertEquals("low", vm.chat.value.sessionReasoningEffort)
        assertEquals("low", vm.screens.exact(fixture.scope, "picker:reasoning", effortRequest(vm))?.value?.string("value"))
    }

    @Test fun passiveComposerWaitsForTheLegacyRuntimeBeforeShowingAnEffort() = withFixture(
        content = { ChatScreen(it) }, prepare = { vm, fixture ->
            fixture.reasoningEffort = "max"
            fixture.sessionReasoning["chat-a"] = "xhigh"
            runBlocking { fixture.client.nativeSession("chat-a") }
            fixture.requests.clear()
            fixture.unseenTurns += "chat-a"
            fixture.holdActiveListAcknowledgement = true
            val raw = vm.chat.value.raw.copy().put("model_config", """{"provider":"fixture"}""").apply { remove("runtime_session_id") }
            flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(raw = raw)
        },
    ) { vm, fixture ->
        await { fixture.requests.any { it.string("method") == "session.active_list" } && vm.data.value["profile-defaults"]?.string("reasoning_effort") == "max" }
        compose.onNodeWithTag("composer-model-effort").assert(!hasText("Max"))
        fixture.releaseCreateAcknowledgement()
        await { vm.chat.value.sessionReasoningEffort == "xhigh" }
        compose.onNodeWithTag("composer-model-effort").assert(hasText("Extra high"))
        assertTrue(fixture.requests.any { it.string("method") == "config.get" && it.getJSONObject("params").let { p -> p.string("key") == "reasoning" && p.string("session_id") == "runtime-chat-a" } })
        assertFalse(fixture.requests.any { it.string("method") in setOf("session.resume", "prompt.submit") })
    }

    @Test fun runtimeEffortSurvivesAProviderOnlyHistoryRowAndAnOfflineColdOpen() {
        var restarted by mutableStateOf<BeaconViewModel?>(null)
        val store = ViewModelStore()
        withFixture(content = { ChatScreen(restarted ?: it) }, prepare = { _, fixture ->
            fixture.canonicalAuthority = true
            fixture.reasoningEffort = "max"
            fixture.reasoningOptions = listOf("high", "xhigh", "max")
            fixture.persistReasoningInRow = false
            fixture.sessions.getValue("chat-a").put("model_config", """{"provider":"fixture"}""")
            fixture.sessionReasoning["chat-a"] = "high"
        }) { vm, fixture ->
            fun chip(label: String) = compose.onNodeWithTag("composer-model-effort").assert(hasText(label))
            try {
                compose.runOnIdle { vm.selectSession("chat-a") }
                await { vm.chat.value.sessionReasoningEffort == "high" }
                chip("High").performClick()
                await { vm.data.value["picker:reasoning"]?.has("options") == true }
                compose.onNodeWithTag("reasoning-level:high").assert(isSelected())
                compose.onNodeWithTag("reasoning-level:xhigh").performClick()
                await { !vm.reasoningSaving.value && vm.chat.value.sessionReasoningEffort == "xhigh" }
                compose.onNodeWithTag("reasoning-level:xhigh").assert(isSelected())
                compose.runOnIdle { vm.closeCatalog() }
                chip("Extra high")
                assertEquals("max", fixture.reasoningEffort)
                assertEquals("xhigh", fixture.sessionReasoning["chat-a"])
                assertEquals("""{"provider":"fixture"}""", fixture.sessions.getValue("chat-a").getString("model_config"))

                compose.runOnIdle { vm.selectSession("chat-a", reopen = true) }
                runBlocking { vm.awaitSelection() }
                chip("Extra high")
                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.sessionReasoningEffort == "max" }
                chip("Max")
                compose.runOnIdle { vm.selectSession("chat-a") }
                runBlocking { vm.awaitSelection() }
                chip("Extra high")
                val diskRow = JSONObject(requireNotNull(runBlocking { app.cache.read(fixture.scope.session("chat-a"), "session") }))
                assertTrue((diskRow.optJSONObject("session") ?: diskRow).string("reasoning_effort").isBlank())

                val fresh = compose.runOnIdle {
                    ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                }
                // A new view model reads the encrypted screen cache with its connection still down.
                runBlocking { fresh.screens.open(fixture.scope) }
                compose.runOnIdle {
                    field(fresh, "cachedOwnerScope", fixture.scope)
                    flow<AppState>(fresh, "_ui").value = vm.ui.value.copy(connected = false)
                    restarted = fresh
                    fresh.selectSession("chat-a")
                }
                runBlocking { fresh.awaitSelection() }
                assertNull(fresh.client)
                chip("Extra high")
                fixture.sessionReasoning["chat-a"] = "high"
                val before = fixture.requests.size
                compose.runOnIdle {
                    field(fresh, "client", fixture.client)
                    flow<AppState>(fresh, "_ui").value = fresh.ui.value.copy(connected = true)
                    fresh.selectSession("chat-a", reopen = true)
                }
                await { fresh.chat.value.sessionReasoningEffort == "high" }
                chip("High")
                val reads = fixture.requests.toList().drop(before)
                assertTrue(reads.any { it.string("method") == "config.get" && it.getJSONObject("params").let { p -> p.string("key") == "reasoning" && p.string("session_id") == "chat-a" } })
                assertFalse(reads.any { it.string("method") in setOf("session.resume", "prompt.submit", "config.set") })
            } finally { compose.runOnUiThread { store.clear() } }
        }
    }

    @Test fun reasoningPickerExplainsExtraHighAndMax() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ ->
            // The sheet shows only a read made for this chat and model (the saved one counts), so the levels are seeded the way a first read leaves them.
            val raw = vm.chat.value.raw
            runBlocking { vm.screens.open(vm.client!!.scope) }
            vm.screens.set(vm.client!!.scope, "picker:reasoning", json("value" to "xhigh", "options" to JSONArray(listOf("xhigh", "max"))),
                dev.hermes.android.data.screenRequest("/api/reasoning", mapOf("session_id" to "chat-a", "model" to raw.string("model"), "provider" to raw.string("model_provider"))))
            flow<String>(vm, "_catalog").value = "Reasoning"
        },
    ) { _, _ ->
        compose.onNodeWithText("Extra high", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Spend more time on difficult problems").assertIsDisplayed()
        compose.onNodeWithText("Thinks as long as it needs").assertIsDisplayed()
    }

    // 2026-10-26: the chat's Toolsets sheet carries the same Tools and MCP servers controls as Settings, not a read-only list.
    @Test fun chatToolsetsSheetShowsTheSettingsToolsAndSendsTheSameToggle() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ -> flow<String>(vm, "_catalog").value = "Toolsets" },
    ) { vm, fixture ->
        compose.onNodeWithTag("chat-toolsets").assertIsDisplayed()
        compose.onNodeWithTag("chat-toolsets-tools").assertHeightIsAtLeast(48.dp).assert(isSelected())
        compose.onNodeWithTag("chat-toolsets-mcp").assertHeightIsAtLeast(48.dp).assert(isNotSelected())
        await { vm.data.value["native-tools"] != null && "native-tools" !in vm.refreshing.value }
        compose.onNodeWithText("Web research").assertIsDisplayed()
        compose.onNodeWithText("Chat commands").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change for all chats").assertDoesNotExist()
        compose.onNode(hasText("Web research") and isToggleable()).performClick()
        await { !fixture.toolEnabled && "native-tools" !in vm.refreshing.value }
        val write = fixture.requests.single { it.string("method") == "PUT" && it.string("path") == "/api/tools/toolsets/web" }
        assertEquals(false, write.getJSONObject("body").getBoolean("enabled"))
        assertEquals(fixture.client.auth.profile, write.getJSONObject("body").string("profile"))
        assertEquals(fixture.client.auth.profile, write.string("profile"))
    }

    @Test fun chatToolsetsSheetSwitchesToTheMcpServersPage() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ -> flow<String>(vm, "_catalog").value = "Toolsets" },
    ) { vm, fixture ->
        compose.onNodeWithText("Changes apply to this profile's new chats.").assertDoesNotExist()
        compose.onNodeWithTag("chat-toolsets-mcp").performClick()
        compose.onNodeWithTag("chat-toolsets-mcp").assert(isSelected())
        compose.onNodeWithTag("chat-toolsets-tools").assert(isNotSelected())
        await { vm.data.value["native-mcp"] != null && "native-mcp" !in vm.refreshing.value }
        compose.onNodeWithText("fixture_mcp").assertIsDisplayed()
        compose.onNodeWithText("Changes apply to this profile's new chats.").assertIsDisplayed()
        compose.onNodeWithText("Web research").assertDoesNotExist()
        compose.onNodeWithText("Test").performScrollTo().performClick()
        await { vm.data.value["mcp-test:fixture_mcp"] != null }
        compose.onNodeWithText("1 tool").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View tools · 1").performScrollTo().performClick()
        compose.onNodeWithText("read_fixture").assertExists()
        compose.onNode(hasText("fixture_mcp") and isToggleable()).performClick()
        await { !fixture.connectionEnabled && "native-mcp" !in vm.refreshing.value }
        assertEquals(1, fixture.requests.count { it.string("method") == "POST" && it.string("path") == "/api/mcp/servers/fixture_mcp/test" })
        assertEquals(false, fixture.requests.single { it.string("method") == "PUT" && it.string("path") == "/api/mcp/servers/fixture_mcp/enabled" }.getJSONObject("body").getBoolean("enabled"))
        compose.onNodeWithTag("chat-toolsets-tools").performClick()
        compose.onNodeWithText("Web research").assertIsDisplayed()
    }

    @Test fun historyFiltersLoadedTitlesAndShowsNoMatchesWhileTheServerIsPending() = withFixture(
        content = { SessionList(it) }, prepare = { _, fixture -> fixture.holdNextHistoryList.set(true) },
    ) { _, fixture ->
        await { fixture.historyListCaptured.count == 0L }
        compose.onNodeWithTag("history-search-toggle").performClick()
        compose.onNodeWithTag("history-search").performTextReplacement("Travel")
        compose.onNodeWithTag("history-row:chat-a").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-row:chat-b").assertDoesNotExist()
        compose.onNodeWithTag("history-search").performTextReplacement("no such title")
        compose.onNodeWithTag("history-no-matches").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history-row:chat-a").assertDoesNotExist()
        fixture.releaseHistoryList.countDown()
    }

    private val archiveSnackbar: @Composable (BeaconViewModel) -> Unit = { vm ->
        val ui by vm.ui.collectAsState()
        val undo by vm.archiveUndo.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        NativeFeedbackEffect(ui.error, ui.notice, false, snackbar, actionLabel = "Undo".takeIf { undo != null && ui.notice == "Archived" }, onAction = vm::undoArchive, clearMessage = vm::clearMessage)
        Column { SnackbarHost(snackbar) }
    }

    /**
     * Hermes turns the row visible and the Undo offer is gone at the tap, yet the app has not been answered: its notice is still
     * "Updating archive…" until the answer arrives. The undo is over only at "Unarchived"; a wait on the row and the offer alone
     * is satisfied in this state (this case holds Hermes' answer to keep it open; the case below waits on the notice).
     */
    @Test fun anUndoIsOverWhenTheNoticeSaysUnarchivedNotWhenHermesHasTheRowBack() = withFixture(content = archiveSnackbar) { vm, fixture ->
        compose.runOnIdle { vm.conversationAction("chat-a", "/api/session/archive", json("archived" to true), "Archived") }
        await { vm.archiveUndo.value != null }
        fixture.holdHiddenAcknowledgement = true
        compose.onNodeWithText("Undo").performClick()
        await { !fixture.sessions.getValue("chat-a").getJSONObject("info").optBoolean("hidden") }
        assertNull(vm.archiveUndo.value)
        assertEquals("Updating archive…", vm.ui.value.notice)
        fixture.releaseCreateAcknowledgement()
        await { vm.ui.value.notice == "Unarchived" }
    }

    @Test fun archivedSnackbarOffersUndoForTheOriginalConversation() = withFixture(content = archiveSnackbar) { vm, fixture ->
        compose.runOnIdle { vm.conversationAction("chat-a", "/api/session/archive", json("archived" to true), "Archived") }
        await { vm.archiveUndo.value != null }
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        // The undo is over when the view model says "Unarchived" (BeaconViewModel.conversationAction). Hermes' row turns visible, and the Undo
        // offer is cleared at the tap, before that: waiting on those two alone could read the "Updating archive…" notice still on screen.
        await { vm.ui.value.notice == "Unarchived" }
        assertFalse(fixture.sessions.getValue("chat-a").getJSONObject("info").optBoolean("hidden"))
        assertNull(vm.archiveUndo.value)
        assertEquals("Unarchived", vm.ui.value.notice)
    }

    @Test fun slowBranchShowsPendingAndIgnoresRepeatedTaps() = withFixture { vm, fixture ->
        fixture.holdBranchAcknowledgement = true
        compose.runOnIdle { vm.sessionAction("/api/session/branch", json("keep_count" to 1)); vm.sessionAction("/api/session/branch", json("keep_count" to 1)) }
        await { fixture.requests.count { it.string("method") == "session.branch" } == 1 }
        assertTrue(vm.ui.value.notice.startsWith("Branching"))
        assertTrue(vm.conversationPending.value.isNotEmpty())
        fixture.releaseCreateAcknowledgement()
        await { vm.conversationPending.value.isEmpty() }
    }

    @Test fun fixtureRetainsTerminalEventsOnTheMatchingConversation() {
        val fixture = NativeExperienceFixture()
        fixture.persistTerminalMessages = true
        try {
            val chatA = fixture.sessions.getValue("chat-a").getJSONArray("messages")
            val chatB = fixture.sessions.getValue("chat-b").getJSONArray("messages")
            val beforeA = chatA.length()
            val beforeB = chatB.length()
            val event = fixture.retainNativeEvent("runtime-chat-b", "message.complete", json("text" to "Only chat B finished"))
            assertEquals("runtime-chat-b", event.getString("session_id"))
            assertEquals(beforeA, chatA.length())
            assertEquals(beforeB + 1, chatB.length())
            assertEquals("Only chat B finished", chatB.getJSONObject(beforeB).getString("text"))
        } finally {
            fixture.client.closeNative()
        }
    }
}
