package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.CypherClient
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ProactiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
    private fun proactiveKey(fixture: NativeExperienceFixture) = proactiveSettingsKey(fixture.scope.copy(profile = "coder"))

    @Test fun importantEmailToggleEnablesImmediatelyWithTheEmailOnlyDefaults() = withFixture { vm, fixture ->
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true); vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithText("Profile: Code profile").assertExists()
        compose.onNodeWithText("coder", substring = false).assertDoesNotExist()

        compose.onNodeWithText("Important emails · Code profile").assertExists()
        compose.onNodeWithText("Cypher checks your email and tells you when something matters. It learns from you.").assertExists()
        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        await { fixture.proactiveEnabled["coder"] == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }

        assertEquals("notify", fixture.proactiveDelivery["coder"])
        assertEquals(15, fixture.proactiveCheckMinutes["coder"])
        assertEquals("22:00", fixture.proactiveQuietStart["coder"])
        assertEquals("07:00", fixture.proactiveQuietEnd["coder"])
        val write = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals("coder", write.string("profile"))
        assertEquals(setOf("enabled"), write.getJSONObject("body").keys().asSequence().toSet())
        assertTrue(write.getJSONObject("body").getBoolean("enabled"))
        compose.onNodeWithText("When one arrives").assertExists()
        compose.onNodeWithText("Delivery: Notify").assertExists()
        compose.onNodeWithText("Check every: 15 min").assertExists()
        compose.onNodeWithTag("proactive-time-Quiet hours start").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-time-confirm-Quiet hours start").assertExists()
        compose.onNodeWithText("Run a brief now").assertDoesNotExist()
        compose.onNodeWithText("What Cypher learned").assertExists()
    }

    @Test fun v4SettingsSaveRulesAndKeepEveryRequestOnTheAssistantProfile() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }

        compose.onNodeWithText("Delivery: Notify").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Delivery:call").performClick()
        compose.onNodeWithText("Check every: 15 min").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Check every:5").performClick()
        compose.onNodeWithTag("proactive-save-settings").performScrollTo().performClick()
        await { fixture.proactiveDelivery["coder"] == "call" && fixture.proactiveCheckMinutes["coder"] == 5 }
        val settingsWrite = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals("coder", settingsWrite.string("profile"))
        assertEquals("call", settingsWrite.getJSONObject("body").string("delivery"))
        assertEquals(5, settingsWrite.getJSONObject("body").getInt("check_minutes"))
        assertEquals("22:00", settingsWrite.getJSONObject("body").string("quiet_start"))
        assertEquals("07:00", settingsWrite.getJSONObject("body").string("quiet_end"))

        compose.onNodeWithTag("proactive-add-rule").performScrollTo().performClick()
        await { vm.form.value?.title == "Add a rule" }
        compose.runOnIdle {
            vm.formValue("text", "Call when the client moves a review")
            vm.formValue("action", "call")
            vm.formValue("night", "true")
        }
        compose.onNodeWithText("Add rule", useUnmergedTree = true).performClick()
        await { fixture.proactiveRequests["coder"]?.size == 1 && vm.data.value[proactiveKey(fixture)]?.rows("rules")?.size == 1 }
        val create = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/requests" && it.string("method") == "POST" }
        assertEquals("coder", create.string("profile"))
        assertEquals("email", create.getJSONObject("body").string("signal"))
        assertTrue(create.getJSONObject("body").getBoolean("night"))
        val ruleId = fixture.proactiveRequests.getValue("coder").single().string("id")
        compose.onNodeWithTag("proactive-rule-text-$ruleId").performScrollTo().assertTextContains("client moves a review", substring = true)
        compose.onNodeWithTag("proactive-rule-enabled-$ruleId").performScrollTo().performClick()
        await { !fixture.proactiveRequests.getValue("coder").single().optBoolean("enabled") }
        compose.onNodeWithTag("proactive-rule-delete-$ruleId").performScrollTo().performClick()
        await { fixture.proactiveRequests.getValue("coder").isEmpty() && vm.data.value[proactiveKey(fixture)]?.rows("rules")?.isEmpty() == true }
        assertTrue(fixture.requests.filter { it.string("path").contains("/proactive/") }.all { it.string("profile") == "coder" })
    }

    @Test fun learnedEmailNotesCanBeRemovedAndShowTheTeachingHintWhenEmpty() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveLearned["coder"] = CopyOnWriteArrayList(listOf("Client moved the review"))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 1 }

        compose.onNodeWithText("What Cypher learned").performScrollTo().assertExists()
        compose.onNodeWithText("Client moved the review").performScrollTo().assertExists()
        compose.onNodeWithTag("proactive-learned-remove-0").performScrollTo().performClick()
        await { fixture.proactiveLearned["coder"]?.isEmpty() == true && vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 0 }
        val removal = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/learned" && it.string("method") == "DELETE" }
        assertEquals("coder", removal.string("profile"))
        assertEquals("Client moved the review", removal.getJSONObject("body").string("note"))
        compose.onNodeWithText("Mark alerts as Important or Not important to teach Cypher.").assertExists()
    }

    @Test fun missingPluginShowsUpdateHelpAndOtherFailuresShowDetailAndRetry() = withFixture { vm, fixture ->
        fixture.proactiveRouteMissing = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("_pluginUpdateRequired") == true }
        compose.onNodeWithText("Update the Cypher plugin on Hermes to use Proactive.").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()

        fixture.proactiveRouteMissing = false
        fixture.proactiveStatusCode = 503
        compose.runOnIdle { vm.setAssistantProfile("default") }
        await { vm.preferences.value.assistantProfile == "default" }
        compose.runOnIdle { vm.setAssistantProfile("coder") }
        await { vm.data.value[proactiveKey(fixture)]?.string("_message") == "Email access needs attention" }
        compose.onNodeWithText("Retry").assertExists()
        compose.onNodeWithText("Email access needs attention").assertExists()
    }

    @Test fun chatEmailPromptOpensSettingsWithoutStartingASetupSheet() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.ui.value.profile == "coder" && vm.chat.value.projectionReady }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithText("Let Cypher flag important emails").assertExists()
        compose.onNodeWithTag("proactive-prompt-settings").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Assistant" }
        // The profile label depends on cached display names; this test only checks it lands on the email settings.
        await { compose.onAllNodesWithText("Important emails · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(fixture.proactiveEnabled["coder"] == true)
    }

    @Test fun emailAlertCardPostsOnlyItsNoticeIdAndV4Verdict() = withFixture { vm, fixture ->
        val source = "email-alert-source-test"
        val noticeId = "important-email-notice-test"
        val reason = "Daniel Reyes moved the client review to Friday."
        runBlocking { app.auth.persist(fixture.client.auth) }
        assertTrue(app.push.bindKeepConnectedSource(fixture.scope, source))
        fixture.incomingNotification = json(
            "seq" to 1,
            "event_id" to noticeId,
            "owner_id" to fixture.scope.owner,
            "profile" to fixture.scope.profile,
            "kind" to "task_result",
            "state" to "finished",
            "source_ref" to source,
            "conversation_ref" to "chat-a",
            "session_id" to "chat-a",
            "reason" to reason,
            "email_alert" to true,
            "preview" to json("title" to "Important email", "body" to reason),
        )
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<CypherClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            vm.handlePush(source, noticeId)
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            // The chat was already open, so wait for the notification's card itself.
            await { compose.onAllNodesWithText("Important email").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(reason).assertExists()
            compose.onNodeWithTag("task-result-feedback-important").assertExists()
            compose.onNodeWithTag("task-result-feedback-not-important").performClick()
            await { fixture.proactiveFeedbacks.isNotEmpty() }
            val request = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/feedback" }
            assertEquals(fixture.scope.profile, request.string("profile"))
            assertEquals(setOf("notice_id", "verdict"), request.getJSONObject("body").keys().asSequence().toSet())
            assertEquals(noticeId, request.getJSONObject("body").string("notice_id"))
            assertEquals("not_important", request.getJSONObject("body").string("verdict"))
            await { compose.onAllNodesWithText("Thanks — Cypher will remember.").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            vm.client?.closeNative()
            pushClients.forEach(CypherClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    @Test fun proactiveControlsStayHiddenWhenOptionalPluginIsAbsent() = withFixture(pluginStatus = "absent") { vm, fixture ->
        compose.runOnIdle { vm.more("Assistant") }
        await { compose.onAllNodesWithText("Important emails", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.chat.value.projectionReady }
        compose.onAllNodesWithTag("proactive-prompt-card").assertCountEquals(0)
        assertFalse(fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH })
    }

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(pluginStatus: String = "available", block: (CypherViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val shell = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shellRun(command: String): String = shell.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val priorAnimatorScale = shellRun("settings get global animator_duration_scale")
        shellRun("settings put global animator_duration_scale 0")
        val prior = runBlocking { app.preferences.data.first() }
        val dismissalKey = stringPreferencesKey("proactive_prompt_dismissed")
        runBlocking {
            app.preferences.edit {
                it[stringPreferencesKey("origin")] = ""
                it[stringPreferencesKey("assistant_name")] = "Cypher"
                it[stringPreferencesKey("assistant_profile")] = "coder"
                it.remove(dismissalKey)
            }
        }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture().apply { bootstrapPluginStatus = pluginStatus; if (pluginStatus == "absent") this.pluginStatus = 404 }
        val profileScopes = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        runBlocking {
            app.auth.remove(fixture.scope.origin)
            profileScopes.forEach { owner ->
                listOf("cypher-session", "cypher-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                (1..4).forEach { n -> listOf("pending", "draft", "session", "client-created-shell").forEach { app.cache.delete(owner.session("new-$n"), it) } }
            }
        }
        lateinit var vm: CypherViewModel
        val profileSwitchClientFactory: (AuthContext) -> CypherClient = { auth -> fixture.freshClientFor(auth, app.auth) }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "profileSwitchClientFactoryForTest", profileSwitchClientFactory)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(
                    origin = fixture.scope.origin,
                    authenticated = true,
                    owner = fixture.scope.owner,
                    connected = true,
                    restoringSession = false,
                    bootstrap = json("voice" to json("available" to false), "optional_features" to json("plugin" to pluginStatus)),
                    sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) },
                )
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(
                    sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") },
                )
                vm.storeData("profile-settings", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter"))
            }
            compose.setContent {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                CompositionLocalProvider(
                    LocalCypherSettings provides (settings["profile-settings"] ?: JSONObject()),
                    LocalAssistantName provides prefs.assistantName,
                ) {
                    NativeLocalization("en") {
                        CypherTheme(dark = true) {
                            NativeWindowLayout(compose.activity) {
                                Box(Modifier.fillMaxSize().testTag("proactive-test-viewport")) { CypherRoot(vm) }
                            }
                        }
                    }
                }
            }
            block(vm, fixture)
        } finally {
            shellRun(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                profileScopes.forEach { owner ->
                    listOf("cypher-session", "cypher-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                    (1..4).forEach { n -> listOf("pending", "draft", "session", "client-created-shell").forEach { app.cache.delete(owner.session("new-$n"), it) } }
                }
                app.preferences.updateData { prior }
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
}
