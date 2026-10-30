package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.MoreScreen
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SidebarNavigation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Synthetic native responses with the real Settings controls and ViewModel. */
class SettingsProfileIsolationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(block: (BeaconViewModel, NativeExperienceFixture, androidx.compose.runtime.MutableState<Boolean>, androidx.compose.runtime.MutableState<BeaconViewModel>) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        val fixture = NativeExperienceFixture().apply { profileIsolationBots = true; notificationProfiles = listOf("default", "coder", "academic") }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        runBlocking {
            app.auth.persist(fixture.client.auth)
            fixture.notificationProfiles.forEach { profile -> app.cache.delete(fixture.scope.copy(profile = profile), "phone-editors") }
        }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "profileSwitchClientFactoryForTest", { auth: dev.hermes.android.data.AuthContext -> fixture.freshClientFor(auth, app.auth) })
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, authenticated = true, owner = fixture.scope.owner,
                    profile = "default", connected = true, restoringSession = false)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Existing chat", raw = json("model" to "chat-model"))
                vm.composer(TextFieldValue("Unsent chat draft"))
                vm.storeData("picker:approvals", json("scope_key" to fixture.scope.key, "session_id" to "chat-a", "profile_mode" to "manual"))
                vm.more("Profiles")
            }
            val navigation = mutableStateOf(false)
            val rendered = mutableStateOf(vm)
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { if (navigation.value) SidebarNavigation(rendered.value) else MoreScreen(rendered.value) } } }
            block(vm, fixture, navigation, rendered)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking {
                fixture.releaseMemoryRead.countDown()
                fixture.notificationProfiles.forEach { profile -> app.cache.delete(fixture.scope.copy(profile = profile), "phone-editors") }
                app.auth.remove(fixture.scope.origin); app.preferences.updateData { prior }
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun scrollTo(matcher: SemanticsMatcher) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
        compose.onNode(SemanticsMatcher("Settings detail list") { it.id == list.id }).performScrollToNode(matcher)
    }
    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun openingAnotherProfilesSkillsKeepsTheChatProfileAndConversation() = withFixture { vm, fixture, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.any { it.profile == "coder" } && !vm.phoneSettings.bots.value.loading }
        compose.runOnIdle { vm.phoneSettings.openBot("coder") }
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Skills for this profile"))
        compose.onNodeWithText("Skills for this profile", useUnmergedTree = true).performClick()
        await { vm.ui.value.morePage == "Skills" || vm.ui.value.profile == "coder" }

        assertEquals("default", vm.ui.value.profile)
        assertSame(fixture.client, vm.client)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertEquals("chat-model", vm.chat.value.raw.string("model"))
        assertEquals("Unsent chat draft", vm.composer.value.text)
        assertEquals("manual", vm.data.value["picker:approvals"]?.string("profile_mode"))
    }

    @Test fun twoInspectedProfilesKeepTheirSettingsAndLeaveTheChatUntouched() = withFixture { vm, fixture, navigation, _ ->
        val assistantProfile = vm.preferences.value.assistantProfile
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        for ((profile, approval) in listOf("coder" to "manual", "academic" to "smart")) {
            scrollTo(hasTestTag("profile-open:$profile"))
            compose.onNodeWithTag("profile-open:$profile").performClick()
            await { vm.phoneSettings.bots.value.selected?.profile == profile && !vm.phoneSettings.bots.value.loading }
            assertEquals("$profile-model", vm.phoneSettings.bots.value.selected?.model)
            await { vm.data.value["profile:approvals"]?.string("scope_key") == fixture.scope.copy(profile = profile).key }
            compose.onNodeWithTag("profile-approval-$approval").performClick()
            await { fixture.isolationApprovals[profile] == approval }
            compose.runOnIdle { vm.phoneSettings.editBot(true); vm.phoneSettings.botDraft("fixture", "$profile-updated"); vm.phoneSettings.saveBot() }
            await { fixture.isolationModels[profile] == "$profile-updated" && !vm.phoneSettings.bots.value.saving }

            scrollTo(hasText("Skills for this profile"))
            compose.onNodeWithText("Skills for this profile", useUnmergedTree = true).performClick()
            await { vm.ui.value.morePage == "Skills" && vm.phoneSettings.skills.value.skills.isNotEmpty() && !vm.phoneSettings.skills.value.loading }
            val skill = vm.phoneSettings.skills.value.skills.single()
            compose.runOnIdle { vm.phoneSettings.openSkill(skill) }
            await { vm.phoneSettings.skills.value.content != null && !vm.phoneSettings.skills.value.loading }
            assertEquals("${profile.replaceFirstChar(Char::uppercase)} instructions", vm.phoneSettings.skills.value.content?.content)
            compose.runOnIdle { vm.phoneSettings.skillEdit(true); vm.phoneSettings.skillDraft("$profile saved skill"); vm.phoneSettings.saveSkill() }
            await { fixture.isolationSkills[profile] == "$profile saved skill" && !vm.phoneSettings.skills.value.saving }
            back(); await { vm.phoneSettings.skills.value.selected == null }
            back(); await { vm.ui.value.morePage == "Profiles" && vm.phoneSettings.bots.value.selected?.profile == profile && !vm.phoneSettings.bots.value.loading }

            scrollTo(hasText("Memory for this profile"))
            compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
            await { vm.ui.value.morePage == "Memory" && vm.phoneSettings.memory.value.documents.isNotEmpty() && !vm.phoneSettings.memory.value.loading }
            assertEquals("$profile notes", vm.phoneSettings.memory.value.documents.first { it.target == "memory" }.content)
            compose.runOnIdle { vm.phoneSettings.editMemory("memory"); vm.phoneSettings.memoryDraft("$profile saved notes"); vm.phoneSettings.saveMemory() }
            await { fixture.isolationMemory.getValue(profile)["memory"] == "$profile saved notes" && !vm.phoneSettings.memory.value.saving }
            back(); await { vm.ui.value.morePage == "Profiles" && vm.phoneSettings.bots.value.selected?.profile == profile && !vm.phoneSettings.bots.value.loading }
            back(); await { vm.phoneSettings.bots.value.selected == null && !vm.settingsProfileSelecting.value }
        }
        assertEquals("default", vm.ui.value.profile)
        assertSame(fixture.client, vm.client)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertEquals("chat-model", vm.chat.value.raw.string("model"))
        assertEquals("Unsent chat draft", vm.composer.value.text)
        assertEquals("manual", vm.data.value["picker:approvals"]?.string("profile_mode"))
        assertEquals(assistantProfile, vm.preferences.value.assistantProfile)
        assertEquals("default", runBlocking { app.auth.read(fixture.scope.origin).profile })
        for (path in listOf("/api/plugins/beacon/admin/bot", "/api/plugins/beacon/mobile/skills/content", "/api/plugins/beacon/memory/documents")) {
            assertEquals(setOf("coder", "academic"), fixture.requests.filter { it.string("method") == "PUT" && it.string("path") == path }.map { it.string("profile") }.toSet())
        }
        assertEquals(setOf("coder", "academic"), fixture.requests.filter { it.string("method") == "config.set" && it.optJSONObject("params")?.string("key") == "approvals.mode" }.map { it.getJSONObject("params").string("profile") }.toSet())

        back(); await { vm.ui.value.tab == "Chat" }
        compose.runOnIdle { navigation.value = true }
        compose.onNodeWithTag("navigation-profile").performClick()
        await { compose.onAllNodes(hasText("coder") and hasAnyAncestor(isPopup())).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("coder") and hasAnyAncestor(isPopup())).performClick()
        await { vm.ui.value.profile == "coder" }
        assertTrue(vm.client?.scope?.profile == "coder")
    }

    @Test fun lateMemoryForPreviousProfileDoesNotReplaceTheCurrentProfile() = withFixture { vm, fixture, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        fixture.holdMemoryProfile = "coder"
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { fixture.memoryReadCaptured.count == 0L }
        back(); await { vm.ui.value.morePage == "Profiles" && vm.phoneSettings.bots.value.selected?.profile == "coder" }
        back(); await { vm.phoneSettings.bots.value.selected == null && !vm.settingsProfileSelecting.value }
        scrollTo(hasTestTag("profile-open:academic")); compose.onNodeWithTag("profile-open:academic").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "academic" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { vm.phoneSettings.memory.value.documents.any { it.content == "academic notes" } && !vm.phoneSettings.memory.value.loading }
        fixture.releaseMemoryRead.countDown()
        await { fixture.memoryReadReturned.count == 0L }
        compose.waitForIdle()
        assertEquals(fixture.scope.copy(profile = "academic").key, vm.phoneSettings.memory.value.scope)
        assertEquals("academic notes", vm.phoneSettings.memory.value.documents.first { it.target == "memory" }.content)
        assertEquals("default", vm.ui.value.profile)
        assertEquals("chat-a", vm.chat.value.sessionId)
    }

    @Test fun deletedOrUnauthorizedProfileCannotBeEdited() = withFixture { vm, fixture, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        fixture.notificationProfiles = listOf("default", "academic")
        compose.runOnIdle { vm.clearMessage() }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.ui.value.notice.isNotBlank() && !vm.settingsProfileSelecting.value }
        assertEquals(null, vm.phoneSettings.bots.value.selected)
        assertEquals("default", vm.ui.value.profile)
        fixture.notificationProfiles = listOf("default", "coder", "academic")
        fixture.rejectedProfiles += "coder"
        compose.runOnIdle { vm.clearMessage() }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { !vm.phoneSettings.bots.value.loading && vm.phoneSettings.bots.value.error.isNotBlank() }
        assertEquals(null, vm.phoneSettings.bots.value.selected)
        assertTrue(fixture.requests.none { it.string("method") == "PUT" && it.string("profile") == "coder" })
        assertEquals("chat-a", vm.chat.value.sessionId)
    }

    @Test fun olderServerWithoutProfileCatalogStillOpensTheActiveProfilesMemory() = withFixture { vm, fixture, _, _ ->
        fixture.profileCatalogUnavailable = true
        compose.runOnIdle { vm.more("Memory") }
        await { vm.phoneSettings.memory.value.documents.any { it.content == "default notes" } && !vm.phoneSettings.memory.value.loading }
        assertEquals("default", vm.ui.value.profile)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertTrue(fixture.requests.any { it.string("path") == "/api/plugins/beacon/memory/documents" && it.string("profile") == "default" })
    }

    @Test fun cancellingAnEditAndBackPreserveTheInspectedProfileAndChat() = withFixture { vm, _, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { vm.phoneSettings.memory.value.documents.any { it.content == "coder notes" } && !vm.phoneSettings.memory.value.loading }
        compose.runOnIdle { vm.phoneSettings.editMemory("memory"); vm.phoneSettings.memoryDraft("recoverable coder draft") }
        back(); compose.onNodeWithText("Keep editing").performClick()
        assertEquals("recoverable coder draft", vm.phoneSettings.memory.value.draft)
        back(); compose.onNodeWithText("Discard changes").performClick()
        await { vm.phoneSettings.memory.value.editing.isBlank() }
        back(); await { vm.ui.value.morePage == "Profiles" && vm.phoneSettings.bots.value.selected?.profile == "coder" }
        assertEquals("default", vm.ui.value.profile)
        assertEquals("chat-a", vm.chat.value.sessionId)
        assertEquals("Unsent chat draft", vm.composer.value.text)
    }

    @Test fun signOutRetainsTheInspectedProfilesDraftAndClearsItsAuthority() = withFixture { vm, fixture, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { vm.phoneSettings.memory.value.documents.any { it.content == "coder notes" } && !vm.phoneSettings.memory.value.loading }
        compose.runOnIdle { vm.phoneSettings.editMemory("memory"); vm.phoneSettings.memoryDraft("draft before sign-out"); vm.logout() }
        await { !vm.ui.value.authenticated && vm.client == null }
        val saved = org.json.JSONObject(requireNotNull(runBlocking { app.cache.read(fixture.scope.copy(profile = "coder"), "phone-editors") }))
        assertEquals(fixture.scope.copy(profile = "coder").key, saved.getString("scope"))
        assertEquals("coder notes", saved.getJSONObject("memory").getString("base"))
        assertEquals("draft before sign-out", saved.getJSONObject("memory").getString("draft"))
        assertEquals("", vm.settingsProfile.value)
    }

    @Test fun recreationReauthorizesTheInspectedProfileAndRestoresItsDraft() = withFixture { vm, fixture, _, rendered ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { vm.phoneSettings.memory.value.documents.any { it.content == "coder notes" } && !vm.phoneSettings.memory.value.loading }
        compose.runOnIdle { vm.phoneSettings.editMemory("memory"); vm.phoneSettings.memoryDraft("recreated coder draft") }
        val coder = fixture.scope.copy(profile = "coder")
        await { runBlocking { app.cache.read(coder, "phone-editors") } != null }
        val original = BeaconViewModel::class.java.getDeclaredField("saved").apply { isAccessible = true }.get(vm) as SavedStateHandle
        val restoredHandle = SavedStateHandle(original.keys().associateWith { original.get<Any?>(it) })
        val recreatedStore = ViewModelStore()
        try {
            lateinit var recreated: BeaconViewModel
            compose.runOnUiThread { recreated = ViewModelProvider(recreatedStore, viewModelFactory { initializer { BeaconViewModel(app, restoredHandle) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(recreated, "originGeneration", 100); field(recreated, "client", fixture.client)
                field(recreated, "sourceScope", fixture.scope.session("chat-a"))
                flow<AppState>(recreated, "_ui").value = vm.ui.value.copy(tab = "More", morePage = "Memory")
                flow<ChatState>(recreated, "_chat").value = vm.chat.value
                rendered.value = recreated
            }
            await { !recreated.settingsProfileRestoring.value && recreated.settingsProfile.value == "coder" && recreated.phoneSettings.memory.value.draft == "recreated coder draft" }
            assertEquals("coder notes", recreated.phoneSettings.memory.value.documents.single().content)
            assertEquals("a".repeat(64), recreated.phoneSettings.memory.value.documents.single().revision)
            assertEquals("default", recreated.ui.value.profile)
            assertEquals("chat-a", recreated.chat.value.sessionId)
            back(); compose.onNodeWithText("Keep editing").performClick()
            assertEquals("recreated coder draft", recreated.phoneSettings.memory.value.draft)
        } finally { compose.runOnUiThread { recreatedStore.clear() } }
    }

    @Test fun reconnectReauthorizesTheInspectedProfileAndKeepsItsDraft() = withFixture { vm, fixture, _, _ ->
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasTestTag("profile-open:coder")); compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo(hasText("Memory for this profile")); compose.onNodeWithText("Memory for this profile", useUnmergedTree = true).performClick()
        await { vm.phoneSettings.memory.value.documents.any { it.content == "coder notes" } && !vm.phoneSettings.memory.value.loading }
        val saved = BeaconViewModel::class.java.getDeclaredField("saved").apply { isAccessible = true }.get(vm) as SavedStateHandle
        val originalClient = vm.client
        compose.runOnIdle {
            saved["session:${fixture.scope.key}"] = "chat-a"
            vm.phoneSettings.editMemory("memory"); vm.phoneSettings.memoryDraft("draft across reconnect")
            vm.connect(fixture.scope.origin)
        }
        await { vm.client != null && vm.client !== originalClient && vm.ui.value.connected && !vm.settingsProfileRestoring.value && vm.settingsProfile.value == "coder" && vm.phoneSettings.memory.value.draft == "draft across reconnect" }
        assertEquals("default", vm.ui.value.profile)
        assertEquals(fixture.scope.copy(profile = "coder").key, vm.phoneSettings.memory.value.scope)
        assertEquals("coder notes", vm.phoneSettings.memory.value.documents.first { it.target == "memory" }.content)
    }
}
