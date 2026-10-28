package dev.hermes.android.updates

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.core.Release
import dev.hermes.android.core.sha256
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

internal enum class UpdatePhase { Idle, Checking, Available, Downloading, NeedsPermission, Installing, Failed }

/** Each failure is one short sentence; [retriesCheck] says whether "Try again" asks GitHub again or repeats the update. */
internal enum class UpdateFailure(val text: String, val retriesCheck: Boolean = false) {
    Check("Couldn't check for updates. Check your connection.", true),
    CheckVerification("The update didn't pass verification.", true),
    Download("Couldn't download the update. Check your connection."),
    Verification("The update didn't pass verification."),
    Install("Android couldn't install the update. Try again."),
    Call("End your call before updating Beacon."),
}

/** Every failure text the update screen shows through uiText. */
internal val UpdateFailureStrings = UpdateFailure.entries.map { it.text }

/** Shown once, on the first launch after the update this phone asked for. */
internal data class UpdatedNotice(val name: String, val notes: String)

/**
 * What the update screen shows. [release] is the newer app release (null when the app is current); [latest] is the newest
 * verified release either way, because its plugin version is the plugin's target.
 */
internal data class AppUpdateUi(
    val phase: UpdatePhase = UpdatePhase.Idle,
    val release: Release? = null,
    val latest: Release? = null,
    val checkedAt: Long = 0,
    val downloaded: Long = 0,
    val failure: UpdateFailure? = null,
    val plugin: PluginRow = PluginRow(),
    val updated: UpdatedNotice? = null,
    val supported: Boolean = true,
)

/**
 * Beacon updates itself from the public GitHub latest release, with no Hermes and no sign-in. A newer release posts one
 * notification; nothing downloads or installs until the user taps Update. When this phone is signed in to a Hermes that runs
 * the Beacon plugin, the same tap updates the plugin first through Hermes' own updater; a plugin that cannot update never blocks the app.
 */
class UpdateManager internal constructor(private val app: BeaconApplication, private val releases: GitHubReleases, private val selfUpdates: Boolean) {
    // Debug, preview and candidate builds never check, notify or install.
    constructor(app: BeaconApplication) : this(app, GitHubReleases(userAgent = "Beacon-Android/${BuildConfig.VERSION_NAME}"),
        app.packageName == "dev.hermes.android" || app.packageName == "dev.hermes.android.stable")

    private val prefs = app.getSharedPreferences("android-updates", Context.MODE_PRIVATE)
    private val lock = Any()
    private val updater = ApkUpdater(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notifications get() = app.getSystemService(NotificationManager::class.java)
    private val _state = MutableStateFlow(AppUpdateUi(supported = selfUpdates))
    internal val state: StateFlow<AppUpdateUi> = _state.asStateFlow()
    private var job: Job? = null
    // Held by every GitHub check, including the one an Update tap starts, so only one runs at a time.
    private val checking = Mutex()
    @Volatile private var probe: PluginProbe = PluginProbe.None
    @Volatile private var pluginResult: PluginRow? = null

    init { migrate(); restore() }

    /** Settles Idle/Available from what is known, so a caller only states what changed. */
    private fun publish(change: AppUpdateUi.() -> AppUpdateUi) {
        _state.update { current ->
            val next = current.change()
            val row = pluginResult ?: pluginRowFor(probe, next.latest?.pluginVersion.orEmpty())
            val shown = next.copy(plugin = row)
            if (shown.phase == UpdatePhase.Idle || shown.phase == UpdatePhase.Available) shown.copy(phase = if (shown.release != null || row.actionable) UpdatePhase.Available else UpdatePhase.Idle) else shown
        }
    }

    private fun fail(failure: UpdateFailure) {
        // A failed update may have nothing on screen to show it (signed out); the next check posts the notice again.
        if (!failure.retriesCheck) synchronized(lock) { prefs.edit().remove("notified_version").commit() }
        publish { copy(phase = UpdatePhase.Failed, failure = failure) }
    }

    /** Updates from Hermes-staged builds (1.1.x) kept bytes and ids this flow never trusts; discard them, the update downloads again from GitHub. */
    private fun migrate() {
        if (prefs.getInt(FORMAT, 0) >= FORMAT_VERSION) return
        prefs.getInt("pending_session_id", -1).takeIf { it >= 0 }?.let { runCatching { app.packageManager.packageInstaller.abandonSession(it) } }
        File(app.filesDir, "updates").listFiles()?.forEach { it.delete() }
        notifications.cancel(NOTIFICATION_ID)
        prefs.edit().clear().putInt(FORMAT, FORMAT_VERSION).commit()
    }

    /** Local facts only: the last verified release, a retained download, and whether this process was started by an update. */
    private fun restore() {
        val installed = runCatching { updater.installedVersion() }.getOrDefault(0L)
        synchronized(lock) {
            val pending = prefs.getLong("pending_version", 0)
            if (pending in 1..installed) {
                prefs.edit().putString("updated_name", prefs.getString("pending_version_name", "")).putString("updated_notes", prefs.getString("pending_notes", "")).commit()
                clearPending()
            }
        }
        val latest = Release.decode(prefs.getString("release", null))?.takeIf { it.packageName == app.packageName && !olderThanSeen(prefs.getLong("seen_code", 0), it) }
        val updated = prefs.getString("updated_name", null)?.takeIf(String::isNotBlank)?.let { UpdatedNotice(it, prefs.getString("updated_notes", "").orEmpty()) }
        publish { copy(latest = latest, release = latest?.takeIf { it.version > installed }, checkedAt = prefs.getLong("last_check", 0), updated = updated) }
    }

    private fun callActive() = app.calls.state.value.active || app.calls.state.value.state == "ending" ||
        app.calls.service != null || app.calls.spec != null || app.push.incoming.value != null
    private fun onScreen() = beaconVisible(ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState).importance)
    private fun ensureChannel() = notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Beacon app updates", NotificationManager.IMPORTANCE_DEFAULT))

    /** Called at launch and after a restart; Android defers the periodic job in Doze and while offline. */
    fun schedule(checkNow: Boolean = true) {
        // Every build lists the category in its notification settings; only stable builds post to it.
        ensureChannel()
        if (!selfUpdates) return
        val scheduler = app.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(app, UpdateCheckJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(UPDATE_CHECK_INTERVAL_MS).build())
        if (checkNow) scope.launch { runCheck(force = false, visible = false) }
    }

    /** The periodic job. */
    suspend fun backgroundCheck() { if (selfUpdates) runCheck(force = false, visible = false) }
    /**
     * Beacon came to the foreground; checks only when the last check is older than 6 hours. An offer already on screen stays put
     * while it refreshes behind it, and on the update screen ([onUpdateScreen]) that refresh is due after 30 minutes.
     */
    internal fun checkIfDue(onUpdateScreen: Boolean = false) {
        if (!selfUpdates) return
        val offered = _state.value.phase == UpdatePhase.Available
        scope.launch { runCheck(force = false, visible = !offered, window = checkWindow(offered, onUpdateScreen)) }
    }
    /** "Check for updates": always asks GitHub. */
    internal fun checkNow() { if (selfUpdates) scope.launch { runCheck(force = true, visible = true) } }

    private suspend fun runCheck(force: Boolean, visible: Boolean, window: Long = UPDATE_CHECK_INTERVAL_MS) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (job?.isActive == true || !updateCheckDue(prefs.getLong("last_check", 0), now, force, prefs.getLong("last_attempt", 0), window) || !checking.tryLock()) return
        }
        // An install waiting on Android keeps its own state on screen.
        if (visible) publish { if (phase == UpdatePhase.Idle || phase == UpdatePhase.Available || phase == UpdatePhase.Failed) copy(phase = UpdatePhase.Checking, failure = null) else this }
        try {
            fetchLatest(now, visible)?.let(::notifyRelease)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { if (visible) fail(checkFailure(error)) }
        finally { checking.unlock() }
    }

    /** Asks GitHub for its latest release (the stored ETag makes "nothing new" a free 304), vets and publishes it; returns it when it is newer than this build. */
    private suspend fun fetchLatest(now: Long, visible: Boolean): Release? {
        synchronized(lock) { prefs.edit().putLong("last_attempt", now).commit() }
        val installed = updater.installedVersion()
        // A 304 is only useful while the release it vouches for is still stored.
        val stored = Release.decode(prefs.getString("release", null))
        val found = releases.latest(prefs.getString("etag", null)?.takeIf { stored != null })
        val latest = when (found) {
            is LatestRelease.NotModified -> stored
            is LatestRelease.Found -> found.release
        }
        val vetted = latest?.let { vet(it) }
        val release = vetted?.takeIf { it.version > installed }
        synchronized(lock) {
            val edit = prefs.edit().putLong("last_check", now)
            if (found is LatestRelease.Found) {
                edit.putString("etag", found.etag.orEmpty())
                // Only what vet accepted is kept: a start-up offer and the permission-return install read this back unchecked.
                if (vetted != null) edit.putString("release", vetted.encode()) else edit.remove("release")
            }
            edit.commit()
        }
        publish { copy(latest = vetted, release = release, checkedAt = now, failure = null, phase = if (visible && (phase == UpdatePhase.Checking || phase == UpdatePhase.Failed)) UpdatePhase.Idle else phase) }
        if (release == null) synchronized(lock) { notifications.cancel(NOTIFICATION_ID); prefs.edit().remove("notification_token").commit() }
        return release
    }

    private fun checkFailure(error: Exception): UpdateFailure {
        val network = error is UpdateException && error.kind == UpdateException.Kind.Network
        return if (error is UpdateException && !network || error is IllegalArgumentException || error is IllegalStateException) UpdateFailure.CheckVerification else UpdateFailure.Check
    }

    /** Fails closed: returns the release for this build, null when it is for another package or older than one already seen, and throws when it is not trustworthy. */
    private fun vet(candidate: Release): Release? {
        if (candidate.packageName != app.packageName) return null
        try { newerRelease(candidate, app.packageName, updater.installedVersion(), updater.signer()) }
        catch (error: IllegalArgumentException) { throw UpdateException(UpdateException.Kind.Verification, error.message.orEmpty(), error) }
        synchronized(lock) {
            // Only the highest version's bytes are pinned, so an older one than that is not offered at all.
            if (olderThanSeen(prefs.getLong("seen_code", 0), candidate)) return null
            if (sameCodeDifferentBytes(prefs.getLong("seen_code", 0), prefs.getString("seen_sha", "").orEmpty(), candidate))
                throw UpdateException(UpdateException.Kind.Verification, "A published Beacon version cannot be replaced with different APK bytes.")
            if (candidate.version >= prefs.getLong("seen_code", 0)) prefs.edit().putLong("seen_code", candidate.version).putString("seen_sha", candidate.sha).commit()
        }
        return candidate
    }

    /** The stored release as the last check vetted it: this build's package, signer, version and the bytes first seen for that version. */
    private fun stillVerifies(release: Release) = runCatching { vet(release) }.getOrNull() != null

    private fun notifyRelease(release: Release) {
        if (!updateNotificationDue(release.version, prefs.getLong("notified_version", 0))) return
        if (postNotification(release)) prefs.edit().putLong("notified_version", release.version).commit()
    }

    /** A notification can be shown now: Android 13's permission, notifications on, and the update channel not muted. */
    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        ensureChannel()
        return notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun postNotification(release: Release): Boolean {
        if (!canNotify()) return false
        val token = UUID.randomUUID().toString().replace("-", "")
        val intent = Intent(app, BeaconActivity::class.java).setData(Uri.parse("hermes://update/$token"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_TOKEN, token)
        val pending = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        // Retain before posting so the immediately actionable handle already resolves.
        synchronized(lock) { check(prefs.edit().putString("notification_token", token).putLong("notification_version", release.version).commit()) }
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle("Beacon ${release.name} is ready").setContentText("Tap to update.")
            .setContentIntent(pending).addAction(0, "Update", pending).setAutoCancel(true).build())
        return true
    }

    /** The plugin row of the update screen: read what Hermes runs. [absent] is the sign-in's own word that the plugin is not installed. */
    internal fun refreshPlugin(client: BeaconClient?, absent: Boolean) {
        scope.launch {
            if (job?.isActive == true) return@launch
            pluginResult = null
            if (client == null || absent || !selfUpdates) { probe = PluginProbe.None; publish { this }; return@launch }
            probe = PluginProbe.Checking
            publish { this }
            val detached = client.detached()
            try { probe = probePlugin(HermesPluginUpdater(detached)) }
            finally { detached.closeNative(); if (probe == PluginProbe.Checking) probe = PluginProbe.None; publish { this } }
        }
    }

    /**
     * The Update button: asks GitHub for the newest release, then the plugin first when Hermes has one to update, then download,
     * check and hand the app to Android. [refresh] is false only for the install that waited on Android's permission page.
     */
    internal fun startUpdate(client: BeaconClient?, pluginAbsent: Boolean = false, refresh: Boolean = true) {
        if (!selfUpdates) return
        val detached = client?.detached()
        synchronized(lock) {
            if (job?.isActive == true) { detached?.closeNative(); return }
            pluginResult = null
            job = scope.launch { try { runUpdate(detached, pluginAbsent, refresh) } finally { detached?.closeNative() } }
        }
    }

    /** A notification tap: only the notification this phone posted last may start an update. */
    internal fun updateFromNotification(token: String, client: BeaconClient?) {
        val ours = synchronized(lock) { validUpdateToken(token) && token == prefs.getString("notification_token", null) }
        if (!ours) { checkNow(); return }
        startUpdate(client)
    }

    internal fun cancel() { job?.cancel() }

    /** A verified download is waiting for the user's Update, or for Android's prompt. */
    fun hasPendingInstall(): Boolean = synchronized(lock) {
        val name = prefs.getString("pending_file", null) ?: return false
        runCatching { pendingFile(name).isFile }.getOrDefault(false)
    }

    /** Beacon is back on screen: finish an install that waited for Android's permission, or check if due. */
    internal fun resume(onUpdateScreen: Boolean = false) {
        // Android may restart Beacon when the permission changes, so the waiting install is kept on disk, not only in state.
        val waiting = _state.value.phase == UpdatePhase.NeedsPermission || prefs.getBoolean("install_after_permission", false)
        if (waiting && app.packageManager.canRequestPackageInstalls()) {
            synchronized(lock) { prefs.edit().remove("install_after_permission").commit() }
            startUpdate(null, refresh = false)
        } else checkIfDue(onUpdateScreen)
    }

    /** "Review changes" accepted: runs the plugin update once more with the owner's consent. */
    internal fun reviewPlugin(client: BeaconClient?) {
        val detached = client?.detached() ?: return
        scope.launch { try { pluginStep(detached, accept = true) } finally { detached.closeNative() } }
    }

    internal fun dismissUpdated() {
        synchronized(lock) { prefs.edit().remove("updated_name").remove("updated_notes").commit() }
        publish { copy(updated = null) }
    }

    private suspend fun runUpdate(client: BeaconClient?, pluginAbsent: Boolean, refresh: Boolean) {
        if (callActive()) return fail(UpdateFailure.Call)
        // Coming back from Android's permission page the verified file is already here: no download to show.
        if (_state.value.phase != UpdatePhase.NeedsPermission) publish { copy(phase = UpdatePhase.Downloading, downloaded = 0, failure = null) }
        try {
            // The newest release goes first, so the plugin step and the download below are for it and not for the last check's answer.
            val release = try {
                if (refresh) releaseForUpdate(_state.value.release, { checking.withLock { fetchLatest(System.currentTimeMillis(), visible = false) } }, ::stillVerifies)
                else _state.value.release
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { return fail(checkFailure(error)) }
            if (client != null && !pluginAbsent && selfUpdates) {
                probe = probePlugin(HermesPluginUpdater(client))
                publish { this }
                if (_state.value.plugin.status == PluginStatus.Available) pluginStep(client, accept = false)
            }
            if (release == null) { publish { copy(phase = UpdatePhase.Idle) }; return }
            install(pendingFor(release) ?: download(release))
        } catch (error: CancellationException) {
            publish { copy(phase = UpdatePhase.Idle, downloaded = 0) }
            throw error
        } catch (error: UpdateException) {
            fail(if (error.kind == UpdateException.Kind.Verification) UpdateFailure.Verification else UpdateFailure.Download)
        } catch (error: Exception) {
            fail(UpdateFailure.Download)
        }
    }

    /** Hermes' own updater; whatever it answers lands on the plugin row, and the app update goes on. */
    private suspend fun pluginStep(client: BeaconClient, accept: Boolean) {
        val target = _state.value.latest?.pluginVersion.orEmpty()
        pluginResult = PluginRow(PluginStatus.Updating, _state.value.plugin.installed, target)
        publish { this }
        val row = runPluginUpdate(HermesPluginUpdater(client), target, accept)
        pluginResult = row.takeIf { it.status != PluginStatus.Hidden }
        if (row.status == PluginStatus.Hidden) probe = PluginProbe.None
        publish { this }
    }

    private fun pendingFile(name: String): File {
        require(name == File(name).name && name.endsWith(".apk")) { "Invalid retained update" }
        return File(File(app.filesDir, "updates"), name).also {
            require(it.canonicalFile.parentFile == File(app.filesDir, "updates").canonicalFile) { "Invalid retained update" }
        }
    }

    private fun clearPending() {
        val obsolete = prefs.getString("pending_file", null)?.let { runCatching { pendingFile(it) }.getOrNull() }
        prefs.edit().remove("pending_file").remove("pending_sha").remove("pending_size").remove("pending_version")
            .remove("pending_version_name").remove("pending_notes").remove("pending_session_id").remove("install_after_permission").commit()
        obsolete?.delete()
    }

    /** A verified download kept from an earlier tap (a cancelled Android prompt, or a permission detour), if it is still exactly this release. */
    private fun pendingFor(release: Release): File? = synchronized(lock) {
        val name = prefs.getString("pending_file", null) ?: return null
        val file = runCatching { pendingFile(name) }.getOrNull()
        if (file != null && file.isFile && prefs.getLong("pending_version", 0) == release.version && file.length() == release.size && sha256(file).equals(release.sha, true)) file
        else { clearPending(); null }
    }

    private suspend fun download(release: Release): File {
        val dir = File(app.filesDir, "updates").apply { mkdirs() }
        prunePartialUpdates(dir)
        val part = File(dir, "${UUID.randomUUID()}.part")
        var lastPublished = 0L
        try {
            releases.download(release, part) { bytes ->
                val now = System.currentTimeMillis()
                if (bytes == release.size || now - lastPublished >= 120) { lastPublished = now; _state.update { it.copy(downloaded = bytes) } }
            }
            updater.verifyDownloaded(part, release)
            currentCoroutineContext().ensureActive()
            val artifact = File(dir, "Beacon-${release.version}-${release.sha.take(16)}.apk")
            artifact.delete()
            check(part.renameTo(artifact)) { "Could not retain the verified update" }
            synchronized(lock) {
                dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") && it != artifact }?.forEach { it.delete() }
                check(prefs.edit().putString("pending_file", artifact.name).putString("pending_sha", release.sha).putLong("pending_size", release.size)
                    .putLong("pending_version", release.version).putString("pending_version_name", release.name).putString("pending_notes", release.notes)
                    .remove("pending_session_id").commit())
            }
            return artifact
        } finally { part.delete() }
    }

    private fun install(file: File) {
        if (callActive()) return fail(UpdateFailure.Call)
        if (!app.packageManager.canRequestPackageInstalls()) {
            synchronized(lock) { prefs.edit().putBoolean("install_after_permission", true).commit() }
            publish { copy(phase = UpdatePhase.NeedsPermission, failure = null) }
            // Signed out there is no update page with Allow; the tap that started this opens Android's page, and resume() installs on return.
            // Off screen it cannot open, so the notice comes back and its tap reuses the verified download.
            if (onScreen()) runCatching { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            else _state.value.release?.let(::postNotification)
            return
        }
        publish { copy(phase = UpdatePhase.Installing, failure = null) }
        try {
            updater.installSession(file, ::resultIntent) { id -> synchronized(lock) { prefs.edit().putInt("pending_session_id", id).commit() } }
        } catch (error: IllegalArgumentException) {
            synchronized(lock) { clearPending() }
            fail(UpdateFailure.Verification)
        } catch (error: Exception) {
            synchronized(lock) { prefs.edit().remove("pending_session_id").commit() }
            fail(UpdateFailure.Install)
        }
    }

    private fun resultIntent(sessionId: Int): PendingIntent {
        val intent = Intent(app, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
        return PendingIntent.getBroadcast(app, sessionId, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    /** Android's answer to the install session: its confirmation prompt, the user's yes or no, or a failure. */
    internal fun handleInstallResult(result: Intent) {
        val sessionId = result.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        synchronized(lock) { if (sessionId < 0 || prefs.getInt("pending_session_id", -1) != sessionId) return }
        fun forgetSession() = synchronized(lock) { prefs.edit().remove("pending_session_id").commit() }
        fun giveUp(failure: UpdateFailure) { runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }; forgetSession(); fail(failure) }
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_SUCCESS -> { notifications.cancel(NOTIFICATION_ID); restore() }
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val prompt = installPrompt(result)
                when {
                    prompt == null -> giveUp(UpdateFailure.Install)
                    callActive() -> giveUp(UpdateFailure.Call)
                    onScreen() -> app.startActivity(prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // In the background with notifications off nothing can show Android's prompt; do not wait on "Installing…".
                    canNotify() -> postInstallerPrompt(sessionId, prompt)
                    else -> giveUp(UpdateFailure.Install)
                }
            }
            // The user said no in Android's prompt: the verified file stays, Update offers it again.
            PackageInstaller.STATUS_FAILURE_ABORTED -> { forgetSession(); publish { copy(phase = UpdatePhase.Idle) } }
            else -> { forgetSession(); fail(UpdateFailure.Install) }
        }
    }

    @Suppress("DEPRECATION")
    private fun installPrompt(result: Intent): Intent? = if (Build.VERSION.SDK_INT >= 33)
        result.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else result.getParcelableExtra(Intent.EXTRA_INTENT)

    private fun postInstallerPrompt(sessionId: Int, prompt: Intent) {
        val pending = PendingIntent.getActivity(app, sessionId, prompt.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle("Beacon ${prefs.getString("pending_version_name", "").orEmpty()} is ready").setContentText("Tap to confirm the update in Android.")
            .setContentIntent(pending).addAction(0, "Install", pending).setAutoCancel(true).build())
    }

    companion object {
        const val EXTRA_TOKEN = "update_token"
        const val CHANNEL = "hermes-updates"
        const val ACTION_INSTALL_RESULT = "dev.hermes.android.updates.INSTALL_RESULT"
        private const val JOB_ID = 4202
        private const val NOTIFICATION_ID = 4202
        private const val FORMAT = "format"
        private const val FORMAT_VERSION = 2
        fun validToken(token: String): Boolean = validUpdateToken(token)
    }
}
