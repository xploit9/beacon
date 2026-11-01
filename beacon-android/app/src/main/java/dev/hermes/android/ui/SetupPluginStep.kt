package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.hermes.android.updates.PluginInstallFailure
import dev.hermes.android.updates.PluginInstallStage
import dev.hermes.android.updates.PluginInstallUi
import org.json.JSONObject

// The setup page that puts the Beacon plugin on a Hermes that does not have it yet. It sits after sign-in, before the call pages, and appears
// only when the app's own plugin probe says the plugin is absent. The install itself is the one the app already uses everywhere else
// (PluginInstallController); this file only draws it, as a short checklist, and decides when the wizard shows it.

/** The README section for setting up from the PC, which the page links to as the other way. */
internal const val BEACON_PC_INSTALL_URL = "https://github.com/xploit9/beacon#alternative-set-up-from-the-pc"

/** How long the page waits for the plugin probe before the wizard moves on; a late answer still brings the page back. */
internal const val SETUP_PLUGIN_CHECK_MS = 6_000L

/** The call pages and the permission pages: the first one the wizard reaches after sign-in decides whether this page comes first. */
internal val SETUP_PLUGIN_GATE_STEPS = setOf("voice-provider", "voice-customize", "permission-microphone", "permission-notifications", "permission-full-screen")

/** What the app knows about Beacon on the signed-in Hermes, read from the plugin probe it already runs. */
internal enum class SetupPluginPhase { Checking, Absent, Present, Unknown }

internal fun setupPluginPhase(bootstrap: JSONObject): SetupPluginPhase = when (bootstrap.optJSONObject("optional_features")?.optString("plugin")) {
    "not_probed" -> SetupPluginPhase.Checking
    "absent" -> SetupPluginPhase.Absent
    // "unauthorized" is the plugin answering before this phone is linked, which signing in does on its own.
    "available", "unauthorized" -> SetupPluginPhase.Present
    // No probe yet, a failed probe or an unreadable one says nothing about the plugin, so the wizard does not stop for it.
    else -> SetupPluginPhase.Unknown
}

/**
 * Whether the wizard shows the plugin page instead of [step]. Only for a signed-in phone on the first call or permission page, until the
 * owner has moved past it; never for a Hermes that has Beacon or one the app could not read, and never mid-install-result for a Hermes
 * that has just gained it (the page stays until the owner continues).
 */
internal fun setupPluginStepShown(step: String, authenticated: Boolean, phase: SetupPluginPhase, install: PluginInstallUi, handled: Boolean, checkTimedOut: Boolean): Boolean {
    if (handled || !authenticated || step !in SETUP_PLUGIN_GATE_STEPS) return false
    return when (phase) {
        SetupPluginPhase.Checking -> !checkTimedOut && install.stage == PluginInstallStage.Idle
        SetupPluginPhase.Absent -> true
        SetupPluginPhase.Present -> install.stage != PluginInstallStage.Idle
        SetupPluginPhase.Unknown -> install.stage != PluginInstallStage.Idle
    }
}

internal enum class SetupPluginRow { Install, Answers, Restart }

internal enum class SetupPluginRowState { Waiting, Working, Done, Failed }

/**
 * The checklist for what the install is doing, from the states the install reports and nothing else: Installing covers the request and
 * the wait for Beacon to answer, Checking is that wait on its own, and the restart row exists only when Hermes asked for one. [restartSeen]
 * keeps that row once the restart has finished.
 */
internal fun setupPluginRows(install: PluginInstallUi, restartSeen: Boolean): List<Pair<SetupPluginRow, SetupPluginRowState>> {
    val installed = SetupPluginRow.Install
    val answers = SetupPluginRow.Answers
    val restart = SetupPluginRow.Restart
    return when (install.stage) {
        PluginInstallStage.Idle -> emptyList()
        PluginInstallStage.Installing -> listOf(installed to SetupPluginRowState.Working, answers to SetupPluginRowState.Waiting)
        PluginInstallStage.Checking -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Working)
        PluginInstallStage.NeedsRestart -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Done, restart to SetupPluginRowState.Waiting)
        PluginInstallStage.Restarting -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Done, restart to SetupPluginRowState.Working)
        PluginInstallStage.Done -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Done) + if (restartSeen) listOf(restart to SetupPluginRowState.Done) else emptyList()
        PluginInstallStage.Failed -> when (install.failure) {
            // The request went through; Beacon did not answer in time.
            PluginInstallFailure.NotYet -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Failed)
            // Beacon answers; the restarted gateway is late.
            PluginInstallFailure.GatewayDown -> listOf(installed to SetupPluginRowState.Done, answers to SetupPluginRowState.Done, restart to SetupPluginRowState.Failed)
            else -> listOf(installed to SetupPluginRowState.Failed, answers to SetupPluginRowState.Waiting)
        }
    }
}

/** Every button the page can show; [setupPluginActions] decides which ones go together. */
internal enum class SetupPluginAction { Install, TryAgain, SignInAgain, Skip, Computer, Restart, RestartLater, ContinueAnyway, Continue }

/** The one primary button (null while the install works) and at most two quiet text actions under it. */
internal class SetupPluginActions(val primary: SetupPluginAction?, val text: List<SetupPluginAction> = emptyList())

/**
 * What the page offers for [install]. Try again appears only where trying again can change the outcome: Hermes refusing a non-owner or a
 * sign-in that has ended would fail the same way, and a Hermes without the install route has only the computer route. The text actions are
 * Skip for now and Set up from a computer, never more than two.
 */
internal fun setupPluginActions(install: PluginInstallUi): SetupPluginActions = when (install.stage) {
    PluginInstallStage.Idle -> SetupPluginActions(SetupPluginAction.Install, listOf(SetupPluginAction.Skip, SetupPluginAction.Computer))
    PluginInstallStage.Installing, PluginInstallStage.Checking, PluginInstallStage.Restarting -> SetupPluginActions(null)
    PluginInstallStage.NeedsRestart -> SetupPluginActions(SetupPluginAction.Restart, listOf(SetupPluginAction.RestartLater))
    PluginInstallStage.Done -> SetupPluginActions(SetupPluginAction.Continue)
    PluginInstallStage.Failed -> when (install.failure) {
        PluginInstallFailure.Unsupported -> SetupPluginActions(SetupPluginAction.Computer, listOf(SetupPluginAction.Skip))
        PluginInstallFailure.Denied -> SetupPluginActions(SetupPluginAction.Skip, listOf(SetupPluginAction.Computer))
        PluginInstallFailure.SignIn -> SetupPluginActions(SetupPluginAction.SignInAgain, listOf(SetupPluginAction.Skip, SetupPluginAction.Computer))
        // The plugin answers and only the restarted gateway is late: the owner can move on, as in the install card.
        PluginInstallFailure.GatewayDown -> SetupPluginActions(SetupPluginAction.TryAgain, listOf(SetupPluginAction.ContinueAnyway))
        else -> SetupPluginActions(SetupPluginAction.TryAgain, listOf(SetupPluginAction.Skip, SetupPluginAction.Computer))
    }
}

internal fun setupPluginCanRetry(install: PluginInstallUi): Boolean = setupPluginActions(install).primary == SetupPluginAction.TryAgain

@Composable private fun setupPluginFailureText(failure: PluginInstallFailure?): String = when (failure) {
    PluginInstallFailure.SignIn -> uiText("Hermes needs you to sign in again.")
    PluginInstallFailure.Denied -> uiText("Only the owner of this Hermes can add the Beacon plugin.")
    PluginInstallFailure.Offline -> uiText("Can't reach Hermes. Check your connection, then try again.")
    PluginInstallFailure.Unsupported -> uiText("This Hermes can't add plugins from the app.")
    PluginInstallFailure.Busy -> uiText("Hermes is busy. Wait a moment and try again.")
    PluginInstallFailure.NotYet, PluginInstallFailure.GatewayDown -> uiText("Hermes isn't answering yet. Try again in a moment.")
    PluginInstallFailure.Error, null -> uiText("Hermes couldn't do that. Try again.")
}

@Composable private fun SetupPluginRowLine(row: SetupPluginRow, state: SetupPluginRowState) {
    val label = when (row) {
        SetupPluginRow.Install -> uiText("Install the Beacon plugin")
        SetupPluginRow.Answers -> uiText("Check that it answers")
        SetupPluginRow.Restart -> uiText("Restart Hermes")
    }
    val word = when (state) {
        SetupPluginRowState.Waiting -> uiText("Waiting")
        SetupPluginRowState.Working -> uiText("Working")
        SetupPluginRowState.Done -> uiText("Done")
        SetupPluginRowState.Failed -> uiText("Failed")
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("setup-plugin-row-${row.name.lowercase()}").semantics(mergeDescendants = true) { stateDescription = word },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (state) {
                SetupPluginRowState.Working -> BeaconDustCore(24.dp)
                SetupPluginRowState.Done -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                SetupPluginRowState.Failed -> Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                SetupPluginRowState.Waiting -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (state == SetupPluginRowState.Waiting) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The page: one check while the probe runs, then Install, a live checklist of what Hermes is doing, and Continue. Every outcome shown is one
 * the install reported; a failure keeps the page, offers only the actions that can help (see [setupPluginActions]), and never claims the
 * Beacon plugin is there. The header's X closes setup from every state except while the install is working.
 */
@Composable
internal fun SetupPluginStepPage(
    modifier: Modifier,
    phase: SetupPluginPhase,
    install: PluginInstallUi,
    restartSeen: Boolean,
    onInstall: () -> Unit,
    onRetry: () -> Unit,
    onRestart: () -> Unit,
    onNotNow: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    onPcInstead: () -> Unit,
    onCancel: () -> Unit,
    onSignIn: () -> Unit = {},
) {
    // The wizard counts this page with Sign in, so the bar and the step number stay where sign-in left them.
    val step = 2
    val buttons = SetupPluginClicks(onInstall, onRetry, onSignIn, onRestart, onNotNow, onContinue, onSkip, onPcInstead)
    CompositionLocalProvider(LocalSetupClose provides SetupClose(enabled = !install.working, onClose = onCancel)) {
        when {
            install.stage == PluginInstallStage.Idle && phase == SetupPluginPhase.Checking -> SetupShell(
                step, uiText("Checking for the Beacon plugin…"), modifier,
                content = { Box(Modifier.fillMaxWidth().testTag("setup-plugin-checking"), contentAlignment = Alignment.Center) { BeaconDustCore(40.dp) } },
            )
            install.stage == PluginInstallStage.Idle -> SetupShell(
                step, uiText("Add the Beacon plugin to Hermes"), modifier, uiText("Hermes downloads the Beacon plugin from GitHub and turns it on."),
                content = {},
                actions = { SetupPluginButtons(install, buttons) },
            )
            else -> {
                val rows = setupPluginRows(install, restartSeen)
                val offer = install.stage == PluginInstallStage.NeedsRestart
                val title = when {
                    install.stage == PluginInstallStage.Done -> uiText("The Beacon plugin is on your Hermes")
                    offer -> uiText("Restart Hermes to finish")
                    else -> uiText("Add the Beacon plugin to Hermes")
                }
                // The checklist already says what happened, so Done needs no body.
                val body = if (offer) {
                    uiText(if (install.busy) "The Beacon plugin is installed. Hermes is working right now; restarting stops that work." else "The Beacon plugin is installed. Restart Hermes to finish turning it on. Anything Hermes is doing right now will stop.")
                } else ""
                SetupShell(
                    step, title, modifier, body,
                    content = {
                        SetupContainer(Modifier.testTag("setup-plugin-rows")) { rows.forEach { (row, state) -> SetupPluginRowLine(row, state) } }
                        // A failure, or a restart that did not start: the real reason, announced, with the way forward below.
                        if (install.stage == PluginInstallStage.Failed || (offer && install.failure != null)) {
                            SetupStatusLine(setupPluginFailureText(install.failure), error = true, modifier = Modifier.testTag("setup-plugin-error"))
                        }
                    },
                    actions = { SetupPluginButtons(install, buttons) },
                )
            }
        }
    }
}

/** What each button does; Restart later and Continue anyway both leave the restart for later. */
private class SetupPluginClicks(
    val install: () -> Unit, val retry: () -> Unit, val signIn: () -> Unit, val restart: () -> Unit,
    val notNow: () -> Unit, val next: () -> Unit, val skip: () -> Unit, val computer: () -> Unit,
) {
    fun of(action: SetupPluginAction): () -> Unit = when (action) {
        SetupPluginAction.Install -> install
        SetupPluginAction.TryAgain -> retry
        SetupPluginAction.SignInAgain -> signIn
        SetupPluginAction.Skip -> skip
        SetupPluginAction.Computer -> computer
        SetupPluginAction.Restart -> restart
        SetupPluginAction.RestartLater, SetupPluginAction.ContinueAnyway -> notNow
        SetupPluginAction.Continue -> next
    }
}

@Composable
private fun SetupPluginLabel(action: SetupPluginAction): String = when (action) {
    SetupPluginAction.Install -> uiText("Install Beacon")
    SetupPluginAction.TryAgain -> uiText("Try again")
    SetupPluginAction.SignInAgain -> uiText("Sign in again")
    SetupPluginAction.Skip -> uiText("Skip for now")
    SetupPluginAction.Computer -> uiText("Set up from a computer")
    SetupPluginAction.Restart -> uiText("Restart Hermes")
    SetupPluginAction.RestartLater -> uiText("Restart later")
    SetupPluginAction.ContinueAnyway -> uiText("Continue anyway")
    SetupPluginAction.Continue -> uiText("Continue")
}

private fun setupPluginTag(action: SetupPluginAction): String = when (action) {
    SetupPluginAction.Install -> "setup-plugin-install"
    SetupPluginAction.TryAgain -> "setup-plugin-retry"
    SetupPluginAction.SignInAgain -> "setup-plugin-signin"
    SetupPluginAction.Skip -> "setup-plugin-skip"
    SetupPluginAction.Computer -> "setup-plugin-pc"
    SetupPluginAction.Restart -> "setup-plugin-restart"
    SetupPluginAction.RestartLater, SetupPluginAction.ContinueAnyway -> "setup-plugin-not-now"
    SetupPluginAction.Continue -> "setup-plugin-continue"
}

/** The primary button and text actions [setupPluginActions] picks for the install, or the working button with its own label while it runs. */
@Composable
private fun ColumnScope.SetupPluginButtons(install: PluginInstallUi, clicks: SetupPluginClicks) {
    if (install.working) {
        SetupPrimaryButton(
            when (install.stage) {
                PluginInstallStage.Checking -> uiText("Checking…")
                PluginInstallStage.Restarting -> uiText("Restarting…")
                else -> uiText("Installing…")
            },
            {}, Modifier.testTag("setup-plugin-working"), loading = true,
        )
        return
    }
    val actions = setupPluginActions(install)
    actions.primary?.let { SetupPrimaryButton(SetupPluginLabel(it), clicks.of(it), Modifier.testTag(setupPluginTag(it))) }
    for (action in actions.text) SetupTextAction(SetupPluginLabel(action), clicks.of(action), Modifier.testTag(setupPluginTag(action)))
}
