package dev.hermes.android.uiux.hub

import dev.hermes.android.ui.ConnectionVerdict
import dev.hermes.android.ui.SettingsRowSummary
import dev.hermes.android.ui.SettingsSnapshot
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStrings
import dev.hermes.android.ui.connectionVerdict
import dev.hermes.android.ui.pluginActionWord
import dev.hermes.android.ui.settingsDescriptions
import dev.hermes.android.ui.settingsFilled
import dev.hermes.android.ui.settingsHost
import dev.hermes.android.ui.settingsLabel
import dev.hermes.android.ui.settingsPillWords
import dev.hermes.android.ui.settingsRowSummary
import dev.hermes.android.ui.settingsSearchMatches
import dev.hermes.android.ui.settingsSearchRoutes
import dev.hermes.android.ui.settingsSummary
import dev.hermes.android.updates.PluginRow
import dev.hermes.android.updates.PluginStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Settings hub rows: what each shows as its value, which state pill it earns, and that the short text loses no search match. */
class SettingsHubTest {
    private fun row(route: String, snapshot: SettingsSnapshot) = settingsRowSummary(settingsSummary(route, snapshot)) { it }

    @Test fun connectionRowPairsAccountAndProfileWithItsStatePill() {
        val signedIn = SettingsSnapshot(connected = true, account = "maya", profile = "Personal")
        assertEquals(SettingsRowSummary("maya · Personal", SettingsStatus.Ok, "Connected"), row("Connection", signedIn))
        assertEquals(SettingsRowSummary("maya · Personal", SettingsStatus.Error, "Offline"), row("Connection", signedIn.copy(connected = false)))
        assertEquals(SettingsRowSummary("maya · Personal", SettingsStatus.Attention, "Sign in"), row("Connection", signedIn.copy(connected = false, signedOut = true)))
        // A connection that is up wins over a stale signed-out flag; the profile alone is still a value.
        assertEquals(SettingsRowSummary("work", SettingsStatus.Ok, "Connected"), row("Connection", SettingsSnapshot(connected = true, profile = "work", signedOut = true)))
    }

    @Test fun anUnknownRowShowsNeitherValueNorPill() {
        for (route in settingsSearchRoutes - "Connection") assertEquals("$route must not invent a value or state", SettingsRowSummary(), row(route, SettingsSnapshot()))
    }

    @Test fun voiceKeepConnectedAndHealthPillsFollowOnlyKnownState() {
        val voice = SettingsSnapshot(voiceProviderId = "gemini", voiceKeySaved = true)
        assertEquals(SettingsRowSummary("Gemini Live", SettingsStatus.Ok, "Key saved"), row("Voice & calls", voice))
        assertEquals(SettingsRowSummary("Grok", SettingsStatus.Attention, "Add key"), row("Voice & calls", SettingsSnapshot(voiceProviderId = "grok", voiceKeySaved = false)))
        assertEquals(SettingsRowSummary(), row("Voice & calls", SettingsSnapshot(voiceProviderId = "gemini")))
        assertEquals(SettingsRowSummary("", SettingsStatus.Ok, "Keep connected on"), row("Notifications & Keep connected", SettingsSnapshot(keepConnectedEnabled = true)))
        assertEquals(SettingsRowSummary("", SettingsStatus.Off, "Keep connected off"), row("Notifications & Keep connected", SettingsSnapshot(keepConnectedEnabled = false)))
        assertEquals(SettingsRowSummary("", SettingsStatus.Ok, "Healthy"), row("Health", SettingsSnapshot(serverHealthy = true)))
        assertEquals(SettingsRowSummary("", SettingsStatus.Attention, "Needs attention"), row("Health", SettingsSnapshot(serverHealthy = false)))
        assertEquals(SettingsRowSummary("", SettingsStatus.Attention, "Update available"), row("Server updates", SettingsSnapshot(serverUpdateAvailable = true)))
        assertEquals("An up-to-date server earns no pill here", SettingsRowSummary(), row("Server updates", SettingsSnapshot(serverUpdateAvailable = false)))
    }

    @Test fun appUpdatesRowSurfacesTheAppUpdateBeforeAPendingPluginStep() {
        val idle = SettingsSnapshot(installedVersion = "1.1.22")
        assertEquals(SettingsRowSummary("1.1.22"), row("App updates", idle))
        assertEquals(SettingsRowSummary("1.1.23", SettingsStatus.Attention, "Update available"), row("App updates", idle.copy(updateName = "1.1.23")))
        assertEquals(SettingsRowSummary("1.1.23", SettingsStatus.Attention, "Update ready"), row("App updates", idle.copy(updateName = "1.1.23", updateReady = true)))
        // Finding 13: a pending plugin step is visible without opening the update screen.
        assertEquals(SettingsRowSummary("1.1.22", SettingsStatus.Attention, "Update the Beacon plugin"), row("App updates", idle.copy(pluginAction = "Update the Beacon plugin")))
        assertEquals(SettingsRowSummary("1.1.22", SettingsStatus.Attention, "Restart Hermes"), row("App updates", idle.copy(pluginAction = "Restart Hermes")))
        assertEquals("An app update is the louder fact", "Update available", row("App updates", idle.copy(updateName = "1.1.23", pluginAction = "Restart Hermes")).statusLabel)
    }

    @Test fun onlyAnActionablePluginRowNamesAStep() {
        val actions = PluginStatus.entries.associateWith { pluginActionWord(PluginRow(it)) }
        for (status in listOf(PluginStatus.Restart, PluginStatus.Waiting, PluginStatus.RestartFailed)) assertEquals("Restart Hermes", actions.getValue(status))
        assertEquals("Restart dashboard", actions.getValue(PluginStatus.DashboardFailed))
        for (status in listOf(PluginStatus.Available, PluginStatus.NeedsReview, PluginStatus.Busy, PluginStatus.Failed)) assertEquals("Update the Beacon plugin", actions.getValue(status))
        for (status in listOf(PluginStatus.Hidden, PluginStatus.Checking, PluginStatus.UpToDate, PluginStatus.Updating, PluginStatus.Updated)) assertEquals("", actions.getValue(status))
    }

    @Test fun cachedCountsFillTheirPlaceholdersAfterTranslation() {
        assertEquals("58 on", row("Skills", SettingsSnapshot(skillsOn = 58)).value)
        assertEquals("3 servers", row("MCP servers", SettingsSnapshot(mcpServers = 3)).value)
        assertEquals("Personal · 2 profiles", row("Profiles", SettingsSnapshot(profile = "Personal", profileCount = 2)).value)
        assertEquals("A count with no profile is not shown", SettingsRowSummary(), row("Profiles", SettingsSnapshot(profileCount = 2)))
        val translated = settingsRowSummary(settingsSummary("Skills", SettingsSnapshot(skillsOn = 58))) { if (it == "{0} on") "Attivi: {0}" else it }
        assertEquals("Attivi: 58", translated.value)
        assertEquals("a b", settingsFilled("{1} {0}", listOf("b", "a")))
    }

    @Test fun userDataStaysVerbatimWhileFixedWordsPassThroughTheTranslator() {
        val summary = settingsRowSummary(settingsSummary("Connection", SettingsSnapshot(connected = false, account = "Connected", profile = "Offline"))) { "T:$it" }
        assertEquals("Connected · Offline", summary.value)
        assertEquals("T:Offline", summary.statusLabel)
    }

    @Test fun everyPillIsAShortWord() {
        val everything = SettingsSnapshot(
            connected = false, signedOut = true, account = "maya", profile = "work", installedVersion = "1.1.22", updateName = "1.1.23", updateReady = true,
            pluginAction = "Update the Beacon plugin", voiceProviderId = "gemini", voiceKeySaved = false, keepConnectedEnabled = false,
            serverHealthy = false, serverUpdateAvailable = true,
        )
        for (route in settingsSearchRoutes) {
            val pill = row(route, everything).statusLabel
            assertTrue("$route pill \"$pill\" is too long", pill.length <= 22)
        }
        assertTrue("Every fixed pill word is checked for translation", settingsPillWords.all { it in SettingsStrings })
    }

    @Test fun descriptionsAreShortCapitalizedAndAliasesShareTheirRowsWording() {
        for ((route, text) in settingsDescriptions) {
            assertTrue("$route description \"$text\" is too long", text.length <= 32)
            assertTrue("$route description starts in lowercase", text.first().isUpperCase())
        }
        // One wording per page: the entries that differ only by route name (Settings/Chat, Beacon/Voice & calls) must read the same.
        for ((text, routes) in settingsDescriptions.entries.groupBy({ it.value }, { it.key })) {
            assertEquals("\"$text\" describes different pages: $routes", 1, routes.map(::settingsLabel).distinct().size)
        }
        assertEquals(settingsDescriptions.getValue("Chat"), settingsDescriptions.getValue("Settings"))
        assertEquals(settingsDescriptions.getValue("Voice & calls"), settingsDescriptions.getValue("Beacon"))
    }

    @Test fun theOldLongerWordsStillFindTheirRows() {
        fun finds(route: String, query: String) = settingsSearchMatches(settingsLabel(route), settingsDescriptions.getValue(route), query)
        assertTrue(finds("Notifications & Keep connected", "delivery"))
        assertTrue(finds("Connection", "signed-in account"))
        assertTrue(finds("Connection", "server"))
        assertTrue(finds("Devices", "manage linked phones"))
        assertTrue(finds("Voice & calls", "voice provider"))
        assertTrue(finds("Appearance", "color style"))
        assertTrue(finds("Memory", "what hermes knows"))
        assertTrue(finds("App updates", "install the latest"))
        assertTrue(finds("Profiles", "profile defaults"))
        assertTrue(!finds("Appearance", "camera"))
    }

    @Test fun connectionVerdictNamesWhyThePhoneIsNotConnected() {
        assertEquals(ConnectionVerdict.Connected, connectionVerdict(connected = true, working = true, authenticated = false, hasAddress = true))
        assertEquals(ConnectionVerdict.Checking, connectionVerdict(connected = false, working = true, authenticated = false, hasAddress = true))
        assertEquals(ConnectionVerdict.SignedOut, connectionVerdict(connected = false, working = false, authenticated = false, hasAddress = true))
        assertEquals(ConnectionVerdict.Offline, connectionVerdict(connected = false, working = false, authenticated = true, hasAddress = true))
        assertEquals("No address saved is not a lost sign-in", ConnectionVerdict.Offline, connectionVerdict(connected = false, working = false, authenticated = false, hasAddress = false))
    }

    @Test fun theServerAddressReadsAsAHostWithoutSchemeOrPath() {
        assertEquals("hermes.example.com", settingsHost("https://hermes.example.com"))
        assertEquals("hermes.example.com", settingsHost(" https://hermes.example.com/ "))
        assertEquals("192.0.2.7:9119", settingsHost("http://192.0.2.7:9119/dashboard"))
        assertEquals("hermes.example.com", settingsHost("hermes.example.com/"))
        assertEquals("not a url", settingsHost("not a url"))
        assertEquals("", settingsHost(""))
    }

    // Gradle runs unit tests from the app module; the other roots cover the Android project and the repository.
    private val catalog by lazy {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        JSONObject(File(app, "src/main/assets/native-locales.json").readText())
    }
    private fun key(text: String) = text.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim()

    @Test fun everyHubStringReadsInAllSixteenLanguages() {
        val added = listOf(
            "Check your network", "Couldn't load chat settings", "Display", "Follow new replies", "Signed out", "Can't reach Hermes", "Offline", "Update ready",
            "Update the Beacon plugin", "Healthy", "{0} on", "{0} servers", "{0} profiles", "Server, account, phones", "Linked phones", "Voice and provider",
            "Alerts and calls", "Theme and colors", "Notes Hermes keeps", "Latest Beacon", "Defaults and behavior",
        )
        val languages = catalog.keys().asSequence().toList()
        assertEquals(16, languages.size)
        val missing = added.flatMap { text -> languages.filter { catalog.getJSONObject(it).optString(key(text)).isBlank() }.map { "$it: $text" } }
        assertTrue("Missing hub translations:\n" + missing.joinToString("\n"), missing.isEmpty())
        // The pill words and descriptions the hub shows through variables need every language except English, which reads the literal.
        val shown = (settingsPillWords + settingsDescriptions.values).distinct()
        val absent = shown.flatMap { text -> languages.filter { it != "en" && catalog.getJSONObject(it).optString(key(text)).isBlank() }.map { "$it: $text" } }
        assertTrue("Missing hub translations:\n" + absent.joinToString("\n"), absent.isEmpty())
    }
}
