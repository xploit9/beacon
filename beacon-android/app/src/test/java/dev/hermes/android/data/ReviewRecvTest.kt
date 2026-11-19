package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * REVIEW-RECV (review-121b/.unlazy/review-121b/REVIEW-RECV.md) on the phone's store of received files, inverted: each test held while its
 * defect was present and now states the fixed behaviour. Only APIs that predate the fix, so the same file runs against the old tree.
 * Synthetic origin and paths only.
 */
class ReviewRecvTest {
    @get:Rule val folder = TemporaryFolder()
    private val requests = mutableListOf<Request>()

    private fun client(reply: (Request) -> Response.Builder) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> val request = chain.request(); synchronized(requests) { requests += request }; reply(request).request(request).protocol(Protocol.HTTP_1_1).message("Fixture").build() }
        }
    // As Starlette's FileResponse (Hermes' fs_download on this host): the same validators on GET and HEAD.
    private fun file(bytes: ByteArray, modified: String, name: String = "report.pdf") = Response.Builder().code(200).header("Content-Length", "${bytes.size}")
        .header("Last-Modified", modified).header("ETag", "\"${bytes.size}-$modified\"")
        .header("Content-Disposition", "attachment; filename=\"$name\"").body(bytes.toResponseBody("application/pdf".toMediaType()))
    private fun head(bytes: ByteArray, modified: String) = Response.Builder().code(200).header("Content-Length", "${bytes.size}")
        .header("Last-Modified", modified).header("ETag", "\"${bytes.size}-$modified\"").header("Content-Type", "application/pdf").body(ByteArray(0).toResponseBody(null))

    /**
     * P2-1, in the app's order: the first card asks Hermes for the size, the owner opens it (a copy is kept), the agent rewrites the same file
     * and delivers the same tag again. The later card asks Hermes again and lets go of the old copy, so Open/Save/Share fetch the new version;
     * a restarted app does the same.
     */
    @Test fun aFileTheAgentRewroteAtTheSamePathIsFetchedAgain() = runBlocking {
        val first = "first version of the report".toByteArray()
        val second = "second, revised version of the report with the owner's changes".toByteArray()
        var served = first
        var modified = "Mon, 16 Nov 2026 09:00:00 GMT"
        val c = client { request -> if (request.method == "HEAD") head(served, modified) else file(served, modified) }
        val chat = c.scope.session("stored-a")
        val path = "/home/me/out/report.pdf"
        val tag = NativeReference.LocalMedia(path, chat.session, null)

        val store = ReceivedFileStore(folder.root)
        store.check(chat, tag, c)
        store.download(chat, tag, c)!!.join()
        assertArrayEquals(first, store.localCopy(chat, path)!!.readBytes())

        served = second
        modified = "Mon, 16 Nov 2026 09:20:00 GMT"
        val before = requests.size
        // The card of the later message.
        store.check(chat, tag, c)
        assertTrue("Hermes was asked again", requests.size > before)
        assertNull("the first version is not handed out", store.localCopy(chat, path))
        assertEquals(second.size.toLong(), store.state(chat, path).value.size)
        // A later run of the app: nothing of the first version comes back.
        val restarted = ReceivedFileStore(folder.root)
        restarted.check(chat, tag, c)
        assertNull(restarted.localCopy(chat, path))
        assertEquals(second.size.toLong(), restarted.state(chat, path).value.size)
        // The new version downloads and is kept.
        restarted.download(chat, tag, c)!!.join()
        assertArrayEquals(second, restarted.localCopy(chat, path)!!.readBytes())
    }

    /** The same file delivered again unchanged keeps its copy: one HEAD, no second download. */
    @Test fun anUnchangedFileKeepsItsCopy() = runBlocking {
        val bytes = "the report".toByteArray()
        val modified = "Mon, 16 Nov 2026 09:00:00 GMT"
        val c = client { request -> if (request.method == "HEAD") head(bytes, modified) else file(bytes, modified) }
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia("/home/me/out/report.pdf", chat.session, null)
        val store = ReceivedFileStore(folder.root)
        store.download(chat, tag, c)!!.join()
        store.check(chat, tag, c)
        ReceivedFileStore(folder.root).check(chat, tag, c)
        assertArrayEquals(bytes, ReceivedFileStore(folder.root).localCopy(chat, "/home/me/out/report.pdf")!!.readBytes())
        assertEquals(1, requests.count { it.method == "GET" })
    }

    /** P2-2. A delivered file named file.json is the owner's file, not the store's record, now and after a restart. */
    @Test fun aDeliveredFileNamedFileJsonKeepsItsBytes() = runBlocking {
        val payload = "{\"rows\":[1,2,3],\"owner\":\"synthetic export\"}".toByteArray()
        val modified = "Mon, 16 Nov 2026 09:00:00 GMT"
        val c = client { request -> if (request.method == "HEAD") head(payload, modified) else file(payload, modified, "file.json") }
        val chat = c.scope.session("stored-a")
        val path = "/home/me/out/file.json"
        ReceivedFileStore(folder.root).download(chat, NativeReference.LocalMedia(path, chat.session, null), c)!!.join()
        assertArrayEquals(payload, ReceivedFileStore(folder.root).localCopy(chat, path)!!.readBytes())
        // A file whose own name ends in .part is kept too, and a store opening again leaves it alone.
        val part = "/home/me/out/archive.part"
        ReceivedFileStore(folder.root).download(chat, NativeReference.LocalMedia(part, chat.session, null), c)!!.join()
        ReceivedFileStore(folder.root, dispatcher = Dispatchers.Unconfined)
        assertArrayEquals(payload, ReceivedFileStore(folder.root).localCopy(chat, part)!!.readBytes())
    }

    /** P3-1. A size check that ends after sign-out leaves nothing of the signed-out account. */
    @Test fun aSizeCheckThatEndsAfterSignOutLeavesNothing() = runBlocking {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val bytes = ByteArray(1234)
        val c = client { asked.countDown(); answer.await(5, TimeUnit.SECONDS); head(bytes, "Mon, 16 Nov 2026 09:00:00 GMT") }
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia("/home/me/out/report.pdf", chat.session, null)
        val store = ReceivedFileStore(folder.root)
        val checking = launch(Dispatchers.IO) { store.check(chat, tag, c) }
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        store.forgetAccount(chat)
        answer.countDown()
        checking.join()
        val left = folder.root.walkTopDown().filter { it != folder.root }.toList()
        assertTrue("nothing of the signed-out account: $left", left.isEmpty())
    }

    /** P3-5. Android keeps a file name to 255 bytes: a long name in Chinese or with emoji is cut by bytes, on whole characters, keeping its type. */
    @Test fun aLongNameIsCutByBytesOnWholeCharacters() {
        for (name in listOf("年度财务报告".repeat(30) + ".pdf", "📊".repeat(80) + ".xlsx", "a".repeat(400) + ".docx", "名".repeat(300))) {
            val local = safeLocalFileName(name)
            val bytes = local.toByteArray(Charsets.UTF_8).size
            assertTrue("$bytes bytes: $local", bytes <= 200)
            assertTrue(local.isNotEmpty() && !local.last().isHighSurrogate() && !local.first().isLowSurrogate())
            assertEquals(String(local.toByteArray(Charsets.UTF_8), Charsets.UTF_8), local)
            val extension = receivedFileExtension(name)
            if (extension.isNotEmpty()) assertTrue(local, local.endsWith(".$extension"))
        }
        assertEquals("report.pdf", safeLocalFileName("report.pdf"))
    }

    /** P3-6. A download a killed app left half done is cleaned when the store opens again; the copies stay. */
    @Test fun aHalfDownloadFromAnEarlierRunIsCleanedUp() = runBlocking {
        val bytes = "the report".toByteArray()
        val modified = "Mon, 16 Nov 2026 09:00:00 GMT"
        val c = client { request -> if (request.method == "HEAD") head(bytes, modified) else file(bytes, modified) }
        val chat = c.scope.session("stored-a")
        val path = "/home/me/out/report.pdf"
        ReceivedFileStore(folder.root).download(chat, NativeReference.LocalMedia(path, chat.session, null), c)!!.join()
        val copy = ReceivedFileStore(folder.root).localCopy(chat, path)!!
        // An app killed mid-download leaves the store's temporary (a name no delivered file can take: delivered names never start with a dot).
        val stray = File(copy.parentFile, ".download.part").apply { writeText("half"); setLastModified(System.currentTimeMillis() - 3_600_000) }
        // One this run is still writing stays.
        val writing = File(File(copy.parentFile.parentFile, "other").apply { mkdirs() }, ".download.part").apply { writeText("now") }
        ReceivedFileStore(folder.root, dispatcher = Dispatchers.Unconfined)
        assertFalse("the half download from the earlier run is gone", stray.exists())
        assertTrue("a download of this run is left alone", writing.isFile)
        assertArrayEquals("a delivered file is never swept", bytes, copy.readBytes())
    }
}
