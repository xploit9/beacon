package dev.hermes.android.ui



import androidx.compose.animation.core.*

import androidx.compose.foundation.*

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.rounded.*

import androidx.compose.material3.*

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.geometry.Offset

import androidx.compose.ui.geometry.Size

import androidx.compose.ui.graphics.*

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.semantics.*

import androidx.compose.ui.text.font.FontStyle

import androidx.compose.ui.unit.dp

import androidx.lifecycle.Lifecycle

import androidx.lifecycle.compose.LocalLifecycleOwner

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
                Row(Modifier.fillMaxSize().testTag("cypher-adaptive-navigation")) {
                    if (persistent) {
                        Surface(Modifier.width((280f * scale.coerceIn(1f, 1.2f)).dp).fillMaxHeight().testTag("cypher-persistent-navigation"), color = MaterialTheme.colorScheme.surfaceContainer) {
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

@Composable internal fun VoiceWave(call: CallState, modifier: Modifier, motionEnabled: Boolean, circular: Boolean = false) {

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val lifecycleState by lifecycle.currentStateFlow.collectAsState()

    val motionAllowed = motionEnabled && call.active && lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val amplitude by animateFloatAsState(call.voiceAmplitude(), if(motionAllowed) tween(110,easing=FastOutSlowInEasing) else snap(),label="Voice activity")
    val activeMotion = voiceMotionActive(amplitude,motionAllowed,true)

    val phase = if(activeMotion) {

        val transition=rememberInfiniteTransition(label="Continuous voice activity")

        val progress by transition.animateFloat(0f,(Math.PI*2).toFloat(),infiniteRepeatable(tween(1600,easing=LinearEasing)),label="Blue wave phase")

        progress

    } else 0f



    val shape=if(circular) RoundedCornerShape(50) else RoundedCornerShape(48.dp)

    Canvas(modifier.clip(shape).background(Color(0xff0a1225)).testTag("cypher-blue-wave")

        .semantics { contentDescription="${dev.hermes.android.data.currentAssistantName} voice activity"; stateDescription=if(call.held) "Call on hold" else call.state }) {

        drawRect(Brush.verticalGradient(listOf(Color(0xff102142),Color(0xff16315b),Color(0xff4c86db))))

        for(layer in 2 downTo 0) {

            val path=Path(); path.moveTo(0f,size.height)

            for(i in 0..56) { val x=i/56f; path.lineTo(x*size.width,voiceWaveY(x,phase,amplitude,layer)*size.height) }

            path.lineTo(size.width,size.height); path.close()

            drawPath(path,Brush.verticalGradient(listOf(Color(0xff9fd1ff).copy(alpha=.78f-layer*.15f),Color(0xff4684ec).copy(alpha=.85f)),startY=size.height*.28f,endY=size.height))

        }

        drawOval(Brush.radialGradient(listOf(Color(0xffc7e8ff).copy(alpha=.38f),Color.Transparent)),topLeft=Offset(size.width*.15f,size.height*.60f),size=Size(size.width*.7f,size.height*.45f))

    }

}



@Composable internal fun LiveCallCaptions(call: CallState, modifier: Modifier = Modifier, compact: Boolean = false) {

    val user=call.latestCaption("user"); val assistant=call.latestCaption("assistant")

    if (user.isBlank() && assistant.isBlank()) return

    Surface(modifier.widthIn(max=620.dp).fillMaxWidth().testTag("cypher-live-captions"),shape=RoundedCornerShape(24.dp),color=Color(0xff0b0e13).copy(alpha=.95f),contentColor=Color(0xfff4f7fc)) {

        Column(Modifier.padding(horizontal=20.dp,vertical=16.dp).semantics { if (call.transcripts.lastOrNull()?.final == true) liveRegion = LiveRegionMode.Polite },verticalArrangement=Arrangement.spacedBy(10.dp)) {

            if(user.isNotBlank()) Text(user,fontStyle=FontStyle.Italic,style=MaterialTheme.typography.bodyLarge,maxLines=if(compact) 3 else 5,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.testTag("cypher-user-caption"))

            if(assistant.isNotBlank()) Text(assistant,style=MaterialTheme.typography.bodyLarge,maxLines=if(compact) 4 else 7,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,modifier=Modifier.testTag("cypher-assistant-caption"))

        }

    }

}

internal data class CallUiActions(val mute: () -> Unit, val camera: () -> Unit, val flip: () -> Unit, val allowBubble: () -> Unit, val end: () -> Unit, val route: (String) -> Unit, val screen: () -> Unit = {})

@Composable internal fun LiveCallDock(call: CallState, actions: CallUiActions, motionEnabled: Boolean, modifier: Modifier = Modifier) {

    var routes by remember { mutableStateOf(false) }

    val buttonColors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xff172131),contentColor=Color(0xfff2f6ff),disabledContainerColor=Color(0xff151b24),disabledContentColor=Color(0xff7c8798))

    Column(modifier.fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {

        Row(Modifier.widthIn(max=480.dp).fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {

            FilledIconButton(actions.camera,Modifier.size(52.dp).testTag("cypher-camera").semantics { selected = call.cameraSharing; stateDescription = if (call.cameraStopPending) "Camera stop unconfirmed. Retry stopping." else if (call.cameraBusy) "Camera changing" else if (call.cameraSharing) "Camera sharing" else "Camera stopped" },enabled=call.active&&(call.cameraSharing||call.cameraBusy||call.cameraStopPending||!call.held&&call.state!="connecting")&&!call.screenSharing&&!call.screenBusy&&!call.screenStopPending,colors=buttonColors) { Icon(if(call.cameraSharing||call.cameraBusy||call.cameraStopPending) Icons.Rounded.VideocamOff else Icons.Rounded.Videocam,uiText(if(call.cameraSharing||call.cameraBusy||call.cameraStopPending) if(call.cameraStopPending) "Retry stop camera" else "Camera off" else "Camera")) }

            FilledIconButton(actions.mute,Modifier.size(52.dp).testTag("cypher-mute").semantics { selected = call.muted; stateDescription = if (call.muted) "Microphone muted" else "Microphone active" },enabled=call.active,colors=buttonColors) { Icon(if(call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,uiText(if(call.muted) "Unmute" else "Mute")) }

            Box(Modifier.weight(1f),contentAlignment=Alignment.Center) { VoiceWave(call,Modifier.widthIn(max=168.dp).fillMaxWidth().height(72.dp),motionEnabled) }

            Box {

                FilledIconButton({routes=true},Modifier.size(52.dp),enabled=call.routes.isNotEmpty(),colors=buttonColors) { Icon(Icons.Rounded.VolumeUp,uiText("Audio output")) }

                DropdownMenu(routes,{routes=false}) { call.routes.forEach { route -> DropdownMenuItem(text={Text(route.name)},onClick={routes=false;actions.route(route.id)},leadingIcon={if(call.route==route.id) Icon(Icons.Rounded.Check,null)}) } }

            }

            FilledIconButton(actions.end,Modifier.size(52.dp).testTag("cypher-hang-up"),colors=IconButtonDefaults.filledIconButtonColors(containerColor=MaterialTheme.colorScheme.error,contentColor=MaterialTheme.colorScheme.onError)) { Icon(Icons.Rounded.CallEnd,uiText("Hang up")) }

        }

        TextButton(actions.screen,enabled=call.active&&(call.screenSharing||call.screenBusy||call.screenStopPending||!call.held&&call.state!="connecting"&&!call.cameraStopPending),modifier=Modifier.heightIn(min=48.dp).testTag("cypher-share-screen").semantics { selected = call.screenSharing; stateDescription = if (call.screenStopPending) "Screen stop unconfirmed. Retry stopping." else if (call.screenSharing) "Screen sharing" else "Screen stopped" }) {

            Icon(if(call.screenSharing||call.screenBusy||call.screenStopPending) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare,null)

            Spacer(Modifier.width(8.dp));Text(uiText(if(call.screenStopPending) "Retry stop screen" else if(call.screenSharing||call.screenBusy) "Stop sharing screen" else "Share screen"))

        }

        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(bottom=8.dp)) {
            BeaconSignal(if(call.state=="connecting") BeaconMotionMode.Sweep else BeaconMotionMode.Pulse, size=20.dp)
            Text(uiText(if(call.held) "Call on hold" else if(call.state=="connecting") "Connecting your call…" else if(call.userSpeaking) "Listening" else call.state.replaceFirstChar{it.titlecase()}),style=MaterialTheme.typography.labelLarge,color=Color(0xffdce8fc))
        }
        if(call.overlayPermissionMissing) AssistChip(
            onClick = actions.allowBubble,
            label = { Text(uiText("Allow call bubble")) },
            leadingIcon = { Icon(Icons.Rounded.Call, null) },
            modifier = Modifier.heightIn(min = 48.dp).testTag("cypher-allow-bubble"),
        )
        if(call.contextNotice.isNotBlank()) Text(call.contextNotice,style=MaterialTheme.typography.bodySmall,color=Color(0xffc3cbd8),modifier=Modifier.widthIn(max=480.dp).heightIn(max=160.dp).verticalScroll(rememberScrollState()).padding(horizontal=12.dp,vertical=8.dp).testTag("cypher-context-notice").semantics { liveRegion = LiveRegionMode.Polite })

    }

}



@Composable internal fun LiveCallSurface(call: CallState, actions: CallUiActions, motionEnabled: Boolean, modifier: Modifier = Modifier, onConversation: () -> Unit,

    cameraPreview: @Composable (Modifier) -> Unit) {

    Box(modifier.fillMaxSize().background(Color(0xff080d15)).testTag("cypher-live-call-surface")) {

        if(call.cameraSharing || call.cameraBusy) cameraPreview(Modifier.fillMaxSize())



        Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.SpaceBetween) {

            Row(Modifier.fillMaxWidth().background(if(call.cameraSharing) Color(0xff080d15) else Color.Transparent),verticalAlignment=Alignment.CenterVertically) {

                TextButton(onConversation,Modifier.heightIn(min=48.dp)) { Icon(Icons.Rounded.ChatBubbleOutline,null); Spacer(Modifier.width(8.dp));Text(uiText("View conversation")) }
                BeaconMark(Modifier.testTag("call-beacon-mark"), size=26.dp, contentDescription=assistantText("Beacon"))

                Spacer(Modifier.weight(1f))

                if(call.cameraSharing&&call.cameraCanFlip) IconButton(actions.flip,enabled=!call.cameraBusy,modifier=Modifier.size(48.dp).testTag("cypher-camera-flip")) { Icon(Icons.Rounded.Cameraswitch,uiText(if(call.cameraRear) "Switch to front camera" else "Switch to back camera"),tint=Color.White) }


            }

            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).weight(1f),verticalArrangement=Arrangement.Bottom,horizontalAlignment=Alignment.CenterHorizontally) {

                LiveCallCaptions(call,Modifier.padding(bottom=14.dp))

                if(call.cameraSharing) Text(assistantText(if(call.cameraRear) "Back camera shared with Beacon" else "Front camera shared with Beacon"),color=Color.White,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=10.dp).background(Color(0xff080d15),RoundedCornerShape(12.dp)).padding(horizontal=12.dp,vertical=6.dp))

                if(call.screenSharing) Text(assistantText("Screen shared with Beacon"),color=Color.White,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=10.dp))

                if(call.screenBusy) Text(uiText("Waiting for screen-sharing consent or first frame…"),color=Color.White,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=10.dp))

                if(call.cameraBusy) Text(uiText("Starting camera…"),color=Color.White,style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(bottom=10.dp).testTag("cypher-camera-starting"))



                if(call.error.isNotBlank()) Text(call.error,color=Color(0xffffb4ab),style=MaterialTheme.typography.bodyMedium,modifier=Modifier.padding(bottom=10.dp).semantics { liveRegion = LiveRegionMode.Polite })

            }

            LiveCallDock(call,actions,motionEnabled,Modifier.background(if(call.cameraSharing) Color(0xff080d15) else Color.Transparent,RoundedCornerShape(topStart=24.dp,topEnd=24.dp)))

            if(call.transcriptLabel().isNotBlank()) Text(uiText(call.transcriptLabel()),style=MaterialTheme.typography.labelSmall,color=androidx.compose.ui.graphics.Color(0xffc3cbd8),modifier=Modifier.align(Alignment.CenterHorizontally).background(Color(0xff080d15)).padding(horizontal=12.dp,vertical=8.dp).semantics { liveRegion = LiveRegionMode.Polite })

        }

    }

}

