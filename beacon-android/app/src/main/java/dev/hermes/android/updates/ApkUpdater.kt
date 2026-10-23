package dev.hermes.android.updates

import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

internal fun prunePartialUpdates(dir: File, now: Long = System.currentTimeMillis()) {
    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < now - 3_600_000 }?.forEach { it.delete() }
}

class ApkUpdater(private val context: Context) {
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun installedVersion(): Long = installed().longVersionCode
    private fun signer(): String {
        val certificate = installed().signingInfo?.apkContentsSigners?.singleOrNull()
            ?: error("This installation has no supported single signing identity. Keep the installed app and contact its owner.")
        return sha256(certificate.toByteArray())
    }
    suspend fun check(client: BeaconClient): Release? {
        // Stable and production builds have separate release feeds on Hermes.
        val release = Release.from(client.nativeRequest("/api/plugins/beacon/mobile/releases/latest", params = mapOf("package" to context.packageName)))
        return newerRelease(release, context.packageName, installed().longVersionCode, signer())
    }
    suspend fun download(client: BeaconClient, release: Release): File = withContext(Dispatchers.IO) {
        release.validate(context.packageName, installed().longVersionCode, signer())
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        prunePartialUpdates(dir)
        val part = File(dir, "${client.scope.key}-${java.util.UUID.randomUUID()}.part")
        try {
            val size = part.outputStream().use { client.copyTo(safeArtifactPath(release.path), it, query = mapOf("package" to context.packageName), limit = release.size) }
            require(size == release.size && sha256(part).equals(release.sha, true)) { "Update checksum or size failed. The installed app is unchanged." }
            val parsed = context.packageManager.getPackageArchiveInfo(part.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("Update is not a valid APK")
            require(parsed.packageName == context.packageName && parsed.longVersionCode == release.version) { "APK package or version differs from the release manifest" }
            val certificate = parsed.signingInfo?.apkContentsSigners?.singleOrNull()?.toByteArray() ?: error("APK signing identity is missing")
            require(sha256(certificate).equals(release.signer, true) && sha256(certificate).equals(signer(), true)) { "APK signature differs from the installed app" }
            val artifact = File(dir, "hermes-${client.scope.key}-${release.version}-${release.sha.take(16)}.apk")
            currentCoroutineContext().ensureActive()
            check(part.renameTo(artifact)) { "Could not retain verified update" }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".apk") && it != artifact }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            artifact
        } finally { part.delete() }
    }
    internal fun validateInstall(file: File) {
        require(file.canonicalFile.parentFile == File(context.filesDir, "updates").canonicalFile) { "Reopen the verified app update" }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES) ?: error("The verified update is no longer available")
        require(archive.packageName == context.packageName && archive.longVersionCode > installed().longVersionCode && archive.signingInfo?.apkContentsSigners?.singleOrNull()?.let { sha256(it.toByteArray()).equals(signer(), true) } == true) { "The APK no longer matches the installed package, newer version or signing identity" }
    }
    /** Best-effort no-tap self-update; Android can still return PENDING_USER_ACTION. */
    internal fun installWithoutUserAction(file: File, pendingIntent: (Int) -> PendingIntent, beforeCommit: (Int) -> Unit) {
        validateInstall(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                beforeCommit(sessionId)
                session.commit(pendingIntent(sessionId).intentSender)
                committed = true
            }
        } catch (error: Throwable) {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }
    /** Call only after validating private bytes and rechecking local authorization. */
    internal fun launchInstaller(file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return false }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: android.content.ActivityNotFoundException) {
            throw IllegalStateException("Android's package installer is unavailable. The verified update is retained for a later retry.", error)
        }
        return true
    }
}
