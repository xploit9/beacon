package dev.hermes.android.core

import androidx.compose.ui.graphics.Color
import dev.hermes.android.ui.NativeMediaNode
import dev.hermes.android.ui.descendants
import dev.hermes.android.ui.inlineAnnotations
import dev.hermes.android.ui.parseNativeMarkdown
import org.commonmark.node.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The paths an agent's `MEDIA:` tags name, read with Hermes' own grammar (gateway/platforms/base.py MEDIA_TAG_CLEANUP_RE and the desktop's
 * apps/desktop/src/lib/chat-messages/parts.ts). Uses only the Markdown entry points that existed before receiving files was built, so it
 * fails on the old reader by assertion.
 */
class ReceivedFileTagsTest {
    private fun paths(text: String) = parseNativeMarkdown(text).descendants().filterIsInstance<NativeMediaNode>().map { it.reference }.toList()

    @Test fun pathsWithSpacesQuotesAndSentencePunctuationAreReadWhole() {
        assertEquals(listOf("/tmp/AI Brain/report.pdf"), paths("Here it is: MEDIA:/tmp/AI Brain/report.pdf"))
        assertEquals(listOf("/tmp/report.pdf"), paths("I made it, MEDIA:/tmp/report.pdf."))
        assertEquals(listOf("/tmp/my file.docx"), paths("MEDIA:\"/tmp/my file.docx\""))
        assertEquals(listOf("/srv/out/data.csv"), paths("**MEDIA:/srv/out/data.csv**"))
        assertEquals(listOf("/tmp/archive.tar.gz"), paths("MEDIA:/tmp/archive.tar.gz"))
        assertEquals(listOf("/tmp/notes.py"), paths("Your script: MEDIA:/tmp/notes.py, enjoy"))
    }

    @Test fun gluedTagsAreTwoFilesAndCodeIsNeverAFile() {
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), paths("MEDIA:/tmp/a.pngMEDIA:/tmp/b.png"))
        assertEquals(listOf("/tmp/one.pdf", "/tmp/two.zip"), paths("MEDIA:/tmp/one.pdf\nMEDIA:/tmp/two.zip"))
        assertEquals(emptyList<String>(), paths("`MEDIA:/tmp/a.pdf`\n\n```\nMEDIA:/tmp/b.pdf\n```"))
    }

    @Test fun windowsPathsKeepTheirBackslashes() {
        // CommonMark reads "\." as an escaped dot; Hermes reads the text as written.
        assertEquals(listOf("C:\\Users\\me\\.cache\\out.zip"), paths("MEDIA:C:\\Users\\me\\.cache\\out.zip"))
        assertEquals(listOf("C:\\work\\_drafts\\plan.docx"), paths("Saved MEDIA:C:\\work\\_drafts\\plan.docx for you"))
        assertEquals(listOf("D:/reports/q3.xlsx"), paths("MEDIA:D:/reports/q3.xlsx"))
    }

    @Test fun theSentenceShowsTheFileNameNotTheTag() {
        val paragraph = parseNativeMarkdown("Here is MEDIA:/home/me/out/report.pdf for you").descendants().filterIsInstance<Paragraph>().single()
        assertEquals("Here is report.pdf for you", inlineAnnotations(paragraph, Color.Blue).text)
    }
}
