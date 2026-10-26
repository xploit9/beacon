package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Telegram-style parts on their own: local state only, nothing here talks to a backend. */
class TelegramChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showRow(unread: Boolean, title: String = "Cyber news", preview: String = "Top story: a new advisory landed", fontScale: Float = 1f) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme { ChatListRow(id = "c1", title = title, preview = preview, time = "9:41 AM", unread = unread, pinned = true, modifier = Modifier.testTag("row")) }
            }
        }
    }

    @Test fun anUnreadRowShowsTheDotAndSaysSoWithItsLatestLineAndTime() {
        showRow(unread = true)
        compose.onNodeWithTag("unread-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("row").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Unread reply"))
        compose.onNodeWithText("9:41 AM").assertExists()
        compose.onNodeWithText("Top story: a new advisory landed").assertExists()
    }

    @Test fun aReadRowHasNoDotAndNoUnreadState() {
        showRow(unread = false)
        compose.onAllNodesWithTag("unread-dot", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("row").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test fun aRowKeepsA48dpAvatarAndOneLinePerTextAtLargeFont() {
        val title = "A rather long chat title ".repeat(6).trim()
        val preview = "A very long latest line ".repeat(12).trim()
        showRow(unread = true, title = title, preview = preview, fontScale = 1.3f)
        compose.onNodeWithTag("chat-avatar", useUnmergedTree = true).assertWidthIsEqualTo(48.dp).assertHeightIsEqualTo(48.dp)
        compose.onNodeWithTag("row").assertHeightIsAtLeast(72.dp)
        listOf(title, preview).forEach { text ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue(layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun aRowWhoseTitleStartsWithAnEmojiShowsTheNameBesideItsAvatar() {
        val lock = String(Character.toChars(0x1F512))
        showRow(unread = false, title = "$lock Cyber news")
        compose.onNodeWithText("Cyber news", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("$lock Cyber news", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aDateChipIsCentred() {
        compose.setContent { MaterialTheme { Box(Modifier.width(300.dp)) { DateChip("Today") } } }
        val chip = compose.onNode(hasText("Today")).getUnclippedBoundsInRoot()
        assertEquals(150f, ((chip.left + chip.right) / 2).value, 2f)
    }

    @Test fun eachSendStateHasItsOwnTickAndWords() {
        compose.setContent { MaterialTheme { Column { SendTickIcon(SendTick.Sending); SendTickIcon(SendTick.Sent); SendTickIcon(SendTick.Unconfirmed) } } }
        compose.onNodeWithContentDescription("Sending").assertExists()
        compose.onNodeWithContentDescription("Sent").assertExists()
        compose.onNodeWithContentDescription("Not confirmed").assertExists()
        listOf("sending", "sent", "unconfirmed").forEach { compose.onNodeWithTag("send-tick:$it", useUnmergedTree = true).assertExists() }
    }
}
