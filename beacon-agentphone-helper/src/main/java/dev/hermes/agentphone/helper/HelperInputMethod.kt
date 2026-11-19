package dev.hermes.agentphone.helper

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup

/**
 * The agent phone's keyboard, without keys (EMU-JOIN fu2, Root after L5-fu1).
 *
 * Every key reaches the phone through the emulator's own hardware keyboard: the owner typing through Beacon's row, the agent's
 * keys, and Ctrl+V for a paste through the helper's clip. By Android's rule (InputMethodService.onEvaluateInputViewShown) no
 * on-screen keyboard shows while a hardware keyboard is there, yet Gboard showed its full keyboard over the lower part of the
 * owner's picture until a key came, and again after any restart of Gboard (measured, 17 Nov). The host makes this the phone's
 * input method at every start (Android's own `ime enable` and `ime set`).
 *
 * Its window is shown while a text field is served, so Android still reports the keyboard as up and which kind of field it
 * serves: the live view's typing row opens by itself and knows a password field (contract revision 5, §11.2). But the window
 * is empty, takes no touches and leaves the app's size alone; fullscreen mode is never used, so landscape shows the app too.
 * Keys go past it to the app (InputMethodService.onKeyDown declines them).
 *
 * It never reads or writes a field: no InputConnection is used anywhere in the helper (pinned by SocketScanTest). If the helper
 * is missing or being updated, Android falls back to its own keyboard (measured).
 */
class HelperInputMethod : InputMethodService() {

    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onCreateInputView(): View =
        View(this).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1) }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val height = window.window?.decorView?.height ?: 0
        outInsets.contentTopInsets = height
        outInsets.visibleTopInsets = height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.setEmpty()
    }
}
