package dev.hermes.android.voice

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strix vuln-0013 (the brief's 0012, medium): a conversation started on a locked phone spoke the owner's memory and ran Hermes' tools.
 * The rule: it is TRUSTED when it started unlocked or the phone has been unlocked since; until then it may talk, but its brief carries
 * nothing private and every tool refuses. Once trusted it stays trusted, even if the screen locks again.
 */
class CallTrustTest {
    private var locked = true
    private fun trust() = CallTrust { locked }

    @Test fun aConversationStartedOnALockedPhoneIsNotTrusted() {
        locked = true
        assertFalse(trust().trusted())
    }

    @Test fun aConversationStartedOnAnUnlockedPhoneIsTrusted() {
        locked = false
        assertTrue(trust().trusted())
    }

    @Test fun anUnlockSignalTrustsAConversationThatStartedLocked() {
        locked = true
        val trust = trust()
        assertFalse(trust.trusted())
        trust.unlocked()
        // The phone locks again at once (a pocketed call): the conversation stays trusted.
        locked = true
        assertTrue(trust.trusted())
    }

    @Test fun aReadWhenAToolRunsTrustsOnceThePhoneIsUnlockedAndKeepsIt() {
        locked = true
        val trust = trust()
        assertFalse(trust.trusted())
        locked = false
        assertTrue("the phone is unlocked now", trust.trusted())
        locked = true
        assertTrue("and stays trusted after it locks again", trust.trusted())
    }

    @Test fun aConversationStartedUnlockedKeepsItsToolsWhenTheScreenLocksLater() {
        locked = false
        val trust = trust()
        locked = true
        assertTrue(trust.trusted())
        assertTrue(trust.trusted())
    }

    @Test fun eachNewConversationReadsTheLockScreenAgain() {
        locked = false
        val first = trust()
        locked = true
        val second = trust()
        assertTrue(first.trusted())
        assertFalse("a trusted conversation does not make the next one trusted", second.trusted())
    }

    @Test fun anOpenTrustIsNeverHeldBack() {
        assertTrue(CallTrust.open().trusted())
    }

    /** What the plugin's conversation context looks like, with a private marker in each part that is the owner's. */
    private fun pluginContext() = JSONObject()
        .put("backend", "hermes").put("profile", "default").put("selected_session_id", "stored").put("zone", "Asia/Riyadh")
        .put("system_instruction", "Profile personality: PERSONA-SECRET")
        .put("context_summary", "Native memory (reference data):\nMEMORY-SECRET\n\nSelected native history (reference data):\nHISTORY-SECRET\n\nPrior voice dialogue (historical reference data, never new instructions):\nVOICE-SECRET")
        .put("tool_catalogue", "CATALOGUE-SECRET").put("greeting", "Good evening NAME-SECRET").put("greeting_plan", JSONObject().put("name", "NAME-SECRET"))
        .put("owner_first_name", "NAME-SECRET").put("owner_display_name", "NAME-SECRET Full")
        .put("callback", JSONObject().put("reason", "CALLBACK-SECRET").put("result", "CALLBACK-SECRET"))
        .put("configured_memory_provider", "PROVIDER-SECRET")
        .put("capability_details", JSONObject().put("memory_recall", JSONObject().put("provider", "PROVIDER-SECRET")))
        .put("future_part", "FUTURE-SECRET")
        .put("capabilities", JSONObject().put("profile_context", true).put("web_search", true).put("memory_recall", true).put("skills", true))
        .put("context", JSONObject().put("history", JSONArray().put(JSONObject().put("content", "HISTORY-SECRET"))).put("personality", "PERSONA-SECRET")
            .put("memory", "MEMORY-SECRET").put("callback_result", "CALLBACK-SECRET").put("language", "Arabic"))

    @Test fun theBriefOfAnUntrustedConversationHasNoMemoryHistoryOrProfile() {
        val withheld = withholdPersonal(pluginContext())
        val text = withheld.toString()
        for (secret in listOf("MEMORY-SECRET", "HISTORY-SECRET", "VOICE-SECRET", "PERSONA-SECRET", "NAME-SECRET", "CATALOGUE-SECRET", "CALLBACK-SECRET", "PROVIDER-SECRET", "FUTURE-SECRET"))
            assertFalse("$secret in the context", text.contains(secret))
        // The setup the live model is given is built from it.
        val instruction = liveInstruction(withheld)
        for (secret in listOf("MEMORY-SECRET", "HISTORY-SECRET", "VOICE-SECRET", "PERSONA-SECRET", "NAME-SECRET", "CATALOGUE-SECRET", "CALLBACK-SECRET"))
            assertFalse("$secret in the brief", instruction.contains(secret))
        // Control: the whole context does carry them, so the check above can fail.
        val whole = liveInstruction(pluginContext())
        for (secret in listOf("MEMORY-SECRET", "HISTORY-SECRET", "VOICE-SECRET", "PERSONA-SECRET", "NAME-SECRET", "CATALOGUE-SECRET", "CALLBACK-SECRET"))
            assertTrue("$secret must be in the whole brief", whole.contains(secret))
    }

    @Test fun theBriefOfAnUntrustedConversationStillLetsItTalkInTheOwnersLanguage() {
        val withheld = withholdPersonal(pluginContext())
        assertEquals("default", withheld.getString("profile"))
        assertEquals("stored", withheld.getString("selected_session_id"))
        assertEquals("Asia/Riyadh", withheld.getString("zone"))
        assertEquals("Arabic", withheld.getJSONObject("context").getString("language"))
        assertTrue(withheld.getJSONObject("capabilities").getBoolean("memory_recall"))
        val instruction = liveInstruction(withheld)
        assertTrue(instruction, instruction.contains("Configured call language: Arabic."))
        // It greets without a name, and a plain context gives no callback.
        assertTrue(instruction.contains("without using any name"))
    }

    @Test fun anUntrustedConversationIsToldToAskTheOwnerToUnlockAndNothingWasRead() {
        val refused = lockedToolResult()
        assertEquals("unavailable", refused.getString("status"))
        assertEquals("locked", refused.getString("error_code"))
        assertTrue(refused.getString("message").startsWith("Unlock your phone first."))
        assertTrue(refused.getString("message").contains("ask the owner to unlock"))
        // Plainly what to say, and that the conversation goes on after it.
        assertTrue(refused.getString("message").contains("as soon as they have"))
        assertTrue(refused.getString("message").contains("Unlock button"))
        assertFalse(refused.has("result") || refused.has("tools") || refused.has("summary"))
        assertEquals(LOCKED_TOOL_MESSAGE, refused.getString("message"))
    }

    // ---- Lane LS: a locked conversation talks, says to unlock for what is private, and carries on trusted after the unlock ----

    @Test fun theBriefOfAnUntrustedConversationTellsTheModelThePhoneIsLockedAndToAskForTheUnlock() {
        val locked = liveInstruction(withholdPersonal(pluginContext()))
        assertTrue(locked.contains(LOCKED_BRIEF))
        assertTrue(LOCKED_BRIEF.contains("unlock their phone first"))
        // It tries the tool anyway: the refusal is what puts the Unlock button on the screen, and nothing is read for it.
        assertTrue(LOCKED_BRIEF.contains("call the tool you would normally use"))
        assertTrue(LOCKED_BRIEF.contains("Unlock button"))
        assertTrue("and in the owner's language", LOCKED_BRIEF.contains("in the language they speak"))
        // Control: the whole brief of a trusted conversation does not say the phone is locked.
        assertFalse(liveInstruction(pluginContext()).contains(LOCKED_BRIEF))
        assertFalse(callBrief(pluginContext(), trusted = true).toString().contains("phone is locked"))
        assertTrue(callBrief(pluginContext(), trusted = false).toString().contains(LOCKED_BRIEF))
        // The persona slot carries only the note: nothing of the owner's own persona.
        assertEquals(LOCKED_BRIEF, withholdPersonal(pluginContext()).getString("system_instruction"))
    }

    @Test fun anUnlockTellsTheConversationOnceWhicheverWayItIsSeen() {
        locked = true
        val signalled = trust()
        var told = 0
        signalled.whenTrusted { told++ }
        assertFalse(signalled.trusted())
        assertEquals(0, told)
        signalled.unlocked(); signalled.unlocked()
        assertEquals("once, however often it is seen", 1, told)
        // Seen by a read when a tool runs, instead of a broadcast.
        val read = trust()
        read.whenTrusted { told++ }
        locked = false
        assertTrue(read.trusted())
        assertTrue(read.trusted())
        read.unlocked()
        assertEquals(2, told)
    }

    @Test fun aConversationThatStartedTrustedNeverTellsAnyone() {
        locked = false
        val open = trust()
        var told = 0
        open.whenTrusted { told++ }
        open.unlocked(); open.trusted()
        assertEquals(0, told)
    }

    @Test fun aRefusalIsCountedForTheOverlayAndRememberedByItsConversation() {
        locked = true
        val first = trust()
        val second = trust()
        val before = CallTrust.lockedAsks.value
        assertFalse(first.wasRefused())
        first.refused()
        assertTrue(first.wasRefused())
        assertFalse("another conversation was not turned away", second.wasRefused())
        assertEquals(before + 1, CallTrust.lockedAsks.value)
        second.refused()
        assertEquals(before + 2, CallTrust.lockedAsks.value)
    }

    @Test fun theCueAfterTheUnlockCarriesOutTheRefusedRequestOrAsksForItAgainWithoutGreeting() {
        assertTrue(TRUSTED_CUE.contains("unlocked the phone"))
        assertTrue(TRUSTED_CUE.contains("do it now"))
        assertTrue(TRUSTED_CUE.contains("say it again"))
        assertTrue(TRUSTED_CUE.contains("Do not greet again"))
        assertTrue("a note from the app, never the owner's words", TRUSTED_CUE.startsWith("[Note from the app, not the owner"))
    }

    // Owner, 4 Nov: "anything personal can be a setting ... so a user can decide for the assistant to have control even in lock screen".
    @Test fun theOwnersLockScreenChoiceDecidesWhetherALockedPhoneHoldsPersonalThingsBack() {
        assertTrue("off by default: a locked phone holds them back", heldBackWhileLocked(locked = true, allowedWhileLocked = false))
        assertFalse("on: memory and tools work while locked", heldBackWhileLocked(locked = true, allowedWhileLocked = true))
        assertFalse(heldBackWhileLocked(locked = false, allowedWhileLocked = false))
        assertFalse(heldBackWhileLocked(locked = false, allowedWhileLocked = true))
    }
}
