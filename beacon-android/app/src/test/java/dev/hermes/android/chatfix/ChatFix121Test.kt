package dev.hermes.android.chatfix

import dev.hermes.android.ChatState
import dev.hermes.android.UNCONFIRMED_REPLIES
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.liveCopyUnbound
import dev.hermes.android.releaseCutStream
import dev.hermes.android.releaseTerminalChat
import dev.hermes.android.savedTurnHoldsReply
import dev.hermes.android.unboundReleaseConfirmed
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CHATFIX-121: REVIEW-JOIN-121's findings that a JVM test can decide. The view model's own paths are proven on the device (LostReplyChoiceUiTest). */
class ChatFix121Test {
    private fun vmSource(): String {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        return File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
    }
    private fun idleRelease(chat: ChatState, rows: List<JSONObject>) =
        releaseTerminalChat(chat, "", replyConfirmed = unboundReleaseConfirmed(chat, savedTurnHoldsReply(rows)), terminalStatus = "")

    /**
     * P2-2: a cut turn saved its commentary before a tool ("I'll check the file first.") and never its later words. Its placed live copy, left unbound, is
     * released by the idle reconcile: the words Hermes never saved stay as a kept copy, as TURN's own end keeps them.
     */
    @Test fun aCutTurnsUnsavedWordsStayWhenOnlyItsCommentaryWasSaved() {
        val rows = listOf(json("role" to "user", "text" to "Synthetic question", "row_id" to 1),
            json("role" to "assistant", "text" to "I'll check the file first.", "row_id" to 2),
            json("role" to "assistant", "text" to "This turn did not complete.", "display_kind" to "failed_turn", "row_id" to 3))
        val unbound = ChatState(sessionId = "chat", liveText = "The synthetic total is 42.", liveOrdinal = 2, raw = JSONObject().put("messages", JSONArray(rows)))
        assertTrue(liveCopyUnbound(unbound))
        assertTrue("the saved turn holds a reply (its commentary)", savedTurnHoldsReply(rows))
        assertEquals("the words Hermes never saved stay on screen as a kept copy", 1, idleRelease(unbound, rows).raw.rows(UNCONFIRMED_REPLIES).size)
    }

    /** P2-2: a placed copy is retired by its place: the F3 shape (the follower's "OK" saved once, its live copy placed first) leaves no second copy. */
    @Test fun aPlacedCopyIsRetiredByItsPlace() {
        val rows = listOf(json("role" to "user", "text" to "Synthetic follower", "row_id" to 10), json("role" to "assistant", "text" to "OK", "row_id" to 11))
        val placed = ChatState(sessionId = "chat", liveText = "OK", liveOrdinal = 1, raw = JSONObject().put("messages", JSONArray(rows)))
        val released = idleRelease(placed, rows)
        assertEquals("the saved reply is not shown twice", 0, released.raw.rows(UNCONFIRMED_REPLIES).size)
        assertFalse(liveCopyUnbound(released))
    }

    /** P2-2: an unplaced copy keeps today's rule: the saved chat holding the turn's reply drops it (it would show twice); otherwise its words are kept. */
    @Test fun anUnplacedCopyKeepsTodaysRule() {
        val replied = listOf(json("role" to "user", "text" to "Synthetic follower", "row_id" to 10), json("role" to "assistant", "text" to "OK", "row_id" to 11))
        val unplaced = ChatState(sessionId = "chat", liveText = "OK", raw = JSONObject().put("messages", JSONArray(replied)))
        assertTrue(unboundReleaseConfirmed(unplaced, savedHoldsReply = true))
        assertEquals(0, idleRelease(unplaced, replied).raw.rows(UNCONFIRMED_REPLIES).size)
        val cut = listOf(json("role" to "user", "text" to "Synthetic follower", "row_id" to 10))
        assertFalse(unboundReleaseConfirmed(unplaced, savedHoldsReply = false))
        assertEquals(1, idleRelease(unplaced.copy(raw = JSONObject().put("messages", JSONArray(cut))), cut).raw.rows(UNCONFIRMED_REPLIES).size)
    }

    /** P2-3: the lost reply's choice lets go of a live copy no stream is bound to (it would stay drawn as Working beside the choice); its words are kept. */
    @Test fun theLostChoiceLetsGoOfAnUnboundLiveCopy() {
        val unbound = ChatState(sessionId = "chat", liveText = "Synthetic words", liveReasoning = "Synthetic notes", raw = JSONObject().put("messages", JSONArray()))
        assertTrue(liveCopyUnbound(unbound))
        val released = releaseCutStream(unbound, "")
        assertFalse("the choice leaves no live copy drawn as Working", liveCopyUnbound(released))
        assertEquals("its words are kept", 1, released.raw.rows(UNCONFIRMED_REPLIES).size)
        val followed = unbound.copy(streamId = "runtime-2")
        assertEquals("a chat that follows another stream keeps its own", followed, releaseCutStream(followed, "runtime-1"))
    }

    /**
     * P1-1, P1-2 and P3-7: Hermes is asked for the cards it waits on after any look (lookAtTurn, when Hermes says "waiting") and on a re-subscription after
     * a drop, both only when no card shows; the reset mark is gone, and each prompt read closes the connection it opens.
     */
    @Test fun oneRuleAsksForTheCardsHermesWaitsOn() {
        val vm = vmSource()
        val look = vm.substring(vm.indexOf("private suspend fun BeaconClient.lookAtTurn("), vm.indexOf("private fun showLostOnOpen("))
        assertTrue("a look that hears 'waiting' with no card shown asks for the cards", look.contains("it?.second == \"waiting\"") && look.contains("pollPrompts(this, scope)"))
        assertFalse("rules27c's reset mark is gone", vm.contains("promptsDroppedByReset"))
        assertTrue("a re-subscription after a drop asks only when no card shows (rules27b, kept: the rules27 fixture test needs it)",
            vm.contains("droppedOnce && resumedFrom.isNotBlank() && _chat.value.approval == null && _chat.value.clarification == null"))
        val poll = vm.substring(vm.indexOf("private suspend fun pollPrompts("), vm.indexOf("private fun watchSession("))
        assertTrue("the read uses a connection of its own and closes it", poll.contains(".detached()") && poll.contains("finally") && poll.contains("closeNative()"))
        assertFalse("callers hand the read their client; it detaches its own", vm.contains("pollPrompts(c.detached()"))
        val watch = vm.substring(vm.indexOf("private fun watchSession("), vm.indexOf("private fun acceptsPrompt("))
        assertFalse("the watch's running branch (never reached: the passive read names no stream) reads no prompts", watch.contains("pollPrompts("))
        // REVIEW-FIXCHAT-121 P1: and a reconcile (the only time frames were missed), whatever card shows.
        assertTrue("a reconcile reads Hermes' cards", vm.contains("if (event.type == \"state_saved\" && sourceScope == scope) launch { pollPrompts(c, scope) }"))
        assertEquals("the call sites: a look, a re-subscription after a drop, a reconcile, the open's own read, a turn's end, the lost reply's choice", 6,Regex("""\bpollPrompts\(""").findAll(vm).count() - 1)
    }

    /** Follow-up 1, REVIEW-JOINB-121 P3-4: no code throws "This Hermes can't take this attachment" any more (CHAT replaced it with AttachmentRefused); its checks go. */
    @Test fun theRetiredAttachmentRefusalIsNoLongerChecked() {
        val retired = "This Hermes can't take this attachment. The message stays on this phone."
        assertFalse(retired in dev.hermes.android.SEND_REFUSALS)
        assertEquals("", dev.hermes.android.queuedHoldReason(dev.hermes.android.data.NativeTurnNotSent(retired)))
    }

    /** Follow-up 1, REVIEW-JOINB-121 P3-1: removing a copy forgets its kept files and send record whatever chat is open; only the drain needs the chat open. */
    @Test fun removingACopyForgetsItsFilesWhicheverChatIsOpen() {
        val vm = vmSource()
        val remove = vm.substring(vm.indexOf("fun removeUnresolvedLocalMessage("), vm.indexOf("fun removeConfirmedLocalCopy("))
        val gate = remove.indexOf("if (removed && sourceScope == scope)")
        assertTrue("the drain is gated on the open chat", gate > 0 && remove.indexOf("drainQueued", gate) > gate)
        assertTrue("the kept files and the record go before the gate", remove.indexOf("releaseKeptFiles") in 0 until gate && remove.indexOf("forgetPendingAttachments") in 0 until gate)
        val pending = vm.substring(vm.indexOf("fun removePendingSend()"), vm.indexOf("private suspend fun restoreWithdrawn("))
        assertTrue("Remove this copy lets go of its kept files", pending.contains("releaseKeptFiles(scope, unknown.draft.attachments)"))
    }

    /**
     * Follow-up 1, JOINBFIX: "Not sent yet" ends only when the open chat's box and every other chat's are clear of waiting messages; follow-up 3 (Root):
     * and only from the account and profile it was raised for.
     */
    @Test fun theNotSentYetNoticeEndsOnlyWhenNothingWaitsAnywhere() {
        val owner = dev.hermes.android.core.Scope("https://hermes.example.com", "synthetic-owner", "default")
        val open = owner.session("open-chat")
        fun ends(raisedFor: dev.hermes.android.core.Scope?, waits: Boolean, outbox: List<String>) = dev.hermes.android.offlineNoticeEnds(raisedFor, open, waits, outbox)
        assertTrue("the open chat's message went and nothing else waits", ends(owner, false, listOf("open-chat")))
        assertTrue(ends(owner, false, emptyList()))
        assertFalse("the open chat still holds a waiting message", ends(owner, true, emptyList()))
        assertFalse("another chat's message still waits", ends(owner, false, listOf("open-chat", "other-chat")))
        assertFalse("raised for another profile: this profile's state does not end it", ends(owner.copy(profile = "coder"), false, emptyList()))
        assertFalse("raised for no one this run", ends(null, false, emptyList()))
    }

    /** REVIEW-FIXCHAT-121 P3: a kept file is "in use" for a chat no longer open too: its saved draft is read when memory has let it go. */
    @Test fun aKeptFileStillNamedByALeftChatsSavedDraftIsKept() {
        val vm = vmSource()
        val release = vm.substring(vm.indexOf("private suspend fun releaseKeptFiles("), vm.indexOf("/** Hermes holds the prompt, whether"))
        assertTrue("the saved draft is read when memory has none", release.contains("drafts[scope.key] ?:") && release.contains("app.cache.read(scope, draftKind(scope))"))
    }

    /** REVIEW-FIXCHAT-121 P3: a full store says so in the owner's words (it was "Hermes couldn't do that"), and the store refuses with exactly that line. */
    @Test fun aFullStoreSaysSoInPlainWords() {
        val line = "Phone storage for drafts is full. Send or clear a draft."
        assertEquals(line, dev.hermes.android.data.appErrorText(IllegalStateException(line)))
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        assertTrue("the store's refusal is that line", File(module, "src/main/java/dev/hermes/android/data/ProjectionDb.kt").readText().contains("error(\"$line\")"))
    }

    /**
     * CHATFIX-121 follow-up 5 (REVIEW-FIXCHAT-121 R2 #9): on a full phone every catch that says a save failed says why ("try again" would fail the same
     * way): the three in the review (queueOffline, the send-failure save, retainDraftAndLeave) and the same lines in the send's own save, the busy queue
     * and the steer. A send the full store refused is refused with those words: shown, and never sent again by itself.
     */
    @Test fun aSaveThatFailsOnAFullPhoneSaysSo() {
        val full = "Phone storage for drafts is full. Send or clear a draft."
        val saveLines = listOf("Couldn't save the message. Keep the draft open and try again.", "Couldn't save this change. Keep the chat open and check the message.",
            "Couldn't save the latest change. Your draft stays on this phone.")
        // Every place the view model shows or throws one of these lines; the bare entry in SEND_REFUSALS is a list, not a catch.
        val sites = vmSource().lines().filter { line -> saveLines.any { "\"$it\"" in line } && !line.trimStart().startsWith("\"") }
        assertEquals("queueOffline, the send's two saves, retainDraftAndLeave, the busy queue and the steer: $sites", 6, sites.size)
        sites.forEach { assertTrue("says the store is full when it is: ${it.trim()}", "if (draftStoreFull(" in it && "DRAFT_STORE_FULL_ERROR else \"" in it) }
        assertTrue("a send the full store refused is refused with its own words",
            dev.hermes.android.refusedWithItsOwnWords(dev.hermes.android.data.NativeTurnNotSent(full, IllegalStateException(full))))
        // Only the store's own refusal reads as full; a checkpoint too large for one row keeps its own line.
        assertFalse(full == dev.hermes.android.data.appErrorText(IllegalArgumentException("Local checkpoint exceeds 16 MiB")))
        assertFalse(full == dev.hermes.android.data.appErrorText(java.io.IOException("Synthetic disk error")))
    }

    /** REVIEW-FIXCHAT-121 P3 (lean): each KDoc sits on its own function: the box rule's on boxHoldsWaiting, and one on pollPrompts. */
    @Test fun eachKDocSitsOnItsOwnFunction() {
        val vm = vmSource()
        fun kdocAbove(signature: String) = vm.substringBefore(signature).trimEnd().lines().takeLastWhile { it.trimStart().startsWith("/**") || it.trimStart().startsWith("*") }
        assertTrue("the box rule's text is right above boxHoldsWaiting", kdocAbove("internal fun boxHoldsWaiting(").joinToString("\n").contains("The phone's box holds a message"))
        assertEquals("one KDoc on pollPrompts", 1, kdocAbove("private suspend fun pollPrompts(").count { it.trimStart().startsWith("/**") })
    }

    /** P3-7: one reader of a saved row's submission id. */
    @Test fun oneReaderOfARowsSubmission() {
        assertFalse(vmSource().contains("savedRowSubmissionId"))
    }
}
