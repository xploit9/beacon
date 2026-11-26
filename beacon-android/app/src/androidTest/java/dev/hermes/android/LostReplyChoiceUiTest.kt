package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
import dev.hermes.android.ui.ChatScreen
import dev.hermes.android.ui.SessionList
import dev.hermes.android.ui.TeammateRow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * WEDGE-121 follow-up 2: the view model and the screen around a reply Hermes lost in a restart, with a synthetic canonical Hermes. A look at the
 * chat (what a reconnect, a receipt or an open makes) leaves the lost reply alone while the owner's message waits, and shows the choice; Send lets
 * it go (prompt.resolve_unknown) and then the waiting message is sent. With nothing waiting, the look closes it at once. Synthetic text only.
 */
class LostReplyChoiceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val rendered = mutableStateOf<BeaconViewModel?>(null)
    private val listShown = mutableStateOf(false)
    private val botRows = mutableStateOf(false)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) {
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun lost(generation: Int = 5) = JSONArray().put(json("admission_id" to "lost-a", "status" to "unknown", "outcome" to JSONObject.NULL,
        "execution_generation" to generation, "sequence" to 1, "authority_epoch" to 1, "input_id" to "lost-a", "text" to "Synthetic cut request"))

    /** A passive look at chat-a's lost reply, as the reconnect projection makes it (fresh evidence). */
    private fun look(vm: BeaconViewModel, scope: Scope) {
        BeaconViewModel::class.java.getDeclaredMethod("recoverLostTurn", Scope::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(vm, scope, true, false, true)
    }

    private fun submitted(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("method") == "prompt.submit" }.map { it.getJSONObject("params").string("text") }

    private fun withFixture(block: (BeaconViewModel, NativeExperienceFixture, Scope) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture().apply { canonicalAuthority = true }
        val store = ViewModelStore()
        val scope = fixture.scope.session("chat-a")
        val cacheKinds = listOf("pending", "draft", "session", "running-turn", "live:runtime-chat-a", "live:chat-a", "live:runtime-chat-b", "live:chat-b")
        val cached = listOf(scope, fixture.scope.session("chat-b")).flatMap { owner -> cacheKinds.map { owner to it } }
        val priorCache = runBlocking { cached.associateWith { (owner, kind) -> app.cache.read(owner, kind) } }
        lateinit var vm: BeaconViewModel
        var constructed: BeaconViewModel? = null
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = ""; it.remove(stringPreferencesKey("busy_send_action")) }
                app.auth.persist(fixture.client.auth)
                cached.forEach { (owner, kind) -> app.cache.delete(owner, kind) }
            }
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            constructed = vm
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", scope)
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, owner = scope.owner, profile = scope.profile, authenticated = true, connected = true, restoringSession = false)
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id"); put("model_provider", string("provider")) }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
                vm.storeData("profile-settings", json("send_key" to "enter"))
            }
            rendered.value = vm
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { rendered.value?.let { current ->
                if (botRows.value) {
                    // Bot Mode's own row for a bot, marked as NativeBotMode marks it.
                    val ui by current.ui.collectAsState()
                    androidx.compose.foundation.layout.Column {
                        listOf("bot-a", "bot-b").forEach { bot -> TeammateRow(bot, bot, "Synthetic role", false, emptyList(), "bot-row-$bot", cutOff = botCutOff(ui, bot)) {} }
                    }
                } else if (listShown.value) SessionList(current) else ChatScreen(current)
            } } } }
            block(vm, fixture, scope)
        } finally {
            fixture.releasePromptAcknowledgement()
            compose.runOnUiThread { constructed?.client?.closeNative(); store.clear(); fixture.client.closeNative() }
            runBlocking {
                cached.forEach { key -> priorCache[key]?.let { app.cache.write(key.first, key.second, it, true) } ?: app.cache.delete(key.first, key.second) }
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    @Test fun aWaitingMessageGetsTheChoiceAndSendLetsTheLostReplyGoThenSendsIt() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        val waiting = QueuedMessage("Send this after the restart")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(waiting)) }
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { vm.chat.value.lostWaiting }
        // The look let nothing go: Hermes still holds the lost reply, and the waiting message was not sent.
        assertTrue(fixture.resolvedLost.isEmpty())
        assertTrue(submitted(fixture).isEmpty())
        compose.onNodeWithTag("lost-reply-choice").assertIsDisplayed()
        // Exactly two lines (Root, follow-up 1): why, then what to do; the restart line once, and no third.
        compose.onAllNodesWithText("Hermes restarted during this reply.").assertCountEquals(1)
        compose.onNodeWithText("Hermes restarted during this reply.").assertIsDisplayed()
        compose.onNodeWithText("Your waiting message hasn't been sent.").assertIsDisplayed()
        compose.onNodeWithTag("lost-reply-send").assertTextEquals("Send it")
        compose.onAllNodesWithTag("transcript-status").assertCountEquals(0)
        compose.onNodeWithTag("queue-text:${waiting.id}").assertTextEquals("Send this after the restart")
        // The owner's Send: the lost reply goes with its own generation, then the waiting message is sent, and the choice goes away.
        compose.onNodeWithTag("lost-reply-send").performClick()
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        val resolve = fixture.requests.single { it.string("method") == "prompt.resolve_unknown" }.getJSONObject("params")
        assertEquals(5, resolve.getInt("execution_generation"))
        compose.waitUntil(20_000) { submitted(fixture) == listOf("Send this after the restart") }
        compose.waitUntil(15_000) { !vm.chat.value.lostWaiting }
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
    }

    /**
     * Chat join rules24 (G6b restart 17, F3): the follower the choice's Send sends answers once. Its answer streams and ends as Hermes ends a turn,
     * with the chat open all along: one saved answer, no live text, no stream left following and no Working (live: a second, time-less copy and
     * Working stayed until a reopen).
     */
    @Test fun aFollowerSentFromTheLostReplyChoiceAnswersOnceWithNothingLeftWorking() = withFixture { vm, fixture, _ ->
        val chat = fixture.scope.session("chat-b")
        val follower = "Synthetic follower behind the lost reply"
        val answer = "Synthetic answer to the follower"
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        runBlocking {
            // Marked, as this phone sent the cut turn; the follower waits in this phone's box.
            app.cache.write(chat, "running-turn", "synthetic-submission")
            // As live: the cut turn's own copy stays in the box as accepted under the lost admission, the follower waits behind it.
            app.cache.write(chat, "draft", Draft(queued = listOf(QueuedMessage("Synthetic cut request", id = "synthetic-submission", state = "accepted",
                admissionId = "lost-a", admissionStatus = "started"), QueuedMessage(follower))).encode())
        }
        // Hermes' receipt for the lost admission: unknown until the Discard, then terminal/interrupted.
        val profileId = NativeExperienceFixture::class.java.getDeclaredMethod("canonicalProfileId", String::class.java).apply { isAccessible = true }
            .invoke(fixture, fixture.scope.profile) as String
        fixture.canonicalAdmissions["lost-a"] = json("admission_id" to "lost-a", "ref" to json("profile_id" to profileId, "session_id" to "chat-b"),
            "sequence" to 1, "status" to "unknown", "outcome" to JSONObject.NULL, "authority_epoch" to 1, "execution_generation" to 5)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.waitForIdle()
        val admittedBefore = fixture.canonicalAdmissions.keys.toSet()
        compose.onNodeWithTag("lost-reply-send").performClick()
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        fixture.canonicalAdmissions["lost-a"] = fixture.canonicalAdmissions.getValue("lost-a").copy().put("status", "terminal").put("outcome", "interrupted")
        compose.waitUntil(30_000) { submitted(fixture) == listOf(follower) }
        compose.waitUntil(15_000) { (fixture.canonicalAdmissions.keys - admittedBefore).size == 1 }
        val admission = (fixture.canonicalAdmissions.keys - admittedBefore).single()
        // The follower's answer streams as Hermes sends it...
        compose.waitUntil(15_000) { vm.chat.value.streamId.isNotBlank() && fixture.activeRuntimeForTest() != null }
        fixture.emitNativeEvent("message.delta", json("text" to answer))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains(answer) }
        // ...and ends as Hermes ends one: the turn is terminal and its done event carries the proof (both rows are in the saved history).
        val rows = fixture.sessions.getValue("chat-b").getJSONArray("messages")
        rows.put(json("id" to 9101, "role" to "user", "text" to follower)).put(json("id" to 9102, "role" to "assistant", "text" to answer))
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        fixture.emitNativeEvent("message.complete", json("text" to answer, "admission_id" to admission, "status" to "completed",
            "persisted_turn" to json("complete" to true, "user_row_id" to 9101, "user_row_ids" to JSONArray(listOf(9101)),
                "final_assistant_row_id" to 9102, "row_ids" to JSONArray(listOf(9101, 9102)))))
        compose.waitUntil(20_000) { vm.chat.value.messages.count { it.role == "assistant" && it.text == answer } == 1 }
        // Settled with the chat open: nothing paints the answer a second time or keeps working.
        Thread.sleep(8_000); compose.waitForIdle()
        val state = vm.chat.value
        val seen = "stream='${state.streamId}' status='${state.status}' live='${state.liveText}' reasoning=${state.liveReasoning.length} lostWaiting=${state.lostWaiting} " +
            "queued=${state.queued.map { it.text.take(20) + "/" + it.state }} answers=${state.messages.count { it.role == "assistant" && it.text == answer }} " +
            "methods=${fixture.requests.map { it.string("method").ifBlank { it.string("path") } }.takeLast(12)}"
        assertEquals("one saved answer: $seen", 1, state.messages.count { it.role == "assistant" && it.text == answer })
        assertEquals("no live copy left: $seen", "", state.liveText.trim())
        assertEquals("no stream left following: $seen", "", state.streamId)
        assertTrue("not working: $seen", state.status != "Working")
        compose.onAllNodesWithText(answer, substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Working").assertCountEquals(0)
    }

    /**
     * Chat join rules24 (G6b restart 17, F3), the cut turn's shape: its saved words and Terminal step, and the live frame the phone kept for it (keyed by the
     * runtime, which the follower's turn shares). The choice's Send settles the cut turn (a look restores that frame) and sends the follower, which Hermes
     * accepts "queued" without a row and claims; its user row is saved at the claim. A saved-chat read Hermes answered before that claim (nothing running, the
     * rows still ending in the cut turn) lands while the follower's reply streams, before its first reasoning frame. Pass, with the chat open all along: the
     * reply stays followed, and it ends with one saved answer, no live copy, no stream left following and no Working (live: a second, time-less copy, a
     * second Reasoning and Working stayed, with no stream followed and an idle composer).
     */
    @Test fun aSavedReadFromBeforeTheFollowersClaimLeavesItsReplyFollowedAndEndsOnce() = withFixture { vm, fixture, _ ->
        val chat = fixture.scope.session("chat-b")
        val follower = "Synthetic follower behind the lost reply"
        val answer = "Synthetic answer to the follower"
        val cutWords = "Synthetic words before the step"
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        // Hermes saves the claimed turn's user row when the turn starts, stamped with this phone's submission id.
        fixture.persistCanonicalUserRows = true
        // The cut turn as Hermes saved it: the request, then the words said before the Terminal step that never returned.
        fixture.sessions.getValue("chat-b").getJSONArray("messages")
            .put(json("id" to 9001, "role" to "user", "text" to "Synthetic cut request", "display_metadata" to json("submission_id" to "synthetic-submission")))
            .put(json("id" to 9002, "role" to "assistant", "text" to cutWords, "tool_calls" to JSONArray().put(json("id" to "call-cut-1", "function" to json("name" to "terminal", "arguments" to "{}")))))
        runBlocking {
            app.cache.write(chat, "running-turn", "synthetic-submission")
            app.cache.write(chat, "draft", Draft(queued = listOf(QueuedMessage("Synthetic cut request", id = "synthetic-submission", state = "accepted",
                admissionId = "lost-a", admissionStatus = "started"), QueuedMessage(follower))).encode())
        }
        val profileId = NativeExperienceFixture::class.java.getDeclaredMethod("canonicalProfileId", String::class.java).apply { isAccessible = true }
            .invoke(fixture, fixture.scope.profile) as String
        fixture.canonicalAdmissions["lost-a"] = json("admission_id" to "lost-a", "ref" to json("profile_id" to profileId, "session_id" to "chat-b"),
            "sequence" to 1, "status" to "unknown", "outcome" to JSONObject.NULL, "authority_epoch" to 1, "execution_generation" to 5)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.waitForIdle()
        // The runtime the open resumed: Hermes' canonical resume names the chat itself, for every turn of it.
        val runtime = "chat-b"
        // The live frame this phone kept for the cut turn (as attachStream saves it): its words, its reasoning, the Terminal step, the boundary that step closed.
        runBlocking {
            app.cache.write(chat, "live:$runtime", json("cursor" to "", "text" to cutWords, "reasoning" to "Synthetic cut reasoning",
                "tools" to JSONArray().put(json("id" to "call-cut-1", "name" to "terminal", "status" to "running")), "sealed" to JSONArray(), "sealed_at" to JSONArray(),
                "ordinal" to 1, "base" to 0, "base_prompt" to "", "error" to "", "terminal" to false, "wait" to "", "submission_id" to "synthetic-submission",
                "boundary_closed" to true, "boundary_tool_ids" to JSONArray().put("call-cut-1")).toString())
        }
        // What a saved-chat read answers once the lost reply is let go and before Hermes claims the follower: nothing running, nothing pending.
        val beforeClaim = runBlocking { requireNotNull(vm.client).get("/api/session", mapOf("session_id" to "chat-b")) }
        (beforeClaim.optJSONObject("session") ?: beforeClaim).apply { remove("pending"); remove("active_stream_id"); put("running", false) }
        val admittedBefore = fixture.canonicalAdmissions.keys.toSet()
        compose.onNodeWithTag("lost-reply-send").performClick()
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        fixture.canonicalAdmissions["lost-a"] = fixture.canonicalAdmissions.getValue("lost-a").copy().put("status", "terminal").put("outcome", "interrupted")
        compose.waitUntil(30_000) { submitted(fixture) == listOf(follower) }
        compose.waitUntil(15_000) { (fixture.canonicalAdmissions.keys - admittedBefore).size == 1 }
        val admission = (fixture.canonicalAdmissions.keys - admittedBefore).single()
        runCatching { compose.waitUntil(15_000) { vm.chat.value.streamId == runtime && fixture.activeRuntimeForTest() != null } }.onFailure {
            val c = vm.chat.value
            throw AssertionError("follower not followed: stream='${c.streamId}' runtime='$runtime' active=${fixture.activeRuntimeForTest()} status='${c.status}' " +
                "live=${c.liveText.length} lostWaiting=${c.lostWaiting} queued=${c.queued.map { q -> q.text.take(16) + "/" + q.state + "/" + q.rowId }} " +
                "turn=${fixture.nativeTurnStatus} methods=${fixture.requests.map { r -> r.string("method").ifBlank { r.string("path") } }.takeLast(14)}", it)
        }
        // The claimed turn's first frame: the phone reads its saved row and the copy takes it.
        fixture.emitNativeEvent("message.start", JSONObject())
        compose.waitUntil(15_000) { vm.chat.value.messages.any { it.role == "user" && it.text == follower } }
        compose.waitForIdle()
        // The read answered before the claim lands now (as an unguarded saved-chat read does).
        val render = BeaconViewModel::class.java.declaredMethods.first { it.name == "renderSession" && it.parameterTypes.size == 6 }.apply { isAccessible = true }
        runBlocking(kotlinx.coroutines.Dispatchers.Main) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont -> render.invoke(vm, beforeClaim, chat, null, false, "", cont) }
        }
        compose.waitForIdle()
        val followedDuring = vm.chat.value.streamId
        // The follower's reply streams: reasoning first, then its words...
        fixture.emitNativeEvent("reasoning.delta", json("text" to "Synthetic follower reasoning"))
        fixture.emitNativeEvent("message.delta", json("text" to answer))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains(answer) }
        // ...and ends as Hermes ends one: the turn is terminal and its done event carries the proof (both rows are in the saved history).
        val rows = fixture.sessions.getValue("chat-b").getJSONArray("messages")
        val userRow = (0 until rows.length()).map(rows::getJSONObject).last { it.string("role") == "user" && it.string("text") == follower }
        val userRowId = userRow.opt("row_id") ?: userRow.opt("id")
        rows.put(json("id" to 9102, "role" to "assistant", "text" to answer))
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        fixture.emitNativeEvent("message.complete", json("text" to answer, "admission_id" to admission, "status" to "completed", "reasoning" to "Synthetic follower reasoning",
            "persisted_turn" to json("complete" to true, "user_row_id" to userRowId, "user_row_ids" to JSONArray(listOf(userRowId)),
                "final_assistant_row_id" to 9102, "row_ids" to JSONArray(listOf(userRowId, 9102)))))
        compose.waitUntil(20_000) { vm.chat.value.messages.count { it.role == "assistant" && it.text == answer } == 1 }
        Thread.sleep(8_000); compose.waitForIdle()
        val state = vm.chat.value
        val seen = "followedDuring='$followedDuring' stream='${state.streamId}' status='${state.status}' live='${state.liveText}' reasoning=${state.liveReasoning.length} " +
            "queued=${state.queued.map { it.text.take(20) + "/" + it.state }} answers=${state.messages.count { it.role == "assistant" && it.text == answer }}"
        assertEquals("the reply stays followed while it streams: $seen", runtime, followedDuring)
        assertEquals("one saved answer: $seen", 1, state.messages.count { it.role == "assistant" && it.text == answer })
        assertEquals("no live copy left: $seen", "", state.liveText.trim())
        assertEquals("no live reasoning left: $seen", "", state.liveReasoning.trim())
        assertEquals("no stream left following: $seen", "", state.streamId)
        assertTrue("not working: $seen", state.status != "Working")
        compose.onAllNodesWithText(answer, substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Working").assertCountEquals(0)
    }

    /** Root review (26 Nov): the caller's expected revision is checked before the bump; after the decode the render must compare the revision it bumped, or every explicit-revision render is dropped. */
    @Test fun anExplicitRevisionRenderStillShowsItsMessages() = withFixture { vm, fixture, scope ->
        val text = "Synthetic explicit revision render"
        val response = runBlocking { requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session)) }
        val session = response.optJSONObject("session") ?: response
        session.put("messages", JSONArray().put(json("role" to "assistant", "text" to text)))
        val revision = BeaconViewModel::class.java.getDeclaredField("chatHistoryRevision").apply { isAccessible = true }.getLong(vm)
        val render = BeaconViewModel::class.java.declaredMethods.first { it.name == "renderSession" && it.parameterTypes.size == 6 }.apply { isAccessible = true }
        runBlocking(kotlinx.coroutines.Dispatchers.Main) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { cont -> render.invoke(vm, response, scope, revision, false, "", cont) }
        }
        assertEquals("an explicit-revision render shows the decoded messages", 1, vm.chat.value.messages.count { it.text == text })
    }

    /** F1: a slow older render must not record its projection after a newer null-revision render has won. */
    @Test fun aSupersededSlowRenderCannotBecomeTheNewestProjection() = withFixture { vm, _, scope ->
        fun response(title: String, padding: String): JSONObject {
            val raw = runBlocking { requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session)) }
            val session = raw.optJSONObject("session") ?: raw
            session.put("title", title)
            session.put("messages", JSONArray().put(json("role" to "assistant", "text" to title)))
            if (padding.isNotEmpty()) session.put("padding", padding)
            return raw
        }
        val older = response("Older projection", "o".repeat(8_000_000))
        val newer = response("Newer projection", "")
        val render = BeaconViewModel::class.java.declaredMethods.first { it.name == "renderSession" && it.parameterTypes.size == 6 }.apply { isAccessible = true }
        suspend fun renderNow(value: JSONObject) {
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
                render.invoke(vm, value, scope, null, false, "", cont)
            }
        }
        val jobs = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val olderJob = jobs.launch { renderNow(older) }
        Thread.sleep(20)
        val newerJob = jobs.launch { renderNow(newer) }
        runBlocking { olderJob.join(); newerJob.join() }
        val field = BeaconViewModel::class.java.getDeclaredField("renderedServerProjection").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val projection = field.get(vm) as Pair<Scope, String>
        assertTrue("the newer projection survives", projection.second.contains("Newer projection"))
        assertFalse("the superseded projection is not recorded", projection.second.contains("Older projection"))
    }

    /** rules24 (b): the chat's live state, to put it in the state restart 17 ended in (whatever the cause). */
    @Suppress("UNCHECKED_CAST")
    private fun chatFlow(vm: BeaconViewModel) =
        BeaconViewModel::class.java.getDeclaredField("_chat").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ChatState>

    /** rules24 (b): the clean path up to the follower's reply streaming (the choice's Send let the lost reply go and sent it). Its admission. */
    private fun followerStreaming(vm: BeaconViewModel, fixture: NativeExperienceFixture, follower: String): String {
        val chat = fixture.scope.session("chat-b")
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        fixture.persistCanonicalUserRows = true
        runBlocking {
            app.cache.write(chat, "running-turn", "synthetic-submission")
            app.cache.write(chat, "draft", Draft(queued = listOf(QueuedMessage("Synthetic cut request", id = "synthetic-submission", state = "accepted",
                admissionId = "lost-a", admissionStatus = "started"), QueuedMessage(follower))).encode())
        }
        val profileId = NativeExperienceFixture::class.java.getDeclaredMethod("canonicalProfileId", String::class.java).apply { isAccessible = true }
            .invoke(fixture, fixture.scope.profile) as String
        fixture.canonicalAdmissions["lost-a"] = json("admission_id" to "lost-a", "ref" to json("profile_id" to profileId, "session_id" to "chat-b"),
            "sequence" to 1, "status" to "unknown", "outcome" to JSONObject.NULL, "authority_epoch" to 1, "execution_generation" to 5)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.waitForIdle()
        val admittedBefore = fixture.canonicalAdmissions.keys.toSet()
        compose.onNodeWithTag("lost-reply-send").performClick()
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        fixture.canonicalAdmissions["lost-a"] = fixture.canonicalAdmissions.getValue("lost-a").copy().put("status", "terminal").put("outcome", "interrupted")
        compose.waitUntil(30_000) { submitted(fixture) == listOf(follower) }
        compose.waitUntil(15_000) { (fixture.canonicalAdmissions.keys - admittedBefore).size == 1 }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-b" && fixture.activeRuntimeForTest() != null }
        fixture.emitNativeEvent("message.start", JSONObject())
        compose.waitUntil(15_000) { vm.chat.value.messages.any { it.role == "user" && it.text == follower } }
        return (fixture.canonicalAdmissions.keys - admittedBefore).single()
    }

    /** rules24 (b): the follower's turn ends as Hermes ends one: its reply saved, the admission terminal, the done event with the proof. */
    private fun followerEnds(fixture: NativeExperienceFixture, follower: String, answer: String, admission: String) {
        val rows = fixture.sessions.getValue("chat-b").getJSONArray("messages")
        val userRow = (0 until rows.length()).map(rows::getJSONObject).last { it.string("role") == "user" && it.string("text") == follower }
        val userRowId = userRow.opt("row_id") ?: userRow.opt("id")
        rows.put(json("id" to 9102, "role" to "assistant", "text" to answer))
        fixture.setCanonicalAdmission(admission, "terminal", "completed")
        fixture.emitNativeEvent("message.complete", json("text" to answer, "admission_id" to admission, "status" to "completed",
            "persisted_turn" to json("complete" to true, "user_row_id" to userRowId, "user_row_ids" to JSONArray(listOf(userRowId)),
                "final_assistant_row_id" to 9102, "row_ids" to JSONArray(listOf(userRowId, 9102)))))
    }

    /** rules24 (b): one saved answer, nothing live left, no stream followed, not Working, one bubble and no Working row on screen. */
    private fun assertAnsweredOnce(vm: BeaconViewModel, answer: String, note: String) {
        val state = vm.chat.value
        val seen = "$note stream='${state.streamId}' status='${state.status}' live='${state.liveText}' reasoning=${state.liveReasoning.length} " +
            "answers=${state.messages.count { it.role == "assistant" && it.text == answer }}"
        assertEquals("one saved answer: $seen", 1, state.messages.count { it.role == "assistant" && it.text == answer })
        assertEquals("no live copy left: $seen", "", state.liveText.trim())
        assertEquals("no live reasoning left: $seen", "", state.liveReasoning.trim())
        assertEquals("no stream left following: $seen", "", state.streamId)
        assertTrue("not working: $seen", state.status != "Working")
        compose.onAllNodesWithText(answer, substring = true).assertCountEquals(1)
        compose.onAllNodesWithText("Working").assertCountEquals(0)
    }

    /** rules24 (b): a reply whose stream id goes blank midway with no end stays followed (the stream that owns the chat binds it again) and ends once. */
    @Test fun aReplyWhoseStreamIdGoesBlankMidwayStaysFollowedAndEndsOnce() = withFixture { vm, fixture, _ ->
        val follower = "Synthetic follower behind the lost reply"
        val answer = "Synthetic answer to the follower"
        val admission = followerStreaming(vm, fixture, follower)
        fixture.emitNativeEvent("reasoning.delta", json("text" to "Synthetic follower reasoning"))
        fixture.emitNativeEvent("message.delta", json("text" to "Synthetic answer"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Synthetic answer") }
        // The stream id goes blank with no end (restart 17's state, whatever causes it).
        chatFlow(vm).let { f -> f.value = f.value.copy(streamId = "") }
        fixture.emitNativeEvent("message.delta", json("text" to " to the follower"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains(answer) }
        val followedDuring = vm.chat.value.streamId
        followerEnds(fixture, follower, answer, admission)
        compose.waitUntil(20_000) { vm.chat.value.messages.count { it.role == "assistant" && it.text == answer } == 1 }
        Thread.sleep(6_000); compose.waitForIdle()
        assertAnsweredOnce(vm, answer, "followedDuring='$followedDuring'")
        assertEquals("the reply stays followed while it streams", "chat-b", followedDuring)
    }

    /** rules24 (b): a live copy left with no stream bound once the turn ended is dropped at once: the saved chat holds that turn's end. */
    @Test fun aLiveCopyLeftWithNoStreamIsDroppedAtOnceWhenTheSavedChatHoldsItsEnd() = withFixture { vm, fixture, _ ->
        val follower = "Synthetic follower behind the lost reply"
        val answer = "Synthetic answer to the follower"
        val admission = followerStreaming(vm, fixture, follower)
        fixture.emitNativeEvent("message.delta", json("text" to answer))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains(answer) }
        followerEnds(fixture, follower, answer, admission)
        compose.waitUntil(20_000) { vm.chat.value.messages.count { it.role == "assistant" && it.text == answer } == 1 && vm.chat.value.streamId.isBlank() }
        compose.waitForIdle()
        // Restart 17's end state: the reply's words and reasoning painted again, no stream bound, an idle composer.
        chatFlow(vm).let { f -> f.value = f.value.copy(liveText = answer, liveReasoning = "Synthetic follower reasoning", streamId = "", status = "") }
        runCatching { compose.waitUntil(15_000) { vm.chat.value.liveText.isBlank() && vm.chat.value.liveReasoning.isBlank() } }
        compose.waitForIdle()
        assertAnsweredOnce(vm, answer, "dropped:")
    }

    /** rules24 (b): a live copy left with no stream bound while Hermes still runs the turn is followed again, and the turn then ends once. */
    @Test fun aLiveCopyLeftWithNoStreamWhileTheTurnRunsIsFollowedAgainAndEndsOnce() = withFixture { vm, fixture, _ ->
        val follower = "Synthetic follower behind the lost reply"
        val answer = "Synthetic answer to the follower"
        val admission = followerStreaming(vm, fixture, follower)
        fixture.emitNativeEvent("message.delta", json("text" to "Synthetic answer"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Synthetic answer") }
        // The stream job is gone and the stream id blank, the words still painted, while Hermes runs the turn. The session watcher is stopped too: it re-follows
        // a queued copy's turn on its next tick (a timer); the state rule must do it by itself, at once.
        @Suppress("UNCHECKED_CAST")
        val streams = BeaconViewModel::class.java.getDeclaredField("streams").apply { isAccessible = true }.get(vm) as MutableMap<String, kotlinx.coroutines.Job>
        val key = fixture.scope.session("chat-b").key
        val watcher = BeaconViewModel::class.java.getDeclaredField("sessionWatch").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.Job?
        runBlocking { watcher?.let { it.cancel(); it.join() }; streams[key]?.let { it.cancel(); it.join() } }
        compose.waitUntil(10_000) { streams[key]?.isActive != true }
        chatFlow(vm).let { f -> f.value = f.value.copy(streamId = "", status = "") }
        runCatching { compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-b" && streams[key]?.isActive == true } }
        val followedAgain = vm.chat.value.streamId
        fixture.emitNativeEvent("message.delta", json("text" to " to the follower"))
        runCatching { compose.waitUntil(15_000) { vm.chat.value.liveText.contains(answer) } }
        followerEnds(fixture, follower, answer, admission)
        runCatching { compose.waitUntil(20_000) { vm.chat.value.messages.count { it.role == "assistant" && it.text == answer } == 1 } }
        Thread.sleep(6_000); compose.waitForIdle()
        assertEquals("followed again while Hermes runs the turn", "chat-b", followedAgain)
        assertAnsweredOnce(vm, answer, "followedAgain='$followedAgain'")
    }

    @Test fun removingTheWaitingMessageLetsTheLoneLostReplyClose() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        val waiting = QueuedMessage("Never mind this one")
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(waiting)) }
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { vm.chat.value.lostWaiting }
        compose.onNodeWithTag("queue-remove:${waiting.id}").performClick()
        // Nothing waits now: the look closes the lost reply by itself, the choice goes, and nothing is sent.
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(15_000) { !vm.chat.value.lostWaiting }
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(submitted(fixture).isEmpty())
    }

    @Test fun withNothingWaitingALookClosesTheLostReplyAndOffersNoChoice() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        // One plain line, no question (Root, follow-up 2): the cut reply is named once.
        compose.waitUntil(15_000) { vm.chat.value.status == "Hermes restarted during this reply." }
        assertFalse(vm.chat.value.lostWaiting)
        compose.onNodeWithText("Hermes restarted during this reply.").assertIsDisplayed()
        compose.onAllNodesWithText("Your waiting message hasn't been sent.").assertCountEquals(0)
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(submitted(fixture).isEmpty())
    }

    /** Root's plain line on any path, not only the look above: the app comes back after Hermes restarted mid-reply (a cold run marker, no stream on
     *  screen), and the foreground read closes the lone lost reply as it settles the turn. One plain line, no question, nothing sent. */
    @Test fun aLoneLostReplyClosedOnComingBackToTheAppSaysSoOnce() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        runBlocking { app.cache.write(scope, "running-turn", "synthetic-submission") }
        compose.runOnIdle { vm.foreground(false) }
        compose.runOnIdle { vm.foreground(true) }
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(20_000) { vm.chat.value.status == "Hermes restarted during this reply." }
        assertFalse(vm.chat.value.lostWaiting)
        compose.onNodeWithText("Hermes restarted during this reply.").assertIsDisplayed()
        compose.onAllNodesWithText("Your waiting message hasn't been sent.").assertCountEquals(0)
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(submitted(fixture).isEmpty())
    }

    /**
     * Follow-up 4 (Root): a chat this phone sent into still holds its run marker after the app was closed through a restart, and its box still holds
     * the person's own cut message as accepted. Opening it lets the lone lost reply go from the route, as any open does: prompt.resolve_unknown, no
     * session.resume (Hermes builds no agent), and the marker goes. Nothing resumes it afterwards either (two of the chat watcher's checks).
     */
    @Test fun aMarkedChatsLoneLostReplyIsLetGoOnOpenWithoutAResume() = withFixture { vm, fixture, _ ->
        val chat = fixture.scope.session("chat-b")
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        runBlocking {
            app.cache.write(chat, "running-turn", "synthetic-submission")
            app.cache.write(chat, "draft", Draft(queued = listOf(QueuedMessage("Synthetic cut request", id = "synthetic-submission", state = "accepted",
                admissionId = "lost-a", admissionStatus = "started"))).encode())
        }
        val before = fixture.requests.size
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.status == "Hermes restarted during this reply." }
        compose.waitUntil(15_000) { runBlocking { app.cache.read(chat, "running-turn") } == null }
        Thread.sleep(12_000)
        val after = fixture.requests.drop(before)
        val seen = after.map { it.string("method").ifBlank { it.string("path") } }
        assertTrue("closing a dead turn builds no agent, then or later: $seen", after.none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
        assertTrue("the cut copy ended with its admission", vm.chat.value.queued.none { it.admissionId == "lost-a" && it.state in setOf("accepted", "unknown") })
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
    }

    /** Follow-up 4 (Root): the same on coming back to the app with the chat open: the run marker's look asks the route first and resumes nothing. */
    @Test fun aMarkedLoneLostReplyIsLetGoOnComingBackWithoutAResume() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        fixture.lostChats = mapOf(scope.session to 0)
        runBlocking { app.cache.write(scope, "running-turn", "synthetic-submission") }
        val before = fixture.requests.size
        compose.runOnIdle { vm.foreground(false) }
        compose.runOnIdle { vm.foreground(true) }
        compose.waitUntil(20_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(20_000) { vm.chat.value.status == "Hermes restarted during this reply." }
        compose.waitUntil(15_000) { runBlocking { app.cache.read(scope, "running-turn") } == null }
        assertTrue("closing a dead turn builds no agent", fixture.requests.drop(before).none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(submitted(fixture).isEmpty())
    }

    private fun resumedOrClosed(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("method") in setOf("session.resume", "prompt.resolve_unknown", "prompt.submit") }
    private fun lostTurnReads(fixture: NativeExperienceFixture) = fixture.requests.filter { it.string("path") == "/api/plugins/beacon/mobile/lost-turns" }

    /**
     * Follow-up 4 (replaces follow-up 3's "plain line, nothing let go"): opening a chat whose lone reply Hermes lost lets it go at once, the way
     * Hermes' Discard does (prompt.resolve_unknown with its own admission and generation; no resume, no agent), then shows the plain line.
     */
    @Test fun openingAChatHoldingALoneLostReplyLetsItGoAndShowsThePlainLine() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.status == "Hermes restarted during this reply." }
        val resolve = fixture.requests.single { it.string("method") == "prompt.resolve_unknown" }.getJSONObject("params")
        assertEquals("chat-b", resolve.string("session_id"))
        assertEquals(5, resolve.getInt("execution_generation"))
        compose.onNodeWithText("Hermes restarted during this reply.").assertIsDisplayed()
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(lostTurnReads(fixture).any { it.string("query").contains("chat-b") })
        assertFalse("its mark goes with it", "${fixture.scope.profile}:chat-b" in vm.ui.value.lostChats)
        assertTrue("closing a dead turn is not a resume", fixture.requests.none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
    }

    /**
     * Root (follow-up 4): the close on open happens only when Hermes holds nothing behind the lost reply AND the phone's box holds nothing
     * waiting, because Hermes' Discard resumes its paused queue. Here the route says nothing waits at Hermes, but the box holds a message: the
     * choice shows and nothing is let go.
     */
    @Test fun openingAChatWhoseBoxHoldsAMessageOffersTheChoiceAndLetsNothingGo() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        runBlocking { app.cache.write(fixture.scope.session("chat-b"), "draft", Draft(queued = listOf(QueuedMessage("Waiting in the box"))).encode()) }
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.waitUntil(15_000) { lostTurnReads(fixture).any { it.string("query").contains("chat-b") } }
        compose.waitForIdle()
        compose.onNodeWithTag("lost-reply-choice").assertIsDisplayed()
        assertTrue("nothing is let go while a message waits", fixture.resolvedLost.isEmpty())
        assertTrue(submitted(fixture).isEmpty())
    }

    /** Follow-up 4: if Hermes refuses to let the lone lost reply go, the chat keeps its mark and shows the plain line; nothing runs. */
    @Test fun openingAChatWhoseLostReplyHermesWontLetGoKeepsItsMarkAndThePlainLine() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        fixture.refuseLostClose = true
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.resolve_unknown" } }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.status == "Hermes restarted during this reply." }
        compose.onNodeWithText("Hermes restarted during this reply.").assertIsDisplayed()
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(fixture.resolvedLost.isEmpty())
        assertTrue("the mark stays while Hermes holds it", "${fixture.scope.profile}:chat-b" in vm.ui.value.lostChats)
        assertTrue(fixture.requests.none { it.string("method") in setOf("session.resume", "session.create", "prompt.submit") })
    }

    /** Follow-up 4: a bot read never clears the chat list's marks of the same profile, and a profile's other lost chat does not mark its bot. */
    @Test fun aBotReadKeepsTheListsMarksOfTheSameProfile() = withFixture { vm, fixture, _ ->
        val profile = fixture.scope.profile
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        compose.runOnIdle { vm.refreshLostChats(listOf("chat-a", "chat-b")) }
        compose.waitUntil(15_000) { "$profile:chat-b" in vm.ui.value.lostChats }
        fixture.lostBots = mapOf("bot-a" to "bot-a-chat")
        compose.runOnIdle { vm.refreshLostBots(listOf(profile, "bot-a")) }
        compose.waitUntil(15_000) { botCutOff(vm.ui.value, "bot-a") }
        assertTrue("the list's mark stays", "$profile:chat-b" in vm.ui.value.lostChats)
        assertFalse("its own Bot Chat holds nothing", botCutOff(vm.ui.value, profile))
        assertEquals(emptyList<JSONObject>(), resumedOrClosed(fixture))
    }

    /** Follow-up 4: after that, the person's Send goes in one tap: no choice, the message is sent at once. */
    @Test fun aSendIntoAChatWhoseLoneLostReplyWasLetGoGoesInOneTap() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost()
        fixture.lostChats = mapOf("chat-b" to 0)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") && vm.chat.value.status == "Hermes restarted during this reply." }
        compose.onNodeWithTag("message-input").performClick().performTextInput("One tap after the restart")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submitted(fixture) == listOf("One tap after the restart") }
        compose.waitForIdle()
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertFalse(vm.chat.value.lostWaiting)
        assertEquals(listOf("lost-a"), fixture.resolvedLost.toList())
    }

    /** With a message Hermes holds behind the lost reply, a Send brings the choice: nothing is let go and nothing runs until the person says so. */
    @Test fun aSendIntoAChatWithAMessageWaitingBehindItsLostReplyBringsTheChoice() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost().put(json("admission_id" to "queued-a", "status" to "queued", "outcome" to JSONObject.NULL, "execution_generation" to JSONObject.NULL,
            "sequence" to 2, "authority_epoch" to 1, "input_id" to "queued-a", "text" to "Synthetic waiting message"))
        fixture.lostChats = mapOf("chat-b" to 1)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.onNodeWithTag("message-input").performClick().performTextInput("Another one after the restart")
        compose.onNodeWithTag("message-send").performClick()
        compose.waitUntil(15_000) { submitted(fixture).isNotEmpty() || vm.chat.value.queued.any { it.text == "Another one after the restart" } }
        compose.waitForIdle()
        compose.waitUntil(15_000) { vm.chat.value.lostWaiting }
        compose.onNodeWithTag("lost-reply-choice").assertIsDisplayed()
        assertTrue("nothing is let go without the person's choice", fixture.resolvedLost.isEmpty())
    }

    /** Follow-up 4: a project's chats are marked from the same read as the chat list. */
    @Test fun aProjectsChatsAreMarkedFromTheSameRead() = withFixture { vm, fixture, _ ->
        fixture.lostChats = mapOf("chat-b" to 0)
        compose.runOnIdle { vm.refreshLostChats(listOf("chat-a", "chat-b")) }
        compose.waitUntil(15_000) { "${fixture.scope.profile}:chat-b" in vm.ui.value.lostChats }
        assertFalse("${fixture.scope.profile}:chat-a" in vm.ui.value.lostChats)
        assertEquals(emptyList<JSONObject>(), resumedOrClosed(fixture))
    }

    /** Follow-up 4: Bot Mode marks a bot whose Bot Chat lost its reply; the plugin finds the chat, nothing is opened or created. */
    @Test fun theBotListMarksABotWhoseChatLostItsReply() = withFixture { vm, fixture, _ ->
        fixture.lostPending = lost()
        fixture.lostBots = mapOf("bot-a" to "bot-a-chat")
        compose.runOnIdle { vm.refreshLostBots(listOf("bot-a", "bot-b")) }
        compose.waitUntil(15_000) { botCutOff(vm.ui.value, "bot-a") }
        assertFalse(botCutOff(vm.ui.value, "bot-b"))
        assertTrue(lostTurnReads(fixture).any { it.string("query").contains("bot-a") })
        compose.runOnIdle { botRows.value = true }
        compose.onAllNodesWithTag("lost-turn-marker", useUnmergedTree = true).assertCountEquals(1)
        compose.onNode(hasTestTag("bot-row-bot-a") and hasAnyDescendant(hasTestTag("lost-turn-marker")), useUnmergedTree = true).assertExists()
        assertEquals(emptyList<JSONObject>(), resumedOrClosed(fixture))
    }

    /** With a message Hermes holds behind the lost reply, the open shows the choice instead; still nothing is resumed until the person's Send. */
    @Test fun openingAChatWithAMessageWaitingBehindItsLostReplyOffersTheChoice() = withFixture { vm, fixture, _ ->
        fixture.lostChats = mapOf("chat-b" to 1)
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(15_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.lostWaiting }
        compose.onNodeWithTag("lost-reply-choice").assertIsDisplayed()
        // Exactly two lines (Root, follow-up 1): why, then what to do; the restart line once, and no third.
        compose.onAllNodesWithText("Hermes restarted during this reply.").assertCountEquals(1)
        compose.onNodeWithText("Your waiting message hasn't been sent.").assertIsDisplayed()
        compose.onAllNodesWithTag("transcript-status").assertCountEquals(0)
        assertEquals(emptyList<JSONObject>(), resumedOrClosed(fixture))
    }

    /** The chat list marks the chat whose reply was cut off, and only that one (P3-2). */
    @Test fun theChatListMarksAChatWhoseReplyWasCutOff() = withFixture { vm, fixture, _ ->
        fixture.lostChats = mapOf("chat-b" to 0)
        compose.runOnIdle { vm.refreshSessions() }
        compose.waitUntil(15_000) { "${fixture.scope.profile}:chat-b" in vm.ui.value.lostChats }
        assertFalse("${fixture.scope.profile}:chat-a" in vm.ui.value.lostChats)
        compose.runOnIdle { listShown.value = true }
        // The list is lazy: bring chat-b's row on screen. The marker sits inside the row's merged click target, so it is found in the unmerged tree.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("history-row:chat-b"))
        compose.onAllNodesWithTag("lost-turn-marker", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("lost-turn-marker", useUnmergedTree = true).assertTextEquals("Reply cut off")
        compose.onNode(hasTestTag("history-row:chat-b") and hasAnyDescendant(hasTestTag("lost-turn-marker")), useUnmergedTree = true).assertExists()
        assertEquals(emptyList<JSONObject>(), resumedOrClosed(fixture))
    }

    /** Live, 17 Nov: the cut turn's own message sits in the box as accepted until its row pairs; it is Hermes', not waiting, so a look still closes the lone lost reply. */
    @Test fun theCutTurnsOwnAcceptedMessageDoesNotKeepALoneLostReplyOpen() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        compose.runOnIdle { flow<ChatState>(vm, "_chat").value = vm.chat.value.copy(queued = listOf(QueuedMessage("Synthetic cut request", state = "accepted"))) }
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { fixture.resolvedLost.toList() == listOf("lost-a") }
        compose.waitUntil(15_000) { vm.chat.value.status == "Hermes restarted during this reply." }
        compose.onAllNodesWithTag("lost-reply-choice").assertCountEquals(0)
        assertTrue(submitted(fixture).isEmpty())
    }

    /**
     * rules27 (backlog 18; CardsLiveTest part 2 live, 18 Nov): a card Hermes holds is answerable once the followed stream is back. Live, the app's reconnect on
     * the network's return reset the open chat (the card went with it) and followed the running turn from its saved cursor, past the request's frame, and
     * nothing asked Hermes what it still waited on: the card stayed gone, "Reconnecting…", while Hermes waited. Here the phone never got the request's frame
     * and its stream resumes from a cursor: only asking Hermes brings the card, and only the subscription itself says the link is back.
     */
    @Test fun aCardHermesHoldsIsAnswerableOnceTheFollowedStreamIsBack() = withFixture { vm, fixture, _ ->
        val runtime = "chat-b"
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.waitForIdle()
        // A turn this phone sent runs, and the app follows it.
        val admittedBefore = fixture.canonicalAdmissions.keys.toSet()
        compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue("Synthetic request that asks first")); vm.send() }
        compose.waitUntil(15_000) { (fixture.canonicalAdmissions.keys - admittedBefore).size == 1 }
        compose.waitUntil(15_000) { vm.chat.value.streamId == runtime && fixture.activeRuntimeForTest() != null }
        fixture.emitNativeEvent("message.delta", json("text" to "Synthetic words before it asks"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Synthetic words") }
        // Hermes asks before an action and keeps the request open until it is answered, while the phone's link drops: the phone never got its frame
        // (the request is not in what the stream resumes from its cursor), and a stream that is back sends no event while the turn waits on it.
        val prompt = json("kind" to "approval", "prompt_id" to "prompt-rules27", "execution_generation" to 7, "description" to "Synthetic write to a protected file",
            "command" to "<write to AGENTS.md>", "choices" to JSONArray(listOf("once", "deny")))
        fixture.canonicalOpenPrompts += prompt
        val subscribedBefore = fixture.eventSubscriptions.get()
        fixture.interruptActiveNativeStream()
        // The stream is back (subscribed again from its cursor).
        runCatching { compose.waitUntil(30_000) { fixture.eventSubscriptions.get() > subscribedBefore && vm.chat.value.streamId == runtime } }.onFailure {
            throw AssertionError("the stream did not come back: stream='${vm.chat.value.streamId}' status='${vm.chat.value.status}' active=${fixture.activeRuntimeForTest()} " +
                "turn=${fixture.nativeTurnStatus} methods=${fixture.requests.map { r -> r.string("method").ifBlank { r.string("path") } }.takeLast(16)}", it)
        }
        runCatching { compose.waitUntil(20_000) { vm.chat.value.approval?.string("approval_id") == "prompt-rules27" && vm.chat.value.status != "Reconnecting…" } }
        val state = vm.chat.value
        val seen = "approval=${state.approval?.string("approval_id")} stream='${state.streamId}' status='${state.status}' open=${fixture.canonicalOpenPrompts.size} " +
            "methods=${fixture.requests.map { it.string("method").ifBlank { it.string("path") } }.takeLast(12)}"
        assertEquals("the card Hermes still holds is answerable again: $seen", "prompt-rules27", state.approval?.string("approval_id"))
        assertTrue("a link that is back is not left Reconnecting: $seen", state.status != "Reconnecting…")
        // ...and it is answered from the card, once.
        compose.onNodeWithText("Deny").performScrollTo()
        CardMoveProbe.waitToSettle(compose, "Deny")
        CardMoveProbe.answerWhenSettled(compose, "Deny") { fixture.respondedPrompts.any { it.first == "prompt-rules27" } }
        assertEquals(listOf("prompt-rules27" to "deny"), fixture.respondedPrompts.toList())
        compose.waitUntil(10_000) { vm.chat.value.approval == null }
    }

    // ---- CHATFIX-121 (REVIEW-JOIN-121's findings) ----

    /** A turn this phone sent runs in chat-b and the app follows it on the stream's own collector; its first words are in. Returns the runtime (canonical: the chat's id). */
    private fun followedTurn(vm: BeaconViewModel, fixture: NativeExperienceFixture): String {
        compose.runOnIdle { vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
        compose.waitForIdle()
        val admittedBefore = fixture.canonicalAdmissions.keys.toSet()
        val collectors = fixture.liveCollectorSubscriptions.get()
        compose.runOnIdle { vm.composer(androidx.compose.ui.text.input.TextFieldValue("Synthetic request that asks first")); vm.send() }
        compose.waitUntil(15_000) { (fixture.canonicalAdmissions.keys - admittedBefore).size == 1 }
        compose.waitUntil(15_000) { vm.chat.value.streamId == "chat-b" && fixture.liveCollectorSubscriptions.get() > collectors && fixture.liveRoutedToCollector() }
        fixture.emitNativeEvent("message.delta", json("text" to "Synthetic words before it asks"))
        compose.waitUntil(15_000) { vm.chat.value.liveText.contains("Synthetic words") }
        return "chat-b"
    }
    private fun syntheticApproval(id: String) = json("kind" to "approval", "prompt_id" to id, "execution_generation" to 7, "description" to "Synthetic write to a protected file",
        "command" to "<write to AGENTS.md>", "choices" to JSONArray(listOf("once", "deny")))
    private fun seen(vm: BeaconViewModel, fixture: NativeExperienceFixture) = vm.chat.value.let { s ->
        "approval=${s.approval?.string("approval_id")} stream='${s.streamId}' status='${s.status}' live=${s.liveText.length} connected=${vm.ui.value.connected} " +
            "truncated=${fixture.truncatedReplays} methods=${fixture.requests.map { it.string("method").ifBlank { it.string("path") } }.takeLast(16)}"
    }
    private fun answerDeny(fixture: NativeExperienceFixture, id: String) {
        compose.onNodeWithText("Deny").performScrollTo()
        CardMoveProbe.waitToSettle(compose, "Deny")
        CardMoveProbe.answerWhenSettled(compose, "Deny") { fixture.respondedPrompts.any { it.first == id } }
        assertEquals(listOf(id to "deny"), fixture.respondedPrompts.toList())
    }
    /** The sockets in [fixture]'s log after [mark] that asked Hermes for a session and never subscribed to a stream: the reads the app made beside its streams. */
    private fun readSockets(fixture: NativeExperienceFixture, mark: Int): Set<Int> {
        val window = fixture.nativeSocketMethods.drop(mark).filter { it.first != 0 }
        return window.filter { it.second == "session.resume" }.map { it.first }.toSet() - window.filter { it.second == "session.events.since" }.map { it.first }.toSet()
    }
    private fun job(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as kotlinx.coroutines.Job?

    /**
     * P1-1: Hermes asks for an approval while the app is away, and more frames go out meanwhile than its replay ring keeps, so coming back resumes from a cursor
     * Hermes can no longer serve (snapshot_required: no frames, the request's own among them). Hermes says the turn waits on the owner: the card comes back and
     * is answered, instead of the reply waiting in silence until Hermes denies it.
     */
    @Test fun aCardAskedWhileTheAppWasAwayComesBackWhenHermesCannotReplayTheGap() = withFixture { vm, fixture, _ ->
        val runtime = followedTurn(vm, fixture)
        compose.runOnIdle { vm.foreground(false) }
        val prompt = syntheticApproval("prompt-away")
        fixture.canonicalOpenPrompts += prompt
        // What went out while the app was away, the request's frame among it, is gone from Hermes' ring.
        fixture.retainNativeEvent(runtime, "approval.request", prompt)
        fixture.replayEvictedThrough = fixture.retainNativeEvent(runtime, "message.delta", json("text" to " and more synthetic words")).optLong("seq")
        compose.runOnIdle { vm.foreground(true) }
        compose.waitUntil(20_000) { fixture.truncatedReplays.any { it > 0 } }
        runCatching { compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-away" } }
        assertEquals("the card Hermes waits on is back: ${seen(vm, fixture)}", "prompt-away", vm.chat.value.approval?.string("approval_id"))
        assertEquals("the turn is still followed: ${seen(vm, fixture)}", runtime, vm.chat.value.streamId)
        answerDeny(fixture, "prompt-away")
        compose.waitUntil(10_000) { vm.chat.value.approval == null }
    }

    /**
     * REVIEW-FIXCHAT-121 P1: card X is up when the link drops. Meanwhile Hermes settles X (the owner's answer reached it, or the computer answered) and
     * asks Y, and more goes out than its replay ring keeps, so the re-subscription gets only Hermes' reconcile. The chat reads Hermes' cards on that
     * reconcile, whatever shows: X goes and Y shows, instead of X blocking Y until Hermes denies it.
     */
    @Test fun aCardHermesSettledWhileTheLinkWasDownGivesWayToTheOneItWaitsOnAfterACutShortReplay() = withFixture { vm, fixture, _ ->
        val runtime = followedTurn(vm, fixture)
        val settled = syntheticApproval("prompt-settled")
        fixture.canonicalOpenPrompts += settled
        fixture.emitNativeEvent("approval.request", settled)
        compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-settled" }
        // While the link is down: Hermes settles X, asks Y, and more frames go out than its ring keeps.
        val waiting = syntheticApproval("prompt-waiting")
        fixture.canonicalOpenPrompts.clear(); fixture.canonicalOpenPrompts += waiting
        fixture.retainNativeEvent(runtime, "approval.request", waiting)
        fixture.replayEvictedThrough = fixture.retainNativeEvent(runtime, "message.delta", json("text" to " and more synthetic words")).optLong("seq")
        fixture.dropActiveNativeStream()
        compose.waitUntil(20_000) { fixture.truncatedReplays.any { it > 0 } }
        runCatching { compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-waiting" } }
        assertEquals("the card Hermes waits on shows and the settled one is gone: ${seen(vm, fixture)}", "prompt-waiting", vm.chat.value.approval?.string("approval_id"))
        answerDeny(fixture, "prompt-waiting")
        compose.waitUntil(10_000) { vm.chat.value.approval == null }
    }

    /**
     * P1-2: a card is up when the link drops; the app reads offline (connected=false), and the network's return reconnects in full: connect() empties the open
     * chat, restores it and opens it again. The chat then follows the running turn from its cursor, which is past the request's frame, so only asking Hermes
     * brings the card back. It comes back and is answered.
     */
    @Test fun aCardAFullReconnectDroppedComesBackAndIsAnswered() = withFixture { vm, fixture, scope ->
        val runtime = followedTurn(vm, fixture)
        val prompt = syntheticApproval("prompt-reconnect")
        fixture.canonicalOpenPrompts += prompt
        fixture.emitNativeEvent("approval.request", prompt)
        compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-reconnect" }
        val states = java.util.concurrent.CopyOnWriteArrayList<ChatState>()
        val recorder = compose.runOnIdle { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { vm.chat.collect { states += it } } }
        try {
            compose.runOnIdle {
                vm.profileSwitchClientFactoryForTest = { auth -> fixture.freshClientFor(auth, app.auth) }
                flow<AppState>(vm, "_ui").value = vm.ui.value.copy(connected = false)
                // What the network's return does when the app reads offline (reconnectOnNetworkReturn): a full connect.
                vm.connect(scope.origin)
            }
            compose.waitUntil(30_000) { vm.ui.value.connected && vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady }
            assertTrue("the reconnect emptied the chat and its card: ${states.map { "${it.sessionId}/${it.approval?.string("approval_id")}" }}",
                states.any { it.approval == null })
            runCatching { compose.waitUntil(20_000) { vm.chat.value.approval?.string("approval_id") == "prompt-reconnect" && vm.chat.value.streamId == runtime } }
            assertEquals("the card Hermes still waits on is back: ${seen(vm, fixture)}", "prompt-reconnect", vm.chat.value.approval?.string("approval_id"))
            assertEquals("the turn is followed again: ${seen(vm, fixture)}", runtime, vm.chat.value.streamId)
            answerDeny(fixture, "prompt-reconnect")
            compose.waitUntil(10_000) { vm.chat.value.approval == null }
        } finally { recorder.cancel(); compose.runOnIdle { vm.client?.closeNative() } }
    }

    /** P1's bound: with the card on screen, a link that drops again and again asks Hermes for no prompt read; only the stream's own looks and binds. */
    @Test fun aFlappingLinkWithTheCardUpMakesNoPromptReads() = withFixture { vm, fixture, _ ->
        followedTurn(vm, fixture)
        val prompt = syntheticApproval("prompt-flap")
        fixture.canonicalOpenPrompts += prompt
        fixture.emitNativeEvent("approval.request", prompt)
        compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-flap" }
        val mark = fixture.nativeSocketMethods.size
        repeat(3) {
            val subscribed = fixture.eventSubscriptions.get()
            fixture.dropActiveNativeStream()
            compose.waitUntil(20_000) { fixture.eventSubscriptions.get() > subscribed && fixture.liveRoutedToCollector() }
        }
        Thread.sleep(2_000); compose.waitForIdle()
        assertEquals("no prompt read while the card shows: ${fixture.nativeSocketMethods.drop(mark)}", emptySet<Int>(), readSockets(fixture, mark))
        assertEquals("prompt-flap", vm.chat.value.approval?.string("approval_id"))
    }

    /** P2-1: Hermes refuses the sign-in (403) to the look at a live copy no stream is bound to. The app says so; nothing crashes. */
    @Test fun aRefusedLookAtAnUnboundCopyIsShownAndCrashesNothing() = withFixture { vm, fixture, _ ->
        fixture.resumeFailureCode = 403
        fixture.failingResumes.set(1)
        compose.runOnIdle { chatFlow(vm).let { f -> f.value = f.value.copy(liveText = "Synthetic words no stream follows", streamId = "", status = "") } }
        compose.waitUntil(15_000) { fixture.failingResumes.get() == 0 }
        runCatching { compose.waitUntil(10_000) { vm.ui.value.error.isNotBlank() } }
        Thread.sleep(1_000); compose.waitForIdle()
        assertTrue("the refusal is shown: '${vm.ui.value.error}'", vm.ui.value.error.isNotBlank())
    }

    /** P2-1: the same refusal to the look at a reply Hermes lost in a restart. The app says so; nothing crashes. */
    @Test fun aRefusedLostReplyLookIsShownAndCrashesNothing() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        fixture.resumeFailureCode = 403
        fixture.failingResumes.set(1)
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { fixture.failingResumes.get() == 0 }
        runCatching { compose.waitUntil(10_000) { vm.ui.value.error.isNotBlank() } }
        Thread.sleep(1_000); compose.waitForIdle()
        assertTrue("the refusal is shown: '${vm.ui.value.error}'", vm.ui.value.error.isNotBlank())
        assertTrue(fixture.resolvedLost.isEmpty())
    }

    /** P2-4: the one look at a live copy no stream is bound to fails on a short drop; the chat's watch asks again on its next tick, so the stale words and Working go. */
    @Test fun aFailedLookAtAnUnboundCopyIsTriedAgainOnTheWatchsNextTick() = withFixture { vm, fixture, _ ->
        compose.runOnIdle { field(vm, "streamWatchMs", 1_000L); vm.selectSession("chat-b") }
        compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" && vm.chat.value.projectionReady && job(vm, "sessionWatch")?.isActive == true }
        compose.waitForIdle()
        fixture.failingResumes.set(1)
        compose.runOnIdle { chatFlow(vm).let { f -> f.value = f.value.copy(liveText = "Synthetic words left behind", liveReasoning = "Synthetic notes", streamId = "", status = "") } }
        compose.waitUntil(15_000) { fixture.failingResumes.get() == 0 }
        runCatching { compose.waitUntil(15_000) { !liveCopyUnbound(vm.chat.value) } }
        val state = vm.chat.value
        assertFalse("the live copy no stream follows is let go: stream='${state.streamId}' live='${state.liveText}' status='${state.status}'", liveCopyUnbound(state))
        compose.onAllNodesWithText("Working").assertCountEquals(0)
    }

    /** P3-1: a quiet stream that could not ask Hermes says Reconnecting…; Hermes' next answer, that the turn waits on the owner, puts the turn's own line back. */
    @Test fun aWaitingAnswerAfterAFailedCheckLeavesNoReconnectingLine() = withFixture { vm, fixture, _ ->
        compose.runOnIdle { field(vm, "streamSilenceMs", 3_000L); field(vm, "streamWatchMs", 500L) }
        followedTurn(vm, fixture)
        fixture.failingResumes.set(1_000)
        runCatching { compose.waitUntil(15_000) { vm.chat.value.status == "Reconnecting…" } }
        assertEquals("a quiet stream that could not ask says so: ${seen(vm, fixture)}", "Reconnecting…", vm.chat.value.status)
        // Hermes answers the next check: the turn waits on the owner.
        fixture.canonicalOpenPrompts += syntheticApproval("prompt-quiet")
        fixture.failingResumes.set(0)
        runCatching { compose.waitUntil(15_000) { vm.chat.value.status != "Reconnecting…" } }
        assertEquals("Hermes answered, so the line is the turn's own: ${seen(vm, fixture)}", "Working", vm.chat.value.status)
    }

    /**
     * P3-1 (Root msg_e2127e2ea974): a dropped stream whose look could not reach Hermes says it couldn't check the reply. The stream subscribing again
     * ends "Reconnecting…" but not that line: the link works, the turn's state is still unknown. Hermes' next answer about the turn (it waits on the
     * owner) puts the turn's own line back.
     */
    @Test fun aStreamThatIsBackKeepsCouldntCheckUntilHermesAnswersAboutTheTurn() = withFixture { vm, fixture, _ -> couldntCheckUntilHermesAnswers(vm, fixture, slowReadsMs = 0) }

    /**
     * CHATFIX-121 follow-up 4 (FINAL-121's open failure): the same on a slow phone. The drop's recovery reads the chat's saved rows before its own look; when that
     * read is slow, the stream's quiet watcher can ask Hermes about the turn first.
     */
    @Test fun aStreamThatIsBackKeepsCouldntCheckOnASlowPhone() = withFixture { vm, fixture, _ -> couldntCheckUntilHermesAnswers(vm, fixture, slowReadsMs = 7_000) }

    private fun couldntCheckUntilHermesAnswers(vm: BeaconViewModel, fixture: NativeExperienceFixture, slowReadsMs: Long) {
        // FINAL-121's open failure: the drop's recovery reads the chat's saved rows (two reads) before its own look. With a 6 s quiet window a slow phone let
        // the stream's quiet watcher ask Hermes first and take the one failed answer; the stream's own look then heard "working", and the line was rightly the
        // turn's own. The window is longer than any recovery here, so only the drop's own look can take the failure; the watcher still asks after it.
        compose.runOnIdle { field(vm, "streamSilenceMs", 30_000L); field(vm, "streamWatchMs", 500L) }
        followedTurn(vm, fixture)
        fixture.sessionReadDelayMs = slowReadsMs
        val subscribed = fixture.eventSubscriptions.get()
        val before = fixture.nativeSocketMethods.toList()
        // Every line the chat shows, so a short one is never missed between two looks at the screen.
        val statuses = java.util.concurrent.CopyOnWriteArrayList<String>()
        val recorder = compose.runOnIdle { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main.immediate).launch { vm.chat.collect { statuses += it.status } } }
        try {
            // The look after the drop fails; the subscription after it binds again.
            fixture.failingResumes.set(1)
            fixture.dropActiveNativeStream()
            runCatching { compose.waitUntil(30_000) { fixture.failingResumes.get() == 0 && statuses.any { it.startsWith("Couldn't check") } } }
            compose.waitUntil(40_000) { fixture.eventSubscriptions.get() > subscribed && fixture.liveRoutedToCollector() }
            Thread.sleep(1_500); compose.waitForIdle()
            fun trace() = "${seen(vm, fixture)} dropped=${fixture.droppedNativeSockets} failingLeft=${fixture.failingResumes.get()} " +
                "before=${before.takeLast(12)} after=${fixture.nativeSocketMethods.drop(before.size)} statuses=${statuses.distinct()}"
            assertTrue("the failed look said so: ${trace()}", statuses.any { it.startsWith("Couldn't check") })
            assertTrue("the stream is back, yet nothing said what the turn does: ${trace()}", vm.chat.value.status.startsWith("Couldn't check"))
            // Hermes answers the next check about the turn (the quiet watcher's): it waits on the owner.
            fixture.canonicalOpenPrompts += syntheticApproval("prompt-answered")
            runCatching { compose.waitUntil(45_000) { vm.chat.value.status == "Working" } }
            assertEquals("Hermes answered about the turn, so the line is the turn's own: ${trace()}", "Working", vm.chat.value.status)
        } finally { recorder.cancel() }
    }

    /** P3-2: asking Hermes what it waits on (here the Refresh question tap) closes the connection the ask opened. */
    @Test fun aPromptReadClosesTheConnectionItOpened() = withFixture { vm, fixture, _ ->
        fixture.canonicalOpenPrompts += syntheticApproval("prompt-read")
        val mark = fixture.nativeSocketMethods.size
        compose.runOnIdle { vm.selectSession("chat-a", dropQuestion = true) }
        compose.waitUntil(15_000) { vm.chat.value.approval?.string("approval_id") == "prompt-read" }
        Thread.sleep(2_000); compose.waitForIdle()
        val readers = readSockets(fixture, mark)
        assertTrue("a prompt read was made: ${fixture.nativeSocketMethods.drop(mark)}", readers.isNotEmpty())
        assertEquals("every connection that only read is closed: ${fixture.nativeSocketMethods.drop(mark)}", emptySet<Int>(), readers - fixture.closedSockets)
    }

    /** P3-3: a card from before Hermes restarted does not stay beside the lost reply's choice: Hermes is asked what it still waits on (nothing). */
    @Test fun theLostReplyChoiceLeavesNoCardHermesNoLongerHolds() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        val waiting = QueuedMessage("Send this after the restart")
        val dead = syntheticApproval("prompt-before-restart").copy().put("approval_id", "prompt-before-restart")
        compose.runOnIdle { chatFlow(vm).let { f -> f.value = f.value.copy(queued = listOf(waiting)).promptAsked("approval", dead, android.os.SystemClock.elapsedRealtime()) } }
        compose.waitUntil(5_000) { vm.chat.value.approval != null }
        compose.runOnIdle { look(vm, scope) }
        compose.waitUntil(15_000) { vm.chat.value.lostWaiting }
        runCatching { compose.waitUntil(10_000) { vm.chat.value.approval == null } }
        assertNull("no card Hermes no longer holds stays beside the choice", vm.chat.value.approval?.string("approval_id"))
        compose.onNodeWithTag("lost-reply-choice").assertIsDisplayed()
        assertTrue(submitted(fixture).isEmpty())
    }

    /** P3-5: signing out leaves no live reply words in memory: the frames, boundaries and saved-turn proofs go with the chat. */
    @Test fun signingOutForgetsTheLiveReplyHeldInMemory() = withFixture { vm, fixture, _ ->
        followedTurn(vm, fixture)
        fun held(name: String) = (BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as Map<*, *>).size
        assertTrue("the live reply is held in memory first", held("liveFrames") > 0)
        val out = compose.runOnIdle { vm.logout() }
        runBlocking { kotlinx.coroutines.withTimeout(30_000) { out.join() } }
        compose.waitUntil(15_000) { !vm.ui.value.authenticated }
        assertEquals("frames", 0, held("liveFrames"))
        assertEquals("boundaries", 0, held("liveBoundaries"))
        assertEquals("saved-turn proofs", 0, held("persistedTurns"))
    }

    /**
     * CHATFIX-121 follow-up 1 (the fence): account A's look is still waiting when the owner signs in to account B; A's Hermes then refuses A's sign-in (401).
     * That answer belongs to an account no longer in use: B stays signed in, with no error.
     */
    private fun aRefusalForTheOldAccountLeavesTheNewOneSignedIn(vm: BeaconViewModel, fixture: NativeExperienceFixture, startLook: () -> Unit) {
        fixture.resumeFailureCode = 401
        fixture.failingResumes.set(1)
        fixture.holdResumeRefusals = true
        compose.runOnIdle { startLook() }
        compose.waitUntil(15_000) { fixture.heldRefusalCount() == 1 }
        val other = NativeExperienceFixture("synthetic-other-owner")
        try {
            runBlocking { app.auth.persist(other.client.auth) }
            compose.runOnIdle {
                vm.profileSwitchClientFactoryForTest = { auth -> other.freshClientFor(auth, app.auth) }
                vm.connect(other.scope.origin)
            }
            compose.waitUntil(30_000) { vm.ui.value.connected && vm.client?.scope?.owner == other.scope.owner }
            compose.runOnIdle { flow<AppState>(vm, "_ui").value = vm.ui.value.copy(error = "") }
            // A's refusal lands now.
            fixture.releaseHeldRefusals()
            Thread.sleep(2_000); compose.waitForIdle()
            val ui = vm.ui.value
            assertTrue("B is still signed in: authenticated=${ui.authenticated} owner='${ui.owner}' client=${vm.client?.scope?.owner}", ui.authenticated && vm.client?.scope?.owner == other.scope.owner)
            assertEquals("No error for an account no longer in use", "", ui.error)
        } finally { other.client.closeNative() }
    }

    @Test fun aRefusedUnboundCheckOfTheOldAccountLeavesTheNewOneSignedIn() = withFixture { vm, fixture, _ ->
        aRefusalForTheOldAccountLeavesTheNewOneSignedIn(vm, fixture) {
            chatFlow(vm).let { f -> f.value = f.value.copy(liveText = "Synthetic words no stream follows", streamId = "", status = "") }
        }
    }

    /**
     * CHATFIX-121 follow-up 2: the old account's side question is refused with a 401 after account B signed in; B stays signed in. The sign-in also
     * cancels the side question (clearDetails), so this guards the fence in resumeSideQuestion rather than failing without it.
     */
    @Test fun aRefusedSideQuestionOfTheOldAccountLeavesTheNewOneSignedIn() = withFixture { vm, fixture, scope ->
        aRefusalForTheOldAccountLeavesTheNewOneSignedIn(vm, fixture) {
            @Suppress("UNCHECKED_CAST")
            (BeaconViewModel::class.java.getDeclaredField("_side").apply { isAccessible = true }.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<SideQuestion?>).value =
                SideQuestion(scope, "Synthetic side question", scope.session, "synthetic-side-stream")
            vm.resumeSideQuestion()
        }
    }

    /** REVIEW-JOINB-121 P3-1: removing an unconfirmed send's copy also lets go of the large file kept on this phone for it. */
    @Test fun removingAnUnconfirmedCopyLetsGoOfItsKeptFile() = withFixture { vm, _, scope ->
        val id = java.util.UUID.randomUUID().toString()
        val source = java.io.File.createTempFile("synthetic-kept", ".pdf", app.cacheDir).apply { writeBytes(ByteArray(1024) { 7 }) }
        val kept = dev.hermes.android.data.ChatAttachmentStore.adopt(source, id)
        try {
            val item = json("id" to id, "state" to "ready", "scope_key" to scope.key, "name" to "synthetic.pdf", "mime" to "application/pdf", "size" to 1024, "staged_file" to true)
            val send = PendingSend.create(scope, Draft("Synthetic words with a kept file", attachments = listOf(item)))
            compose.runOnIdle {
                @Suppress("UNCHECKED_CAST")
                (BeaconViewModel::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(vm) as MutableMap<String, PendingSend>)[scope.key] = send
                chatFlow(vm).let { f -> f.value = f.value.copy(uncertain = send.id, status = "") }
            }
            assertTrue("the file is kept first", kept.isFile)
            compose.runOnIdle { vm.removePendingSend() }
            compose.waitUntil(10_000) { vm.chat.value.uncertain.isBlank() }
            runCatching { compose.waitUntil(5_000) { !kept.exists() } }
            assertFalse("the removed copy's kept file is gone: $kept", kept.exists())
        } finally { kept.delete(); source.delete() }
    }

    @Test fun aRefusedLostReplyLookOfTheOldAccountLeavesTheNewOneSignedIn() = withFixture { vm, fixture, scope ->
        fixture.lostPending = lost()
        aRefusalForTheOldAccountLeavesTheNewOneSignedIn(vm, fixture) { look(vm, scope) }
    }
}
