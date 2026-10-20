package dev.hermes.android

import androidx.compose.ui.platform.testTag
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

class ProactiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<CypherApplication>()
    private fun proactiveKey(fixture: NativeExperienceFixture) = proactiveSettingsKey(fixture.scope.copy(profile = "coder"))

    @Test fun assistantProfileAndProactiveUseHermesDisplayName() = withFixture { vm, fixture ->
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true) }
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("display_name") == "Code profile" } == true }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithText("Profile: Code profile").assertExists()
        compose.onNodeWithText("Proactive · Code profile").assertExists()
        compose.onNodeWithText("coder", substring = false).assertDoesNotExist()
    }

    @Test fun missingProactiveRouteExplainsPluginUpdateAndOtherErrorsKeepRetry() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        fixture.proactiveRouteMissing = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("_pluginUpdateRequired") == true }
        compose.onNodeWithText("Update the Cypher plugin on Hermes to use Proactive.").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()

        fixture.proactiveRouteMissing = false
        fixture.pluginStatus = 503
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.let { it.has("_error") && !it.optBoolean("_pluginUpdateRequired") } == true }
        compose.onNodeWithText("Proactive settings couldn’t be loaded.").assertExists()
        compose.onNodeWithText("Retry").assertExists()
    }

    @Test fun taskResultNotificationShowsFullReasonAndCanBeDismissed() = withFixture { vm, fixture ->
        val source = "task-result-source-test"
        val eventId = "task-result-event-test"
        val prefix = "Daily briefing: "
        val reason = prefix + "x".repeat(199 - prefix.length) + "😀" + " calendar follow-up item;".repeat(40) + "**FULL-REASON-END**"
        runBlocking { app.auth.persist(fixture.client.auth) }
        assertTrue(app.push.bindKeepConnectedSource(fixture.scope, source))
        fixture.incomingNotification = json(
            "seq" to 1,
            "event_id" to eventId,
            "owner_id" to fixture.scope.owner,
            "profile" to fixture.scope.profile,
            "kind" to "task_result",
            "state" to "finished",
            "source_ref" to source,
            "session_id" to "chat-a",
            "reason" to reason,
            "preview" to json("body" to reason.take(240) + "…"),
        )
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<CypherClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store ->
            fixture.freshClientFor(auth, store).also(pushClients::add)
        }
        try {
            vm.handlePush(source, eventId)
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            await { compose.onAllNodesWithText("FULL-REASON-END", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("FULL-REASON-END", substring = true).performScrollTo().assertExists()
            compose.onNodeWithTag("task-result-card").assertExists()
            compose.onNodeWithTag("task-result-feedback-more").performClick()
            await { fixture.requests.any { it.string("path") == "$PROACTIVE_SETTINGS_PATH/feedback" && it.string("method") == "POST" } }
            val feedback = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/feedback" }
            assertEquals("more", feedback.getJSONObject("body").string("verdict"))
            val topic = feedback.getJSONObject("body").string("topic")
            assertEquals(reason.substring(0, reason.offsetByCodePoints(0, 200)), topic)
            assertEquals(200, topic.codePointCount(0, topic.length))
            await { compose.onAllNodesWithTag("task-result-feedback-sent").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Got it").assertExists()
            compose.onAllNodesWithTag("task-result-feedback-more").assertCountEquals(0)
            compose.onAllNodesWithTag("task-result-feedback-less").assertCountEquals(0)
            compose.onNodeWithTag("task-result-dismiss").performClick()
            await { compose.onAllNodesWithTag("task-result-card").fetchSemanticsNodes().isEmpty() }
        } finally {
            vm.client?.closeNative()
            pushClients.forEach(CypherClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    @Test fun switchUsesAssistantProfileAndOnlySendsEnabled() = withFixture { vm, fixture ->
        fixture.proactiveSetupComplete["coder"] = true
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "GET" && it.string("profile") == "coder" } }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }

        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        await { fixture.proactiveEnabled["coder"] == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }
        val enabledWrite = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals("coder", enabledWrite.string("profile"))
        assertEquals(1, enabledWrite.getJSONObject("body").length())
        assertTrue(enabledWrite.getJSONObject("body").getBoolean("enabled"))

        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        await { fixture.proactiveEnabled["coder"] == false && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == false }
        val writes = fixture.requests.filter { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals(2, writes.size)
        assertEquals("coder", writes.last().string("profile"))
        assertEquals(1, writes.last().getJSONObject("body").length())
        assertFalse(writes.last().getJSONObject("body").getBoolean("enabled"))
    }

    @Test fun assistantPromptDismissesUntilItsStateChangesAndOpensAssistantSettings() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.ui.value.profile == "coder" && !vm.ui.value.cypherOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Let Cypher keep an eye on your day").fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithTag("proactive-prompt-dismiss").performClick()
        await { compose.onAllNodesWithText("Let Cypher keep an eye on your day").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.tab("Chat") }
        await { vm.ui.value.tab == "Chat" && vm.ui.value.profile == "default" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.ui.value.profile == "coder" && !vm.ui.value.cypherOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        compose.onAllNodesWithText("Let Cypher keep an eye on your day").assertCountEquals(0)

        fixture.proactiveSetupComplete["coder"] = true
        vm.load(proactiveKey(fixture), PROACTIVE_SETTINGS_PATH, mapOf("profile" to "coder"), force = true)
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("setup_complete") == true }
        compose.onNodeWithText("Let Cypher keep an eye on your day").assertExists()
        compose.onNodeWithTag("proactive-prompt-settings").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Assistant" }
        compose.onNodeWithTag("assistant-proactive-toggle").assertExists()
    }

    @Test fun firstRunSetupChoosesSourcesAndSavesTheFullSetup() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.runOnIdle { vm.setAssistantModel("fixture-model", "fixture-provider") }
        await { vm.preferences.value.assistantModel == "fixture-model" && vm.preferences.value.assistantProvider == "fixture-provider" }

        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-setup-title").assertTextEquals("What should I watch?")
        compose.onNodeWithText("Email: Don’t watch").performClick()
        compose.onNodeWithTag("choice-option:Email:skill:himalaya").performClick()
        compose.onNodeWithTag("proactive-source-caution-email").assertExists()
        compose.onNodeWithText("Email: himalaya").performClick()
        compose.onNodeWithTag("choice-option:Email:mcp:mail").performClick()
        compose.onNodeWithText("Calendar: Calendar server").performClick()
        compose.onNodeWithTag("choice-option:Calendar:mcp:calendar").performClick()
        compose.onNodeWithText("Tasks: Don’t watch").performClick()
        compose.onNodeWithTag("choice-option:Tasks:skill:notion").performClick()
        compose.onNodeWithText("News: Web").performClick()
        compose.onNodeWithTag("choice-option:News:builtin:web").performClick()
        compose.onNodeWithTag("proactive-setup-next").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-add-request-setup").performScrollTo().performClick()
        await { vm.form.value?.title == "Add a request" }
        compose.runOnIdle {
            vm.formValue("text", "Call me when project deadlines change")
            vm.formValue("action", "call")
            vm.formValue("signal", "tasks")
            vm.formValue("night", "true")
        }
        compose.onNodeWithText("Add request", useUnmergedTree = true).performClick()
        await { fixture.proactiveRequests["coder"]?.size == 1 && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.size == 1 }
        val requestPost = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/requests" && it.string("method") == "POST" }
        assertEquals("coder", requestPost.string("profile"))
        assertEquals("Call me when project deadlines change", requestPost.getJSONObject("body").string("text"))
        assertEquals("call", requestPost.getJSONObject("body").string("action"))
        assertEquals("tasks", requestPost.getJSONObject("body").string("signal"))
        assertTrue(requestPost.getJSONObject("body").getBoolean("night"))
        await { vm.data.value[proactiveKey(fixture)]?.rows("requests")?.size == 1 }

        compose.onNodeWithTag("proactive-brief-time").performTextReplacement("09:15")
        compose.onNodeWithText("Wrap-up: Set time").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Wrap-up:off").performClick()
        compose.onNodeWithText("Watch frequency: 30 min").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Watch frequency:5").performClick()
        compose.onNodeWithTag("proactive-five-minute-cost-note").assertExists()
        compose.onNodeWithText("Watch frequency: 5 min").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Watch frequency:15").performClick()
        compose.onNodeWithTag("proactive-five-minute-cost-note").assertDoesNotExist()
        compose.onNodeWithText("Watch frequency: 15 min").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Watch frequency:60").performClick()
        compose.onNodeWithTag("proactive-quiet-start").performScrollTo().performTextReplacement("21:30")
        compose.onNodeWithTag("proactive-quiet-end").performScrollTo().performTextReplacement("06:30")
        compose.onNodeWithText("How much should I interrupt you?: Useful").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:How much should I interrupt you?:urgent").performClick()
        compose.onNodeWithTag("proactive-setup-next").performScrollTo().performClick()
        compose.onNodeWithText("The result will arrive as a notification.", substring = true).assertExists()
        compose.onNodeWithTag("proactive-turn-on").performScrollTo().performClick()

        await { fixture.proactiveEnabled["coder"] == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("setup_complete") == true }
        val setupWrite = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" && it.getJSONObject("body").has("signals") }
        val body = setupWrite.getJSONObject("body")
        assertEquals("coder", setupWrite.string("profile"))
        assertEquals(7, body.length())
        val signals = body.getJSONObject("signals")
        assertEquals("mcp:mail", signals.string("email"))
        assertEquals("mcp:calendar", signals.string("calendar"))
        assertEquals("skill:notion", signals.string("tasks"))
        assertEquals("builtin:web", signals.string("news"))
        val schedule = body.getJSONObject("schedule")
        assertEquals("09:15", schedule.string("brief"))
        assertTrue(schedule.isNull("wrap_up"))
        assertEquals(60, schedule.getInt("watch_minutes"))
        assertEquals("21:30", schedule.string("quiet_start"))
        assertEquals("06:30", schedule.string("quiet_end"))
        assertEquals("urgent", body.string("level"))
        assertEquals("notify", body.getJSONObject("urgent").string("delivery"))
        assertEquals("fixture-model", body.string("model"))
        assertEquals("fixture-provider", body.string("provider"))
        compose.onNodeWithTag("proactive-health-email").performScrollTo().assertTextContains("Checked", substring = true)
        compose.onNodeWithText("Call me when project deadlines change").performScrollTo().assertExists()
    }

    @Test fun noSourceSignalShowsNotConnectedHint() = withFixture { vm, fixture ->
        fixture.proactiveSignals = fixture.proactiveSignals.map { signal ->
            if (signal.string("id") == "news") signal.copy().put("sources", org.json.JSONArray()) else signal
        }
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-no-source-news").assertTextEquals("Not connected")
        compose.onNodeWithText("Connect it on your computer").assertExists()
    }

    @Test fun previewSavesSetupThenPostsWithoutTurningProactiveOn() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-setup-next").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-setup-next").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-run-brief").performScrollTo().performClick()
        await { fixture.proactivePreviews.contains("coder") }
        assertFalse(fixture.proactiveEnabled["coder"] == true)
        assertTrue(fixture.proactiveSetupComplete["coder"] == true)
        val requests = fixture.requests.filter { it.string("path").startsWith(PROACTIVE_SETTINGS_PATH) }
        val saveIndex = requests.indexOfLast { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        val previewIndex = requests.indexOfLast { it.string("path") == "$PROACTIVE_SETTINGS_PATH/preview" && it.string("method") == "POST" }
        assertTrue(saveIndex >= 0 && previewIndex > saveIndex)
        assertFalse(requests[saveIndex].getJSONObject("body").getBoolean("enabled"))
        assertEquals(0, requests[previewIndex].getJSONObject("body").length())
        compose.onNodeWithTag("proactive-setup-close").performClick()
    }

    @Test fun configuredSettingsShowHealthAndCanRunPreview() = withFixture { vm, fixture ->
        fixture.proactiveSetupComplete["coder"] = true
        fixture.proactiveSignals = fixture.proactiveSignals.map { signal ->
            if (signal.string("id") == "calendar") signal.copy().put("health", json("state" to "error", "checked_at" to 1792400000, "message" to "Calendar connection expired")) else signal
        }
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("setup_complete") == true }
        compose.onNodeWithTag("proactive-health-email").performScrollTo().assertTextContains("Checked", substring = true)
        compose.onNodeWithTag("proactive-health-calendar").performScrollTo().assertTextContains("Reconnect", substring = true)
        compose.onNodeWithText("For urgent things: Notify me").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:For urgent things:call").performClick()
        compose.onNodeWithText("Email: Don’t watch").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Email:__none__").performClick()
        compose.onNodeWithText("Watch frequency: 30 min").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Watch frequency:60").performClick()
        compose.onNodeWithTag("proactive-save-setup").performScrollTo().performClick()
        await { fixture.proactiveSchedule.optInt("watch_minutes") == 60 && fixture.proactiveSelections["coder"]?.isNull("email") == true }
        val saved = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" && it.getJSONObject("body").has("signals") }.getJSONObject("body")
        assertFalse(saved.getBoolean("enabled"))
        assertEquals(60, saved.getJSONObject("schedule").getInt("watch_minutes"))
        assertTrue(saved.getJSONObject("signals").isNull("email"))
        assertEquals("call", saved.getJSONObject("urgent").string("delivery"))
        assertEquals("call", fixture.proactiveUrgentDelivery)

        compose.onNodeWithTag("proactive-add-request-settings").performScrollTo().performClick()
        await { vm.form.value?.title == "Add a request" }
        compose.runOnIdle {
            vm.formValue("text", "Call when the calendar has a conflict")
            vm.formValue("action", "notify")
            vm.formValue("signal", "calendar")
        }
        compose.onNodeWithText("Add request", useUnmergedTree = true).performClick()
        await { fixture.proactiveRequests["coder"]?.size == 1 && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.size == 1 }
        val requestId = fixture.proactiveRequests.getValue("coder").single().string("id")
        compose.onNodeWithTag("proactive-request-text-$requestId").performScrollTo().assertTextContains("calendar has a conflict", substring = true)
        compose.onNodeWithText("Delivery: Notify").performScrollTo().performClick()
        compose.onNodeWithTag("choice-option:Delivery:call").performClick()
        await { fixture.proactiveRequests.getValue("coder").single().string("action") == "call" && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.singleOrNull()?.string("action") == "call" }
        compose.onNodeWithTag("proactive-request-night-$requestId").performScrollTo().performClick()
        await { fixture.proactiveRequests.getValue("coder").single().optBoolean("night") && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.singleOrNull()?.optBoolean("night") == true }
        compose.onNodeWithTag("proactive-request-enabled-$requestId").performScrollTo().performClick()
        await { !fixture.proactiveRequests.getValue("coder").single().optBoolean("enabled") && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.singleOrNull()?.optBoolean("enabled") == false }
        compose.onNodeWithTag("proactive-request-delete-$requestId").performScrollTo().performClick()
        await { fixture.proactiveRequests.getValue("coder").isEmpty() && vm.data.value[proactiveKey(fixture)]?.rows("requests")?.isEmpty() == true }
        assertTrue(fixture.requests.any { it.string("method") == "PATCH" && it.string("path").endsWith(requestId) })
        assertTrue(fixture.requests.any { it.string("method") == "DELETE" && it.string("path").endsWith(requestId) })
        assertTrue(fixture.requests.filter { it.string("path").contains("/proactive/requests/") }.all { it.string("profile") == "coder" })
        compose.onNodeWithTag("proactive-run-brief").performScrollTo().performClick()
        await { fixture.proactivePreviews.contains("coder") }
        compose.onNodeWithTag("proactive-change-setup").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-setup-title").assertTextEquals("What should I watch?")
        compose.onNodeWithTag("proactive-setup-close").performClick()
    }

    @Test fun chatPromptOpensFirstRunSetupAndClosingLeavesItOff() = withFixture { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.ui.value.profile == "coder" && !vm.ui.value.cypherOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithTag("proactive-prompt-settings").performClick()
        compose.onNodeWithTag("proactive-setup-title").assertTextEquals("What should I watch?")
        compose.onNodeWithTag("proactive-setup-close").performClick()
        assertFalse(fixture.proactiveEnabled["coder"] == true)
        assertFalse(fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" })
    }

    @Test fun proactiveControlsAndPromptStayHiddenWhenPluginIsAbsent() = withFixture(pluginStatus = "absent") { vm, fixture ->
        await { vm.preferences.value.assistantProfile == "coder" }
        compose.runOnIdle { vm.more("Assistant") }
        await { compose.onAllNodesWithText("Proactive").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { vm.tab("Cypher") }
        await { vm.ui.value.tab == "Cypher" && vm.ui.value.profile == "coder" && !vm.ui.value.cypherOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.startsWith("new-") }
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
        // Motion follows the phone's "Remove animations" setting; still frames let Compose go idle (as in NativeExperienceUiTest).
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
