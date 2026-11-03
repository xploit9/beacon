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
        assertFalse(refused.has("result") || refused.has("tools") || refused.has("summary"))
    }
}
