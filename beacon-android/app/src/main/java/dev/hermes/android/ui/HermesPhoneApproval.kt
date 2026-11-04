package dev.hermes.android.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.hermes.android.assist.ContentDirection
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.phone.HermesPhoneRequests
import dev.hermes.android.phone.PhoneCountdown
import dev.hermes.android.phone.PhoneOutcome
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.PhoneApprovalText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How often an open Beacon looks for new phone requests and for a change in what this phone allows. */
internal const val PHONE_REQUEST_POLL_MILLIS = 5_000L

/**
 * Shows the card for the oldest phone request Hermes is waiting on, and keeps Hermes told what this phone allows while Beacon is open.
 * Pushes bring requests when Beacon is closed; this is the way in when the owner taps that notification. While it is in front, a
 * request that opens another app's screen may run without a card once "Ask before Hermes acts" is off; a call to a saved contact then
 * shows the call countdown (Cancel, Call now) instead of Allow and Deny.
 */
@Composable
internal fun HermesPhoneApprovalHost(client: BeaconClient?) {
    val context = LocalContext.current
    val cards by HermesPhoneRequests.cards.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        if (client == null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            HermesPhoneRequests.setFront(true)
            try {
                while (true) {
                    HermesPhoneRequests.keepCurrent(context, client)
                    delay(PHONE_REQUEST_POLL_MILLIS)
                }
            } finally { HermesPhoneRequests.setFront(false) }
        }
    }
    // Only the signed-in account's requests are shown; another account's wait for its own sign-in.
    val card = cards.firstOrNull { client != null && it.scope.sameAccount(client.scope) } ?: return
    var later by remember { mutableStateOf(emptySet<String>()) }
    if (card.request.id in later) return
    var busy by remember(card.request.id) { mutableStateOf(false) }
    var ended by remember(card.request.id) { mutableStateOf(false) }
    var retry by remember(card.request.id) { mutableStateOf(false) }
    var locked by remember(card.request.id) { mutableStateOf(HermesPhoneRequests.locked(context)) }
    var now by remember(card.request.id) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(card.request.id) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            locked = HermesPhoneRequests.locked(context)
            delay(1_000)
        }
    }
    val scope = rememberCoroutineScope()
    // A call that counts down does so once, at the first draw, and only while the card is fresh and the phone unlocked; after the
    // countdown's answer leaves the card on screen (locked, no answer from Hermes, ended) the ordinary Allow and Deny take over.
    var countdownOver by remember(card.request.id) { mutableStateOf(false) }
    val counting = remember(card.request.id) {
        PhoneCountdown.startsAtFirstDraw(card.held.countdown, card.held.askedAt, SystemClock.elapsedRealtime(), HermesPhoneRequests.locked(context))
    } && !countdownOver
    // The card a live call shows for the same request: the headline, who or where, the details and the notes or message, whole.
    val shown = card.card
    val headline = phoneLineText(shown.headline)
    val details = shown.details().map { phoneLineText(it) }
    val onAllow = {
        busy = true
        retry = false
        scope.launch {
            val outcome = HermesPhoneRequests.approve(context, card)
            busy = false
            if (outcome == PhoneOutcome.Locked) locked = true
            if (outcome == PhoneOutcome.Retry) retry = true
            if (outcome == PhoneOutcome.Expired || outcome == PhoneOutcome.Gone) ended = true
            if (outcome in setOf(PhoneOutcome.Locked, PhoneOutcome.Retry, PhoneOutcome.Expired, PhoneOutcome.Gone)) countdownOver = true
        }
        Unit
    }
    val onDeny = {
        busy = true
        retry = false
        scope.launch {
            val outcome = HermesPhoneRequests.deny(context, card)
            busy = false
            if (outcome == PhoneOutcome.Retry) retry = true
            if (outcome == PhoneOutcome.Expired || outcome == PhoneOutcome.Gone) ended = true
            if (outcome in setOf(PhoneOutcome.Retry, PhoneOutcome.Expired, PhoneOutcome.Gone)) countdownOver = true
        }
        Unit
    }
    if (counting && !ended && card.request.deadline > now) {
        PhoneCallCountdownDialog(
            CallToolApproval(card.request.id, card.request.id, card.request.tool, "", busy = busy,
                phone = PhoneApprovalText(headline, shown.target, details.joinToString("\n"), shown.preview, card.held.countdown, card.held.askedAt)),
            onDecision = { choice -> if (choice == "deny") onDeny() else onAllow() },
        )
        return
    }
    PhoneApprovalCard(
        headline = headline,
        expired = ended || card.request.deadline <= now,
        locked = locked,
        busy = busy,
        onAllow = onAllow,
        onDeny = onDeny,
        onClose = { HermesPhoneRequests.dismiss(context, card) },
        onLater = { later = later + card.request.id },
        target = shown.target,
        details = details,
        preview = shown.preview,
        retry = retry,
    )
}

/**
 * The card the owner sees before Hermes uses their phone: exactly what it will do, with Allow and Deny. Allow waits for an unlocked
 * phone; an expired request can only be closed. [retry] says the answer did not get through and the owner can tap again.
 */
@Composable
internal fun PhoneApprovalCard(
    headline: String,
    expired: Boolean,
    locked: Boolean,
    busy: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onClose: () -> Unit,
    onLater: () -> Unit = {},
    target: String = "",
    details: List<String> = emptyList(),
    preview: String = "",
    retry: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = if (expired) onClose else onLater,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(uiText("Hermes wants to use your phone")) },
        text = {
            val scroll = rememberScrollState()
            // A fade at the bottom shows while there is more to read; Deny and Allow stay in the dialog's own button row.
            Column(Modifier.heightIn(max = 420.dp).verticalScrollFade(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("phone-request-line"))
                if (target.isNotBlank()) Text(target, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("phone-request-target"))
                details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("phone-request-detail")) }
                if (preview.isNotBlank()) {
                    // The notes or message read in their own direction, whatever the interface language.
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Text(preview, Modifier.fillMaxWidth().padding(12.dp).testTag("phone-request-preview"), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection))
                    }
                }
                if (expired) Text(uiText("This request expired."), modifier = Modifier.testTag("phone-request-note"))
                else if (locked) Text(uiText("Unlock your phone to allow this."), modifier = Modifier.testTag("phone-request-note"))
                else if (retry) Text(uiText("Hermes didn't answer. Try again."), modifier = Modifier.testTag("phone-request-note"))
            }
        },
        confirmButton = {
            if (expired) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-request-close")) { Text(uiText("Done")) }
            else TextButton(onClick = onAllow, enabled = !locked && !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-request-allow")) { Text(uiText("Allow")) }
        },
        dismissButton = {
            if (!expired) TextButton(onClick = onDeny, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-request-deny")) { Text(uiText("Deny")) }
        },
    )
}
