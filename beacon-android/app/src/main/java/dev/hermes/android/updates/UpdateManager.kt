package dev.hermes.android.updates

import android.Manifest
import android.app.*
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconActivity
import dev.hermes.android.R
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** One locally authorized owner/origin; intents carry only a revocable random handle. */
class UpdateManager(private val app: BeaconApplication) {
    private val prefs = app.getSharedPreferences("android-updates", Context.MODE_PRIVATE)
    private val lock = Any()
    private val updater = ApkUpdater(app)
    private val automatic = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    /** Device tests route the saved client through their synthetic transport. */
    internal var transportForTest: ((okhttp3.OkHttpClient.Builder) -> Unit)? = null
    private fun source(): UpdateSource? = prefs.getString("id", null)?.let {
        UpdateSource(prefs.getString("origin", "").orEmpty(), prefs.getString("owner", "").orEmpty(), it)
    }
    private fun requireAuthorized(expected: UpdateSource) {
        require(source() == expected && expected.authorizedBy(app.auth.read(expected.origin))) { "Update authorization changed. Sign in and check again." }
    }

    fun autoUpdateEnabled(): Boolean = prefs.getBoolean(AUTO_APP_UPDATES, true)

    fun setAutoUpdateEnabled(enabled: Boolean) {
        check(prefs.edit().putBoolean(AUTO_APP_UPDATES, enabled).commit()) { "Could not save automatic app updates" }
        if (enabled) requestAutomaticCheck()
    }

    fun requestAutomaticCheck() {
        automatic.launch { runCatching { backgroundCheck() } }
    }

    private fun supportsAutomaticAppUpdates() = app.packageName == "dev.hermes.android" || app.packageName == "dev.hermes.android.stable"
    private fun callActive() = app.calls.state.value.active || app.calls.state.value.state == "ending" ||
        app.calls.service != null || app.calls.spec != null || app.push.incoming.value != null
    private fun canInstallSilently() = silentInstallAllowed(Build.VERSION.SDK_INT, app.packageManager.canRequestPackageInstalls(), callActive(),
        ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance)
    private fun unmeteredNetwork(): Boolean {
        val connectivity = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        return connectivity.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    }

    fun schedule(checkNow: Boolean = true) {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Beacon app updates", NotificationManager.IMPORTANCE_DEFAULT))
        // Android defers this in Doze and while the private server is unreachable.
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(6 * 60 * 60_000L).build())
        if (checkNow) requestAutomaticCheck()
    }

    /** Call only after the UI's successful bootstrap has been saved to AuthStore. */
    fun authorize(client: BeaconClient) = synchronized(lock) {
        val auth = client.auth
        require(auth.owner.isNotBlank() && UpdateSource(auth.origin, auth.owner, "").authorizedBy(app.auth.read(auth.origin))) { "Sign in before enabling update checks" }
        val previous = source()
        if (previous?.origin != auth.origin || previous?.owner != auth.owner) {
            clearAuthorization()
            check(prefs.edit().putString("origin", auth.origin).putString("owner", auth.owner).putString("id", UUID.randomUUID().toString()).commit())
        }
        schedule(checkNow = false)
    }

    /** Local revocation must precede logout/account replacement network requests. */
    fun clearAuthorization() = synchronized(lock) {
        val autoUpdates=autoUpdateEnabled()
        prefs.getInt("pending_session_id",-1).takeIf { it>=0 }?.let { session ->
            runCatching { app.packageManager.packageInstaller.abandonSession(session) }
        }
        prefs.getString("pending_file", null)?.let { name -> runCatching { pendingFile(name).delete() } }
        check(prefs.edit().clear().putBoolean(AUTO_APP_UPDATES,autoUpdates).commit()) { "Could not revoke update authorization" }
        notifications.cancel(NOTIFICATION_ID)
    }

    /** A throttled foreground check; force=true preserves manual retry. */
    suspend fun check(client: BeaconClient, force: Boolean = false, notify: Boolean = true): Release? {
        val expected = synchronized(lock) {
            val current = source() ?: error("Sign in before checking updates")
            require(current.authorizedBy(client.auth)) { "Update belongs to another owner or server" }
            current.takeIf { beginCheck(it, force) }
        } ?: return null
        val detached=client.detached()
        return try {
            val release=checkAuthenticated(detached, expected, notify)
            if (!force && release!=null) automaticUpdate(detached,expected,release)
            release
        } finally { detached.closeNative() }
    }

    private fun beginCheck(expected: UpdateSource, force: Boolean): Boolean {
        requireAuthorized(expected)
        val now = System.currentTimeMillis()
        if (!updateCheckDue(prefs.getLong("last_check", 0), now, force)) return false
        check(prefs.edit().putLong("last_check", now).commit())
        return true
    }

    private fun beginAutomaticCheck(expected: UpdateSource): Boolean {
        requireAuthorized(expected)
        val now = System.currentTimeMillis()
        if (!updateCheckDue(prefs.getLong("last_check", 0), now, false)) return false
        check(prefs.edit().putLong("last_check", now).commit())
        return true
    }

    suspend fun backgroundCheck() {
        if (!supportsAutomaticAppUpdates()) return
        val expected = synchronized(lock) {
            val current = source() ?: return
            if (!current.authorizedBy(app.auth.read(current.origin))) { clearAuthorization(); return }
            if (!beginAutomaticCheck(current)) return
            current
        }
        val client = savedClient(expected)
        try {
            val release = checkAuthenticated(client, expected, false) ?: return
            // With automatic updates off, a newer release is only announced, never downloaded.
            if (autoUpdateEnabled()) automaticUpdate(client,expected,release)
            else synchronized(lock) { requireAuthorized(expected); notifyRelease(expected,release) }
        } finally { client.closeNative() }
    }

    private suspend fun automaticUpdate(client: BeaconClient, expected: UpdateSource, release: Release) {
        if (!autoUpdateEnabled() || !supportsAutomaticAppUpdates()) return
        if (!autoUpdateInstallAllowed(autoUpdateEnabled(),unmeteredNetwork(),callActive())) {
            synchronized(lock) { requireAuthorized(expected); notifyRelease(expected,release) }
            return
        }
        downloadOwned(client,release,expected)
        if (!canInstallSilently()) postReadyNotification(expected,release.version,release.name)
        else installPending(automatic=true)
    }

    private fun savedClient(expected: UpdateSource): BeaconClient = synchronized(lock) {
        requireAuthorized(expected)
        BeaconClient(app.auth.read(expected.origin), app.auth, persistContext = false, configureHttp = transportForTest ?: {})
    }

    private suspend fun checkAuthenticated(client: BeaconClient, expected: UpdateSource, notify: Boolean): Release? {
        client.bootstrap()
        if (!expected.authorizedBy(client.auth)) {
            synchronized(lock) { if (source() == expected) clearAuthorization() }
            error("Saved Beacon owner changed. Sign in again.")
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
        if (postNotification(expected, release.version, "Beacon ${release.name} update available"))
            check(prefs.edit().putLong("notified_version", release.version).commit())
    }

    /** FCM wakes a closed app even without tailnet access. Tap rechecks the trusted feed. */
    fun notifyPush(origin: String, owner: String, versionHint: String?) = synchronized(lock) {
        val expected = source() ?: return@synchronized
        if (expected.origin != origin || expected.owner != owner || !expected.authorizedBy(app.auth.read(expected.origin))) return@synchronized
        if (pushReleaseVersion(versionHint, updater.installedVersion(), prefs.getLong("notified_version", 0)) == null) return@synchronized
        // Do not persist an unverified hint as notified_version or required download version.
        postNotification(expected, 0, "Beacon update available")
        if (autoUpdateEnabled()) requestAutomaticCheck()
    }

    private fun postNotification(expected: UpdateSource, version: Long, title: String, message: String = "Update Beacon to the latest verified Android release."): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        requireAuthorized(expected)
        val token = UUID.randomUUID().toString().replace("-", "")
        val intent = Intent(app, BeaconActivity::class.java).setData(Uri.parse("hermes://update/$token"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_TOKEN, token)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Retain before posting so the immediately actionable handle already resolves.
        check(prefs.edit().putString("notification_token", token).putLong("notification_version", version).commit())
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(title).setContentText(message)
            .setContentIntent(pending).addAction(0, "Update", pending).setAutoCancel(true).build())
        return true
    }

    private fun postReadyNotification(expected: UpdateSource, version: Long, name: String) {
        postNotification(expected, version, "Beacon $name is ready — tap to install", "Tap to finish installing the verified update.")
    }

    /** Resolves no URL, owner, cookie or version supplied by the caller. */
    suspend fun downloadNotification(token: String): File? {
        var retained: File? = null
        val expected = synchronized(lock) {
            require(validToken(token) && token == prefs.getString("notification_token", null)) { "This update notification expired. Check for updates in Beacon." }
            (source() ?: error("Sign in to update Beacon")).also { source ->
                requireAuthorized(source)
                val name=prefs.getString("pending_file",null)
                if (name!=null && prefs.getString("pending_source",null)==source.id &&
                    prefs.getLong("pending_version",0)>=prefs.getLong("notification_version",0) && hasPendingInstall()) {
                    retained=pendingFile(name)
                }
            }
        }
        if (retained!=null) return retained
        val client = savedClient(expected)
        try {
            val release = checkAuthenticated(client, expected, false) ?: return null
            synchronized(lock) {
                requireAuthorized(expected)
                require(token == prefs.getString("notification_token", null) && release.version >= prefs.getLong("notification_version", 0)) { "The advertised update changed. Check again." }
            }
            return downloadOwned(client, release, expected)
        } finally { client.closeNative() }
    }

    suspend fun download(client: BeaconClient, release: Release): File {
        val expected = synchronized(lock) {
            (source() ?: error("Sign in before downloading updates")).also {
                requireAuthorized(it)
                require(it.authorizedBy(client.auth)) { "Update belongs to another owner or server" }
            }
        }
        val detached=client.detached()
        return try { downloadOwned(detached, release, expected) } finally { detached.closeNative() }
    }

    private suspend fun downloadOwned(client: BeaconClient, release: Release, expected: UpdateSource): File {
        val file = updater.download(client, release)
        try {
            synchronized(lock) {
                requireAuthorized(expected)
                check(prefs.edit().putString("pending_file", file.name).putString("pending_source", expected.id)
                    .putString("pending_sha", release.sha).putLong("pending_size", release.size).putLong("pending_version", release.version)
                    .putString("pending_version_name",release.name)
                    .remove("pending_session_id").putBoolean("continue_install", true).putBoolean("awaiting_permission", false).commit())
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
        check(prefs.edit().remove("pending_file").remove("pending_source").remove("pending_sha").remove("pending_size").remove("pending_version")
            .remove("pending_version_name").remove("pending_session_id")
            .remove("awaiting_permission").remove("continue_install").commit())
        obsolete?.delete()
    }

    fun awaitingInstallPermission(): Boolean = synchronized(lock) {
        prefs.getBoolean("awaiting_permission", false) && hasPendingInstall()
    }

    fun shouldContinueInstall(): Boolean = synchronized(lock) {
        prefs.getBoolean("continue_install", false) && hasPendingInstall()
    }

    /** Run again after Android settings returns; automatic sessions ask Android to skip its prompt when allowed. */
    suspend fun installPending(automatic: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val session = prefs.getInt("pending_session_id",-1)
            if (session>=0) {
                if (automatic) return@withContext true
                // A manual tap replaces a leftover silent session with Android's installer.
                runCatching { app.packageManager.packageInstaller.abandonSession(session) }
                check(prefs.edit().remove("pending_session_id").commit())
            }
        }
        val pending = synchronized(lock) {
            val expected = source() ?: error("Sign in and download the update again")
            requireAuthorized(expected)
            require(prefs.getString("pending_source", null) == expected.id) { "Update authorization changed" }
            if (prefs.getLong("pending_version", 0) <= updater.installedVersion()) { clearPending(); error("Beacon is already up to date") }
            val file = pendingFile(prefs.getString("pending_file", null) ?: error("Download and verify an update first"))
            val size = prefs.getLong("pending_size", 0)
            Triple(expected, file, Triple(size,prefs.getString("pending_sha", "").orEmpty(),prefs.getString("pending_version_name","").orEmpty()))
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
        if (automatic && (!autoUpdateEnabled() || !canInstallSilently())) {
            postReadyNotification(expected,prefs.getLong("pending_version",0),bytes.third)
            return@withContext false
        }
        if (callActive()) error("Finish your call before installing the update.")
        coroutine.ensureActive()
        if (automatic) {
            var stored = -1
            try {
                updater.installWithoutUserAction(file,::installResultPendingIntent) { sessionId ->
                    synchronized(lock) {
                        require(prefs.getInt("pending_session_id",-1)<0) { "An app update is already being installed." }
                        requireAuthorized(expected)
                        require(prefs.getString("pending_file",null)==file.name && prefs.getString("pending_sha",null)==bytes.second) { "The pending update changed. Try again." }
                        require(canInstallSilently() && autoUpdateEnabled()) { "The automatic update is paused. Finish your call or turn automatic updates back on." }
                        check(prefs.edit().putInt("pending_session_id",sessionId).putBoolean("awaiting_permission",false).putBoolean("continue_install",false).commit())
                        stored = sessionId
                    }
                }
            } catch (error: Throwable) {
                // Only this attempt's session id; another attempt's stays tracked.
                synchronized(lock) { if (stored>=0 && prefs.getInt("pending_session_id",-1)==stored) prefs.edit().remove("pending_session_id").commit() }
                if (!canInstallSilently() || !autoUpdateEnabled()) postReadyNotification(expected,prefs.getLong("pending_version",0),bytes.third)
                throw error
            }
            return@withContext true
        }
        synchronized(lock) {
            requireAuthorized(expected)
            require(prefs.getString("pending_file", null) == file.name && prefs.getString("pending_sha", null) == bytes.second) { "The pending update changed. Try again." }
            // Consume continuation before leaving this process for Android confirmation.
            val needsPermission = !app.packageManager.canRequestPackageInstalls()
            check(prefs.edit().putBoolean("awaiting_permission", needsPermission).putBoolean("continue_install", needsPermission).commit())
            updater.launchInstaller(file)
        }
    }

    private fun installResultPendingIntent(sessionId: Int): PendingIntent {
        val intent=Intent(app,UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT).putExtra(EXTRA_SESSION_ID,sessionId)
        return PendingIntent.getBroadcast(app,sessionId,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    internal fun handleInstallResult(result: Intent) {
        val sessionId=result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)
        val expected=synchronized(lock) {
            if (sessionId<0 || prefs.getInt("pending_session_id",-1)!=sessionId) return
            val current=source() ?: return
            if (runCatching { requireAuthorized(current) }.isFailure) return
            current
        }
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> synchronized(lock) {
                if (prefs.getInt("pending_session_id",-1)==sessionId) clearPending()
                notifications.cancel(NOTIFICATION_ID)
            }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt=installPrompt(result)
                if (callActive() || prompt==null) {
                    runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
                    synchronized(lock) { prefs.edit().remove("pending_session_id").commit() }
                    postReadyNotification(expected,prefs.getLong("pending_version",0),prefs.getString("pending_version_name","").orEmpty())
                } else postInstallerPrompt(expected,sessionId,prompt,prefs.getString("pending_version_name","").orEmpty())
            }
            else -> {
                synchronized(lock) { prefs.edit().remove("pending_session_id").commit() }
                postReadyNotification(expected,prefs.getLong("pending_version",0),prefs.getString("pending_version_name","").orEmpty())
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun installPrompt(result: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        result.getParcelableExtra(Intent.EXTRA_INTENT,Intent::class.java) else result.getParcelableExtra(Intent.EXTRA_INTENT)

    private fun postInstallerPrompt(expected: UpdateSource,sessionId: Int,prompt: Intent,name: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        if (!notifications.areNotificationsEnabled() || notifications.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE) return
        requireAuthorized(expected)
        val pending=PendingIntent.getActivity(app,sessionId,prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(NOTIFICATION_ID,NotificationCompat.Builder(app,CHANNEL).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle("Beacon $name is ready — tap to install").setContentText("Tap to confirm the update in Android.")
            .setContentIntent(pending).addAction(0,"Install",pending).setAutoCancel(true).build())
    }

    companion object {
        const val EXTRA_TOKEN = "update_token"
        const val EXTRA_SESSION_ID = "update_session_id"
        const val CHANNEL = "hermes-updates"
        const val ACTION_INSTALL_RESULT = "dev.hermes.android.updates.INSTALL_RESULT"
        private const val AUTO_APP_UPDATES = "auto_app_updates"
        private const val JOB_ID = 4202
        private const val NOTIFICATION_ID = 4202
        fun validToken(token: String): Boolean = validUpdateToken(token)
    }
}
