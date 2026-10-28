package dev.hermes.android.updates

import android.app.ActivityManager
import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.core.Release
import java.util.Locale

/** Validate trust even when the latest release is already installed. */
fun newerRelease(release: Release, packageName: String, installedVersion: Long, signer: String): Release? {
    require(release.version > 0) { "Invalid release version" }
    require(release.name.isNotBlank() && release.name.length <= 100 && release.name.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Invalid release name" }
    release.validate(packageName, minOf(installedVersion, release.version - 1), signer)
    return release.takeIf { it.version > installedVersion }
}

/** A published version keeps its bytes: the same code with another checksum is a replaced release, never an update. */
internal fun sameCodeDifferentBytes(seenCode: Long, seenSha: String, release: Release): Boolean =
    seenCode != 0L && release.version == seenCode && !release.sha.equals(seenSha, true)

internal const val UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60_000L
internal const val UPDATE_OFFER_REFRESH_MS = 30 * 60_000L
private const val UPDATE_RETRY_MS = 15 * 60_000L

/** A check is due every [window] (6 hours); a failed attempt waits 15 minutes. A manual check is always due. */
internal fun updateCheckDue(lastCheck: Long, now: Long, force: Boolean, lastAttempt: Long = 0, window: Long = UPDATE_CHECK_INTERVAL_MS): Boolean {
    fun aged(last: Long, span: Long) = last == 0L || now < last || now - last >= span
    return force || aged(lastCheck, window) && aged(lastAttempt, UPDATE_RETRY_MS)
}

/** Opening the update screen on an offer refreshes it after 30 minutes; every other check keeps the 6-hour schedule. */
internal fun checkWindow(offered: Boolean, onUpdateScreen: Boolean): Long = if (offered && onUpdateScreen) UPDATE_OFFER_REFRESH_MS else UPDATE_CHECK_INTERVAL_MS

/**
 * The release an Update tap goes on with: what [ask] (GitHub's latest, vetted; null when nothing is newer) answers now, never the
 * release found at the last check. Only when GitHub cannot be reached does the [stored] one stand in, and only while it
 * [stillVerifies]; every other failure, and an unreachable GitHub with nothing verified stored, is rethrown for the check failure.
 */
internal suspend fun releaseForUpdate(stored: Release?, ask: suspend () -> Release?, stillVerifies: (Release) -> Boolean): Release? =
    try { ask() } catch (error: UpdateException) {
        if (error.kind == UpdateException.Kind.Network && stored != null && stillVerifies(stored)) stored else throw error
    }

internal fun updateNotificationDue(version: Long, lastNotified: Long): Boolean = version > lastNotified

internal fun validUpdateToken(token: String): Boolean = token.matches(Regex("[a-f0-9]{32}"))

/** On screen, not merely running: Keep connected's foreground service alone is not on screen. */
internal fun beaconVisible(importance: Int): Boolean =
    importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE && importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE

private fun versionTriple(value: String): List<Int>? = PLUGIN_VERSION_PATTERN.matchEntire(value.trim())?.groupValues?.drop(1)?.map(String::toInt)

/** Plain major.minor.patch ordering; anything else is never "older", so an odd version is never offered. */
internal fun olderVersion(current: String, target: String): Boolean {
    val have = versionTriple(current) ?: return false
    val want = versionTriple(target) ?: return false
    for (part in 0..2) if (have[part] != want[part]) return have[part] < want[part]
    return false
}

private fun megabytes(bytes: Long, decimals: Boolean): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (decimals) String.format(Locale.ROOT, "%.1f", mb) else Math.round(mb).toString()
}

/** "31 MB"; one decimal under 10 MB. */
internal fun formatSize(bytes: Long): String =
    if (bytes < 1024 * 1024 * 95 / 100) "<1 MB" else "${megabytes(bytes, bytes < 10L * 1024 * 1024)} MB"

internal fun progressPercent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

/** "42% · 13 of 31 MB" */
internal fun progressLine(done: Long, total: Long): String =
    "${progressPercent(done, total)}% · ${megabytes(done, total < 10L * 1024 * 1024)} of ${formatSize(total)}"

/** "just now", "5 min ago", "3 h ago", "2 d ago"; blank when never checked. The screen passes the words in the phone's language. */
internal fun checkedAgo(lastCheck: Long, now: Long, justNow: String = "just now", minutesAgo: String = "{0} min ago",
                        hoursAgo: String = "{0} h ago", daysAgo: String = "{0} d ago"): String {
    if (lastCheck <= 0) return ""
    val minutes = ((now - lastCheck) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> justNow
        minutes < 60 -> minutesAgo.replace("{0}", "$minutes")
        minutes < 24 * 60 -> hoursAgo.replace("{0}", "${minutes / 60}")
        else -> daysAgo.replace("{0}", "${minutes / (24 * 60)}")
    }
}
