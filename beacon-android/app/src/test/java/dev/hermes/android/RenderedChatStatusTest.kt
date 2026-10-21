package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Test

class RenderedChatStatusTest {
    @Test fun aHistoryRefreshDuringAnInFlightSendKeepsItInFlight() {
        assertEquals("Sending", renderedChatStatus(ChatState(uncertain = "send-1", status = "Sending"), active = null, streamEnded = false))
        assertEquals("Sending", renderedChatStatus(ChatState(uncertain = "send-1", status = "Sending"), active = null, streamEnded = true))
    }
    @Test fun otherRendersKeepTheirRules() {
        assertEquals("An unknown outcome is not an in-flight send", "", renderedChatStatus(ChatState(uncertain = "send-1", status = "Send outcome unknown"), active = null, streamEnded = false))
        assertEquals("Turn status unknown", renderedChatStatus(ChatState(streamId = "runtime-1"), active = null, streamEnded = false))
        assertEquals("", renderedChatStatus(ChatState(streamId = "runtime-1", status = "Working"), active = "runtime-1", streamEnded = true))
    }
}
