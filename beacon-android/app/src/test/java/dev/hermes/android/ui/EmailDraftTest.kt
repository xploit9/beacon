package dev.hermes.android.ui

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The email draft card: recognising it in a chat, reading Hermes' answers, and what each tap does and says. Addresses are built at run time. */
class EmailDraftTest {
    private val at = "@" + "example.com"
    private val maya = "maya$at"
    private val dan = "dan$at"
    private val id = "a".repeat(32)

    private fun view(
        version: Int = 1, versions: Int = 1, position: Int = version, subject: String = "Friday review", body: String = "Can we move it?", status: String = "draft",
        reason: String = "", via: String = "tool", canSend: Boolean? = true, unchanged: Boolean = false, cc: List<String> = listOf(dan),
    ) = JSONObject().put("id", id).put("version", version).put("versions", versions).put("position", position)
        .put("to", JSONArray(listOf(maya))).put("cc", JSONArray(cc)).put("subject", subject).put("body", body)
        .put("status", status).put("reason", reason).put("via", via).apply { canSend?.let { put("can_send", it) }; if (unchanged) put("unchanged", true) }

    private class FakeApi : EmailDraftApi {
        var read: () -> JSONObject = { error("no read") }
        var revise: (String, Int) -> JSONObject = { _, _ -> error("no revise") }
        var step: (Int, Int) -> JSONObject = { _, _ -> error("no step") }
        var send: (Int) -> JSONObject = { error("no send") }
        val calls = mutableListOf<String>()
        override suspend fun read(id: String): JSONObject { calls += "read"; return read() }
        override suspend fun revise(id: String, instruction: String, version: Int): JSONObject { calls += "revise:$instruction:$version"; return revise(instruction, version) }
        override suspend fun step(id: String, version: Int, step: Int): JSONObject { calls += "step:$version:$step"; return step(version, step) }
        override suspend fun send(id: String, version: Int): JSONObject { calls += "send:$version"; return send(version) }
    }

    private class Chat(var free: Boolean = true) {
        val said = mutableListOf<String>()
        val ask get() = HermesAsk({ free }, { said += it }, { subject -> "Send the email \"$subject\"" })
    }

    private fun TestScope.model(api: FakeApi, seed: EmailDraft = EmailDraft.parse(view())) = EmailDraftModel(id, api, this, seed)

    // ---- recognising the card in a chat ----------------------------------------------------------------------------------

    @Test fun theDraftIdComesFromTheToolsOwnResultOnly() {
        assertEquals(id, emailDraftId("""{"draft_id": "$id", "version": 1, "status": "shown", "note": "..."}"""))
        assertEquals(id, emailDraftId(JSONObject().put("result", JSONObject().put("draft_id", id).toString()).toString()))
        assertEquals(id, emailDraftId("{\\\"draft_id\\\": \\\"$id\\\"}"))
        assertNull(emailDraftId("""{"error": "Email drafts are shown in the owner's Beacon chat only."}"""))
        assertNull(emailDraftId("""{"draft_id": "not-an-id"}"""))
        assertNull(emailDraftId(null))
        assertNull(emailDraftId(""))
    }

    private fun step(name: String, arguments: JSONObject? = null, result: String? = null) = JSONObject().put("name", name).put("tool_call_id", "call-1").apply {
        arguments?.let { put("arguments", it.toString()) }
        result?.let { put("result", it) }
    }
    private val draftArguments = JSONObject().put("to", JSONArray(listOf(maya))).put("cc", JSONArray(listOf(dan))).put("subject", "Friday review").put("body", "Can we move it?")

    @Test fun onlyABeaconEmailDraftStepWithAnIdBecomesACard() {
        val ok = """{"draft_id": "$id", "version": 1}"""
        val card = emailDraftOf(step("beacon_email_draft", draftArguments, ok), null)!!
        assertEquals(id, card.id)
        assertEquals(listOf(maya), card.to)
        assertEquals(listOf(dan), card.cc)
        assertEquals("Friday review", card.subject)
        assertEquals("Can we move it?", card.body)
        // The result can also arrive folded from its own transcript row.
        assertEquals(id, emailDraftOf(step("beacon_email_draft", draftArguments), FoldedToolResult("m1", ok, false))!!.id)
        // Hermes' meta-tool names the tool it ran.
        val wrapped = step("tool_call", JSONObject().put("name", "beacon_email_draft")).put("arguments", JSONObject().put("name", "beacon_email_draft").toString())
        assertEquals(id, emailDraftOf(wrapped.put("result", ok), null)!!.id)
        // A refused draft, a draft still being written, and every other step stay ordinary steps.
        assertNull(emailDraftOf(step("beacon_email_draft", draftArguments, """{"error": "to is required"}"""), null))
        assertNull(emailDraftOf(step("beacon_email_draft", draftArguments), null))
        assertNull(emailDraftOf(step("beacon_email_draft_send", null, ok), null))
        assertNull(emailDraftOf(step("terminal", JSONObject().put("command", "himalaya message send"), ok), null))
    }

    @Test fun aCardSeededFromTheCallShowsItsFieldsAtOnceEvenWithUnreadableArguments() {
        val seed = EmailDraft.seed(id, draftArguments.toString())
        assertEquals(listOf(maya), seed.to)
        assertEquals("Friday review", seed.subject)
        assertEquals(EmailDraft(id), EmailDraft.seed(id, "not json"))
        assertEquals(EmailDraft(id), EmailDraft.seed(id, null))
    }

    @Test fun hermesAnswerIsReadFieldByField() {
        val draft = EmailDraft.parse(view(version = 3, versions = 4, position = 3, status = "failed", reason = "approval", via = "tool", canSend = false, unchanged = true))
        assertEquals(listOf(3, 4, 3), listOf(draft.version, draft.versions, draft.position))
        assertEquals(listOf(maya), draft.to)
        assertEquals(listOf(dan), draft.cc)
        assertEquals("failed", draft.status)
        assertEquals("approval", draft.reason)
        assertFalse(draft.canSend)
        assertTrue(draft.unchanged)
        assertTrue(draft.hasPrevious && draft.hasNewer)
        // A status the app does not know is a plain draft, never a claim about sending.
        assertEquals("draft", EmailDraft.parse(view(status = "teleported")).status)
        assertTrue(EmailDraft.parse(view(canSend = null)).canSend)
    }

    @Test fun theCopyTextIsTheWholeEmail() {
        assertEquals("To: $maya\nCc: $dan\nSubject: Friday review\n\nCan we move it?", EmailDraft.parse(view()).copyText)
        assertEquals("To: $maya\nSubject: Friday review\n\nCan we move it?", EmailDraft.parse(view(cc = emptyList())).copyText)
    }

    // ---- the card's taps ------------------------------------------------------------------------------------------------------

    @Test fun theCardWaitsForHermesBeforeAnyTapCountsThenShowsHermesCopy() = runTest {
        val api = FakeApi().apply { read = { view(subject = "Friday review v2", version = 2, versions = 2) } }
        val model = model(api)
        assertFalse(model.state.value.loaded)
        assertNull(model.revise("shorter"))
        assertNull(model.send(Chat().ask))
        assertEquals(emptyList<String>(), api.calls)
        model.refresh()
        assertTrue(model.state.value.loaded)
        assertEquals("Friday review v2", model.state.value.draft.subject)
        assertEquals(2, model.state.value.draft.version)
    }

    @Test fun aFailedLookKeepsTheCardAsItWas() = runTest {
        val api = FakeApi().apply { read = { throw IOException("offline") } }
        val model = model(api)
        model.refresh()
        assertFalse(model.state.value.loaded)
        assertEquals("Friday review", model.state.value.draft.subject)
    }

    private suspend fun TestScope.loaded(api: FakeApi, draft: JSONObject = view()): EmailDraftModel {
        api.read = { draft }
        return model(api).also { it.refresh() }
    }

    @Test fun aChangeRedraftsTheSameCard() = runTest {
        val api = FakeApi().apply { revise = { words, version -> assertEquals("shorter", words); assertEquals(1, version); view(version = 2, versions = 2, body = "Friday?") } }
        val model = loaded(api)
        val job = model.revise("  shorter ")!!
        assertEquals(DraftWork.Revising, model.state.value.work)
        // A second tap while Hermes works does nothing.
        assertNull(model.revise("again"))
        job.join()
        val state = model.state.value
        assertEquals(DraftWork.None, state.work)
        assertEquals(DraftProblem.None, state.problem)
        assertEquals(listOf(2, 2, "Friday?"), listOf(state.draft.version, state.draft.versions, state.draft.body))
        assertEquals(listOf("read", "revise:shorter:1"), api.calls)
    }

    @Test fun aRedraftThatChangedNothingSaysSoAndABlankRequestIsIgnored() = runTest {
        val api = FakeApi().apply { revise = { _, _ -> view(unchanged = true) } }
        val model = loaded(api)
        assertNull(model.revise("   "))
        model.revise("same")!!.join()
        assertEquals(DraftProblem.Nothing, model.state.value.problem)
        assertEquals(1, model.state.value.draft.versions)
    }

    @Test fun aFailedRedraftKeepsTheCardAndSaysWhy() = runTest {
        val api = FakeApi()
        val model = loaded(api)
        api.revise = { _, _ -> throw ApiFailure(502, "Hermes could not redraft this. Try again.", true) }
        model.revise("x")!!.join()
        assertEquals(DraftProblem.Redraft, model.state.value.problem)
        assertEquals("Can we move it?", model.state.value.draft.body)
        assertEquals(DraftWork.None, model.state.value.work)
        api.revise = { _, _ -> throw IOException("offline") }
        model.revise("x")!!.join()
        assertEquals(DraftProblem.Offline, model.state.value.problem)
        // The next try clears the old message.
        api.revise = { _, _ -> view(version = 2, versions = 2) }
        model.revise("x")!!.join()
        assertEquals(DraftProblem.None, model.state.value.problem)
    }

    @Test fun aStaleScreenIsToldTheDraftChangedAndReadsItAgain() = runTest {
        val api = FakeApi()
        val model = loaded(api)
        api.revise = { _, _ -> throw ApiFailure(409, "This draft changed. Review it and try again.", true) }
        api.read = { view(version = 3, versions = 3, subject = "Newer") }
        model.revise("x")!!.join()
        assertEquals(DraftProblem.Changed, model.state.value.problem)
        assertEquals("Newer", model.state.value.draft.subject)
        assertEquals(3, model.state.value.draft.version)
    }

    @Test fun theOwnerCanStepBetweenVersions() = runTest {
        val api = FakeApi().apply { step = { version, by -> assertEquals(3, version); assertEquals(-1, by); view(version = 1, versions = 3, position = 1, subject = "First") } }
        val model = loaded(api, view(version = 3, versions = 3, position = 3))
        assertNull(model.step(forward = true))
        model.step(forward = false)!!.join()
        assertEquals(listOf("First", 1), listOf(model.state.value.draft.subject, model.state.value.draft.version))
        assertNull(model.step(forward = false))
    }

    @Test fun sendWithAMailToolTakesTheTapThenFollowsHermesUntilSent() = runTest {
        val chat = Chat()
        val api = FakeApi().apply { send = { version -> assertEquals(1, version); view(status = "sending") } }
        val model = loaded(api)
        model.send(chat.ask)!!.join()
        assertEquals("sending", model.state.value.draft.status)
        // Hermes sends it itself with its mail tool, so nothing is said in the chat.
        assertEquals(emptyList<String>(), chat.said)
        var reads = 0
        api.read = { reads++; if (reads < 3) view(status = "sending") else view(status = "sent") }
        val watching = launch { model.watch() }
        advanceTimeBy(DRAFT_POLL_MILLIS * 5)
        watching.join()
        assertEquals("sent", model.state.value.draft.status)
        assertTrue(reads in 3..4)
    }

    @Test fun whenHermesSendsItItselfTheSameTapAsksItInTheChatOnce() = runTest {
        val chat = Chat()
        val api = FakeApi().apply { send = { view(status = "sending", via = "hermes") } }
        val model = loaded(api, view(via = "hermes"))
        model.send(chat.ask)!!.join()
        assertEquals(listOf("Send the email \"Friday review\""), chat.said)
        assertNull(model.send(chat.ask))
        assertEquals(listOf("read", "send:1"), api.calls)
    }

    @Test fun aBusyComposerStopsTheSendBeforeHermesIsToldAnything() = runTest {
        val chat = Chat(free = false)
        val api = FakeApi()
        val model = loaded(api, view(via = "hermes"))
        assertNull(model.send(chat.ask))
        assertEquals(DraftProblem.Send, model.state.value.problem)
        assertEquals(listOf("read"), api.calls)
        assertEquals(emptyList<String>(), chat.said)
        assertEquals(DraftWork.None, model.state.value.work)
    }

    @Test fun noWayToSendTurnsTheCardIntoACopyCard() = runTest {
        val api = FakeApi().apply { send = { throw ApiFailure(422, "Hermes has no way to send email here.", true) } }
        val model = loaded(api)
        model.send(Chat().ask)!!.join()
        assertFalse(model.state.value.draft.canSend)
        assertEquals(DraftProblem.NoWay, model.state.value.problem)
        assertEquals("", model.state.value.draft.via)
    }

    @Test fun aFailedOrUnsureSendCanBeSentAgain() = runTest {
        val api = FakeApi().apply { send = { view(status = "sending") } }
        for (status in listOf("failed", "unsure")) {
            val model = loaded(api, view(status = status, reason = "tool"))
            val job = model.send(Chat().ask)
            assertTrue(status, job != null)
            job!!.join()
            assertEquals("sending", model.state.value.draft.status)
        }
    }

    @Test fun aSentOrSendingCardTakesNoMoreChangesOrSends() = runTest {
        val api = FakeApi()
        for (status in listOf("sent", "sending")) {
            val model = loaded(api, view(status = status))
            assertNull(model.revise("x"))
            assertNull(model.send(Chat().ask))
            assertNull(model.step(forward = false))
        }
    }

    @Test fun aSendThatCouldNotReachHermesLeavesTheCardAsItWasWithAMessage() = runTest {
        val api = FakeApi().apply { send = { throw IOException("offline") } }
        val model = loaded(api)
        model.send(Chat().ask)!!.join()
        assertEquals(DraftProblem.Offline, model.state.value.problem)
        assertEquals("draft", model.state.value.draft.status)
        assertTrue(model.state.value.draft.canSend)
    }

    @Test fun aLookDuringATapCannotUndoIt() = runTest {
        val api = FakeApi()
        val model = loaded(api)
        api.send = { view(status = "sending") }
        val job = model.send(Chat().ask)!!
        api.read = { view(status = "draft", subject = "Stale") }
        model.refresh()
        job.join()
        assertEquals("sending", model.state.value.draft.status)
        assertEquals("Friday review", model.state.value.draft.subject)
    }

    // ---- keeping cards ----------------------------------------------------------------------------------------------------------

    @Test fun theStoreKeepsOneModelPerAccountAndDraftAndForgetsTheOldest() = runTest {
        val store = EmailDraftStore(this)
        val api = FakeApi()
        val first = store.model("account-1", id, EmailDraft(id)) { api }
        assertSame(first, store.model("account-1", id, EmailDraft(id)) { error("kept") })
        assertNotSame(first, store.model("account-2", id, EmailDraft(id)) { api })
        repeat(70) { store.model("account-3", "%032x".format(it), EmailDraft("x")) { api } }
        assertNotSame(first, store.model("account-1", id, EmailDraft(id)) { api })
    }
}
