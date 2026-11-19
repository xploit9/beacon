package dev.hermes.android.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Owner, 4 Nov and 17 Nov: a reply notice from the Beacon chat showed the chat's auto title ("hi") as if it were a separate chat; it carries the
 * assistant's name instead, whether or not Hermes had been told which chat is the Beacon chat, and after Hermes moved the chat to a new session.
 */
class ReplyNotificationTitleTest {
    @Test fun theBeaconChatsRepliesCarryTheAssistantsName() {
        assertEquals("Maya", replyNotificationTitle(JSONObject().put("assistant_chat", true), "hi", "Maya"))
    }

    @Test fun everyOtherChatKeepsItsOwnTitle() {
        assertEquals("Weekend in Lisbon", replyNotificationTitle(JSONObject().put("assistant_chat", false), "Weekend in Lisbon", "Maya"))
        // An older plugin says nothing about the chat, and a name that is not known yet never blanks the title.
        assertEquals("Weekend in Lisbon", replyNotificationTitle(JSONObject(), "Weekend in Lisbon", "Maya"))
        assertEquals("hi", replyNotificationTitle(JSONObject().put("assistant_chat", true), "hi", ""))
    }

    @Test fun theBeaconChatIsFoundByTheChatThisPhoneKeepsWhenHermesSaysNothing() {
        // Hermes had not been told yet, or its record went stale: the notice still names the chat this phone keeps as the Beacon chat.
        assertEquals("Maya", replyNotificationTitle(JSONObject().put("session_id", "beacon-chat"), "hi", "Maya", "beacon-chat"))
        assertEquals("Maya", replyNotificationTitle(JSONObject().put("assistant_chat", false).put("session_id", "beacon-chat"), "hi", "Maya", "beacon-chat"))
        // Hermes names it by its own word after the chat moved to a new session id.
        assertEquals("Maya", replyNotificationTitle(JSONObject().put("assistant_chat", true).put("session_id", "beacon-chat-2"), "hi", "Maya", "beacon-chat"))
    }

    @Test fun anotherChatNeverTakesTheAssistantsName() {
        assertEquals("Weekend in Lisbon", replyNotificationTitle(JSONObject().put("session_id", "chat-a"), "Weekend in Lisbon", "Maya", "beacon-chat"))
        // No chat kept yet, or an empty id: nothing matches an empty id.
        assertEquals("hi", replyNotificationTitle(JSONObject().put("session_id", "chat-a"), "hi", "Maya", null))
        assertEquals("hi", replyNotificationTitle(JSONObject().put("session_id", ""), "hi", "Maya", ""))
        assertEquals("hi", replyNotificationTitle(JSONObject(), "hi", "Maya", ""))
        // The assistant's name is not known yet: the chat keeps its title.
        assertEquals("hi", replyNotificationTitle(JSONObject().put("session_id", "beacon-chat"), "hi", "", "beacon-chat"))
    }

    // Each notice kind that reaches the title rule, from the Beacon chat and from another chat (a completion, a task result posted into the chat,
    // the reply after a callback is a completion too). An email alert and a failure of the Beacon chat read as the assistant, as they did.
    @Test fun everyKindFromTheBeaconChatIsNamedTheSameAndOtherChatsKeepTheirTitle() {
        for (kind in listOf("completion", "task_result", "failure", "approval", "clarify")) {
            val own = JSONObject().put("kind", kind).put("session_id", "beacon-chat")
            val other = JSONObject().put("kind", kind).put("session_id", "chat-a")
            assertEquals(kind, "Maya", replyNotificationTitle(own, "hi", "Maya", "beacon-chat"))
            assertEquals(kind, "Weekend plan", replyNotificationTitle(other, "Weekend plan", "Maya", "beacon-chat"))
        }
        val emailAlert = JSONObject().put("kind", "task_result").put("email_alert", true).put("session_id", "beacon-chat")
        assertEquals("Maya", replyNotificationTitle(emailAlert, "Important from Gmail", "Maya", "beacon-chat"))
        // A test notice names no chat.
        assertEquals("Beacon · Notification test", replyNotificationTitle(JSONObject().put("kind", "test").put("session_id", ""), "Beacon · Notification test", "Maya", "beacon-chat"))
    }
}
