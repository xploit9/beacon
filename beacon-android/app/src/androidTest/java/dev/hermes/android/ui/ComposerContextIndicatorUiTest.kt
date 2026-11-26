package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.uiux.gaps.appFlow
import dev.hermes.android.uiux.gaps.chatFlow
import dev.hermes.android.uiux.gaps.withClientlessVm
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * CTRX-125: the composer's context ring reads Hermes' cached session usage, sits immediately left of the approval shield, opens the existing
 * Session usage page, and stays absent whenever Hermes did not send both the used and window figures.
 */
class ComposerContextIndicatorUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val session = "synthetic-chat"

    private fun key(vm: BeaconViewModel) = "native-usage:${vm.ui.value.profile}:$session"

    private fun open(vm: BeaconViewModel, replies: Boolean = true) {
        vm.chatFlow.value = ChatState(sessionId = session, title = "Travel planning", raw = json("model" to "deepseek-v4.1-flash"),
            messages = if (replies) listOf(Message.from(json("role" to "assistant", "content" to "Synthetic answer", "row_id" to 1), 0, session)) else emptyList())
        vm.appFlow.value = AppState(origin = "https://hermes.example.com", owner = "synthetic", profile = "default", authenticated = true, connected = true,
            restoringSession = false, bootstrap = json("optional_features" to json("plugin" to "available")))
    }

    private fun show(vm: BeaconViewModel) {
        compose.setContent {
            NativeLocalization("en") { BeaconTheme(dark = true) {
                CompositionLocalProvider(LocalAssistantName provides "Beacon", LocalBeaconSettings provides JSONObject()) {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    if (ui.tab == "More") MoreScreen(vm) else ChatScreen(vm)
                }
            } }
        }
        compose.waitForIdle()
    }

    private fun seed(vm: BeaconViewModel, usage: JSONObject) = compose.runOnIdle { vm.storeData(key(vm), usage) }
    private fun usage(window: Long? = 1_000_000, used: Long? = 384_000, input: Long = 18_400, output: Long = 2_300) =
        json("input_tokens" to input, "output_tokens" to output, "context_window" to window, "context_used" to used)

    @Test fun knownUsageShowsTheRingAndShareLeftOfTheShieldAndOpensSessionUsage() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { open(vm) }
        show(vm)
        seed(vm, usage())
        compose.waitForIdle()
        val badge = compose.onNodeWithTag("composer-context")
        badge.assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assertContentDescriptionEquals("Context 38% used, 384K of 1M tokens")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithTag("composer-context-ring", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("composer-context-percent", useUnmergedTree = true).assertExists()
        val ring = badge.fetchSemanticsNode().boundsInRoot
        val shield = compose.onNodeWithTag("composer-approvals").fetchSemanticsNode().boundsInRoot
        val model = compose.onNodeWithTag("composer-model-effort").fetchSemanticsNode().boundsInRoot
        assertTrue("the ring sits left of the shield", ring.right <= shield.left + 1f)
        assertEquals("both sit in the same row", ring.center.y, shield.center.y, 2f)
        assertTrue("the shield still leads the model chip", shield.right <= model.left + 1f)
        badge.performClick()
        compose.waitUntil(5_000) { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Session usage" }
        compose.onNodeWithTag("usage-page").assertIsDisplayed()
    }

    @Test fun aFullWindowReadsOneHundredPercent() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { open(vm) }
        show(vm)
        seed(vm, usage(window = 1_000_000, used = 1_000_000))
        compose.waitForIdle()
        compose.onNodeWithTag("composer-context").assertContentDescriptionContains("Context 100% used, 1M of 1M tokens", substring = true)
    }

    @Test fun unknownUsageShowsNothing() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { open(vm) }
        show(vm)
        compose.onNodeWithTag("composer-context").assertDoesNotExist()
    }

    @Test fun usedWithoutAWindowShowsNothing() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { open(vm) }
        show(vm)
        seed(vm, usage(window = null, used = 695_000))
        compose.waitForIdle()
        compose.onNodeWithTag("composer-context").assertDoesNotExist()
    }

    @Test fun anUnreportedChatShowsNothing() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { open(vm, replies = true) }
        show(vm)
        seed(vm, usage(window = 32_000, used = 4_200, input = 0, output = 0))
        compose.waitForIdle()
        compose.onNodeWithTag("composer-context").assertDoesNotExist()
    }
}
