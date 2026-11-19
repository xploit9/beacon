package dev.hermes.android.push

import dev.hermes.android.data.ChatAttachmentStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * CHAT-121 follow-up 7: a sign-out from Hermes' side (Hermes removed this phone; BeaconMessagingService's deviceRemoved, through forgetAfterDeviceRemoval)
 * clears the large files kept on this phone for sends, as the owner's own sign-out does, even when the other cleanup fails. Synthetic files only.
 */
class DeviceRemovalCleanupTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aSignOutFromHermesSideClearsTheKeptCopies() {
        val previous = ChatAttachmentStore.dir
        ChatAttachmentStore.dir = folder.newFolder("chat-attachments")
        try {
            val kept = List(2) { val id = UUID.randomUUID().toString(); ChatAttachmentStore.adopt(folder.newFile("picked-$id").apply { writeBytes(ByteArray(1024) { 7 }) }, id) }
            assertTrue(kept.all { it.isFile })
            val ran = mutableListOf<String>()
            // Each cleanup runs as deviceRemoved launches it; the saved screens' cleanup fails here and the kept files go all the same.
            forgetAfterDeviceRemoval({ step -> runBlocking { step() } }) { ran += "screens"; error("Synthetic: the screen cache could not be read") }
            assertEquals(listOf("screens"), ran)
            assertTrue("every kept copy is gone: $kept", kept.none { it.exists() })
            assertTrue(ChatAttachmentStore.dir!!.listFiles().isNullOrEmpty())
        } finally { ChatAttachmentStore.dir = previous }
    }
}
