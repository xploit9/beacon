package dev.hermes.android.ui

import dev.hermes.android.data.NativeMediaBytes
import org.junit.Assert.*
import org.junit.Test

class MediaCacheTest {
    @Test fun lruStaysWithinItsByteBudgetAndEvictsTheLeastRecentlyUsed() {
        val cache = ByteBudgetLru<String, String>(100)
        cache.put("a", "A", 40); cache.put("b", "B", 40)
        assertNotNull(cache.get("a")) // a is now more recent than b
        cache.put("c", "C", 40)
        assertNull(cache.get("b"))
        assertEquals("A", cache.get("a")); assertEquals("C", cache.get("c"))
        assertTrue(cache.usedBytes() <= 100)
        repeat(50) { cache.put("k$it", "v", 30) }
        assertTrue(cache.usedBytes() <= 100)
        assertEquals(3, cache.size())
    }

    @Test fun aValueHeavierThanTheWholeBudgetIsNeverKeptAndDoesNotEvictOthers() {
        val cache = ByteBudgetLru<String, String>(100)
        cache.put("small", "s", 10)
        cache.put("huge", "h", 101)
        assertNull(cache.get("huge"))
        assertEquals("s", cache.get("small"))
        // Replacing a key with an oversized value drops the old entry rather than serving it stale.
        cache.put("small", "s2", 500)
        assertNull(cache.get("small"))
        assertEquals(0L, cache.usedBytes())
    }

    @Test fun replacingAKeyUpdatesItsWeightInsteadOfAddingToIt() {
        val cache = ByteBudgetLru<String, String>(100)
        cache.put("a", "1", 60); cache.put("a", "2", 30)
        assertEquals(30L, cache.usedBytes()); assertEquals("2", cache.get("a")); assertEquals(1, cache.size())
    }

    @Test fun anEntryExpiresAfterItsTimeToLiveNoMatterHowOftenItIsRead() {
        var now = 0L
        val cache = ByteBudgetLru<String, String>(100, ttlMillis = 1000, clock = { now })
        cache.put("a", "A", 10)
        now = 999; assertEquals("A", cache.get("a"))
        now = 1000; assertNull(cache.get("a"))
        assertEquals(0L, cache.usedBytes())
    }

    @Test fun aPictureIsKeyedByAccountProfileAndChatSoNoneAppearsInAnother() {
        val cache = ByteBudgetLru<MediaKey, NativeMediaBytes>(1 shl 20)
        val reference = "file:/api/media?path=/tmp/chart.png"
        val mine = MediaKey("scope-owner-a-profile-1-chat-1", reference)
        cache.put(mine, NativeMediaBytes(ByteArray(8)), 8)
        assertNotNull(cache.get(MediaKey("scope-owner-a-profile-1-chat-1", reference)))
        assertNull("another profile", cache.get(MediaKey("scope-owner-a-profile-2-chat-1", reference)))
        assertNull("another server", cache.get(MediaKey("scope-owner-b-profile-1-chat-1", reference)))
        assertNull("another chat", cache.get(MediaKey("scope-owner-a-profile-1-chat-2", reference)))
        assertNull("another size", cache.get(MediaKey("scope-owner-a-profile-1-chat-1", reference, width = 720)))
    }

    @Test fun budgetIsAShareOfTheHeapWithinSensibleBounds() {
        assertEquals(4L shl 20, mediaCacheBudget(8L shl 20))
        assertEquals(32L shl 20, mediaCacheBudget(256L shl 20))
        assertEquals(48L shl 20, mediaCacheBudget(4096L shl 20))
    }

    @Test fun aStreamingMessageReplacesItsEarlierPrefixInsteadOfPilingUp() {
        val cache = GrowingTextCache<String>(10_000, 8)
        cache.put("Hello", "h1"); cache.put("Other row", "o")
        cache.put("Hello there", "h2") // does not extend the last stored text ("Other row")
        assertEquals(3, cache.size())
        cache.put("Hello there, friend", "h3") // extends the last stored text
        assertNull(cache.get("Hello there")); assertEquals("h3", cache.get("Hello there, friend")); assertEquals("o", cache.get("Other row"))
        assertEquals(3, cache.size())
    }

    @Test fun parsedTextIsBoundedByItsLength() {
        val cache = GrowingTextCache<String>(1000, 8)
        cache.put("x".repeat(100), "a"); cache.put("y".repeat(100), "b")
        assertEquals(1, cache.size()) // 100 chars weigh 800; only one fits
        cache.put("z".repeat(200), "c") // 1600 > budget: not kept
        assertNull(cache.get("z".repeat(200)))
    }

    @Test fun decodeSizeFollowsTheDisplayedBoxAndNeverExceedsTheCap() {
        // No box: the old rule (every edge within 1440 px).
        assertEquals(1, imageSampleSize(1440, 900))
        assertEquals(2, imageSampleSize(1441, 900))
        assertEquals(4, imageSampleSize(4000, 3000))
        // A 4000x3000 photo shown in a 1000x1210 px box needs a 1000 px width: a quarter size is the smallest that still covers it.
        assertEquals(4, imageSampleSize(4000, 3000, 1000, 1210))
        // A 2000 px picture in a 400 px wide column needs 1/5 of its size; the largest power of two that still covers the box is 1/4 (500 px), where the cap alone would stop at 1/2.
        assertEquals(2, imageSampleSize(2000, 1500))
        assertEquals(4, imageSampleSize(2000, 1500, 400, 1210))
        // A box bigger than the picture never enlarges it, and the cap still wins over a large box.
        assertEquals(1, imageSampleSize(800, 600, 1080, 1210))
        assertEquals(4, imageSampleSize(5000, 5000, 5000, 5000))
        // The box's height limits tall pictures (1000x4000 shown 1210 px high needs about 1/3, i.e. 1/2), but the cap needs 1/4, so the cap wins.
        assertEquals(4, imageSampleSize(1000, 4000, 1080, 1210))
        // The decoded picture always covers the box (width/sample >= the fitted width) unless the cap forces it smaller.
        listOf(900 to 700, 2400 to 1600, 3000 to 4000, 6000 to 2000).forEach { (w, h) ->
            val sample = imageSampleSize(w, h, 1000, 1210)
            val fit = minOf(1f, 1000f / w, 1210f / h)
            val capped = imageSampleSize(w, h)
            assertTrue("$w x $h: sample $sample", sample == capped || w / sample >= w * fit - 1)
            assertTrue(sample >= capped)
        }
        // Unknown or empty picture / box.
        assertEquals(1, imageSampleSize(0, 0, 100, 100))
        assertEquals(imageSampleSize(4000, 3000), imageSampleSize(4000, 3000, 0, 0))
    }

    @Test fun placeholderReservesTheShapeOfWhatWillArrive() {
        assertEquals(MediaKind.Pdf, mediaKindOf("report.PDF?download=1"))
        assertEquals(MediaKind.Video, mediaKindOf("/tmp/clip.mp4"))
        assertEquals(MediaKind.Audio, mediaKindOf("note.m4a#t=3"))
        assertEquals(MediaKind.Image, mediaKindOf("chart.png"))
        assertEquals(MediaKind.Image, mediaKindOf("no-extension"))
        assertEquals(PlaceholderShape(4f / 3), placeholderShape(MediaKind.Image))
        assertEquals(PlaceholderShape(2f), placeholderShape(MediaKind.Image, 2f))
        assertEquals(VideoPreviewHeightDp, placeholderShape(MediaKind.Video, 2f).fixedHeightDp)
        assertEquals(AudioPreviewHeightDp, placeholderShape(MediaKind.Audio).fixedHeightDp)
        assertNull(placeholderShape(MediaKind.Pdf).fixedHeightDp)
        assertEquals(.75f, placeholderShape(MediaKind.Pdf).aspect)
    }

    @Test fun aMessagesOwnSizeSetsTheAspectWithinBounds() {
        assertEquals(2f, knownAspect(1000, 500))
        assertNull(knownAspect(0, 500)); assertNull(knownAspect(500, 0)); assertNull(knownAspect(-1, -1))
        assertEquals(2.5f, knownAspect(10_000, 100))
        assertEquals(.4f, knownAspect(100, 10_000))
    }

    @Test fun signingOutDropsEveryCachedPictureAndFile() {
        val app = listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.first { java.io.File(it, "src/main/assets/native-locales.json").isFile }
        val viewModel = java.io.File(app, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText(Charsets.UTF_8)
        val logout = viewModel.substringAfter("fun logout()").substringBefore("\n    fun ")
        assertTrue("logout must clear the media cache", "MediaCache.clear()" in logout)
    }
}
