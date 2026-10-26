package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test

class CallPresentationTest {
    private val owner=Scope("https://demo.invalid","synthetic-owner","default")
    private val call=CallState(active=true,state="listening",callId="call",conversationScope=owner.session("chat"))
    @Test fun overlayRequiresCurrentCallOwnerProfileAuthenticationAndUnlockedScreen() {
        assertTrue(overlayOwnsCall(call,"call",owner,true,true))
        assertFalse(overlayOwnsCall(call,"other",owner,true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(origin="https://other.invalid"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(owner="another"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(profile="work"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner,false,true))
        assertFalse(overlayOwnsCall(call,"call",owner,true,false))
        assertFalse(overlayOwnsCall(call.copy(active=false),"call",owner,true,true))
    }
    @Test fun levelsAndActivityNeverInventOutputForInactiveHeldOrConnectingCall() {
        assertNull(normalizedVoiceLevel(Float.NaN)); assertNull(normalizedVoiceLevel(-1f))
        assertEquals(1f,normalizedVoiceLevel(2f)!!,0f)
        assertEquals(0f,call.copy(active=false,remoteSpeaking=true).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(held=true,remoteSpeaking=true).voiceAmplitude(),0f)
        assertTrue(call.copy(userSpeaking=true,localAudioLevel=.7f).voiceAmplitude() > 0f)
        assertTrue(call.copy(state="speaking",remoteAudioLevel=.7f).voiceAmplitude() >= .7f)
    }
    @Test fun captionProjectionPreservesCanonicalTextAndHidesAtEnd() {
        val values=listOf(CallTranscript("user","user","Current caller text",false),CallTranscript("bot","assistant","Current assistant text",false))
        assertEquals("Current caller text",call.copy(transcripts=values).latestCaption("user"))
        assertEquals("",call.copy(active=false,transcripts=values).latestCaption("user"))
        assertEquals(values,call.copy(transcripts=values).transcripts)
        assertEquals("",call.copy(transcripts=values+CallTranscript("cancelled","assistant","",true,interrupted=true)).latestCaption("assistant"))
    }
    @Test fun failedCallRetainsItsOriginalConversationForRetryAndClose() {
        val target=owner.session("source-chat")
        val failed=call.copy(active=false,state="idle",callId="failed-call",conversationScope=target,failureReason="Voice did not connect.")
        assertEquals(target,failed.failedCallConversation("failed-call"))
        assertNull(failed.failedCallConversation("other-call"))
        assertNull(failed.copy(failureReason="").failedCallConversation("failed-call"))
    }
    @Test fun navigationUsesAvailableDpAndLargeTextFallback() {
        assertFalse(persistentCallNavigation(390f,1f)); assertTrue(persistentCallNavigation(864f,1f))
        assertFalse(persistentCallNavigation(720f,1.6f)); assertTrue(persistentCallNavigation(1200f,1.6f))
    }
    @Test fun draggingClampsBothEdgesAndSafeSystemInsets() {
        assertEquals(0 to 24,clampBubblePosition(-100,-100,64,64,400,800,24,32))
        assertEquals(336 to 704,clampBubblePosition(2000,2000,64,64,400,800,24,32))
    }
    @Test fun waveGeometryIsFiniteBoundedAndUsesActivityInsteadOfUnboundedFrameWork() {
        for(phase in listOf(0f,1f,4f)) for(i in 0..56) for(layer in 0..2) {
            val y=voiceWaveY(i/56f,phase,1f,layer); assertTrue(y.isFinite()&&y in .1f..0.95f)
        }
        assertNotEquals(voiceWaveY(.4f,0f,.1f,0),voiceWaveY(.4f,1f,.8f,0))
    }

    @Test fun automaticApprovalReceiptKeepsItsOutcomeAndShowsTheQuietPolicyCaption() {
        val receipt = requireNotNull(CallToolReceipt.from(json(
            "request_id" to "request-1", "tool_name" to "mcp__gmail__search_messages", "status" to "running", "approved_by" to "chat_yolo",
        )))

        assertEquals("Checking Gmail…", receipt.activityLabel())
        assertEquals("Allowed by chat YOLO", receipt.approvalCaption())
        assertEquals("Using Gmail…", callStatusLabel(call.copy(toolReceipts = listOf(receipt))))
        assertEquals("Muted", callStatusLabel(call.copy(muted = true)))
    }

    @Test fun fileToolReceiptHasAReadableActivityLabelAndOwnerApprovalHasNoAutomaticCaption() {
        val receipt = requireNotNull(CallToolReceipt.from(json(
            "request_id" to "request-2", "tool_name" to "write_file", "status" to "ready", "approved_by" to "owner",
        )))

        assertEquals("Writing a file…", receipt.activityLabel())
        assertNull(receipt.approvalCaption())
        assertNull(CallToolReceipt.from(json("request_id" to "request-3", "tool_name" to "send_email", "status" to "needs_approval"))?.activityLabel())
    }

    @Test fun toolChipRetainsFailureAndUncertaintyBesideAuthorization() {
        val failed = requireNotNull(CallToolReceipt.from(json(
            "request_id" to "request-4", "tool_name" to "mcp__gmail__search_messages", "status" to "failed", "approved_by" to "smart",
        )))
        val uncertain = requireNotNull(CallToolReceipt.from(json(
            "request_id" to "request-5", "tool_name" to "write_file", "status" to "uncertain", "approved_by" to "call_allowance",
        )))

        assertEquals("Gmail action failed", failed.chipLabel())
        assertEquals("Allowed automatically", failed.approvalCaption())
        assertEquals("File write outcome unclear", uncertain.chipLabel())
        assertEquals("Allowed for this call", uncertain.approvalCaption())
    }

    @Test fun approvedReceiptsNeverProduceAnOwnerApprovalPrompt() {
        val proposal = json("id" to "p".repeat(32), "arguments" to json("to" to "owner@example.invalid"))
        for (approvedBy in listOf("chat_yolo", "profile_off", "smart", "memory", "call_allowance", "owner")) {
            val receipt = json("status" to "needs_approval", "approved_by" to approvedBy, "proposal" to proposal)
            assertNull(callProposalNeedingOwnerApproval(receipt))
        }
        assertNotNull(callProposalNeedingOwnerApproval(json("status" to "needs_approval", "proposal" to proposal)))
        assertNull(callProposalNeedingOwnerApproval(json("status" to "running", "proposal" to proposal)))
    }

    @Test fun eachAutomaticApprovalSourceGetsItsShortCallCaption() {
        val captions = mapOf(
            "chat_yolo" to "Allowed by chat YOLO",
            "profile_off" to "Approvals off",
            "smart" to "Allowed automatically",
            "memory" to "Allowed automatically",
            "call_allowance" to "Allowed for this call",
        )
        captions.forEach { (approvedBy, caption) ->
            val receipt = requireNotNull(CallToolReceipt.from(json(
                "request_id" to approvedBy, "tool_name" to "read_file", "status" to "completed", "approved_by" to approvedBy,
            )))
            assertEquals(caption, receipt.approvalCaption())
        }
    }

    @Test fun callTimerUsesElapsedTimeWithoutNegativeOrMissingDurations() {
        assertEquals("01:05", callTimerLabel(1_000, 66_000))
        assertEquals("00:00", callTimerLabel(10_000, 5_000))
        assertEquals("00:00", callTimerLabel(0, 5_000))
    }
}
