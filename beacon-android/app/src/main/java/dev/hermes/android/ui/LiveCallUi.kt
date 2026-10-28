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

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.*

import androidx.compose.ui.unit.Constraints

import androidx.compose.ui.unit.Dp

import androidx.compose.ui.unit.dp



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

/** Header on top, the control stack at the bottom, and the presence takes the height between them. A short screen scrolls instead of squeezing the presence below [minPresence]. */
@Composable private fun PresenceStack(
    viewportHeight: Dp,
    minPresence: Dp,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    presence: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Layout({
        Box { header() }
        Box { presence() }
        Box { footer() }
    }, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 8.dp.roundToPx()
        val head = measurables[0].measure(Constraints(minWidth = width, maxWidth = width))
        val foot = measurables[2].measure(Constraints(minWidth = width, maxWidth = width))
        val footGap = if (foot.height > 0) gap else 0
        val room = (viewportHeight.roundToPx() - head.height - foot.height - gap - footGap).coerceAtLeast(minPresence.roundToPx())
        val body = measurables[1].measure(Constraints.fixed(width, room))
        layout(width, head.height + gap + room + footGap + foot.height) {
            head.placeRelative(0, 0)
            body.placeRelative(0, head.height + gap)
            foot.placeRelative(0, head.height + gap + room + footGap)
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
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).heightIn(max = if (compact) 128.dp else 236.dp)
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
                            style = if (latest) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            maxLines = if (compact) 2 else 6,
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
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Build, null, tint = CallGoldBright)
                Text(receipt.activityTemplate()?.let { uiText(it).replace("{0}", receipt.activitySubject()) } ?: uiText(activity), Modifier.testTag("beacon-call-tool-status"), style = MaterialTheme.typography.labelLarge)
            }
            receipt.approvalCaption()?.let { caption ->
                Text(uiText(caption), Modifier.padding(start = 32.dp).testTag("beacon-tool-approval-caption"),
                    style = MaterialTheme.typography.labelSmall, color = CallMuted)
            }
        }
    }
}

@Composable private fun CallControlLabel(text: String, show: Boolean) {
    // The button already carries this name; reading the caption as well would make every control two TalkBack stops.
    if (show) Text(text, Modifier.clearAndSetSemantics {}, color = CallText, style = MaterialTheme.typography.labelMedium,
        maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

/** A caption stays beside each control up to this text size; beyond it the five columns are too narrow and the icons carry the names. */
private const val CallLabelMaxFontScale = 1.6f

@Composable private fun CallControl(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tag: String,
    enabled: Boolean,
    selected: Boolean,
    stateDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    hangup: Boolean = false,
    showLabel: Boolean = true,
) {
    val state = stateDescription
    val button = Modifier.size(48.dp).testTag(tag).semantics { this.stateDescription = state }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (hangup) FilledIconButton(
            onClick, button, enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color(0xff9f3d3d),
                contentColor = CallText,
                disabledContainerColor = CallControl.copy(alpha = .55f),
                disabledContentColor = CallMuted,
            ),
        ) { Icon(icon, label) }
        else FilledIconToggleButton(
            selected, { onClick() }, button, enabled = enabled,
            colors = IconButtonDefaults.filledIconToggleButtonColors(
                containerColor = CallControl,
                contentColor = CallText,
                disabledContainerColor = CallControl.copy(alpha = .55f),
                disabledContentColor = CallMuted,
                checkedContainerColor = CallGoldBright,
                checkedContentColor = CallBackground,
            ),
        ) { Icon(icon, label) }
        CallControlLabel(label, showLabel)
    }
}

@Composable private fun CallSpeakerControl(call: CallState, actions: CallUiActions, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    var routes by remember(call.callId) { mutableStateOf(false) }
    val speakerLabel = uiText("Speaker")
    val routeLabel = call.routes.firstOrNull { it.id == call.route }?.name ?: uiText("Audio output")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            FilledIconButton(
                { routes = true },
                Modifier.size(48.dp).testTag("beacon-speaker").semantics {
                    contentDescription = speakerLabel
                    stateDescription = routeLabel
                },
                enabled = call.routes.isNotEmpty(),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = CallControl, contentColor = CallText),
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
        CallControlLabel(speakerLabel, showLabel)
    }
}

@Composable internal fun LiveCallDock(call: CallState, actions: CallUiActions, motionEnabled: Boolean, modifier: Modifier = Modifier) {
    val labels = LocalDensity.current.fontScale <= CallLabelMaxFontScale
    val cameraOn = call.cameraSharing || call.cameraBusy || call.cameraStopPending
    val screenOn = call.screenSharing || call.screenBusy || call.screenStopPending
    Column(
        modifier.background(CallSurface.copy(alpha = .98f), RoundedCornerShape(26.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Top) {
            CallControl(
                if (cameraOn) uiText("Stop camera") else uiText("Camera"),
                if (cameraOn) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                "beacon-camera",
                call.active && (cameraOn || !call.held && call.state != "connecting") && !call.screenSharing && !call.screenBusy && !call.screenStopPending,
                call.cameraSharing,
                if (call.cameraStopPending) uiText("Camera stop unconfirmed. Retry stopping.") else if (call.cameraBusy) uiText("Camera changing") else if (call.cameraSharing) uiText("Camera sharing") else uiText("Camera stopped"),
                actions.camera,
                Modifier.weight(1f),
                showLabel = labels,
            )
            CallControl(
                if (call.muted) uiText("Unmute") else uiText("Mute"),
                if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                "beacon-mute",
                call.active,
                call.muted,
                if (call.muted) uiText("Microphone muted") else uiText("Microphone active"),
                actions.mute,
                Modifier.weight(1f),
                showLabel = labels,
            )
            CallSpeakerControl(call, actions, Modifier.weight(1f), labels)
            CallControl(
                if (screenOn) uiText("Stop sharing") else uiText("Share screen"),
                if (screenOn) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
                "beacon-share-screen",
                call.active && (screenOn || !call.held && call.state != "connecting" && !call.cameraStopPending),
                call.screenSharing,
                if (call.screenStopPending) uiText("Screen stop unconfirmed. Retry stopping.") else if (call.screenSharing) uiText("Screen sharing") else uiText("Screen stopped"),
                actions.screen,
                Modifier.weight(1f),
                showLabel = labels,
            )
            CallControl(
                uiText("Hang up"),
                Icons.Rounded.CallEnd,
                "beacon-hang-up",
                call.active,
                false,
                uiText("End call"),
                actions.end,
                Modifier.weight(1f),
                hangup = true,
                showLabel = labels,
            )
        }
        if (call.overlayPermissionMissing) AssistChip(
            onClick = actions.allowBubble,
            label = { Text(uiText("Allow call bubble")) },
            leadingIcon = { Icon(Icons.Rounded.Call, null) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("beacon-allow-bubble"),
            colors = AssistChipDefaults.assistChipColors(
                containerColor = CallSurfaceRaised,
                labelColor = CallGoldBright,
                leadingIconContentColor = CallGoldBright,
            ),
        )
        if (call.contextNotice.isNotBlank()) Text(
            call.contextNotice, style = MaterialTheme.typography.bodySmall, color = CallMuted,
            modifier = Modifier.widthIn(max = 520.dp).heightIn(max = 120.dp).verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp).testTag("beacon-context-notice").semantics { liveRegion = LiveRegionMode.Polite },
        )
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
            Text(callStatusText(call), Modifier.testTag("beacon-call-status"), color = CallGoldBright, style = MaterialTheme.typography.labelMedium,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(callTimerLabel(call.startedAt, now), Modifier.testTag("beacon-call-timer"), color = CallText,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"))
            Text(uiText("Call time"), style = MaterialTheme.typography.labelSmall, color = CallMuted)
        }
        FilledTonalIconButton(
            onConversation,
            Modifier.size(48.dp).testTag("beacon-view-conversation"),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CallSurfaceRaised, contentColor = CallGoldBright),
        ) { Icon(Icons.Rounded.ChatBubbleOutline, uiText("View conversation")) }
    }
}

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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            val viewport = maxHeight // read here: inside Row/Column scopes maxHeight is not the implicit receiver
            if (landscape) {
                Row(
                    Modifier.align(Alignment.Center).widthIn(max = 1240.dp).fillMaxWidth().fillMaxHeight()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PresenceStack(
                        viewportHeight = viewport - 24.dp,
                        minPresence = 120.dp,
                        modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        header = { CallHeader(call, onConversation, Modifier.fillMaxWidth()) },
                        presence = { VoicePresence(call, Modifier.fillMaxSize().testTag("beacon-call-hero"), motionEnabled) },
                    )
                    Column(Modifier.weight(1.2f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        call.toolReceipts.lastOrNull { it.chipLabel() != null }?.let { CallToolChip(it, Modifier.padding(bottom = 12.dp)) }
                        LiveCallCaptions(call, Modifier.padding(bottom = 12.dp))
                        if (call.screenSharing) Text(assistantText("Screen shared with Beacon"), color = CallText, style = MaterialTheme.typography.labelLarge)
                        if (call.screenBusy) Text(uiText("Waiting for screen-sharing consent or first frame…"), color = CallMuted, style = MaterialTheme.typography.labelLarge)
                        if (call.cameraBusy) Text(uiText("Starting camera…"), color = CallMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("beacon-camera-starting"))
                        if (call.error.isNotBlank()) Text(call.error, color = Color(0xffffb4ab), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        LiveCallDock(call, actions, motionEnabled, Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 8.dp))
                        if (call.transcriptLabel().isNotBlank()) Text(uiText(call.transcriptLabel()), color = CallMuted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            } else {
                PresenceStack(
                    viewportHeight = viewport - 16.dp,
                    minPresence = 140.dp,
                    modifier = Modifier.align(Alignment.Center).widthIn(max = 920.dp).fillMaxWidth().fillMaxHeight()
                        .verticalScroll(rememberScrollState()).padding(horizontal = if (maxWidth >= 720.dp) 32.dp else 16.dp, vertical = 8.dp),
                    header = { CallHeader(call, onConversation, Modifier.fillMaxWidth()) },
                    presence = { VoicePresence(call, Modifier.fillMaxSize().testTag("beacon-call-hero"), motionEnabled) },
                ) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        call.toolReceipts.lastOrNull { it.chipLabel() != null }?.let { CallToolChip(it) }
                        if (call.screenSharing) Text(assistantText("Screen shared with Beacon"), color = CallText, style = MaterialTheme.typography.labelLarge)
                        if (call.screenBusy) Text(uiText("Waiting for screen-sharing consent or first frame…"), color = CallMuted, style = MaterialTheme.typography.labelLarge)
                        if (call.cameraBusy) Text(uiText("Starting camera…"), color = CallMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("beacon-camera-starting"))
                        if (call.error.isNotBlank()) Text(call.error, color = Color(0xffffb4ab), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LiveCallCaptions(call, Modifier.padding(top = 2.dp))
                        LiveCallDock(call, actions, motionEnabled, Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally))
                        if (call.transcriptLabel().isNotBlank()) Text(uiText(call.transcriptLabel()), color = CallMuted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
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
private val CallSurfaceRaised = Color(0xff1b2940)
private val CallControl = Color(0xff202e45)
private val CallText = Color(0xfff5f4f0)
private val CallMuted = Color(0xffc4cede)
internal val CallGold = Color(0xffd5a64a)
private val CallGoldBright = Color(0xffffe2a0)

/** The call status in the phone's language; a tool's status names its service, so the words are translated around the name. */
@Composable internal fun callStatusText(call: CallState): String {
    val label = callStatusLabel(call)
    val tool = call.toolReceipts.lastOrNull { it.statusLabel() != null }
    return tool?.statusTemplate()?.takeIf { tool.statusLabel() == label }?.let { uiText(it).replace("{0}", tool.activitySubject()) } ?: uiText(label)
}
