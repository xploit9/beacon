package dev.hermes.android.updates

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.CypherApplication
import dev.hermes.android.CypherActivity
import dev.hermes.android.R
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.UUID

/** One locally authorized owner/origin; intents carry only a revocable random handle. */
class UpdateManager(private val app: CypherApplication) {
    private val prefs = app.getSharedPreferences("android-updates", Context.MODE_PRIVATE)
    private val lock = Any()
    private val updater = ApkUpdater(app)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private fun source(): UpdateSource? = prefs.getString("id", null)?.let {
        UpdateSource(prefs.getString("origin", "").orEmpty(), prefs.getString("owner", "").orEmpty(), it)
    }
    private fun requireAuthorized(expected: UpdateSource) {
        require(source() == expected && expected.authorizedBy(app.auth.read(expected.origin))) { "Update authorization changed. Sign in and check again." }
    }

    fun schedule() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Cypher app updates", NotificationManager.IMPORTANCE_DEFAULT))
        // Android defers this in Doze and while the private server is unreachable.
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(6 * 60 * 60_000L).build())
    }

    /** Call only after the UI's successful bootstrap has been saved to AuthStore. */
    fun authorize(client: CypherClient) = synchronized(lock) {
        val auth = client.auth
        require(auth.owner.isNotBlank() && UpdateSource(auth.origin, auth.owner, "").authorizedBy(app.auth.read(auth.origin))) { "Sign in before enabling update checks" }
        val previous = source()
        if (previous?.origin != auth.origin || previous?.owner != auth.owner) {
            clearAuthorization()
            check(prefs.edit().putString("origin", auth.origin).putString("owner", auth.owner).putString("id", UUID.randomUUID().toString()).commit())
        }
        schedule()
    }

    /** Local revocation must precede logout/account replacement network requests. */
    fun clearAuthorization() = synchronized(lock) {
        prefs.getString("pending_file", null)?.let { name -> runCatching { pendingFile(name).delete() } }
        check(prefs.edit().clear().commit()) { "Could not revoke update authorization" }
        notifications.cancel(NOTIFICATION_ID)
    }

    /** A throttled foreground check; force=true preserves manual retry. */
    suspend fun check(client: CypherClient, force: Boolean = false, notify: Boolean = true): Release? {
        val expected = synchronized(lock) {
            val current = source() ?: error("Sign in before checking updates")
            require(current.authorizedBy(client.auth)) { "Update belongs to another owner or server" }
            current.takeIf { beginCheck(it, force) }
        } ?: return null
        return checkAuthenticated(client.detached(), expected, notify)
    }

    private fun beginCheck(expected: UpdateSource, force: Boolean): Boolean {
        requireAuthorized(expected)
        val now = System.currentTimeMillis()
        if (!updateCheckDue(prefs.getLong("last_check", 0), now, force)) return false
        check(prefs.edit().putLong("last_check", now).commit())
        return true
    }

    suspend fun backgroundCheck() {
        val expected = synchronized(lock) {
            val current = source() ?: return
            if (!current.authorizedBy(app.auth.read(current.origin))) { clearAuthorization(); return }
            if (!beginCheck(current, false)) return
            current
        }
        checkAuthenticated(savedClient(expected), expected, true)
    }

    private fun savedClient(expected: UpdateSource): CypherClient = synchronized(lock) {
        requireAuthorized(expected)
        CypherClient(app.auth.read(expected.origin), app.auth, persistContext = false)
    }

    private suspend fun checkAuthenticated(client: CypherClient, expected: UpdateSource, notify: Boolean): Release? {
        client.bootstrap()
        if (!expected.authorizedBy(client.auth)) {
            synchronized(lock) { if (source() == expected) clearAuthorization() }
            error("Saved Cypher owner changed. Sign in again.")
        }
        synchronized(lock) { requireAuthorized(expected) }
        val release = updater.check(client)
        synchronized(lock) {
            requireAuthorized(expected)
            if (release == null) {
                notifications.cancel(NOTIFICATION_ID)
                check(prefs.edit().remove("notification_token").commit())
            } else if (notify) notifyRelease(expected, release)
        }
        return release
    }

    private fun notifyRelease(expected: UpdateSource, release: Release) {
        if (!updateNotificationDue(release.version, prefs.getLong("notified_version", 0))) return
        if (postNotification(expected, release.version, "Cypher ${release.name} update available"))
            check(prefs.edit().putLong("notified_version", release.version).commit())
    }

    /** FCM wakes a closed app even without tailnet access. Tap rechecks the trusted feed. */
    fun notifyPush(origin: String, owner: String, versionHint: String?) = synchronized(lock) {
        val expected = source() ?: return@synchronized
        if (expected.origin != origin || expected.owner != owner || !expected.authorizedBy(app.auth.read(expected.origin))) return@synchronized
        if (pushReleaseVersion(versionHint, updater.installedVersion(), prefs.getLong("notified_version", 0)) == null) return@synchronized
        // Do not persist an unverified hint as notified_version or required download version.
        postNotification(expected, 0, "Cypher update available")
    }

    private fun postNotification(expected: UpdateSource, version: Long, title: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        requireAuthorized(expected)
        val token = UUID.randomUUID().toString().replace("-", "")
        val intent = Intent(app, CypherActivity::class.java).setData(Uri.parse("hermes://update/$token"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_TOKEN, token)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Retain before posting so the immediately actionable handle already resolves.
        check(prefs.edit().putString("notification_token", token).putLong("notification_version", version).commit())
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_cypher_notification)
            .setContentTitle(title).setContentText("Update Cypher to the latest verified Android release.")
            .setContentIntent(pending).addAction(0, "Update", pending).setAutoCancel(true).build())
        return true
    }

    /** Resolves no URL, owner, cookie or version supplied by the caller. */
    suspend fun downloadNotification(token: String): File? {
        val expected = synchronized(lock) {
            require(validToken(token) && token == prefs.getString("notification_token", null)) { "This update notification expired. Check for updates in Cypher." }
            (source() ?: error("Sign in to update Cypher")).also(::requireAuthorized)
        }
        val client = savedClient(expected)
        val release = checkAuthenticated(client, expected, false) ?: return null
        synchronized(lock) {
            requireAuthorized(expected)
            require(token == prefs.getString("notification_token", null) && release.version >= prefs.getLong("notification_version", 0)) { "The advertised update changed. Check again." }
        }
        return downloadOwned(client, release, expected)
    }

    suspend fun download(client: CypherClient, release: Release): File {
        val expected = synchronized(lock) {
            (source() ?: error("Sign in before downloading updates")).also {
                requireAuthorized(it)
                require(it.authorizedBy(client.auth)) { "Update belongs to another owner or server" }
            }
        }
        return downloadOwned(client.detached(), release, expected)
    }

    private suspend fun downloadOwned(client: CypherClient, release: Release, expected: UpdateSource): File {
        val file = updater.download(client, release)
        try {
            synchronized(lock) {
                requireAuthorized(expected)
                check(prefs.edit().putString("pending_file", file.name).putString("pending_source", expected.id)
                    .putString("pending_sha", release.sha).putLong("pending_size", release.size).putLong("pending_version", release.version)
                    .putBoolean("continue_install", true).putBoolean("awaiting_permission", false).commit())
                notifications.cancel(NOTIFICATION_ID)
            }
        } catch (e: Throwable) { file.delete(); throw e }
        return file
    }

    private fun pendingFile(name: String): File {
        require(name == File(name).name && name.endsWith(".apk")) { "Invalid retained update" }
        return File(File(app.filesDir, "updates"), name).also {
            require(it.canonicalFile.parentFile == File(app.filesDir, "updates").canonicalFile) { "Invalid retained update" }
        }
    }

    fun hasPendingInstall(): Boolean = synchronized(lock) {
        val expected = source() ?: return false
        runCatching {
            requireAuthorized(expected)
            if (prefs.contains("pending_file") && prefs.getLong("pending_version", 0) <= updater.installedVersion()) { clearPending(); return false }
            prefs.getString("pending_source", null) == expected.id && prefs.getString("pending_file", null)?.let { pendingFile(it).isFile } == true
        }.getOrDefault(false)
    }

    private fun clearPending() {
        val obsolete = prefs.getString("pending_file", null)?.let { runCatching { pendingFile(it) }.getOrNull() }
        check(prefs.edit().remove("pending_file").remove("pending_source").remove("pending_sha").remove("pending_size").remove("pending_version").remove("awaiting_permission").remove("continue_install").commit())
        obsolete?.delete()
    }

    fun awaitingInstallPermission(): Boolean = synchronized(lock) {
        prefs.getBoolean("awaiting_permission", false) && hasPendingInstall()
    }

    fun shouldContinueInstall(): Boolean = synchronized(lock) {
        prefs.getBoolean("continue_install", false) && hasPendingInstall()
    }

    /** Run again after Android settings returns. Android still confirms installation. */
    suspend fun installPending(): Boolean = withContext(Dispatchers.IO) {
        val pending = synchronized(lock) {
            val expected = source() ?: error("Sign in and download the update again")
            requireAuthorized(expected)
            require(prefs.getString("pending_source", null) == expected.id) { "Update authorization changed" }
            if (prefs.getLong("pending_version", 0) <= updater.installedVersion()) { clearPending(); error("Cypher is already up to date") }
            val file = pendingFile(prefs.getString("pending_file", null) ?: error("Download and verify an update first"))
            val size = prefs.getLong("pending_size", 0)
            Triple(expected, file, size to prefs.getString("pending_sha", "").orEmpty())
        }
        val (expected, file, bytes) = pending
        try {
            require(bytes.first in 1..256L * 1024 * 1024 && file.length() == bytes.first && sha256(file).equals(bytes.second, true)) { "The retained update changed. Tap Update to download and verify it again." }
            updater.validateInstall(file)
        } catch (e: Exception) {
            synchronized(lock) { if (source() == expected && prefs.getString("pending_file", null) == file.name) { clearPending(); file.delete() } }
            throw e
        }
        val coroutine = currentCoroutineContext()
        synchronized(lock) {
            requireAuthorized(expected)
            require(prefs.getString("pending_file", null) == file.name && prefs.getString("pending_sha", null) == bytes.second) { "The pending update changed. Try again." }
            // Keep verified bytes if the user cancels Android's installer. The UI
            // controls permission-return retry; launching never schedules auto-install.
            coroutine.ensureActive()
            // Consume continuation before leaving this process for Android confirmation.
            val needsPermission = !app.packageManager.canRequestPackageInstalls()
            check(prefs.edit().putBoolean("awaiting_permission", needsPermission).putBoolean("continue_install", needsPermission).commit())
            updater.launchInstaller(file)
        }
    }

    companion object {
        const val EXTRA_TOKEN = "update_token"
        const val CHANNEL = "hermes-updates"
        private const val JOB_ID = 4202
        private const val NOTIFICATION_ID = 4202
        fun validToken(token: String): Boolean = validUpdateToken(token)
    }
}
