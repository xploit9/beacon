// Beacon's own design: Hermes Relay has no agent card. The halo is BeaconSignal's motion (Components.kt) around ChatAvatar's mark.
package dev.hermes.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** What the persistent assistant is doing, most relevant first. */
internal enum class BeaconPresence { Idle, Working, OnCall }

internal fun beaconPresence(onCall: Boolean, working: Boolean): BeaconPresence =
    if (onCall) BeaconPresence.OnCall else if (working) BeaconPresence.Working else BeaconPresence.Idle

/** The words for a live state; [idle] (the last message, or the tagline) when there is none. */
@Composable internal fun beaconStatus(presence: BeaconPresence, idle: String): String = when (presence) {
    BeaconPresence.OnCall -> uiText("On a call")
    BeaconPresence.Working -> uiText("Working…")
    BeaconPresence.Idle -> idle
}

/**
 * Whether the assistant is on a call or running a turn. The flows are cut down to two booleans, so a streamed token or a call
 * meter reading does not recompose the history list. [rowWorking] is the history row's own running stream (a turn started elsewhere).
 */
@Composable internal fun rememberBeaconPresence(vm: BeaconViewModel, assistantSessionId: String, rowWorking: Boolean): BeaconPresence {
    val onCall by remember(vm) { vm.app.calls.state.map { it.active }.distinctUntilChanged() }.collectAsStateWithLifecycle(vm.app.calls.state.value.active)
    val streaming by remember(vm, assistantSessionId) {
        vm.chat.map { assistantSessionId.isNotBlank() && it.sessionId == assistantSessionId && it.streamId.isNotBlank() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(false)
    return beaconPresence(onCall, rowWorking || streaming)
}

/** A live state is the brand gold; the last message stays muted. */
@Composable internal fun BeaconStatusText(text: String, presence: BeaconPresence, style: TextStyle, modifier: Modifier = Modifier) {
    val live = presence != BeaconPresence.Idle
    Text(text, modifier, color = if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, style = style,
        fontWeight = if (live) FontWeight.Medium else null, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The assistant's mark in its halo: a slow pulse while idle, an orbiting point while it works or is on a call. BeaconSignal is
 * still when the phone removes animations; [animate] is false while the mark is off screen.
 */
@Composable internal fun BeaconPresenceMark(
    title: String, presence: BeaconPresence, modifier: Modifier = Modifier, size: Dp = 60.dp,
    animate: Boolean = true, unread: Boolean = false, description: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val avatar = size * .72f
    val avatarRadius = with(LocalDensity.current) { avatar.toPx() / 2f }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (animate) BeaconSignal(
            if (presence == BeaconPresence.Idle) BeaconMotionMode.Pulse else BeaconMotionMode.Sweep,
            // The avatar's disc is see-through, so the signal's own ring and dot are cut away behind it; only the halo shows around it.
            Modifier.drawWithContent { clipPath(Path().apply { addOval(Rect(center, avatarRadius)) }, ClipOp.Difference) { this@drawWithContent.drawContent() } },
            size,
        )
        ChatAvatar(title, "beacon", size = avatar, beacon = true, description = description)
        // Where the avatar's rim meets its top-right, cut from the card by a ring of the card's own surface.
        if (unread) Box(Modifier.align(Alignment.TopEnd).padding(top = size * .14f, end = size * .14f).size(12.dp)
            .border(2.dp, scheme.surfaceContainerHigh, CircleShape).padding(2.dp).clip(CircleShape).background(scheme.primary).testTag("unread-dot"))
    }
}

/**
 * Beacon in the drawer, above History: the always-there agent, not one more chat row. No pin, no time; the status line says what
 * it is doing, else the last message. [isOpen] outlines it in the brand gold while its tab is open.
 */
@Composable internal fun BeaconPresenceCard(
    title: String, idle: String, presence: BeaconPresence, unread: Boolean, isOpen: Boolean, animate: Boolean, onClick: () -> Unit,
    canCall: Boolean = false, onCall: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(24.dp)
    val name = plainTitle(title)
    val status = beaconStatus(presence, idle)
    val unreadLabel = uiText("Unread reply")
    // navigation-beacon is the tag of the drawer item this card replaces.
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp).testTag("navigation-beacon")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clip(shape).background(scheme.surfaceContainerHigh)
                // A soft diagonal wash, strongest behind the mark and gone before the text, so the text keeps its contrast.
                .background(Brush.linearGradient(0f to scheme.primary.copy(alpha = .12f), .6f to scheme.primary.copy(alpha = .03f), 1f to scheme.primary.copy(alpha = 0f)))
                .border(if (isOpen) BorderStroke(1.5.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = .6f)), shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { selected = isOpen; contentDescription = "$name, $status"; if (unread) stateDescription = unreadLabel }
                .testTag("history-beacon-row")
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BeaconPresenceMark(title, presence, animate = animate, unread = unread)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BeaconStatusText(status, presence, MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(10.dp))
            BeaconCallButton(enabled = canCall, onCall = onCall)
        }
    }
}

/** Calls the assistant straight from the drawer: a gold circle that gives a little under the finger. */
@Composable private fun BeaconCallButton(enabled: Boolean, onCall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .9f else 1f, label = "call-press")
    FilledIconButton(
        onClick = onCall, enabled = enabled, shape = CircleShape, interactionSource = press,
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
        modifier = Modifier.minimumInteractiveComponentSize().size(44.dp).graphicsLayer { scaleX = scale; scaleY = scale }.testTag("beacon-card-call"),
    ) { Icon(Icons.Rounded.Call, assistantText("Call Beacon"), Modifier.size(22.dp)) }
}
