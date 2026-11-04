package dev.hermes.android.share

import dev.hermes.android.AppState
import dev.hermes.android.ChatState
import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share into Beacon (4 Nov): what the chooser accepts from other apps, which is untrusted, and when the chosen chat may take it. */
class SharedContentTest {
    private val pkg = "dev.hermes.android"
    private val image = "content://media/external/images/media/42"
    private fun content(text: CharSequence? = null, subject: CharSequence? = null, streams: List<String> = emptyList(), type: (String) -> String? = { "image/jpeg" }) =
        sharedContentOf(text, subject, streams, type, pkg)

    @Test fun plainTextIsKeptAsWrittenAndTrimmed() {
        val shared = content("  Pick up bread\nand milk \n")
        assertEquals("Pick up bread\nand milk", shared.text)
        assertTrue(shared.images.isEmpty())
        assertFalse(shared.isEmpty)
    }

    @Test fun aBareLinkKeepsItsPageTitleAboveIt() {
        assertEquals("Trip ideas\nhttps://example.com/trips", content("https://example.com/trips", "Trip ideas").text)
        // Not when the sender already put the title in the text, and not for ordinary text.
        assertEquals("Trip ideas\nhttps://example.com/trips", content("Trip ideas\nhttps://example.com/trips", "Trip ideas").text)
        assertEquals("see you at 6", content("see you at 6", "Re: dinner").text)
        assertEquals("https://example.com/a", content("https://example.com/a", "").text)
    }

    @Test fun controlCharactersAreDroppedAndNothingIsEmpty() {
        assertEquals("ab", content("a\u0007b").text)
        assertTrue(content(null).isEmpty)
        assertTrue(content("   ").isEmpty)
        assertTrue(content("\u0000\u0001").isEmpty)
    }

    @Test fun textOverTheLimitIsRefusedNotCut() {
        val atLimit = content("x".repeat(SHARE_TEXT_LIMIT))
        assertEquals(SHARE_TEXT_LIMIT, atLimit.text.length)
        assertFalse(atLimit.tooLong)
        val over = content("x".repeat(SHARE_TEXT_LIMIT + 1))
        assertTrue(over.tooLong)
        assertEquals("", over.text)
    }

    @Test fun onlyImagesFromAnotherAppsContentProviderAreKept() {
        val types = mapOf(image to "image/png", "content://docs/file/7" to "application/pdf", "content://media/external/images/media/43" to "IMAGE/JPEG")
        val shared = content(streams = types.keys.toList(), type = types::get)
        assertEquals(listOf(image, "content://media/external/images/media/43"), shared.images)
        assertTrue("an item that is not an image is left out", content(streams = listOf("content://docs/file/7"), type = { "application/pdf" }).isEmpty)
        assertTrue("an item with no known type is left out", content(streams = listOf(image), type = { null }).isEmpty)
    }

    @Test fun aFilePathOrOneOfBeaconsOwnProvidersIsNeverRead() {
        for (uri in listOf(
            "file:///data/data/dev.hermes.android/files/secret.png", "/sdcard/DCIM/a.jpg", "https://example.com/a.png", "intent://x",
            "content://dev.hermes.android.files/shared/a.png", "content://dev.hermes.android.debug.files/shared/a.png", "content://DEV.HERMES.ANDROID.files/a",
            "content://0${'@'}dev.hermes.android.files/a.png", "content://x%40dev.hermes.android.files/a.png", "content:///nothing", "", "content://",
        )) assertFalse(uri, acceptableShareUri(uri, pkg))
        for (uri in listOf(image, "content://com.android.providers.media.documents/document/image%3A5", "content://com.example.gallery.fileprovider/images/1.jpg")) assertTrue(uri, acceptableShareUri(uri, pkg))
        assertFalse("an address longer than any provider uses", acceptableShareUri("content://media/" + "a".repeat(3000), pkg))
    }

    @Test fun manyImagesAreCappedAndTheRestCounted() {
        val streams = (1..SHARE_IMAGE_LIMIT + 3).map { "content://media/external/images/media/$it" }
        val shared = content(streams = streams)
        assertEquals(streams.take(SHARE_IMAGE_LIMIT), shared.images)
        assertEquals(3, shared.skippedImages)
        assertEquals("the same image twice is one", 1, content(streams = listOf(image, image)).images.size)
    }

    @Test fun sharedTextGoesUnderWhatIsAlreadyTypedNeverOverIt() {
        assertEquals("shared", mergeSharedDraft("", "shared"))
        assertEquals("shared", mergeSharedDraft("  \n", "shared"))
        assertEquals("typed so far\n\nshared", mergeSharedDraft("typed so far\n", "shared"))
        assertEquals("typed", mergeSharedDraft("typed", ""))
    }

    @Test fun theChooserNamesWhatWasSharedInOneShortLine() {
        assertEquals("first line", shareSnippet("\n  first line  \nsecond"))
        assertEquals("", shareSnippet("  \n "))
        assertEquals("a".repeat(79) + "…", shareSnippet("a".repeat(200)))
        assertEquals("a".repeat(80), shareSnippet("a".repeat(80)))
    }

    private fun row(id: String, title: String = "Chat $id", vararg extra: Pair<String, Any>) = json("session_id" to id, "title" to title).apply { extra.forEach { put(it.first, it.second) } }
    private fun index(vararg rows: JSONObject) = JSONObject().put("sessions", JSONArray(rows.toList()))

    @Test fun recentChatsAreTheSavedOnesBeaconCanOpenAsTheyAre() {
        val saved = index(
            row("a"), row("b", "  Dinner  "), row("a", "duplicate"), row("c", "", "source" to "telegram"), row("d", "Pulled in", "is_cli_session" to true),
            row("e", "Old", "archived" to true), row("f", "Read only", "read_only" to true), row("../g"), row("h", "Web", "source" to "webui", "is_cli_session" to true),
        )
        assertEquals(listOf("a" to "Chat a", "b" to "Dinner", "h" to "Web"), recentChats(saved))
        assertEquals(2, recentChats(index(row("1"), row("2"), row("3")), limit = 2).size)
        assertTrue(recentChats(null).isEmpty())
        assertTrue(recentChats(JSONObject()).isEmpty())
        assertEquals(SHARE_RECENT_LIMIT, recentChats(index(*(1..20).map { row("s$it") }.toTypedArray())).size)
    }

    @Test fun aShareIsTakenOnceAndOnlyWhileFresh() {
        val share = PendingShare(ShareTarget.Beacon, SharedContent("hi", emptyList()), offeredAt = 1_000)
        SharedIntake.offer(share)
        assertEquals(share, SharedIntake.pending.value)
        assertFalse("too late: the app never opened", SharedIntake.claim(share, 1_000 + SHARE_VALID_MILLIS + 1))
        assertNull("a stale share is gone, not kept for a later session", SharedIntake.pending.value)
        SharedIntake.offer(share)
        assertTrue(SharedIntake.claim(share, 1_000 + SHARE_VALID_MILLIS))
        assertFalse("the second taker gets nothing", SharedIntake.claim(share, 2_000))
        SharedIntake.offer(share.copy(offeredAt = 5_000))
        assertFalse("a clock that went backwards is not fresh", SharedIntake.claim(share.copy(offeredAt = 5_000), 4_000))
    }

    @Test fun theChosenChatTakesTheShareOnlyOnceItIsOpenWithItsDraftRestored() {
        val open = ChatState(sessionId = "c1", projectionReady = true)
        val loading = ChatState(sessionId = "c1", projectionReady = false)
        val chatTab = AppState(tab = "Chat")
        assertTrue(shareChatOpen(ShareTarget.Chat("c1"), open, chatTab, beaconOpen = false))
        assertFalse("still loading", shareChatOpen(ShareTarget.Chat("c1"), loading, chatTab, beaconOpen = false))
        assertFalse("another chat is open", shareChatOpen(ShareTarget.Chat("c2"), open, chatTab, beaconOpen = false))
        assertFalse("no chat yet", shareChatOpen(ShareTarget.NewChat, ChatState(), chatTab, beaconOpen = false))
        assertTrue(shareChatOpen(ShareTarget.NewChat, open, chatTab, beaconOpen = false))
        assertFalse("a new chat is still being made", shareChatOpen(ShareTarget.NewChat, open, chatTab.copy(newChatOpening = true), beaconOpen = false))
        val beaconTab = AppState(tab = "Beacon")
        assertTrue(shareChatOpen(ShareTarget.Beacon, open, beaconTab, beaconOpen = true))
        assertFalse("the previous chat is still showing", shareChatOpen(ShareTarget.Beacon, open, beaconTab, beaconOpen = false))
        assertFalse(shareChatOpen(ShareTarget.Beacon, open, beaconTab.copy(beaconOpening = true), beaconOpen = true))
        assertFalse(shareChatOpen(ShareTarget.Beacon, open, beaconTab.copy(beaconError = "x"), beaconOpen = true))
    }

    @Test fun theAppIsReadyOnceSignedInAndPastItsStart() {
        assertTrue(appReadyForShare(AppState(authenticated = true, restoringSession = false, setupWizard = false)))
        assertFalse(appReadyForShare(AppState(authenticated = false, restoringSession = false)))
        assertFalse(appReadyForShare(AppState(authenticated = true, restoringSession = true)))
        assertFalse(appReadyForShare(AppState(authenticated = true, restoringSession = false, setupWizard = true)))
    }
}
