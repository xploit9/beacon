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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
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
            fixture.releaseCreateAcknowledgement()
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.preferences.edit { prior[originKey]?.let { value -> it[originKey] = value } ?: it.remove(originKey) }
                app.auth.remove(fixture.scope.origin)
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
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
        compose.onNodeWithText("Manual", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ask before actions", useUnmergedTree = true).assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-yolo", useUnmergedTree = true).performScrollTo().assert(isSelected()).assert(hasAnyDescendant(hasContentDescription("Selected")))
        compose.onNodeWithTag("approval-ask").performScrollTo().performClick()
        await { vm.data.value["picker:approvals"]?.let { it.optBoolean("session_status_known") && !it.optBoolean("session_bypass") } == true }
        val writes = fixture.requests.filter { it.string("method") == "config.set" }.map { it.getJSONObject("params") }
        assertEquals(listOf("1", "0"), writes.map { it.string("value") })
        assertTrue(writes.all { it.string("key") == "yolo" && it.string("scope") == "session" && it.string("session_id") == "runtime-chat-a" })
        assertEquals("manual", fixture.approvalMode)
        compose.onNodeWithContentDescription("Approvals: Manual").assertIsDisplayed()
    }

    // Live 2026-10-26: enabling YOLO in a just-created chat showed "Approvals: Unknown" for about 5 s while Hermes saved it.
    @Test fun enablingYoloShowsYoloAtOnceInsteadOfUnknownWhileItSaves() = withFixture { vm, fixture ->
        open(vm)
        compose.onNodeWithTag("approval-yolo").performScrollTo().performClick()
        fixture.holdConfigAcknowledgement = true
        compose.onNode(hasText("Skip approvals") and hasAnyAncestor(isDialog())).performClick()
        await { vm.conversationPending.value.isNotEmpty() }
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
        compose.onNodeWithContentDescription("Approvals: Unknown").assertDoesNotExist()
        fixture.releaseCreateAcknowledgement()
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Auto").assertIsDisplayed()
        open(vm)
        compose.onNodeWithText("Auto", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Ask only for risky actions", useUnmergedTree = true).assertIsDisplayed()
        val resumes = fixture.requests.filter { it.string("method") == "session.resume" }
        assertTrue(resumes.isNotEmpty() && resumes.all { it.getJSONObject("params").optBoolean("lazy") && it.getJSONObject("params").optBoolean("omit_messages") })
    }

    @Test fun passiveHistoryDoesNotResumeToPaintTheApprovalShield() = withFixture(prepare = { vm, _ ->
        flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(raw = vm.chat.value.raw.copy().apply { remove("runtime_session_id") })
    }) { vm, fixture ->
        // A browsed chat shows its own saved choice (not YOLO), so the shield shows the profile's mode instead of Unknown.
        await { vm.data.value["picker:approvals"]?.string("profile_mode") == "manual" }
        compose.onNodeWithContentDescription("Approvals: Manual").assertIsDisplayed()
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

                compose.runOnIdle { vm.selectSession("chat-b") }
                await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && vm.chat.value.messages.map { it.id } == listOf("201", "202", "203") }
                assertSavedRows()
                assertTrue("Opening history is passive", fixture.requests.none { it.string("method") == "session.resume" })

                fixture.holdConfigAcknowledgement = true
                compose.runOnIdle { vm.pickModel("other-model", "fixture") }
                await { fixture.requests.any { it.string("method") == "config.set" } && vm.modelSaving.value.isNotBlank() }
                assertEquals("fixture:other-model", vm.modelSaving.value)
                assertTrue("The actual model action remains pending", vm.conversationPending.value.isNotEmpty())
                fixture.releaseCreateAcknowledgement()
                await { vm.modelSaving.value.isBlank() && vm.conversationPending.value.isEmpty() && vm.chat.value.raw.string("model") == "other-model" }
                assertEquals("fixture", vm.chat.value.raw.string("model_provider"))
                assertEquals("runtime-chat-b", vm.chat.value.raw.string("runtime_session_id"))
                assertEquals("chat-b", vm.chat.value.raw.string("stored_session_id"))
                assertSavedRows()

                compose.runOnIdle { vm.selectSession("chat-a") }
                await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && vm.chat.value.messages.any { it.id == "10" } }
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
                val resumes = fixture.requests.filter { it.string("method") == "session.resume" }
                assertTrue(resumes.isNotEmpty())
                assertTrue(resumes.all { it.getJSONObject("params").let { p -> p.string("session_id") == "chat-b" && p.opt("inline_images") == false && p.opt("omit_messages") == false } })
                assertTrue(fixture.requests.filter { it.has("params") && it.string("method") != "client.capabilities" }.all { it.getJSONObject("params").string("profile") == scope.profile })
                assertTrue(fixture.requests.filter { it.has("path") }.all { it.string("profile") == scope.profile })
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
        compose.onNodeWithContentDescription("Approvals: Unknown").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-ask").assert(isNotSelected())
        compose.onNodeWithTag("approval-yolo").assert(isNotSelected())
        compose.onNodeWithText("Ask before actions", useUnmergedTree = true).assertIsDisplayed()
        fixture.omitResumeYolo = false
        compose.onNodeWithTag("approval-ask").performClick()
        await { fixture.requests.any { it.string("method") == "config.set" && it.getJSONObject("params").string("key") == "yolo" } }
        val reset = fixture.requests.last { it.string("method") == "config.set" }.getJSONObject("params")
        assertEquals("0", reset.string("value"))
        assertEquals("session", reset.string("scope"))
        assertEquals("runtime-chat-a", reset.string("session_id"))
        await { vm.data.value["picker:approvals"]?.optBoolean("session_status_known") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Manual").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-ask").performScrollTo().performClick()
        await { vm.ui.value.error.isNotBlank() && vm.conversationPending.value.isEmpty() }
        assertTrue(vm.ui.value.error, vm.ui.value.error.contains("approvals", ignoreCase = true))
        compose.onNodeWithContentDescription("Approvals: Auto").assertDoesNotExist()
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Auto").assertIsDisplayed()
        open(vm)
        compose.onNodeWithTag("approval-ask").assert(isSelected())
        compose.onNodeWithTag("approval-yolo").assert(isNotSelected())
    }

    @Test fun savedTrueBypassIsShownWhenTheRuntimeDoesNotReportIt() = withFixture(prepare = { _, fixture ->
        fixture.omitResumeYolo = true
        fixture.sessions.getValue("chat-a").put("model_config", json("yolo_mode" to true).toString())
    }) { vm, _ ->
        await { vm.data.value["picker:approvals"]?.optBoolean("session_bypass") == true && vm.conversationPending.value.isEmpty() && "picker:approvals" !in vm.refreshing.value }
        compose.onNodeWithContentDescription("Approvals: Skip approvals").assertIsDisplayed()
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
        compose.onNodeWithContentDescription("Approvals: Manual").assertIsDisplayed()  // chat B keeps its own setting: not YOLO
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
        compose.onNodeWithTag("profile-approval-smart").performClick()
        await { fixture.approvalMode == "smart" && vm.data.value["profile:approvals"]?.string("value") == "smart" }
        assertTrue(fixture.sessions.getValue("chat-a").getJSONObject("info").getBoolean("yolo"))
        assertTrue(fixture.requests.filter { it.string("method") == "config.set" }.all { it.getJSONObject("params").string("key") == "approvals.mode" })
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

    @Test fun reasoningPickerExplainsExtraHighAndMax() = withFixture(
        content = { NativeCatalog(it) }, prepare = { vm, _ ->
            vm.storeData("picker:reasoning", json("value" to "xhigh", "options" to JSONArray(listOf("xhigh", "max"))))
            flow<String>(vm, "_catalog").value = "Reasoning"
        },
    ) { _, _ ->
        compose.onNodeWithText("Extra high", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Spend more time on difficult problems").assertIsDisplayed()
        compose.onNodeWithText("Use the model's maximum reasoning budget").assertIsDisplayed()
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
        compose.onNodeWithText("Browse chat commands").performScrollTo().assertIsDisplayed()
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
        compose.onNodeWithText("Test server").performScrollTo().performClick()
        await { vm.data.value["mcp-test:fixture_mcp"] != null }
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

    @Test fun archivedSnackbarOffersUndoForTheOriginalConversation() = withFixture(content = { vm ->
        val ui by vm.ui.collectAsState()
        val undo by vm.archiveUndo.collectAsState()
        val snackbar = remember { SnackbarHostState() }
        NativeFeedbackEffect(ui.error, ui.notice, false, snackbar, actionLabel = "Undo".takeIf { undo != null && ui.notice == "Archived" }, onAction = vm::undoArchive, clearMessage = vm::clearMessage)
        Column { SnackbarHost(snackbar) }
    }) { vm, fixture ->
        compose.runOnIdle { vm.conversationAction("chat-a", "/api/session/archive", json("archived" to true), "Archived") }
        await { vm.archiveUndo.value != null }
        compose.onNodeWithText("Archived").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        await { !fixture.sessions.getValue("chat-a").getJSONObject("info").optBoolean("hidden") && vm.archiveUndo.value == null }
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
