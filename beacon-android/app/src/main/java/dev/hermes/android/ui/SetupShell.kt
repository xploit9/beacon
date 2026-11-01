package dev.hermes.android.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

// The shared wizard shell. The stepper ("Step n of 4"), the grouped chooser rows and the text-button escapes are adapted
// from Hermes Relay's ConnectionWizard (ui/components/ConnectionWizard.kt, MIT) in Beacon's own look.

/** Connect, Sign in, Calls, Ready: one honest count for every route through the wizard. */
internal const val SETUP_STEPS = 4

internal fun setupStepNumber(step: String): Int = when (step) {
    "connect", "probe", "unsupported" -> 1
    "signin" -> 2
    "done" -> 4
    else -> 3
}

/** The pages of step 3 (voice, then permissions) in the order the wizard shows them; the bar moves one page at a time inside that step. */
internal val SETUP_CALL_PAGES = listOf("voice-provider", "voice-key", "voice-customize", "permission-microphone", "permission-notifications", "permission-full-screen")

/** How far through the whole wizard [step] is, counted in steps: the steps before it, plus its share of step 3's pages. */
internal fun setupProgress(step: String): Float {
    val number = setupStepNumber(step)
    val page = SETUP_CALL_PAGES.indexOf(step)
    return if (page < 0) number.toFloat() else (number - 1) + (page + 1) / SETUP_CALL_PAGES.size.toFloat()
}

/** The wizard step being shown, so the shell can place its bar without every page passing it down. */
internal val LocalSetupStep = compositionLocalOf { "" }

/** The wizard's branded dark surface, used in both system themes. */
internal val SetupSea = Color(0xff07151e)
private val SetupSurface = Color(0xff10232d)
private val SetupSurfaceHighest = Color(0xff1b303b)
private val SetupInk = Color(0xfff4f0e3)
private val SetupMuted = Color(0xffb3c3ca)
// The wizard keeps the dark theme's gold and error red in both system themes (default skin, dark: accent #E7B579, error #F2A49C), so it looks the same either way.
private val SetupGold = Color(0xffe7b579)
private val SetupError = Color(0xfff2a49c)

internal fun setupColorScheme(base: ColorScheme): ColorScheme = base.copy(
    background = SetupSea, onBackground = SetupInk,
    surface = SetupSurface, onSurface = SetupInk, surfaceVariant = Color(0xff172b35), onSurfaceVariant = SetupMuted,
    surfaceContainerLowest = Color(0xff0b1d27), surfaceContainerLow = SetupSurface,
    surfaceContainer = SetupSurface, surfaceContainerHigh = Color(0xff142934), surfaceContainerHighest = SetupSurfaceHighest,
    outline = SetupMuted.copy(alpha = .62f), outlineVariant = SetupMuted.copy(alpha = .3f),
    primary = SetupGold, onPrimary = SetupSea,
    error = SetupError, onError = SetupSea,
    primaryContainer = Color(0xff35412c), onPrimaryContainer = SetupInk,
)

/** What the header's Close setup button does and whether it can right now. Null where there is nothing to close back to (before the first sign-in). */
internal class SetupClose(val enabled: Boolean, val onClose: () -> Unit)

/** The wizard sets this once, so every setup page gets the same X in its header instead of its own text link. */
internal val LocalSetupClose = compositionLocalOf<SetupClose?> { null }

/** The wizard and the call screen are dark in both themes, so the clock, battery and gesture bar draw light on them; the app's bars come back when they close. */
@Composable
internal fun SetupSystemBars() {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window
    val bars = remember(window, view) { window?.let { WindowInsetsControllerCompat(it, view) } }
    DisposableEffect(bars) {
        val oldLightStatus = bars?.isAppearanceLightStatusBars
        val oldLightNavigation = bars?.isAppearanceLightNavigationBars
        onDispose {
            if (oldLightStatus != null) bars?.isAppearanceLightStatusBars = oldLightStatus
            if (oldLightNavigation != null) bars?.isAppearanceLightNavigationBars = oldLightNavigation
        }
    }
    // The activity applies edge-to-edge from its own LaunchedEffect, on the first frame and on every theme change. This one is launched after it, so these flags win.
    LaunchedEffect(bars, MaterialTheme.colorScheme.background) {
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
    }
}

@Composable
internal fun SetupSeaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = setupColorScheme(MaterialTheme.colorScheme), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides SetupInk, content = content)
    }
}

/** What a step shows: the scrolling [content] and the [actions] kept below it. Settings reuses the same pages without the shell. */
internal class SetupPage(val content: @Composable ColumnScope.() -> Unit, val actions: @Composable ColumnScope.() -> Unit = {})

/**
 * Header, title and a scrolling content area with its actions pinned below, on the sea background.
 * From 720 dp wide the header and title move to a left pane and the content to a card on the right.
 */
@Composable
internal fun SetupShell(
    step: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String = "",
    content: @Composable ColumnScope.() -> Unit,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    SetupSeaTheme {
        val glow = MaterialTheme.colorScheme.primary
        val key = LocalSetupStep.current
        val progress = if (key.isBlank()) step.toFloat() else setupProgress(key)
        Box(Modifier.fillMaxSize().background(SetupSea)) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Brush.radialGradient(
                    colors = listOf(glow.copy(alpha = .2f), Color.Transparent),
                    center = Offset(size.width * .5f, size.height * .18f),
                    radius = maxOf(size.width, size.height) * .72f,
                ))
            }
            BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                if (maxWidth >= 720.dp) Row(
                    Modifier.widthIn(max = 960.dp).fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                        SetupHeader(step, progress)
                        SetupTitle(title, body)
                    }
                    Surface(Modifier.weight(1.2f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(24.dp)) {
                            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                        }
                    }
                } else Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)) {
                    SetupHeader(step, progress)
                    Spacer(Modifier.height(32.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        SetupTitle(title, body)
                        content()
                    }
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                }
            }
        }
    }
}

@Composable
private fun SetupHeader(step: Int, progress: Float) {
    val motion = rememberNativeMotionEnabled()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                BeaconSignal(BeaconMotionMode.Pulse, Modifier.testTag("setup-beacon-glow"), 48.dp)
                BeaconMark(size = 28.dp, contentDescription = assistantText("Beacon"))
            }
            Text(uiText("Beacon"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(uiText("Step {0} of {1}").replace("{0}", "$step").replace("{1}", "$SETUP_STEPS"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LocalSetupClose.current?.let { close ->
                IconButton(
                    close.onClose, Modifier.size(48.dp).testTag("setup-close"), enabled = close.enabled,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f)),
                ) { Icon(Icons.Rounded.Close, uiText("Close setup")) }
            }
        }
        // The step text names the step; the bar fills a little more on every page of step 3.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {
            testTag = "setup-progress"
            progressBarRangeInfo = ProgressBarRangeInfo(progress.coerceIn(0f, SETUP_STEPS.toFloat()), 0f..SETUP_STEPS.toFloat())
        }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(SETUP_STEPS) { index ->
                val fill = (progress - index).coerceIn(0f, 1f)
                val shown = if (motion) animateFloatAsState(fill, tween(280), label = "setup-progress-$index").value else fill
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(shown).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
    }
}

@Composable
private fun SetupTitle(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (body.isNotBlank()) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The one place a step says what went wrong or what happened: icon plus text, announced politely. */
@Composable
internal fun SetupStatusLine(message: String, error: Boolean, modifier: Modifier = Modifier) {
    if (message.isBlank()) return
    val tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.padding(top = 2.dp).size(18.dp), tint = tint)
        // The one line that says what went wrong: body size, not caption size.
        Text(uiText(message), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = tint)
    }
}

@Composable
internal fun SetupPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    // While it works the button keeps its label beside a turning dust core and is not tappable, but it does not take the grey disabled look.
    Button(
        onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled && !loading,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = if (loading) scheme.primary.copy(alpha = .24f) else scheme.onSurface.copy(alpha = .12f),
            disabledContentColor = if (loading) scheme.onSurface else scheme.onSurface.copy(alpha = .38f),
        ),
    ) {
        if (loading) { BeaconDustCore(18.dp); Spacer(Modifier.width(10.dp)) }
        Text(text)
    }
}

@Composable
internal fun SetupTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled) { Text(text) }
}

/** A quiet grouped block for facts or a single option set (Keep connected, the call-sharing note). */
@Composable
internal fun SetupContainer(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
