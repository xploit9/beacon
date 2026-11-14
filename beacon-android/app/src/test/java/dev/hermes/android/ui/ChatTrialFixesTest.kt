package dev.hermes.android.ui

import dev.hermes.android.core.QueuedMessage
import org.junit.Assert.*
import org.junit.Test

// The placeholder home of the synthetic fixture paths, in two pieces so no source literal reads like a real Windows home path.
private val exampleHome = "C:\\" + "Users\\Example\\"

/** What the hands-on trial of 1.1.33 found in the chat: menus, attachment text and list captions. Synthetic text only. */
class ChatTrialFixesTest {
    private fun local(state: String, admission: String = "", kind: String = "queue") = QueuedMessage("Hello", state = state, admissionId = admission, kind = kind)

    @Test fun hermesHoldsAnAcceptedMessageSoItIsNeverOfferedToBeSentAgain() {
        assertFalse(LocalAction.Resend in localMessageActions(local("accepted", "admission-1"), canDismiss = true))
        assertFalse(LocalAction.Resend in localMessageActions(local("accepted", kind = "steer"), canDismiss = true))
        assertFalse(LocalAction.Resend in localMessageActions(local("settled", "admission-1"), canDismiss = true))
        assertEquals(listOf(LocalAction.Check, LocalAction.Resend, LocalAction.RemoveCopy), localMessageActions(local("unknown", "admission-1"), canDismiss = true))
        assertEquals(listOf(LocalAction.RemoveCopy), localMessageActions(local("accepted"), canDismiss = true))
        // A message the phone still holds is in the box above the composer, never a bubble with a menu.
        assertTrue(localMessageActions(local("queued"), canDismiss = true).isEmpty())
        assertTrue(localMessageActions(local("accepted"), canDismiss = false).isEmpty())
        assertTrue(localMessageActions(local("sent"), canDismiss = true).isEmpty())
    }

    @Test fun aHeldBubbleOpensItsMenuByLongPressWithTheSameItems() {
        // A send whose outcome is unknown: check, resend, remove this copy, whatever the held copy's state (it is the pending send's own menu).
        for (state in listOf("sending", "unknown", "accepted")) {
            assertEquals(state, listOf(LocalAction.Check, LocalAction.Resend, LocalAction.RemoveCopy), localBubbleActions(local(state), canDismiss = false, unconfirmed = true))
        }
        // Otherwise it is the held message's own menu, unchanged.
        assertEquals(localMessageActions(local("unknown", "admission-1"), canDismiss = true), localBubbleActions(local("unknown", "admission-1"), canDismiss = true, unconfirmed = false))
        assertEquals(listOf(LocalAction.RemoveCopy), localBubbleActions(local("accepted"), canDismiss = true, unconfirmed = false))
        // A bubble with no actions has nothing to long-press: no menu and no accessibility actions.
        assertTrue(localBubbleActions(local("sent"), canDismiss = true, unconfirmed = false).isEmpty())
        assertTrue(localBubbleActions(local("accepted"), canDismiss = false, unconfirmed = false).isEmpty())
    }

    @Test fun aPhotoShowsAsItselfAndNeverAsThePathOnTheComputer() {
        val path = "${exampleHome}AppData\\cache\\native-inputs\\abc123\\upload_x1y2.png"
        val sent = splitAttachedMedia("Describe this image in five words\n\n[Image attached at: $path] [screenshot]")
        assertEquals("Describe this image in five words", sent.text)
        assertEquals(listOf(AttachedMedia(true, path)), sent.media)
        assertFalse(sent.text.contains("attached at"))
        val only = splitAttachedMedia("[Image attached at: /home/example/cache/upload_a.png]")
        assertEquals("", only.text)
        assertEquals(listOf(AttachedMedia(true, "/home/example/cache/upload_a.png")), only.media)
        val two = splitAttachedMedia("Compare\n[Image attached at: /tmp/a.png] [photo]\n[File attached at: /tmp/notes.txt]")
        assertEquals("Compare", two.text)
        assertEquals(listOf(AttachedMedia(true, "/tmp/a.png"), AttachedMedia(false, "/tmp/notes.txt")), two.media)
        val plain = splitAttachedMedia("Words with [brackets] and no file")
        assertEquals("Words with [brackets] and no file", plain.text)
        assertTrue(plain.media.isEmpty())
    }

    /** Hermes' stored text of a photo turn (live on the demo, 9 Nov): the words, a blank line, one hint line per image, then one "[screenshot]" line per image (tui_gateway/session_history.py). */
    @Test fun aStoredPhotoRowWithItsScreenshotLinesShowsOnlyTheWordsAndNoPath() {
        val one = splitAttachedMedia("What color is the square in this image\n\n[Image attached at: /home/example/cache/native-inputs/ab/upload_a.png]\n[screenshot]")
        assertEquals("What color is the square in this image", one.text)
        assertEquals(listOf(AttachedMedia(true, "/home/example/cache/native-inputs/ab/upload_a.png")), one.media)
        val two = splitAttachedMedia("Compare\n\n[Image attached at: /home/example/a.png]\n[Image attached at: /home/example/b.png]\n[screenshot]\n[screenshot]")
        assertEquals("Compare", two.text)
        assertEquals(listOf(AttachedMedia(true, "/home/example/a.png"), AttachedMedia(true, "/home/example/b.png")), two.media)
        // A "[screenshot]" line the owner typed, with no attachment before it, stays.
        assertEquals("Look at this\n[screenshot]", splitAttachedMedia("Look at this\n[screenshot]").text)
    }

    @Test fun aListLineCutInsideTheMarkerStillShowsNoPath() {
        assertEquals("You: Describe this", splitAttachedMedia("You: Describe this [Image attached at: ${exampleHome}Ap").text)
    }

    @Test fun aChatMadeOnThisPhoneIsNotCaptionedAsLocal() {
        assertFalse(showsSourceCaption("local", thread = false))
        assertFalse(showsSourceCaption("Local", thread = false))
        assertFalse(showsSourceCaption("android", thread = false))
        assertFalse(showsSourceCaption("", thread = false))
        assertFalse(showsSourceCaption("telegram", thread = true))
        assertTrue(showsSourceCaption("telegram", thread = false))
        assertTrue(showsSourceCaption("tui", thread = false))
    }
}
