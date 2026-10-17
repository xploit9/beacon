package dev.hermes.android.data

import dev.hermes.android.core.sha256
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class DownloadStreamTest {
    private fun generated(size: Long) = object : InputStream() {
        var remaining = size
        override fun read(): Int = if (remaining-- > 0) 97 else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(remaining, length.toLong()).toInt()
            bytes.fill(97, offset, offset + count); remaining -= count
            return count
        }
    }

    @Test fun largeDownloadUsesBoundedWritesAndEnforcesActualBytes() = runBlocking<Unit> {
        var written = 0L
        val output = object : OutputStream() {
            override fun write(value: Int) { written++ }
            override fun write(bytes: ByteArray, offset: Int, count: Int) { assertTrue(count <= 32 * 1024); written += count }
        }
        val size = 256L * 1024 * 1024
        assertEquals(size, copyDownload(generated(size), output, size))
        assertEquals(size, written)
        written = 0
        try { copyDownload(generated(32769), output, 32768); fail("Oversized response accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(32768L, written)
        assertEquals(3L, copyDownload(generated(3), output, 10)) // The caller can reject a truncated advertised APK.
    }

    @Test fun cancellationStopsChunksAndFileHashMatchesKnownDigest() = runBlocking<Unit> {
        var written = 0
        val job = launch(start = CoroutineStart.LAZY) {
            val thisJob = currentCoroutineContext().job
            copyDownload(generated(65536), object : OutputStream() {
                override fun write(value: Int) { error("Expected buffered transfer") }
                override fun write(bytes: ByteArray, offset: Int, count: Int) { written += count; thisJob.cancel() }
            }, 65536)
        }
        job.start(); job.join()
        assertEquals(32768, written)
        assertTrue(job.isCancelled)
        val file = File.createTempFile("hermes-hash", ".tmp")
        try {
            file.writeText("abc")
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256(file))
        } finally { file.delete() }
    }
    @Test fun stalledDownloadBodyCancellationPropagatesCancellationInsteadOfIoFailure() = runBlocking<Unit> {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("a").setHeader("Content-Length", "1000"))
        server.start()
        val client = CypherClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath).newBuilder().encodedQuery(original.url.encodedQuery).build()
                chain.proceed(original.newBuilder().url(local).build())
            }
        }
        val started = CompletableDeferred<Unit>(); val observed = CompletableDeferred<Throwable>()
        val job = launch(Dispatchers.Default) {
            try {
                client.copyTo("/api/plugins/cypher/mobile/releases/fixture/download", object : OutputStream() {
                    override fun write(value: Int) { started.complete(Unit) }
                    override fun write(bytes: ByteArray, offset: Int, count: Int) { started.complete(Unit) }
                })
            } catch (e: Throwable) { observed.complete(e) }
        }
        try {
            withTimeout(3000) { started.await() }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(withTimeout(3000) { observed.await() } is CancellationException)
            withTimeout(3000) { job.join() }
        } finally { job.cancel(); client.http.connectionPool.evictAll(); server.shutdown() }
    }
    @Test fun detachedClientsShareNativeHttpResourcesButKeepCookiesAndIdentityIndependent() {
        val origin = "https://native.example".toHttpUrl()
        val first = Cookie.Builder().name("session").value("first").hostOnlyDomain(origin.host).path("/").secure().httpOnly().build()
        val c = CypherClient(AuthContext(origin.toString().trimEnd('/'), listOf(first.toString()), "owner", "work", backend = "hermes-dashboard"))
        val detached = c.detached()
        assertSame(c.http.connectionPool, detached.http.connectionPool)
        assertSame(c.http.dispatcher, detached.http.dispatcher)
        assertNotSame(c.jar, detached.jar)
        c.jar.saveFromResponse(origin, listOf(Cookie.Builder().name("session").value("replacement").hostOnlyDomain(origin.host).path("/").secure().httpOnly().build()))
        assertEquals("first", detached.jar.loadForRequest(origin).single().value)
        assertEquals("replacement", c.jar.loadForRequest(origin).single().value)
        assertEquals(c.auth.owner, detached.auth.owner)
        assertEquals(c.auth.profile, detached.auth.profile)
    }

}
