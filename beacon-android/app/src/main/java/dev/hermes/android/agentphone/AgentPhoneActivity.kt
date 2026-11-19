package dev.hermes.android.agentphone

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BuildConfig
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.preferences
import dev.hermes.android.ui.AgentPhoneViewChecklist
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.nativeLanguageFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The setup view (EMU-DESIGN §4.4): the agent phone, live, for the owner to sign in to accounts and add apps. L4's phone page opens it by
 * class name with no extras (Root, 17 Nov) and reads the phone's state again when it returns.
 *
 * Private by construction: FLAG_SECURE is on before any content (no screenshot, screen recording, cast or recents thumbnail can hold the
 * owner's accounts or the agent phone's secure screens), frames and typed text live in memory only, and nothing is logged. The screen
 * stays on and the system bars hide while the view is open; leaving the app stops the frames, and the phone stays the owner's for
 * 5 minutes (the server's lease), so a trip to Messages for a code costs nothing.
 */
class AgentPhoneActivity : ComponentActivity() {
    private val model: AgentPhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setFlags(SECURE_WINDOW_FLAGS, SECURE_WINDOW_FLAGS)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            val app = application as BeaconApplication
            val preferences by remember(app) { app.preferences.data.map(AppPreferences::read) }.collectAsStateWithLifecycle(AppPreferences())
            val ui by model.session.ui.collectAsStateWithLifecycle()
            val frame by model.decoder.shown
            val configuration = LocalConfiguration.current
            val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            LaunchedEffect(ui.phase) { if (ui.phase == ViewPhase.Closed) finish() }
            LaunchedEffect(frame?.width, frame?.height, configuration.smallestScreenWidthDp) {
                requestedOrientation = when (preferredOrientation(frame?.width ?: 0, frame?.height ?: 0, configuration.smallestScreenWidthDp)) {
                    ViewOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    ViewOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    ViewOrientation.Any -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // Back is the agent phone's Back while the picture is live; from any other state it leaves the view.
            BackHandler { if (ui.phase == ViewPhase.Live) model.session.key("back") else model.session.close() }
            val actions = remember(model) { model.actions(this@AgentPhoneActivity) }
            CompositionLocalProvider(LocalAssistantName provides preferences.assistantName) {
                NativeLocalization(nativeLanguageFor(configuration.locales[0])) {
                    BeaconTheme(dark, preferences.skin) {
                        AgentPhoneViewScreen(ui, frame, preferences.assistantName, actions, checklist = { AgentPhoneViewChecklist(model::client) })
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        model.session.background(false)
    }

    override fun onStop() {
        // A turn or a fold is not leaving: the view keeps its frames through a configuration change.
        if (!isChangingConfigurations) model.session.background(true)
        super.onStop()
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        /** Set before super.onCreate, so the window is secure from its first frame. */
        const val SECURE_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }
}

/** Hand-backs that must finish after the view has closed (the view model's own scope ends with it). */
private val HandBacks = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Holds the view across turns and folds: the socket, the decoder and the session live as long as the view is open. */
internal class AgentPhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as BeaconApplication
    val decoder = FrameDecoder()
    val session = ViewSession(LazyViewTransport(::client), decoder, viewModelScope, clock = SystemClock::elapsedRealtime, online = ::online,
        lasting = HandBacks)
    private val clientLock = kotlinx.coroutines.sync.Mutex()
    private var signedIn: BeaconClient? = null

    /** The one sign-in the view and its checklist share, read once. */
    suspend fun client(): BeaconClient? = clientLock.withLock { signedIn ?: signedInClient()?.also { signedIn = it } }

    init { session.open() }

    /** Beacon's own sign-in to Hermes on this phone (the account and profile the app is on), or null when there is none. */
    private suspend fun signedInClient(): BeaconClient? = withContext(Dispatchers.IO) {
        val origin = runCatching { app.preferences.data.first()[stringPreferencesKey("origin")] }.getOrNull().orEmpty().ifBlank { BuildConfig.DEFAULT_ORIGIN }
        if (origin.isBlank()) return@withContext null
        val auth = runCatching { app.auth.read(origin) }.getOrNull() ?: return@withContext null
        if (auth.backend != "hermes-dashboard" || auth.owner.isBlank()) return@withContext null
        BeaconClient(auth, app.auth, persistContext = false)
    }

    private fun online(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    fun actions(context: Context) = ViewActions(
        touch = session::touch,
        key = session::key,
        text = session::text,
        paste = {
            // Read only when the owner taps Paste; the text goes straight to the socket and is not kept.
            val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
            session.paste(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString())
        },
        done = session::done,
        close = session::close,
        retry = session::retry,
        turnOn = session::turnOn,
    )

    override fun onCleared() {
        session.dispose()
        decoder.close()
    }
}

/** The app's transport once Beacon's sign-in is read; no sign-in answers like a refused ticket (401), which the view shows plainly. */
internal class LazyViewTransport(private val resolve: suspend () -> BeaconClient?) : ViewTransport {
    private var inner: BeaconViewTransport? = null
    private suspend fun get(): BeaconViewTransport = inner ?: (resolve()?.let(::BeaconViewTransport) ?: throw TicketRefused(401, "signed out")).also { inner = it }
    override suspend fun ticket(): ViewTicket = get().ticket()
    override fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection = requireNotNull(inner) { "no ticket yet" }.open(ticket, listener)
    override suspend fun start(): StartAnswer = get().start()
    override suspend fun phone(): Pair<String, String> = get().phone()
    override suspend fun phoneNow(): PhoneNow = get().phoneNow()
    override suspend fun handBack() = get().handBack()
}
