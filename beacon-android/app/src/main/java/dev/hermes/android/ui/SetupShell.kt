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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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

/** The wizard's branded dark surface, used in both system themes. */
internal val SetupSea = Color(0xff07151e)
private val SetupSurface = Color(0xff10232d)
private val SetupSurfaceHighest = Color(0xff1b303b)
private val SetupInk = Color(0xfff4f0e3)
private val SetupMuted = Color(0xffb3c3ca)

/** Brightens [color] just enough to read at 4.5:1 on [background]; a light-mode brown or red would not. */
internal fun readableOnSea(color: Color, background: Color): Color =
    (0..20).firstNotNullOfOrNull { step -> lerp(color, Color.White, step / 20f).takeIf { nativeContrast(it, background) >= 4.5f } } ?: Color.White

internal fun setupColorScheme(base: ColorScheme): ColorScheme = base.copy(
    background = SetupSea, onBackground = SetupInk,
    surface = SetupSurface, onSurface = SetupInk, surfaceVariant = Color(0xff172b35), onSurfaceVariant = SetupMuted,
    surfaceContainerLowest = Color(0xff0b1d27), surfaceContainerLow = SetupSurface,
    surfaceContainer = SetupSurface, surfaceContainerHigh = Color(0xff142934), surfaceContainerHighest = SetupSurfaceHighest,
    outline = SetupMuted.copy(alpha = .62f), outlineVariant = SetupMuted.copy(alpha = .3f),
    primary = readableOnSea(base.primary, SetupSurfaceHighest), onPrimary = SetupSea,
    error = readableOnSea(base.error, SetupSurfaceHighest),
    primaryContainer = Color(0xff35412c), onPrimaryContainer = SetupInk,
)

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
                        SetupHeader(step)
                        SetupTitle(title, body)
                    }
                    Surface(Modifier.weight(1.2f), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(24.dp)) {
                            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
                            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
                        }
                    }
                } else Column(Modifier.widthIn(max = 480.dp).fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 24.dp)) {
                    SetupHeader(step)
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
private fun SetupHeader(step: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                BeaconSignal(BeaconMotionMode.Pulse, Modifier.testTag("setup-beacon-glow"), 48.dp)
                BeaconMark(size = 28.dp, contentDescription = assistantText("Beacon"))
            }
            Text(uiText("Beacon"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(uiText("Step {0} of {1}").replace("{0}", "$step").replace("{1}", "$SETUP_STEPS"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Decorative: the step text carries the meaning.
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(SETUP_STEPS) { index ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (index < step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
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
        Icon(if (error) Icons.Rounded.ErrorOutline else Icons.Rounded.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = tint)
        Text(uiText(message), Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall, color = tint)
    }
}

@Composable
internal fun SetupPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = enabled) {
        // The label stays (hidden) while loading, so TalkBack still names the button and it keeps its size.
        Box(contentAlignment = Alignment.Center) {
            Text(text, Modifier.alpha(if (loading) 0f else 1f))
            if (loading) BeaconSignal(BeaconMotionMode.Sweep, size = 20.dp)
        }
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
