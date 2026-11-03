package dev.hermes.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.CallToolApprovalDialog
import dev.hermes.android.phone.PhoneCountdown
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.deviceLockedNow
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.PhoneApprovalText
import dev.hermes.android.voice.callToolApprovalErrorMessage
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One running countdown for a call the owner asked for in a live call or the assistant overlay: [left] whole seconds to go, [elapsed]
 * milliseconds so far, and the two ways to answer early. The call is placed when the countdown ends; [cancel], [dismiss] (leaving the
 * card), leaving the screen or the call ending first is a no: nothing is dialled. [dismiss] and the countdown answer once.
 */
internal class CountdownRun(val seconds: Int, val elapsed: Long, val left: Int, val cancel: () -> Unit, val callNow: () -> Unit, val dismiss: () -> Unit)

/** The lock screen is up: a phone that is locked (or turning its screen off) places no call by itself. */
@Composable
internal fun rememberKeyguardLocked(): () -> Boolean {
    val context = LocalContext.current
    return remember(context) { { deviceLockedNow(context) } }
}

/** How often (in 40 ms steps) a running countdown looks at the lock screen. */
private const val LOCK_CHECK_EVERY = 6

/**
 * Runs [approval]'s countdown while the composable that remembers it is on screen, and null for an approval that has none. It only
 * counts while it is on screen, and leaving the screen (the lifecycle stopping), the screen turning off or the phone locking is a
 * no: [locked] says whether the lock screen is up (the phone's own state unless a test gives another).
 */
@Composable
internal fun rememberCountdownRun(
    approval: CallToolApproval,
    onDecision: (String) -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime,
    locked: () -> Boolean = rememberKeyguardLocked(),
): CountdownRun? {
    val card = approval.phone ?: return null
    if (card.countdown <= 0) return null
    // A card that waited before it was drawn (the screen was off) is old news: no countdown, the caller shows the plain Allow / Deny.
    val seconds = remember(approval.requestId) { PhoneCountdown.secondsAt(card.countdown, card.askedAt, now()) }
    if (seconds == 0) return null
    var elapsed by remember(approval.requestId) { mutableLongStateOf(0L) }
    // The first choice wins: the countdown ending, a button or leaving the screen each answer once.
    val answered = remember(approval.requestId) { AtomicBoolean(false) }
    fun answer(choice: String) { if (answered.compareAndSet(false, true)) onDecision(choice) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, approval.requestId) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) answer("deny") }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // The overlay is no activity, so nothing stops it when the screen goes off: the broadcast does.
    val context = LocalContext.current
    DisposableEffect(context, approval.requestId) {
        val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { answer("deny") } }
        val registered = runCatching { ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        onDispose { if (registered) runCatching { context.unregisterReceiver(screenOff) } }
    }
    val lockedNow by rememberUpdatedState(locked)
    LaunchedEffect(approval.requestId) {
        val started = now()
        var steps = 0
        while (!answered.get()) {
            if (steps++ % LOCK_CHECK_EVERY == 0 && lockedNow()) { answer("deny"); break }
            elapsed = now() - started
            if (PhoneCountdown.finished(seconds, elapsed)) { answer("approve"); break }
            delay(40)
        }
    }
    return CountdownRun(seconds, elapsed, PhoneCountdown.remaining(seconds, elapsed),
        cancel = { answered.set(true); onDecision("deny") }, callNow = { answered.set(true); onDecision("approve") }, dismiss = { answer("deny") })
}

/**
 * Says the card of [requestId] is really on screen: drawn while [shown] and its screen is started. It takes that back when the card
 * leaves, [shown] ends or the screen stops, so [report] always matches what the owner can see. The call posts a notification for each
 * approval whose card is not reported. A locked overlay draws no card, so it passes `shown = false`.
 */
@Composable
internal fun ApprovalCardOnScreen(requestId: String, report: (String, Boolean) -> Unit, shown: Boolean = true) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, requestId, shown) {
        var reported = false
        fun sync() {
            val want = shown && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (want != reported) { reported = want; report(requestId, want) }
        }
        val observer = LifecycleEventObserver { _, _ -> sync() }
        owner.lifecycle.addObserver(observer)
        sync()
        onDispose {
            owner.lifecycle.removeObserver(observer)
            if (reported) { reported = false; report(requestId, false) }
        }
    }
}

/** "Calling in 3": announced as it changes. */
@Composable
internal fun PhoneCountdownTitle(run: CountdownRun) {
    Text(PhoneLine(uiText("Calling in {0}"), listOf(run.left.toString())).text(),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("phone-countdown-title"))
}

/** Who is called and the number in full, any detail, the countdown's bar and a failed answer's reason. */
@Composable
internal fun PhoneCountdownDetails(approval: CallToolApproval, card: PhoneApprovalText, run: CountdownRun) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(card.action, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("phone-countdown-who"))
        if (card.target.isNotBlank()) Text(card.target, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("phone-countdown-number"))
        if (card.details.isNotBlank()) Text(card.details, style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { (run.elapsed / (run.seconds * 1000f)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if (approval.error.isNotBlank()) Text(callToolApprovalErrorMessage(approval.error), color = MaterialTheme.colorScheme.error)
    }
}

/** The large Cancel, then Call now. */
@Composable
internal fun PhoneCountdownButtons(approval: CallToolApproval, run: CountdownRun) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = run.cancel, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("phone-countdown-cancel")) {
            Text(uiText("Cancel"), style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = run.callNow, enabled = !approval.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("phone-countdown-now")) { Text(uiText("Call now")) }
    }
}

/**
 * The card for a call the owner asked for in a live call or the assistant overlay: who and which number, a visible "Calling in 3, 2, 1",
 * a large Cancel and Call now. The call is placed when the countdown ends. Cancel, Back, leaving the screen or the call ending first
 * is a no: nothing is dialled. The countdown only runs while this card is on screen, and only for a card still fresh when it is drawn;
 * an older one shows the ordinary Allow / Deny card.
 */
@Composable
internal fun PhoneCallCountdownDialog(approval: CallToolApproval, onDecision: (String) -> Unit, now: () -> Long = SystemClock::elapsedRealtime, locked: () -> Boolean = rememberKeyguardLocked()) {
    val card = approval.phone ?: return
    val run = rememberCountdownRun(approval, onDecision, now, locked)
        ?: kotlin.run { CallToolApprovalDialog(approval.copy(phone = card.copy(countdown = 0)), onDecision); return }
    AlertDialog(
        onDismissRequest = run.dismiss,
        icon = { Icon(Icons.Rounded.Call, null) },
        title = { PhoneCountdownTitle(run) },
        text = { PhoneCountdownDetails(approval, card, run) },
        confirmButton = { PhoneCountdownButtons(approval, run) },
        modifier = Modifier.testTag("phone-countdown"),
    )
}
