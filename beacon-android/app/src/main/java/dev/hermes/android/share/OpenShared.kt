package dev.hermes.android.share

import android.net.Uri
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.viewModelScope
import dev.hermes.android.AppState
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The app is signed in and past its start: a chat can be opened (from the saved screens if Hermes is not reachable yet). */
internal fun appReadyForShare(ui: AppState): Boolean = ui.authenticated && !ui.restoringSession && !ui.setupWizard

/**
 * The chosen chat is open with its saved draft restored. Until then the composer still belongs to the chat that was open before, and
 * the shared content must never be added to that one.
 */
internal fun shareChatOpen(target: ShareTarget, chat: ChatState, ui: AppState, beaconOpen: Boolean): Boolean = chat.sessionId.isNotBlank() && chat.projectionReady && when (target) {
    ShareTarget.Beacon -> ui.tab == "Beacon" && !ui.beaconOpening && ui.beaconError.isBlank() && beaconOpen
    ShareTarget.NewChat -> ui.tab == "Chat" && !ui.newChatOpening
    is ShareTarget.Chat -> ui.tab == "Chat" && chat.sessionId == target.id
}

/**
 * Takes the share the chooser handed over: opens the chosen chat, puts the shared text in its composer under whatever is already
 * typed there, and attaches the images the way the attach button does. It sends nothing; the owner reviews, adds a line and sends.
 */
internal fun openShared(model: BeaconViewModel, share: PendingShare): Job? {
    if (!SharedIntake.claim(share, System.currentTimeMillis())) return null
    return model.viewModelScope.launch {
        if (withTimeoutOrNull(SHARE_READY_MILLIS) { model.ui.first(::appReadyForShare) } == null) { model.notice(ShareStrings.SIGN_IN); return@launch }
        when (val target = share.target) {
            ShareTarget.Beacon -> { model.tab("Beacon"); model.openBeacon().join() }
            ShareTarget.NewChat -> { model.tab("Chat"); model.newChat().join(); model.awaitSelection() }
            is ShareTarget.Chat -> { model.tab("Chat"); if (model.chat.value.sessionId != target.id) { model.selectSession(target.id); model.awaitSelection() } }
        }
        if (withTimeoutOrNull(SHARE_OPEN_MILLIS) { model.chat.first { shareChatOpen(share.target, it, model.ui.value, model.isBeaconConversation()) } } == null) { model.notice(ShareStrings.NOT_OPENED); return@launch }
        val merged = mergeSharedDraft(model.composer.value.text, share.content.text)
        if (share.content.text.isNotBlank()) model.composer(TextFieldValue(merged, TextRange(merged.length)))
        share.content.images.forEach { model.upload(Uri.parse(it)) }
    }
}
