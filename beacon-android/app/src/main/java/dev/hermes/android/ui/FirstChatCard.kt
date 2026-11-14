package dev.hermes.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.hermes.android.AppState
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.rows
import dev.hermes.android.data.PROFILE_PLUGIN_COMMAND_HEAD
import dev.hermes.android.data.PROFILE_PLUGIN_OFF_ERROR
import dev.hermes.android.data.profileTurnOnCommand
import dev.hermes.android.newChatAction
import dev.hermes.android.newChatSecondaryAction

/**
 * The card of a new chat that has no conversation behind it yet. Besides opening and the generic Retry it covers a profile Hermes does not serve Beacon for:
 * one line with Turn on (a confirm sheet naming the profile, then the plugin asks Hermes to turn Beacon on for that profile only and the chat opens by itself),
 * "Turning on Beacon…" while Hermes works, and, when the plugin cannot do it from the phone, the exact command to run (selectable, with Copy command and Check again).
 * With [existingChat] it is the page of a chat the owner opened in such a profile: the same one line and buttons, and Turn on opens that same chat again.
 */
@Composable internal fun FirstChatCard(vm: BeaconViewModel, ui: AppState, existingChat: Boolean = false) {
    val turningOn by vm.profileTurningOn.collectAsState()
    val data by vm.data.collectAsState()
    val assistantProfile = vm.preferences.collectAsState().value.assistantProfile
    var confirm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val opening = ui.newChatOpening && !existingChat
    val error = if (existingChat) ui.newChatError.ifBlank { PROFILE_PLUGIN_OFF_ERROR } else ui.newChatError
    val command = profileTurnOnCommand(error)
    val copied = uiText("Copied")
    val title = uiText(if (turningOn) "Turning on Beacon…" else if (existingChat) PROFILE_PLUGIN_OFF_ERROR else if (opening) "Opening chat…" else "").ifBlank { if (ui.tab == "Beacon") LocalAssistantName.current else profileTitle(ui.profile, data["navigation-profiles"]?.rows("profiles").orEmpty(), LocalAssistantName.current, assistantProfile) }
    val description = when {
        turningOn -> ""
        command != null -> uiText(PROFILE_PLUGIN_COMMAND_HEAD) + "\n" + command
        existingChat && error == PROFILE_PLUGIN_OFF_ERROR -> ""
        error.isNotBlank() -> uiText(error)
        else -> if (opening) uiText("Your draft is kept.") else ""
    }
    EmptyState(title, description, icon = null,
        action = if (turningOn) "" else uiText(newChatAction(error, opening)),
        onAction = {
            when {
                error == PROFILE_PLUGIN_OFF_ERROR -> confirm = true
                command != null -> {
                    runCatching { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Command", command)) }
                    vm.notice(copied)
                }
                else -> vm.retryInitialChat()
            }
        },
        centered = true,
        secondaryAction = if (turningOn) "" else uiText(newChatSecondaryAction(error, opening)),
        onSecondaryAction = { vm.retryInitialChat() },
        selectable = command != null)
    if (confirm) {
        // The sheet names the profile itself, never by the assistant's name: Hermes' name for it, else its readable id ("this profile" only without an id).
        val name = profileTitle(ui.profile, data["navigation-profiles"]?.rows("profiles").orEmpty(), "", "")
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(if (name.isBlank()) uiText("Turn on Beacon for this profile?") else uiText("Turn on Beacon for {0}?").replace("{0}", name)) },
            text = { Text(uiText("It takes a few seconds.")) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.turnOnBeaconForProfile() }) { Text(uiText("Turn on")) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(uiText("Cancel")) } },
        )
    }
}
