package dev.hermes.android.uiux.gaps

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Gap tests that need the app's real ViewModel run it with a blank address: nothing signs in, no request leaves the phone, and a read the page
 * starts ends at once with "Reconnect to refresh this page." (BeaconViewModel.load without a client). The state a page shows is then set
 * directly: the chat the page reads, the records under `vm.data`, and which reads count as running.
 */
internal fun withClientlessVm(compose: ComposeContentTestRule, block: (BeaconViewModel) -> Unit) {
    val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
    val prior = runBlocking { app.preferences.data.first() }
    val store = ViewModelStore()
    try {
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        lateinit var vm: BeaconViewModel
        compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
        compose.waitUntil(5_000) { vm.ui.value.origin.isBlank() && !vm.ui.value.restoringSession }
        block(vm)
    } finally {
        compose.runOnUiThread { store.clear() }
        runBlocking { app.preferences.updateData { prior } }
    }
}

/** A page on the app's background, in the dark theme the visual baselines use. */
internal fun ComposeContentTestRule.showPage(content: @Composable () -> Unit) = setContent {
    BeaconTheme(dark = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { content() } } }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> BeaconViewModel.stateFlowNamed(name: String): MutableStateFlow<T> =
    BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(this) as MutableStateFlow<T>

internal val BeaconViewModel.chatFlow get() = stateFlowNamed<ChatState>("_chat")
internal val BeaconViewModel.appFlow get() = stateFlowNamed<AppState>("_ui")
internal val BeaconViewModel.dataFlow get() = stateFlowNamed<Map<String, JSONObject>>("_data")
internal val BeaconViewModel.refreshingFlow get() = stateFlowNamed<Set<String>>("_refreshing")
internal val BeaconViewModel.catalogFlow get() = stateFlowNamed<String>("_catalog")

/** Opens a chat for the pages that read the open chat (usage, model sheet). */
internal fun BeaconViewModel.openSyntheticChat(sessionId: String, title: String = "Synthetic chat", raw: JSONObject = JSONObject()) {
    chatFlow.value = ChatState(sessionId = sessionId, title = title, raw = raw)
}

/** Gives the ViewModel a client the way the device tests do: the field is written directly, nothing connects. */
internal fun BeaconViewModel.useClient(client: dev.hermes.android.data.BeaconClient?) {
    BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(this, client)
}

/** Drops a saved record, so the page sees a first load. */
internal fun BeaconViewModel.forget(key: String) = dataFlow.update { it - key }
