package dev.hermes.android.agentphone

import dev.hermes.android.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Assistant › {name}'s phone: where the row sits, what it says, how search finds it (§8 "Intro card", §9.2). */
class AgentPhoneSettingsRowTest {
    private fun row(snapshot: SettingsSnapshot) = settingsRowSummary(settingsSummary(AGENT_PHONE_ROUTE, snapshot)) { it }

    @Test fun theRowSitsRightAfterPhoneAccessInTheAssistantSection() {
        val assistant = settingsHubSections.getValue("Assistant")
        assertEquals(assistant.indexOf("Phone access") + 1, assistant.indexOf(AGENT_PHONE_ROUTE))
        assertEquals(AGENT_PHONE_ROUTE, settingsRoute(AGENT_PHONE_ROUTE))
        assertEquals("", settingsParent(AGENT_PHONE_ROUTE))
    }

    @Test fun theLabelCarriesTheAssistantsNameAndTheRouteNever() {
        assertEquals("{name}'s phone", settingsLabel(AGENT_PHONE_ROUTE))
        assertFalse(AGENT_PHONE_ROUTE.contains("{name}"))
        assertEquals("Maya's phone", settingsLabel(AGENT_PHONE_ROUTE).replace("{name}", "Maya"))
    }

    @Test fun theRowNeedsThePlugin() {
        assertTrue(settingsNeedsBeaconPlugin(AGENT_PHONE_ROUTE, "absent"))
        assertFalse(settingsNeedsBeaconPlugin(AGENT_PHONE_ROUTE, "available"))
    }

    @Test fun thePillFollowsTheCachedStatusAndClaimsNothingUnread() {
        assertEquals(SettingsRowSummary(), row(SettingsSnapshot()))
        assertEquals(SettingsRowSummary("", SettingsStatus.Ok, "On"), row(SettingsSnapshot(agentPhone = "on")))
        assertEquals(SettingsRowSummary("", SettingsStatus.Off, "Off"), row(SettingsSnapshot(agentPhone = "off")))
        assertEquals(SettingsRowSummary("", SettingsStatus.Working, "Starting…"), row(SettingsSnapshot(agentPhone = "starting")))
        assertEquals(SettingsRowSummary("Not set up"), row(SettingsSnapshot(agentPhone = "setup")))
        assertTrue(listOf("On", "Starting…", "Not set up", "Off").all { it in settingsPillWords })
    }

    @Test fun searchFindsItByTheNameShownAndByItsWords() {
        val shown = settingsLabel(AGENT_PHONE_ROUTE).replace("{name}", "Maya")
        val description = settingsDescriptions.getValue(AGENT_PHONE_ROUTE)
        assertTrue(settingsRowMatches(AGENT_PHONE_ROUTE, shown, description, settingsKeywordsOf(AGENT_PHONE_ROUTE), "maya phone"))
        assertTrue(settingsRowMatches(AGENT_PHONE_ROUTE, shown, description, settingsKeywordsOf(AGENT_PHONE_ROUTE), "payments"))
        assertTrue(settingsRowMatches(AGENT_PHONE_ROUTE, shown, description, settingsKeywordsOf(AGENT_PHONE_ROUTE), "Chrome"))
        assertFalse(settingsRowMatches(AGENT_PHONE_ROUTE, shown, description, settingsKeywordsOf(AGENT_PHONE_ROUTE), "steer"))
    }
}
