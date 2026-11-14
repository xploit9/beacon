package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Lane S3: the first new chat after a sign-in must be usable at once.
 *
 * Every test signs in through the real `connect()` against the synthetic native authority and renders the real app; what the fixture received is the log
 * of what Hermes was asked. A new chat that shows the composer with no conversation behind it has to prepare that conversation on its own, exactly once:
 * the composer controls are live and Attach opens its choices. Synthetic ids only.
 */
class S3ComposerUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private companion object { const val GONE_SHELL = "new-gone" }

    private fun creates(fixture: NativeExperienceFixture) = fixture.requests.count { it.string("method") == "session.create" }
    private fun createFrames(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("method") == "session.create" }.map { it.optJSONObject("params") ?: JSONObject() }
    private fun madeSessions(fixture: NativeExperienceFixture) = fixture.sessions.keys.count { it.startsWith("new-") }

    private fun shell(command: String): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        return automation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
    }

    /**
     * A phone that has not signed in yet: the saved state and preferences the case names, the account stored for sign-in, and the real screen on top.
     * [savedState] is the process-restored state (the tab that was showing, a chat that was open); [preferences] are stored settings.
     */
    private fun withSignedOutPhone(savedState: (NativeExperienceFixture) -> Map<String, Any?> = { emptyMap() }, preferences: Map<String, String> = emptyMap(),
        canonical: Boolean = false, seed: suspend (NativeExperienceFixture) -> Unit = {}, screen: Boolean = true, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        // [canonical]: the fixture answers like Hermes' canonical authority (4001 not_found for a missing session, create fields and request_id as in gateway/session_policy.py and session_local.py).
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = canonical }
        val prior = runBlocking { app.preferences.data.first() }
        val owners = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        val ownerKinds = listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "conversation-index", "beacon-session", "beacon-opening")
        fun clean() = runBlocking {
            owners.forEach { owner ->
                ownerKinds.forEach { app.cache.delete(owner, it) }
                (1..24).forEach { n -> listOf("pending", "draft", "session", "client-created-shell", "side-question", "running-turn").forEach { app.cache.delete(owner.session("new-$n"), it) } }
            }
            // Other classes share these chats and scopes on the device (the cache is not set aside by --isolated): a reply left running or a held draft would make the app ask Hermes on open.
            val chatKinds = listOf("pending", "draft", "session", "running-turn", "side-question", "alerts", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b")
            listOf("chat-a", "chat-b", "gone-chat").forEach { id -> owners.forEach { owner -> chatKinds.forEach { kind -> app.cache.delete(owner.session(id), kind) } } }
            owners.forEach { app.cache.delete(it.session(GONE_SHELL), "client-created-shell") }
        }
        val animatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit { values ->
                    values[stringPreferencesKey("origin")] = ""
                    values.remove(stringPreferencesKey("assistant_return"))
                    preferences.forEach { (key, value) -> values[stringPreferencesKey(key)] = value }
                }
                app.auth.persist(fixture.client.auth)
            }
            clean()
            runBlocking { seed(fixture) }
            val handle = SavedStateHandle(savedState(fixture))
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, handle) } })[BeaconViewModel::class.java] }
            compose.waitUntil(10_000) { !vm.ui.value.restoringSession }
            compose.runOnIdle { vm.profileSwitchClientFactoryForTest = { auth -> fixture.freshClientFor(auth, app.auth) } }
            // [screen] false: no chat screen is composed, so nothing on screen can ask for the chat again; the view model alone has to open it.
            if (screen) compose.setContent {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                CompositionLocalProvider(LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()), LocalAssistantName provides prefs.assistantName) {
                    NativeLocalization("en") { BeaconTheme(dark = true) { NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize()) { BeaconRoot(vm) } } } }
                }
            } else compose.setContent { Box(Modifier.fillMaxSize()) }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking {
                app.preferences.updateData { prior }
                app.auth.remove(fixture.scope.origin)
            }
            clean()
            shell(if (animatorScale == "null" || animatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $animatorScale")
        }
    }

    private fun describe(vm: BeaconViewModel, fixture: NativeExperienceFixture): String {
        val ui = vm.ui.value
        return "tab=${ui.tab} ready=${ui.initialChatReady} opening=${ui.newChatOpening} error='${ui.newChatError}' appError='${ui.error.take(160)}' wizard=${ui.setupWizard} authenticated=${ui.authenticated} " +
            "chat='${vm.chat.value.sessionId}' projection=${vm.chat.value.projectionReady} status='${vm.chat.value.status}' creates=${creates(fixture)} made=${madeSessions(fixture)} " +
            "chatA=${fixture.requests.filter { it.toString().contains("chat-a") }.map { "${it.string("method")}${it.string("path")}" }.take(8)} " +
            "recent=${fixture.requests.takeLast(8).map { "${it.string("method")}${it.string("path")}" }} rejected=${fixture.rejectedRequests.takeLast(3).map { "${it.string("method")}${it.string("path")}" }}"
    }

    /**
     * The composer is live (its controls are enabled) and Attach opens its choices; the chat behind it was prepared once, and it is not the assistant's own conversation
     * ([assistantChat]), which never belongs in Chat.
     */
    private fun assertTheFirstChatIsUsable(vm: BeaconViewModel, fixture: NativeExperienceFixture, createsBefore: Int = 0, assistantChat: String = "") {
        compose.waitUntil(10_000) { vm.ui.value.tab == "Chat" && vm.ui.value.authenticated }
        val live = try { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("composer-attach").assertIsEnabled() }.isSuccess }; true } catch (_: Throwable) { false }
        assertTrue("The composer stayed dimmed: ${describe(vm, fixture)}", live)
        compose.onNodeWithTag("composer-commands").assertIsEnabled()
        // The first chat opens by itself; Attach is tapped once it has (a tap in the instant between recovery steps, with no chat and no job yet, is consumed without a menu).
        val opened = try { compose.waitUntil(30_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != GONE_SHELL && vm.chat.value.sessionId != assistantChat && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The first chat did not open: ${describe(vm, fixture)}", opened)
        compose.onNodeWithTag("composer-attach").performClick()
        val menu = try { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("attach-menu").assertIsDisplayed() }.isSuccess }; true } catch (_: Throwable) { false }
        assertTrue("Attach did not open its menu: ${describe(vm, fixture)}", menu)
        compose.waitForIdle()
        Thread.sleep(1_500)
        assertEquals("One chat was prepared, not two: ${describe(vm, fixture)}", createsBefore + 1, creates(fixture))
        assertTrue("The prepared chat is the one on screen: ${describe(vm, fixture)}", vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != assistantChat)
    }

    /** Signs in as a fresh install does (inside the setup wizard) and waits until Hermes has answered. */
    private fun signIn(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        compose.runOnIdle { vm.connect(fixture.scope.origin) }
        compose.waitUntil(15_000) { vm.ui.value.authenticated && vm.ui.value.connected && !vm.ui.value.loading }
    }

    /** The wizard's last step: it opens the Chat tab. */
    private fun finishSetup(vm: BeaconViewModel) = compose.runOnIdle { vm.finishSetupWizard(keepConnected = false) }

    /** The guard: a plain sign-in on the Chat tab has always prepared its chat. */
    @Test fun signingInOnTheChatTabPreparesTheFirstChatOnce() = withSignedOutPhone { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
    }

    /**
     * Branch 1 (S6): signed in while the Beacon tab was showing, then Chat is opened. The tab was entered from Settings, so the saved return has no chat to go back to
     * (every real way into the assistant tab leaves a return record). Chat must not keep the assistant's conversation; it gets its own prepared chat.
     */
    @Test fun signingInWithTheBeaconTabShowingThenOpeningChatPreparesTheFirstChatOnce() = withSignedOutPhone({ mapOf("tab" to "Beacon") },
        preferences = mapOf("assistant_return" to JSONObject().put("profile", "default").put("session", "").toString())) { vm, fixture ->
        signIn(vm, fixture)
        compose.waitUntil(15_000) { vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.chat.value.sessionId.isNotBlank() && vm.chat.value.projectionReady }
        val assistantChat = vm.chat.value.sessionId
        val before = creates(fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture, createsBefore = before, assistantChat = assistantChat)
    }

    /** Branch 2 (S6): signed in with a saved chat selected that Hermes no longer has. */
    @Test fun signingInWithASavedChatThatNoLongerExistsLeavesAChatTheComposerCanWriteTo() = withSignedOutPhone({ fixture -> mapOf("session:${fixture.scope.key}" to "gone-chat") }) { vm, fixture ->
        signIn(vm, fixture)
        compose.waitUntil(15_000) { vm.chat.value.projectionReady || vm.ui.value.error.isNotBlank() }
        finishSetup(vm)
        compose.waitUntil(10_000) { vm.ui.value.tab == "Chat" }
        val live = try { compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("composer-attach").assertIsEnabled() }.isSuccess }; true } catch (_: Throwable) { false }
        assertTrue("The composer stayed dimmed: ${describe(vm, fixture)}", live)
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("attach-menu").assertIsDisplayed() }.isSuccess }
        Thread.sleep(1_500)
        assertEquals("A saved chat that is gone is not replaced by a new one: ${describe(vm, fixture)}", 0, creates(fixture))
    }

    /** The guard for "which chat opens": a saved chat that exists is the one on screen after sign-in, and no other chat is made. */
    @Test fun signingInWithASavedChatOpensThatChatAndPreparesNoOther() = withSignedOutPhone({ fixture -> mapOf("session:${fixture.scope.key}" to "chat-a") }) { vm, fixture ->
        signIn(vm, fixture)
        // The saved chat loads while the wizard is still showing; the wizard's last step (which opens Chat) comes later, as it does for a person.
        val loaded = try { compose.waitUntil(30_000) { vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The saved chat did not load: ${describe(vm, fixture)}", loaded)
        finishSetup(vm)
        val opened = try { compose.waitUntil(15_000) { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId == "chat-a" && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The saved chat did not open: ${describe(vm, fixture)}", opened)
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("composer-attach").assertIsEnabled() }.isSuccess }
        compose.onNodeWithTag("composer-attach").performClick()
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("attach-menu").assertIsDisplayed() }.isSuccess }
        Thread.sleep(1_500)
        assertEquals("The saved chat stays open: ${describe(vm, fixture)}", "chat-a", vm.chat.value.sessionId)
        assertEquals("No other chat was made: ${describe(vm, fixture)}", 0, creates(fixture))
    }

    /**
     * Branch 4 (found by this lane): signed out from Settings and signed in again. The tab is still Settings when the leftover return is spent, so nothing prepares the chat
     * then, and the wizard's last step (which opens Chat) finds a chat that was never made ready.
     */
    @Test fun signingInAgainFromSettingsWithALeftoverAssistantReturnPreparesTheFirstChatOnce() = withSignedOutPhone({ mapOf("tab" to "More", "more" to "Settings") },
        preferences = mapOf("assistant_return" to JSONObject().put("profile", "default").put("session", "").toString())) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
    }

    /** The guard for branch 4's tab: no leftover record, signed in again from Settings. */
    @Test fun signingInAgainFromSettingsPreparesTheFirstChatOnce() = withSignedOutPhone({ mapOf("tab" to "More", "more" to "Settings") }) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
    }

    /** Branch 3 (found by this lane): a leftover return record from the assistant tab makes sign-in return to the chats instead of preparing the first chat. */
    @Test fun signingInWithALeftoverAssistantReturnPreparesTheFirstChatOnce() = withSignedOutPhone(
        preferences = mapOf("assistant_return" to JSONObject().put("profile", "default").put("session", "").toString())) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
    }

    /**
     * Round 2 (found by the wire capture on the demo): the phone remembers its first chat (the shell it made last time), Hermes no longer has it, and the canonical authority
     * answers a resume with 4001 not_found instead of the legacy 4007. The app did not read that as a missing chat: no new chat was ever made and Retry resumed the same dead id.
     * A fresh sign-in must open one new first chat whose Attach opens its menu.
     */
    @Test fun aFreshSignInWhoseRememberedFirstChatHermesNoLongerHasOpensOneNewChat() = withSignedOutPhone(canonical = true, seed = { fixture ->
        app.cache.write(fixture.scope.session(GONE_SHELL), "client-created-shell", "1", protected = true)
        app.cache.write(fixture.scope, "new-chat-shell", GONE_SHELL, protected = true)
    }) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
        assertEquals("one chat was made, and it is not the vanished one", 1, madeSessions(fixture))
        assertNotEquals("the chat on screen is not the vanished one", GONE_SHELL, vm.chat.value.sessionId)
        assertNull("no unconfirmed attempt is left behind", runBlocking { app.cache.read(fixture.scope, "new-chat-attempt") })
    }

    /**
     * Round 2 (found live on the demo): with the remembered first chat gone on Hermes, the recovery cleared its markers and then stopped, because the check it re-ran needed the
     * remembered shell it had just removed. The chat screen's own re-ask hid that on a device test with the screen up, and on the demo it left a live composer and no chat. With
     * no chat screen to ask again, the first chat must open by itself, once.
     */
    @Test fun aRememberedFirstChatHermesNoLongerHasIsReplacedByTheRecoveryItselfWithNoScreenToAskAgain() = withSignedOutPhone(canonical = true, screen = false, seed = { fixture ->
        app.cache.write(fixture.scope.session(GONE_SHELL), "client-created-shell", "1", protected = true)
        app.cache.write(fixture.scope, "new-chat-shell", GONE_SHELL, protected = true)
    }) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        val opened = try { compose.waitUntil(30_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.sessionId != GONE_SHELL && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The first chat did not open by itself: ${describe(vm, fixture)}", opened)
        Thread.sleep(1_500)
        assertEquals("one chat was made: ${describe(vm, fixture)}", 1, madeSessions(fixture))
        assertEquals("one create: ${describe(vm, fixture)}", 1, creates(fixture))
        assertNull("no unconfirmed attempt is left behind", runBlocking { app.cache.read(fixture.scope, "new-chat-attempt") })
    }

    /** Round 2: the first chat's create carries its attempt as the retry identity Hermes' own client sends (gateway/session_local.py:333-340). */
    @Test fun theFirstChatsCreateCarriesItsAttemptAsHermesRetryIdentity() = withSignedOutPhone(canonical = true) { vm, fixture ->
        signIn(vm, fixture)
        finishSetup(vm)
        assertTheFirstChatIsUsable(vm, fixture)
        val request = createFrames(fixture).single().optString("request_id")
        assertTrue("the create names its attempt: ${createFrames(fixture)}", request.isNotBlank())
    }

    /**
     * Round 2: the first chat's create reached Hermes but its answer was lost. The phone keeps the attempt, and the Retry finds the chat that was made (same retry identity)
     * instead of making a second one.
     */
    @Test fun aFirstChatCreateWhoseAnswerWasLostIsFoundByItsRetryNotMadeTwice() = withSignedOutPhone(canonical = true) { vm, fixture ->
        fixture.loseNextCreateAnswer = true
        signIn(vm, fixture)
        finishSetup(vm)
        val lost = try { compose.waitUntil(15_000) { creates(fixture) == 1 && vm.ui.value.newChatError.isNotBlank() }; true } catch (_: Throwable) { false }
        assertTrue("The first create was not left unconfirmed: ${describe(vm, fixture)}", lost)
        assertEquals("Hermes made the chat", 1, madeSessions(fixture))
        compose.runOnIdle { vm.retryInitialChat() }
        compose.waitUntil(15_000) { vm.chat.value.sessionId.startsWith("new-") && vm.chat.value.projectionReady }
        compose.waitForIdle()
        Thread.sleep(1_500)
        assertEquals("the retry found that chat: ${describe(vm, fixture)}", 1, madeSessions(fixture))
        val frames = createFrames(fixture)
        assertEquals("two requests, one retry identity: $frames", 1, frames.map { it.optString("request_id") }.filter { it.isNotBlank() }.toSet().size)
        // R10 Nice 9: Hermes refuses a retry that carries other fields than the create it resumes (gateway/session_local.py:340-343, invalid_params), and the fixture now does too:
        // the Retry must send exactly the first create's fields, so a later change that adds a field to the create cannot loop every Retry on a real Hermes.
        assertEquals("the retry carries exactly the first create's fields: $frames", 1, frames.map { frame -> frame.keys().asSequence().sorted().joinToString(",") { "$it=${frame.opt(it)}" } }.toSet().size)
        assertTheFirstChatIsUsable(vm, fixture, createsBefore = 1)
    }

    /**
     * R10 Should 3: the owner is on the first-chat screen with no conversation (the create's answer was lost, Retry shows), types a message, opens the Beacon tab (its return record
     * has no chat) and comes back. The assistant's conversation leaves Chat and the composer is cleared for it; the typed message must be back in the composer, and the Retry
     * must open the chat the lost create made, with the message in it. One chat, no lost text.
     */
    @Test fun aDraftTypedWithNoConversationSurvivesAVisitToTheAssistantTab() = withSignedOutPhone(canonical = true) { vm, fixture ->
        fixture.loseNextCreateAnswer = true
        signIn(vm, fixture)
        finishSetup(vm)
        val lost = try { compose.waitUntil(15_000) { creates(fixture) == 1 && vm.ui.value.newChatError.isNotBlank() }; true } catch (_: Throwable) { false }
        assertTrue("The first create was not left unconfirmed: ${describe(vm, fixture)}", lost)
        val first = fixture.sessions.keys.single { it.startsWith("new-") }
        val draft = "A message typed while the chat could not open"
        compose.runOnIdle { vm.composer(TextFieldValue(draft, TextRange(draft.length))) }
        runBlocking { vm.checkpointNow() }
        compose.runOnIdle { vm.tab("Beacon") }
        val inBeacon = try { compose.waitUntil(15_000) { vm.ui.value.tab == "Beacon" && !vm.ui.value.beaconOpening && vm.chat.value.sessionId.isNotBlank() && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The assistant tab did not open its conversation: ${describe(vm, fixture)}", inBeacon)
        val assistantChat = vm.chat.value.sessionId
        compose.runOnIdle { vm.tab("Chat") }
        val back = try { compose.waitUntil(15_000) { vm.ui.value.tab == "Chat" && vm.chat.value.sessionId != assistantChat }; true } catch (_: Throwable) { false }
        assertTrue("Chat did not leave the assistant's conversation: ${describe(vm, fixture)}", back)
        val kept = try { compose.waitUntil(10_000) { vm.composer.value.text == draft }; true } catch (_: Throwable) { false }
        assertTrue("The typed message was lost: composer='${vm.composer.value.text}' ${describe(vm, fixture)}", kept)
        compose.runOnIdle { vm.retryInitialChat() }
        val opened = try { compose.waitUntil(15_000) { vm.chat.value.sessionId == first && vm.chat.value.projectionReady }; true } catch (_: Throwable) { false }
        assertTrue("The Retry did not open the chat the first create made: ${describe(vm, fixture)}", opened)
        compose.waitForIdle()
        assertEquals("the message is in the chat that opened", draft, vm.composer.value.text)
        assertEquals("one retry identity, so one first chat: ${createFrames(fixture)}", 1, createFrames(fixture).map { it.optString("request_id") }.filter { it.isNotBlank() }.toSet().size)
    }
}
