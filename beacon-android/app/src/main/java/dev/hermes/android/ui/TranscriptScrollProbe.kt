package dev.hermes.android.ui

import androidx.compose.foundation.lazy.LazyListState

/** The transcript state for frame-by-frame instrumented measurement; it has no effect on the app. */
internal object TranscriptScrollProbe {
    @Volatile var state: LazyListState? = null
}
