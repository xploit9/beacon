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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class ProactiveUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private fun proactiveKey(fixture: NativeExperienceFixture) = proactiveSettingsKey(fixture.scope.copy(profile = "coder"))

    // Owner, 4 Nov 2026: whether the assistant may use personal things on the lock screen is the owner's choice, in Settings › Assistant.
    @Test fun thePhoneAssistantCardHoldsTheLockScreenChoiceOffByDefault() = withFixture { vm, _ ->
        val context = ApplicationProvider.getApplicationContext<BeaconApplication>()
        dev.hermes.android.voice.LockScreenAccess.set(context, false)
        try {
            compose.runOnIdle { vm.more("Assistant") }
            if (dev.hermes.android.assist.phoneAssistantState(context) == dev.hermes.android.assist.PhoneAssistantState.Unavailable) return@withFixture
            val row = compose.onNodeWithTag("lock-screen-access")
            row.performScrollTo().assertIsOff().assertTextContains("Personal info when locked").assertTextContains("Asks you to unlock first")
            // It sits in the side button's own card, not in a card of its own.
            compose.onNode(hasTestTag("lock-screen-access") and hasAnyAncestor(hasTestTag("phone-assistant-section"))).assertExists()
            row.performClick()
            compose.waitForIdle()
            row.assertIsOn().assertTextContains("Memory and tools work while locked")
            assertTrue(dev.hermes.android.voice.LockScreenAccess.allowed(context))
            row.performClick()
            compose.waitForIdle()
            assertFalse(dev.hermes.android.voice.LockScreenAccess.allowed(context))
        } finally { dev.hermes.android.voice.LockScreenAccess.set(context, false) }
    }

    @Test fun proactiveToggleEnablesImmediatelyWithTheExistingDefaults() = withFixture { vm, fixture ->
        fixture.profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true); vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        // Display names arrive with the profile list, a moment after the Proactive settings.
        await { compose.onAllNodes(hasText("Profile") and hasText("Code profile")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("coder", substring = false).assertDoesNotExist()

        // The section names the profile it watches for in a badge beside its heading.
        compose.onNode(hasText("Code profile") and hasAnyAncestor(hasTestTag("settings-section:What needs you"))).assertExists()
        compose.onNodeWithText("Beacon tells you what needs your attention.").assertDoesNotExist()
        compose.onNodeWithTag("assistant-proactive-toggle").performScrollTo().assertIsOff().assertTextContains("Watch for me").performClick()
        await { fixture.proactiveEnabled["coder"] == true && vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }

        assertEquals("notify", fixture.proactiveDelivery["coder"])
        assertEquals(15, fixture.proactiveCheckMinutes["coder"])
        assertEquals("22:00", fixture.proactiveQuietStart["coder"])
        assertEquals("07:00", fixture.proactiveQuietEnd["coder"])
        val write = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }
        assertEquals("coder", write.string("profile"))
        assertEquals(setOf("enabled"), write.getJSONObject("body").keys().asSequence().toSet())
        assertTrue(write.getJSONObject("body").getBoolean("enabled"))
        // R6P watches every connected source, not only incoming email. Delivery and the interval are segments, not dropdowns.
        compose.onNodeWithTag("assistant-proactive-toggle").assertIsOn()
        compose.onNodeWithTag("choice-option:Delivery:notify").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("choice-option:Delivery:call").assertIsNotSelected()
        compose.onNodeWithTag("choice-option:Check every:15").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("proactive-time-Quiet hours start").performScrollTo().assertTextContains("Quiet from").performClick()
        compose.onNodeWithTag("proactive-time-confirm-Quiet hours start").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("proactive-time-Quiet hours end").performScrollTo().assertTextContains("Quiet until")
        compose.onNodeWithText("Run a brief now").assertDoesNotExist()
        // Importance lives in the rules list; with no earlier ratings there is no card for them.
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Your rules"))
        compose.onNodeWithText("Your rules").assertExists()
        compose.onNodeWithText("Earlier ratings").assertDoesNotExist()
        compose.onNodeWithText("What Beacon learned").assertDoesNotExist()
    }

    @Test fun v4SettingsSaveRulesAndKeepEveryRequestOnTheAssistantProfile() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }

        // Each choice saves as soon as its segment is tapped; there is no dropdown to open first.
        compose.onNodeWithTag("choice-option:Delivery:call").performScrollTo().performClick()
        await { fixture.proactiveDelivery["coder"] == "call" }
        compose.onNodeWithTag("choice-option:Check every:5").performScrollTo().performClick()
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
        compose.onNodeWithTag("proactive-rule-delete-confirm").performClick()
        await { fixture.proactiveRequests.getValue("coder").isEmpty() && vm.data.value[proactiveKey(fixture)]?.rows("rules")?.isEmpty() == true }
        assertTrue(fixture.requests.filter { it.string("path").contains("/proactive/") }.all { it.string("profile") == "coder" })
    }

    @Test fun aRefusedChoiceGoesBackToWhatHermesHasAndShowsTheFailure() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("enabled") == true }
        fixture.proactiveSaveStatusCode = 503

        compose.onNodeWithTag("choice-option:Delivery:call").performScrollTo().performClick()
        await { compose.onAllNodesWithText("Settings change refused").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("choice-option:Delivery:notify").assertIsSelected()
        compose.onNodeWithTag("choice-option:Delivery:call").assertIsNotSelected()
        assertEquals("notify", fixture.proactiveDelivery["coder"])
    }

    // Importance rules sit in the one rules list beside the delivery rules: a verdict instead of Notify / Call, with edit, switch and delete.
    @Test fun importanceRulesSitInTheOneRulesListWithEditSwitchAndDelete() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveRequests["coder"] = CopyOnWriteArrayList(listOf(
            json("id" to "rule-call", "text" to "Call when the client moves a review", "action" to "call", "signal" to null, "night" to false, "enabled" to true, "created_at" to 1),
            json("id" to "rule-imp", "text" to "Sent by Acme Promotions", "action" to "not_important", "signal" to "email", "night" to false, "enabled" to true, "created_at" to 2),
        ))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("rules")?.length() == 2 }

        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-rule-rule-imp"))
        badgeShows("proactive-rule-kind-rule-imp", "Not important")
        // The source reads as a word, not as the id Hermes sends.
        badgeShows("proactive-rule-source-rule-imp", "Email")
        compose.onNodeWithTag("proactive-rule-text-rule-imp").assertTextContains("Sent by Acme Promotions")
        compose.onAllNodesWithTag("proactive-rule-night-rule-imp").assertCountEquals(0)
        compose.onNodeWithTag("proactive-rule-night-rule-call").performScrollTo().assertExists()

        // Edit the words; the verdict stays.
        compose.onNodeWithTag("proactive-rule-edit-rule-imp").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-rule-edit-save").assertIsNotEnabled()
        compose.onNodeWithTag("proactive-rule-edit-field").performTextReplacement("Sent by Acme")
        compose.onNodeWithTag("proactive-rule-edit-save").assertIsEnabled().performClick()
        await { fixture.proactiveRequests["coder"]?.firstOrNull { it.string("id") == "rule-imp" }?.string("text") == "Sent by Acme" }
        val patch = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/requests/rule-imp" && it.string("method") == "PATCH" }
        assertEquals(setOf("text"), patch.getJSONObject("body").keys().asSequence().toSet())
        await { compose.onAllNodesWithText("Sent by Acme").fetchSemanticsNodes().isNotEmpty() }
        badgeShows("proactive-rule-kind-rule-imp", "Not important")

        // The switch and delete work like every other rule's.
        compose.onNodeWithTag("proactive-rule-enabled-rule-imp").performScrollTo().performClick()
        await { fixture.proactiveRequests["coder"]?.firstOrNull { it.string("id") == "rule-imp" }?.optBoolean("enabled") == false }
        compose.onNodeWithTag("proactive-rule-delete-rule-imp").performScrollTo().performClick()
        compose.onNodeWithTag("proactive-rule-delete-confirm").performClick()
        await { fixture.proactiveRequests["coder"]?.none { it.string("id") == "rule-imp" } == true }
        compose.onAllNodesWithTag("proactive-rule-rule-imp").assertCountEquals(0)
        compose.onNodeWithTag("proactive-rule-rule-call").performScrollTo().assertExists()
    }

    // Ratings from before importance became rules show once. Turn into rules lets the owner review and change what is proposed.
    @Test fun earlierRatingsTurnIntoReviewedRulesAndTheCardIsGone() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveLearned["coder"] = CopyOnWriteArrayList(listOf(
            "Beacon importance: email: Important: \"Daniel Reyes / Client moved the review\"",
            "Beacon importance: email: Not important: \"Acme Promotions / Weekly deals\"",
        ))
        fixture.proactiveProposal["coder"] = CopyOnWriteArrayList(listOf(
            json("verdict" to "important", "source" to "email", "sender" to "Daniel Reyes", "topic" to "Client moved the review", "rule" to "Sent by Daniel Reyes"),
            json("verdict" to "not_important", "source" to "email", "sender" to "Acme Promotions", "topic" to "Weekly deals", "rule" to "Sent by Acme Promotions"),
        ))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned_proposal")?.length() == 2 }

        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Earlier ratings"))
        compose.onNodeWithText("Earlier ratings").assertExists()
        compose.onNodeWithText("What Beacon learned").assertDoesNotExist()
        compose.onNodeWithTag("proactive-earlier-convert").performScrollTo().performClick()
        await { compose.onAllNodesWithTag("earlier-rules-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("earlier-rule-text-0").assertTextContains("Sent by Daniel Reyes")
        badgeShows("earlier-rule-kind-0", "Important")
        compose.onNodeWithTag("earlier-rule-text-1").assertTextContains("Sent by Acme Promotions")
        badgeShows("earlier-rule-kind-1", "Not important")

        // The owner drops the second, rewords the first and saves.
        compose.onNodeWithTag("earlier-rule-remove-1").performClick()
        compose.onAllNodesWithTag("earlier-rule-text-1").assertCountEquals(0)
        compose.onNodeWithTag("earlier-rule-text-0").performTextReplacement("Sent by Daniel Reyes (clients)")
        compose.onNodeWithTag("earlier-rules-save").performClick()
        await { fixture.proactiveLearnedRuleBodies.size == 1 && vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 0 }
        val request = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/learned/rules" && it.string("method") == "POST" }
        assertEquals("coder", request.string("profile"))
        val rules = request.getJSONObject("body").getJSONArray("rules")
        assertEquals(1, rules.length())
        assertEquals("important", rules.getJSONObject(0).string("verdict"))
        assertEquals("email", rules.getJSONObject(0).string("source"))
        assertEquals("Sent by Daniel Reyes (clients)", rules.getJSONObject(0).string("rule"))

        // The card and the sheet are gone for good, and the rule is in the rules list.
        await { compose.onAllNodesWithTag("earlier-rules-sheet").fetchSemanticsNodes().isEmpty() }
        compose.onAllNodesWithText("Earlier ratings").assertCountEquals(0)
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Sent by Daniel Reyes (clients)"))
        compose.onNodeWithText("Sent by Daniel Reyes (clients)").assertExists()
    }

    // The same cap as saving one rule from an alert: a full list says so, and the sheet stays with what the owner reviewed.
    @Test fun turningEarlierRatingsIntoRulesWithAFullRulesListSaysItIsFull() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        fixture.proactiveRequests["coder"] = CopyOnWriteArrayList((1..20).map {
            json("id" to "rule-$it", "text" to "Call about item $it", "action" to "call", "signal" to null, "night" to false, "enabled" to true, "created_at" to it)
        })
        fixture.proactiveLearned["coder"] = CopyOnWriteArrayList(listOf("Beacon importance: email: Important: \"Daniel Reyes / Client moved the review\""))
        fixture.proactiveProposal["coder"] = CopyOnWriteArrayList(listOf(
            json("verdict" to "important", "source" to "email", "sender" to "Daniel Reyes", "topic" to "Client moved the review", "rule" to "Sent by Daniel Reyes"),
        ))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned_proposal")?.length() == 1 }

        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Earlier ratings"))
        compose.onNodeWithTag("proactive-earlier-convert").performScrollTo().performClick()
        await { compose.onAllNodesWithTag("earlier-rules-sheet").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("earlier-rules-save").performClick()
        await { compose.onAllNodesWithTag("earlier-rules-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("earlier-rules-error").assertTextEquals("Rules are full. Delete one first.")
        // Nothing was saved, and the owner's review is still there.
        assertTrue(fixture.proactiveLearnedRuleBodies.isEmpty())
        compose.onNodeWithTag("earlier-rule-text-0").assertTextContains("Sent by Daniel Reyes")
    }

    @Test fun clearingEarlierRatingsRemovesTheCardWithoutMakingRules() = withFixture { vm, fixture ->
        fixture.proactiveEnabled["coder"] = true
        // A rating in no known wording gives no proposal, so Clear is the only action.
        fixture.proactiveLearned["coder"] = CopyOnWriteArrayList(listOf("Client decisions matter."))
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 1 }

        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasText("Earlier ratings"))
        compose.onAllNodesWithTag("proactive-earlier-convert").assertCountEquals(0)
        // The row says what it clears, not just Clear.
        compose.onNodeWithTag("proactive-earlier-clear").performScrollTo().assertTextContains("Clear earlier ratings")
        compose.onNodeWithTag("proactive-earlier-clear").performClick()
        compose.onNodeWithTag("proactive-earlier-clear-confirm").performClick()
        await { fixture.proactiveLearned["coder"]?.isEmpty() == true && vm.data.value[proactiveKey(fixture)]?.optJSONArray("learned")?.length() == 0 }
        val clear = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/learned" && it.string("method") == "DELETE" }
        assertEquals("coder", clear.string("profile"))
        assertTrue(clear.getJSONObject("body").getBoolean("all"))
        assertFalse(clear.getJSONObject("body").has("note"))
        compose.onAllNodesWithText("Earlier ratings").assertCountEquals(0)
        assertTrue(fixture.proactiveRequests["coder"].isNullOrEmpty())
    }

    @Test fun missingPluginShowsUpdateHelpAndOtherFailuresShowDetailAndRetry() = withFixture { vm, fixture ->
        fixture.proactiveRouteMissing = true
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.optBoolean("_pluginUpdateRequired") == true }
        compose.onNodeWithText("Update the Beacon plugin").assertExists()
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
        await { compose.onAllNodesWithTag("assistant-proactive-toggle").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("What needs you").assertExists()
        assertFalse(fixture.proactiveEnabled["coder"] == true)
    }

    /** A badge's tag sits on its surface; the words are in its child. */
    private fun badgeShows(tag: String, text: String) = compose.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text))).assertExists()

    private fun alertJson(noticeId: String, text: String, ageSeconds: Long = 60, expiresInSeconds: Long = 3600, verdict: String = "") = json(
        "notice_id" to noticeId, "text" to text, "source" to "Email", "created_at" to System.currentTimeMillis() / 1000 - ageSeconds,
        "expires_at" to System.currentTimeMillis() / 1000 + expiresInSeconds, "verdict" to verdict,
    )
    private fun saveAlerts(scope: Scope, vararg alerts: JSONObject) = runBlocking { app.cache.write(scope, "alerts", JSONArray(alerts.toList()).toString(), protected = true) }
    private fun sourcesBody(fixture: NativeExperienceFixture) = fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }.getJSONObject("body")
    private fun savedSources(fixture: NativeExperienceFixture) = sourcesBody(fixture).getJSONArray("sources").let { names -> List(names.length()) { names.getString(it) } }

    @Test fun theSourcesRowOpensASheetWhoseSwitchesSaveAtOnce() = withFixture { vm, fixture ->
        fixture.proactiveSourceNames = listOf("Hermes", "github", "gmail")
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("source_choices") == true }
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-sources"))
        // All sources is the default, and the row is a button now.
        compose.onNodeWithTag("proactive-sources").assertIsEnabled().assertTextContains("All sources").performClick()
        compose.onNodeWithTag("proactive-sources-sheet").assertExists()
        compose.onNodeWithTag("proactive-source-all").assertIsOn()
        // The sources are listed, every one on while All sources is on, and each can still be switched off.
        for (name in listOf("Hermes", "github", "gmail")) compose.onNodeWithTag("proactive-source-$name").assertIsOn().assertIsEnabled()

        compose.onNodeWithTag("proactive-source-all").performClick()
        await { fixture.proactiveChosenSources["coder"] == listOf("Hermes", "github", "gmail") }
        assertEquals("coder", fixture.requests.last { it.string("path") == PROACTIVE_SETTINGS_PATH && it.string("method") == "PUT" }.string("profile"))
        assertEquals(setOf("sources"), sourcesBody(fixture).keys().asSequence().toSet())
        await { runCatching { compose.onNodeWithTag("proactive-source-all").assertIsOff(); compose.onNodeWithTag("proactive-source-github").assertIsEnabled() }.isSuccess }

        compose.onNodeWithTag("proactive-source-Hermes").performClick()
        await { fixture.proactiveChosenSources["coder"] == listOf("github", "gmail") }
        await { runCatching { compose.onNodeWithTag("proactive-source-Hermes").assertIsOff() }.isSuccess }
        compose.onNodeWithTag("proactive-source-github").performClick()
        await { fixture.proactiveChosenSources["coder"] == listOf("gmail") }
        // The last source stays on: none chosen would mean every source.
        await { runCatching { compose.onNodeWithTag("proactive-source-gmail").assertIsOn().assertIsNotEnabled() }.isSuccess }
        compose.onNodeWithTag("proactive-sources-done").performClick()
        compose.onNodeWithTag("proactive-sources-sheet").assertDoesNotExist()
        compose.onNodeWithTag("proactive-sources").assertTextContains("Gmail")

        // Back to All sources: nothing chosen is saved.
        compose.onNodeWithTag("proactive-sources").performClick()
        compose.onNodeWithTag("proactive-source-all").performClick()
        await { fixture.proactiveChosenSources["coder"] == emptyList<String>() }
        assertEquals(emptyList<String>(), savedSources(fixture))
        await { runCatching { compose.onNodeWithTag("proactive-source-all").assertIsOn() }.isSuccess }
        compose.onNodeWithTag("proactive-sources-done").performClick()
        compose.onNodeWithTag("proactive-sources").assertTextContains("All sources")
    }

    @Test fun aFailedSourcesSaveGivesTheSwitchesBack() = withFixture { vm, fixture ->
        fixture.proactiveSourceNames = listOf("github", "gmail")
        fixture.proactiveChosenSources["coder"] = listOf("github", "gmail")
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("source_choices") == true }
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-sources"))
        compose.onNodeWithTag("proactive-sources").assertTextContains("Github, Gmail").performClick()
        fixture.proactiveSaveStatusCode = 500
        compose.onNodeWithTag("proactive-source-github").assertIsOn().performClick()
        // Hermes refused, so the switch is back on what Hermes has.
        await { runCatching { compose.onNodeWithTag("proactive-source-github").assertIsOn() }.isSuccess && fixture.requests.any { it.string("method") == "PUT" && it.getJSONObject("body").has("sources") } }
        assertEquals(listOf("github", "gmail"), fixture.proactiveChosenSources["coder"])
        fixture.proactiveSaveStatusCode = 200
        compose.onNodeWithTag("proactive-sources-done").performClick()
    }

    @Test fun anOlderPluginThatSendsNoChoicesKeepsAPlainRow() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.more("Assistant") }
        await { vm.data.value[proactiveKey(fixture)]?.has("enabled") == true }
        compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-sources"))
        compose.onNodeWithTag("proactive-sources").assertTextContains("Not yet").assertHasNoClickAction()
    }

    private fun emailAlertRoute(fixture: NativeExperienceFixture, source: String, noticeId: String, reason: String) = json(
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

    // Replaces the pinned "Important update" card: an alert is a message from Beacon in the chat, with its own buttons.
    @Test fun emailAlertIsAChatMessageWhoseButtonsPostOnlyItsNoticeIdAndVerdict() = withFixture { vm, fixture ->
        val source = "email-alert-source-test"
        val noticeId = "important-email-notice-test"
        val reason = "Daniel Reyes moved the client review to Friday."
        runBlocking { app.auth.persist(fixture.client.auth) }
        assertTrue(app.push.bindKeepConnectedSource(fixture.scope, source))
        saveAlerts(fixture.scope.session("chat-a"), alertJson(noticeId, reason))
        fixture.incomingNotification = emailAlertRoute(fixture, source, noticeId, reason)
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<BeaconClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            vm.handlePush(source, noticeId)
            await { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            await { compose.onAllNodesWithTag("alert-message:$noticeId").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(reason).assertExists()
            compose.onAllNodesWithTag("task-result-card").assertCountEquals(0)
            compose.onNodeWithTag("alert-important:$noticeId").assertExists()
            // The tap opens the rule sheet with the suggested rule; "Just this email" marks only this alert.
            compose.onNodeWithTag("alert-not-important:$noticeId").performClick()
            await { compose.onAllNodesWithTag("email-rule-sheet").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("email-rule-title").assertTextEquals("Mark as not important")
            await { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
            assertTrue(fixture.proactiveFeedbacks.isEmpty())
            compose.onNodeWithTag("email-rule-just-this").performClick()
            await { fixture.proactiveFeedbacks.isNotEmpty() }
            val request = fixture.requests.last { it.string("path") == "$PROACTIVE_SETTINGS_PATH/feedback" }
            assertEquals(fixture.scope.profile, request.string("profile"))
            assertEquals(setOf("notice_id", "verdict"), request.getJSONObject("body").keys().asSequence().toSet())
            assertEquals(noticeId, request.getJSONObject("body").string("notice_id"))
            assertEquals("not_important", request.getJSONObject("body").string("verdict"))
            await { compose.onAllNodesWithTag("alert-verdict:$noticeId").fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodesWithTag("alert-important:$noticeId").assertCountEquals(0)
            compose.onAllNodesWithTag("alert-not-important:$noticeId").assertCountEquals(0)
            // Leave and come back, so the chat restores from its saved state: the choice is still there.
            compose.runOnIdle { vm.selectSession("chat-b") }
            await { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            compose.runOnIdle { vm.selectSession("chat-a") }
            await { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }
            await { compose.onAllNodesWithTag("alert-verdict:$noticeId").fetchSemanticsNodes().isNotEmpty() }
        } finally {
            vm.client?.closeNative()
            pushClients.forEach(BeaconClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    // The preview no longer saves a pinned card or adds buttons; it tells the open chat to read its alerts.
    @Test fun emailAlertPreviewTellsTheChatToReadItsAlertsWhenTheRouteRefIsAHash() = withFixture { _, fixture ->
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
        fixture.incomingNotification = emailAlertRoute(fixture, source, noticeId, reason)
        val previousFactory = KeepConnectedService.clientFactoryForTest
        val pushClients = mutableListOf<BeaconClient>()
        KeepConnectedService.clientFactoryForTest = { auth, store -> fixture.freshClientFor(auth, store).also(pushClients::add) }
        try {
            val posted = runBlocking {
                val first = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(20_000) { app.push.emailAlerts.first() } }
                refreshNotificationPreview(app, source, noticeId, "ref-chat-a", "task_result")
                first.await()
            }
            assertEquals(fixture.scope.session("chat-a").key, posted.key)
            assertNull("The alert is a chat message, not a saved card", runBlocking { app.cache.read(fixture.scope.session("chat-a"), "task-result") })
        } finally {
            pushClients.forEach(BeaconClient::closeNative)
            KeepConnectedService.clientFactoryForTest = previousFactory
            app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit()
                .remove("source:${source.digest()}").commit()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    // Was: a delivered alert filled the pinned card. Now the open Beacon chat reads its alerts from the plugin.
    @Test fun emailAlertDeliveredWhileBeaconChatIsOpenMakesTheChatReadItsAlerts() = withFixture { vm, fixture ->
        compose.runOnIdle { vm.tab("Beacon") }
        await { vm.ui.value.tab == "Beacon" && vm.chat.value.projectionReady && vm.isBeaconConversation() }
        val scope = requireNotNull(vm.authenticatedScope()).session(vm.chat.value.sessionId)
        await { app.push.emailAlerts.subscriptionCount.value > 0 }
        fixture.requests.clear()

        runBlocking { app.push.emailAlerts.emit(scope) }

        await { fixture.requests.any { it.string("path") == "$PROACTIVE_SETTINGS_PATH/alerts" && it.string("query").contains("session_id=${scope.session}") } }
    }

    // Was: the cached card appeared pinned. Now the cached alert is a message as soon as the chat opens.
    @Test fun cachedEmailAlertAppearsAsAMessageWhenItsChatOpensWithoutPushExtras() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        val noticeId = "cached-email-notice"
        val reason = "The client review moved to Friday."
        saveAlerts(scope, alertJson(noticeId, reason))

        compose.runOnIdle { vm.selectSession("chat-a") }

        await { compose.onAllNodesWithTag("alert-message:$noticeId").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(reason).assertExists()
        compose.onAllNodesWithTag("task-result-card").assertCountEquals(0)
    }

    // Was: an expired cached alert was deleted. Now it stays as a message; only its feedback is off.
    @Test fun expiredEmailAlertStaysAsAMessageWithItsFeedbackOff() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        saveAlerts(scope, alertJson("expired-email-notice", "Old alert", ageSeconds = 90_000, expiresInSeconds = -1))

        compose.runOnIdle { vm.selectSession("chat-a") }

        await { compose.onAllNodesWithTag("alert-message:expired-email-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("alert-important:expired-email-notice").assertCountEquals(0)
        compose.onAllNodesWithTag("alert-not-important:expired-email-notice").assertCountEquals(0)
        assertTrue(fixture.proactiveFeedbacks.isEmpty())
    }

    // "Save rule" sends the words the owner left in the sheet, and the rule appears in the one rules list.
    @Test fun savingAnAlertRuleSendsTheEditedWordsAndTheAlertShowsItsChoice() = withFixture { vm, fixture ->
        val noticeId = "rule-email-notice"
        saveAlerts(fixture.scope.session("chat-a"), alertJson(noticeId, "Daniel Reyes / Client moved the review"))
        fixture.proactiveSuggestions[noticeId] = json("source" to "email", "sender" to "Daniel Reyes", "topic" to "Client moved the review", "rule" to "Sent by Daniel Reyes")
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { compose.onAllNodesWithTag("alert-message:$noticeId").fetchSemanticsNodes().isNotEmpty() && vm.ui.value.connected }

        compose.onNodeWithTag("alert-important:$noticeId").performClick()
        await { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
        compose.onNodeWithTag("email-rule-title").assertTextEquals("Mark as important")
        compose.onNodeWithTag("email-rule-text").performTextReplacement("Sent by Daniel Reyes and his team")
        compose.onNodeWithTag("email-rule-save").performClick()
        await { fixture.proactiveFeedbacks.size == 1 && compose.onAllNodesWithTag("alert-verdict:$noticeId").fetchSemanticsNodes().isNotEmpty() }

        val sent = fixture.proactiveFeedbacks.single()
        assertEquals(setOf("notice_id", "verdict", "rule"), sent.keys().asSequence().toSet())
        assertEquals(noticeId, sent.string("notice_id"))
        assertEquals("important", sent.string("verdict"))
        assertEquals("Sent by Daniel Reyes and his team", sent.string("rule"))
        compose.onAllNodesWithTag("email-rule-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("alert-important:$noticeId").assertCountEquals(0)
        val saved = fixture.proactiveRequests[fixture.scope.profile].orEmpty().single()
        assertEquals("important", saved.string("action"))
        assertEquals("email", saved.string("signal"))
        assertEquals("Sent by Daniel Reyes and his team", saved.string("text"))
    }

    // A full rules list refuses "Save rule" and the sheet says so; "Just this email" still marks the alert.
    @Test fun aFullRulesListKeepsTheSheetOpenAndJustThisEmailStillWorks() = withFixture { vm, fixture ->
        val noticeId = "full-list-notice"
        saveAlerts(fixture.scope.session("chat-a"), alertJson(noticeId, "Daniel Reyes / Client moved the review"))
        fixture.proactiveRequests[fixture.scope.profile] = CopyOnWriteArrayList((1..20).map {
            json("id" to "rule-$it", "text" to "Rule $it", "action" to "notify", "signal" to null, "night" to false, "enabled" to true, "created_at" to it)
        })
        compose.runOnIdle { vm.selectSession("chat-a") }
        await { compose.onAllNodesWithTag("alert-message:$noticeId").fetchSemanticsNodes().isNotEmpty() && vm.ui.value.connected }

        compose.onNodeWithTag("alert-important:$noticeId").performClick()
        await { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
        compose.onNodeWithTag("email-rule-save").performClick()
        await { compose.onAllNodesWithTag("email-rule-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("email-rule-error").assertTextContains("Rules are full. Delete one first.")
        assertEquals(20, fixture.proactiveRequests[fixture.scope.profile].orEmpty().size)
        compose.onAllNodesWithTag("alert-verdict:$noticeId").assertCountEquals(0)
        compose.onNodeWithTag("email-rule-just-this").performClick()
        await { compose.onAllNodesWithTag("alert-verdict:$noticeId").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(20, fixture.proactiveRequests[fixture.scope.profile].orEmpty().size)
    }

    // Was: dismissing the alert card. Alerts have no dismiss, so this checks that each one is its own message and keeps its own choice.
    @Test fun severalEmailAlertsStayAsSeparateMessagesWithTheirOwnChoices() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        saveAlerts(scope, alertJson("older-email-notice", "Invoice 4471 is overdue", ageSeconds = 600, verdict = "important"), alertJson("newer-email-notice", "The client review moved to Friday.", ageSeconds = 60))

        compose.runOnIdle { vm.selectSession("chat-a") }

        await { compose.onAllNodesWithTag("alert-message:newer-email-notice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("alert-verdict:older-email-notice").assertExists()
        compose.onAllNodesWithTag("alert-important:older-email-notice").assertCountEquals(0)
        compose.onNodeWithTag("alert-important:newer-email-notice").assertExists()
    }

    // The generic card stays for task results that are not alerts.
    @Test fun dismissingATaskResultCardClearsIt() = withFixture { vm, fixture ->
        val scope = fixture.scope.session("chat-a")
        compose.runOnIdle { vm.storeData("task-result:${scope.key}", json("reason" to "The nightly backup finished.")) }
        await { compose.onAllNodesWithTag("task-result-card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Task result").assertExists()
        compose.onAllNodesWithTag("task-result-feedback-important").assertCountEquals(0)

        compose.onNodeWithTag("task-result-dismiss").performClick()

        await { compose.onAllNodesWithTag("task-result-card").fetchSemanticsNodes().isEmpty() }
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
        compose.onNodeWithTag("chat-activity-strip").assertIsDisplayed().assertContentDescriptionEquals("Background activity, 1 schedule running")
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
                    listOf("pending", "draft", "session", "client-created-shell", "task-result", "alerts").forEach { app.cache.delete(owner.session(id), it) }
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
                        listOf("pending", "draft", "session", "client-created-shell", "task-result", "alerts").forEach { app.cache.delete(owner.session(id), it) }
                    }
                }
                app.preferences.updateData { prior }
            }
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
}
