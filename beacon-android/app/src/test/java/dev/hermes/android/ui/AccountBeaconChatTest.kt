package dev.hermes.android.ui

import dev.hermes.android.assistantConversationToAdopt
import dev.hermes.android.beaconChatLookupMissing
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.branchTitle
import dev.hermes.android.core.json
import dev.hermes.android.pushDestination
import dev.hermes.android.registeredBeaconChat
import dev.hermes.android.titleAfterLoad
import org.junit.Assert.*
import org.junit.Test

class AccountBeaconChatTest {
    // A phone with no Beacon chat of its own asks the plugin for the account's chat and adopts it instead of starting another.
    @Test fun aPhoneWithoutABeaconChatAdoptsTheAccountsRegisteredOne() {
        val registered = registeredBeaconChat(json("conversation_id" to "20261025_215123_6fa89f"))
        assertEquals("20261025_215123_6fa89f", assistantConversationToAdopt("", registered))
    }

    @Test fun nothingIsAdoptedWhenThePluginAnswersWithoutAUsableChat() {
        assertNull(registeredBeaconChat(null))
        assertNull(registeredBeaconChat(json()))
        assertNull(registeredBeaconChat(json("conversation_id" to "")))
        assertNull(registeredBeaconChat(json("conversation_id" to "../other chat")))
        assertNull(registeredBeaconChat(json("conversation_id" to "x".repeat(257))))
        assertNull(assistantConversationToAdopt("", registeredBeaconChat(json())))
    }

    @Test fun aNewBranchShowsTheTitleHermesGaveIt() {
        assertEquals("Lighthouse story #2", branchTitle(json("session_id" to "branch-1", "title" to "Lighthouse story #2")))
        assertEquals("Lighthouse story #2", branchTitle(json("session" to json("title" to "Lighthouse story #2"))))
        assertEquals("", branchTitle(json("session_id" to "branch-1")))
    }

    // A load that still carries the parent's title must not take the open branch's top bar back from it.
    @Test fun aLoadEchoingTheParentsTitleKeepsTheBranchTitle() {
        assertEquals("Lighthouse story #2", titleAfterLoad("Lighthouse story #2", "Lighthouse story", "Lighthouse story"))
        assertEquals("Lighthouse story #2", titleAfterLoad("Lighthouse story #2", "Lighthouse story", "Lighthouse story #2"))
        assertEquals("Renamed", titleAfterLoad("Lighthouse story #2", "Lighthouse story", "Renamed"))
        assertEquals("Lighthouse story", titleAfterLoad("", "Lighthouse story", "Lighthouse story"))
    }

    @Test fun aCallOrResultFromAnotherChatOpensThatChat() {
        assertEquals("Chat", pushDestination("incoming_call", "qa-chat", "beacon-chat"))
        assertEquals("Chat", pushDestination("task_result", "qa-chat", "beacon-chat"))
        assertEquals("Chat", pushDestination("incoming_call", "qa-chat", null))
        assertEquals("Beacon", pushDestination("incoming_call", "beacon-chat", "beacon-chat"))
        assertEquals("Beacon", pushDestination("incoming_call", "", "beacon-chat"))
        assertEquals("Beacon", pushDestination("task_result", "beacon-chat", "beacon-chat"))
        assertEquals("Tasks", pushDestination("task_result", "", "beacon-chat"))
        assertEquals("Chat", pushDestination("message", "qa-chat", "qa-chat"))
    }

    @Test fun onlyAPluginWithoutABeaconChatLetsThePhoneStartOne() {
        for (status in listOf(401, 403, 404, 405)) assertTrue(status.toString(), beaconChatLookupMissing(ApiFailure(status, "none")))
        for (status in listOf(408, 429, 500, 502, 503, 504)) assertFalse(status.toString(), beaconChatLookupMissing(ApiFailure(status, "failed")))
    }
}
