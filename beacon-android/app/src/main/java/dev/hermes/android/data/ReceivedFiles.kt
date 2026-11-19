package dev.hermes.android.data

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Files the agent sends in a chat, on this phone. Every byte comes through Hermes' own download route, `/api/fs/download` with the message's
 * conversation and profile (hermes_cli/web_routers/files.py fs_download, the route Hermes' desktop downloads a delivered file with,
 * apps/desktop/src/lib/browser-transport.ts FILE_ENDPOINTS): Hermes resolves the conversation's workspace and applies its read rules
 * (_hosted_fs_read_guard, _fs_regular_file, _is_sensitive_path). A refusal is shown, never worked around through another route.
 */
internal data class FileHead(val size: Long?, val mime: String, val name: String?, val etag: String? = null, val modified: String? = null)

private const val RECEIVED_ROUTE = "/api/fs/download"
/** The most one file may take on this phone. */
internal const val RECEIVED_FILE_LIMIT = 4L shl 30

private fun BeaconClient.receivedQuery(reference: NativeReference.LocalMedia) =
    mapOf("path" to reference.path, "session_id" to reference.sessionId, "profile" to auth.profile)

private fun head(response: okhttp3.Response) = FileHead(
    response.header("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 },
    response.header("Content-Type").orEmpty().substringBefore(';').trim(),
    contentDispositionName(response.header("Content-Disposition")),
    response.header("ETag")?.trim()?.takeIf(String::isNotEmpty),
    response.header("Last-Modified")?.trim()?.takeIf(String::isNotEmpty),
)

/**
 * Whether Hermes still holds the file a copy was made from. Hermes' host download is Starlette's FileResponse, whose ETag (md5 of mtime and
 * size) and Last-Modified are the same on GET and HEAD; a profile's SSH workspace sends neither, only Content-Length on HEAD, so the size is
 * the signal there. Nothing to compare means the copy is kept.
 */
internal fun sameFileVersion(etag: String?, modified: String?, size: Long?, head: FileHead): Boolean {
    val kept = etag?.removePrefix("W/")
    val now = head.etag?.removePrefix("W/")
    return when {
        kept != null && now != null -> kept == now
        modified != null && head.modified != null -> modified == head.modified
        size != null && head.size != null -> size == head.size
        else -> true
    }
}

/** Size and type without the bytes: Hermes answers HEAD on the same route (files.py @router.head("/api/fs/download")). */
internal suspend fun BeaconClient.receivedFileHead(reference: NativeReference.LocalMedia, timeoutMillis: Long? = null): FileHead =
    nativeResponse(request(RECEIVED_ROUTE, receivedQuery(reference), "HEAD").build(), timeoutMillis) { response ->
        if (!response.isSuccessful) throw ApiFailure(response.code, "Download failed (${response.code})")
        head(response)
    }

/** The file's bytes into [output], telling [onProgress] (received, total; total -1 when Hermes does not say) as they arrive. A message's snapshot, when it has one, must match. */
internal suspend fun BeaconClient.downloadReceivedFile(reference: NativeReference.LocalMedia, output: OutputStream, limit: Long = RECEIVED_FILE_LIMIT, onProgress: (Long, Long) -> Unit = { _, _ -> }): FileHead =
    nativeResponse(request(RECEIVED_ROUTE, receivedQuery(reference)).build()) { response ->
        if (!response.isSuccessful) throw ApiFailure(response.code, "Download failed (${response.code})")
        val body = response.body ?: throw IOException("Missing file")
        val total = body.contentLength()
        if (total > limit) throw ReceivedFileTooLarge()
        val digest = reference.snapshot?.let { MessageDigest.getInstance("SHA-256") }
        val buffer = ByteArray(64 * 1024)
        var received = 0L
        body.byteStream().use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > limit - received) throw ReceivedFileTooLarge()
                output.write(buffer, 0, count)
                digest?.update(buffer, 0, count)
                received += count
                onProgress(received, total)
            }
        }
        if (digest != null && !digest.digest().joinToString("") { "%02x".format(it) }.equals(reference.snapshot, true)) throw ReceivedFileChanged()
        head(response).copy(size = received)
    }

internal class ReceivedFileTooLarge : IOException("This file is too large for this phone.")
internal class ReceivedFileChanged : IOException("This file changed since this message.")
internal class ReceivedFileNoSpace : IOException("Not enough space on this phone.")

internal fun receivedFileProblemOf(error: Throwable): ReceivedFileProblem = when (error) {
    is ReceivedFileTooLarge -> ReceivedFileProblem.TooLarge
    is ReceivedFileChanged -> ReceivedFileProblem.Changed
    is ReceivedFileNoSpace -> ReceivedFileProblem.NoSpace
    is ApiFailure -> receivedFileProblem(error.status)
    else -> ReceivedFileProblem.Offline
}

/** What Save to Downloads came to, for the card's line; [at] tells one save from the next. */
internal enum class ReceivedFileSaved { Saved, Failed, NoSpace }
internal data class ReceivedFileSave(val outcome: ReceivedFileSaved, val at: Long)

/** One received file as its card shows it. [local] is the copy on this phone, which opens without a connection. */
internal data class ReceivedFileState(
    val name: String,
    val size: Long? = null,
    val mime: String = "",
    val local: File? = null,
    val downloading: Boolean = false,
    val received: Long = 0L,
    val total: Long = -1L,
    val problem: ReceivedFileProblem? = null,
    /** What Hermes said about the copy's bytes when they came (ETag, Last-Modified): how a later answer tells whether it changed. */
    val etag: String? = null,
    val modified: String? = null,
    val saved: ReceivedFileSave? = null,
    /** What Hermes last said about the file itself (ETag, Last-Modified), copy or not: the card's picture belongs to this version. */
    val seenEtag: String? = null,
    val seenModified: String? = null,
) {
    val progress: Float? get() = if (downloading && total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Room a download of [fileBytes] leaves free on the phone: as much again as the file (so Save to Downloads still fits after), at least 64 MB
 * and at most 1 GB. A small file still comes on a nearly full phone.
 */
internal fun receivedFileReserve(fileBytes: Long): Long = fileBytes.coerceIn(64L shl 20, 1L shl 30)

/**
 * The phone's copies of received files, under [root]/<account>/<file>/: the file and a small record of its name, size, type and what Hermes
 * last said. A record lives with its account; [forgetAccount] removes them all at sign-out, account change and device removal, as the
 * in-memory MediaCache is cleared then. Copies over [budgetBytes] are let go oldest first; their cards stay and download again when opened.
 * The record and the download's temporary have names a delivered file never takes (safeLocalFileName never starts a name with a dot).
 */
internal class ReceivedFileStore(
    private val root: File,
    private val budgetBytes: Long = 2L shl 30,
    private val reserveBytes: Long = 64L shl 20,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val work = CoroutineScope(SupervisorJob() + dispatcher)
    private val states = ConcurrentHashMap<String, MutableStateFlow<ReceivedFileState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val checked = ConcurrentHashMap.newKeySet<String>()
    private val opened = clock()

    init {
        // A download an earlier run did not finish (the app was killed) leaves its temporary; this run's own are newer than the store.
        work.launch {
            root.listFiles().orEmpty().forEach { account -> account.listFiles().orEmpty().forEach { file ->
                File(file, PART).takeIf { it.isFile && it.lastModified() < opened - 2_000 }?.delete()
            } }
        }
    }

    private fun account(scope: Scope) = Scope(scope.origin, scope.owner, "", "", scope.nativeProvider).key
    /** One file of one conversation in one profile: the same path in another chat or profile is another file. */
    private fun key(scope: Scope, path: String) = account(scope) + "/" + (scope.key + "\u0000" + path).digest()
    private fun folder(key: String) = File(root, key)
    private fun record(key: String) = File(folder(key), RECORD)

    private fun flow(scope: Scope, path: String): MutableStateFlow<ReceivedFileState> {
        val key = key(scope, path)
        return states.getOrPut(key) { MutableStateFlow(load(key, receivedFileName(path))) }
    }
    fun state(scope: Scope, path: String): StateFlow<ReceivedFileState> = flow(scope, path)

    /** The copy on this phone, when there is one. */
    fun localCopy(scope: Scope, path: String): File? = flow(scope, path).value.local?.takeIf(File::isFile)

    private fun load(key: String, name: String): ReceivedFileState = try {
        val saved = record(key).takeIf(File::isFile)?.let { JSONObject(it.readText()) } ?: return ReceivedFileState(name)
        val size = saved.optLong("size", -1L).takeIf { it >= 0 }
        val local = saved.optString("file").takeIf(String::isNotBlank)?.let { File(folder(key), it) }
            ?.takeIf { it.isFile && (size == null || it.length() == size) }
        val problem = saved.optString("problem").takeIf(String::isNotBlank)?.let { runCatching { ReceivedFileProblem.valueOf(it) }.getOrNull() }
        fun text(field: String) = saved.optString(field).takeIf { local != null && it.isNotBlank() }
        ReceivedFileState(name, size, saved.optString("mime"), local, problem = problem, etag = text("etag"), modified = text("modified"),
            seenEtag = saved.optString("seen_etag").ifBlank { null }, seenModified = saved.optString("seen_modified").ifBlank { null })
    } catch (_: Exception) { ReceivedFileState(name) }

    /** Writes the record, unless the account was signed out meanwhile ([flow] is no longer this store's): sign-out takes the same lock. */
    private fun persist(key: String, flow: MutableStateFlow<ReceivedFileState>, used: Long = clock()) = synchronized(this) {
        if (states[key] !== flow) return@synchronized
        val state = flow.value
        try {
            val folder = folder(key).apply { mkdirs() }
            val saved = JSONObject().put("size", state.size ?: -1L).put("mime", state.mime).put("used", used)
                .put("file", state.local?.name.orEmpty()).put("etag", state.etag.orEmpty()).put("modified", state.modified.orEmpty())
                .put("seen_etag", state.seenEtag.orEmpty()).put("seen_modified", state.seenModified.orEmpty())
                .put("problem", state.problem?.takeIf { it == ReceivedFileProblem.Gone || it == ReceivedFileProblem.Refused }?.name.orEmpty())
            val temporary = File(folder, "$RECORD.tmp")
            temporary.writeText(saved.toString())
            if (!temporary.renameTo(record(key))) { record(key).delete(); temporary.renameTo(record(key)) }
        } catch (_: Exception) { /* the card still works from memory; the next run asks Hermes again */ }
    }

    /**
     * Hermes' answer about the file: size and type for the card, and a copy here that is no longer this file is let go, so Open, Save and
     * Share fetch the new one. True when the card's picture must load again: the copy went, or Hermes now reports another version than it
     * did last time (ETag, else Last-Modified, else size), copy or not.
     */
    private fun settle(key: String, flow: MutableStateFlow<ReceivedFileState>, head: FileHead): Boolean {
        val before = flow.value
        val copy = before.local?.takeIf(File::isFile)
        val copyChanged = copy != null && !sameFileVersion(before.etag, before.modified, copy.length(), head)
        val seenChanged = !sameFileVersion(before.seenEtag, before.seenModified, before.size, head)
        if (copyChanged) copy?.delete()
        val keep = copy != null && !copyChanged
        flow.update { it.copy(size = head.size ?: it.size, mime = head.mime.ifBlank { it.mime }, problem = null,
            local = it.local.takeIf { keep }, etag = it.etag.takeIf { keep }, modified = it.modified.takeIf { keep },
            seenEtag = head.etag, seenModified = head.modified) }
        persist(key, flow)
        return copyChanged || seenChanged
    }

    /** Hermes now refuses the file (an access rule): a copy here is not handed out around it. True when a copy went. */
    private fun refuse(key: String, flow: MutableStateFlow<ReceivedFileState>): Boolean {
        val dropped = flow.value.local?.let { it.delete(); true } ?: false
        flow.update { it.copy(local = null, etag = null, modified = null, problem = ReceivedFileProblem.Refused) }
        persist(key, flow)
        return dropped
    }

    /**
     * Asks Hermes for the size and type, and whether a copy here is still that file: once per run for each [message] that names the file (a
     * later message about the same path asks again, so a file the agent rewrote shows as it is now), and on every call without a message.
     * Without an answer within [waitMs] nothing changes. True when the card's picture must load again.
     */
    /** Whether [message]'s card already asked Hermes about the file in this run (a card coming back into view then shows what it has at once). */
    fun asked(scope: Scope, path: String, message: String?): Boolean = message != null && (key(scope, path) + "\u0000" + message) in checked

    suspend fun check(scope: Scope, reference: NativeReference.LocalMedia, client: BeaconClient, message: String? = null, waitMs: Long = 3_000): Boolean {
        val key = key(scope, reference.path)
        val flow = flow(scope, reference.path)
        val mark = message?.let { key + "\u0000" + it }
        if (flow.value.downloading || mark != null && !checked.add(mark)) return false
        try {
            return settle(key, flow, client.receivedFileHead(reference, waitMs))
        } catch (e: CancellationException) { mark?.let(checked::remove); throw e }
        catch (e: Exception) {
            when (val status = (e as? ApiFailure)?.status) {
                400, 403 -> return refuse(key, flow)
                404, 410 -> { flow.update { it.copy(problem = receivedFileProblem(status)) }; persist(key, flow) }
                // Older Hermes without HEAD: the size shows once the file is downloaded.
                405, 501 -> Unit
                else -> mark?.let(checked::remove)
            }
            return false
        }
    }

    /**
     * Before Open, Save or Share: the copy here while it is still the file Hermes holds, or when Hermes can't be asked (signed out, offline,
     * no answer within [waitMs]); null when it changed or Hermes now refuses it, and then the card downloads again or says why. A file
     * Hermes no longer has still opens from its copy.
     */
    suspend fun current(scope: Scope, reference: NativeReference.LocalMedia, client: BeaconClient?, waitMs: Long = 3_000): File? {
        val key = key(scope, reference.path)
        val flow = flow(scope, reference.path)
        val copy = flow.value.local?.takeIf(File::isFile) ?: return null
        if (client == null) return copy
        val head = try { client.receivedFileHead(reference, waitMs) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            when (val status = (e as? ApiFailure)?.status) {
                400, 403 -> { refuse(key, flow); return null }
                404, 410 -> { flow.update { it.copy(problem = receivedFileProblem(status)) }; persist(key, flow) }
            }
            return copy
        }
        return if (settle(key, flow, head)) null else flow.value.local?.takeIf(File::isFile)
    }

    fun downloading(scope: Scope, path: String): Boolean = jobs[key(scope, path)]?.isActive == true

    /** Downloads the file to this phone, unless it is already downloading. [client] is this store's to close. */
    fun download(scope: Scope, reference: NativeReference.LocalMedia, client: BeaconClient): Job? {
        val key = key(scope, reference.path)
        val flow = flow(scope, reference.path)
        synchronized(jobs) {
            if (jobs[key]?.isActive == true) { client.closeNative(); return null }
            flow.update { it.copy(downloading = true, received = 0L, total = it.size ?: -1L, problem = null) }
            val job = work.launch(start = CoroutineStart.LAZY) {
                val name = safeLocalFileName(flow.value.name)
                val folder = folder(key).apply { mkdirs() }
                val part = File(folder, PART)
                var shown = 0L
                try {
                    flow.value.size?.let { size -> if (size > RECEIVED_FILE_LIMIT) throw ReceivedFileTooLarge(); if (root.usableSpace in 0 until size + maxOf(reserveBytes, receivedFileReserve(size))) throw ReceivedFileNoSpace() }
                    val head = part.outputStream().use { output ->
                        client.downloadReceivedFile(reference, output) { received, total ->
                            if (root.usableSpace in 0 until reserveBytes) throw ReceivedFileNoSpace()
                            // Every 256 KB, and the end: enough for a smooth bar without waking the screen for each block.
                            if (received - shown >= 256 * 1024 || received == total) { shown = received; flow.update { it.copy(received = received, total = total) } }
                        }
                    }
                    val target = File(folder, name)
                    target.delete()
                    if (!part.renameTo(target)) throw IOException("Could not keep the file")
                    // Signed out while the last bytes arrived: the account's folder is gone, and this copy goes with it.
                    if (states[key] !== flow) { target.delete(); folder.delete(); return@launch }
                    // The validators of the bytes kept, from this download: a later answer from Hermes is compared with these.
                    flow.update { it.copy(local = target, size = target.length(), mime = head.mime.ifBlank { it.mime }, downloading = false, received = target.length(),
                        problem = null, etag = head.etag, modified = head.modified, seenEtag = head.etag, seenModified = head.modified) }
                    persist(key, flow)
                    evict(keep = key)
                } catch (e: Throwable) {
                    part.delete()
                    flow.update { it.copy(downloading = false, received = 0L, problem = if (e is CancellationException) it.problem else receivedFileProblemOf(e)) }
                    if (e !is CancellationException && flow.value.problem.let { it == ReceivedFileProblem.Gone || it == ReceivedFileProblem.Refused }) persist(key, flow)
                    if (e is CancellationException) throw e
                } finally {
                    client.closeNative()
                    coroutineContext[Job]?.let { jobs.remove(key, it) }
                }
            }
            jobs[key] = job
            job.start()
            return job
        }
    }

    fun cancel(scope: Scope, path: String) { jobs[key(scope, path)]?.cancel() }

    /**
     * Save to Downloads in the store's own scope, so it finishes when the card leaves the screen: the copy here while it is current, else the
     * file fetched again first, then [saveTo]. The outcome shows on the card ([ReceivedFileState.saved]); a failed download shows its own line.
     */
    fun save(scope: Scope, reference: NativeReference.LocalMedia, client: BeaconClient?, saveTo: suspend (File) -> Unit): Job {
        val key = key(scope, reference.path)
        val flow = flow(scope, reference.path)
        flow.update { it.copy(saved = null) }
        return work.launch {
            var handed = false
            val outcome = try {
                val file = current(scope, reference, client) ?: run {
                    val c = client ?: return@launch
                    if (flow.value.problem == ReceivedFileProblem.Refused) return@launch
                    handed = true
                    (download(scope, reference, c) ?: jobs[key])?.join()
                    flow.value.local?.takeIf(File::isFile)
                } ?: return@launch
                saveTo(file)
                ReceivedFileSaved.Saved
            } catch (e: CancellationException) { throw e }
            catch (e: ReceivedFileNoSpace) { ReceivedFileSaved.NoSpace }
            catch (e: Exception) { ReceivedFileSaved.Failed }
            finally { if (!handed) client?.closeNative() }
            if (states[key] === flow) flow.update { it.copy(saved = ReceivedFileSave(outcome, clock())) }
        }
    }

    /** The card's line for the last save goes when the owner does something else. */
    fun clearSaved(scope: Scope, path: String) { states[key(scope, path)]?.update { it.copy(saved = null) } }

    /** Marks the copy as used now, so the budget lets go of older ones first. */
    fun touch(scope: Scope, path: String) {
        val key = key(scope, path)
        states[key]?.takeIf { it.value.local != null }?.let { flow -> work.launch { persist(key, flow) } }
    }

    /** Lets go of the oldest copies (never [keep]) until the rest fit the budget; their records and cards stay. */
    private fun evict(keep: String) {
        val copies = root.listFiles().orEmpty().flatMap { account -> account.listFiles().orEmpty().map { "${account.name}/${it.name}" } }.mapNotNull { key ->
            val saved = runCatching { JSONObject(record(key).readText()) }.getOrNull() ?: return@mapNotNull null
            val file = saved.optString("file").takeIf(String::isNotBlank)?.let { File(folder(key), it) }?.takeIf(File::isFile) ?: return@mapNotNull null
            Triple(key, file, saved.optLong("used"))
        }
        var total = copies.sumOf { it.second.length() }
        for ((key, file, _) in copies.sortedBy { it.third }) {
            if (total <= budgetBytes) break
            if (key == keep || jobs[key]?.isActive == true) continue
            total -= file.length()
            file.delete()
            states[key]?.let { flow -> flow.update { it.copy(local = null, etag = null, modified = null) }; persist(key, flow) }
        }
    }

    /** Sign-out, account change and device removal: this account's copies and records go, and its downloads stop. */
    fun forgetAccount(scope: Scope): Unit = synchronized(this) {
        val account = account(scope)
        jobs.keys.filter { it.startsWith("$account/") }.forEach { jobs.remove(it)?.cancel() }
        states.keys.removeAll { it.startsWith("$account/") }
        checked.removeAll { it.startsWith("$account/") }
        File(root, account).deleteRecursively()
    }

    private companion object {
        const val RECORD = ".record.json"
        const val PART = ".download.part"
    }
}

/** The app's one store, in its private files (not the cache, which Android may empty): a downloaded file still opens offline. */
internal object ReceivedFiles {
    @Volatile private var instance: ReceivedFileStore? = null
    fun store(context: Context): ReceivedFileStore = instance ?: synchronized(this) {
        instance ?: ReceivedFileStore(File(context.applicationContext.filesDir, "received")).also { instance = it }
    }
}

/** A content:// address other apps can read for this one file (FileProvider, res/xml/file_paths.xml "received"); never a file:// path. */
internal fun receivedFileUri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

/** Opens the file in the app this phone uses for its type (ACTION_VIEW). False when no app opens it. */
internal fun openReceivedFile(context: Context, file: File, mime: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(receivedFileUri(context, file), mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
} catch (_: ActivityNotFoundException) { false }

/** The share sheet with the file itself (ACTION_SEND + EXTRA_STREAM); ClipData carries the read grant to the app picked. */
internal fun shareReceivedFile(context: Context, file: File, name: String, mime: String): Boolean = try {
    val uri = receivedFileUri(context, file)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(name, uri)
    context.startActivity(Intent.createChooser(send, null).apply { if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    true
} catch (_: ActivityNotFoundException) { false }

/** A copy in the phone's Downloads (MediaStore.Downloads, API 29+, no storage permission). Pending until written, so no app sees half a file. */
internal suspend fun saveReceivedFileToDownloads(context: Context, file: File, name: String, mime: String): Uri = withContext(Dispatchers.IO) {
    // A full phone gets the card's space line, not a general failure (the shared storage Downloads lives on).
    context.getExternalFilesDir(null)?.usableSpace?.let { free -> if (free in 0 until file.length() + (16L shl 20)) throw ReceivedFileNoSpace() }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, safeLocalFileName(name))
        put(MediaStore.Downloads.MIME_TYPE, mime)
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Downloads is not available")
    try {
        (resolver.openOutputStream(uri) ?: throw IOException("Downloads is not available")).use { output -> file.inputStream().use { input -> input.copyTo(output) } }
        currentCoroutineContext().ensureActive()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        uri
    } catch (e: Throwable) {
        resolver.delete(uri, null, null)
        if (e is IOException && e.message.orEmpty().let { "ENOSPC" in it || "No space" in it }) throw ReceivedFileNoSpace()
        throw e
    }
}
