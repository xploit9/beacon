package dev.hermes.android.core

import dev.hermes.android.ui.NativeMediaNode
import dev.hermes.android.ui.ReceivedFileStrings
import dev.hermes.android.ui.deliversOnlyFiles
import dev.hermes.android.ui.descendants
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.parseNativeMarkdown
import org.commonmark.node.Paragraph
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ReceivedFilesTest {
    private val scope = Scope("https://hermes.example", "owner", "work", "stored-a")

    @Test fun aDeliveredUnixPathIsAFileOnHermesNotAnAddressOnItsOrigin() {
        val reference = resolveMediaTag("/home/me/out/report.pdf", scope) as NativeReference.LocalMedia
        assertEquals("/home/me/out/report.pdf", reference.path)
        assertEquals("stored-a", reference.sessionId)
        assertEquals("C:\\work\\a b.docx", (resolveMediaTag("C:\\work\\a b.docx", scope) as NativeReference.LocalMedia).path)
        assertEquals("C:/work/chart.png", (resolveMediaTag("file:///C:/work/chart.png", scope) as NativeReference.LocalMedia).path)
        assertEquals("a".repeat(64), (resolveMediaTag("/tmp/x.png", scope, mapOf("/tmp/x.png" to "A".repeat(64))) as NativeReference.LocalMedia).snapshot)
        assertTrue(resolveMediaTag("https://cdn.example/a.png", scope) is NativeReference.ExternalMedia)
        assertTrue(resolveMediaTag("/tmp/x.pdf", scope.copy(session = "")) is NativeReference.Unsupported)
        assertTrue(resolveMediaTag("/tmp/bad\u0000.pdf", scope) is NativeReference.Unsupported)
        assertTrue(resolveMediaTag("javascript:alert(1)", scope) is NativeReference.Unsupported)
    }

    @Test fun nameTypeSizeAndMimeAreWhatAPersonAndOtherAppsExpect() {
        assertEquals("report.pdf", receivedFileName("/home/me/out/report.pdf"))
        assertEquals("plan v2.docx", receivedFileName("C:\\work\\plan v2.docx"))
        assertEquals("File", receivedFileName("/"))
        assertEquals("PDF · 24 KB", receivedFileDetail("report.pdf", 24_000))
        assertEquals("CSV", receivedFileDetail("data.csv", null))
        assertEquals("File · 812 B", receivedFileDetail("Makefile", 812))
        assertEquals("3.4 MB", formatFileSize(3_400_000)); assertEquals("1.2 GB", formatFileSize(1_200_000_000)); assertEquals("120 MB", formatFileSize(120_000_000))
        assertEquals("application/pdf", receivedFileMime("a.PDF", "application/octet-stream"))
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", receivedFileMime("a.docx"))
        assertEquals("text/csv", receivedFileMime("a.csv", "text/csv; charset=utf-8"))
        assertEquals("text/x-python", receivedFileMime("a.py", "text/x-python; charset=utf-8"))
        assertEquals("application/octet-stream", receivedFileMime("a.bin", "not a type"))
        assertEquals(FileCategory.Spreadsheet, receivedFileCategory("q3.xlsx")); assertEquals(FileCategory.Archive, receivedFileCategory("out.zip"))
        assertEquals(FileCategory.Image, receivedFileCategory("chart.PNG")); assertEquals(FileCategory.Other, receivedFileCategory("Makefile"))
        assertFalse("an app package is saved or shared, never opened from a chat", receivedFileOpens("update.apk"))
        assertTrue(receivedFileOpens("report.pdf"))
    }

    @Test fun localNamesAreSafeAndHermesFileNamesAreRead() {
        assertEquals("a_b_c.pdf", safeLocalFileName("a/b\\c.pdf"))
        assertEquals("file", safeLocalFileName(".."))
        assertTrue(safeLocalFileName("x".repeat(300) + ".docx").let { it.toByteArray().size <= 200 && it.endsWith(".docx") })
        assertEquals("rapport été.pdf", contentDispositionName("attachment; filename*=utf-8''rapport%20%C3%A9t%C3%A9.pdf"))
        assertEquals("report.pdf", contentDispositionName("attachment; filename=\"report.pdf\""))
        assertNull(contentDispositionName(null))
    }

    @Test fun hermesStatusesBecomeTheCardsLines() {
        assertEquals(ReceivedFileProblem.Gone, receivedFileProblem(404))
        assertEquals(ReceivedFileProblem.Refused, receivedFileProblem(403))
        assertEquals("a folder or other non-file", ReceivedFileProblem.Refused, receivedFileProblem(400))
        assertEquals("a sign-in problem is worth another try", ReceivedFileProblem.Offline, receivedFileProblem(401))
        assertEquals(ReceivedFileProblem.TooLarge, receivedFileProblem(413))
        assertEquals(ReceivedFileProblem.Offline, receivedFileProblem(502))
    }

    @Test fun fileLinksAreFilesAndHermesRoutesStayLinks() {
        assertEquals("/home/me/report.pdf", localFileLinkPath("/home/me/report.pdf"))
        assertEquals("C:\\work\\q3.xlsx", localFileLinkPath("C:\\work\\q3.xlsx"))
        assertEquals("~/out/a.zip", localFileLinkPath("~/out/a.zip"))
        listOf("/session/abc", "/api/media?path=/a.png", "https://example.com/a.pdf", "//cdn.example/a.pdf", "/docs/page", "/a.pdf#page=2", "notes.md")
            .forEach { assertNull(it, localFileLinkPath(it)) }
    }

    /** RECV follow-up 4: a tapped /api/file/raw link keeps its in-app viewer (PDF pages, JSON tree); only a path link is a file card. */
    @Test fun aTappedHermesFileLinkKeepsItsViewerAndOnlyAPathLinkIsACard() {
        val (raw, rawCard) = tappedLink("/api/file/raw?path=C:/synthetic-workspace/reader.pdf", scope)
        assertEquals(NativeReference.LocalMedia("C:/synthetic-workspace/reader.pdf", "stored-a", null, "/api/file/raw"), raw)
        assertFalse("Hermes' own file link is not a delivery", rawCard)
        assertFalse(tappedLink("/api/media?path=/tmp/a.png", scope).second)
        assertFalse(tappedLink("file:///C:/work/a.pdf", scope).second)
        val (path, pathCard) = tappedLink("/home/me/out/report.pdf", scope)
        assertTrue("a file the agent names by its path is a card", pathCard)
        assertEquals("/home/me/out/report.pdf", (path as NativeReference.LocalMedia).path)
        assertTrue(tappedLink("https://example.com/a.pdf", scope).first is NativeReference.ExternalLink)
    }

    @Test fun aLineThatOnlyDeliversFilesShowsNoTextAndTagsAreMarked() {
        val document = parseNativeMarkdown("Done.\n\nMEDIA:/tmp/a.pdf\n\nMEDIA:/tmp/b.csv MEDIA:/tmp/c.png\n\nSee MEDIA:/tmp/d.zip here\n\nBare file:///C:/x/y.png")
        val paragraphs = document.descendants().filterIsInstance<Paragraph>().toList()
        assertEquals(listOf(false, true, true, false, false), paragraphs.map(::deliversOnlyFiles))
        val nodes = document.descendants().filterIsInstance<NativeMediaNode>().toList()
        assertEquals(listOf(true, true, true, true, false), nodes.map { it.mediaTag })
        assertEquals(listOf("/tmp/a.pdf", "/tmp/b.csv", "/tmp/c.png", "/tmp/d.zip", "file:///C:/x/y.png"), nodes.map { it.reference })
    }

    @Test fun theOwnersOwnMessageKeepsWhatWasTyped() {
        val paragraph = parseNativeMarkdown("Send each one as MEDIA:/absolute/path please").descendants().filterIsInstance<Paragraph>().single()
        assertEquals("Send each one as MEDIA:/absolute/path please", dev.hermes.android.ui.inlineAnnotations(paragraph, androidx.compose.ui.graphics.Color.Blue, fileNames = false).text)
        assertEquals("Send each one as path please", dev.hermes.android.ui.inlineAnnotations(paragraph, androidx.compose.ui.graphics.Color.Blue).text)
    }

    @Test fun aReplyStillArrivingShowsNamesAndNoCards() {
        // Mid-stream the last tag can be half a path: "C:\Users\me\Lo" must not become a card (or a HEAD) for "Lo".
        val partial = parseNativeMarkdown("Here you go:\n\nMEDIA:C:\\Users\\me\\Lo").descendants().filterIsInstance<Paragraph>().last()
        val cards = dev.hermes.android.ui.FileTags.Cards
        val names = dev.hermes.android.ui.FileTags.Names
        val typed = dev.hermes.android.ui.FileTags.Typed
        assertTrue(dev.hermes.android.ui.mediaUnder(partial, names).isEmpty())
        assertTrue("its paragraph shows the name instead", dev.hermes.android.ui.showsParagraphText(partial, names))
        assertEquals(1, dev.hermes.android.ui.mediaUnder(partial, cards).size)
        assertFalse(dev.hermes.android.ui.showsParagraphText(partial, cards))
        assertTrue(dev.hermes.android.ui.mediaUnder(partial, typed).isEmpty())
        // Pictures in Markdown and bare file:// addresses keep their previews in every mode.
        val image = parseNativeMarkdown("![chart](https://cdn.example/a.png) file:///C:/x/y.png").descendants().filterIsInstance<Paragraph>().single()
        assertEquals(2, dev.hermes.android.ui.mediaUnder(image, names).size)
    }

    @Test fun readAloudSaysTheFileNameNotThePath() {
        assertEquals("Done. report.pdf and data.csv are ready.", withFileNames("Done. MEDIA:/home/me/out/report.pdf and MEDIA:C:\\work\\data.csv are ready."))
        assertEquals("No files here.", withFileNames("No files here."))
    }

    @Test fun mediaTagsMatchHermesOnItsOwnCases() {
        // Cases from Hermes' tests/gateway/test_media_tag_cleanup.py and test_media_tag_separator.py.
        assertEquals(listOf("/tmp/早报.pdf"), mediaTags("MEDIA:/tmp/早报.pdf（782.6 KB）").map { it.path })
        assertEquals(listOf("/tmp/a.png", "/tmp/b.png"), mediaTags("MEDIA:/tmp/a.png MEDIA:/tmp/b.png").map { it.path })
        assertEquals(listOf("/tmp/stop!.md"), mediaTags("MEDIA:'/tmp/stop!.md'").map { it.path })
        assertEquals(emptyList<String>(), mediaTags("MEDIA:... and MEDIA:word").map { it.path })
        assertEquals(listOf("/tmp/x.pdf"), mediaTags("media:/tmp/x.pdf").map { it.path })
    }

    @Test fun everyCardLineHasATranslation() {
        val app = listOf(".", "app", "beacon-android/app").map(::File).first { File(it, "src/main/assets/native-locales.json").isFile }
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language ->
            ReceivedFileStrings.filter { catalog.getJSONObject(language).optString(normalized(it)).isBlank() }.map { "$language: $it" }
        }.toList()
        assertTrue("untranslated: $missing", missing.isEmpty())
    }
}
