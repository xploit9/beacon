package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test

class BotChatNameTest {
    private val profiles = listOf(json("name" to "beacon-qa", "display_name" to "QA Bot"), json("name" to "plain"))

    @Test fun aBotChatSpeaksAsItsBot() {
        assertEquals("QA Bot", botChatName("beacon-qa", "bot-chat", "bot-chat", profiles))
        assertEquals("plain", botChatName("plain", "bot-chat", "bot-chat", profiles))
    }

    @Test fun everyOtherChatKeepsTheAssistantsName() {
        assertNull(botChatName(null, "bot-chat", "bot-chat", profiles))
        assertNull(botChatName("beacon-qa", "bot-chat", "other-chat", profiles))
        assertNull(botChatName("beacon-qa", "", "", profiles))
    }
}
