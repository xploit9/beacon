package dev.hermes.android.ui

import android.app.Activity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.platform.LocalView
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
    val transcriptDescription = uiText("Call transcript")
    Surface(
        modifier.widthIn(max = 680.dp).fillMaxWidth().testTag("beacon-live-captions"),
        shape = RoundedCornerShape(24.dp),
        color = CallSurface,
        contentColor = CallText,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp).heightIn(max = if (compact) 128.dp else 236.dp)
                .verticalScroll(rememberScrollState()).semantics {
                    if (lines.last().final) liveRegion = LiveRegionMode.Polite
                    contentDescription = transcriptDescription
                },
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp),
        ) {
            lines.forEachIndexed { index, line ->
                val latest = index == lines.lastIndex
                val latestForRole = index == lines.indexOfLast { it.role == line.role }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (line.role == "user") Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(.94f).alpha(if (latest) 1f else .52f)) {
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
                Button(onRetry, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-call-failure-retry")) { Text("Retry") }
                OutlinedButton(onClose, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("beacon-call-failure-close")) { Text("Close") }
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
                Text(uiText(activity), Modifier.testTag("beacon-call-tool-status"), style = MaterialTheme.typography.labelLarge)
            }
            receipt.approvalCaption()?.let { caption ->
                Text(uiText(caption), Modifier.padding(start = 32.dp).testTag("beacon-tool-approval-caption"),
                    style = MaterialTheme.typography.labelSmall, color = CallMuted)
            }
        }
    }
}

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
    iconLabel: String = label,
) {
    val accessibleLabel = uiText(iconLabel)
    val accessibleState = uiText(stateDescription)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledIconButton(
            onClick,
            Modifier.size(48.dp).testTag(tag).semantics { this.selected = selected; this.stateDescription = accessibleState },
            enabled = enabled,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (hangup) Color(0xff9f3d3d) else CallControl,
                contentColor = CallText,
                disabledContainerColor = CallControl.copy(alpha = .55f),
                disabledContentColor = CallMuted,
            ),
        ) { Icon(icon, accessibleLabel) }
        Text(uiText(label), color = CallText, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun CallSpeakerControl(call: CallState, actions: CallUiActions, modifier: Modifier = Modifier) {
    var routes by remember(call.callId) { mutableStateOf(false) }
    val speakerLabel = uiText("Speaker")
    val routeLabel = uiText(call.routes.firstOrNull { it.id == call.route }?.name ?: "Audio output")
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
            ) { Icon(Icons.Rounded.VolumeUp, uiText("Speaker")) }
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
        Text(uiText("Speaker"), color = CallText, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable internal fun LiveCallDock(call: CallState, actions: CallUiActions, motionEnabled: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.background(CallSurface.copy(alpha = .98f), RoundedCornerShape(26.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Top) {
            CallControl(
                "Camera",
                if (call.cameraSharing || call.cameraBusy || call.cameraStopPending) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,
                "beacon-camera",
                call.active && (call.cameraSharing || call.cameraBusy || call.cameraStopPending || !call.held && call.state != "connecting") && !call.screenSharing && !call.screenBusy && !call.screenStopPending,
                call.cameraSharing,
                if (call.cameraStopPending) "Camera stop unconfirmed. Retry stopping." else if (call.cameraBusy) "Camera changing" else if (call.cameraSharing) "Camera sharing" else "Camera stopped",
                actions.camera,
                Modifier.weight(1f),
                iconLabel = if (call.cameraSharing || call.cameraBusy || call.cameraStopPending) "Turn camera off" else "Camera",
            )
            CallControl(
                "Mute",
                if (call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                "beacon-mute",
                call.active,
                call.muted,
                if (call.muted) "Microphone muted" else "Microphone active",
                actions.mute,
                Modifier.weight(1f),
                iconLabel = if (call.muted) "Unmute" else "Mute",
            )
            CallSpeakerControl(call, actions, Modifier.weight(1f))
            CallControl(
                if (call.screenSharing || call.screenBusy || call.screenStopPending) "Stop sharing" else "Share screen",
                if (call.screenSharing || call.screenBusy || call.screenStopPending) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,
                "beacon-share-screen",
                call.active && (call.screenSharing || call.screenBusy || call.screenStopPending || !call.held && call.state != "connecting" && !call.cameraStopPending),
                call.screenSharing,
                if (call.screenStopPending) "Screen stop unconfirmed. Retry stopping." else if (call.screenSharing) "Screen sharing" else "Screen stopped",
                actions.screen,
                Modifier.weight(1f),
                iconLabel = if (call.screenSharing || call.screenBusy || call.screenStopPending) "Stop sharing screen" else "Share screen",
            )
            CallControl(
                "Hang up",
                Icons.Rounded.CallEnd,
                "beacon-hang-up",
                call.active,
                false,
                "End call",
                actions.end,
                Modifier.weight(1f),
                hangup = true,
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(CallGold.copy(alpha = .16f))
                .border(1.dp, CallGold.copy(alpha = .52f), CircleShape), contentAlignment = Alignment.Center) {
                BeaconMark(Modifier.testTag("call-beacon-mark"), size = 36.dp, contentDescription = assistantText("Beacon"))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(dev.hermes.android.data.currentAssistantName, color = CallText, style = MaterialTheme.typography.titleLarge, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(CallGoldBright))
                    Text(uiText(callStatusLabel(call)), Modifier.testTag("beacon-call-status"), color = CallGoldBright, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(callTimerLabel(call.startedAt, now), Modifier.testTag("beacon-call-timer"), color = CallText,
                    style = MaterialTheme.typography.titleMedium)
                Text(uiText("CALL TIME"), style = MaterialTheme.typography.labelSmall, color = CallMuted)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(
                onConversation,
                Modifier.heightIn(min = 48.dp).testTag("beacon-view-conversation"),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = CallSurfaceRaised, contentColor = CallGoldBright),
            ) {
                Icon(Icons.Rounded.ChatBubbleOutline, null)
                Spacer(Modifier.width(8.dp))
                Text(uiText("View conversation"))
            }
        }
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
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    DisposableEffect(window, view) {
        val bars = window?.let { WindowInsetsControllerCompat(it, view) }
        val oldStatusColor = window?.statusBarColor
        val oldNavigationColor = window?.navigationBarColor
        val oldLightStatus = bars?.isAppearanceLightStatusBars
        val oldLightNavigation = bars?.isAppearanceLightNavigationBars
        if (window != null) {
            window.statusBarColor = CallBackground.toArgb()
            window.navigationBarColor = CallBackground.toArgb()
            bars?.isAppearanceLightStatusBars = false
            bars?.isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (window != null) {
                if (oldStatusColor != null) window.statusBarColor = oldStatusColor
                if (oldNavigationColor != null) window.navigationBarColor = oldNavigationColor
                if (oldLightStatus != null) bars?.isAppearanceLightStatusBars = oldLightStatus
                if (oldLightNavigation != null) bars?.isAppearanceLightNavigationBars = oldLightNavigation
            }
        }
    }
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
                        if (call.screenSharing) Text(uiText("Screen shared with Beacon"), color = CallText, style = MaterialTheme.typography.labelLarge)
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
private val CallGold = Color(0xffd5a64a)
private val CallGoldBright = Color(0xffffe2a0)
