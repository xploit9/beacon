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

/**
 * Settings › Assistant: one row that says whether Beacon answers the phone's assist gesture and what that gives; [onOpen] goes to
 * Android's picker. The gesture itself differs between phones, so the row never names one. Android's picker lists the app as
 * Beacon whatever the assistant is called, so that is the name the row asks the owner to choose.
 */
@Composable internal fun PhoneAssistantSection(state: PhoneAssistantState, onOpen: () -> Unit) {
    val active = state == PhoneAssistantState.Active
    SettingsSection(testTag = "phone-assistant-section") {
        SettingsNavRow(uiText("Phone assistant"), onOpen,
            icon = Icons.Rounded.AutoAwesome, value = if (active) uiText("Opens over any app") else uiText("Choose Beacon to open it over any app"),
            // Not set, the line already says what to do: a "Not set" pill beside it only squeezed it ("to open it over …").
            valueMaxLines = 2, status = if (active) SettingsStatus.Ok else null,
            statusLabel = if (active) uiText("Active") else "", external = true, valueTestTag = "phone-assistant-value", testTag = "phone-assistant")
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
    val activity = androidx.activity.compose.LocalActivity.current
    // The overlay's buttons name what they open; the gesture itself starts the call. A restarted process reads it from the intent.
    val target = androidx.compose.runtime.remember(issuedAt) {
        issuedAt?.let { at -> AssistGestures.target(at) ?: activity?.intent?.let { assistTargetOf(it.getStringExtra(EXTRA_ASSIST_PURPOSE), it.getStringExtra(EXTRA_ASSIST_SESSION), it.getStringExtra(EXTRA_ASSIST_PROFILE)) } } ?: AssistTarget()
    }
    LaunchedEffect(issuedAt, readiness) {
        val at = issuedAt ?: return@LaunchedEffect
        var step = assistStep(readiness, at, System.currentTimeMillis())
        if (step == AssistStep.Wait) {
            // Nothing changes for the whole window: the app did not become ready in time.
            delay((at + ASSIST_WINDOW_MILLIS + 1 - System.currentTimeMillis()).coerceAtLeast(0))
            step = AssistStep.Expired
        }
        handled()
        when (assistAction(step, target.purpose)) {
            // A phone that locked while the app got ready asks for the unlock first; the call starts only unlocked.
            AssistAction.StartCall -> if (dev.hermes.android.phone.deviceLockedNow(context)) AssistUnlockActivity.open(context, target) else vm.callBeacon()
            AssistAction.ShowCall -> vm.openCurrentCall()
            AssistAction.OpenAssistant -> vm.navigate("Beacon")
            AssistAction.OpenChat -> {
                vm.tab("Chat")
                vm.openConversation(org.json.JSONObject().put("session_id", target.session).put("profile", target.profile))
            }
            AssistAction.Expired -> vm.notice("$currentAssistantName didn't open in time. Try again.")
            AssistAction.None -> Unit
        }
    }
}
