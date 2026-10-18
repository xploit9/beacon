package dev.hermes.android.ui

import dev.hermes.android.ChatState
import dev.hermes.android.core.Scope
import dev.hermes.android.sessionCanStillBeUnpersisted
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CypherF1RegressionTest {
    private val owner = Scope("https://hermes.example", "owner", "default")

    @Test fun composerAcceptsEditsOnlyAcrossTheInitialSessionCreation() {
        val created = owner.session("created")
        assertTrue(acceptsInitialConversationEdit(null, created, owner, newChatOpening = true, sameClient = true, callActive = false))
        assertTrue(acceptsInitialConversationEdit(owner, created, owner, newChatOpening = true, sameClient = true, callActive = false))
        assertFalse(acceptsInitialConversationEdit(owner.session("old"), created, owner, newChatOpening = true, sameClient = true, callActive = false))
        assertFalse(acceptsInitialConversationEdit(null, owner.session("other-profile"), owner.copy(profile = "work"), newChatOpening = true, sameClient = true, callActive = false))
        assertFalse(acceptsInitialConversationEdit(null, created, owner, newChatOpening = false, sameClient = true, callActive = false))
        assertFalse(acceptsInitialConversationEdit(null, created, owner, newChatOpening = true, sameClient = true, callActive = true))
    }

    @Test fun missingClientCreatedSessionIsPendingOnlyBeforeItsFirstTurn() {
        assertTrue(sessionCanStillBeUnpersisted(true, ChatState()))
        assertFalse(sessionCanStillBeUnpersisted(false, ChatState()))
        assertFalse(sessionCanStillBeUnpersisted(true, ChatState(raw = JSONObject().put("message_count", 1))))
        assertFalse(sessionCanStillBeUnpersisted(true, ChatState(streamId = "running")))
    }

    @Test fun connectInputDiagnosticsNeverPrintTheSessionToken() {
        val token = "private-loopback-session-token"
        assertFalse(CypherConnectInput("https://hermes.example", token).toString().contains(token))
    }
}
