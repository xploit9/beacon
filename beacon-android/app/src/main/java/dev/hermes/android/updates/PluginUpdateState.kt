package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class PluginVersions(val running: String, val installed: String) {
    val restartRequired: Boolean get() = running.isNotBlank() && installed.isNotBlank() && running != installed
}

internal enum class PluginStatus { Hidden, Checking, UpToDate, Available, Updating, Updated, NeedsReview, Restart, Busy, Failed }

/** The "Hermes plugin" row of the update screen. [target] is the plugin version the latest release ships with. */
internal data class PluginRow(val status: PluginStatus = PluginStatus.Hidden, val installed: String = "", val target: String = "", val review: List<String> = emptyList()) {
    /** Shown beside the version, or as the row's only action when it needs the owner's review. */
    val text: String get() = when (status) {
        PluginStatus.Checking -> "Checking…"
        PluginStatus.UpToDate -> "Up to date"
        PluginStatus.Available -> "Ready to update"
        PluginStatus.Updating -> "Updating…"
        PluginStatus.Updated -> "Updated"
        PluginStatus.NeedsReview -> "Review changes"
        PluginStatus.Restart -> "Restart Hermes on your PC to finish."
        PluginStatus.Busy -> "Hermes is busy. Try again later."
        PluginStatus.Failed -> "Couldn't update. Try again."
        PluginStatus.Hidden -> ""
    }
    val actionable: Boolean get() = status in setOf(PluginStatus.Available, PluginStatus.NeedsReview, PluginStatus.Busy, PluginStatus.Failed, PluginStatus.Restart)
}

/** Every status text the plugin row shows through uiText. */
internal val PluginRowStrings = PluginStatus.entries.map { PluginRow(it).text }.filter(String::isNotBlank)

/** What the phone learned about the plugin on Hermes. */
internal sealed interface PluginProbe {
    /** No Hermes session, no plugin, or not the owner: nothing to update. */
    data object None : PluginProbe
    data object Checking : PluginProbe
    /** An older plugin without the version route: installed, but old. */
    data object Unreadable : PluginProbe
    data object Failed : PluginProbe
    data class Read(val versions: PluginVersions) : PluginProbe
}

internal fun pluginStatusFor(versions: PluginVersions, target: String): PluginStatus = when {
    !olderVersion(versions.running, target) && !olderVersion(versions.installed, target) -> PluginStatus.UpToDate
    versions.restartRequired -> PluginStatus.Restart
    else -> PluginStatus.Available
}

/** A failed check hides the row: there is nothing to say about a plugin the phone could not reach. */
internal fun pluginRowFor(probe: PluginProbe, target: String): PluginRow = when {
    target.isBlank() -> PluginRow()
    probe is PluginProbe.Checking -> PluginRow(PluginStatus.Checking, "", target)
    probe is PluginProbe.Unreadable -> PluginRow(PluginStatus.Available, "", target)
    probe is PluginProbe.Read -> PluginRow(pluginStatusFor(probe.versions, target), probe.versions.installed, target)
    else -> PluginRow()
}

internal interface PluginEndpoint {
    suspend fun version(): PluginVersions
    suspend fun update(acceptCapabilities: Boolean): JSONObject
}

/** Reads the plugin version; a 404 on the version route is an older plugin, a 403 means this account is not the owner. */
internal suspend fun probePlugin(endpoint: PluginEndpoint): PluginProbe = try {
    PluginProbe.Read(endpoint.version())
} catch (error: CancellationException) { throw error }
catch (error: Exception) {
    when {
        error is ApiFailure && error.status == 403 -> PluginProbe.None
        error is ApiFailure && error.status == 404 -> PluginProbe.Unreadable
        else -> PluginProbe.Failed
    }
}

/**
 * Runs Hermes' own updater once and says how the plugin row ends. It never throws (so the app update that follows
 * is never blocked) and never installs a plugin Hermes asked the owner to review.
 */
internal suspend fun runPluginUpdate(endpoint: PluginEndpoint, target: String, accept: Boolean, pause: suspend (Long) -> Unit = { delay(it) }): PluginRow {
    var before: PluginVersions? = null
    try {
        before = runCatching { endpoint.version() }.getOrNull()
        val current = before
        if (current != null && current.restartRequired) return PluginRow(PluginStatus.Restart, current.installed, target)
        val response = endpoint.update(accept)
        if (response.optBoolean("scan_blocked")) return PluginRow(PluginStatus.NeedsReview, before?.installed.orEmpty(), target)
        if (response.optBoolean("consent_required")) {
            val lines = response.optJSONArray("delta_lines")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optString(it).takeIf { line -> line.isNotBlank() && line != "null" } } }.orEmpty()
            return PluginRow(PluginStatus.NeedsReview, before?.installed.orEmpty(), target, lines)
        }
        if (!response.optBoolean("ok")) return PluginRow(PluginStatus.Failed, before?.installed.orEmpty(), target)
        var latest = runCatching { endpoint.version() }.getOrNull()
        if (!response.optBoolean("unchanged")) {
            // Hermes reports the new version a moment after the update call returns.
            for (attempt in 0 until 10) {
                if (latest != null && latest != before) break
                pause(1000)
                latest = runCatching { endpoint.version() }.getOrNull() ?: latest
            }
        }
        val done = latest ?: return PluginRow(PluginStatus.Failed, before?.installed.orEmpty(), target)
        return PluginRow(when {
            response.optBoolean("restart_required") || done.restartRequired -> PluginStatus.Restart
            olderVersion(done.installed, target) -> PluginStatus.Failed
            else -> PluginStatus.Updated
        }, done.installed, target)
    } catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        val installed = before?.installed.orEmpty()
        return when {
            error is ApiFailure && (error.status == 403 || error.status == 404) -> PluginRow()
            error is ApiFailure && error.status in setOf(409, 423, 429) -> PluginRow(PluginStatus.Busy, installed, target)
            else -> PluginRow(PluginStatus.Failed, installed, target)
        }
    }
}
