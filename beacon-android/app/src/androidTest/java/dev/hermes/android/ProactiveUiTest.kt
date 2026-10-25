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
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.refreshNotificationPreview
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
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private fun proactiveKey(fixture: NativeExperienceFixture) = proactiveSettingsKey(fixture.scope.copy(profile = "coder"))

    @Test fun proactiveToggleEnablesImmediatelyWithTheExistingDefaults() = withFixture { vm, fixture ->
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true); vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        // Display names arrive with the profile list, a moment after the Proactive settings.
        await { compose.onAllNodesWithText("Profile: Code profile").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("coder", substring = false).assertDoesNotExist()

        compose.onNodeWithText("What needs you · Code profile").assertExists()
        compose.onNodeWithText("Beacon tells you what needs your attention.").assertExists()
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
        // R6P watches every connected source, not only incoming email.
        compose.onNodeWithText("When something matters").assertExists()
        compose.onNodeWithText("Delivery: Notify").assertExists()
        compose.onNodeWithText("Check every: 15 min").assertExists()
        compose.onNodeWithTag("proactive-time-Quiet hours start").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-time-confirm-Quiet hours start").assertExists()
        compose.onNodeWithText("Run a brief now").assertDoesNotExist()
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("What Beacon learned"))
        compose.onNodeWithText("What Beacon learned").assertExists()
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

        // Rules wait until the saved settings come back; the rules card sits below the watch card.
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-add-rule"))
        await { runCatching { compose.onNodeWithTag("proactive-add-rule").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("proactive-add-rule").performScrollTo().performClick()
        await { vm.form.value?.title == "Add a rule" }
        compose.runOnIdle {
            vm.formValue("text", "Call when the client moves a review")
            vm.formValue("action", "call")
            vm.formValue("night", "true")
        }
        compose.onNodeWithText("Add rule", useUnmergedTree = true).performClick()
        await {
            fixture.proactiveRequests["coder"]?.size == 1 && vm.data.value[proactiveKey(fixture)]?.rows("rules")?.size == 1 &&
                vm.form.value == null && compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
        }
        val create = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/requests" && it.string("method") == "POST" }
        assertEquals("coder", create.string("profile"))
        assertTrue(create.getJSONObject("body").isNull("signal"))
        assertTrue(fixture.proactiveRequests.getValue("coder").single().isNull("signal"))
        assertTrue(create.getJSONObject("body").getBoolean("night"))
        val ruleId = fixture.proactiveRequests.getValue("coder").single().string("id")
        compose.onNodeWithTag("proactive-rule-text-$ruleId").performScrollTo().assertTextContains("client moves a review", substring = true)
        val ruleToggle = compose.onNodeWithTag("proactive-rule-enabled-$ruleId")
        // Refresh can leave enabled rule semantics in an unplaced lazy item.
        await { runCatching {
            compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-rule-enabled-$ruleId"))
            ruleToggle.performScrollTo().assertIsDisplayed().assertIsEnabled().assertIsOn()
        }.isSuccess }
        ruleToggle.assertIsDisplayed().assertIsEnabled().assertIsOn().performClick()
        val ruleDelete = compose.onNodeWithTag("proactive-rule-delete-$ruleId")
        // The fixture mutates before the PATCH response and settings refresh reach the UI.
        await {
            val projectedRule = vm.data.value[proactiveKey(fixture)]?.rows("rules")?.singleOrNull()
            !fixture.proactiveRequests.getValue("coder").single().optBoolean("enabled") &&
                projectedRule?.let { it.string("id") == ruleId && !it.optBoolean("enabled") } == true &&
                runCatching {
                    compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-rule-delete-$ruleId"))
                    ruleDelete.performScrollTo().assertIsDisplayed().assertIsEnabled()
                }.isSuccess
        }
        ruleDelete.assertIsDisplayed().assertIsEnabled().performClick()
        await { fixture.proactiveRequests.getValue("coder").isEmpty() && vm.data.value[proactiveKey(fixture)]?.rows("rules")?.isEmpty() == true }
        assertTrue(fixture.requests.filter { it.string("path").contains("/proactive/") }.all { it.string("profile") == "coder" })
    }

    @Test fun learnedEmailNotesCanBeRemovedAndShowTheTeachingHintWhenEmpty() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveLearned["coder"] = CopyOnWriteArrayList(listOf("Client moved the review"))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 1 }

        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("What Beacon learned"))
        compose.onNodeWithText("What Beacon learned").assertExists()
        compose.onNodeWithText("Client moved the review").performScrollTo().assertExists()
        compose.onNodeWithTag("proactive-learned-remove-0").performScrollTo().performClick()
        await { fixture.proactiveLearned["coder"]?.isEmpty() == true && vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 0 }
        val removal = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/learned" && it.string("method") == "DELETE" }
        assertEquals("coder", removal.string("profile"))
        assertEquals("Client moved the review", removal.getJSONObject("body").string("note"))
        compose.onNodeWithText("Mark alerts as Important or Not important to teach Beacon.").assertExists()
    }

    @Test fun missingPluginShowsUpdateHelpAndOtherFailuresShowDetailAndRetry() = withFixture { vm, fixture ->
        fixture.proactiveRouteMissing = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("_pluginUpdateRequired") == true }
        compose.onNodeWithText("Update Beacon on your Hermes").assertExists()
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
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && vm.chat.value.projectionReady }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        await { compose.onAllNodesWithText("Let Beacon tell you what needs you").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("proactive-prompt-settings").performClick()
        await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Assistant" }
        // The profile label depends on cached display names; check that this opens Proactive settings.
        await { compose.onAllNodesWithText("What needs you · ", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(fixture.proactiveEnabled["coder"] == true)
    }

    @Test fun emailAlertCardPostsOnlyItsNoticeIdAndV4Verdict() = withFixture { vm, fixture ->
        val source = "email-alert-source-test"
        val noticeId = "important-email-notice-test"
        val reason = "Daniel Reyes moved the client review to Friday."
        runBlocking { app.auth.persist(fixture.client.auth) }
        assertTrue(app.push.bindKeepConnectedSource(fixture.scope, source))
        val taskScope = fixture.scope.session("chat-a")
        runBlocking { app.cache.write(taskScope, "task-result", json("reason" to reason, "notice_id" to noticeId, "email_alert" to true, "expires_at" to System.currentTimeMillis() / 1000 + 3600).toString(), protected = true) }
        fixture.incomingNotification = json(
            "seq" to 1,
            "event_id" to noticeId,
            "owner_id" to fixture.scope.owner,
            "profile" to fixture.scope.profile,
            "kind" to "task_result",
            "state" to "finished",
            "source_ref" to source,
            "conversation_ref" to "ref-chat-a",
            "session_id" to "chat-a",
            "reason" to reason,
            "email_alert" to true,
            "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            "preview" to json("title" to "Important email", "body" to reason),
        )
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<BeaconClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            vm.handlePush(source, noticeId)
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            // The chat was already open, so wait for the notification's card itself.
            await { compose.onAllNodesWithText("Important update").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(reason).assertExists()
            compose.onNodeWithTag("task-result-feedback-important").assertExists()
            compose.onNodeWithTag("task-result-feedback-not-important").performClick()
            await { fixture.proactiveFeedbacks.isNotEmpty() }
            val request = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/feedback" }
            assertEquals(fixture.scope.profile, request.string("profile"))
            assertEquals(setOf("notice_id", "verdict"), request.getJSONObject("body").keys().asSequence().toSet())
            assertEquals(noticeId, request.getJSONObject("body").string("notice_id"))
            assertEquals("not_important", request.getJSONObject("body").string("verdict"))
            await { compose.onAllNodesWithText("Thanks — Beacon will remember.").fetchSemanticsNodes().isNotEmpty() }
            await { runBlocking { app.cache.read(taskScope, "task-result") == null } }
            compose.runOnIdle { vm.storeData("task-result:${taskScope.key}", JSONObject()) }
            // Leave and come back, so the chat restores from its saved state.
            compose.runOnIdle { vm.selectSession("chat-b") }
            await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.selectSession("chat-a") }
            await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            compose.onNodeWithTag("task-result-card").assertDoesNotExist()
        } finally {
            vm.client?.closeNative()
            pushClients.forEach(BeaconClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    @Test fun emailAlertPreviewSavesTheCardForItsChatWhenTheRouteRefIsAHash() = withFixture { _, fixture ->
        val source = "email-alert-preview-test"
        val noticeId = "preview-email-notice"
        val reason = "The client review moved to Friday."
        runBlocking { app.auth.persist(fixture.client.auth) }
        // A plain push binding (not Keep connected), as an FCM-delivered alert has.
        val bindingKey = "source:${source.digest()}"
        val binding = JSONObject().put("source", source).put("origin", fixture.scope.origin).put("owner", fixture.scope.owner)
            .put("native_provider", fixture.scope.nativeProvider)
            .put("profile", fixture.scope.profile).put("backend", "hermes-dashboard").put("target", "fixture-target")
        persistPushBindings(app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE), mapOf(bindingKey to
            android.util.Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), bindingKey), android.util.Base64.NO_WRAP)))
        assertTrue(app.push.permitsIncoming(source))
        // Hermes sends conversation_ref as an HMAC of the conversation, so it never equals session_id.
        fixture.incomingNotification = json(
            "seq" to 1,
            "event_id" to noticeId,
            "owner_id" to fixture.scope.owner,
            "profile" to fixture.scope.profile,
            "kind" to "task_result",
            "state" to "finished",
            "source_ref" to source,
            "conversation_ref" to "ref-chat-a",
            "session_id" to "chat-a",
            "reason" to reason,
            "email_alert" to true,
            "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            "preview" to json("title" to "Important email", "body" to reason),
        )
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<BeaconClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            runBlocking { refreshNotificationPreview(app, source, noticeId, "ref-chat-a", "task_result") }
            val saved = runBlocking { app.cache.read(fixture.scope.session("chat-a"), "task-result") }
            assertEquals(noticeId, JSONObject(requireNotNull(saved) { "the alert card was not saved" }).string("notice_id"))
        } finally {
            pushClients.forEach(BeaconClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            runBlocking { app.cache.delete(fixture.scope.session("chat-a"), "task-result") }
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    @Test fun emailAlertDeliveredWhileBeaconChatIsOpenAppearsWithoutPushTap() = withFixture { vm, _ ->
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.chat.value.projectionReady && vm.isBeaconConversation() }
        val scope = requireNotNull(vm.authenticatedScope()).session(vm.chat.value.sessionId)
        val noticeId = "live-email-notice"
        val reason = "The client review moved to Friday."
        await { app.push.emailAlerts.subscriptionCount.value > 0 }

        runBlocking {
            app.push.emailAlerts.emit(scope to json(
                "reason" to reason,
                "notice_id" to noticeId,
                "email_alert" to true,
                "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            ))
        }

        await { vm.data.value["task-result:${scope.key}"]?.string("notice_id") == noticeId }
        compose.onNodeWithTag("task-result-card").assertExists()
        compose.onNodeWithText(reason).assertExists()
    }

    @Test fun cachedEmailAlertAppearsWhenItsChatOpensWithoutPushExtras() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val noticeId = "cached-email-notice"
        val reason = "The client review moved to Friday."
        runBlocking {
            app.cache.write(scope, "task-result", json(
                "reason" to reason,
                "notice_id" to noticeId,
                "email_alert" to true,
                "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            ).toString(), protected = true)
        }

        compose.runOnIdle { vm.selectSession("chat-a") }

        await { vm.data.value["task-result:${scope.key}"]?.string("notice_id") == noticeId }
        compose.onNodeWithTag("task-result-card").assertExists()
        compose.onNodeWithText(reason).assertExists()
    }

    @Test fun expiredCachedEmailAlertIsDeletedWhenItsChatOpens() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val raw = json(
            "reason" to "Old alert",
            "notice_id" to "expired-email-notice",
            "email_alert" to true,
            "expires_at" to System.currentTimeMillis() / 1000 - 1,
        ).toString()
        runBlocking { app.cache.write(scope, "task-result", raw, protected = true) }

        compose.runOnIdle { vm.selectSession("chat-a") }

        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady && runBlocking { app.cache.read(scope, "task-result") == null } }
        assertTrue(vm.data.value["task-result:${scope.key}"]?.string("reason").isNullOrBlank())
        compose.onNodeWithTag("task-result-card").assertDoesNotExist()
    }

    @Test fun dismissingCachedEmailAlertStopsItReturningWhenTheChatReopens() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val noticeId = "dismissed-email-notice"
        val reason = "The client review moved to Friday."
        runBlocking {
            app.cache.write(scope, "task-result", json(
                "reason" to reason,
                "notice_id" to noticeId,
                "email_alert" to true,
                "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            ).toString(), protected = true)
        }
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.data.value["task-result:${scope.key}"]?.string("notice_id") == noticeId }
        compose.onNodeWithTag("task-result-dismiss").performClick()
        await { runBlocking { app.cache.read(scope, "task-result") == null } && vm.data.value["task-result:${scope.key}"]?.string("reason").isNullOrBlank() }

        // Leave and come back, so the chat restores from its saved state.
        compose.runOnIdle { vm.selectSession("chat-b") }
        await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        compose.onNodeWithTag("task-result-card").assertDoesNotExist()
    }

    @Test fun taskResultForTheBoundBeaconSessionOpensTheBeaconTab() = withFixture { vm, fixture ->
        val source = "email-alert-beacon-route"
        val noticeId = "email-alert-beacon-route-event"
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<BeaconClient>()
        runBlocking {
            app.preferences.edit { it[stringPreferencesKey("assistant_profile")] = "default" }
            app.auth.persist(fixture.client.auth)
        }
        await { vm.preferences.value.assistantProfile == "default" }
        assertTrue(app.push.bindKeepConnectedSource(fixture.scope, source))
        fixture.incomingNotification = json(
            "seq" to 1,
            "event_id" to noticeId,
            "owner_id" to fixture.scope.owner,
            "profile" to fixture.scope.profile,
            "kind" to "task_result",
            "state" to "finished",
            "source_ref" to source,
            "conversation_ref" to "ref-chat-a",
            "session_id" to "chat-a",
            "reason" to "The client review moved to Friday.",
            "email_alert" to true,
            "expires_at" to System.currentTimeMillis() / 1000 + 3600,
            "preview" to json("title" to "Important email", "body" to "The client review moved to Friday."),
        )
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            val saved = BeaconViewModel::class.java.getDeclaredField("saved").apply { isAccessible = true }.get(vm) as SavedStateHandle
            saved["beacon:${fixture.scope.key}"] = "chat-a"
            vm.handlePush(source, noticeId)
            await { vm.ui.value.tab == "Beacon" && vm.isBeaconConversation() && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
        } finally {
            vm.client?.closeNative()
            pushClients.forEach(BeaconClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    @Test fun proactiveControlsStayHiddenWhenOptionalPluginIsAbsent() = withFixture(pluginStatus = "absent") { vm, fixture ->
        fixture.jobs.getValue("task-a").put("state", "running")
        compose.runOnIdle { vm.more("Assistant") }
        // The email card offers the plugin install instead of its controls.
        await { compose.onAllNodesWithTag("missing-plugin-install").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("assistant-proactive-toggle").assertCountEquals(0)
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.ui.value.profile == "coder" && vm.chat.value.projectionReady }
        // The native summary appears only after the background poll has completed.
        await { compose.onAllNodesWithTag("chat-activity-strip").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-activity-strip").assertIsDisplayed().assertContentDescriptionEquals("Background activity, 1 cron job")
        compose.onAllNodesWithTag("proactive-prompt-card").assertCountEquals(0)
        assertFalse(fixture.requests.any { it.string("path") == PROACTIVE_SETTINGS_PATH })
    }

    @Suppress("UNCHECKED_CAST") private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun withFixture(pluginStatus: String = "available", block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
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
                it[stringPreferencesKey("assistant_name")] = "Beacon"
                it[stringPreferencesKey("assistant_profile")] = "coder"
                it.remove(dismissalKey)
            }
        }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture().apply { bootstrapPluginStatus = pluginStatus; if (pluginStatus == "absent") this.pluginStatus = 404 }
        val profileScopes = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        runBlocking {
            app.auth.persist(fixture.client.auth)
            profileScopes.forEach { owner ->
                listOf("beacon-session", "beacon-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                (listOf("chat-a", "chat-b") + (1..4).map { "new-$it" }).forEach { id ->
                    listOf("pending", "draft", "session", "client-created-shell", "task-result").forEach { app.cache.delete(owner.session(id), it) }
                }
            }
        }
        lateinit var vm: BeaconViewModel
        val profileSwitchClientFactory: (AuthContext) -> BeaconClient = { auth -> fixture.freshClientFor(auth, app.auth) }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
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
                    LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()),
                    LocalAssistantName provides prefs.assistantName,
                ) {
                    NativeLocalization("en") {
                        BeaconTheme(dark = true) {
                            NativeWindowLayout(compose.activity) {
                                Box(Modifier.fillMaxSize().testTag("proactive-test-viewport")) { BeaconRoot(vm) }
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
                    listOf("beacon-session", "beacon-opening", "bot-mode-return", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) }
                    (listOf("chat-a", "chat-b") + (1..4).map { "new-$it" }).forEach { id ->
                        listOf("pending", "draft", "session", "client-created-shell", "task-result").forEach { app.cache.delete(owner.session(id), it) }
                    }
                }
                app.preferences.updateData { prior }
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
}
