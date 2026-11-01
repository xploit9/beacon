package dev.hermes.android.uiux.drawer

import dev.hermes.android.ui.ConversationDestructiveGroup
import dev.hermes.android.ui.ConversationStrings
import dev.hermes.android.ui.conversationActionGroups
import dev.hermes.android.ui.conversationActionLabel
import dev.hermes.android.ui.conversationDebugActions
import dev.hermes.android.ui.conversationDestructiveActions
import dev.hermes.android.ui.conversationMoreActions
import dev.hermes.android.ui.conversationMoreMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationMenuDrawerTest {
    @Test fun deleteIsItsOwnLastGroupWithNoHeading() {
        assertEquals(listOf("Conversation", ConversationDestructiveGroup), conversationActionGroups.keys.toList())
        assertEquals("", ConversationDestructiveGroup)
        assertEquals(listOf("Delete conversation"), conversationDestructiveActions)
        assertEquals(listOf("New conversation", "Rename", "Pin", "Archive", "Branch"), conversationActionGroups.getValue("Conversation"))
        // No one-item "Manage" heading any more.
        assertFalse("Manage" in conversationActionGroups.keys)
        assertEquals("Delete is the last action of the groups", "Delete conversation", conversationActionGroups.values.flatten().last())
    }

    @Test fun everyActionIdIsStillThereForTheMenuToDispatchOn() {
        assertEquals(listOf("New conversation", "Rename", "Pin", "Archive", "Branch", "Delete conversation"), conversationActionGroups.values.flatten())
        assertEquals(listOf("Toolsets", "Compress context", "Undo last turn", "Usage"), conversationMoreActions)
    }

    @Test fun compressContextReadsShortenHistoryButKeepsItsId() {
        assertEquals("Shorten history", conversationActionLabel("Compress context", pinned = false, archived = false))
        assertEquals("Unpin", conversationActionLabel("Pin", pinned = true, archived = false))
        assertEquals("Unarchive", conversationActionLabel("Archive", pinned = false, archived = true))
        assertEquals("Tools", conversationActionLabel("Toolsets", pinned = false, archived = false))
        assertEquals("Delete conversation", conversationActionLabel("Delete conversation", pinned = false, archived = false))
        assertTrue("Shorten history" in ConversationStrings)
    }

    @Test fun copyConversationIdIsADebugRow() {
        assertEquals(listOf("Copy conversation ID"), conversationDebugActions)
        assertEquals(conversationMoreActions, conversationMoreMenu(debug = false))
        assertEquals(conversationMoreActions + "Copy conversation ID", conversationMoreMenu(debug = true))
        assertTrue("Copy conversation ID" in ConversationStrings)
    }

    @Test fun theMenuTextsHaveNoBlankEntryForTheTranslationCheckToTrip() {
        assertTrue(ConversationStrings.none { it.isBlank() })
        assertTrue("Conversation" in ConversationStrings)
        assertFalse("Manage" in ConversationStrings)
    }
}
