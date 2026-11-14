package dev.hermes.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp

// The top of the drawer in Bot Mode: where the profile pill is in the normal drawer, a Bot Mode header takes its place. Profiles do not
// switch here (a bot is a profile, and the roster below is the way between them), so the pill has nothing to do on this page.

/** The least the profile pill and the Bot Mode header stand tall. */
private val DrawerHeaderMinHeight = 56.dp
/** The pill's own padding above and below its two lines of text (the outlined button's 8 dp each way). */
private val DrawerHeaderPadding = 16.dp
private val HeaderMarkSize = 32.dp

/**
 * The height of the profile pill and of the Bot Mode header. One value for both, worked out from the pill's own two lines of text, so a
 * larger font grows both alike and switching Bot Mode never moves the buttons and tiles below the header.
 */
@Composable internal fun drawerHeaderHeight(): Dp {
    val type = MaterialTheme.typography
    val density = LocalDensity.current
    return with(density) {
        val title = if (type.titleMedium.lineHeight.isSpecified) type.titleMedium.lineHeight.toDp() else type.titleMedium.fontSize.toDp() * 1.5f
        val small = if (type.labelSmall.lineHeight.isSpecified) type.labelSmall.lineHeight.toDp() else type.labelSmall.fontSize.toDp() * 1.5f
        maxOf(DrawerHeaderMinHeight, title + small + DrawerHeaderPadding)
    }
}

/**
 * Bot Mode's header: Beacon's dust mark beside the words "Bot Mode", as tall and as wide as the profile pill it replaces
 * ([drawerHeaderHeight]). It is a title, not a button: nothing here switches or opens anything. The words stay centred (a spacer the
 * size of the mark balances it), so the header reads the same in a right-to-left layout, where the row mirrors.
 */
@Composable internal fun BotModeHeader(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = CircleShape
    Row(
        // The semantics come before the padding, so the header's bounds (what TalkBack outlines) are the whole pill, not its inside.
        modifier.fillMaxWidth().height(drawerHeaderHeight()).clip(shape).background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .semantics(mergeDescendants = true) { heading() }.testTag("bot-mode-header")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        BeaconDustCore(HeaderMarkSize, Modifier.testTag("bot-mode-header-mark"))
        Text(uiText("Bot Mode"), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp).contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(HeaderMarkSize))
    }
}
