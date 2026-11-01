package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.Scope
import dev.hermes.android.ui.MoreScreen
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Chat settings: Follow new replies and Background animation write their choice for the signed-in account and the page shows what was written. */
class ChatPreferencesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val scope = Scope("https://gaps.example.com", "synthetic-owner", "default")

    private fun isOn(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On

    @Test fun followNewRepliesAndBackgroundAnimationWriteTheirChoice() = withClientlessVm(compose) { vm ->
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val prior = runBlocking { app.cache.read(scope, "chat-preferences") }
        try {
            // The account the choice belongs to: the ViewModel's saved owner, written directly as the device tests do.
            BeaconViewModel::class.java.getDeclaredField("cachedOwnerScope").apply { isAccessible = true }.set(vm, scope)
            compose.runOnIdle { vm.tab("More"); vm.more("Settings") }
            compose.waitUntil(5_000) { vm.ui.value.morePage == "Settings" }
            compose.showPage { MoreScreen(vm) }
            for ((tag, key) in listOf("chat-pref-follow" to "auto_scroll_follow", "chat-pref-animation" to "animate_background")) {
                repeat(2) {
                    compose.onNodeWithTag("chat-preferences").performScrollToNode(hasTestTag(tag))
                    val before = isOn(tag)
                    compose.onNodeWithTag(tag).performClick()
                    compose.waitUntil(5_000) { vm.data.value["profile-settings"]?.let { record -> record.has(key) && record.getBoolean(key) == !before } == true }
                    compose.onNodeWithTag("chat-preferences").performScrollToNode(hasTestTag(tag))
                    assertEquals("$tag flips on tap", !before, isOn(tag))
                    val saved = JSONObject(requireNotNull(runBlocking { app.cache.read(scope, "chat-preferences") }) { "The choice was not saved for the account" })
                    assertEquals("$key is saved as $before's opposite", !before, saved.getBoolean(key))
                }
            }
        } finally {
            runBlocking { if (prior == null) app.cache.delete(scope, "chat-preferences") else app.cache.write(scope, "chat-preferences", prior, true) }
        }
    }
}
