package dev.hermes.android.setup

import dev.hermes.android.ui.BEACON_PC_INSTALL_URL
import dev.hermes.android.updates.BEACON_PLUGIN_SOURCE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The README's install journey is the one the app does: install the app, paste the link, and the wizard installs the plugin. */
class ReadmeJourneyTest {
    private val root = requireNotNull(listOf(".", "..", "../..", "../../..").map { File(it) }.firstOrNull { File(it, "README.md").isFile && File(it, "docs").isDirectory && File(it, "beacon-android").isDirectory }) {
        "Run this check from the Beacon repository, the Android project or the app module."
    }
    private fun text(path: String) = File(root, path).readText().replace("\r\n", "\n")
    private val readme = text("README.md")
    private val guide = text("docs/install-with-agent.md")

    /** The one fenced block that follows a "No HTTPS address yet?" heading. */
    private fun tailscalePrompt(document: String): String {
        val heading = document.indexOf("No HTTPS address yet?")
        assertTrue("A 'No HTTPS address yet?' heading", heading >= 0)
        val open = document.indexOf("```text\n", heading)
        assertTrue("A text block after it", open >= 0)
        val close = document.indexOf("\n```", open + 8)
        return document.substring(open + 8, close)
    }

    private fun headingAnchor(heading: String) = heading.lowercase().replace(Regex("[^a-z0-9 -]"), "").trim().replace(' ', '-')
    private fun anchors(document: String) = document.lines().filter { it.startsWith("#") && !it.startsWith("#!") }.map { headingAnchor(it.trimStart('#')) }.toSet() +
        Regex("""<h1[^>]*>([^<]+)</h1>""").findAll(document).map { headingAnchor(it.groupValues[1]) }

    @Test fun theReadmeLeadsWithTheAppIconAtAboutNinetySixPixels() {
        val icon = File(root, "docs/images/beacon-icon.png")
        assertTrue(icon.isFile)
        val bytes = icon.readBytes()
        assertEquals("a PNG", listOf(0x89, 0x50, 0x4E, 0x47), bytes.take(4).map { it.toInt() and 0xff })
        fun int(at: Int) = bytes.slice(at until at + 4).fold(0) { sum, b -> (sum shl 8) or (b.toInt() and 0xff) }
        assertEquals("square", int(16), int(20))
        assertTrue("96 px shown from a sharp 96 to 192 px file", int(16) in 96..192)
        val image = readme.indexOf("""<img src="docs/images/beacon-icon.png" width="96" """)
        assertTrue("The icon is in the README at 96 px", image >= 0)
        assertTrue("and it comes before the title and the install section", image < readme.indexOf("<h1") && readme.indexOf("<h1") < readme.indexOf("## Install"))
    }

    @Test fun theInstallSectionSaysInstallTheAppAndTheWizardDoesTheRest() {
        val install = readme.substringAfter("## Install\n").substringBefore("\n## ")
        assertTrue(install.startsWith("\n**Install the app, paste your Hermes link, sign in. The app installs the Beacon plugin for you.**"))
        for (part in listOf("Releases", "open it on your phone", "allow installs from your browser or Files app", "voice key", "permissions", "Keep connected")) assertTrue(part, part in install)
        // The command-line installer stays, as the alternative.
        assertTrue("### Alternative: set up from the PC" in install)
        assertTrue("beacon_install.py" in install.substringAfter("### Alternative: set up from the PC"))
        assertFalse("The first thing a reader meets is the app, not the PC installer", "beacon_install.py" in install.substringBefore("### Alternative"))
    }

    @Test fun theNoAddressSectionLeadsWithThePrivateHttpsAddressAndOffersTailscaleFree() {
        val install = readme.substringAfter("## Install\n").substringBefore("\n### Alternative")
        assertTrue("### No HTTPS address yet?" in install)
        assertFalse("The old Tailscale-first heading is gone", "No Tailscale yet?" in readme || "No Tailscale yet?" in guide)
        // One sentence says what the address is, in the placeholder format, before the heading that offers to set it up.
        val sentence = install.substringBefore("### No HTTPS address yet?").lines().single { "private HTTPS address" in it }
        assertTrue(sentence, "`https://<your-pc>.<your-tailnet>.ts.net`" in sentence && "the link you paste" in sentence)
        assertTrue("Tailscale is introduced under the heading, as the free way to get the address", "Tailscale (free)" in install.substringAfter("### No HTTPS address yet?"))
    }

    @Test fun theGuideNamesTheWizardPageTheAppShows() {
        // The page title and actions the guide quotes are the ones the setup page uses, so the README journey and the app do not drift apart.
        val page = File(root, "beacon-android/app/src/main/java/dev/hermes/android/ui/SetupPluginStep.kt").readText()
        val title = "Add the Beacon plugin to Hermes"
        assertTrue("uiText(\"$title\")" in page)
        assertTrue("\"$title\"" in guide)
        for (action in listOf("Try again", "Sign in again", "Skip for now", "Set up from a computer")) {
            assertTrue("the guide names $action", action in guide)
            assertTrue("the page shows $action", "uiText(\"$action\")" in page)
        }
    }

    @Test fun theTailscalePromptSetsUpTailscaleAndNeverAsksForThePlugin() {
        val prompt = tailscalePrompt(readme)
        for (part in listOf("Windows", "macOS", "Linux", "winget install -e --id Tailscale.Tailscale", "https://tailscale.com/install.sh", "tailscale status", "MagicDNS", "Enable HTTPS", "https://login.tailscale.com/admin/dns",
            "tailscale serve --bg <port>", "tailscale serve status", "hermes config set dashboard.public_url", "/api/status", "phone", "same account")) assertTrue(part, part in prompt)
        assertTrue("it says outright that the app installs the plugin", "Do not install the Beacon plugin" in prompt)
        for (forbidden in listOf("beacon_install", "plugins install", "plugins update", "hermes-plugin", "xploit9")) assertFalse("The prompt must not ask for the plugin: $forbidden", forbidden in prompt)
        assertTrue("never asks for secrets", "Never ask for or print passwords, tokens, Tailscale keys or API keys" in prompt)
        assertTrue("and never changes Hermes", "never modify Hermes core" in prompt)
        assertTrue("it ends with the ready message and the link", prompt.trimEnd().endsWith("You're ready. Install the Beacon app on your phone, paste this link, and the app installs the Beacon plugin and walks you through the rest: <address>"))
    }

    @Test fun theGuideCarriesTheSamePromptAsTheReadme() {
        assertEquals(tailscalePrompt(readme), tailscalePrompt(guide))
        assertTrue("the setup wizard offers to install it" in guide)
        assertTrue("Alternative: set up from the PC" in guide)
        assertEquals("the same PC prompt", readme.lines().filter { it.startsWith("> Install Beacon on my Hermes") }, guide.lines().filter { it.startsWith("> Install Beacon on my Hermes") })
    }

    @Test fun thePcRouteInstallsTheSameSourceTheAppDoes() {
        assertEquals("xploit9/beacon/hermes-plugin/beacon", BEACON_PLUGIN_SOURCE)
        assertTrue("plugins install $BEACON_PLUGIN_SOURCE --enable" in guide)
        assertTrue(File(root, "hermes-plugin/beacon/install/beacon_install.py").readText().contains("INSTALL_SOURCE = \"$BEACON_PLUGIN_SOURCE\""))
    }

    @Test fun theAppLinksLandOnRealReadmeSections() {
        val sections = anchors(readme)
        assertTrue("install" in sections)
        assertEquals("https://github.com/xploit9/beacon#" + "alternative-set-up-from-the-pc", BEACON_PC_INSTALL_URL)
        assertTrue(BEACON_PC_INSTALL_URL.substringAfter('#') in sections)
        // The existing "How to install" card opens #install.
        val components = File(root, "beacon-android/app/src/main/java/dev/hermes/android/ui/Components.kt").readText()
        assertTrue("https://github.com/xploit9/beacon#install" in components)
    }

    @Test fun everyRelativeLinkAndImageResolves() {
        for ((name, document) in listOf("README.md" to readme, "docs/install-with-agent.md" to guide)) {
            val base = File(root, name).parentFile
            val targets = Regex("""\]\((?!https?:|#|mailto:)([^)\s]+)\)""").findAll(document).map { it.groupValues[1] }.toList() +
                Regex("""src="(?!https?:)([^"]+)"""").findAll(document).map { it.groupValues[1] }.toList()
            for (target in targets) assertTrue("$name links to $target", File(base, target.substringBefore('#')).exists())
            for (anchor in Regex("""\]\(#([^)]+)\)""").findAll(document).map { it.groupValues[1] }) assertTrue("$name anchor #$anchor", anchor in anchors(document))
        }
    }

    @Test fun noTailnetNameOwnerAddressOrSecretReachesTheDocs() {
        for ((name, document) in listOf("README.md" to readme, "docs/install-with-agent.md" to guide)) {
            // Only the bracketed placeholder appears under ts.net; a real-looking host name never does.
            assertTrue("$name shows the placeholder address", document.contains("<your-pc>.<your-tailnet>.ts.net"))
            assertFalse("$name names a real ts.net host", Regex("""[A-Za-z0-9-]\.ts\.net""").containsMatchIn(document))
            assertFalse("$name has a tailnet id", Regex("""tail[0-9a-f]{5,}""").containsMatchIn(document))
            assertFalse("$name has an auth key", Regex("""tskey-[A-Za-z0-9-]+""").containsMatchIn(document))
            assertFalse("$name has a key or token", Regex("""(?i)(api[_-]?key|token|secret)\s*[:=]\s*\S{12,}""").containsMatchIn(document))
            assertFalse("$name has an e-mail address", Regex("""[A-Za-z0-9._%+-]+@(?!users\.noreply)[A-Za-z0-9.-]+\.[a-z]{2,}""").containsMatchIn(document))
            assertFalse("$name has a user path", Regex("""(?i)[a-z]:[\\/]+users[\\/]|/users/|/home/[a-z]""").containsMatchIn(document))
        }
    }
}
