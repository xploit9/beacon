package dev.hermes.android.ui

import dev.hermes.android.ChatState
import dev.hermes.android.core.Scope
import dev.hermes.android.sessionCanStillBeUnpersisted
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeaconF1RegressionTest {
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
        assertFalse(BeaconConnectInput("https://hermes.example", token).toString().contains(token))
    }

    @Test fun deniedSetupPermissionStaysOnTheCurrentStep() {
        var advanced = 0
        var denied = 0

        handleSetupPermissionResult(false, { advanced++ }, { denied++ })

        assertEquals(0, advanced)
        assertEquals(1, denied)
        handleSetupPermissionResult(true, { advanced++ }, { denied++ })
        assertEquals(1, advanced)
        assertFalse(setupPermissionRequiresSettings(false, false))
        assertFalse(setupPermissionRequiresSettings(true, true))
        assertTrue(setupPermissionRequiresSettings(true, false))
    }
    @Test fun anUnsavedPhoneCreatedChatAnswering404RaisesNoBanner() {
        val empty = dev.hermes.android.ChatState(sessionId = "20261020_191200_ab12cd")
        val missing = dev.hermes.android.data.ApiFailure(404, "Session not found")
        assertTrue(dev.hermes.android.unsavedOpenChatRead(missing, "20261020_191200_ab12cd", true, empty))
        assertFalse(dev.hermes.android.unsavedOpenChatRead(dev.hermes.android.data.ApiFailure(401, "Sign in"), "20261020_191200_ab12cd", true, empty))
        assertFalse(dev.hermes.android.unsavedOpenChatRead(missing, "20261020_000000_other1", true, empty))
        assertFalse(dev.hermes.android.unsavedOpenChatRead(missing, "20261020_191200_ab12cd", false, empty))
        val saved = empty.copy(raw = org.json.JSONObject().put("message_count", 1))
        assertFalse(dev.hermes.android.unsavedOpenChatRead(missing, "20261020_191200_ab12cd", true, saved))
    }
}
