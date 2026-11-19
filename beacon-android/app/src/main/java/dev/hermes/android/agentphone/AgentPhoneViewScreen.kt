package dev.hermes.android.agentphone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MobileOff
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhonelinkErase
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.SwitchAccount
import androidx.compose.ui.draw.blur
import dev.hermes.android.ui.SettingsStatusTint
import dev.hermes.android.ui.rememberSettingsStatusPalette
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardHide
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import dev.hermes.android.data.AGENT_PHONE_DIDNT_START
import dev.hermes.android.data.AgentPhoneAction
import dev.hermes.android.data.AgentPhoneLine
import dev.hermes.android.data.agentPhoneLine
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.ui.BeaconDustCore
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.SettingsAction
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.agentPhoneText
import dev.hermes.android.ui.named
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.uiText
import dev.hermes.android.ui.uiTextLookup
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The setup view's screen (EMU-DESIGN §4.4, §8): the agent phone's live picture with a slim strip above ("Private · Live", Done) and a
 * phone-style bar below (Back, Home, Recents, Keyboard, Paste). Stateless: [AgentPhoneActivity] feeds it the session's [ViewUi] and the
 * decoded frame, so every state can be drawn and captured with a synthetic frame.
 */

/** Beacon's own text row. Its text lives in memory only: never in saved state, a log or a file. */
internal class TextRowState {
    var open by mutableStateOf(false)
    var value by mutableStateOf(TextFieldValue(""))
    /** The row shows dots and offers saved passwords. */
    var hidden by mutableStateOf(false)
    /** Opened by itself because a text field on the agent phone took focus (then it also closes by itself). */
    var auto by mutableStateOf(false)
    /** The owner closed the row while the phone's keyboard was up: it stays closed until that keyboard goes and comes back. */
    var closedByOwner by mutableStateOf(false)
    val mirror = TextMirror()

    fun clear() { value = TextFieldValue(""); mirror.reset() }
}

/** What the screen can ask of the view. */
internal class ViewActions(
    val touch: (List<TouchPoint>) -> Unit = {},
    val key: (String) -> Boolean = { false },
    val text: (String) -> Boolean = { false },
    val paste: () -> ViewSession.Paste = { ViewSession.Paste.NotReady },
    val done: () -> Unit = {},
    val close: () -> Unit = {},
    val retry: () -> Unit = {},
    val turnOn: () -> Unit = {},
)

private const val FLASH_MILLIS = 4_000L
/** How long Beacon's own No to the debugging prompt has before the owner is offered Cancel (Root, 17 Nov). */
internal const val PROMPT_CANCEL_AFTER_MILLIS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentPhoneViewScreen(
    ui: ViewUi,
    frame: ShownFrame?,
    name: String,
    actions: ViewActions,
    modifier: Modifier = Modifier,
    row: TextRowState = remember { TextRowState() },
    checklist: (@Composable () -> Unit)? = null,
    checklistOpen: Boolean = false,
    /** Whether opening the text row also brings up the owner's keyboard (off only in the capture test). */
    rowKeyboard: Boolean = true,
) = CompositionLocalProvider(LocalAssistantName provides name) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < .5f
    // One quiet surface around the picture, so the phone's own screen is the brightest thing in view.
    val stage = if (dark) scheme.surfaceContainerLowest else scheme.surfaceContainer
    val live = ui.phase == ViewPhase.Live
    var flash by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var flashes by remember { mutableStateOf(0L) }
    fun say(line: String) { if (line.isNotBlank()) { flashes++; flash = line to flashes } }
    val lookup = uiTextLookup()  // a host line's words: its code's own template, translated (revision 5 §11.1)
    val lowered = uiText("Picture lowered for a slow connection")
    val textRefused = uiText("Couldn't type that. Try again.")
    val pasteRefused = uiText("Couldn't paste that. Try again.")
    val nothingToPaste = uiText("Nothing to paste")
    val tooLong = uiText("Too long to paste")
    val asciiOnly = uiText("Only English letters work right now.")
    LaunchedEffect(ui.flash?.id) {
        val shown = ui.flash ?: return@LaunchedEffect
        say(when (shown.kind) {
            FlashKind.Lowered -> lowered
            FlashKind.Notice -> noticeWords(shown, name, lookup)
            FlashKind.TextRefused -> textRefused
            FlashKind.PasteRefused -> pasteRefused
        })
    }
    LaunchedEffect(flash?.second) { if (flash != null) { delay(FLASH_MILLIS); flash = null } }
    // A new socket or a refused text: the phone no longer has what the row shows, so the row starts over.
    var seenReset by remember { mutableStateOf(ui.textReset) }
    LaunchedEffect(ui.textReset) { if (ui.textReset != seenReset) { seenReset = ui.textReset; row.clear() } }

    var asciiWarned by remember { mutableStateOf(false) }
    fun sync(flush: Boolean) {
        val complete = row.mirror.sync(row.value.text, flush) { step ->
            when (step) {
                is TextStep.Backspace -> (1..step.count).all { actions.key("backspace") }
                is TextStep.Type -> actions.text(step.text)
            }
        }
        if (!complete && flush && ui.helper != "ready" && !asciiWarned) { asciiWarned = true; say(asciiOnly) }
        if (complete) asciiWarned = false
    }
    fun closeRow() { sync(flush = true); row.clear(); row.open = false; row.auto = false }
    // The phone's own keyboard came up (a field there has focus): Beacon's row comes up with the owner's keyboard; it goes when that does.
    LaunchedEffect(ui.keyboard, live) {
        if (!live) return@LaunchedEffect
        if (ui.keyboard && !row.open && !row.closedByOwner) { row.open = true; row.auto = true; row.hidden = ui.keyboardPassword }
        if (!ui.keyboard) { row.closedByOwner = false; if (row.open && row.auto && row.value.text.isEmpty()) { row.open = false; row.auto = false } }
    }
    // A password field took the phone's keyboard (revision 5 §11.2): an empty row the phone opened goes hidden and offers the
    // owner's password manager; back to plain for a plain field.
    LaunchedEffect(ui.keyboardPassword) { if (row.open && row.auto && row.value.text.isEmpty()) row.hidden = ui.keyboardPassword }
    LaunchedEffect(row.value.text) { if (row.value.text.isNotEmpty()) { delay(600); sync(flush = true) } }
    var sheet by remember { mutableStateOf(checklistOpen) }
    // Back closes the text row first (the owner's keyboard closes before that, by itself).
    BackHandler(row.open) { closeRow() }

    val waiting = ui.phase in setOf(ViewPhase.Reconnecting, ViewPhase.BackSoon)
    // What the strip says instead of "Private · Live" for a moment: a drop that sorts itself out, or a short note. Never on the picture.
    val note = when {
        waiting && frame != null -> (if (ui.phase == ViewPhase.BackSoon) uiText("Back in a moment") else uiText("Reconnecting…")) to true
        flash != null && !(live && ui.prompt) && !waiting -> flash!!.first to false
        else -> null
    }
    val picture: @Composable (Modifier) -> Unit = { area ->
        Box(area) {
            val ended = ui.phase !in setOf(ViewPhase.Live, ViewPhase.Connecting, ViewPhase.Starting, ViewPhase.Reconnecting, ViewPhase.BackSoon)
            FrameCanvas(
                frame = frame,
                // Edge to edge: the picture takes all the width its ratio allows (AGENTUX-121 idea 4).
                modifier = Modifier.fillMaxSize().then(if (ended) Modifier.blur(18.dp) else Modifier).testTag("agent-phone-frame"),
                dim = if (ended) .72f else if (!live || ui.background) .55f else 0f,
                enabled = live && !ui.prompt,
                description = uiText("{name}'s phone screen").named(),
                edge = if (dark) Color.White.copy(alpha = .10f) else Color.Black.copy(alpha = .10f),
                onFingerDown = { if (row.value.text.isNotEmpty()) { sync(flush = true); row.clear() } },
                onTouch = actions.touch,
            )
            when {
                ui.phase == ViewPhase.Connecting || ui.phase == ViewPhase.Starting || waiting && frame == null ->
                    CenterStatus(when (ui.phase) {
                        ViewPhase.Starting -> uiText("Starting {name}'s phone…").named()
                        ViewPhase.BackSoon -> uiText("Back in a moment")
                        ViewPhase.Reconnecting -> uiText("Reconnecting…")
                        else -> uiText("Opening {name}'s phone…").named()
                    })
                ended -> EndCard(ui, name, actions, Modifier.align(Alignment.Center))
            }
            Reveal(live && ui.prompt, Modifier.align(Alignment.TopCenter)) {
                PromptCard(ui.promptSince, onDismiss = { actions.key("back") })
            }
        }
    }
    val bottom: @Composable (Modifier) -> Unit = { area ->
        if (row.open) TextRow(row, ui, live, actions, area, rowKeyboard, onSync = ::sync, onClose = { closeRow(); if (ui.keyboard) row.closedByOwner = true })
        else BottomBar(live, ui, area, actions, ::say,
            onKeyboard = { row.open = true; row.auto = false; row.closedByOwner = false },
            onPaste = {
                when (actions.paste()) {
                    ViewSession.Paste.Empty -> say(nothingToPaste)
                    ViewSession.Paste.TooLong -> say(tooLong)
                    ViewSession.Paste.NotReady, ViewSession.Paste.Sent -> Unit
                }
            })
    }
    val insetBottom = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).imePadding()

    BoxWithConstraints(modifier.fillMaxSize().background(stage).testTag("agent-phone-view")) {
        // The unfolded phone (AGENTUX-121 idea 7): the picture fills the height on one side, the page's setup steps sit beside it.
        // Only where the side pane costs the picture nothing: a window about as wide as it is tall (the unfolded phone, a tablet on its
        // side). A tall window (a tablet upright) keeps one pane, and a phone turned on its side keeps the whole width for its picture.
        val wide = maxWidth >= WIDE_VIEW && maxHeight >= 480.dp && maxWidth >= maxHeight * .75f
        if (wide) Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                picture(Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Start)).padding(top = 12.dp))
                bottom(insetBottom)
            }
            SidePane(live, note, checklist, actions.done, Modifier.width(SIDE_PANE).fillMaxHeight())
        } else Column(Modifier.fillMaxSize()) {
            TopStrip(live, note, checklist != null, onChecklist = { sheet = true }, onDone = actions.done)
            picture(Modifier.weight(1f).fillMaxWidth())
            bottom(insetBottom)
        }
        if (sheet && checklist != null && !wide) ModalBottomSheet(onDismissRequest = { sheet = false }, modifier = Modifier.testTag("agent-phone-checklist")) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) { checklist() }
        }
    }
}

/** From this width the view shows its two panes (the unfolded phone, a tablet), and the side pane's width there. */
private val WIDE_VIEW = 600.dp
private val SIDE_PANE = 340.dp

/**
 * A notice from the host in the app's words. A saved login names only its app (P3-8: the strip shows no account on screen); the
 * identifier stays in the activity list.
 */
internal fun noticeWords(shown: Flash, name: String, lookup: (String) -> String): String {
    if (shown.reason == "vault.saved") return AgentPhoneLines.words("vault.saved_label", shown.line, shown.args, name, lookup)
    val older = serverLine(shown.line)
    if (shown.reason == null && older.template == "Saved: {0} · {1}") return PhoneLine(lookup("Saved: {0}"), older.args.take(1)).shown()
    return AgentPhoneLines.words(shown.reason, shown.line, shown.args, name, lookup)
}

/** Slides a note in from the top and fades it out (outside any Row or Column scope, so the plain AnimatedVisibility is the one used). */
@Composable private fun Reveal(visible: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) { content() }
}

@Composable private fun TopStrip(live: Boolean, note: Pair<String, Boolean>?, checklist: Boolean, onChecklist: () -> Unit, onDone: () -> Unit) {
    val scale = LocalDensity.current.fontScale
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { StatusSlot(live, note) }
        if (checklist) {
            val label = uiText("Setup steps")
            if (scale < 1.3f) TextButton(onClick = onChecklist, modifier = Modifier.heightIn(min = 48.dp).testTag("agent-phone-steps")) {
                Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else IconButton(onClick = onChecklist, modifier = Modifier.size(48.dp).testTag("agent-phone-steps")) { Icon(Icons.Rounded.Checklist, label) }
        }
        DoneButton(onDone)
    }
}

@Composable private fun DoneButton(onDone: () -> Unit) {
    FilledTonalButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag("agent-phone-done")) {
        Text(uiText("Done"), maxLines = 1, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The strip's left end: "Private · Live" while live, or for a moment a note in its place ([note]: the words, and whether it is a drop
 * that sorts itself out). Notes and drops stay off the picture (AGENTUX-121 P3-8).
 */
@Composable private fun StatusSlot(live: Boolean, note: Pair<String, Boolean>?) {
    val motion = rememberNativeMotionEnabled()
    AnimatedContent(note, label = "agent-phone-status", transitionSpec = {
        if (motion) fadeIn() + slideInVertically { -it / 3 } togetherWith fadeOut() + slideOutVertically { it / 3 } else fadeIn(androidx.compose.animation.core.snap()) togetherWith fadeOut(androidx.compose.animation.core.snap())
    }) { shown ->
        when {
            shown != null -> Note(shown.first, working = shown.second)
            live -> LivePill()
            else -> Spacer(Modifier.height(1.dp))
        }
    }
}

/**
 * The view beside the picture on a wide window: the status and Done on top, {name}'s phone named, and the setup steps the phone page
 * lists, read whole instead of in a sheet.
 */
@Composable private fun SidePane(live: Boolean, note: Pair<String, Boolean>?, checklist: (@Composable () -> Unit)?, onDone: () -> Unit, modifier: Modifier) {
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)).padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 16.dp)
        .testTag("agent-phone-side"), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { StatusSlot(live, note) }
            DoneButton(onDone)
        }
        Text(uiText("{name}'s phone").named(), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
        if (checklist != null) Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).testTag("agent-phone-side-steps"),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Setup steps"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            checklist()
        }
    }
}

/**
 * "Private · Live": the picture is only on this phone, nothing is recorded, and it is live. When the words do not fit (2x text on a
 * narrow phone, a long translation) the pill keeps its lock and live dot and TalkBack still reads the words.
 */
@Composable private fun LivePill() {
    val scheme = MaterialTheme.colorScheme
    val motion = rememberNativeMotionEnabled()
    // An infinite transition, so UI tests can idle while it runs; still when the system turns animations off.
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "live-dot")
    val breathing by pulse.animateFloat(1f, .35f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900),
        androidx.compose.animation.core.RepeatMode.Reverse), label = "live-dot-alpha")
    val dot = if (motion) breathing else 1f
    val words = uiText("Private · Live")
    val fontScale = LocalDensity.current.fontScale
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    var compact by remember(fontScale, width, words) { mutableStateOf(false) }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 7.dp)
            .semantics(mergeDescendants = true) { contentDescription = words }.testTag("agent-phone-live"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(Icons.Rounded.Lock, null, Modifier.size(15.dp), tint = scheme.onSurfaceVariant)
        if (!compact) Text(words, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false), onTextLayout = { if (it.isLineEllipsized(0) || it.didOverflowWidth) compact = true })
        Box(Modifier.size(7.dp).clip(CircleShape).background(scheme.primary.copy(alpha = dot)))
    }
}

@Composable private fun CenterStatus(line: String) {
    Column(Modifier.fillMaxSize().padding(24.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-status"),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        BeaconDustCore(72.dp)
        Spacer(Modifier.size(24.dp))
        Text(line, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
    }
}

/** A note in the strip: a drop that sorts itself out ([working] shows the dust core), or a short line for a few seconds. */
@Composable private fun Note(line: String, working: Boolean) {
    val scheme = MaterialTheme.colorScheme
    // A fixed corner, not a half-height one: at large text the note wraps, and a pill's ends would cut its letters off.
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(scheme.inverseSurface).padding(horizontal = 14.dp, vertical = 8.dp)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-banner"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (working) BeaconDustCore(18.dp, color = scheme.inversePrimary)
        Text(line, style = MaterialTheme.typography.bodyMedium.copy(hyphens = androidx.compose.ui.text.style.Hyphens.Auto),
            fontWeight = FontWeight.Medium, color = scheme.inverseOnSurface)
    }
}

/**
 * Android's own question that would let an app control the agent phone (its debugging prompt): Beacon has already said no. Said
 * without "debugging" (P3-4); Dismiss (Back on the phone) shows only if the question is still there after Beacon's No.
 */
@Composable private fun PromptCard(since: Long, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var cancel by remember(since) { mutableStateOf(false) }
    LaunchedEffect(since) { delay(PROMPT_CANCEL_AFTER_MILLIS); cancel = true }
    Surface(Modifier.padding(12.dp).widthIn(max = 520.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive }.testTag("agent-phone-prompt"),
        shape = RoundedCornerShape(24.dp), color = scheme.errorContainer, contentColor = scheme.onErrorContainer, shadowElevation = 8.dp) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Rounded.GppBad, null, Modifier.size(28.dp), tint = scheme.error)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText("An app tried to take control of {name}'s phone. Beacon blocked it.").named(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (cancel) Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("agent-phone-prompt-cancel")) { Text(uiText("Dismiss")) }
            }
        }
    }
}

/**
 * Where the view stands when it is not live: one solid card in the middle (the picture behind it blurred and dimmed, so the card is the
 * only thing to read), a status tile in the kit's own colours, a title that takes as many lines as it needs at any text size, one line,
 * and the actions.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun EndCard(ui: ViewUi, name: String, actions: ViewActions, modifier: Modifier) {
    val close = SettingsAction(uiText("Close"), actions.close, testTag = "agent-phone-close")
    val again = SettingsAction(uiText("Try again"), actions.retry, testTag = "agent-phone-retry")
    data class Card(val status: SettingsStatus, val icon: ImageVector, val title: String, val line: String, val primary: SettingsAction, val secondary: SettingsAction?)
    val card = when (ui.phase) {
        ViewPhase.Replaced -> Card(SettingsStatus.Attention, Icons.Rounded.Devices, uiText("Opened on another phone"), "", SettingsAction(uiText("Open here"), actions.retry, testTag = "agent-phone-open-here"), close)
        ViewPhase.Ended -> Card(SettingsStatus.Off, Icons.Rounded.PhoneAndroid, uiText("{name} has its phone back").named(), uiText("Open it again to keep setting it up."), SettingsAction(uiText("Open again"), actions.retry, testTag = "agent-phone-open-again"), close)
        ViewPhase.Stopped -> Card(SettingsStatus.Off, Icons.Rounded.PowerSettingsNew, uiText("{name}'s phone is off").named(), "", SettingsAction(uiText("Turn on"), actions.turnOn, testTag = "agent-phone-turn-on"), close)
        ViewPhase.StartRefused -> Card(SettingsStatus.Error, Icons.Rounded.ErrorOutline, uiText("{name}'s phone can't start").named(),
            agentPhoneText(agentPhoneLine(ui.line, DIDNT_START, ui.lineReason, ui.lineArgs) ?: DIDNT_START),
            again.copy(onClick = actions.turnOn), close)
        ViewPhase.Removed -> Card(SettingsStatus.Error, Icons.Rounded.PhonelinkErase, uiText("Can't open {name}'s phone here").named(), uiText("Open it again from Settings."), close, null)
        ViewPhase.Failed -> Card(SettingsStatus.Error, Icons.Rounded.ErrorOutline, uiText("The picture stopped"), "", again, close)
        ViewPhase.HermesDown -> Card(SettingsStatus.Error, Icons.Rounded.CloudOff, uiText("Hermes isn't answering"), uiText("Check that the Hermes computer is on."), again, close)
        ViewPhase.NotSetUp -> Card(SettingsStatus.Off, Icons.Rounded.MobileOff, uiText("{name}'s phone isn't set up yet.").named(), "", close, null)
        ViewPhase.NotYours -> Card(SettingsStatus.Attention, Icons.Rounded.SwitchAccount, uiText("{name}'s phone belongs to another profile.").named(), uiText("Switch to that profile to open it."), close, null)
        ViewPhase.SignedOut -> Card(SettingsStatus.Attention, Icons.AutoMirrored.Rounded.Login, uiText("Sign in to Hermes first"), "", close, null)
        else -> return
    }
    val scheme = MaterialTheme.colorScheme
    val ink = rememberSettingsStatusPalette().ink(card.status)
    Surface(modifier.padding(20.dp).widthIn(max = 440.dp).testTag("agent-phone-end"), shape = RoundedCornerShape(28.dp),
        color = scheme.surfaceContainerHigh, contentColor = scheme.onSurface, shadowElevation = 12.dp) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(ink.copy(alpha = SettingsStatusTint)), contentAlignment = Alignment.Center) {
                Icon(card.icon, null, Modifier.size(28.dp), tint = ink)
            }
            Text(card.title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-end-title"),
                style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            if (card.line.isNotBlank()) Text(card.line, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
            FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = card.primary.onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(card.primary.testTag)) { Text(card.primary.label) }
                card.secondary?.let { TextButton(onClick = it.onClick, modifier = Modifier.heightIn(min = 48.dp).testTag(it.testTag)) { Text(it.label) } }
            }
        }
    }
}

/** A start the host refused with no words of its own, or a start that failed inside the phone's process (its words go to Details on the page). */
private val DIDNT_START = AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)

/**
 * A phone's own bar (Back, Home, Recent apps) and the view's tools (Keyboard, Paste), as icons: they read at a glance like any Android
 * phone, and they fit a narrow phone at 2x text in every language. TalkBack reads each name; a long press shows it in the banner.
 */
@Composable private fun BottomBar(live: Boolean, ui: ViewUi, modifier: Modifier, actions: ViewActions, say: (String) -> Unit, onKeyboard: () -> Unit, onPaste: () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
    Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 8.dp).testTag("agent-phone-bar"), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // Back stays on while the debugging prompt shows: it is the one key that answers it (No).
        BarButton(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Back"), live, "agent-phone-back", say) { actions.key("back") }
        BarButton(Icons.Rounded.RadioButtonUnchecked, uiText("Home screen"), live && !ui.prompt, "agent-phone-home", say) { actions.key("home") }
        BarButton(Icons.Rounded.CropSquare, uiText("Recent apps"), live && !ui.prompt, "agent-phone-recents", say) { actions.key("recents") }
        Box(Modifier.size(width = 1.dp, height = 24.dp).background(MaterialTheme.colorScheme.outlineVariant))
        BarButton(Icons.Rounded.Keyboard, uiText("Keyboard"), live && !ui.prompt, "agent-phone-keyboard", say, onKeyboard)
        BarButton(Icons.Rounded.ContentPaste, uiText("Paste"), live && !ui.prompt && ui.helper == "ready", "agent-phone-paste", say, onPaste)
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable private fun RowScope.BarButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, say: (String) -> Unit, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
            .combinedClickable(enabled = enabled, role = Role.Button, onClickLabel = label, onLongClick = { say(label) }, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(26.dp), tint = if (enabled) scheme.onSurface else scheme.onSurface.copy(alpha = .38f))
    }
}

@Composable private fun TextRow(row: TextRowState, ui: ViewUi, live: Boolean, actions: ViewActions, modifier: Modifier, showKeyboard: Boolean, onSync: (Boolean) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (showKeyboard) { runCatching { focus.requestFocus() }; keyboard?.show() } }
    val send by rememberUpdatedState(onSync)
    fun enter() { send(true); actions.key("enter"); row.clear() }
    fun backspace() {
        val text = row.value.text
        if (text.isEmpty()) actions.key("backspace")
        else { val shorter = TextMirror.dropLast(text); row.value = TextFieldValue(shorter, TextRange(shorter.length)); send(false) }
    }
    val hide = uiText("Hide text"); val show = uiText("Show text")
    Box(modifier, contentAlignment = Alignment.Center) {
    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp).testTag("agent-phone-row"), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.size(48.dp).testTag("agent-phone-row-close")) { Icon(Icons.Rounded.KeyboardHide, uiText("Hide keyboard")) }
        OutlinedTextField(
            value = row.value,
            onValueChange = { next -> row.value = next; send(false) },
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("agent-phone-text")
                .semantics { contentType = if (row.hidden) ContentType.Password else ContentType.Username }
                .onPreviewKeyEvent { event ->
                    // A hardware keyboard on an empty row drives the phone directly (Backspace, Tab, arrows).
                    if (event.type != KeyEventType.KeyDown || row.value.text.isNotEmpty()) return@onPreviewKeyEvent false
                    val name = when (event.key) { Key.Backspace -> "backspace"; Key.Tab -> "tab"; Key.DirectionLeft -> "left"; Key.DirectionRight -> "right"; Key.DirectionUp -> "up"; Key.DirectionDown -> "down"; else -> null }
                    name != null && actions.key(name)
                },
            enabled = live,
            singleLine = true,
            placeholder = { Text(uiText("Type here"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            visualTransformation = if (row.hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                keyboardType = if (row.hidden) KeyboardType.Password else KeyboardType.Text, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { enter() }),
            trailingIcon = {
                IconButton(onClick = { row.hidden = !row.hidden }, modifier = Modifier.size(48.dp).testTag("agent-phone-row-hide")) {
                    Icon(if (row.hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (row.hidden) show else hide)
                }
            },
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        RepeatIconButton(Icons.AutoMirrored.Rounded.Backspace, uiText("Delete"), live, "agent-phone-row-delete") { backspace() }
        IconButton(onClick = { enter() }, enabled = live, modifier = Modifier.size(48.dp).testTag("agent-phone-row-enter")) { Icon(Icons.AutoMirrored.Rounded.KeyboardReturn, uiText("Enter")) }
    }
    }
}

/** Repeats while held, like a keyboard's Backspace: once at the press, then every 70 ms after 400 ms. */
@Composable private fun RepeatIconButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, onRepeat: () -> Unit) {
    val action by rememberUpdatedState(onRepeat)
    Box(
        Modifier.size(48.dp).clip(CircleShape)
            .then(if (enabled) Modifier.pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        action()
                        val held = launch { delay(400); while (true) { action(); delay(70) } }
                        try { waitForUpOrCancellation() } finally { held.cancel() }
                    }
                }
            } else Modifier)
            .semantics { role = Role.Button; contentDescription = label; if (enabled) onClick(label) { action(); true } else disabled() }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) }
}
