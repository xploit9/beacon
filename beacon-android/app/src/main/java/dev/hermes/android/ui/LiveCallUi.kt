package dev.hermes.android.ui

import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import kotlinx.coroutines.delay




import androidx.compose.foundation.*

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.rounded.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha

import androidx.compose.ui.draw.clip



import androidx.compose.ui.graphics.*

import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.*

import androidx.compose.ui.unit.Constraints

import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp



import dev.hermes.android.assist.ContentDirection
import dev.hermes.android.voice.*



val LocalPersistentNavigation = staticCompositionLocalOf { false }

@Composable internal fun AdaptiveCallNavigation(drawer: DrawerState, showNavigation: Boolean, navigation: @Composable (Boolean) -> Unit, content: @Composable () -> Unit) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = persistentCallNavigation(maxWidth.value, scale)
        val persistent = wide && showNavigation
        val closedDrawer = rememberDrawerState(DrawerValue.Closed)
        CompositionLocalProvider(LocalPersistentNavigation provides persistent) {
            // Keep the content at one composition location when navigation changes.
            ModalNavigationDrawer(drawerState = if (wide) closedDrawer else drawer, gesturesEnabled = !wide, drawerContent = {
                if (!wide) ModalDrawerSheet(Modifier.widthIn(max = 360.dp), drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer) { navigation(false) }
            }) {
                Row(Modifier.fillMaxSize().testTag("beacon-adaptive-navigation")) {
                    if (persistent) {
                        Surface(Modifier.width(persistentCallNavigationWidthDp.dp).fillMaxHeight().testTag("beacon-persistent-navigation"), color = MaterialTheme.colorScheme.surfaceContainer) {
                            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { navigation(true) }
                        }
                        VerticalDivider()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
            }
        }
    }
}

/**
 * The call window: always exactly as tall as the space it is given, so the window itself never scrolls.
 * Space goes in this order: the [header] and the [controls] (always whole, the controls pinned to the bottom), the [notices] (tool window, sharing and error lines),
 * then the [captions] (they scroll inside their own card when they do not fit, and keep [minCaptions] even if the presence has to give way), and the [presence] takes the rest.
 * The presence is kept at [minPresence] while the captions can still shrink.
 * Without a presence (the landscape side pane) the stack is centred.
 */
@Composable private fun CallFit(
    modifier: Modifier = Modifier,
    minPresence: Dp = 96.dp,
    minCaptions: Dp = 88.dp,
    header: @Composable () -> Unit = {},
    presence: (@Composable () -> Unit)? = null,
    notices: @Composable () -> Unit = {},
    captions: @Composable () -> Unit = {},
    controls: @Composable () -> Unit = {},
) {
    Layout({
        Box(contentAlignment = Alignment.TopCenter) { header() }
        Box(contentAlignment = Alignment.TopCenter) { presence?.invoke() }
        Box(contentAlignment = Alignment.TopCenter) { notices() }
        Box(contentAlignment = Alignment.TopCenter) { captions() }
        Box(contentAlignment = Alignment.TopCenter) { controls() }
    }, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val gap = 8.dp.roundToPx()
        val hasPresence = presence != null
        val presenceFloor = if (hasPresence) minPresence.roundToPx() else 0
        var left = height
        fun slot(index: Int, room: Int): Placeable = measurables[index].measure(Constraints(minWidth = width, maxWidth = width, maxHeight = room.coerceAtLeast(0)))
        fun Placeable.spend(): Placeable { left -= this.height + if (this.height > 0) gap else 0; return this }
        val head = slot(0, left).spend()
        val foot = slot(4, left).spend()
        val note = slot(2, left).spend()
        // Captions keep their minimum while there is room, but never squeeze the presence under 56 dp: on a very short screen
        // at large text the captions give way instead (they scroll inside their card).
        val presenceMin = if (hasPresence) 56.dp.roundToPx() + gap else 0
        val caps = slot(3, minOf(left, maxOf(left - presenceFloor - gap, minOf(minCaptions.roundToPx(), left - presenceMin)))).spend()
        val body = measurables[1].measure(Constraints.fixed(width, if (hasPresence) left.coerceAtLeast(0) else 0))
        layout(width, height) {
            val stack = listOf(head, body, note, caps).filter { it.height > 0 }
            val used = stack.sumOf { it.height } + foot.height + gap * (stack.size - if (foot.height > 0) 0 else 1).coerceAtLeast(0)
            var y = if (hasPresence) 0 else ((height - used) / 2).coerceAtLeast(0)
            stack.forEach { it.placeRelative(0, y); y += it.height + gap }
            foot.placeRelative(0, if (hasPresence) height - foot.height else y)
        }
    }
}

@Composable internal fun LiveCallCaptions(call: CallState, modifier: Modifier = Modifier, compact: Boolean = false) {
    val lines = if (call.active) orderCallTranscriptLines(call.transcripts.filter { it.text.isNotBlank() && !it.interrupted }).takeLast(if (compact) 2 else 4) else emptyList()
    if (lines.isEmpty()) return
    val motion = rememberNativeMotionEnabled()
    val scroll = rememberScrollState()
    // The card is taller than its cap at large text, so keep the newest words in view as it grows.
    LaunchedEffect(scroll.maxValue) { if (motion) scroll.animateScrollTo(scroll.maxValue) else scroll.scrollTo(scroll.maxValue) }
    Surface(
        modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("beacon-live-captions"),
        shape = RoundedCornerShape(24.dp),
        color = CallSurface,
        contentColor = CallText,
    ) {
        Column(
            // No contentDescription here: it would replace the lines a live region reads out.
            // Scrolled, the first line would be half cut at the card's top edge: it fades out instead.
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).heightIn(max = if (compact) 128.dp else 236.dp).verticalScrollFade(scroll, top = true)
                .verticalScroll(scroll).semantics { if (lines.last().final) liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
        ) {
            lines.forEachIndexed { index, line ->
                val latest = index == lines.lastIndex
                val latestForRole = index == lines.indexOfLast { it.role == line.role }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(.94f).alpha(if (latest) 1f else .7f)) {
                        Text(
                            uiText(if (line.role == "user") "You" else dev.hermes.android.data.currentAssistantName),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (latest) CallGoldBright else CallMuted,
                        )
                        Text(
                            line.text,
                            color = if (latest) CallText else CallMuted,
                            // Each line reads in its own direction, whatever the interface language, as the overlay's captions do.
                            style = (if (latest) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium).merge(ContentDirection),
                            // The newest line is whole: the card scrolls, so cutting it would hide words the owner can reach anyway.
                            maxLines = if (latest) Int.MAX_VALUE else if (compact) 2 else 6,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = if (latestForRole && line.role == "user") Modifier.testTag("beacon-user-caption")
                                else if (latestForRole) Modifier.testTag("beacon-assistant-caption") else Modifier,
                        )
                    }
                }
            }
        }
    }
}

internal data class CallUiActions(
    val mute: () -> Unit,
    val camera: () -> Unit,
    val flip: () -> Unit,
    val allowBubble: () -> Unit,
    val end: () -> Unit,
    val route: (String) -> Unit,
    val screen: () -> Unit = {},
)

@Composable internal fun CallFailurePanel(reason: String, onRetry: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(CallBackground).testTag("beacon-call-failure-screen"), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(24.dp).testTag("beacon-call-failure-panel"),
            shape = RoundedCornerShape(28.dp), color = CallSurface) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(reason, Modifier.testTag("beacon-call-failure-reason").semantics { liveRegion = LiveRegionMode.Polite },
                    color = Color(0xffffb4ab), style = MaterialTheme.typography.bodyLarge)
                Button(onRetry, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-call-failure-retry")) { Text(uiText("Retry")) }
                OutlinedButton(onClose, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-call-failure-close")) { Text(uiText("Close")) }
            }
        }
    }
}

@Composable internal fun CallToolChip(receipt: CallToolReceipt, modifier: Modifier = Modifier) {
    val activity = receipt.chipLabel() ?: return
    Surface(
        modifier.widthIn(max = 620.dp).fillMaxWidth().testTag("beacon-call-tool-chip"),
        shape = RoundedCornerShape(18.dp),
        color = CallSurfaceRaised,
        contentColor = CallText,
    ) {
        // The one place the call screen names the tool in use; the header says only the state.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Build, null, tint = CallGoldBright)
            Text(receipt.activityTemplate()?.let { uiText(it).replace("{0}", receipt.activitySubject()) } ?: uiText(activity), Modifier.testTag("beacon-call-tool-status"), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** One short word under a control, on two lines when a language needs them. The button carries the full name, so TalkBack does not read the caption a second time. */
@Composable private fun CallControlLabel(text: String) {
    if (text.isNotBlank()) Text(text, Modifier.clearAndSetSemantics {}, color = CallText, style = MaterialTheme.typography.labelMedium,
        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/**
 * A round call control. [caption] is a single word kept visible at any text size; [description] is the full name for TalkBack.
 * Hang up is larger, red, and has no caption: the red phone is its own label.
 */
@Composable private fun CallControl(
    caption: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    enabled: Boolean,
    selected: Boolean,
    stateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hangup: Boolean = false,
    size: Dp = CallControlSize,
) {
    val state = stateDescription
    val button = Modifier.size(size).testTag(tag).semantics { this.stateDescription = state }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hangup) FilledIconButton(
            onClick, button, enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = CallHangupRed,
                contentColor = CallText,
                disabledContainerColor = CallControlDisabledContainer,
                disabledContentColor = CallControlDisabledContent,
            ),
        ) { Icon(icon, description, Modifier.size(size / 2)) }
        else FilledIconToggleButton(
            selected, { onClick() }, button, enabled = enabled,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = CallControl,
                contentColor = CallText,
                disabledContainerColor = CallControlDisabledContainer,
                disabledContentColor = CallControlDisabledContent,
                checkedContainerColor = CallGoldBright,
                checkedContentColor = CallBackground,
            ),
        ) { Icon(icon, description) }
        CallControlLabel(caption)
    }
}

@Composable private fun CallSpeakerControl(call: CallState, actions: CallUiActions, modifier: Modifier = Modifier) {
    var routes by remember(call.callId) { mutableStateOf(false) }
    val speakerLabel = uiText("Speaker")
    val routeLabel = call.routes.firstOrNull { it.id == call.route }?.name ?: uiText("Audio output")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            FilledIconButton(
                { routes = true },
                Modifier.size(CallControlSize).testTag("beacon-speaker").semantics {
                    contentDescription = speakerLabel
                    stateDescription = routeLabel
                },
                enabled = call.routes.isNotEmpty(),
                // Disabled colours of its own: the always-dark call surface must not inherit the (light) theme's near-white disabled fill.
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallControl, contentColor = CallText,
                    disabledContainerColor = CallControlDisabledContainer, disabledContentColor = CallControlDisabledContent),
            ) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, speakerLabel) }
            DropdownMenu(routes, { routes = false }) {
                call.routes.forEach { route ->
                    DropdownMenuItem(
                        text = { Text(route.name) },
                        onClick = { routes = false; actions.route(route.id) },
                        leadingIcon = { if (call.route == route.id) Icon(Icons.Rounded.Check, null) },
                    )
                }
            }
        }
        CallControlLabel(speakerLabel)
    }
}

private val CallControlSize = 56.dp
private val CallSecondarySize = 48.dp
private val CallHangupSize = 68.dp
private val CallCompactHangupSize = 60.dp

/**
 * Camera and screen share sit in a quiet group on top; Mute and Speaker are the main controls and the large red Hang up comes last.
 * The call bubble permission is not asked here: it is asked once after the call ([CallBubbleAskHost]).
 */
@Composable internal fun LiveCallDock(
    call: CallState,
    actions: CallUiActions,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    /** One row of all five controls, for a screen too short for two rows. */
    compact: Boolean = false,
) {
    val cameraOn = call.cameraSharing || call.cameraBusy || call.cameraStopPending
    val screenOn = call.screenSharing || call.screenBusy || call.screenStopPending
    @Composable fun CameraControl(modifier: Modifier) = CallControl(
        uiText("Camera"),
        if (cameraOn) uiText("Stop camera") else uiText("Camera"),
        if (cameraOn) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
        "beacon-camera",
        call.active && (cameraOn || !call.held && call.state != "connecting") && !call.screenSharing && !call.screenBusy && !call.screenStopPending,
        call.cameraSharing,
        if (call.cameraStopPending) uiText("Camera stop unconfirmed. Retry stopping.") else if (call.cameraBusy) uiText("Camera changing") else if (call.cameraSharing) uiText("Camera sharing") else uiText("Camera stopped"),
        actions.camera,
        modifier,
        size = CallSecondarySize,
    )
    @Composable fun ShareControl(modifier: Modifier) = CallControl(
        uiText("Share"),
        if (screenOn) uiText("Stop sharing") else uiText("Share screen"),
        if (screenOn) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
        "beacon-share-screen",
        call.active && (screenOn || !call.held && call.state != "connecting" && !call.cameraStopPending),
        call.screenSharing,
        if (call.screenStopPending) uiText("Screen stop unconfirmed. Retry stopping.") else if (call.screenSharing) uiText("Screen sharing") else uiText("Screen stopped"),
        actions.screen,
        modifier,
        size = CallSecondarySize,
    )
    @Composable fun MuteControl(modifier: Modifier) = CallControl(
        if (call.muted) uiText("Unmute") else uiText("Mute"),
        if (call.muted) uiText("Unmute") else uiText("Mute"),
        if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
        "beacon-mute",
        call.active,
        call.muted,
        if (call.muted) uiText("Microphone muted") else uiText("Microphone active"),
        actions.mute,
        modifier,
    )
    @Composable fun HangUpControl(modifier: Modifier) = CallControl(
        "",
        uiText("Hang up"),
        Icons.Rounded.CallEnd,
        "beacon-hang-up",
        call.active,
        false,
        uiText("End call"),
        actions.end,
        modifier,
        hangup = true,
        size = if (compact) CallCompactHangupSize else CallHangupSize,
    )
    Column(
        modifier.background(CallSurface.copy(alpha = .98f), RoundedCornerShape(26.dp)).padding(horizontal = 12.dp, vertical = 14.dp).testTag("beacon-call-dock"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (compact) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CameraControl(Modifier.weight(1f))
            ShareControl(Modifier.weight(1f))
            MuteControl(Modifier.weight(1f))
            CallSpeakerControl(call, actions, Modifier.weight(1f))
            HangUpControl(Modifier.weight(1f))
        } else {
            Row(
                Modifier.background(CallSurfaceRaised.copy(alpha = .5f), RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp).testTag("beacon-call-share-group"),
                horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top,
            ) {
                CameraControl(Modifier.widthIn(min = 64.dp))
                ShareControl(Modifier.widthIn(min = 64.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                MuteControl(Modifier.weight(1f))
                CallSpeakerControl(call, actions, Modifier.weight(1f))
                HangUpControl(Modifier.weight(1f))
            }
        }
    }
}

/** True once: a call that ran without the bubble permission has just ended and the question was never asked. */
internal fun shouldAskCallBubble(wasActive: Boolean, bubbleWasMissing: Boolean, active: Boolean, alreadyAsked: Boolean): Boolean =
    wasActive && !active && bubbleWasMissing && !alreadyAsked

/** After such a call: one short question, remembered so it is never asked again. */
@Composable internal fun CallBubbleAsk(call: CallState, asked: Boolean, onAsked: () -> Unit, onAllow: () -> Unit) {
    var wasActive by remember { mutableStateOf(call.active) }
    var missing by remember { mutableStateOf(call.active && call.overlayPermissionMissing) }
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(call.active, call.overlayPermissionMissing, asked) {
        if (call.active) missing = missing || call.overlayPermissionMissing
        if (shouldAskCallBubble(wasActive, missing, call.active, asked)) show = true
        if (!call.active) missing = false
        wasActive = call.active
    }
    if (show) {
        val close = { show = false; onAsked() }
        AlertDialog(
            onDismissRequest = close,
            icon = { Icon(Icons.Rounded.Call, null) },
            title = { Text(uiText("Allow call bubble")) },
            text = { Text(uiText("Show calls in a floating bubble.")) },
            confirmButton = { TextButton(onClick = { close(); onAllow() }, modifier = Modifier.heightIn(min = 48.dp).testTag("beacon-allow-bubble")) { Text(uiText("Allow")) } },
            dismissButton = { TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp).testTag("beacon-allow-bubble-skip")) { Text(uiText("Not now")) } },
            modifier = Modifier.testTag("beacon-allow-bubble-dialog"),
        )
    }
}

/** Hosts [CallBubbleAsk] for the app: the answer is stored once on this phone and the permission screen opens through the activity. */
@Composable internal fun CallBubbleAskHost(call: CallState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("beacon_call_ui", android.content.Context.MODE_PRIVATE) }
    var asked by remember(prefs) { mutableStateOf(prefs.getBoolean("bubble_asked", false)) }
    CallBubbleAsk(call, asked, onAsked = { asked = true; prefs.edit().putBoolean("bubble_asked", true).apply() }) {
        (context as? dev.hermes.android.BeaconActivity)?.openCallBubbleSettings()
    }
}

@Composable private fun CallHeader(call: CallState, onConversation: () -> Unit, modifier: Modifier = Modifier) {
    var now by remember(call.callId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.callId, call.active, call.startedAt) {
        if (!call.active) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    // The chat header above already shows who this is; this row is the call's own state, time and way back to the chat.
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(CallGoldBright))
            Text(callStatusText(call, withTool = false), Modifier.testTag("beacon-call-status"), color = CallGoldBright, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text(callTimerLabel(call.startedAt, now), Modifier.testTag("beacon-call-timer"), color = CallText,
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
        FilledTonalIconButton(
            onConversation,
            Modifier.size(48.dp).testTag("beacon-view-conversation"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CallSurfaceRaised, contentColor = CallGoldBright),
        ) { Icon(Icons.Rounded.ChatBubbleOutline, uiText("View conversation")) }
    }
}

/** The tool window and the sharing and error lines. Only this block scrolls, and only when it is given less height than its lines need. */
@Composable private fun CallNotices(call: CallState) {
    val tool = call.toolReceipts.lastOrNull { it.chipLabel() != null }
    if (tool == null && !call.screenSharing && !call.screenBusy && !call.cameraBusy && call.error.isBlank()) return
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tool?.let { CallToolChip(it) }
        if (call.screenSharing) Text(assistantText("Screen shared with Beacon"), color = CallText, style = MaterialTheme.typography.labelLarge)
        if (call.screenBusy) Text(uiText("Starting screen share…"), color = CallMuted, style = MaterialTheme.typography.labelLarge)
        if (call.cameraBusy) Text(uiText("Starting camera…"), color = CallMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("beacon-camera-starting"))
        if (call.error.isNotBlank()) Text(call.error, color = Color(0xffffb4ab), style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** The controls: the one block that is always whole and pinned to the bottom. The call screen carries no explanatory lines; what the chat saved of the call is in its record afterwards. */
@Composable private fun CallControls(call: CallState, actions: CallUiActions, motionEnabled: Boolean, compact: Boolean) {
    Column(Modifier.testTag("beacon-call-controls"), horizontalAlignment = Alignment.CenterHorizontally) {
        LiveCallDock(call, actions, motionEnabled, Modifier.widthIn(max = 640.dp).fillMaxWidth(), compact)
    }
}

/**
 * The live call window. It fits the space it is given and has no scroll of its own ([CallFit]): the presence shrinks first, the controls stay whole at the bottom,
 * and a short screen gets the one-row controls. Landscape puts the presence beside the notices, captions and controls.
 */
@Composable internal fun LiveCallSurface(
    call: CallState,
    actions: CallUiActions,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier,
    onConversation: () -> Unit,
    cameraPreview: @Composable (Modifier) -> Unit,
) {
    // Edge-to-edge ignores the bar colours; the call background shows through, so only the icon shades matter.
    SetupSystemBars()
    Box(modifier.fillMaxSize().background(CallBackground).testTag("beacon-live-call-surface")) {
        if (call.cameraSharing || call.cameraBusy) cameraPreview(Modifier.fillMaxSize())
        // Insets the app already applied are not applied twice; on its own the window keeps clear of the bars and the cutout.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val landscape = maxWidth > maxHeight
            val compact = maxHeight < 600.dp
            val header: @Composable () -> Unit = { CallHeader(call, onConversation, Modifier.fillMaxWidth()) }
            val presence: @Composable () -> Unit = { VoicePresence(call, Modifier.fillMaxSize().testTag("beacon-call-hero"), motionEnabled) }
            if (landscape) {
                Row(
                    Modifier.align(Alignment.Center).widthIn(max = 1240.dp).fillMaxWidth().fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CallFit(Modifier.weight(1f).fillMaxHeight(), header = header, presence = presence)
                    CallFit(
                        Modifier.weight(1.2f).fillMaxHeight(),
                        notices = { CallNotices(call) },
                        captions = { LiveCallCaptions(call, compact = compact) },
                        controls = { CallControls(call, actions, motionEnabled, compact) },
                    )
                }
            } else {
                CallFit(
                    Modifier.align(Alignment.Center).widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()
                        .padding(horizontal = if (maxWidth >= 720.dp) 32.dp else 16.dp, vertical = 8.dp),
                    header = header,
                    presence = presence,
                    notices = { CallNotices(call) },
                    captions = { LiveCallCaptions(call, compact = compact) },
                    controls = { CallControls(call, actions, motionEnabled, compact) },
                )
            }
        }
        if (call.cameraSharing) Surface(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp).testTag("beacon-camera-sharing-status"),
            shape = RoundedCornerShape(50), color = CallSurface.copy(alpha = .96f), contentColor = CallText,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(assistantText(if (call.cameraRear) "Back camera shared with Beacon" else "Front camera shared with Beacon"),
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp, vertical = 5.dp))
                if (call.cameraCanFlip) IconButton(onClick = actions.flip, modifier = Modifier.testTag("beacon-camera-flip")) {
                    Icon(Icons.Rounded.FlipCameraAndroid, uiText("Flip camera"))
                }
            }
        }
    }
}

internal val CallBackground = Color(0xff080d15)
private val CallSurface = Color(0xff101a2b)
private val CallHangupRed = Color(0xffb83a36)
private val CallSurfaceRaised = Color(0xff1b2940)
private val CallControl = Color(0xff202e45)
private val CallText = Color(0xfff5f4f0)
private val CallMuted = Color(0xffc4cede)
/** A disabled round control on the call surface: its own dark fill and a muted glyph, in either app theme. */
internal val CallControlDisabledContainer = CallControl.copy(alpha = .55f)
internal val CallControlDisabledContent = CallMuted
internal val CallGold = Color(0xffd5a64a)
private val CallGoldBright = Color(0xffffe2a0)

/**
 * The call status in the phone's language; a tool's status names its service, so the words are translated around the name.
 * [withTool] false is the state alone (Listening, Thinking, Speaking), for a place where the tool has a line of its own.
 */
@Composable internal fun callStatusText(call: CallState, withTool: Boolean = true): String {
    val label = callStatusLabel(call, withTool)
    val tool = if (withTool) call.toolReceipts.lastOrNull { it.statusLabel() != null } else null
    return tool?.statusTemplate()?.takeIf { tool.statusLabel() == label }?.let { uiText(it).replace("{0}", tool.activitySubject()) } ?: uiText(label)
}
