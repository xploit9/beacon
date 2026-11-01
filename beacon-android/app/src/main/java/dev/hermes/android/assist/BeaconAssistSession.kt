package dev.hermes.android.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.hermes.android.BeaconApplication
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.preferences
import dev.hermes.android.ui.nativeLanguageFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * One assistant overlay window. It is the window Android puts over the current app or the lock screen for the assist gesture; it
 * never opens the app unless the owner asks. The window owns a lifecycle, saved state and view models like an activity would, so
 * Compose and the app's own view model run inside it. Android keeps the session while it is hidden, so later gestures open warm.
 */
class BeaconAssistSession(context: Context) : VoiceInteractionSession(context), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    override val viewModelStore = ViewModelStore()
    internal val app = context.applicationContext as BeaconApplication
    internal val controller = AssistOverlayController(this)
    private var view: View? = null

    override fun onCreate() {
        super.onCreate()
        saved.performAttach()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        AssistPreferences.warm(app)
        current = WeakReference(this)
    }

    override fun onCreateContentView(): View {
        window.window?.let { w ->
            // Beacon draws its own dim, glass and dust edge to edge; the window itself is clear, still and unanimated, so the
            // first frame is the dust, not a system fade.
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setWindowAnimations(0)
            WindowCompat.setDecorFitsSystemWindows(w, false)
            @Suppress("DEPRECATION") w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            // The window paints its own bars, clear, so the dust and the deepened ground run behind the navigation bar instead of
            // under the system's grey band.
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            @Suppress("DEPRECATION") w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
            @Suppress("DEPRECATION") run { w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT }
            w.isNavigationBarContrastEnforced = false
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@BeaconAssistSession)
            setViewTreeSavedStateRegistryOwner(this@BeaconAssistSession)
            setViewTreeViewModelStoreOwner(this@BeaconAssistSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { AssistOverlayRoot(controller) }
            view = this
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        controller.shown(args)
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) { controller.screenshot(screenshot) }

    override fun onHide() {
        controller.hidden()
        if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) registry.currentState = Lifecycle.State.CREATED
        super.onHide()
    }

    override fun onBackPressed() { controller.dismiss() }

    override fun onDestroy() {
        controller.destroyed()
        registry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.refreshLocale()
    }

    /** Holds the screen on, over the lock screen too, while the overlay is working (see keepsScreenOn). */
    internal fun keepScreenOn(on: Boolean) {
        val w = window?.window ?: return
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        // A flag change lays the window out again; one that changes nothing would only cost a show its first frame.
        if (((w.attributes.flags and flag) != 0) == on) return
        if (on) w.addFlags(flag) else w.clearFlags(flag)
    }

    /** A short tick for summon, listen and send. */
    internal fun tick() { view?.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }

    internal companion object {
        /** The open session, for the device test that drives and measures it. */
        @Volatile var current: WeakReference<BeaconAssistSession>? = null
    }
}

/** The owner's theme, skin, assistant name and language, read when Beacon becomes the assistant so the overlay opens in them. */
internal object AssistPreferences {
    @Volatile var latest: AppPreferences? = null
    private var job: Job? = null

    @Synchronized fun warm(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext as BeaconApplication
        job = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            launch { if (nativeLanguageFor(java.util.Locale.getDefault()) != "en") runCatching { app.readTranslations() } }
            app.preferences.data.map(AppPreferences::read).collect { latest = it }
        }
    }
}
