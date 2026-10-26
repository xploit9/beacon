package dev.hermes.android.ui

import dev.hermes.android.AppState
import dev.hermes.android.Message
import dev.hermes.android.core.Draft
import dev.hermes.android.core.json
import dev.hermes.android.reconnectOnNetworkReturn
import dev.hermes.android.sendArrived
import dev.hermes.android.sentCopies
import org.junit.Assert.*
import org.junit.Test

class UnconfirmedSendTest {
    private fun history(vararg rows: Pair<String, String>) = rows.mapIndexed { i, (role, text) -> Message("m$i", role, text) }

    @Test fun historyHoldingOneMoreCopyProvesTheSendArrived() {
        val before = sentCopies(history("user" to "hello", "assistant" to "hi"), "hello")
        val after = history("user" to "hello", "assistant" to "hi", "user" to "hello ", "assistant" to "again")
        assertEquals(1, before)
        assertTrue(sendArrived(before, after, Draft("hello")))
    }

    @Test fun anEarlierIdenticalMessageIsNotTheSend() {
        val same = history("user" to "continue", "assistant" to "ok")
        assertFalse(sendArrived(sentCopies(same, "continue"), same, Draft("continue")))
    }

    @Test fun nothingIsProvedWithoutAStartingCountOrForAttachmentsAndBlankDrafts() {
        val after = history("user" to "hello")
        assertFalse(sendArrived(null, after, Draft("hello")))
        assertFalse(sendArrived(0, after, Draft("hello", attachments = listOf(json("name" to "a.png")))))
        assertFalse(sendArrived(0, history("user" to ""), Draft("  ")))
        assertFalse(sendArrived(0, history("assistant" to "hello"), Draft("hello")))
    }

    @Test fun savedHistoryOfASignedInAccountReconnectsWhenTheNetworkReturns() {
        val offline = AppState(origin = "https://hermes.example", authenticated = true, connected = false, loading = false, setupWizard = false)
        assertTrue(reconnectOnNetworkReturn(offline))
        assertFalse(reconnectOnNetworkReturn(offline.copy(connected = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(loading = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(setupWizard = true)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(authenticated = false)))
        assertFalse(reconnectOnNetworkReturn(offline.copy(origin = "")))
    }
}
