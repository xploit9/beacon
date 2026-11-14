package dev.hermes.android

import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.json
import dev.hermes.android.ui.AttachedMedia
import dev.hermes.android.ui.chatPreviewLine
import dev.hermes.android.ui.isOwnUpload
import dev.hermes.android.ui.splitAttachedMedia
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

// The placeholder home of the synthetic fixture paths, in two pieces so no source literal reads like a real Windows home path.
private val exampleHome = "C:\\" + "Users\\Example\\"

/**
 * The live check of 1.1.34 on the owner's Hermes: Hermes rewrites the stored user row of a photo with its own vision note and the file path, and the chat
 * must show what the owner typed plus the attachment, from every path that reads a stored row. The templates are Hermes' own
 * (gateway/run_inbound.py `_enrich_message_with_vision`, `_prepend_inbound_media_file_notes`, gateway/run.py `_build_document_context_note`). Synthetic text and ids only.
 */
class LiveFixTest {
    private val hash = "5d3c74026cd9dcf0d35468e783e47f6fe91445954ffadf38c5b96fb09bd1ba28"
    private val path = "${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\upload_pwmaj5u4.png"
    private val caption = "What color is the square in this image Reply in one word"
    private fun seen(at: String = path, description: String = "A solid orange rectangle is centered on a dark background. No text is visible.") =
        "[The user sent an image~ Here's what I can see:\n$description]\n[If you need a closer look, use vision_analyze with image_url: $at ~]"

    // ---- the one cleaner

    @Test fun theVisionNoteAndThePathNeverReachTheBubbleOnlyTheCaptionAndThePhotoDo() {
        val sent = splitAttachedMedia("${seen()}\n\n$caption")
        assertEquals(caption, sent.text)
        assertEquals(listOf(AttachedMedia(true, path)), sent.media)
        assertFalse(sent.text.contains("vision_analyze") || sent.text.contains("native-inputs") || sent.text.contains("I can see"))
    }

    @Test fun aPhotoWithoutACaptionLeavesNoText() {
        val sent = splitAttachedMedia(seen())
        assertEquals("", sent.text)
        assertEquals(1, sent.media.size)
    }

    @Test fun severalPhotosEachGetTheirOwnNote() {
        val second = path.replace("pwmaj5u4", "k29fd0aa")
        val sent = splitAttachedMedia("${seen()}\n\n${seen(second, "A blue circle.")}\n\n$caption")
        assertEquals(caption, sent.text)
        assertEquals(listOf(path, second), sent.media.map { it.path })
    }

    @Test fun aDescriptionThatHoldsBracketsDoesNotBreakTheNote() {
        val sent = splitAttachedMedia("${seen(description = "A chart with [axes] and a legend [1] in the corner.")}\n\n$caption")
        assertEquals(caption, sent.text)
        assertEquals(1, sent.media.size)
    }

    @Test fun theTwoWordingsForAPhotoHermesCouldNotSeeAreCleanedToo() {
        val unseen = "[The user sent an image but I couldn't quite see it this time (>_<) You can try looking at it yourself with vision_analyze using image_url: $path]"
        val broken = "[The user sent an image but something went wrong when I tried to look at it~ You can try examining it yourself with vision_analyze using image_url: $path]"
        for (note in listOf(unseen, broken)) {
            val sent = splitAttachedMedia("$note\n\n$caption")
            assertEquals(caption, sent.text)
            assertEquals(listOf(AttachedMedia(true, path)), sent.media)
        }
    }

    @Test fun documentAudioAndVideoNotesAreCleanedToTheirFileName() {
        val doc = "[The user sent a document: 'report.pdf'. It is saved at: /home/example/cache/documents/doc_ab12_report.pdf. Its text is not inlined here (it's a binary format such as PDF or DOCX). " +
            "To read it, extract the document's text yourself — for example with the terminal tool or the ocr-and-documents skill — before answering, instead of asking the user to paste the contents.]"
        val text = "[The user sent a text document: 'notes.txt'. Its content has been included below. The file is also saved at: /home/example/cache/documents/doc_x_notes.txt]"
        val audio = "[The user sent an audio file attachment: 'memo.m4a'. It is saved at: /home/example/cache/documents/doc_q_memo.m4a. Its content is not inlined here. " +
            "If the user's request involves what the audio contains, transcribe or process it yourself — for example by passing the path to a transcription or media tool — instead of asking the user to describe it. Only ask what to do with it if their intent is genuinely unclear.]"
        val sent = splitAttachedMedia("$audio\n\n$text\n\n$doc\n\nSummarize these")
        assertEquals("Summarize these", sent.text)
        assertEquals(listOf("memo.m4a", "notes.txt", "report.pdf"), sent.media.map { it.name })
        assertTrue("a document is a file, never a photo", sent.media.none { it.image })
    }

    @Test fun aNoteSquashedIntoOneLineAndAnUnfinishedNoteAreCleanedToo() {
        val squashed = "[The user sent an image~ Here's what I can see: A solid orange rectangle.] [If you need a closer look, use vision_analyze with image_url: $path ~] $caption"
        assertEquals(caption, splitAttachedMedia(squashed).text)
        assertEquals("", splitAttachedMedia("[The user sent an image~ Here's what I can see: A solid orange rect").text)
    }

    @Test fun plainWordsThatMentionAnImageAreLeftAlone() {
        assertEquals("The user sent an image of a cat, [sic]", splitAttachedMedia("The user sent an image of a cat, [sic]").text)
    }

    // ---- every path that turns a stored row into something the owner sees

    @Test fun aStoredRowWithTheNoteIsADisplayedAsTheCaptionOnly() {
        val row = json("row_id" to 31L, "role" to "user", "content" to "${seen()}\n\n$caption")
        assertEquals(caption, Message.from(row, 0, "chat").text)
    }

    @Test fun aSteerRowCarryingAPhotoStillPairsByTheWordsTheOwnerTyped() {
        val rows = listOf(json("row_id" to 10L, "role" to "user", "content" to "Question"),
            json("row_id" to 12L, "role" to "user", "display_kind" to "steer", "content" to "${seen()}\n\nLook at this instead"))
        val bubble = QueuedMessage("Look at this instead", id = "steer-1", createdAt = 1, state = "accepted", kind = "steer")
        assertEquals(mapOf("steer-1" to "12"), steerRowPairs(listOf(bubble), emptyList(), rows))
    }

    // ---- the guard: only the owner's own uploads are ever fetched

    @Test fun onlyHermesNativeInputUploadsAreFetchedForAThumbnail() {
        assertTrue(isOwnUpload(path))
        assertTrue(isOwnUpload("/home/example/.hermes/cache/documents/native-inputs/$hash/upload_pwmaj5u4.jpeg"))
        for (other in listOf(
            "${exampleHome}Documents\\photo.png",
            "${exampleHome}AppData\\Local\\hermes\\cache\\documents\\doc_ab12cd34_photo.png",
            "${exampleHome}AppData\\Local\\hermes\\cache\\images\\img_ab12cd34.png",
            "${exampleHome}AppData\\Local\\hermes\\config.yaml",
            "${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\..\\..\\..\\secret.png",
            "${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\upload_pwmaj5u4.png.exe",
            "\\\\server\\share\\native-inputs\\$hash\\upload_a.png",
            "//server/share/native-inputs/$hash/upload_a.png",
            "file://server/share/native-inputs/$hash/upload_a.png",
            "file:///home/example/.hermes/cache/documents/native-inputs/$hash/upload_a.png",
            "\\\\?\\${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\upload_a.png",
            "\\\\.\\${exampleHome}native-inputs\\$hash\\upload_a.png",
            "${exampleHome}AppData\\Local\\hermes\\cache\\documents\\native-inputs\\$hash\\upload_a.png:stream",
            "C:upload_a.png",
            "native-inputs/$hash/upload_a.png",
            "/\\server/native-inputs/$hash/upload_a.png",
            "/home/example/.hermes/cache/documents/native-inputs/$hash/upload_x.svg")) assertFalse(other, isOwnUpload(other))
    }

    // ---- the photo itself

    @Test fun aRowWhoseDisplayTextHidesTheNoteStillNamesItsPhotoSoItCanBeShownAfterAReopen() {
        val row = json("row_id" to 31L, "role" to "user", "display_text" to caption, "content" to "${seen()}\n\n$caption")
        val message = Message.from(row, 0, "chat")
        assertEquals(caption, message.text)
        assertEquals(listOf(path), message.media.map { it.path })
    }

    @Test fun aRowWithTheNoteInItsTextCarriesItsPhotoOnce() {
        val message = Message.from(json("row_id" to 31L, "role" to "user", "content" to "${seen()}\n\n$caption"), 0, "chat")
        assertEquals(listOf(path), message.media.map { it.path })
        assertTrue(Message.from(json("row_id" to 32L, "role" to "user", "content" to "Plain words"), 0, "chat").media.isEmpty())
        assertTrue("an assistant row is never cleaned", Message.from(json("row_id" to 33L, "role" to "assistant", "content" to seen()), 0, "chat").media.isEmpty())
    }

    @Test fun theSentPhotoKeepsASmallPictureWithItsReceiptAndNeverTheFullSizeData() {
        val picked = listOf(json("name" to "408.png", "mime" to "image/png", "staged_base64" to "AAAA"), json("name" to "notes.pdf", "mime" to "application/pdf", "staged_base64" to "BBBB"))
        val receipts = JSONArray().put(json("name" to "408.png", "path" to path)).put(json("name" to "notes.pdf", "path" to "/x/notes.pdf"))
        val small = { file: JSONObject -> if (file.getString("mime").startsWith("image/")) "THUMB" else null }
        val kept = withKeptThumbnails(receipts, picked, small)
        assertEquals("THUMB", kept.getJSONObject(0).getString("thumb_base64"))
        assertFalse("a document has no picture", kept.getJSONObject(1).has("thumb_base64"))
        assertEquals(path, kept.getJSONObject(0).getString("path"))
        val byName = withKeptThumbnails(JSONArray().put(json("name" to "408.png")), picked + json("name" to "other.png", "mime" to "image/png", "staged_base64" to "CCCC"), small)
        assertEquals("pairs by name when the counts differ", "THUMB", byName.getJSONObject(0).getString("thumb_base64"))
        val fromDraft = withKeptThumbnails(JSONArray(picked), picked, small)
        assertTrue("the picked file's own data never enters the cache", (0 until fromDraft.length()).none { fromDraft.getJSONObject(it).has("staged_base64") })
        assertTrue("and the picked list itself is left alone", picked.all { it.has("staged_base64") })
    }

    // ---- the drawer

    @Test fun theDrawerPreviewOfAPhotoChatIsTheCaptionOnly() {
        val row = json("session_id" to "chat-1", "title" to "Photo chat", "preview" to "${seen()}\n\n$caption", "message_count" to 2)
        assertEquals(caption, chatPreviewLine(row, null))
        val cut = json("session_id" to "chat-1", "title" to "Photo chat", "preview" to "[The user sent an image~ Here's what I can see:\nA solid orange rect", "message_count" to 2)
        assertEquals("a preview cut inside the note shows the count, never the note", "2 messages", chatPreviewLine(cut, null))
        val plain = json("session_id" to "chat-2", "title" to "Other", "preview" to "Just words", "message_count" to 1)
        assertEquals("Just words", chatPreviewLine(plain, null))
    }

    private fun source(name: String): String {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        return File(module, "src/main/java/dev/hermes/android/$name").readText()
    }

    @Test fun theSentBubbleShowsThePickedPhotoAndTheClosedSearchReturnsTheDrawerToTheTop() {
        val chat = source("ui/ChatScreen.kt")
        assertTrue("the bubble of a message still waiting shows the picked image", chat.contains("LocalAttachment(attachment)"))
        assertTrue(chat.contains("thumb = at?.let { message.attachments[it].string(\"thumb_base64\") }"))
        assertTrue("closing search scrolls the drawer to its top, only when search turns from on to off", chat.contains("LaunchedEffect(searching) { if (wasSearching && !searching) drawerList.scrollToItem(0); wasSearching = searching }"))
        assertFalse("a recreation (unfold, rotation) keeps the drawer's place", chat.contains("LaunchedEffect(searching) { if (!searching) drawerList.scrollToItem(0) }"))
        val model = source("BeaconViewModel.kt")
        assertFalse("every receipt of a sent photo is written with its small picture", model.contains("sentAttachmentReceipts(result, send.draft) ?: JSONArray()") || model.contains("sentAttachmentReceipts(o, send.draft) ?: JSONArray()"))
    }

    // ---- review round 4

    @Test fun aDocumentNameWithBracketsKeepsItsCaptionAndItsAttachment() {
        val note = "[The user sent a document: 'Report [final].pdf'. It is saved at: /home/example/cache/documents/doc_ab12_Report [final].pdf. Its text is not inlined here (it's a binary format such as PDF or DOCX). " +
            "To read it, extract the document's text yourself — for example with the terminal tool or the ocr-and-documents skill — before answering, instead of asking the user to paste the contents.]"
        val sent = splitAttachedMedia("$note\n\nPlease summarise this")
        assertEquals("Please summarise this", sent.text)
        assertEquals(listOf("Report [final].pdf"), sent.media.map { it.name })
        val inlined = "[The user sent a text document: 'a [1].txt'. Its content has been included below. The file is also saved at: /home/example/cache/documents/doc_x_a [1].txt]"
        assertEquals("What is in it", splitAttachedMedia("$inlined\n\nWhat is in it").text)
    }

    @Test fun onlyTheNotesHermesPutsInFrontAreCutAndACopyPastedElsewhereStays() {
        val note = "[The user sent a document: 'a.pdf'. It is saved at: /tmp/a.pdf. Its text is not inlined here (it's a binary format such as PDF or DOCX). To read it, extract the document's text yourself — for example with the terminal tool or the ocr-and-documents skill — before answering, instead of asking the user to paste the contents.]"
        val pasted = "I saw this in the log: $note why?"
        assertEquals(pasted, splitAttachedMedia(pasted).text)
        assertTrue(splitAttachedMedia(pasted).media.isEmpty())
        val photoNote = seen()
        val midText = "Before $photoNote after"
        assertEquals(midText, splitAttachedMedia(midText).text)
        assertEquals("a note in front is cut and the same note later in the words stays", "Look: $note", splitAttachedMedia("${seen()}\n\nLook: $note").text)
        assertEquals("a note cut short after text of the owner's own is theirs", "Typed [The user sent an image~ Here's what I can see: A so", splitAttachedMedia("Typed [The user sent an image~ Here's what I can see: A so").text)
    }

    @Test fun theOldMarkerIsCutOnlyWhereHermesPutsItAfterTheWords() {
        assertEquals("Describe this", splitAttachedMedia("Describe this\n\n[Image attached at: /tmp/a.png] [screenshot]").text)
        val middle = "See [Image attached at: /tmp/a.png] here and more words"
        assertEquals(middle, splitAttachedMedia(middle).text)
    }

    @Test fun theVoiceMessageNoteNeverShowsItsPath() {
        val voice = "[The user sent a voice message: /home/example/cache/audio/voice_ab12.ogg (duration: 0:07)]"
        val sent = splitAttachedMedia("$voice\n\nTranscribe it please")
        assertEquals("Transcribe it please", sent.text)
        assertFalse(sent.text.contains("voice_ab12") || sent.text.contains("/home/"))
        assertEquals(1, sent.media.size)
        assertFalse("a voice message is a file, never a photo", sent.media.single().image)
        assertEquals("", splitAttachedMedia("[The user sent a voice message: ${exampleHome}cache\\audio\\voice_ab12.ogg]").text)
        assertEquals("", splitAttachedMedia("[The user sent a voice message but it came through empty or inaudible — speech-to-text returned no words. Do not guess at the content; ask the user to resend or type it out.]").text)
        assertEquals("", splitAttachedMedia("[The user sent a voice message: /home/example/cache/audio/voice_ab12.og").text)
    }

    @Test fun aDeletedChatTakesTheKeptPictureOfEachSentPhotoWithIt() {
        val rows = listOf(
            json("role" to "user", "row_id" to 41L),
            json("role" to "assistant", "row_id" to 42L),
            json("role" to "user", "row_id" to "43"),
            json("role" to "user", "_row_id" to 44L),
            json("role" to "user", "row_id" to 41L),
            json("role" to "user", "row_id" to 0L),
            json("role" to "user"),
        )
        assertEquals(listOf("attachments:41", "attachments:43", "attachments:44"), keptAttachmentKinds(rows))
        assertTrue(keptAttachmentKinds(emptyList()).isEmpty())
    }
}
