package dev.hermes.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The chat never words an internal state to the owner, and its menus and polling keep to what the trial asked for. Reads the chat sources, so it needs no app code. */
class RetiredChatWordsTest {
    private fun source(name: String): String {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val file = File(module, "src/main/java/dev/hermes/android/$name")
        assertTrue("${file.path} must exist", file.isFile)
        return file.readText()
    }

    @Test fun noScreenSourceStillWritesTheRetiredInternalStatusWords() {
        val retired = listOf("final reply not confirmed", "live output retained", "history changed during refresh", "final history refresh failed",
            "\"Reply not confirmed\"", "\"Accepted\"", "Accepted; position unconfirmed", "check history", "· review history")
        for (name in listOf("BeaconViewModel.kt", "ui/ChatScreen.kt")) {
            val text = source(name)
            retired.forEach { assertFalse("$name still shows \"$it\"", text.contains(it)) }
        }
    }

    @Test fun oneLongPressOpensOneMenu() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse("a bubble's plain text is not a selection container (Android's toolbar would open beside Beacon's menu)",
            Regex("""SelectionContainer \{ Text\((findHighlighted|message\.text)""").containsMatchIn(chat))
        assertFalse("\"Copy source\" is not words an owner reads", chat.contains("\"Copy source\""))
        assertFalse(chat.contains("\"Remove saved local copy\"") || chat.contains("\"Remove local message\""))
    }

    @Test fun theSecurePromptIsPolledOffTheMainThread() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse(Regex("""while \(true\) \{ vm\.refreshSecurePrompt\(\)""").containsMatchIn(chat))
        assertTrue(chat.contains("withContext(Dispatchers.Default) { vm.refreshSecurePrompt() }"))
    }

    @Test fun aDeniedMicrophoneOffersAndroidSettings() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse("a bare snackbar is not the way out of a denied permission", chat.contains("Allow microphone permission to call"))
        assertTrue(chat.contains("uiText(\"Open settings\")"))
    }

    @Test fun theApprovalChipNeverSaysUnknown() {
        val menu = source("ui/NativeApprovalMenu.kt")
        assertFalse("the chip's words come from shieldDescription, which has no 'Unknown'", menu.contains("uiText(\"Approvals\") + \": \" + uiText(approvalChoiceLabel(selection))"))
        assertFalse(menu.contains("Approvals are unknown"))
        assertTrue(menu.contains("shieldDescription(selection, "))
    }

    @Test fun thePhoneApprovalsRowIsNotNamedLikeTheChatsApprovals() {
        val row = source("ui/ApprovalChoiceRow.kt")
        assertTrue("the phone-access choice is its own setting, so it says so", row.contains("label = uiText(\"Phone actions\")"))
        assertFalse(row.contains("label = uiText(\"Approvals\")"))
    }

    @Test fun theEmptyChatSphereLeavesRoomForItsWordsWhenTheKeyboardIsOpen() {
        val chat = source("ui/ChatScreen.kt")
        assertTrue(Regex("""heightIn\(max = \(viewportHeight - [0-9]+\.dp\)[^\n]*chat-sphere-empty""").containsMatchIn(chat))
    }
}
