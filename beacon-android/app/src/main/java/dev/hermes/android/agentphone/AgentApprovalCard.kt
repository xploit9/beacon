package dev.hermes.android.agentphone

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.isolateBidi
import dev.hermes.android.ui.BeaconDustCore
import dev.hermes.android.ui.Glass
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusTint
import dev.hermes.android.ui.nativeMetadataStyle
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.rememberSettingsStatusPalette
import dev.hermes.android.ui.named
import dev.hermes.android.ui.uiText

/** Where a card is in its life: asking, an answer on its way, the answer taken, or a line about why it can't be answered. */
internal sealed interface CardPhase {
    data object Asking : CardPhase
    data class Sending(val approved: Boolean) : CardPhase
    /** The plugin has the answer: the card says so briefly, then the next one comes. */
    data class Answered(val line: String, val approved: Boolean) : CardPhase
    /** The answer didn't go through and can be given again ([retry]), or the question is over (ended, answered elsewhere, signed out). */
    data class Problem(val line: String, val retry: Boolean) : CardPhase
}

/** One card on screen: its record, where it is in the queue, how much of its time is left (null: no clock, a Needs-you notice). */
internal data class ApprovalCardUi(
    val record: AgentApproval,
    val phase: CardPhase = CardPhase.Asking,
    val position: Int = 1,
    val count: Int = 1,
    val leftFraction: Float? = null,
    val leftSeconds: Long? = null,
    /** False for a moment after the card took the front, so the tap that answered the last card can't answer this one. */
    val answerable: Boolean = true,
)

/** The record's full time, for the time-left bar: 2 min for decisions, 10 min for a held login (approvals-v1 §2). */
internal fun approvalWindowSeconds(kind: ApprovalKind): Long? = when (kind) {
    ApprovalKind.Save, ApprovalKind.Update -> 600
    ApprovalKind.NeedsYou -> null
    else -> 120
}

/** A plugin sentence in the app's words: its template translated, the owner's own names isolated in its holes. Shared with the phone page. */
@Composable internal fun shownLine(value: PhoneLine): String = PhoneLine(uiText(value.template), value.args).shown()

private fun glyphIcon(glyph: ApprovalGlyph): ImageVector = when (glyph) {
    ApprovalGlyph.Payment -> Icons.Rounded.Payments
    ApprovalGlyph.Tap -> Icons.Rounded.TouchApp
    ApprovalGlyph.Send -> Icons.AutoMirrored.Rounded.Send
    ApprovalGlyph.Delete -> Icons.Rounded.Delete
    ApprovalGlyph.Install -> Icons.Rounded.InstallMobile
    ApprovalGlyph.Account -> Icons.Rounded.ManageAccounts
    ApprovalGlyph.Phone -> Icons.Rounded.Smartphone
    ApprovalGlyph.Login -> Icons.Rounded.Key
    ApprovalGlyph.Code -> Icons.Rounded.Pin
    ApprovalGlyph.NeedsYou -> Icons.Rounded.PanTool
}

/**
 * The question the agent's phone asks (approvals-v1 §4), on Beacon's glass: whose phone it is, what it wants in one heading, the amount
 * first when it is about money, where the input lands, the agent's purpose in its own words, and the two answers. Everything the record
 * says is the agent phone's screen text or the agent's words: shown as plain text, each piece isolated so a name in another script never
 * turns the sentence around. The code field lives only in memory and is emptied when the card leaves the screen.
 */
@Composable internal fun AgentApprovalCard(
    ui: ApprovalCardUi,
    onAnswer: (approved: Boolean, code: String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val face = remember(ui.record) { approvalFace(ui.record) }
    val palette = rememberSettingsStatusPalette()
    val scheme = MaterialTheme.colorScheme
    val ink = if (face.money) palette.attention else scheme.primary
    val motion = rememberNativeMotionEnabled()
    // In light the glass sits on a solid card, so its grey words and the Deny outline read over the dimmed screen (P3-15).
    val solid = if (scheme.background.luminance() > .5f) Modifier.background(scheme.surfaceContainerLowest, RoundedCornerShape(26.dp)) else Modifier
    Glass(modifier.fillMaxWidth().then(solid).testTag("agent-approval-card")) {
        Column(Modifier.animateContentSize(if (motion) tween(220) else snap())) {
            // How much time the agent still waits: a thin line that drains; read out once as "Time left", never ticking aloud.
            if (ui.leftFraction != null && ui.phase !is CardPhase.Answered) {
                val minutes = ((ui.leftSeconds ?: 0L) + 59) / 60
                val left = uiText("Time left: {0} min").replace("{0}", "$minutes")
                LinearProgressIndicator(
                    progress = { ui.leftFraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).semantics { contentDescription = left }.testTag("agent-approval-time"),
                    color = ink, trackColor = ink.copy(alpha = .12f), gapSize = 0.dp, drawStopIndicator = {},
                )
            }
            AnimatedContent(ui.phase is CardPhase.Answered, label = "agent-approval-answered",
                transitionSpec = { if (motion) fadeIn(tween(180)) togetherWith fadeOut(tween(120)) else fadeIn(tween(0)) togetherWith fadeOut(tween(0)) }) { answered ->
                if (answered) AnsweredBody(face, ui.phase as? CardPhase.Answered) else AskingBody(ui, face, ink, onAnswer, onClose)
            }
        }
    }
}

/** The answer taken: the question it answered, and under it what happens now, for a moment before the next card. */
@Composable private fun AnsweredBody(face: ApprovalFace, phase: CardPhase.Answered?) {
    val palette = rememberSettingsStatusPalette()
    val yes = phase?.approved == true
    val ink = if (yes) palette.ok else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(20.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("agent-approval-answered"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(ink.copy(alpha = SettingsStatusTint)), contentAlignment = Alignment.Center) {
            Icon(if (yes) Icons.Rounded.CheckCircle else Icons.Rounded.Block, null, Modifier.size(26.dp), tint = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(uiText(face.title).named(), style = MaterialTheme.typography.titleMedium)
            Text(uiText(phase?.line.orEmpty()).named(), style = MaterialTheme.typography.bodyMedium, color = ink)
        }
    }
}

@Composable private fun AskingBody(
    ui: ApprovalCardUi, face: ApprovalFace, ink: androidx.compose.ui.graphics.Color,
    onAnswer: (approved: Boolean, code: String?) -> Unit, onClose: () -> Unit,
) {
    val record = ui.record
    val scheme = MaterialTheme.colorScheme
    val sending = ui.phase is CardPhase.Sending
    val problem = ui.phase as? CardPhase.Problem
    val over = problem != null && !problem.retry
    // The code is typed here and sent once: it is never saved, never shown again, and gone when the card leaves the screen.
    var code by remember(record.id) { mutableStateOf("") }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(record.id, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) code = "" }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); code = "" }
    }
    val answer by rememberUpdatedState(onAnswer)
    fun send(approved: Boolean) {
        if (!ui.answerable || sending || over) return
        if (face.codeField && approved) {
            if (!codeReady(code)) return
            val typed = code
            code = ""
            answer(true, typed)
        } else answer(approved, null)
    }
    val scroll = rememberScrollState()
    // At large text the card scrolls inside; a line about why the answer didn't go through is brought into view when it appears.
    LaunchedEffect(problem) { if (problem != null) scroll.animateScrollTo(scroll.maxValue) }
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The question, and under it whose phone it is about: the agent's own, never this one (Beacon's hero: tile, title, line).
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(ink.copy(alpha = SettingsStatusTint)), contentAlignment = Alignment.Center) {
                    Icon(glyphIcon(face.glyph), null, Modifier.size(24.dp), tint = ink)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiText(face.title).named(), Modifier.semantics { heading() }.testTag("agent-approval-title"), style = MaterialTheme.typography.titleLarge)
                    // One line that wraps as words do ("Maya's phone · 1 of 3"), after the agent's own small mark of dust.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BeaconDustCore(14.dp, color = scheme.primary)
                        val whose = uiText("{name}'s phone").named()
                        val count = if (ui.count > 1) uiText("{0} of {1}").replace("{0}", "${ui.position}").replace("{1}", "${ui.count}") else ""
                        Text(if (count.isBlank()) whose else "$whose · $count", Modifier.testTag(if (count.isBlank()) "agent-approval-whose" else "agent-approval-count"),
                            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = scheme.onSurfaceVariant)
                    }
                }
            }
            // The amount as the screen showed it, whole and large: it is what the owner checks first.
            if (face.amount.isNotBlank()) Text(isolateBidi(face.amount), Modifier.testTag("agent-approval-amount"),
                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
            // The record's own names ("Kanto Foods: Place order") read left to right in every language, never turned round (P3-14).
            val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
            face.subject?.let { Text(shownLine(it), Modifier.fillMaxWidth().testTag("agent-approval-subject"),
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr, textAlign = if (rtl) TextAlign.Right else TextAlign.Left)) }
            face.question?.let { Text(shownLine(it), Modifier.testTag("agent-approval-question"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }
            if (face.detail.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.AccountCircle, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
                Text(isolateBidi(face.detail), Modifier.testTag("agent-approval-detail"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
            if (face.purpose.isNotBlank()) Text(shownLine(PhoneLine("For: {0}", listOf(face.purpose))), Modifier.testTag("agent-approval-purpose"),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            face.note?.let { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = scheme.onSurfaceVariant)
                    Text(shownLine(note), Modifier.weight(1f).testTag("agent-approval-note"), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (face.codeField && !over) OutlinedTextField(
                value = code, onValueChange = { code = codeInput(it) }, singleLine = true, enabled = !sending,
                label = { Text(uiText("Code")) },
                textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace, letterSpacing = 4.sp, textAlign = TextAlign.Center),
                // An incognito keyboard (no suggestions, nothing learned) that still shows what is typed; the phone's own SMS code
                // suggestion can fill it, since the agent phone's text codes come to the owner's number.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { send(true) }),
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.SmsOtpCode }.testTag("agent-approval-code"),
            )
            problem?.let { shown ->
                val color = if (shown.retry) rememberSettingsStatusPalette().error else scheme.onSurfaceVariant
                // One stop for TalkBack (icon and words), announced when it appears; the tag sits on that one node.
                Row(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("agent-approval-line"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.ErrorOutline, null, Modifier.padding(top = 2.dp).size(18.dp), tint = color)
                    Text(uiText(shown.line).named(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = color)
                }
            }
        }
        Buttons(face, ui, sending, over, codeOk = !face.codeField || codeReady(code), onYes = { send(true) }, onNo = { send(false) }, onClose = onClose)
    }
}

/**
 * The two answers, the yes at the end where the thumb rests. Side by side while both labels fit on one line; stacked (yes on top) at
 * large text, so no label is ever cut. A question that is over offers only Close.
 */
@Composable private fun Buttons(face: ApprovalFace, ui: ApprovalCardUi, sending: Boolean, over: Boolean, codeOk: Boolean, onYes: () -> Unit, onNo: () -> Unit, onClose: () -> Unit) {
    val stacked = LocalDensity.current.fontScale >= 1.3f
    // "Pay £12.40": the amount the screen showed, kept whole inside any language's sentence.
    val yesLabel = uiText(face.buttons.yes).replace("{0}", isolateBidi(face.amount))
    val noLabel = uiText(face.buttons.no)
    val minHeight = Modifier.heightIn(min = 52.dp)
    val enabled = ui.answerable && !sending
    if (over) {
        Button(onClose, minHeight.fillMaxWidth().testTag("agent-approval-close")) { Text(uiText("Close")) }
        return
    }
    val yes: @Composable (Modifier) -> Unit = { m ->
        Button(onYes, m.then(minHeight).testTag("agent-approval-yes"), enabled = enabled && codeOk) {
            if (sending && (ui.phase as? CardPhase.Sending)?.approved == true) { BeaconDustCore(18.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(yesLabel, textAlign = TextAlign.Center)
        }
    }
    val no: @Composable (Modifier) -> Unit = { m ->
        // A no is as plainly a button as the yes: its outline reads on glass in both themes.
        OutlinedButton(onNo, m.then(minHeight).testTag("agent-approval-no"), enabled = enabled,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) .55f else .2f))) { Text(noLabel, textAlign = TextAlign.Center) }
    }
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { yes(Modifier.fillMaxWidth()); no(Modifier.fillMaxWidth()) }
    else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { no(Modifier.weight(1f)); yes(Modifier.weight(1f)) }
}

/** The card's own fixed words (beside [AgentApprovalWords]); the words test checks each one. */
internal val AgentApprovalCardWords = listOf("Time left: {0} min", "{name}'s phone", "{0} of {1}", "For: {0}", "Code", "Close")
