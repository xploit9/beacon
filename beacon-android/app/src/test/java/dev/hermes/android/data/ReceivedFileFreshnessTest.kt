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
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Before Open, Save or Share the phone asks Hermes whether its copy is still the file (REVIEW-RECV P2-1), and Save outlives its card (P3-7). */
class ReceivedFileFreshnessTest {
    @get:Rule val folder = TemporaryFolder()
    private val requests = mutableListOf<Request>()
    private val path = "/home/me/out/report.pdf"

    private fun client(reply: (Request) -> Response.Builder) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> val request = chain.request(); synchronized(requests) { requests += request }; reply(request).request(request).protocol(Protocol.HTTP_1_1).message("Fixture").build() }
        }
    private fun host(bytes: ByteArray, modified: String, head: Boolean) = Response.Builder().code(200).header("Content-Length", "${bytes.size}")
        .header("Last-Modified", modified).header("ETag", "\"${bytes.size}-$modified\"").header("Content-Type", "application/pdf")
        .body((if (head) ByteArray(0) else bytes).toResponseBody("application/pdf".toMediaType()))
    private fun status(code: Int) = Response.Builder().code(code).body("{\"detail\":\"no\"}".toResponseBody("application/json".toMediaType()))

    private class Served(var bytes: ByteArray, var modified: String = "Mon, 16 Nov 2026 09:00:00 GMT", var code: Int = 200, var offline: Boolean = false)
    private fun hermes(served: Served) = client { request ->
        if (served.offline) throw IOException("offline")
        if (served.code != 200) status(served.code) else host(served.bytes, served.modified, request.method == "HEAD")
    }
    private suspend fun downloaded(store: ReceivedFileStore, c: BeaconClient): Pair<Scope, NativeReference.LocalMedia> {
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia(path, chat.session, null)
        store.download(chat, tag, c)!!.join()
        assertNotNull(store.localCopy(chat, path))
        return chat to tag
    }

    @Test fun anUnchangedCopyOpensAfterOneQuestion() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        val before = requests.size
        val file = store.current(chat, tag, c)
        assertArrayEquals("v1".toByteArray(), file!!.readBytes())
        assertEquals("HEAD", requests.drop(before).single().method)
    }

    @Test fun aChangedCopyIsLetGoBeforeTheAction() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        // The once-per-run check is spent; the action still asks.
        store.check(chat, tag, c)
        served.bytes = "the second version".toByteArray(); served.modified = "Mon, 16 Nov 2026 09:20:00 GMT"
        assertNull(store.current(chat, tag, c))
        assertNull(store.localCopy(chat, path))
        assertEquals(served.bytes.size.toLong(), store.state(chat, path).value.size)
    }

    @Test fun offlineOrSignedOutTheCopyStillOpens() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        served.offline = true
        assertNotNull(store.current(chat, tag, c))
        assertNotNull(store.current(chat, tag, null))
        // A Hermes that answers with an error it can't explain says nothing about the file either.
        served.offline = false; served.code = 502
        assertNotNull(store.current(chat, tag, c))
    }

    @Test fun aRefusedFileIsNotHandedOutFromItsCopyButAGoneOneIs() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        served.code = 404
        assertNotNull("the owner's copy of a file Hermes deleted still opens", store.current(chat, tag, c))
        served.code = 403
        assertNull("an access rule is not worked around", store.current(chat, tag, c))
        assertNull(store.localCopy(chat, path))
        assertEquals(ReceivedFileProblem.Refused, store.state(chat, path).value.problem)
    }

    @Test fun versionsCompareByETagThenDateThenSize() {
        val head = FileHead(10, "application/pdf", null, "W/\"abc\"", "Mon, 16 Nov 2026 09:00:00 GMT")
        assertTrue("a weak ETag is the same ETag", sameFileVersion("\"abc\"", null, 99, head))
        assertFalse(sameFileVersion("\"abd\"", "Mon, 16 Nov 2026 09:00:00 GMT", 10, head))
        assertTrue(sameFileVersion(null, "Mon, 16 Nov 2026 09:00:00 GMT", 99, head.copy(etag = null)))
        // A profile's SSH workspace: no validators on the download, only Content-Length on HEAD.
        val ssh = FileHead(10, "application/pdf", null)
        assertTrue(sameFileVersion(null, null, 10, ssh))
        assertFalse(sameFileVersion(null, null, 11, ssh))
        assertTrue("nothing to compare keeps the copy", sameFileVersion(null, null, null, FileHead(null, "", null)))
    }

    @Test fun aSshWorkspaceFileThatGrewIsFetchedAgain() = runBlocking {
        var bytes = "v1".toByteArray()
        // Hermes' _remote_download_response: Content-Disposition only on GET; Content-Length on HEAD.
        val c = client { request ->
            if (request.method == "HEAD") Response.Builder().code(200).header("Content-Length", "${bytes.size}").body(ByteArray(0).toResponseBody(null))
            else Response.Builder().code(200).header("Content-Disposition", "attachment; filename*=UTF-8''report.pdf").body(bytes.toResponseBody("application/pdf".toMediaType()))
        }
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        assertNotNull(store.current(chat, tag, c))
        bytes = "a longer second version".toByteArray()
        assertNull(store.current(chat, tag, c))
    }

    @Test fun saveFinishesAfterTheCardIsGone() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia(path, chat.session, null)
        val saving = CountDownLatch(1)
        val saved = mutableListOf<String>()
        // A card's own scope, which ends when the card scrolls away. Save is pressed (undispatched, so before the card goes: a dispatched
        // launch could be cancelled before it ever ran on a busy host, follow-up 4's forced suite), then the card goes.
        val card = CoroutineScope(Job())
        card.launch(start = CoroutineStart.UNDISPATCHED) { store.save(chat, tag, hermes(served)) { file -> saving.await(5, TimeUnit.SECONDS); saved += file.readText() } }
        card.cancel()
        saving.countDown()
        withTimeout(5_000) { while (store.state(chat, path).value.saved == null) delay(20) }
        assertEquals(listOf("v1"), saved)
        assertEquals(ReceivedFileSaved.Saved, store.state(chat, path).value.saved!!.outcome)
    }

    @Test fun aFullPhoneSaysSo() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        val (chat, tag) = downloaded(store, c)
        store.save(chat, tag, c) { throw ReceivedFileNoSpace() }.join()
        assertEquals(ReceivedFileSaved.NoSpace, store.state(chat, path).value.saved!!.outcome)
        store.save(chat, tag, null) { throw IOException("disk") }.join()
        assertEquals(ReceivedFileSaved.Failed, store.state(chat, path).value.saved!!.outcome)
        // The space line is one of the card's translated lines already.
        assertEquals("Not enough space on this phone.", ReceivedFileProblem.NoSpace.text)
    }

    @Test fun theRecordAndTemporariesNeverMeetADeliveredName() = runBlocking {
        val served = Served("v1".toByteArray())
        val c = hermes(served)
        val store = ReceivedFileStore(folder.root)
        downloaded(store, c)
        val names = folder.root.walkTopDown().filter(File::isFile).map { it.name }.toList()
        assertTrue(names.toString(), names.filter { it != "report.pdf" }.all { it.startsWith(".") })
        assertFalse("a delivered name never starts with a dot", safeLocalFileName(".record.json").startsWith("."))
    }
}
