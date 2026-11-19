package dev.hermes.android.data

import dev.hermes.android.core.string
import org.json.JSONObject
import java.io.File

/**
 * Picked chat files too large for one 8 MiB frame (CHAT-121 follow-up 4) stay on this phone as files until they are sent, not as base64 in the
 * draft: the draft, the pending send and the queued message keep only `staged_file: true` with the attachment's id. The file sits in the app's own
 * storage under that id ([file]: a path never comes from what was stored), so a draft survives a restart; it is deleted when it is removed, once
 * Hermes has it, at sign-out, and after [KEEP_MILLIS] untouched.
 */
internal object ChatAttachmentStore {
    /** The app's chat-attachments folder (BeaconApplication sets it); tests set a folder of their own. */
    @Volatile var dir: File? = null
    const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000
    /** The most the kept files may hold together: past it the oldest go (their drafts say "attach it again", as after [KEEP_MILLIS]). */
    const val MAX_BYTES = 512L * 1024 * 1024
    private val id = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    /** The kept file of [item], or null when it keeps none (a small file travels as base64) or its id is not one of ours. */
    fun file(item: JSONObject): File? =
        if (item.optBoolean("staged_file")) item.string("id").takeIf { it.matches(id) }?.let { name -> dir?.let { File(it, name) } } else null

    /** Moves [source] (the picked file, read in full) into the store under [attachmentId]; the attachment's JSON says `staged_file: true`. */
    fun adopt(source: File, attachmentId: String, capBytes: Long = MAX_BYTES): File {
        require(attachmentId.matches(id)) { "Invalid attachment id" }
        val folder = requireNotNull(dir) { "Attachment storage is unavailable" }.apply { mkdirs() }
        val target = File(folder, attachmentId)
        if (!source.renameTo(target)) { source.copyTo(target, overwrite = true); source.delete() }
        capTotal(folder, target, capBytes)
        return target
    }

    /** Deletes the oldest files, never [keep], until the folder fits [capBytes]. */
    internal fun capTotal(folder: File, keep: File, capBytes: Long) {
        val files = folder.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        for (old in files.filter { it != keep }.sortedBy { it.lastModified() }) {
            if (total <= capBytes) break
            val size = old.length()
            if (old.delete()) total -= size
        }
    }

    fun delete(item: JSONObject) { file(item)?.delete() }
    fun deleteAll(items: Iterable<JSONObject>) { items.forEach(::delete) }

    /** Removes files older than [KEEP_MILLIS] (a draft left that long gets "attach it again" at send). */
    fun sweep(now: Long = System.currentTimeMillis()) {
        dir?.listFiles()?.filter { it.isFile && now - it.lastModified() > KEEP_MILLIS }?.forEach { it.delete() }
    }

    /** At sign-out: nothing of the account stays. */
    fun clear() { dir?.listFiles()?.forEach { it.delete() } }
}
