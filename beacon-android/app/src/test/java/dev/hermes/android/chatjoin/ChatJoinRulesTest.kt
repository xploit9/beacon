package dev.hermes.android.chatjoin

import dev.hermes.android.data.ATTACHMENT_REFUSED_LINE
import dev.hermes.android.data.ATTACHMENT_TOO_LARGE_LINE
import dev.hermes.android.data.AttachmentRefused
import dev.hermes.android.data.AttachmentStalled
import dev.hermes.android.data.NativeTurnNotSent
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.FILE_NOT_SENT_OUTCOME
import dev.hermes.android.OFFLINE_OUTCOME
import dev.hermes.android.answerLost
import dev.hermes.android.data.NATIVE_OUTCOME_UNKNOWN
import dev.hermes.android.queueBoxSaysNotSent
import dev.hermes.android.queuedHoldLine
import dev.hermes.android.queuedHoldReason
import dev.hermes.android.TURN_RESTARTED
import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.failedTurnHold
import dev.hermes.android.holdBehindFailedTurn
import dev.hermes.android.nextOfflineToSend
import dev.hermes.android.refusedWithItsOwnWords
import dev.hermes.android.releaseCutStream
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.liveCopyUnbound
import dev.hermes.android.ui.answeredOwnMessageIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** INT-121 chat join: where the merged patches meet (Root's rules). */
class ChatJoinRulesTest {
    // REVIEW-CHATQA merge rule 2: CHAT-121's refused file is a refusal with its own words in CHATQA's send paths, so it is never counted as
    // offline (never queued as "Not sent" to go again by itself), whatever the network says. A send that never left the phone still is.
    // Root (after the chat-121-10 fold): a file that could not get through says "Not sent" once, on its own row with its Send. TURN's hold would add
    // "Not sent. Your draft is kept." above the box; fileNotSent already keeps the message from every drain and the outbox.
    @Test fun aStalledFileSaysNotSentOnceOnItsRow() {
        val stalled = QueuedMessage("Synthetic report attached", listOf(json("name" to "Synthetic report.pdf")), id = "s1", state = "queued",
            outcome = FILE_NOT_SENT_OUTCOME, held = queuedHoldReason(AttachmentStalled()))
        assertTrue("its row says Not sent", queueBoxSaysNotSent(stalled))
        assertEquals("and nothing above the box says it again", "", queuedHoldLine(listOf(stalled)))
        val refused = QueuedMessage("Synthetic note", id = "r1", state = "queued", held = queuedHoldReason(AttachmentRefused("Scan.png", ATTACHMENT_TOO_LARGE_LINE)))
        assertTrue("a refused file still says why above the box", queuedHoldLine(listOf(refused)).isNotBlank())
    }

    // Chat join (Root, CHATQA fix 5 with CHAT fu9): a file that could not get through is never asked again. On the one-frame path its error wraps
    // the lost answer, which answerLost finds, so the send's failure path excludes it by name.
    @Test fun aStalledFileIsNeverAskedAgain() {
        assertTrue("a stalled one-frame file carries a lost answer", answerLost(AttachmentStalled(java.io.IOException(NATIVE_OUTCOME_UNKNOWN))))
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val rule = vm.substringAfter("            val lostAnswer = ", "").substringBefore("\n")
        assertTrue("the send's failure path leaves it out", rule.contains("answerLost(e) && e !is AttachmentStalled"))
    }

    // Chat join (Root msg_480d16659b3c): Hermes holds the send under its id (admission_conflict), so the phone forgets its copy. The saved record goes
    // before the bubble clears; the other way round, a kill or a slow device between the two left the record that brings the copy back.
    @Test fun anAnsweredCopyLosesItsSavedRecordBeforeItsBubble() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val forget = vm.substringAfter("    private suspend fun forgetAnsweredCopy(", "").substringBefore("\n    }\n")
        val record = forget.indexOf("if (!deletePending(send))")
        val bubble = forget.indexOf("it.copy(uncertain = \"\", status = \"\")")
        assertTrue("both steps are there", record > 0 && bubble > 0)
        assertTrue("the record goes first", record < bubble)
    }

    // Chat join (Root msg_22371a4afdad, rules19): Remove on an unconfirmed send deletes what is saved before its bubble goes; the other way round, an
    // app that died between the two woke up with the removed message saved and unconfirmed, and fix 5's ask-again could send it.
    @Test fun aRemovedSendLosesItsSavedRecordBeforeItsBubble() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val remove = vm.substringAfter("    fun removePendingSend() = viewModelScope.launch {", "").substringBefore("\n    }\n")
        val record = remove.indexOf("if (!deletePending(unknown))")
        val files = remove.indexOf("forgetPendingAttachments(scope, unknown.submissionId)")
        val bubble = remove.indexOf("it.copy(uncertain = \"\", status = \"\")")
        assertTrue("every step is there", record > 0 && files > 0 && bubble > 0)
        assertTrue("the saved record and the files go first, the bubble last", record < bubble && files < bubble)
        assertTrue("a delete that failed keeps the send to remove again", remove.contains("pending.putIfAbsent(scope.key, unknown)"))
    }

    // Chat join (rules21): sign-out's and device removal's delete of every unprotected row also clears CACHEFIX-B's in-memory transcripts, so a warm-up
    // write that landed after the first sweep is never drawn again once its saved row is gone.
    @Test fun signOutsSweepClearsTheTranscriptsHeldInMemory() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val db = File(module, "src/main/java/dev/hermes/android/data/ProjectionDb.kt").readText().replace("\r\n", "\n")
        val sweep = db.substringAfter("internal suspend fun deleteUnprotected(", "").substringBefore("\n    }\n")
        assertTrue("deleteUnprotected is there", sweep.isNotBlank())
        assertTrue("it clears the transcripts held in memory under the lock", sweep.contains("lock.withLock { recent.clear();"))
        assertTrue("delete() still forgets its own kind", db.contains("lock.withLock { forgetRecent(kind, scope); db.withTransaction {"))
    }

    // Chat join (rules22): sign-out, an account change and a phone removed on the computer drop the agent phone's copies too: the activity page
    // under the assistant's profile, the list's last rows and the approval board's records of the account.
    @Test fun signOutForgetsTheAgentPhonesCopies() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val forget = vm.substringAfter("    private suspend fun forgetScreens(", "").substringBefore("\n    }\n")
        assertTrue("forgetScreens is there", forget.isNotBlank())
        assertTrue("the activity page under the assistant's profile", forget.contains("deleteForgotten(scope.copy(profile = assistant, session = \"\"), dev.hermes.android.agentphone.AGENT_PHONE_LOG_KIND, removal)"))
        assertTrue("what this process holds", forget.contains("dev.hermes.android.agentphone.forgetAgentPhoneAccount(scope)"))
        val log = File(module, "src/main/java/dev/hermes/android/agentphone/AgentPhoneLogActivity.kt").readText().replace("\r\n", "\n")
        val drop = log.substringAfter("internal suspend fun forgetAgentPhoneAccount(", "").substringBefore("\n}\n")
        assertTrue("the last rows and the board", drop.contains("lastRows.remove(account)") && drop.contains("AgentApprovals.board.forget(account)"))
    }

    @Test fun aRefusedFileIsARefusalNeverOffline() {
        assertTrue(refusedWithItsOwnWords(AttachmentRefused("Report.pdf", ATTACHMENT_REFUSED_LINE)))
        assertTrue(refusedWithItsOwnWords(AttachmentRefused("Scan.png", ATTACHMENT_TOO_LARGE_LINE)))
        assertTrue("a file that stalled three times (CHAT-121 follow-up 8) waits for the owner too", refusedWithItsOwnWords(AttachmentStalled()))
        assertTrue(refusedWithItsOwnWords(NativeTurnNotSent("The model changed. Pick this message's model to send it.")))
        assertFalse(refusedWithItsOwnWords(NativeTurnNotSent("Couldn't reach Hermes.")))
        assertFalse(refusedWithItsOwnWords(java.io.IOException("Connection reset")))
    }

    // CHATQA follow-up 2 (Root msg_333cceb80261): one saved record per message. A "Not sent" message TURN or WEDGE held is never the outbox's
    // next one, and nothing behind it goes first; released, it goes.
    @Test fun theOutboxNeverSendsAHeldMessageNorAnythingBehindIt() {
        val held = QueuedMessage("first", id = "a", outcome = OFFLINE_OUTCOME, held = TURN_RESTARTED)
        val behind = QueuedMessage("second", id = "b", outcome = OFFLINE_OUTCOME)
        assertNull(nextOfflineToSend(Draft(queued = listOf(held, behind))))
        assertEquals("a", nextOfflineToSend(Draft(queued = listOf(held.copy(held = ""), behind)))?.id)
    }

    private val now = System.currentTimeMillis()
    private fun failedTurn(code: String, layer: String, retryable: Boolean, endedAt: Long, resetsAt: Double? = null) = json(
        "role" to "assistant", "content" to "", "display_kind" to "failed_turn", "timestamp" to endedAt / 1000.0,
        "display_metadata" to json("error_surface" to json("layer" to layer, "code" to code, "retryable" to retryable).apply { if (resetsAt != null) put("resets_at", resetsAt) }))
    private val asked = json("role" to "user", "content" to "Summarise the report")

    // TURN's drain rule for the outbox: it holds only when the chat's last turn failed and its error_surface says retrying does not help now.
    @Test fun theOutboxHoldsOnlyBehindAFailedTurnThatRetryingCannotHelp() {
        val endedAt = now - 60_000
        val tooLong = failedTurnHold(listOf(asked, failedTurn("context_overflow", "provider", false, endedAt)), now)
        assertNotNull("a chat too long for the model: hold", tooLong)
        assertEquals(endedAt, tooLong!!.second)
        assertNotNull("a limit that resets in an hour: hold",
            failedTurnHold(listOf(asked, failedTurn("rate_limit", "provider", true, endedAt, resetsAt = (now + 3_600_000) / 1000.0)), now))
        assertNull("a timeout Hermes says a retry can help: the messages go",
            failedTurnHold(listOf(asked, failedTurn("timeout", "provider", true, endedAt)), now))
        assertNull("a limit that has reset since: the messages go",
            failedTurnHold(listOf(asked, failedTurn("rate_limit", "provider", true, now - 600_000, resetsAt = (now - 300_000) / 1000.0)), now))
        assertNull("a turn that answered: the messages go", failedTurnHold(listOf(asked, json("role" to "assistant", "content" to "Here it is.")), now))
        assertNull("an empty chat", failedTurnHold(emptyList<JSONObject>(), now))
    }

    // Held as the open chat holds them: only the messages that waited behind the failed turn, each with its line; one written after it goes.
    @Test fun theOutboxHoldsOnlyTheMessagesThatWaitedBehindTheFailedTurn() {
        val endedAt = now - 60_000
        val waited = QueuedMessage("before", id = "a", outcome = OFFLINE_OUTCOME, createdAt = endedAt - 30_000)
        val later = QueuedMessage("after", id = "b", outcome = OFFLINE_OUTCOME, createdAt = endedAt + 30_000)
        val held = holdBehindFailedTurn(listOf(waited, later), "line", endedAt).associateBy { it.id }
        assertEquals("line", held.getValue("a").held)
        assertEquals("", held.getValue("b").held)
        assertTrue("no time from Hermes: every one waits", holdBehindFailedTurn(listOf(waited, later), "line", 0).all { it.held == "line" })
    }

    // Root msg_1f1ef8bc48aa (2), seen live on 17 Nov: a prompt is blue only with its own answer words (or a file or media Beacon sends back). A row
    // that only calls a tool, as JOIN-RA's did before a Hermes restart cut it, answers nothing.
    @Test fun aToolCallWithNoWordsNeverTicksThePromptBlue() {
        val ask = Message("u1", "user", "Run the long command, then tell me")
        val toolOnly = Message("a1", "assistant", "", tools = listOf(JSONObject().put("id", "call-1")))
        val note = Message("n1", "assistant", "This turn did not complete. Some actions may already have run.", displayKind = "failed_turn")
        assertEquals("cut after a word-less tool call: grey", emptySet<String>(), answeredOwnMessageIds(listOf(ask, toolOnly, note), replying = false))
        assertEquals("a saved tool call mid-turn: still grey", emptySet<String>(), answeredOwnMessageIds(listOf(ask, toolOnly), replying = false))
        val wordsFirst = Message("a2", "assistant", "Step 1: three sentences.", tools = listOf(JSONObject().put("id", "call-2")))
        assertEquals("words before the tool answer it", setOf("u1"), answeredOwnMessageIds(listOf(ask, wordsFirst, note), replying = false))
        val fileBack = Message("a3", "assistant", "", attachments = listOf(JSONObject().put("name", "report.pdf")))
        assertEquals("a file sent back answers it", setOf("u1"), answeredOwnMessageIds(listOf(ask, fileBack), replying = false))
    }

    // Root msg_62e9a0492763 (TURN follow-up 4 with WEDGE follow-up 3): when the lost reply's choice comes up, the stream left on the cut turn is let go
    // without losing what the reply said. Before the merge fix, WEDGE's release cleared the live words, so the cut reply's unsaved words were gone.
    @Test fun theLostReplyChoiceKeepsTheCutReplysWordsWhenItLetsGoOfItsStream() {
        val cut = ChatState(sessionId = "chat-1", streamId = "runtime-1", liveText = "Second part", liveSealed = listOf("First part"), liveSealedAt = listOf(1),
            liveBase = 1, liveBasePrompt = "1", liveOrdinal = 2)
        val released = releaseCutStream(cut, "runtime-1")
        assertEquals("", released.streamId)
        assertTrue(released.liveText.isEmpty() && released.liveSealed.isEmpty() && released.liveOrdinal == 0)
        // rules17: TURN follow-up 5's place fields go with the sealed words they pair with.
        assertTrue(released.liveSealedAt.isEmpty() && released.liveBase == 0 && released.liveBasePrompt.isEmpty())
        assertEquals(listOf("First part", "Second part"), released.raw.rows("_unconfirmed_replies").map { it.getString("text") })
        assertEquals("another stream is left alone", cut, releaseCutStream(cut, "runtime-2"))
    }

    // Root msg_b0eda5e7404d (CHATQA follow-up 3 with WEDGE follow-up 3): on open, the lost-turn route goes first. CHATQA's one look at Hermes' live
    // inventory stands aside for a reply Hermes lost (the line or the choice shows), so a chat never shows both the lost line and Stop.
    @Test fun theOpenLookAsksTheLostTurnRouteBeforeHermesLiveInventory() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val look = vm.substringAfter("    private suspend fun followUnseenTurn(", "").substringBefore("\n    }\n")
        val route = look.indexOf("c.lostTurnChats(listOf(scope.session))")
        val inventory = look.indexOf("c.nativeRpc(\"session.active_list\", JSONObject())")
        assertTrue("the route is asked", route > 0)
        assertTrue("before the live inventory", inventory > route)
        assertTrue("a lost reply already shown stands the look aside", look.indexOf("if (_chat.value.lostWaiting || _chat.value.status == LOST_REPLY_STATUS) return true") in 1 until route)
        assertTrue("and again after the inventory read", look.contains("followsHere(scope) || _chat.value.lostWaiting || _chat.value.status == LOST_REPLY_STATUS) return true"))
    }

    // REVIEW-CHATQA rule 1 (msg_eaeb5a1f8d9d): WEDGE's lost-turn checks ask followsHere, never whether some stream job is alive. A job the chat was
    // left with is alive but muted (CHATQA's D8), and must not keep the lost line or choice from showing on a reopen.
    @Test fun theLostTurnChecksAskWhetherAStreamFollowsHereNotWhetherOneIsAlive() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        // wedge-121-6: the open check and a cold run marker share one look, closeLostOnOpen; showLostOnOpen only launches it.
        assertTrue("the open check is the shared look", vm.contains("viewModelScope.launch { closeLostOnOpen(source, scope, request) }"))
        val open = vm.substringAfter("    private suspend fun closeLostOnOpen(", "").substringBefore("\n    }\n")
        assertTrue("the open check is found", open.isNotBlank())
        assertTrue(open.contains("fun idle() = _chat.value.streamId.isBlank() && !followsHere(scope)"))
        val reattach = vm.substringAfter("    private suspend fun reattachMarkedRun(", "").substringBefore("    private suspend fun followUnseenTurn(")
        val unseen = reattach.indexOf("return followUnseenTurn(c, scope, request)")
        val close = reattach.indexOf("val lostLook = closeLostOnOpen(c, scope, request)")
        val ask = reattach.indexOf("c.lookAtTurn(scope, lostCloseFor(scope))")
        assertTrue("no marker: CHATQA's look; a marker: the route close, then Hermes is asked", unseen in 1 until close && close < ask)
        val alive = Regex("""streams\[[^\]]*\]\?\.isActive""").findAll(vm).map { m -> vm.lastIndexOf("fun ", m.range.first) }.map { vm.substring(it, minOf(vm.length, it + 40)) }.toList()
        assertEquals("only followsHere reads whether a stream job is alive", listOf("fun followsHere(scope: Scope) = streamFo"), alive)
    }

    /** rules24 (b): a live copy is drawn only while a stream is bound to it. */
    @Test fun aLiveCopyWithNoStreamBoundIsUnbound() {
        assertTrue(liveCopyUnbound(ChatState(liveText = "OK")))
        assertTrue(liveCopyUnbound(ChatState(liveReasoning = "notes")))
        assertFalse(liveCopyUnbound(ChatState(streamId = "chat", liveText = "OK")))
        assertFalse(liveCopyUnbound(ChatState()))
    }

    /** rules24 (b), G6b restart 17: the turn's own end releases a reply it left painted after its stream id went blank; another turn's end does not. */
    @Test fun anOwnedEndReleasesAReplyLeftPaintedWithNoStreamBound() {
        val painted = ChatState(sessionId = "chat", liveText = "OK", liveReasoning = "notes", raw = JSONObject().put("_display_submission_id", "sub-b"))
        val released = releaseTerminalChat(painted, "chat", replyConfirmed = true, terminalStatus = "", submission = "sub-b")
        assertEquals("", released.liveText)
        assertEquals("", released.liveReasoning)
        assertEquals("", released.streamId)
        assertFalse(liveCopyUnbound(released))
        org.junit.Assert.assertSame(painted, releaseTerminalChat(painted, "chat", replyConfirmed = true, terminalStatus = "", submission = "sub-a"))
        val following = painted.copy(streamId = "other")
        org.junit.Assert.assertSame(following, releaseTerminalChat(following, "chat", replyConfirmed = true, terminalStatus = ""))
    }

    /** rules24 (b): a turn's end is in the saved chat when Hermes saved a reply for it; its own note on a cut turn is not one. */
    @Test fun theSavedChatHoldsATurnsEndOnlyWithAReply() {
        val prompt = json("role" to "user", "text" to "Synthetic request")
        assertTrue(dev.hermes.android.savedTurnHoldsReply(listOf(prompt, json("role" to "assistant", "text" to "OK"))))
        assertFalse(dev.hermes.android.savedTurnHoldsReply(listOf(prompt, json("role" to "assistant", "text" to "This turn did not complete.", "display_kind" to "failed_turn"))))
        assertFalse(dev.hermes.android.savedTurnHoldsReply(listOf(json("role" to "assistant", "text" to "Earlier"), prompt)))
    }

    /** rules24 (a): every stream attach starts its runtime's boundary and saved-turn proof fresh, before it restores a frame. */
    @Test fun anAttachNeverCarriesAnEarlierTurnsBoundaryOrProof() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val attach = vm.substring(vm.indexOf("private fun attachStream("), vm.indexOf("val streamJob = currentCoroutineContext()[Job]"))
        assertTrue("attachStream resets the runtime's boundary and proof", attach.contains("liveBoundaries.remove(it); persistedTurns.remove(it)"))
    }
}
