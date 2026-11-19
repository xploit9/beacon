package dev.hermes.android.ui

import androidx.compose.ui.graphics.Color
import dev.hermes.android.core.mediaTags
import org.commonmark.node.Link
import org.commonmark.node.Paragraph
import org.junit.Assert.*
import org.junit.Test

/** Chat QA D19 (bare web addresses) and the MEDIA: tag outside the bubble (the chat list). Uses only APIs that predate the fix. */
class ReplyRenderingTest {
    private fun links(markdown: String) = parseNativeMarkdown(markdown).descendants().filterIsInstance<Link>().map { it.destination }.toList()

    @Test fun aBareWebAddressInAReplyIsALink() {
        assertEquals(listOf("https://example.com"), links("Link: https://example.com"))
        assertEquals(listOf("https://example.com"), links("Link:https://example.com"))
        assertEquals("sentence punctuation stays text", listOf("https://example.com/a"), links("Go to https://example.com/a."))
        assertEquals("a closing bracket inside the address stays", listOf("https://example.com/wiki/A_(b)"), links("(see https://example.com/wiki/A_(b))"))
        assertEquals(listOf("http://example.com/q?x=1"), links("Old: http://example.com/q?x=1, new below"))
        assertEquals("table cells too", listOf("https://example.com"), links("| Site | Link |\n|---|---|\n| Example | https://example.com |"))
        val paragraph = parseNativeMarkdown("Link: https://example.com").descendants().filterIsInstance<Paragraph>().single()
        val shown = inlineAnnotations(paragraph, Color.Blue, onLink = {})
        assertEquals("the words read the same", "Link: https://example.com", shown.text)
        assertEquals(1, shown.getLinkAnnotations(0, shown.length).size)
    }

    @Test fun codeLinksFilesAndOtherSchemesStayAsTheyWere() {
        assertEquals(emptyList<String>(), links("`https://example.com`"))
        assertEquals(emptyList<String>(), links("```\nhttps://example.com\n```"))
        assertEquals("an existing link is not linked twice", listOf("https://example.com"), links("[site](https://example.com)"))
        assertEquals(listOf("https://example.com"), links("<https://example.com>"))
        assertEquals(emptyList<String>(), links("javascript:alert(1) ftp://example.com file:///C:/x/y.png mailto:a${'@'}example.com https://"))
        val delivered = parseNativeMarkdown("MEDIA:https://cdn.example/a.png").descendants().toList()
        assertTrue("a delivered address is a file, not a link", delivered.none { it is Link } && delivered.any { it is NativeMediaNode })
        val both = parseNativeMarkdown("MEDIA:C:\\work\\.cache\\a.pdf and https://example.com").descendants().toList()
        assertEquals("C:\\work\\.cache\\a.pdf", both.filterIsInstance<NativeMediaNode>().single().reference)
        assertEquals("https://example.com", both.filterIsInstance<Link>().single().destination)
    }

    @Test fun theChatListNamesDeliveredFiles() {
        assertEquals("Here it is: report q3.pdf", chatPreviewText("Here it is: MEDIA:C:\\Users\\me\\My Files\\report q3.pdf"))
        assertEquals("📎 a.pdf, b_c.csv", chatPreviewText("MEDIA:/tmp/a.pdf\n\nMEDIA:/tmp/b_c.csv"))
        assertEquals("Social media: example.com is down", chatPreviewText("Social media: example.com is down"))
    }

    @Test fun proseIsNeverAFile() {
        // Hermes' desktop needs the bare tag in capitals; a sentence about social media is words, not a file card.
        assertTrue(mediaTags("Social media: example.com is down").isEmpty())
        assertEquals(listOf("/tmp/x.pdf"), mediaTags("media:/tmp/x.pdf").map { it.path })
    }
}
