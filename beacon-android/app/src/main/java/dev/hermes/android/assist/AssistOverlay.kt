package dev.hermes.android.assist

import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.currentAssistantName
import dev.hermes.android.preferences
import dev.hermes.android.ui.ApprovalCardOnScreen
import dev.hermes.android.ui.BeaconDust
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.Markdown
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.VoicePresence
import dev.hermes.android.ui.assistantText
import dev.hermes.android.ui.beaconGold
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.presenceState
import dev.hermes.android.ui.rememberBeaconClock
import dev.hermes.android.ui.rememberKeyguardLocked
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.uiText
import dev.hermes.android.voice.activitySubject
import dev.hermes.android.voice.statusLabel
import dev.hermes.android.voice.statusTemplate
import dev.hermes.android.voice.voiceAmplitude
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The overlay in the owner's theme, language and assistant name, read before the first frame where Beacon already knows them. */
@Composable internal fun AssistOverlayRoot(controller: AssistOverlayController) {
    val app = controller.app
    val first = remember { AssistPreferences.latest ?: AppPreferences(assistantName = currentAssistantName) }
    val prefs by remember(app) { app.preferences.data.map(AppPreferences::read) }.collectAsState(first)
    val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val locale = controller.locale
    val direction = if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalAssistantName provides prefs.assistantName, LocalLayoutDirection provides direction) {
        NativeLocalization(nativeLanguageFor(locale)) {
            BeaconTheme(dark, prefs.skin) { ProvideGlass { AssistOverlay(controller, dark) } }
        }
    }
}

internal val Gold = Color(0xFFE8A63A)
internal val GoldBright = Color(0xFFFFD27A)
private val GoldDeep = Color(0xFFB9700F)
/** The ink on a gold pearl. */
private val OnGold = Color(0xFF2E1C00)

@Composable private fun AssistOverlay(c: AssistOverlayController, dark: Boolean) {
    val state = c.state
    val motion = rememberNativeMotionEnabled()
    val clock = rememberBeaconClock(motion)
    val call by c.app.calls.state.collectAsState()
    val context = LocalContext.current
    var pill by remember { mutableStateOf(Rect.Zero) }
    var stack by remember { mutableStateOf(Rect.Zero) }
    var mic by remember { mutableStateOf(Offset.Unspecified) }
    var orb by remember { mutableStateOf(Rect.Zero) }
    val field = remember { FocusRequester() }
    val leaving = state.phase == OverlayPhase.Dismissing || state.phase == OverlayPhase.Hidden
    val appear by animateFloatAsState(if (leaving) 0f else 1f, if (motion) tween(if (leaving) 200 else 260) else snap(), label = "Overlay appear")
    LaunchedEffect(state.shows) {
        if (state.phase != OverlayPhase.Summoning) return@LaunchedEffect
        // With TalkBack on, focus starts in the question field; otherwise the keyboard waits for a tap.
        if (context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true) runCatching { field.requestFocus() }
        delay(if (motion) dev.hermes.android.ui.BEACON_DUST_SUMMON_MILLIS.toLong() else 0L)
        c.summoned()
    }
    // A finished answer keeps the screen on for half a minute of reading; the lock screen would otherwise sleep after ten seconds.
    var reading by remember { mutableStateOf(false) }
    LaunchedEffect(state.ask?.question, state.ask?.streaming) {
        reading = state.ask?.streaming == false
        if (reading) { delay(30_000); reading = false }
    }
    // Open and idle, the screen stays on for IDLE_HOLD_MILLIS after the last interaction (a summon, typing, a touch, dictation or a
    // call ending, an answer or a line arriving), then sleeps as the phone normally does. A touch only stamps the time, so it never recomposes.
    val lastTouch = remember { LongArray(1) }
    var idleHold by remember { mutableStateOf(true) }
    LaunchedEffect(state.shows, state.surface, state.line, state.dictating, state.ask?.streaming, c.draft.text) {
        lastTouch[0] = SystemClock.uptimeMillis()
        holdWhileIdle({ SystemClock.uptimeMillis() }, { lastTouch[0] }, { idleHold = it }, IDLE_HOLD_MILLIS)
    }
    val awake = keepsScreenOn(state, reading, idleHold)
    // Hiding leaves the flag alone (a hidden window keeps nothing on), so a warm show finds it already set.
    LaunchedEffect(awake) { if (awake || state.phase != OverlayPhase.Hidden) c.keepScreenOn(awake) }
    val live = state.surface == OverlaySurface.Live
    // Locked, the conversation's words, the tool in use and the approval's details stay off the window. One value decides all of them: the
    // controller's flag follows the broadcasts and the lock screen is read now as well, so a lock that came up between two broadcasts is not missed.
    val keyguardLocked = rememberKeyguardLocked()
    val phoneLocked = overlayLocked(c.locked, keyguardLocked())
    // Locked with no conversation running, the text box is inert: nothing to type, send or dictate (a written question goes into the private chat); its orb talks.
    val pillLocked = pillInert(phoneLocked, live)
    // The chips start rising a frame after the overlay shows (they are clear for their first 200 ms), so the first frame after
    // the gesture composes as little as it can.
    var chipShows by remember { androidx.compose.runtime.mutableIntStateOf(state.shows) }
    LaunchedEffect(state.shows) { androidx.compose.runtime.withFrameNanos { }; chipShows = state.shows }
    val mood = dustMood(state, if (live) presenceState(call) else null)
    val paneTitle = assistantText("Beacon assistant")
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().semantics { this.paneTitle = paneTitle }.testTag("assist-overlay")
        // Watches every touch before the controls see it (it consumes nothing), for the idle hold.
        .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(PointerEventPass.Initial); lastTouch[0] = SystemClock.uptimeMillis() } } }) {
        // The app beneath stays as it is; tapping anywhere outside Beacon's glass closes the overlay, unless a conversation is live (see
        // outsideTap: Back, a swipe down and the orb still end it). The lower part of the screen
        // deepens toward the theme's background under the dust (BeaconDust's scrim), and from GROUND_LEAD_DP above the topmost surface
        // down it is the theme's background itself (all of it in light, .98 in dark), so nothing of the app shows between the chips,
        // the line, the card and the pill. The dust is drawn above it.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { c.outsideTap() } }.drawBehind {
            if (stack.height <= 0f) return@drawBehind
            val edge = (stack.top - GROUND_LEAD_DP.dp.toPx()).coerceAtLeast(0f)
            val ramp = 56.dp.toPx()
            val ground = background.copy(alpha = groundAlpha(dark) * appear)
            drawRect(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground), edge - ramp, edge), Offset(0f, edge - ramp), Size(size.width, ramp))
            drawRect(ground, Offset(0f, edge), Size(size.width, size.height - edge))
        })
        // Live, the voice is the conversation's own; otherwise the aurora follows the dictation or the reply. The dust keeps its crown
        // above whatever shows over the text box, and listening dust streams to the orb, which is the conversation's one control.
        BeaconDust(mood, pill, if (live && orb != Rect.Zero) orb.center else mic, if (live) ({ call.voiceAmplitude() }) else c::dustLevel, Modifier.fillMaxSize(), motion,
            crown = stack, scrim = { background.copy(alpha = (if (dark) .94f else .92f) * appear) }, onFrame = c::drawn)
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                // The top inset too: with the card, the captions and the text box at their tallest the stack reaches the top of a short screen, and stays under the bar.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                .onGloballyPositioned { stack = it.boundsInRoot() }
                // The glass condenses out of the dust: it rises a little and settles to full size as the swarm reaches its outline.
                .graphicsLayer { alpha = appear; translationY = (1f - appear) * 24.dp.toPx(); scaleX = .96f + .04f * appear; scaleY = .96f + .04f * appear },
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val vm = c.vm
            if (state.surface == OverlaySurface.Answer) state.ask?.let {
                AnswerCard(it, vm, clock, motion, phoneLocked, c::closeCard, c::reveal) { c.open(AssistTarget(AssistPurpose.OpenAssistant)) }
            }
            state.line?.let { LineRow(c, it, state.callFailure) }
            if (live) {
                // The conversation's words, tool activity and any approval it waits on sit right above the text box: no band, no call
                // screen, no window of the app.
                val ui = vm?.ui?.collectAsState()?.value
                val approval = if (ui != null) waitingApproval(call, ui.authenticated, ui.origin, ui.owner, ui.profile) else null
                val captions = remember(call.transcripts, c.typedTurns) { liveCaptions(call.transcripts, c.typedTurns) }
                val tool = call.toolReceipts.lastOrNull { it.statusLabel() != null }
                val activity = tool?.statusTemplate()?.let { uiText(it).replace("{0}", tool.activitySubject()) }
                val show = approvalShow(live, approval, phoneLocked)
                // Only a card that is really drawn counts as the approval; locked, the call posts its notification instead.
                if (approval != null) ApprovalCardOnScreen(approval.requestId, c.app.calls::approvalCardShown, shown = show == ApprovalShow.Card)
                LiveAbovePill(captions, activity, approval, show, { decision -> approval?.let { c.decide(it, decision) } }, phoneLocked = phoneLocked, onUnlock = c::unlock)
            } else {
                // The chips join a frame after the show (they are clear for their first 200 ms anyway), so fitting them never
                // costs the first frame. While the unlock line waits, it is the only thing the overlay shows.
                if (vm != null && chipShows == state.shows && state.line != OverlayLine.Unlock) Chips(c, vm, chipShows, motion, field, phoneLocked) else Spacer(Modifier.height(0.dp))
            }
            val face = orbFace(live, call)
            // The text box says the state only; the tool in use is the line above it.
            val status = if (live) (if (!call.active) uiText("Connecting") else uiText(liveStatusLabel(call, phoneLocked))) else ""
            val screenAvailable = c.screen != null && !live
            Pill(PillInput(
                state = state, draft = { c.draft }, onDraft = { c.draft = it }, screen = screenAvailable, screenAttached = c.screenAttached && screenAvailable, onAttach = c::attach,
                heardLevel = { c.heardLevel }, face = face, status = status, voice = { call.voiceAmplitude() },
                onSend = { question -> c.ask(screenQuestion = question) }, onDictate = c::dictate, onOrb = { if (live) c.endCall() else c.startLive() },
                locked = pillLocked,
            ), field, motion, clock, onBounds = { pill = it }, onMic = { mic = it }, onOrb = { orb = it })
        }
    }
}

/** Beacon's glass for one theme, worked out once per theme for every surface: its colours and the paint of its outer glow. */
private class GlassStyle(scheme: androidx.compose.material3.ColorScheme, glowRadius: Float) {
    val light = scheme.background.luminance() > .5f
    val top = if (light) Color(0xFFFFFDF8) else GoldBright.copy(alpha = .05f).compositeOver(scheme.surfaceContainerHigh)
    val bottom = if (light) Color(0xFFFFF4E1) else scheme.surfaceContainerLow
    val shine = Color.White.copy(alpha = if (light) .95f else .14f)
    val rimHi = if (light) Color(0xFFD48A1C) else GoldBright
    val rimLo = if (light) Color(0xFFB8741A) else Color(0xFFE39A2B)
    val glowInk = if (light) Color(0xFFE39A2B) else Gold
    val glow = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(glowRadius, android.graphics.BlurMaskFilter.Blur.NORMAL) }
}

private val LocalGlass = androidx.compose.runtime.staticCompositionLocalOf<GlassStyle?> { null }

/** Provides [GlassStyle] for the overlay's theme to every surface below it. */
@Composable private fun ProvideGlass(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val radius = with(LocalDensity.current) { 14.dp.toPx() }
    val style = remember(scheme, radius) { GlassStyle(scheme, radius) }
    CompositionLocalProvider(LocalGlass provides style, content = content)
}

/**
 * Beacon's glass: a warm inner gradient, a fine highlight along the top, a gold rim light and a soft gold glow around it. The window
 * cannot blur the app beneath it, so the fill is nearly opaque and the surfaces that carry reading text ([solid]) are fully opaque:
 * nothing on screen competes with an answer. [rim] is how bright the rim light is now (0..1) and [turn] where its brightest point
 * sits round the edge, in radians; both are read while drawing, so a breathing rim redraws without recomposing. [halo] is the
 * strength of the outer glow. A panel takes the taps that land on it ([panel]), so only the dim around it closes the overlay; a
 * button's face leaves them to its button.
 */
@Composable internal fun Modifier.glass(shape: Shape, solid: Boolean = false, rim: () -> Float = { .45f }, turn: () -> Float = { -.6f }, halo: Float = 0f, panel: Boolean = true): Modifier {
    val style = LocalGlass.current ?: return this
    val fill = if (solid) 1f else .95f
    return drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
        val inset = 1.dp.toPx()
        val inner = shape.createOutline(Size(size.width - 2 * inset, size.height - 2 * inset), layoutDirection, this)
        val body = Brush.verticalGradient(listOf(style.top.copy(alpha = fill), style.bottom.copy(alpha = fill)))
        val highlight = Brush.verticalGradient(0f to style.shine, .5f to Color.Transparent)
        val edge = Stroke(1.2.dp.toPx())
        val hairline = Stroke(inset)
        onDrawBehind {
            val r = rim().coerceIn(0f, 1f)
            if (halo > 0f) drawIntoCanvas {
                style.glow.color = style.glowInk.copy(alpha = (halo * (.35f + .65f * r)).coerceIn(0f, 1f)).toArgb()
                it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, corner, corner, style.glow)
            }
            drawOutline(outline, body)
            translate(inset, inset) { drawOutline(inner, highlight, style = hairline) }
            // The rim catches the light at one point and fades round the rest of the edge.
            val a = turn()
            val reach = size.maxDimension / 2f
            val toward = Offset(cos(a) * reach, sin(a) * reach)
            drawOutline(outline, Brush.linearGradient(
                listOf(style.rimHi.copy(alpha = .3f + .62f * r), style.rimLo.copy(alpha = .1f + .22f * r), style.rimHi.copy(alpha = .16f + .4f * r)),
                center + toward, center - toward), style = edge)
        }
    }.clip(shape).then(if (panel) Modifier.pointerInput(Unit) { detectTapGestures { } } else Modifier)
}

/** The owner's and the agent's own words read in their own direction, whatever the interface language. */
internal val ContentDirection = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Content)

// --- the pill ---

/**
 * What the text box shows and does, apart from the controller: the overlay passes its controller's, a Compose test its own. [face] is
 * the orb's (a tap to talk, or the conversation's state), [status] the words for it, [voice] the conversation's voice level (read
 * while drawing) and [onOrb] a tap on it: talk, or, live, end the conversation. [onSend] gets the question an attached screen asks.
 * [locked] is the locked phone's text box (see [pillInert]): nothing to type, send or dictate, and no hint; the orb still talks.
 */
internal class PillInput(
    val state: AssistOverlayState,
    val draft: () -> TextFieldValue,
    val onDraft: (TextFieldValue) -> Unit,
    val screen: Boolean,
    val screenAttached: Boolean,
    val onAttach: () -> Unit,
    val heardLevel: () -> Float,
    val face: OrbFace,
    val status: String,
    val voice: () -> Float,
    val onSend: (String) -> Unit,
    val onDictate: () -> Unit,
    val onOrb: () -> Unit,
    val locked: Boolean = false,
)

/**
 * The text box. It stays through a live conversation: typing then sends a typed turn, the mic (dictation would compete with the
 * conversation's own microphone) and the screen's + give way, and the orb shows the conversation and ends it.
 */
@Composable internal fun Pill(p: PillInput, field: FocusRequester, motion: Boolean, clock: FloatState, onBounds: (Rect) -> Unit = {}, onMic: (Offset) -> Unit = {}, onOrb: (Rect) -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val state = p.state
    val live = p.face != OrbFace.Talk
    val screenQuestion = uiText("What's on my screen?")
    val typeHint = uiText("Type a message")
    // Locked (no conversation running), a question typed but not sent is not drawn: the controller keeps it for the unlock, the box shows none of it.
    val draft = if (p.locked) TextFieldValue() else p.draft()
    // Locked, nothing is sent from the text box, whatever it holds.
    val canSend = !p.locked && (draft.text.isNotBlank() || (p.screenAttached && p.screen))
    val dictating = state.dictating
    val unlockFirst = uiText("Unlock your phone first.")
    // The rim flares as the dust condenses into it, warms while it listens, talks or works, and rests at a slow breath.
    val rest by animateFloatAsState(when {
        state.phase == OverlayPhase.Summoning -> 1f
        dictating -> .8f
        p.face == OrbFace.Speaking -> .8f
        p.face == OrbFace.Listening || p.face == OrbFace.Working -> .65f
        state.ask?.streaming == true -> .65f
        else -> .42f
    }, if (motion) tween(360) else snap(), label = "Pill rim")
    val rim = { (rest * (.86f + .14f * sin(clock.floatValue * 1.7f)) + if (dictating) .45f * p.heardLevel() else if (live) .35f * p.voice() else 0f).coerceIn(0f, 1f) }
    Row(
        // The tag is outermost, so its bounds are the whole pill (the 16 dp inset from the screen included), not what is inside its padding.
        Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("assist-pill").onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .glass(CircleShape, rim = rim, turn = { -.9f + .45f * clock.floatValue }, halo = .55f).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.screen) {
            IconButton(p.onAttach, Modifier.testTag("assist-attach")) {
                Icon(if (p.screenAttached) Icons.Rounded.CheckCircle else Icons.Rounded.Add,
                    if (p.screenAttached) uiText("Screen attached") else uiText("Attach screen"), tint = if (p.screenAttached) Gold else scheme.onSurfaceVariant)
            }
        } else Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f).padding(vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            val hint = when {
                // Locked, the line above says it; the box is blank and nothing can be typed in it.
                p.locked -> ""
                dictating -> state.heard.ifBlank { uiText("Listening") }
                // Live, the words say what the conversation is doing; the box can still be typed in, and TalkBack hears that (see below).
                live -> p.status.ifBlank { typeHint }
                p.screenAttached && p.screen -> screenQuestion
                else -> assistantText("Ask Beacon")
            }
            BasicTextField(
                draft, p.onDraft, Modifier.fillMaxWidth().focusRequester(field).testTag("assist-field").semantics { contentDescription = if (p.locked) unlockFirst else if (live) typeHint else hint },
                enabled = !p.locked,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface).merge(ContentDirection), cursorBrush = SolidColor(Gold), maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { p.onSend(screenQuestion) }),
            )
            if (draft.text.isEmpty() && hint.isNotEmpty()) Text(hint, color = if (dictating && state.heard.isNotBlank()) scheme.onSurface else scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (canSend) IconButton({ p.onSend(screenQuestion) }, Modifier.testTag("assist-send")) {
            Pearl(36.dp) { Icon(Icons.Rounded.ArrowUpward, uiText("Send"), tint = OnGold) }
        } else if (!live && !p.locked) IconButton(p.onDictate, Modifier.onGloballyPositioned { onMic(it.boundsInRoot().center) }.testTag("assist-mic")) {
            // Listening, a gold ring round the mic swells with the voice.
            Box(Modifier.size(40.dp).drawBehind {
                if (!dictating) return@drawBehind
                val level = p.heardLevel().coerceIn(0f, 1f)
                drawCircle(Gold.copy(alpha = .16f + .14f * level), 15.dp.toPx())
                drawCircle(GoldBright.copy(alpha = .5f + .4f * level), (13f + 6f * level).dp.toPx(), style = Stroke(1.5.dp.toPx()))
            }, contentAlignment = Alignment.Center) {
                Icon(if (dictating) Icons.Rounded.Stop else Icons.Rounded.Mic, if (dictating) uiText("Stop listening") else uiText("Ask by voice"),
                    tint = if (dictating) Gold else scheme.onSurface)
            }
        }
        // The orb: a tap to talk; during the conversation it shows listening, speaking or working, and a tap on it ends the conversation.
        IconButton(p.onOrb, Modifier.onGloballyPositioned { onOrb(it.boundsInRoot()) }.testTag("assist-live")
            .semantics { if (live) stateDescription = p.status }) {
            LiveOrb(p.face, clock, p.voice, motion) {
                DustCore(clock, 38.dp) {
                    if (live) Icon(Icons.Rounded.Stop, uiText("End call"), Modifier.size(18.dp), tint = Color(0xFFFFF3D6))
                    else Icon(Icons.Rounded.GraphicEq, assistantText("Talk to Beacon"), Modifier.size(20.dp), tint = Color(0xFFFFF3D6))
                }
            }
        }
    }
}

/** A gold pearl: the send button's face. */
@Composable private fun Pearl(size: Dp, content: @Composable () -> Unit) {
    Box(Modifier.size(size).drawWithCache {
        val r = this.size.minDimension / 2f
        val mid = Offset(this.size.width / 2f, this.size.height / 2f)
        val body = Brush.radialGradient(listOf(Color(0xFFFFE6A6), Color(0xFFF0AE3C), GoldDeep), mid + Offset(-r * .3f, -r * .35f), r * 1.5f)
        val shine = Brush.radialGradient(listOf(Color.White.copy(alpha = .55f), Color.Transparent), mid + Offset(-r * .35f, -r * .45f), r * .55f)
        onDrawBehind { drawCircle(body, r); drawCircle(shine, r * .55f, mid + Offset(-r * .35f, -r * .45f)) }
    }, contentAlignment = Alignment.Center) { content() }
}

/**
 * The live button's face: a dark jewel with a core of gold dust that turns slowly inside it and glows, the same dust as the aurora.
 * With reduced motion the dust holds still.
 */
@Composable private fun DustCore(clock: FloatState, size: Dp, content: @Composable () -> Unit) {
    val specks = remember { val random = kotlin.random.Random(73); FloatArray(18 * 3) { random.nextFloat() } }
    Box(Modifier.size(size).drawWithCache {
        val r = this.size.minDimension / 2f
        val mid = Offset(this.size.width / 2f, this.size.height / 2f)
        val jewel = Brush.radialGradient(listOf(Color(0xFF3A2A12), Color(0xFF140E06)), mid + Offset(-r * .2f, -r * .25f), r * 1.2f)
        val core = Brush.radialGradient(listOf(GoldBright.copy(alpha = .95f), Gold.copy(alpha = .55f), Color.Transparent), mid, r * .9f)
        val speck = 1.1.dp.toPx()
        onDrawBehind {
            val t = clock.floatValue
            drawCircle(jewel, r)
            drawCircle(core, r * (.86f + .06f * sin(t * 2.1f)), blendMode = BlendMode.Plus)
            for (i in 0 until 18) {
                val orbit = r * (.22f + .62f * specks[i * 3])
                val angle = specks[i * 3 + 1] * 6.2832f + t * (.7f + .9f * (1f - specks[i * 3])) * (if (i % 3 == 0) -1f else 1f)
                val twinkle = .45f + .55f * (.5f + .5f * sin(t * 3f + specks[i * 3 + 2] * 6.2832f))
                drawCircle(Color(0xFFFFF1CC).copy(alpha = twinkle), speck * (.8f + specks[i * 3 + 2]), mid + Offset(cos(angle) * orbit, sin(angle) * orbit * .8f), blendMode = BlendMode.Plus)
            }
            drawCircle(GoldBright.copy(alpha = .55f), r - .6.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
    }, contentAlignment = Alignment.Center) { content() }
}

// --- the chips ---

/** The chips' inset from the screen's edges, the same as the pill's. */
private val ChipInset = 16.dp
private val ChipPadding = 14.dp

@Composable private fun Chips(c: AssistOverlayController, vm: BeaconViewModel, shows: Int, motion: Boolean, field: FocusRequester, locked: Boolean) {
    val ui by vm.ui.collectAsState()
    val chat by vm.chat.collectAsState()
    val assistantChat = chat.sessionId.isNotBlank() && vm.isBeaconConversation()
    val waiting = if (assistantChat && (chat.approval != null || chat.clarification != null)) AssistChip.Waiting(approval = chat.approval != null) else null
    val last = remember(ui.sessions, chat.sessionId, ui.profile) { lastChat(ui.sessions, if (assistantChat) chat.sessionId else "", ui.profile) }
    val chips = assistChips(c.screen != null, locked, last, waiting)
    if (chips.isEmpty()) return
    val labels = chips.map { chipLabel(it) }
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val scroll = rememberScrollState()
    // The row reaches the screen's edges, its chips inset like the pill. At rest every chip fits whole (fitChips); should one ever
    // reach past an edge, the row scrolls and fades there instead of cutting it.
    BoxWithConstraints(Modifier.bleed(ChipInset).fillMaxWidth()) {
        val room = constraints.maxWidth - with(density) { (ChipInset * 2).roundToPx() }
        val fitted = remember(chips, labels, room, style, density) {
            with(density) {
                // A chip is its padding, its icon and the gap after it, then its label.
                val chrome = (ChipPadding * 2 + 18.dp + 8.dp).roundToPx() + 1
                fitChips(chips, room, 8.dp.roundToPx(), (56.dp + 112.dp * fontScale).roundToPx()) { chip ->
                    chrome + measurer.measure(labels[chips.indexOf(chip)], style, maxLines = 1, softWrap = false).size.width
                }
            }
        }
        Row(Modifier.fillMaxWidth().edgeFade(scroll, rtl).pointerInput(Unit) { detectTapGestures { } }.horizontalScroll(scroll)
            .padding(horizontal = ChipInset, vertical = 4.dp).testTag("assist-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fitted.forEachIndexed { index, (chip, width) ->
                Chip(c, chip, labels[chips.indexOf(chip)], index, shows, motion, field, Modifier.widthIn(max = with(density) { width.toDp() }))
            }
        }
    }
}

@Composable private fun chipLabel(chip: AssistChip): String = when (chip) {
    is AssistChip.Waiting -> if (chip.approval) uiText("Approval needed") else uiText("Question for you")
    AssistChip.AboutScreen -> uiText("Ask about this screen")
    is AssistChip.Continue -> uiText("Continue: {0}").replace("{0}", chip.title)
}

@Composable private fun Chip(c: AssistOverlayController, chip: AssistChip, label: String, index: Int, shows: Int, motion: Boolean, field: FocusRequester, modifier: Modifier) {
    // The chips rise in one after another once the pill has formed.
    val rise = remember(shows, chip) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(shows, chip, motion) {
        if (motion) { delay(200L + index * 70L); rise.animateTo(1f, tween(220)) } else rise.snapTo(1f)
    }
    // Pressed, a chip lifts a little toward the finger and its rim brightens.
    val press = remember { MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val lift by animateFloatAsState(if (pressed) 1f else 0f, if (motion) spring(stiffness = Spring.StiffnessMediumLow) else snap(), label = "Chip lift")
    val icon = when (chip) {
        is AssistChip.Waiting -> Icons.Rounded.HourglassTop
        AssistChip.AboutScreen -> Icons.Rounded.CropFree
        is AssistChip.Continue -> Icons.Rounded.History
    }
    Row(
        modifier.graphicsLayer {
            alpha = rise.value
            translationY = (1f - rise.value) * 12.dp.toPx() - lift * 3.dp.toPx()
            scaleX = 1f + .03f * lift; scaleY = 1f + .03f * lift
        }.heightIn(min = 48.dp).glass(RoundedCornerShape(24.dp), rim = { .36f + .55f * lift }, halo = .5f * lift)
            .clickable(press, LocalIndication.current) {
                when (chip) {
                    is AssistChip.Waiting -> c.open(AssistTarget(AssistPurpose.OpenAssistant), OverlayAction.OpenWaiting)
                    AssistChip.AboutScreen -> { c.screenAttached = true; runCatching { field.requestFocus() } }
                    is AssistChip.Continue -> c.open(AssistTarget(AssistPurpose.OpenChat, chip.sessionId, chip.profile), OverlayAction.ContinueChat)
                }
            }.padding(horizontal = ChipPadding).testTag("assist-chip-$index"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (chip is AssistChip.Waiting) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Lets a row reach [by] past both sides of its parent's padding, out to the screen's edges. */
private fun Modifier.bleed(by: Dp) = layout { measurable, constraints ->
    val extra = (by * 2).roundToPx()
    val row = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(constraints.maxWidth, row.height) { row.place(-by.roundToPx(), 0) }
}

/** A 24 dp fade at whichever edge the row continues past, so a chip there fades out instead of being cut; mirrored right to left. */
private fun Modifier.edgeFade(scroll: ScrollState, rtl: Boolean) = graphicsLayer {
    compositingStrategy = if (scroll.maxValue > 0) CompositingStrategy.Offscreen else CompositingStrategy.Auto
}.drawWithContent {
    drawContent()
    if (scroll.maxValue <= 0) return@drawWithContent
    val fade = 24.dp.toPx()
    val (left, right) = if (rtl) scroll.canScrollForward to scroll.canScrollBackward else scroll.canScrollBackward to scroll.canScrollForward
    if (left) drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), 0f, fade), size = Size(fade, size.height), blendMode = BlendMode.DstIn)
    if (right) drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), size.width - fade, size.width), Offset(size.width - fade, 0f), Size(fade, size.height), blendMode = BlendMode.DstIn)
}

// --- the written answer ---

/**
 * The written answer. Stateless, so the overlay and a Compose test run the same card: [onClose] puts it away, [onReveal] asks for the
 * unlock behind a locked answer, [onOpen] opens the chat. [locked] hides the owner's question like the reply (see [answerQuestion]).
 */
@Composable internal fun AnswerCard(ask: OverlayAsk, vm: BeaconViewModel?, clock: FloatState, motion: Boolean, locked: Boolean, onClose: () -> Unit, onReveal: () -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember { mutableFloatStateOf(0f) }
    val scroll = rememberScrollState()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .55f).dp
    LaunchedEffect(ask.answer.length) { scroll.animateScrollTo(scroll.maxValue) }
    val shape = RoundedCornerShape(28.dp)
    val streaming = ask.streaming && !ask.failed
    // The gold edge shimmers while the answer streams in, then rests.
    val shimmer by animateFloatAsState(if (streaming) 1f else 0f, if (motion) tween(600) else snap(), label = "Answer shimmer")
    val light = scheme.background.luminance() > .5f
    val sparkle = remember { val random = kotlin.random.Random(29); FloatArray(14 * 3) { random.nextFloat() } }
    // The answer reads a step larger than the question, which stays small and quiet above it.
    val typography = MaterialTheme.typography.let { it.copy(bodyLarge = it.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp)) }
    Column(
        Modifier.fillMaxWidth().offset { IntOffset(0, drag.roundToInt()) }
            // Swiping the card down puts it away and leaves the pill.
            .draggable(rememberDraggableState { drag = (drag + it).coerceAtLeast(0f) }, Orientation.Vertical,
                onDragStopped = { if (drag > with(density) { 96.dp.toPx() }) onClose(); drag = 0f })
            .heightIn(max = maxHeight).glass(shape, solid = true, rim = { .4f + .4f * shimmer }, halo = .3f)
            .drawWithContent {
                drawContent()
                if (shimmer <= .01f) return@drawWithContent
                // A band of gold light runs along the top edge, with dust sparkling just under it.
                val t = clock.floatValue
                val w = size.width
                val span = w * .45f
                val sweep = ((t * .42f) % 1f) * (w + 2 * span) - span
                val x = if (rtl) w - sweep else sweep
                drawRect(Brush.verticalGradient(listOf(Gold.copy(alpha = (if (light) .1f else .14f) * shimmer), Color.Transparent), 0f, 28.dp.toPx()), size = Size(w, 28.dp.toPx()))
                drawRect(Brush.horizontalGradient(listOf(Color.Transparent, GoldBright.copy(alpha = .95f * shimmer), Color.Transparent), x - span, x + span), size = Size(w, 2.dp.toPx()))
                for (i in 0 until 14) {
                    val px = ((sparkle[i * 3] + t * .03f * (if (i % 2 == 0) 1f else -1f)) % 1f + 1f) % 1f * w
                    val py = (2f + 10f * sparkle[i * 3 + 1]) * density.density
                    val twinkle = .5f + .5f * sin(t * (2f + 2f * sparkle[i * 3 + 2]) + sparkle[i * 3] * 6.2832f)
                    drawCircle(if (light) GoldDeep.copy(alpha = .8f * twinkle * shimmer) else GoldBright.copy(alpha = .9f * twinkle * shimmer), (.8f + 1.2f * sparkle[i * 3 + 2]) * density.density, Offset(px, py))
                }
            }
            .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp).testTag("assist-answer"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Locked, the owner's question is not composed at all (no text for the screen or a service to read); the close keeps its place.
            val question = answerQuestion(locked, ask)
            if (question.isNotEmpty()) Text(question, Modifier.weight(1f).padding(vertical = 8.dp).testTag("assist-answer-question"), style = MaterialTheme.typography.labelLarge.merge(ContentDirection), color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            else Spacer(Modifier.weight(1f))
            IconButton(onClose, Modifier.testTag("assist-answer-close")) { Icon(Icons.Rounded.Close, uiText("Close"), tint = scheme.onSurfaceVariant) }
        }
        val body = answerBody(locked, ask)
        when (body) {
            // Locked, the reply never shows a word before an unlock; the unlock brings the overlay back with it.
            AnswerBody.Locked -> Row(Modifier.padding(end = 6.dp, bottom = 4.dp).testTag("assist-unlock-to-see"), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(20.dp), tint = Gold)
                Text(uiText("Unlock to see your answer"), Modifier.weight(1f).padding(start = 12.dp).semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                TextButton(onReveal, Modifier.heightIn(min = 48.dp).testTag("assist-unlock")) { Text(uiText("Unlock")) }
            }
            AnswerBody.Thinking -> Text(uiText("Thinking…"), Modifier.padding(bottom = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }
                // A slow gold light passes over the word while Beacon thinks.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (!motion) return@drawWithContent
                    val w = size.width
                    val sweep = ((clock.floatValue * .6f) % 1f) * (w * 2f) - w * .5f
                    val x = if (rtl) w - sweep else sweep
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Gold, Color.Transparent), x - w * .35f, x + w * .35f), blendMode = BlendMode.SrcAtop)
                },
                style = typography.bodyLarge, color = scheme.onSurfaceVariant)
            // TalkBack reads the answer once, when it is complete, not again with every piece that streams in.
            AnswerBody.Text -> key(ask.streaming) {
                Box(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(end = 14.dp, bottom = 4.dp).testTag("assist-answer-text")
                    .semantics { if (!ask.streaming) liveRegion = LiveRegionMode.Polite }) {
                    MaterialTheme(colorScheme = scheme, typography = typography, shapes = MaterialTheme.shapes) {
                        if (vm != null) Markdown(ask.answer, vm, interactive = false) else Text(ask.answer, style = typography.bodyLarge.merge(ContentDirection), color = scheme.onSurface)
                    }
                }
            }
            AnswerBody.Empty -> Unit
        }
        if (body == AnswerBody.Text && !ask.streaming) TextButton(onOpen, Modifier.heightIn(min = 48.dp).testTag("assist-open")) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(uiText("Open in Beacon"))
        }
    }
}

// --- one short line instead of a silent failure ---

@Composable private fun LineRow(c: AssistOverlayController, line: OverlayLine, callFailure: CallReason?) {
    val onAction: () -> Unit = when (lineAction(line, callFailure)) {
        // Asks Android for the unlock again; the overlay stays, and after the unlock it comes back with the same conversation.
        LineAction.Unlock -> c::unlock
        LineAction.Allow -> c::openPermissions
        // Both open Beacon; locked, the phone unlocks first (needsUnlock). SignIn lands on the sign-in the app shows.
        LineAction.Open, LineAction.SignIn -> { { c.open(AssistTarget(AssistPurpose.OpenAssistant)) } }
        LineAction.OpenWaiting -> { { c.open(AssistTarget(AssistPurpose.OpenAssistant), OverlayAction.OpenWaiting) } }
        LineAction.TryAgain -> c::retry
        LineAction.CallAgain -> c::startLive
        null -> { {} }
    }
    LineRowBody(line, callFailure, onAction, c::clearLine)
}

/** The line itself, without the controller: its words, its one action ([onAction]) and, except for the unlock line, its close ([onClear]). */
@Composable internal fun LineRowBody(line: OverlayLine, callFailure: CallReason?, onAction: () -> Unit, onClear: () -> Unit) {
    val text = when (line) {
        OverlayLine.Microphone -> uiText("Allow the microphone to talk")
        OverlayLine.VoiceKey -> uiText("Add a voice key in Beacon")
        OverlayLine.SignIn -> uiText("Sign in to Beacon")
        OverlayLine.NotHeard -> uiText("Didn't catch that")
        OverlayLine.NoRecognizer -> uiText("Voice typing isn't available")
        OverlayLine.NotSent -> uiText("Couldn't send")
        // The short reason the call service gave (it names whose side failed) without its second instruction, which the action carries.
        OverlayLine.CallFailed -> when (callFailure) {
            CallReason.HermesNoAnswer -> uiText("Hermes didn't answer")
            CallReason.VoiceNoAnswer -> uiText("The voice service didn't answer")
            CallReason.MicFailed -> uiText("The microphone didn't start")
            CallReason.Dropped -> uiText("The call dropped")
            CallReason.VoiceStopped -> uiText("Voice stopped answering")
            CallReason.SignInExpired -> uiText("Your sign-in expired")
            null -> uiText("Couldn't start the call")
        }
        OverlayLine.Waiting -> uiText("Waiting for you in Beacon")
        OverlayLine.Unlock -> uiText("Unlock your phone first.")
    }
    val action = lineAction(line, callFailure)
    val label = when (action) {
        LineAction.Unlock -> uiText("Unlock")
        LineAction.Allow -> uiText("Allow")
        LineAction.Open, LineAction.OpenWaiting -> uiText("Open")
        LineAction.SignIn -> uiText("Sign in")
        LineAction.TryAgain -> uiText("Try again")
        LineAction.CallAgain -> uiText("Call again")
        null -> null
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).glass(RoundedCornerShape(24.dp), solid = true, halo = .2f).padding(start = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("assist-line"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f).padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        label?.let { TextButton(onAction, Modifier.heightIn(min = 48.dp).testTag("assist-line-action")) { Text(it) } }
        // The unlock line is not closed: the phone is still locked, so Back or a tap outside closes the overlay, and the action asks again.
        if (line == OverlayLine.Unlock) Spacer(Modifier.width(8.dp))
        else IconButton(onClear) { Icon(Icons.Rounded.Close, uiText("Close"), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
