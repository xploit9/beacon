package dev.hermes.android.updates

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.hermes.android.core.Release
import dev.hermes.android.core.sha256
import java.io.File

internal fun prunePartialUpdates(dir: File, now: Long = System.currentTimeMillis()) {
    dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") && it.lastModified() < now - 3_600_000 }?.forEach { it.delete() }
}

/** What an APK file says about itself, read from the archive and not from anything GitHub told us. */
internal data class ArchiveFacts(val packageName: String, val version: Long, val signer: String?)

/** Why this archive may not replace the installed app, or null when it may. [release] adds the release metadata's claims. */
internal fun archiveRefusal(archive: ArchiveFacts?, packageName: String, installedVersion: Long, installedSigner: String, release: Release? = null): String? = when {
    archive == null -> "Update is not a valid APK"
    archive.packageName != packageName -> "The APK belongs to another app"
    archive.version <= installedVersion -> "The APK is not newer than the installed app"
    release != null && archive.version != release.version -> "APK version differs from the release manifest"
    archive.signer == null -> "APK signing identity is missing"
    !archive.signer.equals(installedSigner, true) -> "APK signature differs from the installed app"
    release != null && !archive.signer.equals(release.signer, true) -> "APK signature differs from the release manifest"
    else -> null
}

class ApkUpdater(private val context: Context) {
    private fun installed() = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    fun installedVersion(): Long = installed().longVersionCode
    internal fun signer(): String {
        val certificate = installed().signingInfo?.apkContentsSigners?.singleOrNull()
            ?: error("This installation has no supported single signing identity. Keep the installed app and contact its owner.")
        return sha256(certificate.toByteArray())
    }
    private fun facts(file: File): ArchiveFacts? = context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)?.let { info ->
        ArchiveFacts(info.packageName.orEmpty(), info.longVersionCode, info.signingInfo?.apkContentsSigners?.singleOrNull()?.let { sha256(it.toByteArray()) })
    }
    /** The downloaded bytes must be this app's package, the announced version, and signed by the installed app's own signer. */
    internal fun verifyDownloaded(file: File, release: Release) {
        archiveRefusal(facts(file), context.packageName, installedVersion(), signer(), release)?.let { throw UpdateException(UpdateException.Kind.Verification, it) }
    }
    /** Run again right before install: the retained file must still be a newer build of this app from the same signer. */
    internal fun validateInstall(file: File) {
        require(file.canonicalFile.parentFile == File(context.filesDir, "updates").canonicalFile) { "Reopen the verified app update" }
        archiveRefusal(facts(file), context.packageName, installedVersion(), signer())?.let { throw IllegalArgumentException(it) }
    }
    /** Hands the verified APK to Android's installer; Android asks the user to confirm and reports back through [result]. */
    internal fun installSession(file: File, result: (Int) -> PendingIntent, beforeCommit: (Int) -> Unit) {
        validateInstall(file)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
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
                session.commit(result(sessionId).intentSender)
                committed = true
            }
        } catch (error: Throwable) {
            if (!committed) runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }
}
