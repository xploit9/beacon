package dev.hermes.android.updates

import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class ReleaseNotesTest {
    private fun notes(source: String) = releaseNotesText(source, Color.Blue) { }

    @Test fun headingsListsAndLinksReadAsOneCalmRun() {
        val text = notes("## What's new\n- Faster [downloads](https://github.com/xploit9/beacon)\n- Calmer **update** screen\n\n1. One\n2. Two")
        // The leading heading repeats the card's own "What's new" label, so it is dropped.
        assertEquals("• Faster downloads\n• Calmer update screen\n1. One\n2. Two", text.text)
        assertEquals(1, text.getLinkAnnotations(0, text.length).size)
    }

    @Test fun onlyTheLeadingTitleHeadingIsDropped() {
        assertEquals("Set up in minutes\n• One installer", notes("## Beacon 1.1.1\n\n### Set up in minutes\n- One installer").text)
        assertEquals("Intro\nDetails", notes("Intro\n\n## Details").text)
    }

    @Test fun htmlImagesAndRulesNeverBecomeContent() {
        val text = notes("Intro\n\n<script>alert(1)</script>\n\n---\n\n![diagram](https://evil.invalid/x.png)")
        assertEquals("Intro\ndiagram", text.text)
    }

    @Test fun codeStaysPlainAndEmptyNotesStayEmpty() {
        assertEquals("Run\nbeacon --version", notes("Run\n\n```\nbeacon --version\n```").text)
        assertEquals("", notes("").text)
        assertEquals("", notes("   \n").text)
    }
}
