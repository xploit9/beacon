package dev.hermes.android

import android.app.Instrumentation
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.sha256
import dev.hermes.android.updates.ApkUpdater
import dev.hermes.android.updates.GitHubReleases
import dev.hermes.android.updates.UpdateFailure
import dev.hermes.android.updates.UpdateManager
import dev.hermes.android.updates.UpdatePhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The real update manager on isolated storage, with a canned GitHub and no installer: what an update may and may not do. */
class UpdateMigrationDeviceTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var prefix: String
    private lateinit var directory: File
    private lateinit var app: BeaconApplication
    private lateinit var prefs: SharedPreferences
    private val installerOpened = AtomicInteger()
    private val apk = ByteArray(4096) { (it % 251).toByte() } // not an APK: it must never get as far as Android
    private val installedCode get() = ApkUpdater(app).installedVersion()
    private val sessionsBefore get() = target.packageManager.packageInstaller.mySessions.size

    @Before fun isolateApplication() {
        check(BuildConfig.DEBUG && target.packageName.endsWith(".debug")) { "Use the isolated debug test installation" }
        prefix = "update-device-${UUID.randomUUID()}"
        directory = File(target.cacheDir, prefix).apply { check(mkdirs()) }
        val context = object : ContextWrapper(target) {
            override fun getFilesDir() = directory
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
            override fun startActivity(intent: Intent) { installerOpened.incrementAndGet() }
        }
        // Attach without onCreate(): no real startup jobs, pairing, Firebase or cache maintenance.
        app = Instrumentation.newApplication(BeaconApplication::class.java, context) as BeaconApplication
        prefs = app.getSharedPreferences("android-updates", Context.MODE_PRIVATE)
    }

    @After fun discardApplication() {
        target.getSystemService(NotificationManager::class.java).cancel(4202)
        if (::prefix.isInitialized) target.deleteSharedPreferences("$prefix-android-updates")
        if (::directory.isInitialized) check(directory.deleteRecursively())
    }

    /** GitHub, as the app sees it: the latest release, its metadata file and its APK. Every request is recorded. */
    private inner class FakeGitHub(var code: Long = installedCode + 1, var digest: String = "", var signer: String = ApkUpdater(app).signer()) : Interceptor {
        val paths = CopyOnWriteArrayList<String>()
        var etag = "\"v1\""
        private val sha get() = digest.ifBlank { sha256(apk) }
        private fun assetUrl(name: String) = "https://github.com/xploit9/beacon/releases/download/v99.0.0/$name"
        private fun announcement() = JSONObject().put("tag_name", "v99.0.0").put("draft", false).put("prerelease", false).put("body", "## What's new\n- Calmer updates")
            .put("published_at", "2026-10-24T09:00:00Z").put("assets", JSONArray(listOf(
                JSONObject().put("name", "Beacon-99.0.0.json").put("browser_download_url", assetUrl("Beacon-99.0.0.json")).put("size", 10).put("state", "uploaded"),
                JSONObject().put("name", "Beacon-99.0.0.apk").put("browser_download_url", assetUrl("Beacon-99.0.0.apk")).put("size", apk.size).put("state", "uploaded"))))
        private fun metadata() = JSONObject().put("package_name", target.packageName).put("version_name", "99.0.0").put("version_code", code).put("artifact_filename", "Beacon-99.0.0.apk")
            .put("size_bytes", apk.size).put("sha256", sha).put("certificate_sha256", signer).put("plugin_version", "1.1.3")
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val path = request.url.encodedPath
            paths += path
            fun answer(status: Int, body: ByteArray = ByteArray(0)) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("canned").header("ETag", etag)
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
            return when {
                path.endsWith("/releases/latest") -> if (request.header("If-None-Match") == etag) answer(304) else answer(200, announcement().toString().toByteArray())
                path.endsWith(".json") -> answer(200, metadata().toString().toByteArray())
                path.endsWith(".apk") -> answer(200, apk)
                else -> answer(404)
            }
        }
        fun requests(suffix: String) = paths.count { it.endsWith(suffix) }
    }

    private fun manager(github: Interceptor, selfUpdates: Boolean = true) = UpdateManager(app, GitHubReleases(OkHttpClient.Builder().addInterceptor(github).build()), selfUpdates)
    private fun dueAgain() = assertTrue(prefs.edit().remove("last_check").remove("last_attempt").commit())
    private fun notifications(): NotificationManager = target.getSystemService(NotificationManager::class.java).also {
        check(it.areNotificationsEnabled()) { "Enable notifications for the isolated debug test installation" }
        it.createNotificationChannel(NotificationChannel(UpdateManager.CHANNEL, "Beacon app updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun posted(manager: NotificationManager) = manager.activeNotifications.any { it.id == 4202 && it.tag == null }

    private fun retainHermesStagedUpdate(): File {
        val file = File(File(directory, "updates").apply { mkdirs() }, "legacy.apk").apply { writeBytes(apk) }
        assertTrue(prefs.edit().clear().putString("origin", "https://native.example").putString("owner", "alice").putString("id", "legacy-source").putString("native_provider", "basic")
            .putString("pending_file", file.name).putString("pending_source", "legacy-source").putString("pending_sha", sha256(file)).putLong("pending_size", apk.size.toLong())
            .putLong("pending_version", Long.MAX_VALUE).putInt("pending_session_id", -1).putBoolean("continue_install", true).putBoolean("awaiting_permission", true)
            .putString("notification_token", "a".repeat(32)).putLong("notification_version", Long.MAX_VALUE).putLong("notified_version", 0).putBoolean("auto_app_updates", true).commit())
        return file
    }

    @Test fun startupDiscardsTheHermesStagedUpdateOfAnOlderBeacon() {
        val file = retainHermesStagedUpdate()
        val partial = File(file.parentFile, "killed.part").apply { writeBytes(apk) }
        val github = FakeGitHub()
        val manager = manager(github)
        assertFalse(file.exists())
        assertFalse(partial.exists())
        assertEquals(setOf("format"), prefs.all.keys)
        assertEquals(UpdatePhase.Idle, manager.state.value.phase)
        assertNull(manager.state.value.release)
        assertTrue(github.paths.isEmpty())
        assertEquals(0, installerOpened.get())
    }

    @Test fun startupCancelsTheOldUpdateNotification() {
        retainHermesStagedUpdate()
        val system = notifications()
        check(system.activeNotifications.none { it.id == 4202 && it.tag == null }) { "An existing update notification must not be replaced by the test" }
        try {
            system.notify(4202, NotificationCompat.Builder(target, UpdateManager.CHANNEL).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle("Synthetic old update").build())
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (!posted(system) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            assertTrue(posted(system))
            manager(FakeGitHub())
            while (posted(system) && SystemClock.elapsedRealtime() < deadline + 5_000) SystemClock.sleep(20)
            assertFalse(posted(system))
        } finally { system.cancel(4202) }
    }

    @Test fun aNewerGitHubReleaseIsOfferedAndNotifiedOnceAndNothingInstallsByItself() = runBlocking {
        val system = notifications()
        check(system.activeNotifications.none { it.id == 4202 && it.tag == null }) { "An existing update notification must not be replaced by the test" }
        val sessions = sessionsBefore
        val github = FakeGitHub()
        val manager = manager(github)
        try {
            manager.backgroundCheck()
            val offered = manager.state.value
            assertEquals(UpdatePhase.Available, offered.phase)
            assertEquals(installedCode + 1, offered.release?.version)
            assertEquals("99.0.0", offered.release?.name)
            assertEquals("1.1.3", offered.release?.pluginVersion)
            assertTrue(offered.checkedAt > 0)
            assertEquals(installedCode + 1, prefs.getLong("notified_version", 0))
            assertTrue(posted(system))
            // The same release again: GitHub says 304, nothing more is posted, and nothing was downloaded.
            system.cancel(4202)
            dueAgain()
            manager.backgroundCheck()
            assertEquals(2, github.requests("/releases/latest"))
            assertEquals(1, github.requests(".json"))
            assertEquals(0, github.requests(".apk"))
            assertEquals(UpdatePhase.Available, manager.state.value.phase)
            assertFalse(posted(system))
            assertFalse(prefs.contains("pending_file"))
            assertEquals(sessions, sessionsBefore)
            assertEquals(0, installerOpened.get())
        } finally { system.cancel(4202) }
    }

    @Test fun anApkThatIsNotThisAppSignedByItsOwnerFailsVerificationAndLeavesNothingBehind() = runBlocking {
        val sessions = sessionsBefore
        val manager = manager(FakeGitHub())
        manager.backgroundCheck()
        assertEquals(UpdatePhase.Available, manager.state.value.phase)
        manager.startUpdate(null)
        val failed = withTimeout(20_000) { manager.state.first { it.phase == UpdatePhase.Failed } }
        assertEquals(UpdateFailure.Verification, failed.failure)
        assertFalse(prefs.contains("pending_file"))
        assertTrue(File(directory, "updates").listFiles().orEmpty().isEmpty())
        assertEquals(sessions, sessionsBefore)
        assertEquals(0, installerOpened.get())
    }

    @Test fun aReplacedVersionOrAnotherSignerIsRefusedAndNeverAnnounced() = runBlocking {
        val github = FakeGitHub()
        val manager = manager(github)
        manager.backgroundCheck()
        assertEquals(UpdatePhase.Available, manager.state.value.phase)
        // The same version code with other bytes.
        github.digest = "c".repeat(64); github.etag = "\"v2\""
        dueAgain()
        manager.checkNow()
        assertEquals(UpdateFailure.CheckVerification, withTimeout(20_000) { manager.state.first { it.phase == UpdatePhase.Failed } }.failure)
        // Another signing certificate on a fresh version.
        val other = FakeGitHub(code = installedCode + 2, signer = "d".repeat(64)).also { it.etag = "\"v3\"" }
        val strict = manager(other)
        strict.checkNow()
        assertEquals(UpdateFailure.CheckVerification, withTimeout(20_000) { strict.state.first { it.phase == UpdatePhase.Failed } }.failure)
        assertEquals(installedCode + 1, prefs.getLong("seen_code", 0))
        assertEquals(0, installerOpened.get())
    }

    @Test fun previewAndCandidateBuildsNeverCheckNotifyOrInstall() = runBlocking {
        val github = FakeGitHub()
        val manager = manager(github, selfUpdates = false)
        manager.schedule()
        manager.backgroundCheck()
        manager.checkNow()
        manager.startUpdate(null)
        SystemClock.sleep(500)
        assertTrue(github.paths.isEmpty())
        assertFalse(manager.state.value.supported)
        assertEquals(0, installerOpened.get())
    }
}
