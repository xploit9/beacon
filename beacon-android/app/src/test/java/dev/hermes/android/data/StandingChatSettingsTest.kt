package dev.hermes.android.data

import dev.hermes.android.core.json
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R9 Should 1: Hermes keeps "Never ask" and Fast mode per chat, in the chat row's model_config (`yolo_mode`: hermes_state_sessions.set_session_yolo; `service_tier`:
 * tui_gateway/agent_factory._persist_live_session_runtime, written by config.set fast). The empty chat New chat reuses must hold neither.
 */
class StandingChatSettingsTest {
    private fun chat(config: Any?) = json("session_id" to "shell-a", "message_count" to 0).apply { if (config != null) put("model_config", config) }

    @Test fun aChatWithNoSavedSettingsKeepsNothing() {
        assertFalse(chatKeepsApprovalOrSpeed(chat(null)))
        assertFalse(chatKeepsApprovalOrSpeed(chat("")))
        assertFalse(chatKeepsApprovalOrSpeed(chat("{}")))
        assertFalse(chatKeepsApprovalOrSpeed(chat(JSONObject.NULL)))
        assertFalse("model and effort are compared against the profile default elsewhere", chatKeepsApprovalOrSpeed(chat("""{"model":"m","reasoning_config":{"enabled":true,"effort":"high"}}""")))
    }

    @Test fun neverAskIsKeptWhetherHermesSendsTheConfigAsTextOrAnObject() {
        assertTrue(chatKeepsApprovalOrSpeed(chat("""{"yolo_mode":true}""")))
        assertTrue(chatKeepsApprovalOrSpeed(chat(json("yolo_mode" to true))))
        assertFalse(chatKeepsApprovalOrSpeed(chat("""{"yolo_mode":false}""")))
        assertFalse(chatKeepsApprovalOrSpeed(chat(json("yolo_mode" to false))))
    }

    @Test fun anySpeedOtherThanNormalIsKept() {
        for (tier in listOf("priority", "ultrafast", "auto", "cold")) assertTrue(tier, chatKeepsApprovalOrSpeed(chat("""{"service_tier":"$tier"}""")))
        for (tier in listOf("normal", "", " Normal ", "default")) assertFalse(tier, chatKeepsApprovalOrSpeed(chat("""{"service_tier":"$tier"}""")))
    }

    @Test fun aConfigThatCannotBeReadIsNotAFreshChat() {
        assertTrue(chatKeepsApprovalOrSpeed(chat("{not json")))
    }
}
