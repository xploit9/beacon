package dev.hermes.android.ui

import android.graphics.Bitmap
import dev.hermes.android.data.NativeMediaBytes
import org.commonmark.node.Node
import java.lang.ref.WeakReference

/**
 * A least-recently-used map bounded by bytes, not by count. A value heavier than the whole budget is never kept, and with [ttlMillis]
 * an entry ends its life that long after it was stored however often it is read (a file can change on the server).
 */
internal class ByteBudgetLru<K : Any, V : Any>(private val maxBytes: Long, private val ttlMillis: Long = 0L, private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Entry<V>(val value: V, val bytes: Long, val storedAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private var total = 0L

    @Synchronized fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (ttlMillis > 0 && clock() - entry.storedAt >= ttlMillis) { remove(key); return null }
        return entry.value
    }
    @Synchronized fun put(key: K, value: V, bytes: Long) {
        remove(key)
        if (bytes < 0 || bytes > maxBytes) return
        entries[key] = Entry(value, bytes, clock()); total += bytes
        val oldest = entries.entries.iterator()
        while (total > maxBytes && oldest.hasNext()) { total -= oldest.next().value.bytes; oldest.remove() }
    }
    @Synchronized fun remove(key: K) { entries.remove(key)?.let { total -= it.bytes } }
    @Synchronized fun clear() { entries.clear(); total = 0 }
    @Synchronized fun size(): Int = entries.size
    @Synchronized fun usedBytes(): Long = total
    @Synchronized fun keys(): List<K> = entries.keys.toList()
}

/** Parsed text keyed by the text. A message that streams in grows by prefixes, so a new text that extends the last one stored replaces it instead of piling up. */
internal class GrowingTextCache<V : Any>(maxBytes: Long, private val bytesPerChar: Long) {
    private val lru = ByteBudgetLru<String, V>(maxBytes)
    private var last: String? = null
    @Synchronized fun get(text: String): V? = lru.get(text)
    @Synchronized fun put(text: String, value: V) {
        last?.let { if (text.length > it.length && text.startsWith(it)) lru.remove(it) }
        lru.put(text, value, text.length * bytesPerChar); last = text
    }
    @Synchronized fun clear() { lru.clear(); last = null }
    fun size(): Int = lru.size()
}

/** What a cached picture belongs to: the account, profile and chat it was shown for (Scope.key), the reference, and the width it was decoded for (0 = the full cap). */
internal data class MediaKey(val scopeKey: String, val reference: Any, val width: Int = 0)

/** A share of the app's heap, never so small it is useless or so large it competes with the screens. */
internal fun mediaCacheBudget(maxHeapBytes: Long): Long = (maxHeapBytes / 8).coerceIn(4L shl 20, 48L shl 20)
/** A file fetched by path can change on the server; a cached copy is trusted this long. */
internal const val MediaPayloadTtlMillis = 5 * 60_000L

/** A decoded picture and the bytes it came from; it stays valid only while those same bytes are the ones on screen. */
private class DecodedPicture(source: ByteArray, val bitmap: Bitmap) {
    private val from = WeakReference(source)
    fun isFrom(bytes: ByteArray) = from.get() === bytes
}

/** In-memory media shared by every row that shows a picture, so scrolling a row back in does not fetch or decode it again. Keys carry the scope: one profile's or server's picture never appears in another. */
internal object MediaCache {
    private val budget = mediaCacheBudget(Runtime.getRuntime().maxMemory())
    /** Fetched files (inline images, attachments, outside media). */
    val payloads = ByteBudgetLru<MediaKey, NativeMediaBytes>(budget / 2, MediaPayloadTtlMillis)
    private val pictures = ByteBudgetLru<MediaKey, DecodedPicture>(budget)
    /** Parsed Markdown, so a row that re-enters draws at its full height on the first frame. */
    val markdown = GrowingTextCache<Node>(4L shl 20, 8)
    fun picture(key: MediaKey, from: ByteArray): Bitmap? = pictures.get(key)?.takeIf { it.isFrom(from) }?.bitmap
    fun putPicture(key: MediaKey, from: ByteArray, bitmap: Bitmap) { pictures.put(key, DecodedPicture(from, bitmap), bitmap.allocationByteCount.toLong()) }
    fun clear() { payloads.clear(); pictures.clear(); markdown.clear() }
}

/** The longest edge a picture is ever decoded to. */
internal const val MaxImageEdge = 1440
/** The tallest an inline picture is drawn (ZoomImage). */
internal const val InlineImageMaxHeightDp = 440

/**
 * The BitmapFactory inSampleSize (a power of two) for a [width]x[height] picture. [MaxImageEdge] bounds the result from above; given the box the picture
 * is drawn in ([targetWidth]x[targetHeight], fit inside, never enlarged), it is decoded no larger than that box needs. Without a box (0) only the cap applies.
 */
internal fun imageSampleSize(width: Int, height: Int, targetWidth: Int = 0, targetHeight: Int = 0, cap: Int = MaxImageEdge): Int {
    if (width <= 0 || height <= 0) return 1
    val capped = generateSequence(1) { it * 2 }.first { width / it <= cap && height / it <= cap }
    if (targetWidth <= 0 || targetHeight <= 0) return capped
    val fit = minOf(1f, targetWidth.toFloat() / width, targetHeight.toFloat() / height)
    return maxOf(capped, (1f / fit).toInt().coerceAtLeast(1).takeHighestOneBit())
}

/** What kind of preview a reference will become, guessed from its file name before the file arrives so the placeholder matches. */
internal enum class MediaKind { Image, Pdf, Audio, Video }
internal fun mediaKindOf(filename: String): MediaKind = when (filename.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)) {
    "pdf" -> MediaKind.Pdf
    "mp4", "webm", "mkv", "mov" -> MediaKind.Video
    "mp3", "wav", "m4a", "ogg", "flac", "aac" -> MediaKind.Audio
    else -> MediaKind.Image
}

/** The size a preview reserves while it loads: [fixedHeightDp] when the finished preview has one, else [aspect] (width / height) across the full width. */
internal data class PlaceholderShape(val aspect: Float, val fixedHeightDp: Int? = null)
internal const val VideoPreviewHeightDp = 260
internal const val AudioPreviewHeightDp = 48
internal const val DefaultImageAspect = 4f / 3f
internal const val DefaultPdfAspect = .75f
/** A message's own width and height, when it says them. Extremes are bounded so a strip or a thin column cannot reserve a screen. */
internal fun knownAspect(width: Int, height: Int): Float? = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(.4f, 2.5f) else null
internal fun placeholderShape(kind: MediaKind, known: Float? = null): PlaceholderShape = when (kind) {
    MediaKind.Video -> PlaceholderShape(16f / 9, VideoPreviewHeightDp)
    MediaKind.Audio -> PlaceholderShape(8f, AudioPreviewHeightDp)
    MediaKind.Pdf -> PlaceholderShape(known ?: DefaultPdfAspect)
    MediaKind.Image -> PlaceholderShape(known ?: DefaultImageAspect)
}
