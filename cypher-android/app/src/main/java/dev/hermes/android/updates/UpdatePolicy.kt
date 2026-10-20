package dev.hermes.android.updates

import dev.hermes.android.core.Release
import dev.hermes.android.data.AuthContext

/** Validate trust even when the server's latest release is already installed. */
fun newerRelease(release: Release, packageName: String, installedVersion: Long, signer: String): Release? {
    require(release.version > 0) { "Invalid release version" }
    require(release.name.isNotBlank() && release.name.length <= 100 && release.name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Invalid release name" }
    release.validate(packageName, minOf(installedVersion, release.version - 1), signer)
    return release.takeIf { it.version > installedVersion }
}

internal data class UpdateSource(val origin: String, val owner: String, val id: String) {
    fun authorizedBy(auth: AuthContext): Boolean = auth.backend == "hermes-dashboard" && owner.isNotBlank() && origin == auth.origin && owner == auth.owner
}

internal fun updateCheckDue(lastCheck: Long, now: Long, force: Boolean): Boolean =
    force || lastCheck == 0L || now < lastCheck || now - lastCheck >= 12 * 60 * 60_000L

internal fun autoUpdateEnabled(stored: Boolean?): Boolean = stored ?: true

internal fun autoUpdateCheckDue(enabled: Boolean, lastCheck: Long, now: Long): Boolean =
    enabled && updateCheckDue(lastCheck, now, false)

internal fun autoUpdateInstallAllowed(enabled: Boolean, unmetered: Boolean, callActive: Boolean): Boolean =
    enabled && unmetered && !callActive

internal fun updateNotificationDue(version: Long, lastNotified: Long): Boolean = version > lastNotified

internal fun validUpdateToken(token: String): Boolean = token.matches(Regex("[a-f0-9]{32}"))

/** An availability hint can suppress old notices; it never becomes install metadata. */
internal fun pushReleaseVersion(value: String?, installed: Long, verifiedNotified: Long): Long? =
    value?.takeIf { it.matches(Regex("[1-9][0-9]{0,18}")) }?.toLongOrNull()
        ?.takeIf { it > installed && updateNotificationDue(it, verifiedNotified) }
