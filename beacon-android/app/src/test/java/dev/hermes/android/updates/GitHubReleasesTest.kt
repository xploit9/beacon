package dev.hermes.android.updates

import dev.hermes.android.core.Release
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The GitHub side of updates, against a local server: release selection, trust limits and the host rules. */
class GitHubReleasesTest {
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val apk = ByteArray(2048) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val latestPath = "/repos/xploit9/beacon/releases/latest"
    private lateinit var dir: File

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = routes[request.path?.substringBefore('?')]?.invoke() ?: MockResponse().setResponseCode(404)
        }
        server.start()
        dir = Files.createTempDirectory("beacon-github-test").toFile()
    }

    @After fun stop() { server.shutdown(); dir.deleteRecursively() }

    private fun source(secure: Boolean = false, hosts: Set<String> = setOf(server.hostName)) =
        GitHubReleases(OkHttpClient(), server.url("/").toString().trimEnd('/'), "xploit9/beacon", "Beacon-Test", hosts, secure)

    private fun metadata(version: String = "1.1.3", code: Int = 113, size: Int = apk.size, digest: String = sha, plugin: String = "1.1.3") = JSONObject()
        .put("package_name", "dev.hermes.android.stable").put("version_name", version).put("version_code", code).put("artifact_filename", "Beacon-$version.apk")
        .put("size_bytes", size).put("sha256", digest).put("certificate_sha256", "a".repeat(64)).put("plugin_version", plugin)

    private fun asset(name: String, size: Int = 10, state: String = "uploaded") =
        JSONObject().put("name", name).put("browser_download_url", server.url("/dl/$name").toString()).put("size", size).put("state", state)

    private fun announcement(assets: List<JSONObject>, draft: Boolean = false, prerelease: Boolean = false, body: String = "## What's new\n- Faster updates") = JSONObject()
        .put("tag_name", "v1.1.3").put("draft", draft).put("prerelease", prerelease).put("body", body).put("published_at", "2026-10-24T09:00:00Z").put("assets", JSONArray(assets))

    private val complete get() = listOf(asset("Beacon-1.1.3.json"), asset("Beacon-1.1.3.apk", apk.size), asset("SHA256SUMS.txt"), asset("notes.pdf"))

    private fun serve(answer: JSONObject = announcement(complete), meta: JSONObject = metadata(), etag: String? = "\"v1\"") {
        routes[latestPath] = { MockResponse().setBody(answer.toString()).apply { if (etag != null) setHeader("ETag", etag) } }
        routes["/dl/Beacon-1.1.3.json"] = { MockResponse().setBody(meta.toString()) }
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setBody(Buffer().write(apk)) }
    }

    private fun found(from: GitHubReleases = source(), etag: String? = null) = (runBlocking { from.latest(etag) } as LatestRelease.Found)
    private fun refuses(kind: UpdateException.Kind, block: suspend () -> Unit) {
        try { runBlocking { block() }; fail("Accepted") } catch (error: UpdateException) { assertEquals(error.message, kind, error.kind) }
    }
    private fun announced(url: String = server.url("/dl/Beacon-1.1.3.apk").toString(), size: Long = apk.size.toLong(), digest: String = sha) =
        Release(113, "1.1.3", "dev.hermes.android.stable", url, digest, size, "a".repeat(64), "1.1.3")

    @Test fun theLatestReleaseBecomesOneUpdateAndOtherAssetsAreIgnored() {
        serve()
        val result = found()
        val release = result.release!!
        assertEquals(Release(113, "1.1.3", "dev.hermes.android.stable", server.url("/dl/Beacon-1.1.3.apk").toString(), sha, apk.size.toLong(), "a".repeat(64), "1.1.3", "## What's new\n- Faster updates", "2026-10-24T09:00:00Z"), release)
        assertEquals("\"v1\"", result.etag)
        val request = server.takeRequest()
        assertEquals(latestPath, request.path)
        assertEquals("application/vnd.github+json", request.getHeader("Accept"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
        assertEquals("Beacon-Test", request.getHeader("User-Agent"))
        assertNull(request.getHeader("If-None-Match"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun aStoredEtagMakesAnUnchangedReleaseFree() {
        routes[latestPath] = { MockResponse().setResponseCode(304) }
        assertEquals(LatestRelease.NotModified, runBlocking { source().latest("\"v1\"") })
        assertEquals("\"v1\"", server.takeRequest().getHeader("If-None-Match"))
        // A blank stored tag is never sent.
        runBlocking { source().latest("  ") }
        assertNull(server.takeRequest().getHeader("If-None-Match"))
    }

    @Test fun noReleaseDraftPrereleaseOrIncompleteReleaseOffersNothing() {
        assertNull(found().release) // 404: nothing published yet
        serve(announcement(complete, draft = true)); assertNull(found().release)
        serve(announcement(complete, prerelease = true)); assertNull(found().release)
        serve(announcement(listOf(asset("source.zip"), asset("SHA256SUMS.txt")))); assertNull(found().release)
        serve(announcement(listOf(asset("Beacon-1.1.3.json")))); assertNull(found().release) // metadata without its APK
        serve(announcement(listOf(asset("Beacon-1.1.3.json"), asset("Beacon-1.1.3.apk", apk.size, state = "starter")))); assertNull(found().release)
        serve(announcement(complete)); assertNotNull(found().release)
    }

    @Test fun twoMetadataFilesAreAmbiguousAndRefused() {
        serve(announcement(complete + asset("Beacon-1.1.4.json")))
        refuses(UpdateException.Kind.Verification) { source().latest(null) }
    }

    @Test fun answersBeyondTheirLimitsAreRefused() {
        serve(announcement(complete, body = "x".repeat(300_000)))
        refuses(UpdateException.Kind.Verification) { source().latest(null) }
        serve(meta = metadata().put("padding", "x".repeat(17_000)))
        refuses(UpdateException.Kind.Verification) { source().latest(null) }
        serve(meta = metadata(size = 256 * 1024 * 1024 + 1))
        refuses(UpdateException.Kind.Verification) { source().latest(null) }
        // A long release body is kept, but only its first 8 KiB.
        serve(announcement(complete, body = "word ".repeat(5_000)))
        assertTrue(found().release!!.notes.length <= 8 * 1024)
    }

    @Test fun metadataThatContradictsTheReleaseIsRefused() {
        val bad = listOf(
            metadata().put("artifact_filename", "Beacon-other.apk"),
            metadata(version = "1.1.4"),
            metadata(digest = sha.uppercase()),
            metadata(digest = "abc"),
            metadata(plugin = "1.1"),
            metadata(code = 20),
            metadata(size = 0),
            metadata(size = apk.size + 1), // the API lists a different size for the APK
            metadata().put("package_name", ""),
            metadata().put("certificate_sha256", "xyz"),
            metadata().put("version_code", "113"),
        )
        bad.forEach { serve(meta = it); refuses(UpdateException.Kind.Verification) { source().latest(null) } }
        serve(); assertNotNull(found().release)
    }

    @Test fun anUnreadableAnswerIsRefusedAndRateLimitingIsANetworkProblem() {
        routes[latestPath] = { MockResponse().setBody("not json") }
        refuses(UpdateException.Kind.Verification) { source().latest(null) }
        routes[latestPath] = { MockResponse().setResponseCode(403) }
        refuses(UpdateException.Kind.Network) { source().latest(null) }
        routes[latestPath] = { MockResponse().setResponseCode(500) }
        refuses(UpdateException.Kind.Network) { source().latest(null) }
    }

    @Test fun addressesOutsideTheAllowedHostsOrWithoutHttpsAreNeverUsed() {
        serve()
        // Only HTTPS: a plain-HTTP API address is refused before any request leaves the phone.
        refuses(UpdateException.Kind.Verification) { source(secure = true).latest(null) }
        assertEquals(0, server.requestCount)
        // An asset address on another host is refused, whatever it looks like.
        listOf("http://github.com.evil.invalid/dl/Beacon-1.1.3.apk", "http://${server.hostName}@evil.invalid/Beacon-1.1.3.apk", "http://evil.invalid/Beacon-1.1.3.apk").forEach { address ->
            serve(announcement(listOf(asset("Beacon-1.1.3.json"), asset("Beacon-1.1.3.apk", apk.size).put("browser_download_url", address))))
            refuses(UpdateException.Kind.Verification) { source().latest(null) }
        }
    }

    @Test fun theApkIsDownloadedChecked_AndRedirectsStayOnAllowedHosts() {
        serve()
        val part = File(dir, "a.part")
        var last = 0L
        runBlocking { source().download(announced(), part) { last = it } }
        assertArrayEquals(apk, part.readBytes())
        assertEquals(apk.size.toLong(), last)
        // A redirect to the same allowed host is followed (GitHub hands the file to its asset CDN this way).
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setResponseCode(302).setHeader("Location", "/cdn/Beacon-1.1.3.apk") }
        routes["/cdn/Beacon-1.1.3.apk"] = { MockResponse().setBody(Buffer().write(apk)) }
        val moved = File(dir, "b.part")
        runBlocking { source().download(announced(), moved) {} }
        assertArrayEquals(apk, moved.readBytes())
        // A redirect to any other host is refused without a request.
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setResponseCode(302).setHeader("Location", "http://evil.invalid/Beacon-1.1.3.apk") }
        refuses(UpdateException.Kind.Verification) { source().download(announced(), File(dir, "c.part")) {} }
        // So is a loop of redirects.
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setResponseCode(302).setHeader("Location", "/dl/Beacon-1.1.3.apk") }
        refuses(UpdateException.Kind.Network) { source().download(announced(), File(dir, "d.part")) {} }
    }

    @Test fun theDownloadMustBeExactlyTheAnnouncedBytes() {
        serve()
        val longer = apk + 1.toByte()
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setBody(Buffer().write(longer)) }
        refuses(UpdateException.Kind.Verification) { source().download(announced(), File(dir, "long.part")) {} }
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setBody(Buffer().write(apk.copyOf(apk.size - 1))) }
        refuses(UpdateException.Kind.Verification) { source().download(announced(), File(dir, "short.part")) {} }
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setBody(Buffer().write(apk.copyOf().also { it[0] = (it[0] + 1).toByte() })) }
        refuses(UpdateException.Kind.Verification) { source().download(announced(), File(dir, "swapped.part")) {} }
        routes["/dl/Beacon-1.1.3.apk"] = { MockResponse().setResponseCode(404) }
        refuses(UpdateException.Kind.Network) { source().download(announced(), File(dir, "missing.part")) {} }
        refuses(UpdateException.Kind.Verification) { source().download(announced(url = "http://evil.invalid/Beacon-1.1.3.apk"), File(dir, "elsewhere.part")) {} }
    }
}
