package dev.hermes.android.assist

import android.content.Context
import android.content.Intent
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication

/*
 * The overlay's only touch points with the call engine (owned by the voice lane) and with the app's own screen. Each is one
 * function, so the engine side can be wired in one line when it lands.
 */

/** Tells the call engine the overlay opened or closed, so a call from it starts on an already-connected session. */
internal fun assistPrewarm(visible: Boolean) = dev.hermes.android.voice.VoicePrewarm.hint(visible, "assist-overlay")

/** Whether a live call takes typed turns (`CallController.sendText`); the overlay shows its keyboard control only then. */
internal const val LIVE_TYPING = true

/** Sends one typed turn into the live call [callId]; true only when the live session took it. */
internal fun sendTypedTurn(app: BeaconApplication, callId: String, text: String): Boolean = app.calls.sendText(callId, text)

/**
 * Opens the app for [target]. The token is what lets BeaconActivity serve it once; the extras carry the same purpose in case the
 * process restarts before the app reads it. Callers on the lock screen go through [AssistUnlockActivity] first.
 */
internal fun openBeacon(context: Context, target: AssistTarget) {
    context.startActivity(Intent(context, BeaconActivity::class.java)
        .setAction(ACTION_ASSIST_CALL)
        .putExtra(EXTRA_ASSIST_TOKEN, AssistGestures.issue(target = target))
        .putExtra(EXTRA_ASSIST_PURPOSE, target.purpose.name)
        .putExtra(EXTRA_ASSIST_SESSION, target.session)
        .putExtra(EXTRA_ASSIST_PROFILE, target.profile)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
}

/** Android's screen for Beacon's permissions, where the microphone is allowed. */
internal fun openBeaconPermissions(context: Context) {
    val details = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(details) }.isFailure) openBeacon(context, AssistTarget(AssistPurpose.OpenAssistant))
}
