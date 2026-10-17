package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.map

/** Posture only changes placement. Drafts, streams, calls and editor state remain in the ViewModel. */
@Composable fun NativeWindowLayout(activity: ComponentActivity, content: @Composable () -> Unit) {
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity).map<WindowLayoutInfo, WindowLayoutInfo?> { it } }
    val layout by flow.collectAsStateWithLifecycle(initialValue = null)
    val density = LocalDensity.current
    val feature = layout?.displayFeatures.orEmpty().filterIsInstance<FoldingFeature>().firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        var leftInset = 0.dp; var rightInset = 0.dp; var topInset = 0.dp; var bottomInset = 0.dp
        feature?.let { fold ->
            val bounds = fold.bounds
            if (fold.orientation == FoldingFeature.Orientation.VERTICAL) {
                val left = with(density) { bounds.left.toDp() }.coerceIn(0.dp, maxWidth)
                val right = with(density) { bounds.right.toDp() }.coerceIn(left, maxWidth)
                val limit = (maxWidth - 1.dp).coerceAtLeast(0.dp)
                if (maxWidth - right > left) leftInset = (right + 6.dp).coerceAtMost(limit)
                else rightInset = (maxWidth - left + 6.dp).coerceAtMost(limit)
            } else {
                val top = with(density) { bounds.top.toDp() }.coerceIn(0.dp, maxHeight)
                val bottom = with(density) { bounds.bottom.toDp() }.coerceIn(top, maxHeight)
                val limit = (maxHeight - 1.dp).coerceAtLeast(0.dp)
                if (maxHeight - bottom > top) topInset = (bottom + 6.dp).coerceAtMost(limit)
                else bottomInset = (maxHeight - top + 6.dp).coerceAtMost(limit)
            }
        }
        // One content position preserves saveable state across both posture and Activity restoration.
        Box(Modifier.fillMaxSize().absolutePadding(leftInset, topInset, rightInset, bottomInset)) { content() }
    }
}
