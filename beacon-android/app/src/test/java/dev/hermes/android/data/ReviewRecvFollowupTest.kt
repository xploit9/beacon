package dev.hermes.android.data

import dev.hermes.android.core.*
import dev.hermes.android.ui.webAddressRanges
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * REVIEW-RECV "Follow-up: recv-121-3" (review-121b/.unlazy/review-121b/ReviewRecvFollowupTest.kt), inverted: each test held while its finding
 * was present and now states the fixed behaviour. Only APIs recv-121-3 already has, so the same file runs against that tree. Synthetic only.
 */
class ReviewRecvFollowupTest {
    @get:Rule val folder = TemporaryFolder()
    private val requests = mutableListOf<Request>()

    private fun client(reply: (Request) -> Response.Builder) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> val request = chain.request(); synchronized(requests) { requests += request }; reply(request).request(request).protocol(Protocol.HTTP_1_1).message("Fixture").build() }
        }
    // As Starlette's FileResponse (Hermes' fs_download on a host profile): the same validators on GET and HEAD.
    private fun file(bytes: ByteArray, modified: String) = Response.Builder().code(200).header("Content-Length", "${bytes.size}")
        .header("Last-Modified", modified).header("ETag", "\"${bytes.size}-$modified\"")
        .header("Content-Disposition", "attachment; filename=\"chart.png\"").body(bytes.toResponseBody("image/png".toMediaType()))
    private fun head(bytes: ByteArray, modified: String) = Response.Builder().code(200).header("Content-Length", "${bytes.size}")
        .header("Last-Modified", modified).header("ETag", "\"${bytes.size}-$modified\"").header("Content-Type", "image/png").body(ByteArray(0).toResponseBody(null))

    private val first = "first chart, red".toByteArray()
    private val second = "second chart, blue, as the owner asked".toByteArray()
    private var served = first
    private var modified = "Mon, 16 Nov 2026 09:00:00 GMT"
    private val c by lazy { client { request -> if (request.method == "HEAD") head(served, modified) else file(served, modified) } }
    private val path = "/home/me/out/chart.png"
    private fun rewrite(bytes: ByteArray = second) { served = bytes; modified = "Mon, 16 Nov 2026 09:20:00 GMT" }

    /** The review's original P2-1 order (download first, no card check before it), inverted: still fetched again. */
    @Test fun myP2_1OrderInvertedTheRewriteIsFetchedAgain() = runBlocking {
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia(path, chat.session, null)
        val store = ReceivedFileStore(folder.root)
        store.download(chat, tag, c)!!.join()
        rewrite()
        val before = requests.size
        store.check(chat, tag, c)
        assertTrue("Hermes was asked", requests.size > before)
        assertNull("the first version is not handed out", store.localCopy(chat, path))
        assertNull(ReceivedFileStore(folder.root).localCopy(chat, path))
    }

    /**
     * FU2-P2-1, inverted. Card A asks, the agent rewrites the picture, the later message's card B asks again: it shows the new size, and the
     * store says its picture must load again (the card then lets the cached one go). With a copy shown again before the rewrite, the copy
     * (the picture's source) goes too.
     */
    @Test fun theLaterCardOfARewrittenFileAsksAndShowsItAsItIsNow() = runBlocking {
        val chat = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        store.check(chat, NativeReference.LocalMedia(path, chat.session, null), c)
        assertEquals(first.size.toLong(), store.state(chat, path).value.size)
        rewrite()
        val before = requests.size
        assertTrue("card B's picture must load again", store.check(chat, NativeReference.LocalMedia(path, chat.session, null), c))
        assertTrue("card B asks Hermes", requests.size > before)
        assertEquals("card B shows the new size", second.size.toLong(), store.state(chat, path).value.size)

        served = first; modified = "Mon, 16 Nov 2026 09:00:00 GMT"
        val other = c.scope.session("stored-b")
        val tagA = NativeReference.LocalMedia(path, other.session, null)
        store.download(other, tagA, c)!!.join()
        store.check(other, tagA, c)
        rewrite()
        assertTrue(store.check(other, NativeReference.LocalMedia(path, other.session, null), c))
        assertNull("the first version's copy is no longer the picture's source", store.localCopy(other, path))
    }

    /** A rewrite that keeps the size (a recoloured chart can) is still seen: Hermes' ETag and date changed. */
    @Test fun aRewriteOfTheSameSizeIsSeenByItsVersion() = runBlocking {
        val chat = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        store.check(chat, NativeReference.LocalMedia(path, chat.session, null), c)
        rewrite("first chart, tan".toByteArray())
        assertEquals(first.size, served.size)
        assertTrue(store.check(chat, NativeReference.LocalMedia(path, chat.session, null), c))
    }

    /** FU2-P3-2, inverted: a bare link ends at CJK punctuation. */
    @Test fun aBareLinkStopsAtCjkPunctuation() {
        val text = "请看 https://example.com/report，然后打开。"
        assertEquals("https://example.com/report", text.substring(webAddressRanges(text).single()))
        val japanese = "詳細は https://example.com/a「資料」をご覧ください"
        assertEquals("https://example.com/a", japanese.substring(webAddressRanges(japanese).single()))
    }

    /** FU2-P3-6, inverted: the room a download leaves free scales with the file, so a small file still comes on a nearly full phone. */
    @Test fun theSpaceReserveScalesToTheFile() {
        assertEquals("a 10 KB text file", 64L shl 20, receivedFileReserve(10_000L))
        assertEquals(300L shl 20, receivedFileReserve(300L shl 20))
        assertEquals("never more than 1 GB", 1L shl 30, receivedFileReserve(3_000_000_000L))
    }
}
