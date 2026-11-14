package dev.hermes.android.ui

import dev.hermes.android.Message
import dev.hermes.android.afterReceipt
import dev.hermes.android.afterTurnFrame
import androidx.compose.ui.graphics.toArgb
import dev.hermes.android.core.QueuedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ticks beside an own message: one grey, two grey, two blue. Synthetic text only. */
class ChatTicksTest {
    private fun local(state: String, outcome: String = "", kind: String = "queue") = QueuedMessage("Hello", state = state, outcome = outcome, kind = kind)
    private fun own(id: String, kind: String = "") = Message(id, "user", "Question $id", displayKind = kind)
    private fun reply(id: String, kind: String = "", text: String = "Answer $id") = Message(id, "assistant", text, displayKind = kind)

    @Test fun aMessageThePhoneStillHoldsShowsOneTick() {
        for (state in listOf("queued", "sending", "unknown", "something-new")) {
            assertEquals(state, SendTick.Pending, localSendTick(local(state), answered = false))
            // An unconfirmed message cannot be called answered, whatever follows it.
            assertEquals(state, SendTick.Pending, localSendTick(local(state), answered = true))
        }
    }

    @Test fun aMessageHermesHasShowsTwoGreyTicksUntilBeaconAnswers() {
        val held = listOf(local("accepted"), local("sent"), local("settled"))
        for (item in held) {
            assertEquals(item.state + "/" + item.outcome, SendTick.Delivered, localSendTick(item, answered = false))
            assertEquals(item.state + "/" + item.outcome, SendTick.Answered, localSendTick(item, answered = true))
        }
    }

    @Test fun aMessageHermesNeverStartedIsNeverShownDeliveredOrAnswered() {
        // Only a cancelled turn with no saved row is proof Hermes never started the message. It lives in the queue box as "Not sent";
        // if one ever reached the chat it must not look delivered.
        assertEquals(SendTick.Pending, localSendTick(local("settled", "cancelled"), answered = false))
        assertEquals(SendTick.Pending, localSendTick(local("settled", "cancelled"), answered = true))
    }

    @Test fun aMessageHermesRanShowsTwoBlueTicksWhateverItsTurnDid() {
        // Hermes settles completed, failed, rejected and interrupted only for a turn it started (settle_session_input), so Hermes started the
        // message: two blue ticks, with or without a reply row after it.
        for (outcome in listOf("completed", "failed", "rejected", "interrupted")) {
            assertEquals(outcome, SendTick.Answered, localSendTick(local("settled", outcome), answered = false))
            assertEquals(outcome, SendTick.Answered, localSendTick(local("settled", outcome), answered = true))
        }
    }

    @Test fun aSettledMessageWithNoReadOutcomeIsBlueOnlyOnceAReplyFollows() {
        assertEquals(SendTick.Delivered, localSendTick(local("settled"), answered = false))
        assertEquals(SendTick.Answered, localSendTick(local("settled"), answered = true))
    }

    @Test fun aMessageHermesHoldsARowForIsDeliveredEvenIfItsTurnWasCancelled() {
        val withRow = QueuedMessage("Hello", state = "settled", outcome = "cancelled", rowId = "42")
        assertEquals(SendTick.Delivered, localSendTick(withRow, answered = false))
        assertEquals(SendTick.Answered, localSendTick(withRow, answered = true))
    }

    @Test fun aSavedRowIsDeliveredAndAnsweredOnceAReplyFollows() {
        assertEquals(SendTick.Delivered, historySendTick(answered = false))
        assertEquals(SendTick.Answered, historySendTick(answered = true))
    }

    @Test fun aReplyAfterAMessageAnswersIt() {
        val rows = listOf(own("1"), reply("2"), own("3"))
        assertEquals(setOf("1"), answeredOwnMessageIds(rows, replying = false))
    }

    @Test fun noReplyMeansNoBlueTick() {
        assertTrue(answeredOwnMessageIds(listOf(own("1"), own("2")), replying = false).isEmpty())
        assertTrue(answeredOwnMessageIds(emptyList(), replying = true).isEmpty())
    }

    // Blue needs real data for THIS message. A phone copy whose admission Hermes still holds as "queued" (it is running another turn) has none yet.
    private fun copy(id: String, state: String, kind: String = "queue", admission: String = "") =
        QueuedMessage("Copy $id", state = state, kind = kind, id = id, admissionStatus = admission)
    private fun bubble(id: String) = Message("queued:$id", "user", "Copy $id", displayKind = "local_input")
    /** The tick ChatScreen gives a held copy: the rows' answered set first, then the copy's own rule. */
    private fun tickOf(copy: QueuedMessage, rows: List<Message>, replying: Boolean) = localSendTick(copy, "queued:${copy.id}" in answeredOwnMessageIds(rows, replying))

    @Test fun aReplyThatIsStreamingNowAnswersTheSavedOwnRowsBeforeIt() {
        val rows = listOf(own("1"), reply("2"), own("3"), own("4"))
        assertEquals(setOf("1", "3", "4"), answeredOwnMessageIds(rows, replying = true))
    }

    @Test fun aMessageQueuedBehindAnotherTurnShowsTwoGreyTicksWhileThatReplyStreams() {
        // The review's exact case: the chat is in use from another client, Hermes answers this phone's send "queued" and the other turn's reply streams.
        val queuedBehind = copy("m", "accepted", admission = "queued")
        val rows = listOf(own("1"), reply("2"), bubble("m"))
        assertEquals(SendTick.Delivered, tickOf(queuedBehind, rows, replying = true))
    }

    @Test fun aMessageQueuedBehindAnotherTurnIsNeverBlueEvenWhenAnAssistantRowFollowsIt() {
        // While its admission is still queued, a reply row after it is the other turn's, not an answer to this message.
        val queuedBehind = copy("m", "accepted", admission = "queued")
        assertEquals(SendTick.Delivered, tickOf(queuedBehind, listOf(bubble("m"), reply("3")), replying = false))
        assertEquals(SendTick.Delivered, tickOf(queuedBehind, listOf(bubble("m"), reply("3")), replying = true))
    }

    @Test fun aMessageWhoseAdmissionStartedIsAnsweredByItsLiveReply() {
        assertEquals(SendTick.Answered, tickOf(copy("m", "accepted", admission = "started"), listOf(own("1"), bubble("m")), replying = true))
        // Hermes told nothing about the admission (a legacy gateway, or a copy saved before this field existed): as before.
        assertEquals(SendTick.Answered, tickOf(copy("m", "accepted"), listOf(own("1"), bubble("m")), replying = true))
        // Started, but no reply yet: two grey ticks.
        assertEquals(SendTick.Delivered, tickOf(copy("m", "accepted", admission = "started"), listOf(own("1"), bubble("m")), replying = false))
    }

    @Test fun aMessageThatHermesStartsLaterTurnsBlueOnlyOnceItsAdmissionIsStarted() {
        val waiting = copy("m", "accepted", admission = "queued")
        val rows = listOf(own("1"), bubble("m"))
        assertEquals(SendTick.Delivered, tickOf(waiting, rows, replying = true))
        // The receipt check finds the admission started: the same copy, now answered by the reply that streams.
        val started = waiting.afterReceipt("accepted", "", "started")
        assertEquals("started", started.admissionStatus)
        assertEquals(SendTick.Answered, tickOf(started, rows, replying = true))
    }

    // Hermes answers "queued" to every fresh send, an idle chat too (the row is queued until the reply loop claims it). The tick turns blue when the
    // frames of THIS message's own turn arrive (they carry its admission), not when the reply merely streams.
    private fun withAdmission(copy: QueuedMessage, admission: String) = copy.copy(admissionId = admission)

    @Test fun aSendOnAnIdleChatTurnsBlueAsSoonAsItsOwnTurnStartsAndBeforeTheReplyEnds() {
        val rows = listOf(own("1"), bubble("m"))
        val sent = withAdmission(copy("m", "accepted", admission = "queued"), "admission-m")
        // Hermes answered "queued" and the reply is not here yet: the phone shows what it knows, two grey ticks.
        assertEquals(SendTick.Delivered, tickOf(sent, rows, replying = false))
        // The reply streams, but nothing yet says whose turn it is: still grey.
        assertEquals(SendTick.Delivered, tickOf(sent, rows, replying = true))
        // The first frame of the turn names this message's admission: Hermes started it, and the reply that streams answers it.
        val started = requireNotNull(sent.afterTurnFrame("admission-m"))
        assertEquals(SendTick.Answered, tickOf(started, rows, replying = true))
    }

    @Test fun aSendBehindAnotherTurnStaysGreyUntilItsOwnTurnStarts() {
        val rows = listOf(own("1"), reply("2"), bubble("m"))
        val waiting = withAdmission(copy("m", "accepted", admission = "queued"), "admission-m")
        // The other turn streams: its frames name its own admission, so nothing changes for this message.
        assertNull(waiting.afterTurnFrame("admission-other"))
        assertEquals(SendTick.Delivered, tickOf(waiting, rows, replying = true))
        // Hermes claims this message: its frames name its admission.
        assertEquals(SendTick.Answered, tickOf(requireNotNull(waiting.afterTurnFrame("admission-m")), rows, replying = true))
    }

    @Test fun aSteerDeliveredIntoTheRunningReplyAndCopiesHermesAlreadySavedKeepTheRule() {
        val steer = copy("s", "accepted", kind = "steer")
        val sent = copy("t", "sent", admission = "queued")
        val settled = copy("z", "settled", admission = "queued")
        val rows = listOf(bubble("s"), bubble("t"), bubble("z"))
        assertEquals(SendTick.Answered, tickOf(steer, rows, replying = true))
        assertEquals(SendTick.Answered, tickOf(sent, rows, replying = true))
        assertEquals(SendTick.Answered, tickOf(settled, rows, replying = true))
    }

    @Test fun aReplyWithToolsOnlyStillMeansBeaconIsOnIt() {
        assertEquals(setOf("1"), answeredOwnMessageIds(listOf(own("1"), reply("2", text = "")), replying = false))
    }

    @Test fun anAlertACallLineOrABackgroundResultIsNotAReplyToTheMessage() {
        val rows = listOf(
            own("1"),
            Message("alert:n1", "assistant", "An email alert"),
            Message("call:e1", "assistant", "Spoken line"),
            reply("2", kind = "async_delegation_complete"),
            reply("3", kind = "process_complete"),
        )
        assertTrue(answeredOwnMessageIds(rows, replying = false).isEmpty())
        // A real reply after those still answers it.
        assertEquals(setOf("1"), answeredOwnMessageIds(rows + reply("4"), replying = false))
    }

    @Test fun aKeptStreamedReplyCountsAsBeaconsAnswer() {
        assertEquals(setOf("1"), answeredOwnMessageIds(listOf(own("1"), reply("unconfirmed:a", kind = "unconfirmed_reply")), replying = false))
    }

    @Test fun aScheduledPostIsNotAnOwnMessageSoItNeverGetsATick() {
        val post = Message("p1", "user", "Report", displayKind = "cron_delivery")
        assertTrue(answeredOwnMessageIds(listOf(post, reply("2")), replying = false).isEmpty())
    }

    @Test fun twoTurnsAreJudgedOneByOne() {
        val rows = listOf(own("1"), reply("2"), own("3"), reply("4"), own("5"))
        assertEquals(setOf("1", "3"), answeredOwnMessageIds(rows, replying = false))
    }

    @Test fun aSteerIsMarkedWhetherLocalOrSaved() {
        assertTrue(isSteerMessage(own("1", kind = "steer")))
        assertFalse(isSteerMessage(own("1")))
        assertFalse(isSteerMessage(reply("2")))
        assertTrue(isSteerMessage(local("sending", kind = "steer")))
        assertFalse(isSteerMessage(local("sending")))
    }

    @Test fun everyTickHasAShortTalkBackWordAndATestTag() {
        // One grey tick also covers a send whose outcome is not known, when Hermes may already have the message: TalkBack must not say "not sent".
        assertEquals(mapOf(SendTick.Pending to "Not confirmed yet", SendTick.Delivered to "Delivered", SendTick.Answered to "Answered"), SendTick.entries.associateWith(::sendTickLabel))
        assertEquals(listOf("pending", "delivered", "answered"), SendTick.entries.map { it.name.lowercase() })
        assertEquals(listOf(1, 2, 2), SendTick.entries.map(::sendTickCount))
        assertEquals(listOf(false, false, true), SendTick.entries.map(::sendTickBlue))
    }

    @Test fun theChatTextNamesNoStateAtAll() {
        for (banned in listOf("Queued on this phone", "Send outcome unknown", "Not sure your last message arrived.", "Queued message canceled", "Message rejected", "Sending guidance")) {
            assertFalse(banned, banned in ChatStrings)
        }
    }

    @Test fun theBlueTicksReadOnTheLightAndTheDarkBubble() {
        // Bubble colours sampled from the device screenshots: the light theme's own bubble (222, 210, 196) and the dark theme's (44, 46, 45).
        val lightBubble = 0xFFDED2C4L
        val darkBubble = 0xFF2C2E2DL
        val onLight = answeredTickBlueArgb(lightBubble)
        val onDark = answeredTickBlueArgb(darkBubble)
        // 3:1 is the contrast guidance for a graphic that carries meaning.
        assertTrue("on the light bubble: ${contrastRatio(onLight, lightBubble)}", contrastRatio(onLight, lightBubble) >= 3.0)
        assertTrue("on the dark bubble: ${contrastRatio(onDark, darkBubble)}", contrastRatio(onDark, darkBubble) >= 3.0)
        // Both are blue (blue is the strongest channel), and they differ: one blue could not read on both.
        for (blue in listOf(onLight, onDark)) assertTrue((blue and 0xFF) > ((blue shr 16) and 0xFF) && (blue and 0xFF) > ((blue shr 8) and 0xFF))
        assertNotEquals(onLight, onDark)
    }

    @Test fun theBlueTicksReadOnEverySkinLightAndDarkOnTheAppsRealBubbleColour() {
        // The bubble is the theme's own primaryContainer from nativeSkinColorScheme (lerp(sidebar, accent, .13) after the accent has been made readable),
        // not a sampled colour, so the accent adjustment is included.
        val file = requireNotNull(listOf(java.io.File("src/main/assets/native-skins.json"), java.io.File("app/src/main/assets/native-skins.json"), java.io.File("beacon-android/app/src/main/assets/native-skins.json")).firstOrNull { it.isFile })
        val palettes = org.json.JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(21, NativeSkins.size)
        val worst = mutableListOf<String>()
        var lowest = Double.MAX_VALUE; var lowestAt = ""
        var lowestOldDark = Double.MAX_VALUE; var lowestOldDarkAt = ""
        for (skin in NativeSkins) for (dark in listOf(false, true)) {
            val scheme = nativeSkinColorScheme(palettes.getJSONObject(skin).getJSONObject(if (dark) "dark" else "light"), dark)
            val bubble = scheme.primaryContainer.toArgb().toLong() and 0xFFFFFFFFL
            val blue = answeredTickBlueArgb(bubble)
            val ratio = contrastRatio(blue, bubble)
            if (ratio < 3.0) worst += "$skin dark=$dark ratio=$ratio"
            if (ratio < lowest) { lowest = ratio; lowestAt = "$skin dark=$dark" }
            // For the record: the dark blue before R7 (#1E88E5) on the same real bubbles.
            val before = contrastRatio(0xFF1E88E5L, bubble)
            if (dark && before < lowestOldDark) { lowestOldDark = before; lowestOldDarkAt = skin }
        }
        println("R7 blue ticks, 21 skins x light/dark, real bubble colour: lowest ratio now %.2f (%s); old dark blue #1E88E5 lowest on dark bubbles %.2f (%s)".format(lowest, lowestAt, lowestOldDark, lowestOldDarkAt))
        assertTrue("these skins keep the blue ticks under 3:1: $worst", worst.isEmpty())
    }

    @Test fun theEmptyComposerNamesWhatSendWillDoWhileAReplyRuns() {
        assertEquals("Change the reply…", busyComposerPlaceholder(hasFiles = false, busyAction = "steer"))
        assertEquals("Queue a message…", busyComposerPlaceholder(hasFiles = false, busyAction = "queue"))
        // Files cannot be steered, so they are queued whatever the setting says.
        assertEquals("Queue a message…", busyComposerPlaceholder(hasFiles = true, busyAction = "steer"))
        assertTrue("Change the reply…" in ChatStrings && "Queue a message…" in ChatStrings)
        assertFalse("Correct the response..." in ChatStrings)
    }

    @Test fun queueAndSteerAreTheOnlyWordsForTheBusyChoice() {
        assertTrue("Send now" in ChatStrings && "Queue" in ChatStrings && "Run command" in ChatStrings)
        assertFalse("Will send after this reply" in ChatStrings || "Change the reply" in ChatStrings || "Send after" in ChatStrings)
        assertEquals("Send now", busyComposerCaption(slash = false, hasFiles = false, busyAction = "steer", explicitQueue = false))
        assertEquals("Queue", busyComposerCaption(slash = false, hasFiles = false, busyAction = "queue", explicitQueue = false))
        assertEquals("Queue", busyComposerCaption(slash = false, hasFiles = true, busyAction = "steer", explicitQueue = false))
        assertEquals("Queue", busyComposerCaption(slash = false, hasFiles = false, busyAction = "steer", explicitQueue = true))
        assertEquals("Run command", busyComposerCaption(slash = true, hasFiles = false, busyAction = "queue", explicitQueue = false))
    }
}
