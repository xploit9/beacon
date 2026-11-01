package dev.hermes.android.assist

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.currentAssistantName
import dev.hermes.android.ui.SettingsNavRow
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.openAppInfo
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.delay

/** Whether this phone lets the owner pick an assistant, and whether Beacon is the one picked. */
internal enum class PhoneAssistantState { Unavailable, NotSet, Active }

internal fun phoneAssistantState(context: Context): PhoneAssistantState {
    val roles = context.getSystemService(RoleManager::class.java) ?: return PhoneAssistantState.Unavailable
    return runCatching {
        when {
            !roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT) -> PhoneAssistantState.Unavailable
            roles.isRoleHeld(RoleManager.ROLE_ASSISTANT) -> PhoneAssistantState.Active
            else -> PhoneAssistantState.NotSet
        }
    }.getOrDefault(PhoneAssistantState.Unavailable)
}

/** Android's own "Digital assistant app" screen, then the list of default apps. An app cannot ask for this role itself. */
internal val assistantSettingsActions = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)

/** Opens the first of those screens the phone has, else Beacon's own app screen. False only if none opened. */
internal fun openAssistantSettings(context: Context): Boolean {
    for (action in assistantSettingsActions) if (runCatching { context.startActivity(Intent(action)) }.isSuccess) return true
    return openAppInfo(context)
}

/** The state shown on the Assistant page. The owner changes it in Android's settings, so it is read again every time Beacon comes back to the front. */
@Composable internal fun rememberPhoneAssistantState(): PhoneAssistantState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(phoneAssistantState(context)) }
    LifecycleResumeEffect(context) { state = phoneAssistantState(context); onPauseOrDispose { } }
    return state
}

/** Settings › Assistant: one row that says whether Beacon answers the phone's assist gesture; [onOpen] goes to Android's picker. */
@Composable internal fun PhoneAssistantSection(state: PhoneAssistantState, onOpen: () -> Unit) {
    val active = state == PhoneAssistantState.Active
    SettingsSection(testTag = "phone-assistant-section") {
        SettingsNavRow(uiText("Phone assistant"), onOpen,
            icon = Icons.Rounded.AutoAwesome, status = if (active) SettingsStatus.Ok else SettingsStatus.Off,
            statusLabel = if (active) uiText("Active") else uiText("Not set"), external = true, testTag = "phone-assistant")
    }
}

/**
 * Serves one assist gesture once the app is ready: starts the assistant's call through the chat's own entry, brings a running
 * call to the front, or stops at the sign-in screen. [issuedAt] is cleared through [onHandled] before anything starts.
 */
@Composable internal fun AssistLaunchEffect(vm: BeaconViewModel, issuedAt: Long?, onHandled: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val call by vm.app.calls.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Read per gesture: the owner may have picked or dropped Beacon as the assistant since the last one.
    val assistantRole = androidx.compose.runtime.remember(issuedAt) { phoneAssistantState(context) == PhoneAssistantState.Active }
    val readiness = AssistReadiness(
        restoring = ui.restoringSession, loading = ui.loading, authenticated = ui.authenticated, setupWizard = ui.setupWizard,
        callActive = call.active, pluginAbsent = ui.bootstrap.optJSONObject("optional_features")?.optString("plugin") == "absent",
        assistantRole = assistantRole,
    )
    val handled by androidx.compose.runtime.rememberUpdatedState(onHandled)
    LaunchedEffect(issuedAt, readiness) {
        val at = issuedAt ?: return@LaunchedEffect
        var step = assistStep(readiness, at, System.currentTimeMillis())
        if (step == AssistStep.Wait) {
            // Nothing changes for the whole window: the app did not become ready in time.
            delay((at + ASSIST_WINDOW_MILLIS + 1 - System.currentTimeMillis()).coerceAtLeast(0))
            step = AssistStep.Expired
        }
        handled()
        when (step) {
            AssistStep.Call -> vm.callBeacon()
            AssistStep.ShowCall -> vm.openCurrentCall()
            AssistStep.OpenChat -> vm.navigate("Beacon")
            AssistStep.Expired -> vm.notice("$currentAssistantName didn't open in time. Try again.")
            AssistStep.SignIn, AssistStep.Wait -> Unit
        }
    }
}
