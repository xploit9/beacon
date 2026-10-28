package dev.hermes.android.updates

import dev.hermes.android.core.Release
import kotlinx.coroutines.CancellationException
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
import java.util.concurrent.CopyOnWriteArrayList

/** What the Update button and the update screen ask GitHub: always the newest release, with the stored one only as an offline fallback. */
class UpdateRefreshTest {
    private class Build(val name: String, val code: Int, size: Int, seed: Int) {
        val apk = ByteArray(size) { (it % seed).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    }

    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()
    private val seen = CopyOnWriteArrayList<String>()
    private val latestPath = "/repos/xploit9/beacon/releases/latest"
    private val older = Build("1.1.11", 1111, 2048, 251)
    private val newer = Build("1.1.12", 1112, 3072, 241)
    private lateinit var dir: File

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += "${request.path} ${request.getHeader("If-None-Match").orEmpty()}".trim()
                return routes[request.path?.substringBefore('?')]?.invoke() ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        dir = Files.createTempDirectory("beacon-refresh-test").toFile()
    }

    @After fun stop() { server.shutdown(); dir.deleteRecursively() }

    private fun source(base: String = server.url("/").toString().trimEnd('/'), host: String = server.hostName) =
        GitHubReleases(OkHttpClient(), base, "xploit9/beacon", "Beacon-Test", setOf(host), false)

    private fun asset(name: String, size: Int = 10) = JSONObject().put("name", name).put("browser_download_url", server.url("/dl/$name").toString()).put("size", size).put("state", "uploaded")

    /** GitHub's latest release is now [build]. */
    private fun publish(build: Build, etag: String) {
        val metadata = JSONObject().put("package_name", "dev.hermes.android.stable").put("version_name", build.name).put("version_code", build.code)
            .put("artifact_filename", "Beacon-${build.name}.apk").put("size_bytes", build.apk.size).put("sha256", build.sha)
            .put("certificate_sha256", "a".repeat(64)).put("plugin_version", build.name)
        val announcement = JSONObject().put("draft", false).put("prerelease", false).put("body", "").put("published_at", "2026-10-27T09:00:00Z")
            .put("assets", JSONArray(listOf(asset("Beacon-${build.name}.json"), asset("Beacon-${build.name}.apk", build.apk.size))))
        routes[latestPath] = { MockResponse().setBody(announcement.toString()).setHeader("ETag", etag) }
        routes["/dl/Beacon-${build.name}.json"] = { MockResponse().setBody(metadata.toString()) }
        routes["/dl/Beacon-${build.name}.apk"] = { MockResponse().setBody(Buffer().write(build.apk)) }
    }

    /** The check's own mapping: a 304 stands for the stored release, anything else is what GitHub now calls latest. */
    private suspend fun ask(from: GitHubReleases, stored: Release?, etag: String?): Release? = when (val found = from.latest(etag)) {
        is LatestRelease.NotModified -> stored
        is LatestRelease.Found -> found.release
    }

    private fun tap(stored: Release?, from: GitHubReleases, verified: Boolean = true): Release? =
        runBlocking { releaseForUpdate(stored, { ask(from, stored, "\"v1\"") }, { verified }) }

    private fun refuses(kind: UpdateException.Kind, block: () -> Unit) {
        try { block(); fail("Accepted") } catch (error: UpdateException) { assertEquals(error.message, kind, error.kind) }
    }

    private fun lastCheckFound(): Release {
        publish(older, "\"v1\"")
        return (runBlocking { source().latest(null) } as LatestRelease.Found).release!!
    }

    @Test fun anUpdateTapDownloadsTheReleaseThatAppearedAfterTheLastCheck() {
        val stored = lastCheckFound()
        assertEquals(1111L, stored.version)
        publish(newer, "\"v2\"")
        val chosen = tap(stored, source())!!
        assertEquals(1112L, chosen.version)
        val part = File(dir, "newer.part")
        runBlocking { source().download(chosen, part) {} }
        assertArrayEquals(newer.apk, part.readBytes())
        // GitHub was asked with the stored ETag, and the older APK was never fetched.
        assertTrue(seen.contains("$latestPath \"v1\""))
        assertTrue(seen.none { it.contains("Beacon-1.1.11.apk") })
    }

    @Test fun anUnchangedReleaseStillGoesThroughTheTapAndGitHubSayingNothingIsNewerInstallsNothing() {
        val stored = lastCheckFound()
        routes[latestPath] = { MockResponse().setResponseCode(304) }
        assertEquals(stored, tap(stored, source()))
        // GitHub withdrew the release: nothing newer than this build is left to install.
        routes[latestPath] = { MockResponse().setResponseCode(404) }
        assertNull(tap(stored, source()))
    }

    @Test fun aStaleOfferRefreshesWhenTheUpdateScreenOpensAndEverythingElseKeepsTheSixHourSchedule() {
        val minute = 60_000L
        val checked = 1_000L
        val screen = checkWindow(offered = true, onUpdateScreen = true)
        assertEquals(30 * minute, screen)
        assertFalse(updateCheckDue(checked, checked + 30 * minute - 1, false, window = screen))
        assertTrue(updateCheckDue(checked, checked + 30 * minute, false, window = screen))
        // A failed attempt a moment ago still waits its 15 minutes.
        assertFalse(updateCheckDue(checked, checked + 40 * minute, false, lastAttempt = checked + 30 * minute, window = screen))
        assertTrue(updateCheckDue(checked, checked + 45 * minute, false, lastAttempt = checked + 30 * minute, window = screen))
        // No offer, or not the update screen: the 6-hour schedule.
        listOf(false to true, true to false, false to false).forEach { (offered, onScreen) ->
            assertEquals(UPDATE_CHECK_INTERVAL_MS, checkWindow(offered, onScreen))
            assertFalse(updateCheckDue(checked, checked + 30 * minute, false, window = checkWindow(offered, onScreen)))
        }
    }

    @Test fun anOfflineTapUsesTheStoredReleaseOnlyWhileItStillVerifies() {
        val stored = lastCheckFound()
        // Nothing listens on this address, so GitHub cannot be reached.
        val offline = source("http://127.0.0.1:1", "127.0.0.1")
        assertEquals(stored, tap(stored, offline))
        // The stored release no longer passes the check, or there is none: the check failure is shown.
        refuses(UpdateException.Kind.Network) { tap(stored, offline, verified = false) }
        refuses(UpdateException.Kind.Network) { tap(null, offline) }
    }

    @Test fun aReleaseGitHubAnswersWrongOrACancelledTapNeverFallsBackToTheStoredOne() {
        val stored = lastCheckFound()
        val unusable = UpdateException(UpdateException.Kind.Verification, "The release metadata is unreadable")
        refuses(UpdateException.Kind.Verification) { runBlocking { releaseForUpdate(stored, { throw unusable }, { true }) } }
        try { runBlocking { releaseForUpdate(stored, { throw CancellationException("cancelled") }, { true }) }; fail("Fell back") } catch (_: CancellationException) { }
    }
}
