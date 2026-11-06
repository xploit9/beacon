package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AdaptiveVoiceTest {
    @Test fun elapsedProgressIsOneCueAndNeverRedispatchesWork() {
        val progress=VoiceWorkProgress()
        assertFalse(progress.overrun("exact-receipt","running",100))
        assertFalse(progress.overrun("exact-receipt","running",20099))
        assertTrue(progress.overrun("exact-receipt","running",20100))
        assertFalse(progress.overrun("exact-receipt","running",50000))
        assertFalse(progress.overrun("unknown-receipt","uncertain",50000))
        assertFalse(progress.overrun("finished","completed",50000))
    }

    @Test fun callbackConsentSeparatesInlineUnsetDeclinedAndAlreadyRequested() {
        fun receipt(mode:String,consent:String)=JSONObject().put("status","queued").put("work_mode",mode).put("callback_consent",consent)
        assertTrue(callToolHint("hermes_work",receipt("inline","unset")).contains("finish the quick work in this call"))
        assertFalse(callToolHint("hermes_work",receipt("inline","unset")).contains("offer"))
        assertTrue(callToolHint("hermes_work",receipt("background","unset")).contains("offer"))
        assertTrue(callToolHint("hermes_work",receipt("background","declined")).contains("decline"))
        assertTrue(callToolHint("hermes_follow",receipt("background","requested")).contains("do not ask again"))
        assertTrue(callToolHint("hermes_callback",JSONObject().put("subscribed",false)).contains("withdrawn"))
        assertFalse(callToolHint("hermes_follow",receipt("background","unset").put("status","failed")).contains("naturally offer"))
    }
    @Test fun modelContractDistinguishesJudgmentFromReceiptAuthority() {
        val setup=GeminiWire.setup("synthetic-model","synthetic-voice",null,JSONObject(),emptySet(),"ask").getJSONObject("setup")
        val declarations=setup.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        val tools=(0 until declarations.length()).associate { declarations.getJSONObject(it).getString("name") to declarations.getJSONObject(it) }
        assertTrue("An overrun follows an existing receipt instead of resubmitting the action",tools.containsKey("hermes_follow"))
        val followGuidance=tools.getValue("hermes_follow").getString("description")
        assertTrue(followGuidance.contains("Omit callback when consent is unset"))
        assertFalse(followGuidance.contains("false for absent"))
        assertEquals(setOf("request_id","callback"),tools.getValue("hermes_follow").getJSONObject("parameters").getJSONObject("properties").keys().asSequence().toSet())
        assertTrue(tools.getValue("hermes_work").getJSONObject("parameters").getJSONObject("properties").has("mode"))
        assertTrue(tools.getValue("hermes_callback").getJSONObject("parameters").getJSONObject("properties").has("enabled"))
        val policy=GeminiWire.HERMES_ON_THE_CALL
        for(evidence in listOf("capabilities","expected steps","observed progress","already requested","declined","same conversation")) assertTrue(evidence,policy.contains(evidence))
        assertFalse(policy.contains("after a direct tool fails twice"))
        assertFalse(policy.contains("Work you do with your own tools stops"))
    }
}
