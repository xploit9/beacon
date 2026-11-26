package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.*
import dev.hermes.android.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * D21 device proof: the owner's 25 Nov reply (space-aligned, no pipes) arrives in a real chat on emulator-5578 and must show as a table of
 * seven columns and three rows, with the words and numbers of one row never mixed into another. Synthetic text and a synthetic Hermes only.
 */
class TableRenderChatDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(vm, value)

    /** The owner's message as Hermes stored it at 25 Nov 2026: a heading, the columns kept apart by two spaces, and a closing paragraph. */
    private val reply = """
        All verified.

        Final state in Overleaf

          Document           Pages  Errors  Overfull  Refs  Captions  Figure
          elsevier_paper      17      0        0       33     14        1
          ieee_paper          13      0        0       33     14        1
          tf_paper            25      0        1 (4pt) 33     13        1

        The single remaining 4 pt overhang in tf_paper is one prose line 1.4 mm past the margin.
    """.trimIndent()

    @Test fun theOwnersTableShowsAsAChatTable() {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val priorOwner = app.calls.ownerScope
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val scope = fixture.scope.session("chat-a")
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        val rows = JSONArray()
            .put(json("row_id" to 41, "role" to "user", "text" to "Compare the three papers.", "timestamp" to 1_800_000_001.0))
            .put(json("row_id" to 42, "role" to "assistant", "text" to reply, "timestamp" to 1_800_000_002.0))
        fixture.sessions.getValue(scope.session).put("messages", rows)
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = "" }
                app.cache.delete(scope, "session")
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", scope)
                app.calls.ownerScope = scope.copy(session = "")
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, authenticated = true, owner = scope.owner, profile = scope.profile,
                    tab = "Chat", connected = true, restoringSession = false)
                val snapshot = fixture.snapshot("chat-a")
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Final state", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { i, row -> Message.from(row, i, "chat-a") }, projectionReady = true)
            }
            compose.setContent {
                NativeLocalization("en") {
                    BeaconTheme {
                        CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) { ChatScreen(vm) }
                    }
                }
            }
            compose.waitUntil(20_000) {
                compose.onAllNodes(hasTestTag("table-cell:0:0"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val density = compose.density.density
            for (row in 0..3) {
                var previous: androidx.compose.ui.geometry.Rect? = null
                for (column in 0..6) {
                    val node = compose.onNodeWithTag("table-cell:$row:$column", useUnmergedTree = true)
                    node.assertExists("row $row column $column is missing from the chat")
                    val measured = node.fetchSemanticsNode()
                    assertTrue("row $row column $column is ${measured.size.width / density} dp wide", measured.size.width >= 24 * density)
                    if (previous != null && measured.boundsInRoot.width > 0f) assertTrue("row $row column $column runs into its neighbour", measured.boundsInRoot.left >= previous.right - 1f)
                    previous = measured.boundsInRoot
                }
            }
            // Each row's own words are in it, and one row never holds the next row's first cell.
            val labels = listOf("Document", "elsevier_paper", "ieee_paper", "tf_paper")
            for (row in labels.indices) compose.onNodeWithTag("table-cell:$row:0", useUnmergedTree = true)
                .onChildren().filterToOne(hasText(labels[row])).assertExists("row $row shows ${labels[row]} in its first column")
            // The last column holds this row's own figure: the 4 pt row keeps "1", never the row above's numbers.
            listOf("Figure", "1", "1", "1").forEachIndexed { row, cell -> compose.onNodeWithTag("table-cell:$row:6", useUnmergedTree = true)
                .onChildren().filterToOne(hasText(cell)).assertExists("row $row column 6 shows $cell") }
            assertTrue(compose.onAllNodes(hasText("Final state in Overleaf", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            val out = File(app.getExternalFilesDir(null), "table-render-chat.png")
            out.outputStream().use { stream -> compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream) }
            assertTrue("captured ${out.absolutePath}", out.length() > 0)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = priorOwner }
            runBlocking { app.cache.delete(scope, "session"); app.preferences.updateData { prior } }
        }
    }
}
