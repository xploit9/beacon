package dev.hermes.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.hermes.android.AppState
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.rows
import dev.hermes.android.data.BeaconOffLines
import dev.hermes.android.data.PROFILE_PLUGIN_OFF_ERROR
import dev.hermes.android.newChatAction

/**
 * The card of a new chat that has no conversation behind it yet: opening, the generic Retry, and a profile Hermes refused Beacon for after the phone could not turn it on
 * by itself. That is one line with its button ([dev.hermes.android.data.beaconOffAction]): Turn on when the profile turned Beacon off on Hermes, Retry when turning it on did
 * not work, and "Turning on Beacon…" while Hermes works; the chat opens by itself after. With [existingChatLine] it is the page of a chat the owner opened in such a profile:
 * the same line and button, and the button opens that same chat again.
 */
@Composable internal fun FirstChatCard(vm: BeaconViewModel, ui: AppState, existingChatLine: String = "") {
    val turningOn by vm.profileTurningOn.collectAsState()
    val data by vm.data.collectAsState()
    val assistantProfile = vm.preferences.collectAsState().value.assistantProfile
    val existingChat = existingChatLine.isNotBlank()
    val opening = ui.newChatOpening && !existingChat
    val error = if (existingChat) existingChatLine else ui.newChatError
    val title = uiText(if (turningOn) "Turning on Beacon…" else if (existingChat) error else if (opening) "Opening chat…" else "").ifBlank { if (ui.tab == "Beacon") LocalAssistantName.current else profileTitle(ui.profile, data["navigation-profiles"]?.rows("profiles").orEmpty(), LocalAssistantName.current, assistantProfile) }
    val description = when {
        turningOn || existingChat -> ""
        error.isNotBlank() -> uiText(error)
        else -> if (opening) uiText("Your draft is kept.") else ""
    }
    EmptyState(title, description, icon = null,
        action = if (turningOn) "" else uiText(newChatAction(error, opening)),
        onAction = {
            when {
                error == PROFILE_PLUGIN_OFF_ERROR -> vm.turnOnBeaconForProfile(auto = false)
                error in BeaconOffLines -> vm.turnOnBeaconForProfile(auto = true)
                else -> vm.retryInitialChat()
            }
        },
        centered = true)
}
