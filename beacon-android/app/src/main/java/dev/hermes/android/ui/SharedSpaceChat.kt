package dev.hermes.android.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.BotModeAction
import dev.hermes.android.data.BotModeEvent
import dev.hermes.android.data.BotModeMember
import dev.hermes.android.data.NativeBotModeState
import org.json.JSONObject

// The shared space as a group chat: others on the left with an avatar under each run of messages, the owner on the right,
// "is typing" for the bots Hermes reports as answering (driver_status.member_activity), and @mentions as Hermes routes them
// (gateway/hosted_room_discussion.py). Hermes' desktop group room (apps/desktop/.../hermes-bots/canonical-group-activity.tsx and
// group-mention-text.tsx) is the reference for the working-members idea and for styling only mentions routing would honour.

/** The four header lines, already translated. */
internal class TypingTexts(val bots: String, val one: String, val two: String, val many: String)

@Composable internal fun typingTexts() = TypingTexts(uiText("{0} bots"), uiText("{0} is typing…"), uiText("{0} and {1} are typing…"), uiText("{0} bots are typing…"))

/** The header's second line: who is typing, else how many bots are in the space (blank until the room is read). */
internal fun typingSubtitle(names: List<String>, bots: Int, texts: TypingTexts): String = when (names.size) {
    0 -> if (bots > 0) texts.bots.replace("{0}", "$bots") else ""
    1 -> texts.one.replace("{0}", names[0])
    2 -> texts.two.replace("{0}", names[0]).replace("{1}", names[1])
    else -> texts.many.replace("{0}", "${names.size}")
}

internal sealed interface SpaceRow { val key: String }
internal data class SpaceDayRow(val label: String, override val key: String) : SpaceRow
internal data class SpaceNoticeRow(val event: BotModeEvent) : SpaceRow { override val key get() = "event:${event.id}" }
/** [first] and [last] mark the ends of a run of messages by one sender. */
internal data class SpaceMessageRow(val event: BotModeEvent, val first: Boolean, val last: Boolean) : SpaceRow { override val key get() = "event:${event.id}" }

/** The log as rows: a day divider before the first event of each day, failures as notices. A divider or notice ends a run. */
internal fun spaceRows(events: List<BotModeEvent>, dayStarts: Map<String, String>): List<SpaceRow> {
    val flat = events.flatMap { event ->
        listOfNotNull<SpaceRow>(dayStarts[event.id]?.let { SpaceDayRow(it, "day:${event.id}") }, if (event.failed) SpaceNoticeRow(event) else SpaceMessageRow(event, first = true, last = true))
    }
    return flat.mapIndexed { index, row ->
        if (row !is SpaceMessageRow) row else SpaceMessageRow(row.event,
            first = (flat.getOrNull(index - 1) as? SpaceMessageRow)?.event?.speaker != row.event.speaker,
            last = (flat.getOrNull(index + 1) as? SpaceMessageRow)?.event?.speaker != row.event.speaker)
    }
}

internal data class MentionSpan(val start: Int, val end: Int, val handle: String)

// Hermes' own pattern (hosted_room_discussion._MENTION_RE), but never inside a word: "a@b.com" is an address, not a mention.
private val mentionPattern = Regex("(?<![A-Za-z0-9_@])@([A-Za-z0-9][A-Za-z0-9._:-]*)")
private val typingMention = Regex("(?<![A-Za-z0-9_@])@([A-Za-z0-9._:-]*)\\z")

internal fun mentionSpans(text: String): List<MentionSpan> =
    mentionPattern.findAll(text).map { MentionSpan(it.range.first, it.range.last + 1, it.groupValues[1]) }.toList()

/** What Hermes routes: the bots' handles, `@all` and `@everyone` to every bot, and `@user` to the owner. Lower case. */
internal fun mentionHandles(roster: List<BotModeMember>): Set<String> = roster.map { it.handle.lowercase() }.toSet() + setOf("all", "everyone", "user")

/** The half-typed name after an `@` that ends [before] (the text up to the cursor), or null when no mention is being typed. */
internal fun mentionQuery(before: String): String? = typingMention.find(before)?.groupValues?.get(1)

/** The chips offered above the composer: each bot's handle, then `all`, narrowed by what follows a typed `@`. */
internal fun mentionChips(roster: List<BotModeMember>, query: String?): List<String> =
    (roster.map { it.handle } + "all").distinct().filter { query == null || it.startsWith(query, ignoreCase = true) }

/** [text] with `@handle ` in place of the selection (or of a half-typed `@ha`), and where the cursor goes. */
internal fun insertMention(text: String, selectionStart: Int, selectionEnd: Int, handle: String): Pair<String, Int> {
    val before = text.substring(0, selectionStart)
    val start = mentionQuery(before)?.let { before.length - it.length - 1 } ?: before.length
    val after = text.substring(selectionEnd)
    val inserted = (if (start > 0 && !before[start - 1].isWhitespace()) " " else "") + "@$handle" + (if (after.firstOrNull()?.isWhitespace() == true) "" else " ")
    return before.take(start) + inserted + after to start + inserted.length
}

/** A bot's name in the space: the one Hermes set for the member, else its profile's display name. */
internal fun spaceMemberTitle(id: String, roster: List<BotModeMember>, profiles: List<JSONObject>): String {
    val member = roster.firstOrNull { it.id == id } ?: roster.firstOrNull { it.profile == id }
    return member?.name?.takeIf(String::isNotBlank) ?: profileDisplayName(member?.profile ?: id, profiles)
}

private fun plainParagraph(source: String): org.commonmark.node.Node? {
    val only = parseNativeMarkdown(source).children().singleOrNull() as? org.commonmark.node.Paragraph ?: return null
    return only.takeIf { paragraph ->
        paragraph.descendants().all { it is org.commonmark.node.Text || it is org.commonmark.node.Code || it is org.commonmark.node.Emphasis || it is org.commonmark.node.StrongEmphasis || it is org.commonmark.node.SoftLineBreak || it is org.commonmark.node.HardLineBreak }
    }
}

private fun mentionText(paragraph: org.commonmark.node.Node, handles: Set<String>, ink: Color, onMention: (String) -> Unit): AnnotatedString {
    val base = inlineAnnotations(paragraph, ink)
    val code = base.spanStyles.filter { it.item.fontFamily == FontFamily.Monospace }
    val style = TextLinkStyles(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold))
    return buildAnnotatedString {
        append(base)
        mentionSpans(base.text).filter { span -> span.handle.lowercase() in handles && code.none { span.start < it.end && it.start < span.end } }.forEach { span ->
            addLink(LinkAnnotation.Clickable(span.handle, style) { onMention(span.handle) }, span.start, span.end)
        }
    }
}

/** Messages that were not there when the space was first read; each is told once, so scrolling back never replays it. */
private class Arrival {
    var base: Set<String>? = null
    val shown = HashSet<String>()
    fun take(id: String) = base?.let { id !in it && shown.add(id) } == true
}

@Composable internal fun SharedSpaceHeader(spaceName: String, state: NativeBotModeState, profiles: List<JSONObject>, menu: (() -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    val names = state.typing.map { spaceMemberTitle(it, state.roster, profiles) }
    Header(spaceName, typingSubtitle(names, state.roster.size.takeIf { it > 0 } ?: state.members.size, typingTexts()), menu = menu, liveSubtitle = state.typing.isNotEmpty(), actions = {
        SpaceAvatarStack(state.roster, profiles)
        actions()
    })
}

@Composable private fun SpaceAvatarStack(roster: List<BotModeMember>, profiles: List<JSONObject>) {
    if (roster.isEmpty()) return
    val ring = MaterialTheme.colorScheme.background
    val label = uiText("Members") + ": " + roster.joinToString(", ") { spaceMemberTitle(it.id, roster, profiles) }
    Row(Modifier.padding(end = 4.dp).clearAndSetSemantics { contentDescription = label }.testTag("space-avatars"), horizontalArrangement = Arrangement.spacedBy((-8).dp), verticalAlignment = Alignment.CenterVertically) {
        roster.take(4).forEach { ChatAvatar(spaceMemberTitle(it.id, roster, profiles), it.profile, Modifier.border(2.dp, ring, CircleShape), size = 28.dp) }
        if (roster.size > 4) Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Text("+${roster.size - 4}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The shared space's conversation and composer. [richText] draws a message that needs full Markdown (code, lists, links, tables). */
@Composable internal fun ColumnScope.SharedSpaceChat(
    state: NativeBotModeState, profiles: List<JSONObject>, onDraft: (String) -> Unit, onSend: () -> Unit,
    onAction: (BotModeAction, String) -> Unit, onReviewProfiles: () -> Unit, richText: @Composable (String, String) -> Unit,
) {
    val motion = rememberNativeMotionEnabled()
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val texts = typingTexts()
    val handles = remember(state.roster) { mentionHandles(state.roster) }
    val rows = remember(state.events) { spaceRows(state.events, messageDayStarts(state.events.map { it.id to it.at })) }
    val arrival = remember(state.selectedSpace) { Arrival() }
    if (!state.loading && arrival.base == null) arrival.base = state.events.mapTo(HashSet()) { it.id }

    var field by remember { mutableStateOf(TextFieldValue(state.draft, TextRange(state.draft.length))) }
    // The draft changes under the field when a send clears it or another space is opened.
    LaunchedEffect(state.draft) { if (state.draft != field.text) field = TextFieldValue(state.draft, TextRange(state.draft.length)) }
    val focus = remember { FocusRequester() }
    val mention: (String) -> Unit = { handle ->
        val (text, cursor) = insertMention(field.text, field.selection.min, field.selection.max, handle)
        field = TextFieldValue(text, TextRange(cursor))
        onDraft(text)
        focus.requestFocus()
    }

    val typing = state.typing.take(3)
    val more = state.typing.size - typing.size
    val lead = if (state.earlierEventsOmitted) 1 else 0
    val total = lead + (if (rows.isEmpty() && !state.loading) 1 else 0) + rows.size + state.actions.size + typing.size + (if (more > 0) 1 else 0)
    val list = rememberLazyListState()
    var opened by remember(state.selectedSpace) { mutableStateOf(false) }
    var follow by remember(state.selectedSpace) { mutableStateOf(true) }
    // Whether to follow is the owner's choice, made while they scroll; a row that just arrived never decides it.
    LaunchedEffect(list) { snapshotFlow { list.canScrollForward }.collect { more -> if (list.isScrollInProgress) follow = !more } }
    // A reply replaces the typing row without changing the count, so the last row is a key too.
    LaunchedEffect(total, rows.lastOrNull()?.key) {
        // Open at the latest message; after that follow new ones only while the owner is at the bottom.
        if (total > 0 && (!opened || follow)) list.scrollToItem(total - 1)
        if (total > 0) opened = true
    }

    val routine = state.status in setOf("Shared space is ready", "Bots are discussing…")
    val failed = remember(state.events) { state.events.groupBy { it.speaker }.values.any { it.last().failed } }
    if (!routine && state.status.isNotBlank() || failed) SpaceBanner(if (routine) "" else uiText(state.status), failed, onReviewProfiles)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("space-list"), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.earlierEventsOmitted) item("earlier") { Text(uiText("Showing the latest messages."), Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        if (rows.isEmpty() && !state.loading) item("empty") { Text(uiText("Send the bots a task to start the discussion."), Modifier.fillMaxWidth().padding(32.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        items(rows, key = { it.key }) { row ->
            when (row) {
                is SpaceDayRow -> DateChip(uiText(row.label))
                is SpaceNoticeRow -> {
                    val name = spaceMemberTitle(row.event.speaker, state.roster, profiles)
                    SpacePill(Icons.Rounded.ErrorOutline, error = true) {
                        Text("$name · ${uiText("failed")}", style = MaterialTheme.typography.labelLarge)
                        Text(uiText(row.event.text), style = MaterialTheme.typography.bodySmall)
                    }
                }
                is SpaceMessageRow -> {
                    val mine = row.event.speaker == "You"
                    val member = state.roster.firstOrNull { it.id == row.event.speaker } ?: state.roster.firstOrNull { it.profile == row.event.speaker }
                    val fresh = remember(row.event.id) { arrival.take(row.event.id) }
                    SpaceMessage(row, spaceMemberTitle(row.event.speaker, state.roster, profiles), member?.profile ?: row.event.speaker, mine, handles, fresh, motion, is24Hour, mention) { text -> richText(text, row.event.id) }
                }
            }
        }
        items(state.actions, key = { "action:${it.task}:${it.request}" }) { action -> SpaceActionCard(action, spaceMemberTitle(action.member, state.roster, profiles), state.loading, onAction) }
        items(typing, key = { "typing:$it" }) { id ->
            val member = state.roster.firstOrNull { it.id == id }
            val title = spaceMemberTitle(id, state.roster, profiles)
            SpaceTypingRow(title, member?.profile ?: id, texts.one.replace("{0}", title), motion)
        }
        if (more > 0) item("typing-more") { Text(uiText("and {0} more").replace("{0}", "$more"), Modifier.padding(start = 44.dp, top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    // Your own message always comes into view, even after you scrolled up to read.
    SpaceComposer(state, field, { value -> if (value.text != field.text) onDraft(value.text); field = value }, focus, mention) { follow = true; onSend() }
}

@Composable private fun SpaceBanner(status: String, failed: Boolean, onReview: () -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag("space-banner"), color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.isNotBlank()) Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) else Spacer(Modifier.weight(1f))
            if (failed) TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Review bot profiles")) }
        }
    }
}

/** A centred, compact system line with an icon. */
@Composable private fun SpacePill(icon: ImageVector, error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(Modifier.widthIn(max = 420.dp), shape = RoundedCornerShape(16.dp), color = if (error) scheme.errorContainer else scheme.surfaceVariant, contentColor = if (error) scheme.onErrorContainer else scheme.onSurfaceVariant) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, Modifier.size(16.dp))
                Column(content = content)
            }
        }
    }
}

@Composable private fun SpaceMessage(
    row: SpaceMessageRow, title: String, profile: String, mine: Boolean, handles: Set<String>, fresh: Boolean, motion: Boolean, is24Hour: Boolean,
    onMention: (String) -> Unit, richText: @Composable (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val bubble = if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh
    val accent = readableInk(Color(chatAvatarSpec(title, profile).color)) { listOf(bubble) }
    val entry = remember { Animatable(if (fresh && motion) 0f else 1f) }
    LaunchedEffect(Unit) { if (entry.value < 1f) entry.animateTo(1f, tween(180, easing = FastOutSlowInEasing)) }
    val rise = with(LocalDensity.current) { 12.dp.toPx() }
    val time = remember(row.event.at, is24Hour) { messageTimeLabel(row.event.at, is24Hour = is24Hour) }
    Row(
        Modifier.fillMaxWidth().padding(top = if (row.first) 8.dp else 2.dp).graphicsLayer { alpha = entry.value; translationY = (1f - entry.value) * rise },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom,
    ) {
        if (!mine) {
            // The avatar sits under the last bubble of a run; earlier bubbles keep its width so they line up.
            Box(Modifier.size(32.dp).then(if (row.last) Modifier.testTag("space-message-avatar") else Modifier)) { if (row.last) ChatAvatar(title, profile, size = 32.dp) }
            Spacer(Modifier.width(6.dp))
        }
        Surface(Modifier.widthIn(max = 560.dp).padding(start = if (mine) 24.dp else 0.dp, end = if (mine) 0.dp else 24.dp).semantics(mergeDescendants = true) {}, shape = bubbleShape(mine, joinedTop = !row.first, tail = row.last), color = bubble) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)) {
                if (!mine && row.first) Text(title, Modifier.padding(bottom = 2.dp).testTag("space-sender"), color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                SpaceMessageText(row.event.text, handles, readableInk(scheme.primary) { listOf(bubble) }, onMention, richText)
                if (time != null) Text(time, Modifier.align(Alignment.End).padding(top = 2.dp), style = nativeMetadataStyle(), color = LocalContentColor.current.copy(alpha = .68f))
            }
        }
    }
}

// shortcut: only a message that is one paragraph of plain text gets tappable @mentions; code, lists, links and tables use the
// shared Markdown renderer, which has no mention hook. Upgrade by giving Markdown an optional text decorator.
@Composable private fun SpaceMessageText(text: String, handles: Set<String>, ink: Color, onMention: (String) -> Unit, richText: @Composable (String) -> Unit) {
    val paragraph = remember(text) { plainParagraph(text) }
    if (paragraph == null) { richText(text); return }
    val latest by rememberUpdatedState(onMention)
    val annotated = remember(paragraph, handles, ink) { mentionText(paragraph, handles, ink) { latest(it) } }
    SelectionContainer { Text(annotated, style = MaterialTheme.typography.bodyLarge) }
}

/** An incoming message that is still being written: the bot's avatar, its name, and three pulsing dots. */
@Composable private fun SpaceTypingRow(title: String, profile: String, description: String, motion: Boolean) {
    val bubble = MaterialTheme.colorScheme.surfaceContainerHigh
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = description }.testTag("space-typing:$profile"),
        verticalAlignment = Alignment.Bottom,
    ) {
        ChatAvatar(title, profile, size = 32.dp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(title, Modifier.padding(start = 12.dp, bottom = 2.dp), color = MaterialTheme.colorScheme.background.let { page -> readableInk(Color(chatAvatarSpec(title, profile).color)) { listOf(page) } }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Surface(shape = bubbleShape(false, joinedTop = false, tail = true), color = bubble) { TypingDots(motion) }
        }
    }
}

/** Three dots that pulse one after another; with motion off they hold still. */
@Composable private fun TypingDots(motion: Boolean) {
    val transition = if (motion) rememberInfiniteTransition(label = "typing") else null
    Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val pulse = transition?.animateFloat(.3f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse, StartOffset(index * 140)), label = "dot$index")
            Box(Modifier.size(7.dp).graphicsLayer { alpha = pulse?.value ?: .6f }.background(LocalContentColor.current, CircleShape))
        }
    }
}

@Composable private fun SpaceActionCard(action: BotModeAction, member: String, loading: Boolean, resolve: (BotModeAction, String) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action.kind == "approval") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("$member ${uiText("needs permission")}", style = MaterialTheme.typography.titleMedium)
                }
                SelectionContainer { Text(action.description.ifBlank { uiText("Tool approval requested") }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if ("once" in action.choices) Button(onClick = { resolve(action, "once") }, enabled = !loading && action.description.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Allow once")) }
                    if ("deny" in action.choices) OutlinedButton(onClick = { resolve(action, "deny") }, enabled = !loading, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Deny")) }
                }
            } else {
                Text(action.description)
            }
        }
    }
}

@Composable private fun SpaceComposer(state: NativeBotModeState, field: TextFieldValue, onField: (TextFieldValue) -> Unit, focus: FocusRequester, onMention: (String) -> Unit, onSend: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val chips = mentionChips(state.roster, mentionQuery(field.text.substring(0, field.selection.min)))
    Surface(tonalElevation = 2.dp) {
        Column {
            if ((focused || field.text.isBlank()) && chips.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = SettingsPagePadding).padding(top = 8.dp).testTag("space-chips"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chips.forEach { handle -> AssistChip(onClick = { onMention(handle) }, label = { Text("@$handle") }, enabled = !state.sending, modifier = Modifier.testTag("space-chip:$handle")) }
                }
            }
            OutlinedTextField(field, onField, placeholder = { Text(uiText("Message the shared space")) }, shape = RoundedCornerShape(24.dp), maxLines = 6, enabled = !state.sending,
                trailingIcon = { FilledIconButton(onClick = onSend, enabled = !state.loading && !state.sending && state.draft.isNotBlank(), shape = CircleShape, modifier = Modifier.testTag("bot-shared-send")) { Icon(Icons.Rounded.ArrowUpward, uiText(if (state.sending) "Sending…" else "Send")) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsPagePadding, vertical = 12.dp).focusRequester(focus).onFocusChanged { focused = it.hasFocus }.testTag("bot-shared-composer"))
        }
    }
}
