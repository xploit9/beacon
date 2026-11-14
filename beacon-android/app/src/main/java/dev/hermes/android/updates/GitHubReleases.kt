package dev.hermes.android.updates

// Release selection follows Hermes Relay's sideload UpdateChecker (MIT; see licenses/Hermes-Relay-MIT.txt): GitHub's
// releases API with an Accept and User-Agent header, drafts and prereleases ignored, assets matched by name. Relay hands the
// APK to the browser; Beacon downloads it itself and checks it against the release metadata before anything is installed.

import dev.hermes.android.core.MAX_UPDATE_APK_BYTES
import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.core.Release
import dev.hermes.android.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** [kind] separates "GitHub or the connection failed" from "what GitHub served is not an acceptable update"; [Busy] is GitHub's rate limit (403 or 429). */
internal class UpdateException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { Network, Verification, Busy }
}

/** The plugin version of the latest release and the commit its tag names. */
internal data class PublishedCommit(val version: String, val commit: String)

internal sealed interface LatestRelease {
    data object NotModified : LatestRelease
    /** [release] is null when GitHub has no complete Beacon release to offer. */
    data class Found(val release: Release?, val etag: String?) : LatestRelease
}

private const val API_LIMIT = 256 * 1024
private const val METADATA_LIMIT = 16 * 1024
private const val NOTES_LIMIT = 8 * 1024
private const val MAX_REDIRECTS = 5
private const val COMMIT_LIMIT = 128
private val TAG_NAME = Regex("v?[0-9][A-Za-z0-9._-]{0,62}")
/** A full commit id, as Hermes' installer takes it for `ref`: 40 lowercase hex digits and nothing else. */
internal val COMMIT_SHA = Regex("[0-9a-f]{40}")
private val METADATA_NAME = Regex("Beacon-([A-Za-z0-9][A-Za-z0-9._-]{0,63})\\.json")
private val VERSION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val STORED_SHA = Regex("[a-f0-9]{64}")
private val SIGNER_SHA = Regex("[a-fA-F0-9]{64}")

/**
 * The public latest release of [repo]: no token, no Hermes. Tests move [apiBase], [repo] and the host rules; the app uses the defaults.
 * Every request and every redirect hop must stay on an allowed host over HTTPS.
 */
internal class GitHubReleases(
    http: OkHttpClient = OkHttpClient(),
    private val apiBase: String = "https://api.github.com",
    private val repo: String = "xploit9/beacon",
    private val userAgent: String = "Beacon-Android",
    private val assetHosts: Set<String> = setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"),
    private val secureOnly: Boolean = true,
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun permitted(url: HttpUrl, hosts: Set<String>) = (url.isHttps || !secureOnly) && url.host in hosts && url.username.isEmpty() && url.password.isEmpty()
    private fun verification(message: String) = UpdateException(UpdateException.Kind.Verification, message)
    private fun network(message: String, cause: Throwable? = null) = UpdateException(UpdateException.Kind.Network, message, cause)
    /** GitHub's own "not now" for an unauthenticated address (403, or 429 for its secondary limit) is not "no release" or "no connection". */
    private fun refused(code: Int) = if (code == 403 || code == 429) UpdateException(UpdateException.Kind.Busy, "GitHub answered $code") else network("GitHub answered $code")

    /** [etag] is the one stored from the last answer; GitHub replies 304 while nothing was published since. */
    suspend fun latest(etag: String?): LatestRelease {
        val api = "$apiBase/repos/$repo/releases/latest".toHttpUrl()
        val request = Request.Builder().url(api).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", userAgent)
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }.build()
        val (code, answerEtag, body) = fetch(request, setOf(api.host)) { response ->
            Triple(response.code, response.header("ETag"), if (response.code == 200) response.bounded(API_LIMIT) else ByteArray(0))
        }
        return when (code) {
            304 -> LatestRelease.NotModified
            404 -> LatestRelease.Found(null, null)
            200 -> LatestRelease.Found(try { release(JSONObject(String(body, Charsets.UTF_8))) } catch (error: JSONException) { throw verification("GitHub answered with an unreadable release") }, answerEtag)
            else -> throw refused(code)
        }
    }

    /**
     * The commit the latest release was built from, for the plugin install that pins it: latest release, its tag, the commit that tag names
     * (asked as a bare SHA). Fails closed: no release, a draft or prerelease, a tag that is not a plugin version, or an answer that is not
     * exactly 40 lowercase hex digits is an [UpdateException] and nothing is installed. [expectedVersion] ties the commit to the release an
     * update is for, so a release published meanwhile is never installed under the old one's name.
     */
    suspend fun latestCommit(expectedVersion: String? = null): PublishedCommit {
        val api = "$apiBase/repos/$repo/releases/latest".toHttpUrl()
        val latest = Request.Builder().url(api).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", userAgent).build()
        val body = fetch(latest, setOf(api.host)) { response ->
            when (response.code) {
                200 -> response.bounded(API_LIMIT)
                404 -> throw verification("GitHub has no Beacon release")
                else -> throw refused(response.code)
            }
        }
        val json = try { JSONObject(String(body, Charsets.UTF_8)) } catch (error: JSONException) { throw verification("GitHub answered with an unreadable release") }
        val tag = json.string("tag_name")
        val version = tag.removePrefix("v")
        if (json.optBoolean("draft") || json.optBoolean("prerelease") || !TAG_NAME.matches(tag) || !PLUGIN_VERSION_PATTERN.matches(version))
            throw verification("The latest release has no usable tag")
        if (expectedVersion != null && version != expectedVersion) throw verification("The latest release is not the one being installed")
        return PublishedCommit(version, commitOf(tag))
    }

    /** The commit [tag] names, asked as a bare SHA and accepted only as exactly 40 lowercase hex digits. */
    private suspend fun commitOf(tag: String): String {
        val commits = "$apiBase/repos/$repo/commits".toHttpUrl().newBuilder().addPathSegment(tag).build()
        val ask = Request.Builder().url(commits).header("Accept", "application/vnd.github.sha").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", userAgent).build()
        val sha = fetch(ask, setOf(commits.host)) { response ->
            if (response.code != 200) throw if (response.code == 404 || response.code == 422) verification("The release tag names no commit") else refused(response.code)
            String(response.bounded(COMMIT_LIMIT), Charsets.UTF_8).trim()
        }
        if (!COMMIT_SHA.matches(sha)) throw verification("GitHub answered with something that is not a commit")
        return sha
    }

    /**
     * GitHub's latest-release document as the owner's Hermes read and passed it on: read exactly like GitHub's own answer, so its
     * metadata and APK still come from GitHub's hosts and pass every check below. Null for a draft, a prerelease or no Beacon assets.
     */
    suspend fun relayed(json: JSONObject): Release? = release(json)

    /** The release this document describes, with the commit its tag names: found here, once, so an Update or Repair tap needs no GitHub call of its own. */
    private suspend fun release(json: JSONObject): Release? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val assets = json.optJSONArray("assets")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }.orEmpty()
            .filter { it.string("state", "uploaded") == "uploaded" }
        val metadataAssets = assets.filter { METADATA_NAME.matches(it.string("name")) }
        if (metadataAssets.isEmpty()) return null
        if (metadataAssets.size > 1) throw verification("The release has more than one metadata file")
        val metadataAsset = metadataAssets.single()
        val versionName = METADATA_NAME.matchEntire(metadataAsset.string("name"))!!.groupValues[1]
        val apk = assets.firstOrNull { it.string("name") == "Beacon-$versionName.apk" } ?: return null
        val metadataBytes = fetch(Request.Builder().url(assetUrl(metadataAsset)).header("User-Agent", userAgent).build(), assetHosts) { response ->
            if (response.code != 200) throw network("GitHub answered ${response.code}")
            response.bounded(METADATA_LIMIT)
        }
        val metadata = try { JSONObject(String(metadataBytes, Charsets.UTF_8)) } catch (error: JSONException) { throw verification("The release metadata is unreadable") }
        checkMetadata(metadata, versionName, apk.optLong("size"))
        // One release is one version: its tag has to name that version, or the commit it names is not the plugin this release ships.
        val tag = json.string("tag_name")
        if (!TAG_NAME.matches(tag) || tag.removePrefix("v") != versionName) throw verification("The release tag does not match its version")
        val commit = commitOf(tag)
        val notes = json.string("body").replace("\r\n", "\n").take(NOTES_LIMIT).trim()
        return Release.from(metadata, assetUrl(apk).toString(), notes, json.string("published_at"), commit)
    }

    private fun assetUrl(asset: JSONObject): HttpUrl =
        asset.string("browser_download_url").toHttpUrlOrNull()?.takeIf { permitted(it, assetHosts) } ?: throw verification("The release points to an address Beacon does not download from")

    /**
     * The rules for `Beacon-<version>.json` (written by tools/release-metadata.py), plus the API's asset size as a second witness.
     * One release is one version of the app and of the Hermes plugin, so a plugin version that differs from the app's is refused.
     */
    private fun checkMetadata(metadata: JSONObject, versionName: String, apiSize: Long) {
        fun text(key: String) = metadata.opt(key) as? String ?: ""
        fun whole(key: String): Long? = (metadata.opt(key) as? Number)?.takeIf { it.toDouble() == it.toLong().toDouble() }?.toLong()
        val code = whole("version_code")
        val size = whole("size_bytes")
        val sound = VERSION_NAME.matches(versionName) && text("version_name") == versionName && text("artifact_filename") == "Beacon-$versionName.apk" &&
            text("package_name").isNotBlank() && code != null && code > 20 && size != null && size in 1..MAX_UPDATE_APK_BYTES && (apiSize <= 0 || apiSize == size) &&
            STORED_SHA.matches(text("sha256")) && SIGNER_SHA.matches(text("certificate_sha256")) && PLUGIN_VERSION_PATTERN.matches(text("plugin_version")) &&
            text("plugin_version") == versionName
        if (!sound) throw verification("The release metadata does not describe a Beacon update")
    }

    /** Streams the APK into [part] and refuses anything that is not exactly the announced size and SHA-256. */
    suspend fun download(release: Release, part: File, progress: (Long) -> Unit) {
        val url = release.url.toHttpUrlOrNull()?.takeIf { permitted(it, assetHosts) } ?: throw verification("The release points to an address Beacon does not download from")
        fetch(Request.Builder().url(url).header("User-Agent", userAgent).build(), assetHosts) { response ->
            if (response.code != 200) throw network("GitHub answered ${response.code}")
            val body = response.body ?: throw network("GitHub sent no data")
            if (body.contentLength().let { it >= 0 && it != release.size }) throw verification("The update is not the announced size")
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            part.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > release.size) throw verification("The update is larger than announced")
                        out.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        progress(total)
                    }
                }
            }
            if (total != release.size || !digest.digest().joinToString("") { "%02x".format(it) }.equals(release.sha, true)) throw verification("Update checksum or size failed. The installed app is unchanged.")
        }
    }

    /** Cancelling the coroutine cancels the in-flight call, so a stalled read ends at once. */
    private suspend fun <T> fetch(request: Request, hosts: Set<String>, read: (Response) -> T): T = coroutineScope {
        val active = AtomicReference<Call?>()
        val watcher = launch { try { awaitCancellation() } finally { active.get()?.cancel() } }
        try {
            withContext(Dispatchers.IO) {
                try { open(request, hosts, active).use(read) }
                catch (error: UpdateException) { throw error }
                catch (error: IOException) { throw network("Could not reach GitHub", error) }
            }
        } finally { watcher.cancel() }
    }

    private fun open(first: Request, hosts: Set<String>, active: AtomicReference<Call?>): Response {
        var request = first
        for (hop in 0..MAX_REDIRECTS) {
            if (!permitted(request.url, hosts)) throw verification("The download left the hosts Beacon trusts")
            val call = http.newCall(request)
            active.set(call)
            val response = call.execute()
            if (!response.isRedirect) return response
            val next = response.header("Location")?.let { response.request.url.resolve(it) }
            response.close()
            request = request.newBuilder().url(next ?: throw verification("GitHub sent an invalid redirect")).build()
        }
        throw network("Too many redirects")
    }

    private fun Response.bounded(limit: Int): ByteArray {
        val source = body ?: throw network("GitHub sent no data")
        if (source.contentLength() > limit) throw verification("GitHub answered with too much data")
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        source.byteStream().use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) throw verification("GitHub answered with too much data")
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }
}
