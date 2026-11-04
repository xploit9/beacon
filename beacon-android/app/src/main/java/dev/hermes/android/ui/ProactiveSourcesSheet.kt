package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * The sources the watch reads: "All sources" (the default) and, when it is off, one switch per connected source. Every tap is saved at once
 * by the caller; the sheet only shows [view]. Tags: `proactive-source-all`, `proactive-source-<name>`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProactiveSourcesSheet(
    view: ProactiveSourcesView,
    enabled: Boolean,
    onAll: (Boolean) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("proactive-sources-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiText("Sources"), Modifier.padding(horizontal = 24.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                SettingsSwitchRow(uiText("All sources"), view.all, onAll, enabled = enabled, testTag = "proactive-source-all")
                val watchedCount = if (view.all) view.sources.size else view.sources.count { it.watched }
                view.sources.forEach { source ->
                    SettingsDivider()
                    // With All sources on every source shows on, and switching one off keeps the rest. The last watched one stays on,
                    // because none chosen would mean all.
                    val watched = view.all || source.watched
                    val locked = watched && watchedCount == 1
                    SettingsSwitchRow(proactiveSourceName(source.name), watched, { onToggle(source.name, it) }, enabled = enabled && !locked,
                        testTag = "proactive-source-${source.name}")
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 24.dp).testTag("proactive-sources-done")) { Text(uiText("Done")) }
        }
    }
}
