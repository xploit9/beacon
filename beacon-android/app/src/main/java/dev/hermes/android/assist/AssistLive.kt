package dev.hermes.android.assist

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.PhoneCountdownButtons
import dev.hermes.android.ui.PhoneCountdownDetails
import dev.hermes.android.ui.PhoneCountdownTitle
import dev.hermes.android.ui.rememberCountdownRun
import dev.hermes.android.ui.rememberKeyguardLocked
import dev.hermes.android.ui.verticalScrollFade
import dev.hermes.android.ui.uiText
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.callStatusLabel
import dev.hermes.android.voice.callToolApprovalErrorMessage
import dev.hermes.android.voice.callToolApprovalSummary
import dev.hermes.android.voice.statusLabel
import kotlin.math.PI
import kotlin.math.sin

/*
 * The live conversation's pieces in the overlay. There is no window of its own for it: the text box stays, its orb shows the
 * conversation, and what the conversation needs from the owner (words, tool activity, an approval) sits directly above the text box.
 * Everything here is stateless, so the same composables run in the overlay and in a Compose test.
 */

/**
 * What sits above the text box while the conversation is live, top to bottom: the approval it waits on ([show] says whether the whole
 * card or, locked, only that something waits), then the two caption lines with Hermes' tool [activity] as one short line under them.
 * Nothing is drawn for a quiet conversation, so the text box is alone until there are words. It takes what the text box leaves of the
 * height and no more: the card scrolls inside its room with the answers pinned, so a short or sideways screen never loses the text box.
 * On a locked phone ([phoneLocked], or the phone's own lock screen read now through [locked]) the owner's words, the reply and the tool in
 * use are private: they are not composed at all, so nothing of them is on the window for anyone, or any service, to read. The text box and
 * its orb still show the state, and the words come back when the phone is unlocked.
 */
@Composable internal fun ColumnScope.LiveAbovePill(
    captions: List<Caption>,
    activity: String?,
    approval: CallToolApproval?,
    show: ApprovalShow,
    onDecision: (String) -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime,
    locked: () -> Boolean = rememberKeyguardLocked(),
    phoneLocked: Boolean = false,
) {
    val card = show == ApprovalShow.Card && approval != null
    // With the card up, the note it carries gets the room: one caption line and no tool line above the text box.
    val (shownCaptions, shownActivity) = liveWords(captions, activity, card)
    val words = !(phoneLocked || locked()) && (shownCaptions.isNotEmpty() || shownActivity != null)
    if (!card && show != ApprovalShow.Locked && !words) return
    // Weighted, so the text box (placed first) keeps its height and this block gets the rest.
    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (show) {
            ApprovalShow.Card -> if (approval != null) AssistApprovalCard(approval, onDecision, now, locked)
            ApprovalShow.Locked -> LockedApprovalRow { onDecision("deny") }
            ApprovalShow.None -> Unit
        }
        if (words) LiveCaptions(shownCaptions, shownActivity)
    }
}

/**
 * What the caption card shows. While an approval [card] is on screen it is only the last caption line and no tool line, so the
 * request's note has the height; otherwise everything there is.
 */
internal fun liveWords(captions: List<Caption>, activity: String?, card: Boolean): Pair<List<Caption>, String?> =
    if (card) captions.takeLast(1) to null else captions to activity

/**
 * The conversation's state in words, for the text box and the orb: Listening, Thinking, Speaking. The tool in use has its own line
 * above the text box, so it is not named here too; on a [locked] phone, where that line is not drawn, a tool in use is only
 * "Working…", never which one: what the assistant is doing on the phone is not shown over the lock screen.
 */
internal fun liveStatusLabel(call: CallState, locked: Boolean): String =
    if (locked && call.toolReceipts.any { it.statusLabel() != null }) "Working…" else callStatusLabel(call, withTool = false)

/** The colour of the tool activity line: the theme's own [primary] on a light card, where the gold of a dark one is too pale to read. */
internal fun liveActivityColor(light: Boolean, primary: Color): Color = if (light) primary else Gold

/** The owner's words in the quieter colour, then the assistant's reply; Hermes' tool activity is one short line under them. */
@Composable internal fun LiveCaptions(captions: List<Caption>, activity: String?) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > .5f
    // The live region and the tag come first, so their bounds are the whole visible card (its padding included), as the text box's are.
    Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("assist-captions")
        .glass(RoundedCornerShape(24.dp), solid = true, halo = .2f).padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        captions.forEach { (owner, text) ->
            Text(text, Modifier.fillMaxWidth().testTag(if (owner) "assist-caption-owner" else "assist-caption-reply"),
                style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), color = if (owner) scheme.onSurfaceVariant else scheme.onSurface,
                maxLines = if (owner) 2 else 4, overflow = TextOverflow.Ellipsis)
        }
        if (activity != null) Text(activity, Modifier.fillMaxWidth().testTag("assist-live-activity"), style = MaterialTheme.typography.labelLarge, color = liveActivityColor(light, scheme.primary), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The card for what the conversation asks of the owner on the phone, in the overlay itself: a call about to be placed counts down
 * ("Calling in 3") with a large Cancel and Call now, any other request shows what will run with Deny and Allow once. The countdown only
 * runs while this card is on screen; closing the overlay is a no.
 */
@Composable internal fun AssistApprovalCard(
    approval: CallToolApproval,
    onDecision: (String) -> Unit,
    now: () -> Long = SystemClock::elapsedRealtime,
    locked: () -> Boolean = rememberKeyguardLocked(),
) {
    val card = approval.phone
    val scroll = rememberScrollState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .55f).dp
    // A stale countdown (the card waited before it was drawn) falls back to the plain request, like the app's dialog.
    val run = if (card != null && card.countdown > 0) rememberCountdownRun(approval, onDecision, now, locked) else null
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).glass(RoundedCornerShape(28.dp), solid = true, halo = .3f).testTag("assist-approval")) {
        // What the owner reads scrolls, whole; the answers stay pinned under it, so a long note never pushes them out of reach.
        // A fade at the bottom shows while there is more to read.
        Column(Modifier.weight(1f, fill = false).testTag("assist-approval-scroll").verticalScrollFade(scroll).verticalScroll(scroll).padding(start = 20.dp, top = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card != null && run != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Call, null, tint = Gold)
                    ProvideTextStyle(MaterialTheme.typography.titleMedium) { PhoneCountdownTitle(run) }
                }
                PhoneCountdownDetails(approval, card, run)
            } else RequestApprovalBody(approval)
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (card != null && run != null) PhoneCountdownButtons(approval, run) else RequestApprovalButtons(approval, onDecision)
        }
    }
}

/** What a request that waits for a yes will do, whole: the action, who or where, the details and the notes or message, never cut. */
@Composable private fun RequestApprovalBody(approval: CallToolApproval) {
    val scheme = MaterialTheme.colorScheme
    val summary = remember(approval.toolName, approval.arguments, approval.phone) { callToolApprovalSummary(approval) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.VerifiedUser, null, tint = Gold)
        Text(uiText("Allow this action?"), style = MaterialTheme.typography.titleMedium)
    }
    Text(summary.action, Modifier.testTag("assist-approval-action"), style = MaterialTheme.typography.titleLarge.merge(ContentDirection))
    if (summary.target.isNotBlank()) Text(summary.target, Modifier.testTag("assist-approval-target"), style = MaterialTheme.typography.bodyLarge.merge(ContentDirection))
    if (summary.fields.isNotBlank()) Text(summary.fields, Modifier.testTag("assist-approval-fields"), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), color = scheme.onSurfaceVariant)
    if (summary.preview.isNotBlank()) Text(summary.preview, Modifier.testTag("assist-approval-preview"), style = MaterialTheme.typography.bodyMedium.merge(ContentDirection), color = scheme.onSurfaceVariant)
}

/** Deny and Allow once (and, when the call allows it, Always allow during this call), with a failed answer's reason above them. */
@Composable private fun RequestApprovalButtons(approval: CallToolApproval, onDecision: (String) -> Unit) {
    if (approval.error.isNotBlank()) Text(callToolApprovalErrorMessage(approval.error), color = MaterialTheme.colorScheme.error)
    if (approval.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ onDecision("deny") }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assist-approval-deny"), enabled = !approval.busy) { Text(uiText("Deny")) }
        Button({ onDecision("approve") }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assist-approval-allow"), enabled = !approval.busy) { Text(uiText("Allow once")) }
        if (approval.allowCall) TextButton({ onDecision("approve_call") }, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("assist-approval-allow-call"), enabled = !approval.busy) {
            Text(uiText("Always allow during this call"))
        }
    }
}

/** Locked, the overlay says only that something waits, never what; the one thing offered is no. Allowing needs the phone unlocked. */
@Composable internal fun LockedApprovalRow(onDeny: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).glass(RoundedCornerShape(24.dp), solid = true, halo = .2f).padding(start = 16.dp)
        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("assist-approval-locked"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), tint = Gold)
        Text(uiText("Approval needed"), Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        TextButton(onDeny, Modifier.heightIn(min = 48.dp).testTag("assist-approval-deny")) { Text(uiText("Deny")) }
    }
}

/**
 * The orb's face during the conversation: the dust jewel with a ring round it that shows what the conversation is doing. Listening it
 * swells with the owner's voice, speaking with the assistant's; working, a gold arc turns round it; connecting, it breathes. With
 * reduced motion the ring holds still. [voice] is read while drawing, so the ring never recomposes.
 */
@Composable internal fun LiveOrb(face: OrbFace, clock: FloatState, voice: () -> Float, motion: Boolean, content: @Composable () -> Unit) {
    val lit by animateFloatAsState(if (face == OrbFace.Speaking) 1f else .55f, if (motion) tween(260) else snap(), label = "Orb lit")
    Box(Modifier.size(44.dp).drawBehind {
        val t = clock.floatValue
        val centre = Offset(size.width / 2f, size.height / 2f)
        val base = 20.dp.toPx()
        val level = voice().coerceIn(0f, 1f)
        when (face) {
            OrbFace.Listening, OrbFace.Speaking -> {
                drawCircle(Gold.copy(alpha = (.12f + .16f * level) * lit), base + 1.dp.toPx() + 2.dp.toPx() * level, centre)
                drawCircle(GoldBright.copy(alpha = (.45f + .45f * level) * lit.coerceAtLeast(.8f)), base + 1.dp.toPx() * level, centre, style = Stroke(1.5.dp.toPx()))
            }
            OrbFace.Working -> {
                val sweep = Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                val start = (t * 180f) % 360f
                drawArc(GoldBright.copy(alpha = .9f), start, 110f, false, Offset(centre.x - base, centre.y - base), Size(base * 2, base * 2), style = sweep)
            }
            OrbFace.Connecting -> {
                val breath = .5f + .5f * sin(t * 2f * PI.toFloat() * .5f)
                drawCircle(GoldBright.copy(alpha = .2f + .45f * breath), base, centre, style = Stroke(1.5.dp.toPx()))
            }
            OrbFace.Talk -> Unit
        }
    }, contentAlignment = Alignment.Center) { content() }
}
