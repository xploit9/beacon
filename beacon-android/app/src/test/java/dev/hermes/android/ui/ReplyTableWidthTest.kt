package dev.hermes.android.ui

import dev.hermes.android.core.FILES_MARK
import dev.hermes.android.core.withFileNames
import org.junit.Assert.*
import org.junit.Test

/** Chat QA D20: a reply holding a table takes the transcript's width; and a reply copied or shared names its files. */
class ReplyTableWidthTest {
    @Test fun aReplyWithATableIsKnown() {
        assertTrue(holdsMarkdownTable("| a | b |\n|---|:--:|\n| 1 | 2 |"))
        assertTrue(holdsMarkdownTable("a | b\n--- | ---\n1 | 2"))
        assertTrue(holdsMarkdownTable("Intro\n\n| Only |\n|---|\n| one |"))
        assertFalse("a heading underline", holdsMarkdownTable("Title\n---\ntext"))
        assertFalse(holdsMarkdownTable("a - b | c"))
        assertFalse(holdsMarkdownTable("Plain words."))
    }

    @Test fun copiedOrSharedRepliesNameTheirFiles() {
        assertEquals("Done: report.pdf", withFileNames("Done: MEDIA:/home/me/out/report.pdf"))
        assertEquals(FILES_MARK + "report.pdf", withFileNames("**MEDIA:/home/me/out/report.pdf**", FILES_MARK))
        assertEquals("No files.", withFileNames("No files.", FILES_MARK))
    }
}
