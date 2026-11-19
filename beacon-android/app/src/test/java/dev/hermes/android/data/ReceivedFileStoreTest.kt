package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Received files come through Hermes' own /api/fs/download for the message's conversation and profile, and stay on the phone. */
class ReceivedFileStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val requests = mutableListOf<Request>()

    private fun client(profile: String = "work", reply: (Request) -> Response.Builder) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", profile, backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> val request = chain.request(); synchronized(requests) { requests += request }; reply(request).request(request).protocol(Protocol.HTTP_1_1).message("Fixture").build() }
        }
    private fun ok(bytes: ByteArray, type: String = "application/pdf") = Response.Builder().code(200).header("Content-Disposition", "attachment; filename=\"report.pdf\"")
        .body(bytes.toResponseBody(type.toMediaType()))
    private fun status(code: Int) = Response.Builder().code(code).body("{\"detail\":\"no\"}".toResponseBody("application/json".toMediaType()))
    private fun headOk(size: Long, type: String) = Response.Builder().code(200).header("Content-Length", "$size").header("Content-Type", type).body(ByteArray(0).toResponseBody(null))

    private fun reference(scope: Scope, path: String = "/home/me/out/report.pdf", snapshot: String? = null) = NativeReference.LocalMedia(path, scope.session, snapshot)

    @Test fun sizeAndTypeComeFromAHeadOnHermesOwnRouteForTheMessagesConversation() = runBlocking {
        val c = client { headOk(24_000, "application/pdf") }
        val scope = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        store.check(scope, reference(scope), c, message = "m1")
        val request = requests.single()
        assertEquals("HEAD", request.method)
        assertEquals("/api/fs/download", request.url.encodedPath)
        assertEquals("/home/me/out/report.pdf", request.url.queryParameter("path"))
        assertEquals("stored-a", request.url.queryParameter("session_id"))
        assertEquals("work", request.url.queryParameter("profile"))
        assertEquals(24_000L, store.state(scope, "/home/me/out/report.pdf").value.size)
        // Once per run for the same message.
        store.check(scope, reference(scope), c, message = "m1")
        assertEquals(1, requests.size)
        // A restarted app shows the size offline.
        assertEquals(24_000L, ReceivedFileStore(folder.root).state(scope, "/home/me/out/report.pdf").value.size)
    }

    @Test fun goneAndRefusedFilesSaySoAndAreNotFetchedAnyOtherWay() = runBlocking {
        val gone = client { status(404) }
        val scope = gone.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        store.check(scope, reference(scope, "/tmp/old.csv"), gone)
        assertEquals(ReceivedFileProblem.Gone, store.state(scope, "/tmp/old.csv").value.problem)
        val refused = client { status(403) }
        store.check(scope, reference(scope, "/home/me/.ssh/id.txt"), refused)
        assertEquals(ReceivedFileProblem.Refused, store.state(scope, "/home/me/.ssh/id.txt").value.problem)
        store.download(scope, reference(scope, "/home/me/.ssh/id.txt"), refused)!!.join()
        assertEquals(ReceivedFileProblem.Refused, store.state(scope, "/home/me/.ssh/id.txt").value.problem)
        assertNull(store.localCopy(scope, "/home/me/.ssh/id.txt"))
        assertEquals("every request used Hermes' download route", setOf("/api/fs/download"), requests.map { it.url.encodedPath }.toSet())
        assertEquals(ReceivedFileProblem.Refused, ReceivedFileStore(folder.root).state(scope, "/home/me/.ssh/id.txt").value.problem)
        // Older Hermes without HEAD: no line, the size waits for the download.
        val oldHermes = client { status(405) }
        store.check(scope, reference(scope, "/tmp/new.pdf"), oldHermes)
        assertNull(store.state(scope, "/tmp/new.pdf").value.problem)
    }

    @Test fun downloadShowsProgressKeepsTheCopyAndOpensAfterARestart() = runBlocking {
        val payload = ByteArray(700_000) { (it % 251).toByte() }
        val c = client { ok(payload) }
        val scope = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        val seen = mutableListOf<Long>()
        val watcher = launch(Dispatchers.Default) { store.state(scope, "/home/me/out/report.pdf").collect { if (it.downloading) synchronized(seen) { seen += it.received } } }
        store.download(scope, reference(scope), c)!!.join()
        watcher.cancel()
        val state = store.state(scope, "/home/me/out/report.pdf").value
        assertFalse(state.downloading)
        assertArrayEquals(payload, state.local!!.readBytes())
        assertEquals(700_000L, state.size)
        assertTrue("progress rose while it downloaded: $seen", synchronized(seen) { seen.zipWithNext().all { (a, b) -> b >= a } })
        assertEquals("GET", requests.single().method)
        assertTrue(folder.root.walkTopDown().none { it.name.endsWith(".part") })
        val restarted = ReceivedFileStore(folder.root)
        assertArrayEquals(payload, restarted.localCopy(scope, "/home/me/out/report.pdf")!!.readBytes())
        // The same path in another conversation or profile is another file.
        assertNull(restarted.localCopy(scope.session("stored-b"), "/home/me/out/report.pdf"))
        assertNull(restarted.localCopy(scope.copy(profile = "personal"), "/home/me/out/report.pdf"))
    }

    @Test fun cancellingStopsTheDownloadAndLeavesNothingBehind() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val endless = object : Source {
            var sent = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (sent > 0) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
                sink.write(ByteArray(64 * 1024)); sent += 64 * 1024; return 64 * 1024L
            }
            override fun timeout() = Timeout.NONE
            override fun close() { release.countDown() }
        }
        val c = client { Response.Builder().code(200).body(endless.buffer().asResponseBody("application/zip".toMediaType(), 50L * 1024 * 1024)) }
        val scope = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        val job = store.download(scope, reference(scope, "/tmp/big.zip"), c)!!
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(store.state(scope, "/tmp/big.zip").value.downloading)
        assertNull("a second tap does not start a second download", store.download(scope, reference(scope, "/tmp/big.zip"), c))
        store.cancel(scope, "/tmp/big.zip")
        release.countDown()
        withTimeout(5_000) { job.join() }
        val state = store.state(scope, "/tmp/big.zip").value
        assertFalse(state.downloading); assertNull(state.local); assertNull(state.problem)
        assertTrue(folder.root.walkTopDown().none { it.isFile && it.name.startsWith("big.zip") })
    }

    @Test fun aFileThatChangedSinceItsMessageIsNotKept() = runBlocking {
        val payload = "new bytes".toByteArray()
        val old = MessageDigest.getInstance("SHA-256").digest("old bytes".toByteArray()).joinToString("") { "%02x".format(it) }
        val c = client { ok(payload) }
        val scope = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root)
        store.download(scope, reference(scope, snapshot = old), c)!!.join()
        assertEquals(ReceivedFileProblem.Changed, store.state(scope, "/home/me/out/report.pdf").value.problem)
        assertNull(store.localCopy(scope, "/home/me/out/report.pdf"))
    }

    @Test fun signOutRemovesOnlyThatAccountsFiles() = runBlocking {
        val mine = client("work") { ok("a".toByteArray()) }
        val other = BeaconClient(AuthContext("https://other.example", emptyList(), "someone", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain -> ok("b".toByteArray()).request(chain.request()).protocol(Protocol.HTTP_1_1).message("Fixture").build() }
        }
        val a = mine.scope.session("stored-a"); val b = other.scope.session("stored-b")
        val store = ReceivedFileStore(folder.root)
        store.download(a, reference(a), mine)!!.join(); store.download(b, reference(b), other)!!.join()
        store.forgetAccount(mine.scope)
        assertNull(store.localCopy(a, "/home/me/out/report.pdf"))
        assertNull(ReceivedFileStore(folder.root).localCopy(a, "/home/me/out/report.pdf"))
        assertEquals("b", store.localCopy(b, "/home/me/out/report.pdf")!!.readText())
    }

    @Test fun overTheBudgetTheOldestCopyGoesAndItsCardStays() = runBlocking {
        var now = 1_000L
        val c = client { ok(ByteArray(600)) }
        val scope = c.scope.session("stored-a")
        val store = ReceivedFileStore(folder.root, budgetBytes = 1_000, clock = { now })
        store.download(scope, reference(scope, "/tmp/first.pdf"), c)!!.join()
        now = 2_000L
        store.download(scope, reference(scope, "/tmp/second.pdf"), c)!!.join()
        assertNull(store.localCopy(scope, "/tmp/first.pdf"))
        assertNotNull(store.localCopy(scope, "/tmp/second.pdf"))
        assertEquals(600L, ReceivedFileStore(folder.root).state(scope, "/tmp/first.pdf").first().size)
    }
}
