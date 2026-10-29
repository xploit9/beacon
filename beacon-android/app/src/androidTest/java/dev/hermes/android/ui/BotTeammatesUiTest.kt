package dev.hermes.android.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import dev.hermes.android.data.NativeProfileAdminState
import dev.hermes.android.data.profileDeletable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Bot Mode's teammates on a fixture of profiles: a long press shows a bot's or a group's menu, New chat takes two to six bots, and
 * deleting a profile needs its name typed. Local state only; no Hermes is involved (the routes are covered by the unit tests).
 */
class BotTeammatesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val profiles = listOf("default", "coder", "writer")
    private val calls = mutableListOf<String>()

    private fun roster(active: String = "default", assistant: String = "default") = compose.setContent {
        MaterialTheme {
            Column {
                profiles.forEach { profile ->
                    val actions = botMenu(profileDeletable(profile, active, assistant), open = { calls += "open:$profile" }, group = { calls += "group:$profile" }, role = { calls += "role:$profile" },
                        settings = { calls += "settings:$profile" }, delete = { calls += "delete:$profile" })
                    TeammateRow(profile.replaceFirstChar { it.uppercase() }, profile, if (profile == "coder") "Reviews code" else "", isOpen = profile == active, actions = actions, tag = "teammate-row:$profile") { calls += "tap:$profile" }
                }
            }
        }
    }

    @Test fun aRowShowsTheNameAndRoleAndATapOpensTheChat() {
        roster()
        compose.onNodeWithTag("teammate-row:coder").assertTextContains("Coder").assertTextContains("Reviews code")
        compose.onNodeWithTag("teammate-row:coder").performClick()
        compose.runOnIdle { assertEquals(listOf("tap:coder"), calls) }
    }

    @Test fun aLongPressShowsABotsMenuAndAnActionRunsOnce() {
        roster()
        compose.onNodeWithTag("teammate-row:coder").performTouchInput { longClick() }
        for (tag in listOf("bot-action-open", "bot-action-group", "bot-action-role", "bot-action-settings", "bot-action-delete")) compose.onNodeWithTag(tag).assertIsDisplayed()
        compose.onNodeWithText("Start a group with…").assertIsDisplayed()
        compose.onNodeWithTag("bot-action-role").performClick()
        compose.runOnIdle { assertEquals(listOf("role:coder"), calls) }
        compose.onAllNodesWithTag("bot-action-role").assertCountEquals(0)
    }

    @Test fun deleteIsAbsentForTheBotInUseTheDefaultAndTheAssistantsBot() {
        roster(active = "coder", assistant = "writer")
        for (profile in listOf("default", "coder", "writer")) {
            compose.onNodeWithTag("teammate-row:$profile").performTouchInput { longClick() }
            compose.onNodeWithTag("bot-action-open").assertIsDisplayed()
            compose.onAllNodesWithTag("bot-action-delete").assertCountEquals(0)
            compose.onNodeWithTag("bot-action-open").performClick()
        }
        compose.runOnIdle { assertEquals(listOf("open:default", "open:coder", "open:writer"), calls) }
    }

    @Test fun anotherBotCanBeDeletedFromItsMenu() {
        roster(active = "default", assistant = "default")
        compose.onNodeWithTag("teammate-row:writer").performTouchInput { longClick() }
        compose.onNodeWithTag("bot-action-delete").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("delete:writer"), calls) }
    }

    @Test fun aGroupRowShowsItsBotsNewestLineAndAMenuWithTheSameActions() {
        compose.setContent {
            MaterialTheme {
                val actions = spaceMenu(canRename = true, canDelete = false, open = { calls += "open" }, rename = { calls += "rename" }, members = { calls += "members" }, delete = { calls += "delete" })
                SpaceListRow("Review team", listOf("Default" to "default", "Coder" to "coder", "Writer" to "writer", "Nova" to "nova"), "Coder: Plan the review", "10:42", isOpen = false, actions = actions, tag = "space-row:r1") { calls += "tap" }
            }
        }
        compose.onNodeWithTag("space-row:r1").assertTextContains("Review team").assertTextContains("Coder: Plan the review").assertTextContains("10:42")
        // Three bots show; more become a "+N" chip.
        compose.onNodeWithText("+2").assertIsDisplayed()
        compose.onNodeWithTag("space-row:r1").performTouchInput { longClick() }
        compose.onNodeWithTag("space-action-open").assertIsDisplayed()
        compose.onNodeWithTag("space-action-rename").assertIsEnabled()
        compose.onNodeWithTag("space-action-members").assertIsDisplayed()
        compose.onNodeWithTag("space-action-delete").assertIsNotEnabled()
        compose.onNodeWithText("Deletion unavailable").assertIsDisplayed()
        compose.onNodeWithTag("space-action-members").performClick()
        compose.runOnIdle { assertEquals(listOf("members"), calls) }
    }

    private var picked by mutableStateOf(emptySet<String>())
    private val started = mutableListOf<String>()

    private fun sheet(canName: Boolean = true, loading: Boolean = false, bots: List<String> = profiles) = compose.setContent {
        MaterialTheme {
            NewChatSheet(bots.map { NewChatBot(it, it.replaceFirstChar { c -> c.uppercase() }) }, using = "default", selected = picked, canName = canName, loading = loading, error = "",
                onToggle = { picked = if (it in picked) picked - it else picked + it }, onStart = { started += it }, onNewBot = { calls += "new-bot" }, onDismiss = { calls += "dismiss" })
        }
    }

    @Test fun newChatTakesTwoToSixBotsAndTheOneInUseIsAlwaysIn() {
        sheet()
        compose.onNodeWithTag("new-chat-sheet").assertIsDisplayed()
        compose.onNodeWithText("New chat").assertIsDisplayed()
        // The bot in use is ticked and cannot be unticked; with no one else picked there is no group yet.
        compose.onNodeWithTag("new-chat-bot:default").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("new-chat-start").assertIsNotEnabled()
        compose.onNodeWithTag("new-chat-bot:coder").performClick()
        compose.onNodeWithTag("new-chat-bot:coder").assertIsOn()
        compose.onNodeWithTag("new-chat-start").assertIsEnabled()
        compose.onNodeWithTag("new-chat-bot:coder").performClick()
        compose.onNodeWithTag("new-chat-start").assertIsNotEnabled()
    }

    @Test fun startSendsTheNameTheOwnerTypedOrTheBotsNamesJoined() {
        sheet()
        compose.onNodeWithTag("new-chat-bot:writer").performClick()
        compose.onNodeWithTag("new-chat-bot:coder").performClick()
        compose.onNodeWithTag("new-chat-start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("Default, Coder, Writer"), started) }
        compose.onNodeWithTag("new-chat-name").performTextInput("  Review team ")
        compose.onNodeWithTag("new-chat-start").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Review team", started.last()) }
    }

    @Test fun withoutRenameTheNameFieldIsHiddenAndWithOnlyOneBotThereIsAWayToAddAnother() {
        sheet(canName = false, bots = listOf("default"))
        compose.onAllNodesWithTag("new-chat-name").assertCountEquals(0)
        compose.onNodeWithText("Add another bot to start a group.").assertIsDisplayed()
        compose.onNodeWithTag("new-chat-start").assertIsNotEnabled()
        compose.onNodeWithTag("new-chat-new-bot").performClick()
        compose.runOnIdle { assertTrue("new-bot" in calls) }
    }

    @Test fun deleteStaysDisabledUntilTheNameIsTyped() {
        compose.setContent { MaterialTheme { DeleteProfileDialog("coder", "Code Wizard", bot = true, admin = NativeProfileAdminState(), onDelete = { calls += "deleted" }, onDismiss = { calls += "dismissed" }, groups = 2) } }
        compose.onNodeWithText("Delete bot?").assertIsDisplayed()
        compose.onNodeWithText("Deletes Code Wizard and its chats, memory and settings on your computer. This can't be undone.").assertIsDisplayed()
        compose.onNodeWithText("Type coder to confirm").assertIsDisplayed()
        compose.onNodeWithText("Its group chats (2) will disappear from Bot Mode.").assertIsDisplayed()
        compose.onNodeWithTag("delete-profile-confirm-button").assertIsNotEnabled()
        compose.onNodeWithTag("delete-profile-confirm").performTextInput("code")
        compose.onNodeWithTag("delete-profile-confirm-button").assertIsNotEnabled()
        compose.onNodeWithTag("delete-profile-confirm").performTextInput("r")
        compose.onNodeWithTag("delete-profile-confirm-button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("deleted"), calls) }
    }

    @Test fun aDeleteInProgressCannotBeCancelledOrRepeated() {
        compose.setContent { MaterialTheme { DeleteProfileDialog("coder", "Code Wizard", bot = false, admin = NativeProfileAdminState(busy = true), onDelete = { }, onDismiss = { }) } }
        compose.onNodeWithText("Delete profile?").assertIsDisplayed()
        compose.onNodeWithTag("delete-profile-confirm-button").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
    }

    @Test fun theNewProfileFormChecksTheNameAndOffersACopy() {
        val created = mutableListOf<Triple<String, String, String>>()
        compose.setContent {
            MaterialTheme { NewProfileDialog(listOf("default" to "Default", "coder" to "Code profile"), NativeProfileAdminState(), onCreate = { n, f, r -> created += Triple(n, f, r) }, onDismiss = { }) }
        }
        compose.onNodeWithText("New profile").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-create").assertIsNotEnabled()
        compose.onNodeWithTag("new-profile-name").performTextInput("Bad name!")
        compose.onNodeWithText("Use lowercase letters, numbers, - or _, up to 64 characters.").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-create").assertIsNotEnabled()
        compose.onNodeWithTag("new-profile-name").performTextClearance()
        compose.onNodeWithTag("new-profile-name").performTextInput("Coder")
        compose.onNodeWithText("A profile with that name already exists.").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-create").assertIsNotEnabled()
        compose.onNodeWithTag("new-profile-name").performTextClearance()
        compose.onNodeWithTag("new-profile-name").performTextInput("writer")
        compose.onNodeWithTag("new-profile-create").assertIsEnabled()
        compose.onNodeWithTag("new-profile-from").performClick()
        compose.onNodeWithTag("new-profile-from:coder").performClick()
        compose.onNodeWithText("Copies its settings, instructions, keys, skills and notes.").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-role").performTextInput("Writes the docs")
        compose.onNodeWithTag("new-profile-create").performClick()
        compose.runOnIdle { assertEquals(listOf(Triple("writer", "coder", "Writes the docs")), created) }
    }
}
