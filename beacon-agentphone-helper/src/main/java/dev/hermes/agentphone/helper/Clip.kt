package dev.hermes.agentphone.helper

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Text and Paste for the host (contract §5.1 clip_set/clip_clear): the text goes on the phone's own clipboard marked
 * sensitive, so keyboards neither show nor keep it, and is cleared after its time, on clip_clear, or when the link ends.
 * Android lets a background app write and clear the clipboard but not read it, so the helper can't see whether
 * someone copied over its clip in that window; it clears it whenever its own clip may still be there.
 */
class Clip(context: Context) {
    private val manager: ClipboardManager? = context.getSystemService(ClipboardManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var held = false
    private var generation = 0

    /** False when there is no clipboard to write to. */
    fun set(text: String, ttlMs: Long): Boolean {
        val m = manager ?: return false
        val clip = ClipData.newPlainText("", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            // No clipboard chip on the agent phone's screen (Root, EMU-JOIN fu2): SystemUI's ClipboardListener skips its overlay
            // for a clip carrying this extra on an emulator (AOSP shouldSuppressOverlay: Build.IS_EMULATOR), which the agent
            // phone always is. The sensitive mark alone still showed the chip, its preview as dots (L5-fu1 live).
            putBoolean(SUPPRESS_OVERLAY, true)
        }
        val mine: Int
        synchronized(lock) {
            try {
                m.setPrimaryClip(clip)
            } catch (e: RuntimeException) {
                return false
            }
            held = true
            mine = ++generation
        }
        main.postDelayed({ synchronized(lock) { if (generation == mine) clearLocked() } }, ttlMs)
        return true
    }

    fun clear() {
        synchronized(lock) { clearLocked() }
    }

    private fun clearLocked() {
        if (!held) return
        held = false
        generation++
        try { manager?.clearPrimaryClip() } catch (e: RuntimeException) { }
    }

    companion object {
        /** AOSP ClipboardListener.EXTRA_SUPPRESS_OVERLAY (SystemUI). */
        const val SUPPRESS_OVERLAY = "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY"
    }
}
