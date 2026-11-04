package dev.hermes.android.push

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Owner, 4 Nov: a reply notice from the Beacon chat showed the chat's auto title ("hi"); it carries the assistant's name instead. */
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
}
