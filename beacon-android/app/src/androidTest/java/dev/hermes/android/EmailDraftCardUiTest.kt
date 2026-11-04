package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.EmailDraft
import dev.hermes.android.ui.EmailDraftApi
import dev.hermes.android.ui.EmailDraftCard
import dev.hermes.android.ui.EmailDraftModel
import dev.hermes.android.ui.HermesAsk
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The email draft card as the owner meets it: the email laid out like an email, a field to say what to change that redrafts the same card,
 * and Send. Hermes is a fake here; the last tests put the card in a real chat with the fixture Hermes.
 */
class EmailDraftCardUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val at = "@" + "example.com"
    private val maya = "maya$at"
    private val dan = "dan$at"
    private val id = "c".repeat(32)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After fun stop() = scope.cancel()

    private fun view(
        version: Int = 1, versions: Int = 1, subject: String = "Friday review", body: String = "Hi Maya,\n\nCan we move the review to Friday?\n\nThanks",
        status: String = "draft", reason: String = "", via: String = "tool", canSend: Boolean = true, cc: List<String> = listOf(dan),
    ) = JSONObject().put("id", id).put("version", version).put("versions", versions).put("position", version)
        .put("to", JSONArray(listOf(maya))).put("cc", JSONArray(cc)).put("subject", subject).put("body", body)
        .put("status", status).put("reason", reason).put("via", via).put("can_send", canSend).put("label", "gmail")

    private class FakeApi(var current: JSONObject) : EmailDraftApi {
        val calls = CopyOnWriteArrayList<String>()
        var revise: (String, Int) -> JSONObject = { _, _ -> throw IllegalStateException("no revise") }
        var step: (Int, Int) -> JSONObject = { _, _ -> throw IllegalStateException("no step") }
        var send: (Int) -> JSONObject = { throw IllegalStateException("no send") }
        override suspend fun read(id: String): JSONObject { calls += "read"; return current }
        override suspend fun revise(id: String, instruction: String, version: Int): JSONObject { calls += "revise:$instruction"; return revise(instruction, version).also { current = it } }
        override suspend fun step(id: String, version: Int, step: Int): JSONObject { calls += "step:$step"; return step(version, step).also { current = it } }
        override suspend fun send(id: String, version: Int): JSONObject { calls += "send:$version"; return send(version).also { current = it } }
    }

    private class Chat(var free: Boolean = true) {
        val said = CopyOnWriteArrayList<String>()
        val ask get() = HermesAsk({ free }, { said += it }, { subject -> "Send the email \"$subject\"" })
    }

    private fun show(api: FakeApi, chat: Chat = Chat(), copied: MutableList<String> = mutableListOf(), width: Int? = null): EmailDraftModel {
        val model = EmailDraftModel(id, api, scope, EmailDraft.seed(id, JSONObject().put("to", JSONArray(listOf(maya))).put("subject", "Friday review").put("body", "Hi Maya,")))
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val card = @androidx.compose.runtime.Composable { EmailDraftCard(model, chat.ask, { copied += it }) }
                if (width == null) card() else Box(Modifier.width(width.dp)) { card() }
            }
        }
        compose.waitUntil(5_000) { model.state.value.loaded }
        compose.waitForIdle()
        return model
    }

    private fun text(tag: String) = compose.onNodeWithTag(tag)
    private fun awaitTag(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    // ---- the card on its own --------------------------------------------------------------------------------------------------

    @Test fun theEmailIsLaidOutLikeAnEmailWithTheControlsUnderIt() {
        show(FakeApi(view()))
        text("draft-title").assertTextEquals("Email draft")
        text("draft-value:to").assertTextEquals(maya)
        text("draft-value:cc").assertTextEquals(dan)
        text("draft-value:subject").assertTextEquals("Friday review")
        text("draft-body").assertTextContains("Can we move the review to Friday?", substring = true)
        text("draft-revise-input").assertIsEnabled()
        text("draft-confirm").assertIsEnabled().assertTextEquals("Send email")
        // What to change is on the left, Send on the right, both under the email.
        val input = text("draft-revise-input").getUnclippedBoundsInRoot()
        val send = text("draft-confirm").getUnclippedBoundsInRoot()
        val body = text("draft-body").getUnclippedBoundsInRoot()
        assertTrue(input.left < send.left && input.bottom > body.bottom - 1.dp && send.top > body.bottom - 1.dp)
        // No Cc line when there is no Cc.
    }

    @Test fun anEmailWithoutACcShowsNoCcLine() {
        show(FakeApi(view(cc = emptyList())))
        text("draft-field:cc").assertDoesNotExist()
        text("draft-field:to").assertExists()
    }

    @Test fun theCardShowsTheCallsFieldsBeforeHermesAnswersAndWaitsToBeUsed() {
        val model = EmailDraftModel(id, FakeApi(view()).apply { current = view() }, scope, EmailDraft.seed(id, JSONObject().put("to", JSONArray(listOf(maya))).put("subject", "Seeded subject").put("body", "Seeded body")))
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { EmailDraftCard(model, Chat().ask, {}) } }
        // The seed is on screen at once; the controls follow when Hermes' own copy has arrived.
        text("draft-value:subject").assertExists()
        compose.waitUntil(5_000) { model.state.value.loaded }
        text("draft-value:subject").assertTextEquals("Friday review")
        text("draft-confirm").assertIsEnabled()
    }

    @Test fun aChangeRedraftsTheSameCardAndItsVersionsCanBeSteppedThrough() {
        val api = FakeApi(view())
        api.revise = { words, _ -> assertEquals("make it shorter", words); view(version = 2, versions = 2, body = "Hi Maya, Friday instead?") }
        api.step = { _, by -> if (by < 0) view(version = 1, versions = 2) else view(version = 2, versions = 2, body = "Hi Maya, Friday instead?") }
        show(api)
        text("draft-previous").assertDoesNotExist()
        text("draft-revise-go").assertIsNotEnabled()
        text("draft-revise-input").performTextInput("make it shorter")
        text("draft-revise-go").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runCatching { text("draft-body").assertTextContains("Friday instead?", substring = true) }.isSuccess }
        text("draft-version").assertTextEquals("2 of 2")
        // The field is empty again and the same card is still there with its Send.
        compose.waitUntil(5_000) { runCatching { text("draft-revise-input").assert(!hasText("make it shorter")) }.isSuccess }
        text("draft-confirm").assertIsEnabled()
        text("draft-previous").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runCatching { text("draft-version").assertTextEquals("1 of 2") }.isSuccess }
        text("draft-body").assertTextContains("Can we move the review to Friday?", substring = true)
        text("draft-next").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { runCatching { text("draft-body").assertTextContains("Friday instead?", substring = true) }.isSuccess }
        assertEquals(listOf("read", "revise:make it shorter", "step:-1", "step:1"), api.calls.toList())
    }

    @Test fun aRedraftThatFailsKeepsTheCardAndSaysSo() {
        val api = FakeApi(view())
        api.revise = { _, _ -> throw dev.hermes.android.data.ApiFailure(502, "Hermes could not redraft this. Try again.", true) }
        show(api)
        text("draft-revise-input").performTextInput("rewrite it")
        text("draft-revise-go").performClick()
        awaitTag("draft-problem")
        text("draft-problem").assertTextEquals("Couldn't redraft. Try again.")
        // The owner's words are still there to try again.
        text("draft-revise-input").assert(hasText("rewrite it"))
        text("draft-body").assertTextContains("Can we move the review to Friday?", substring = true)
        text("draft-confirm").assertIsEnabled()
    }

    @Test fun sendShowsSendingThenTheHonestResult() {
        val api = FakeApi(view())
        api.send = { view(status = "sending") }
        show(api)
        text("draft-confirm").performClick()
        awaitTag("draft-progress")
        text("draft-status").assertTextEquals("Sending…")
        // The revise field and Send are gone while it sends.
        text("draft-confirm").assertDoesNotExist()
        text("draft-revise-input").assertDoesNotExist()
        api.current = view(status = "sent")
        awaitTag("draft-done")
        text("draft-status").assertTextContains("Sent", substring = true)
        text("draft-revise-input").assertDoesNotExist()
        text("draft-confirm").assertDoesNotExist()
    }

    @Test fun aSendThatDidNotWorkSaysWhyAndOffersTryAgain() {
        val api = FakeApi(view())
        api.send = { view(status = "failed", reason = "approval") }
        show(api)
        text("draft-confirm").performClick()
        awaitTag("draft-problem")
        text("draft-problem").assertTextEquals("Hermes needs your approval to send this. Approve it in Hermes, then try again.")
        text("draft-confirm").assertTextEquals("Try again").assertIsEnabled()
        // It can still be reworded.
        text("draft-revise-input").assertIsEnabled()
    }

    @Test fun aSendHermesCouldNotConfirmNeverSaysSent() {
        val api = FakeApi(view())
        api.send = { view(status = "unsure", reason = "unconfirmed") }
        show(api)
        text("draft-confirm").performClick()
        awaitTag("draft-problem")
        text("draft-problem").assertTextContains("Couldn't confirm it was sent", substring = true)
        text("draft-confirm").assertTextEquals("Send again")
        text("draft-done").assertDoesNotExist()
    }

    @Test fun withNoWayToSendTheCardOffersCopyInsteadOfSend() {
        val copied = mutableListOf<String>()
        show(FakeApi(view(canSend = false, via = "")), copied = copied)
        text("draft-confirm").assertDoesNotExist()
        text("draft-problem").assertTextEquals("Hermes can't send email here.")
        text("draft-copy").assertIsEnabled().performClick()
        assertEquals(listOf("To: $maya\nCc: $dan\nSubject: Friday review\n\nHi Maya,\n\nCan we move the review to Friday?\n\nThanks"), copied)
    }

    @Test fun whenHermesSendsItItselfTheTapAsksItInTheChatAndABusyComposerStopsIt() {
        val chat = Chat(free = false)
        val api = FakeApi(view(via = "hermes"))
        api.send = { view(status = "sending", via = "hermes") }
        show(api, chat)
        text("draft-confirm").performClick()
        awaitTag("draft-problem")
        text("draft-problem").assertTextEquals("Send or clear your message first.")
        assertEquals(listOf("read"), api.calls.toList())
        chat.free = true
        text("draft-confirm").performClick()
        compose.waitUntil(5_000) { chat.said.isNotEmpty() }
        assertEquals(listOf("Send the email \"Friday review\""), chat.said.toList())
    }

    @Test fun aLongEmailFoldsAndOpens() {
        val body = (1..40).joinToString("\n") { "Line $it of a long email body." }
        show(FakeApi(view(body = body)))
        awaitTag("draft-more")
        text("draft-more").assertTextEquals("Show more").performClick()
        text("draft-more").assertTextEquals("Show less")
        text("draft-body").assertTextContains("Line 40 of a long email body.", substring = true)
    }

    @Test fun aNarrowPhoneKeepsTheFieldAndSendOnScreen() {
        show(FakeApi(view(subject = "A rather long subject line that needs more than one row to be read in full")), width = 320)
        text("draft-confirm").assertIsDisplayed()
        text("draft-revise-input").assertIsDisplayed()
        val send = text("draft-confirm").getUnclippedBoundsInRoot()
        assertTrue("Send stays inside a 320 dp card", send.right <= 320.dp)
        assertTrue("Send keeps a comfortable touch target", send.bottom - send.top >= 48.dp)
    }

    // ---- the card in a real chat ---------------------------------------------------------------------------------------------------

    private val showChat = mutableStateOf<BeaconViewModel?>(null)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun inChat(api: FakeApi, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val store = ViewModelStore()
        val chatScope = fixture.scope.session("chat-a")
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a")
        val priorCache = runBlocking { cacheKinds.associateWith { app.cache.read(chatScope, it) } }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                cacheKinds.forEach { app.cache.delete(chatScope, it) }
            }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            constructed = vm
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", chatScope)
                flow<AppState>(vm, "_ui").value = AppState(origin = chatScope.origin, owner = chatScope.owner, profile = chatScope.profile, authenticated = true, connected = true, restoringSession = false)
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id"); put("model_provider", string("provider")) }
                val call = JSONObject().put("id", "call-1").put("type", "function").put("function", JSONObject().put("name", "beacon_email_draft")
                    .put("arguments", JSONObject().put("to", JSONArray(listOf(maya))).put("cc", JSONArray(listOf(dan))).put("subject", "Friday review").put("body", "Hi Maya,\n\nCan we move the review to Friday?\n\nThanks").toString()))
                val rows = JSONArray()
                    .put(JSONObject().put("id", "m1").put("role", "user").put("content", "Write Maya about moving the review to Friday"))
                    .put(JSONObject().put("id", "m2").put("role", "assistant").put("content", "").put("tool_calls", JSONArray().put(call)))
                    .put(JSONObject().put("id", "m3").put("role", "tool").put("tool_call_id", "call-1").put("content", JSONObject().put("draft_id", id).put("version", 1).put("status", "shown").toString()))
                    .put(JSONObject().put("id", "m4").put("role", "assistant").put("content", "The draft is ready."))
                snapshot.put("messages", rows)
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = (0 until rows.length()).map { Message.from(rows.getJSONObject(it), it, "chat-a") })
                vm.storeData("profile-settings", json("send_key" to "enter"))
                // The card's Hermes is the fake: the store hands this model to the chat.
                vm.emailDrafts.model(chatScope.copy(session = "").key, id, EmailDraft(id)) { api }
            }
            showChat.value = vm
            compose.setContent { NativeLocalization("en") { BeaconTheme { showChat.value?.let { ChatScreen(it) } } } }
            block(vm, fixture)
        } finally {
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                priorCache.forEach { (kind, value) -> value?.let { app.cache.write(chatScope, kind, it, true) } ?: app.cache.delete(chatScope, kind) }
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    @Test fun anEmailThatHermesDraftedAppearsAsACardInTheChatNotAsAStep() = inChat(FakeApi(view())) { _, _ ->
        awaitTag("draft-card")
        text("draft-value:subject").assertTextEquals("Friday review")
        text("draft-confirm").assertExists()
        // The draft is not folded into the activity as an ordinary tool step.
        compose.onAllNodesWithTag("tool-card", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("tool-steps", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aChangeInTheChatRedraftsTheCardWithoutAddingToTheConversation() {
        val api = FakeApi(view())
        api.revise = { _, _ -> view(version = 2, versions = 2, body = "Short version.") }
        inChat(api) { vm, fixture ->
            awaitTag("draft-card")
            val messages = vm.chat.value.messages.size
            text("draft-revise-input").performTextInput("make it short")
            text("draft-revise-go").performClick()
            compose.waitUntil(15_000) { runCatching { text("draft-body").assertTextContains("Short version.", substring = true) }.isSuccess }
            assertEquals(messages, vm.chat.value.messages.size)
            assertTrue("A change never starts a turn", fixture.requests.none { it.string("method") == "prompt.submit" })
        }
    }

    @Test fun sendThatHermesDoesItselfSaysSendTheEmailInTheChat() {
        val api = FakeApi(view(via = "hermes"))
        api.send = { view(status = "sending", via = "hermes") }
        inChat(api) { vm, fixture ->
            awaitTag("draft-card")
            compose.waitUntil(15_000) { vm.preferences.value.busySendLoaded }
            text("draft-confirm").performClick()
            compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } }
            val submitted = fixture.requests.first { it.string("method") == "prompt.submit" }.getJSONObject("params")
            assertEquals("Send the email “Friday review”", submitted.string("text"))
            // A card that is sending asks Hermes how it went every second or two; the two calls that matter come first.
            assertEquals(listOf("read", "send:1"), api.calls.take(2))
        }
    }

    @Test fun sendWithAMailToolSaysNothingInTheChat() {
        val api = FakeApi(view(via = "tool"))
        api.send = { view(status = "sending") }
        inChat(api) { _, fixture ->
            awaitTag("draft-card")
            text("draft-confirm").performClick()
            awaitTag("draft-progress")
            compose.waitForIdle()
            assertTrue(fixture.requests.none { it.string("method") == "prompt.submit" })
            api.current = view(status = "sent")
            awaitTag("draft-done")
        }
    }
}
