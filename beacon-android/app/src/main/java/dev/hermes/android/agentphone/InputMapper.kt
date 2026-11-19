package dev.hermes.android.agentphone

import java.text.BreakIterator
import kotlin.math.floor
import kotlin.math.min

/*
 * From the owner's fingers and keyboard to the agent phone (EMU-DESIGN §4.2): touches become fractions 0-1 of the frame as it is shown,
 * with pointer ids 0-9 and phases down/move/up; text typed in Beacon's own row becomes `text` messages and Backspace keys.
 *
 * Rotation: the emulator turns its picture with the device ("The image will be rotated based upon the coarse grained orientation of the
 * device", emulator_controller.proto), so a landscape phone arrives as a landscape frame. The app shows each frame as it comes and sends
 * fractions of what it shows; the server turns them into the phone's own pixels with the rotation it read from the same image
 * (agent_phone_stream.to_device). The app's part of rotation is the letterbox and which way the owner's phone should face.
 */

/** Where the frame is drawn in the view, in whole view pixels so the picture is never resampled off the pixel grid. */
internal data class Fit(val left: Int, val top: Int, val width: Int, val height: Int) {
    val empty: Boolean get() = width <= 0 || height <= 0
    fun contains(x: Float, y: Float): Boolean = !empty && x >= left && x < left + width && y >= top && y < top + height
    /** The point as fractions of the frame as shown, clamped to its edges (a finger that slides off the picture stays on its edge). */
    fun fraction(x: Float, y: Float): Pair<Double, Double> =
        if (empty) 0.0 to 0.0 else ((x - left) / width.toDouble()).coerceIn(0.0, 1.0) to ((y - top) / height.toDouble()).coerceIn(0.0, 1.0)
}

/** The largest whole-pixel rectangle with the frame's shape that fits the view, centred: the letterbox. */
internal fun fitFrame(viewWidth: Int, viewHeight: Int, frameWidth: Int, frameHeight: Int): Fit {
    if (viewWidth <= 0 || viewHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return Fit(0, 0, 0, 0)
    val scale = min(viewWidth / frameWidth.toDouble(), viewHeight / frameHeight.toDouble())
    val width = floor(frameWidth * scale + 1e-6).toInt().coerceIn(1, viewWidth)
    val height = floor(frameHeight * scale + 1e-6).toInt().coerceIn(1, viewHeight)
    return Fit((viewWidth - width) / 2, (viewHeight - height) / 2, width, height)
}

internal enum class ViewOrientation { Any, Portrait, Landscape }

/**
 * Which way the owner's phone should face for the agent phone's picture: the same way as the picture on a phone-sized screen, so a
 * landscape app fills the screen instead of a strip; free on a large screen (an unfolded phone, a tablet), where both fit.
 */
internal fun preferredOrientation(frameWidth: Int, frameHeight: Int, smallestWidthDp: Int): ViewOrientation = when {
    frameWidth <= 0 || frameHeight <= 0 || smallestWidthDp >= 600 -> ViewOrientation.Any
    frameWidth > frameHeight -> ViewOrientation.Landscape
    else -> ViewOrientation.Portrait
}

/**
 * The owner's fingers as the server's pointer ids 0-9. A finger that lands outside the picture is not sent at all; one that slides off
 * stays on the picture's edge; an eleventh finger is ignored; a cancelled gesture lifts every finger where it last was.
 */
internal class PointerSlots {
    private val slots = arrayOfNulls<Long>(10)
    private val last = HashMap<Long, Pair<Double, Double>>()
    val active: Boolean get() = slots.any { it != null }

    private fun slotOf(pointer: Long) = slots.indexOf(pointer).takeIf { it >= 0 }

    fun down(pointer: Long, at: Pair<Double, Double>): TouchPoint? {
        if (slotOf(pointer) != null) return null
        val slot = slots.indexOfFirst { it == null }.takeIf { it >= 0 } ?: return null
        slots[slot] = pointer
        last[pointer] = at
        return TouchPoint(slot, at.first, at.second, "down")
    }

    fun move(pointer: Long, at: Pair<Double, Double>): TouchPoint? {
        val slot = slotOf(pointer) ?: return null
        if (last[pointer] == at) return null
        last[pointer] = at
        return TouchPoint(slot, at.first, at.second, "move")
    }

    fun up(pointer: Long, at: Pair<Double, Double>? = null): TouchPoint? {
        val slot = slotOf(pointer) ?: return null
        val where = at ?: last[pointer] ?: (0.0 to 0.0)
        slots[slot] = null
        last.remove(pointer)
        return TouchPoint(slot, where.first, where.second, "up")
    }

    /** Lifts every finger still down, at its last position. */
    fun cancelAll(): List<TouchPoint> = slots.filterNotNull().mapNotNull { up(it) }
}

/** What the row's change needs on the phone, in order. */
internal sealed interface TextStep {
    data class Backspace(val count: Int) : TextStep
    data class Type(val text: String) : TextStep
}

/**
 * Keeps the agent phone in line with Beacon's text row. [sent] is what the phone has from the row; each change of the row is the
 * difference: Backspaces for what was removed from the end, then the new end. Printable ASCII goes at once (typed as keys, so it shows as
 * the owner types); any other text (accents, Arabic, emoji, a CJK word) is held until [flush], because the server sends each such message
 * whole through the helper on the agent phone (clipboard, paste, clear: about half a second each), and a word at a time beats a letter at
 * a time. Everything is counted in whole characters (graphemes), never in UTF-16 units, so an emoji is one Backspace.
 */
internal class TextMirror {
    var sent: String = ""
        private set

    /** What the row shows that the phone does not have yet (held text). */
    fun held(field: String): String = if (field.startsWith(sent)) field.substring(sent.length) else ""

    /**
     * Brings the phone in line with [field] through [send]; with [flush] the held text goes too. [send] returns false when a step cannot
     * go now (other alphabets while the helper is not ready): what it refused stays held. Returns false when anything is still held.
     */
    fun sync(field: String, flush: Boolean, send: (TextStep) -> Boolean): Boolean {
        val prefix = commonBoundary(sent, field)
        val removed = graphemes(sent.substring(prefix))
        if (removed > 0) {
            if (!send(TextStep.Backspace(removed))) return false
            sent = sent.substring(0, prefix)
        }
        val added = field.substring(sent.length.coerceAtMost(field.length))
        if (added.isEmpty()) return true
        if (!flush && !isPrintableAscii(added)) return false
        if (!send(TextStep.Type(added))) return false
        sent = field
        return true
    }

    /** The row starts over (it was cleared, the owner tapped elsewhere on the phone, or the view reconnected). */
    fun reset() { sent = "" }

    companion object {
        fun graphemes(text: String): Int {
            if (text.isEmpty()) return 0
            val it = BreakIterator.getCharacterInstance().apply { setText(text) }
            var count = 0
            while (it.next() != BreakIterator.DONE) count++
            return count
        }

        /** The longest common start of [a] and [b] that ends on a character boundary in both. */
        fun commonBoundary(a: String, b: String): Int {
            var i = 0
            val n = min(a.length, b.length)
            while (i < n && a[i] == b[i]) i++
            if (i == 0) return 0
            if (i == a.length && i == b.length) return i
            val inA = BreakIterator.getCharacterInstance().apply { setText(a) }
            val inB = BreakIterator.getCharacterInstance().apply { setText(b) }
            var at = i
            while (at > 0 && !((at == a.length || inA.isBoundary(at)) && (at == b.length || inB.isBoundary(at)))) at--
            return at
        }

        /** The last character of [text] removed (the row's own Backspace). */
        fun dropLast(text: String): String {
            if (text.isEmpty()) return text
            val it = BreakIterator.getCharacterInstance().apply { setText(text) }
            return text.substring(0, it.preceding(text.length).coerceAtLeast(0))
        }
    }
}
