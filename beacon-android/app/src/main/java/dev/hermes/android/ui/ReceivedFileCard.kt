package dev.hermes.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ReceivedFileAction { Open, Save, Share }

private const val SAVED_TO_DOWNLOADS = "Saved to Downloads"
private const val SAVE_FAILED = "Couldn't save the file."
private const val NO_APP_OPENS = "No app on this phone opens this file."
private const val NO_APP_SHARES = "No app on this phone can share this file."
private const val SIGNED_OUT = "Open this from its chat."

/** Every line a file card can show; each reaches uiText through a variable, so the translation check reads this list. */
internal val ReceivedFileStrings = listOf(SAVED_TO_DOWNLOADS, SAVE_FAILED, NO_APP_OPENS, NO_APP_SHARES, SIGNED_OUT) + ReceivedFileProblem.entries.map { it.text }

private fun FileCategory.icon(): ImageVector = when (this) {
    FileCategory.Image -> Icons.Rounded.Image
    FileCategory.Pdf -> Icons.Rounded.PictureAsPdf
    FileCategory.Document -> Icons.Rounded.Description
    FileCategory.Spreadsheet -> Icons.Rounded.TableChart
    FileCategory.Presentation -> Icons.Rounded.Slideshow
    FileCategory.Archive -> Icons.Rounded.FolderZip
    FileCategory.Audio -> Icons.Rounded.AudioFile
    FileCategory.Video -> Icons.Rounded.VideoFile
    FileCategory.Text -> Icons.AutoMirrored.Rounded.Article
    FileCategory.App -> Icons.Rounded.Android
    FileCategory.Other -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** A received file's bytes for an inline picture, from the copy on this phone when there is one (so it shows offline too). */
internal suspend fun receivedPreviewBytes(vm: BeaconViewModel, origin: Scope, reference: NativeReference.LocalMedia): NativeMediaBytes? {
    val file = ReceivedFiles.store(vm.app).localCopy(origin, reference.path)?.takeIf { it.length() <= 32L * 1024 * 1024 } ?: return null
    return runCatching { withContext(kotlinx.coroutines.Dispatchers.IO) { NativeMediaBytes(file.readBytes(), receivedFileMime(file.name)) } }.getOrNull()
}

/**
 * A file the agent sent, as the file it is: name, type and size, then Open (Android's own viewer for the type), Save (Downloads) and Share
 * (the share sheet). The first action downloads it through Hermes, with progress and Cancel; the copy stays on this phone, so the card and
 * the file still open after the chat is closed, the screen cache is cleared or the phone is offline. A picture also shows inline.
 */
@Composable internal fun ReceivedFileCard(vm: BeaconViewModel, reference: NativeReference.LocalMedia, origin: Scope) {
    val context = LocalContext.current
    val store = remember { ReceivedFiles.store(vm.app) }
    val state by remember(origin.key, reference.path) { store.state(origin, reference.path) }.collectAsStateWithLifecycle()
    val name = state.name
    val category = receivedFileCategory(name)
    val mime = receivedFileMime(name, state.mime)
    val scope = rememberCoroutineScope()
    var note by remember(origin.key, reference.path) { mutableStateOf("") }
    var pending by remember(origin.key, reference.path) { mutableStateOf<ReceivedFileAction?>(null) }
    var asking by remember(origin.key, reference.path) { mutableStateOf(false) }
    val app = vm.app
    // Only a client of this message's own account and profile ever fetches it.
    fun client(): BeaconClient? = vm.client?.takeIf { it.scope == origin.copy(session = "") }?.detached()
    // A copy that went (the file changed on Hermes) takes its picture with it.
    fun forgetPicture() = MediaCache.payloads.remove(MediaKey(origin.key, reference))

    // The message this card belongs to: Hermes is asked once per run for each message naming the file, so a later message about a file the
    // agent rewrote shows the file as it is now (its size, and its picture once the cached one is let go).
    val message = LocalMessageKey.current
    // Until this card's question is answered (3 s at most), a picture waits rather than show a version Hermes may no longer hold. A card that
    // already asked this run (scrolled back into view), or one with nobody to ask, shows what it has at once.
    var answered by remember(origin.key, reference.path, message) {
        mutableStateOf(vm.client?.scope != origin.copy(session = "") || store.asked(origin, reference.path, message))
    }
    LaunchedEffect(origin.key, reference.path, message) {
        try { client()?.let { c -> try { if (store.check(origin, reference, c, message)) forgetPicture() } finally { c.closeNative() } } }
        finally { answered = true }
    }
    fun perform(action: ReceivedFileAction, file: java.io.File) {
        store.touch(origin, reference.path)
        when (action) {
            ReceivedFileAction.Open -> if (!openReceivedFile(context, file, mime)) note = NO_APP_OPENS
            ReceivedFileAction.Share -> if (!shareReceivedFile(context, file, name, mime)) note = NO_APP_SHARES
            ReceivedFileAction.Save -> Unit
        }
    }
    fun request(action: ReceivedFileAction) {
        note = ""
        store.clearSaved(origin, reference.path)
        val c = client()
        if (state.local == null && c == null) { note = SIGNED_OUT; return }
        // Save runs in the store, so it finishes when the card scrolls away; its line comes back through the card's state.
        if (action == ReceivedFileAction.Save) { store.save(origin, reference, c) { file -> saveReceivedFileToDownloads(app, file, name, mime) }; return }
        // Open and Share stay with the card: one that popped up after the card left the screen would surprise. Online, Hermes is asked first
        // whether the copy here is still the file; a changed one is fetched again.
        asking = true
        scope.launch {
            var handed = false
            try {
                val file = store.current(origin, reference, c)
                when {
                    file != null -> perform(action, file)
                    c != null && store.state(origin, reference.path).value.problem != ReceivedFileProblem.Refused -> {
                        forgetPicture(); pending = action; handed = true; store.download(origin, reference, c)
                    }
                    else -> forgetPicture()
                }
            } finally { if (!handed) c?.closeNative(); asking = false }
        }
    }
    // The action asked for runs once the file is here; a failed download drops it.
    LaunchedEffect(state.local, pending) {
        val file = state.local
        val action = pending
        if (file != null && action != null) { pending = null; perform(action, file) }
    }
    LaunchedEffect(state.problem, state.downloading) { if (state.problem != null || !state.downloading && state.local == null) pending = null }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // A picture shows inline, unless Hermes already said it is gone or refused: then the card's one line says so.
        val unavailable = state.local == null && (state.problem == ReceivedFileProblem.Gone || state.problem == ReceivedFileProblem.Refused)
        // Keyed by the copy and the version Hermes last reported, so a file that changed loads its new picture.
        if (category == FileCategory.Image && !unavailable) {
            if (answered) key(state.local?.path, state.etag, state.modified, state.seenEtag, state.seenModified, state.size) { NativeReferencePreview(vm, reference, name, origin, received = true) }
            else MediaPlaceholder(placeholderShape(mediaKindOf(name)))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().testTag("received-file")) {
            Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(category.icon(), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                        Text(name, Modifier.testTag("received-file-name"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(receivedFileDetail(name, state.size), Modifier.testTag("received-file-detail"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (asking && !state.downloading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp).testTag("received-file-asking"))
                if (state.downloading) Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val progress = state.progress
                    if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).testTag("received-file-progress"))
                    else LinearProgressIndicator(Modifier.weight(1f).testTag("received-file-progress"))
                    TextButton(onClick = { pending = null; store.cancel(origin, reference.path) }, modifier = Modifier.testTag("received-file-cancel")) { Text(uiText("Cancel")) }
                } else if (state.local != null || state.problem != ReceivedFileProblem.Gone && state.problem != ReceivedFileProblem.Refused) Row {
                    if (receivedFileOpens(name)) TextButton(onClick = { request(ReceivedFileAction.Open) }, modifier = Modifier.testTag("received-file-open")) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("Open"))
                    }
                    TextButton(onClick = { request(ReceivedFileAction.Save) }, modifier = Modifier.testTag("received-file-save")) {
                        Icon(Icons.Rounded.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("Save"))
                    }
                    TextButton(onClick = { request(ReceivedFileAction.Share) }, modifier = Modifier.testTag("received-file-share")) {
                        Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("Share"))
                    }
                }
                val problem = state.problem?.takeIf { !state.downloading && (state.local == null || it == ReceivedFileProblem.NoSpace || it == ReceivedFileProblem.Offline) }
                val saved = when (state.saved?.outcome) {
                    ReceivedFileSaved.Saved -> SAVED_TO_DOWNLOADS
                    ReceivedFileSaved.Failed -> SAVE_FAILED
                    ReceivedFileSaved.NoSpace -> ReceivedFileProblem.NoSpace.text
                    null -> null
                }
                val line = problem?.text ?: saved ?: note
                if (line.isNotBlank()) Text(uiText(line), Modifier.padding(end = 8.dp, bottom = 8.dp).testTag("received-file-note"), style = MaterialTheme.typography.bodySmall,
                    color = if (problem != null || line == SAVE_FAILED || line == ReceivedFileProblem.NoSpace.text) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
