package dev.hermes.android.agentphone

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.ClipboardManager
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import dev.hermes.android.BeaconActivity
import dev.hermes.android.voice.BeaconCallService
import dev.hermes.android.ui.assistantText
import dev.hermes.android.ui.uiText
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.Scope
import java.lang.ref.WeakReference
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.preferences
import dev.hermes.android.ui.AgentPhoneViewChecklist
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.nativeLanguageFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * stays on and the system bars hide while the view is open. Watching leaves the agent in control. After takeover the lease lasts
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
            var returnCall by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(ui.phase) {
                if (ui.phase == ViewPhase.Closed) {
                    returnCall?.takeIf { id -> app.calls.state.value.let { it.active && it.callId == id } }?.let { id ->
                        startActivity(Intent(this@AgentPhoneActivity, BeaconActivity::class.java)
                            .putExtra(BeaconCallService.EXTRA_CALL_ID, id)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                    finish()
                }
            }
            LaunchedEffect(frame?.width, frame?.height, configuration.smallestScreenWidthDp) {
                requestedOrientation = when (preferredOrientation(frame?.width ?: 0, frame?.height ?: 0, configuration.smallestScreenWidthDp)) {
                    ViewOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
                    ViewOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                    ViewOrientation.Any -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
            // Back is the agent phone's Back while the picture is live; from any other state it leaves the view. A watching Back during
            // a call asks the call screen to open its live surface as it resumes, instead of the page the emulator came from.
            BackHandler {
                if (!model.watching && ui.phase == ViewPhase.Live) model.session.key("back")
                else {
                    if (model.watching) app.calls.state.value.takeIf { it.active }?.let { app.calls.requestCallOnResume(it.callId) }
                    model.session.close()
                }
            }
            val actions = remember(model) { model.actions(this@AgentPhoneActivity) }
            CompositionLocalProvider(LocalAssistantName provides preferences.assistantName) {
                NativeLocalization(nativeLanguageFor(configuration.locales[0])) {
                    BeaconTheme(dark, preferences.skin) {
                        val call by app.calls.state.collectAsStateWithLifecycle()
                        Column(Modifier.fillMaxSize()) {
                            val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                                if (granted) model.call() else model.callNotice = "Allow the microphone to talk"
                            }
                            if (!call.active && model.watching) {
                                TextButton(onClick = {
                                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) model.call()
                                    else microphone.launch(Manifest.permission.RECORD_AUDIO)
                                }, enabled = ui.phase == ViewPhase.Live && !model.callStarting,
                                    modifier = Modifier.fillMaxWidth().statusBarsPadding().testTag("agent-phone-call")) { Text(assistantText("Call Beacon")) }
                            }
                            if (model.callNotice.isNotBlank()) Text(uiText(model.callNotice))
                            if (call.active) {
                                TextButton(onClick = {
                                    returnCall = call.callId
                                    model.session.done()
                                }, modifier = Modifier.fillMaxWidth().statusBarsPadding().testTag("agent-phone-show-call")) { Text(uiText("Return to call")) }
                            }
                            Box(Modifier.weight(1f)) {
                                AgentPhoneViewScreen(ui, frame, preferences.assistantName, actions,
                                    checklist = { AgentPhoneViewChecklist(model::client) },
                                    onTakeControl = if (model.watching) ({
                                        startActivity(Intent(this@AgentPhoneActivity, AgentPhoneActivity::class.java)
                                            .putExtra(EXTRA_CONTROL, true))
                                        finish()
                                    }) else null)
                                // This window covers the call screen, so a call's question shows here too (R3-F9).
                                if (call.active) CallApprovalOverPhone(call, model.signedInScope, app.calls)
                            }
                        }
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
        foregroundViewer = WeakReference(this)
    }

    override fun onStop() {
        if (foregroundViewer?.get() === this) foregroundViewer = null
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
        @Volatile private var foregroundViewer: WeakReference<AgentPhoneActivity>? = null
        internal fun showsAccount(account: Scope): Boolean = foregroundViewer?.get()?.let {
            !it.isFinishing && it.model.signedInScope == account.copy(session = "")
        } == true
        internal fun watching(scope: Scope?): Boolean {
            val viewer = foregroundViewer?.get() ?: return false
            return scope != null && !viewer.isFinishing && viewer.model.watching &&
                viewer.model.session.phaseNow == ViewPhase.Live && viewer.model.signedInScope == scope
        }

        internal const val EXTRA_CONTROL = "dev.hermes.android.extra.CONTROL_AGENT_PHONE"

        /** Set before super.onCreate, so the window is secure from its first frame. */
        const val SECURE_WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
    }
}

/** Hand-backs that must finish after the view has closed (the view model's own scope ends with it). */
private val HandBacks = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/** Holds the view across turns and folds: the socket, the decoder and the session live as long as the view is open. */
internal class AgentPhoneViewModel(application: Application, state: SavedStateHandle) : AndroidViewModel(application) {
    private val callId = state.get<String>(BeaconCallService.EXTRA_CALL_ID)
    val watching = state.get<Boolean>(AgentPhoneActivity.EXTRA_CONTROL) != true
    private val app = application as BeaconApplication
    val decoder = FrameDecoder()
    val session = ViewSession(LazyViewTransport(watching, ::client), decoder, viewModelScope, clock = SystemClock::elapsedRealtime, online = ::online,
        lasting = HandBacks)
    private val clientLock = kotlinx.coroutines.sync.Mutex()
    @Volatile private var signedIn: BeaconClient? = null
    val signedInScope: Scope? get() = signedIn?.scope?.copy(session = "")

    /** The one sign-in the view and its checklist share, read once. */
    suspend fun client(): BeaconClient? = clientLock.withLock { signedIn ?: signedInClient()?.also { signedIn = it } }

    var callStarting by mutableStateOf(false)
        private set
    var callNotice by mutableStateOf("")

    /** An explicit call from Settings keeps the secure viewer in front and uses the account owning its view. */
    fun call() {
        if (callStarting || app.calls.state.value.active) return
        callStarting = true
        callNotice = ""
        viewModelScope.launch {
            try { app.calls.start(requireNotNull(client())) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { callNotice = "Couldn't start the call" }
            finally { callStarting = false }
        }
    }

    init { session.open() }

    /** Beacon's own sign-in to Hermes on this phone (the account and profile the app is on), or null when there is none. */
    private suspend fun signedInClient(): BeaconClient? = withContext(Dispatchers.IO) {
        if (!callId.isNullOrBlank()) return@withContext runCatching { app.calls.service?.clientForHandoff(callId) }.getOrNull()
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
internal class LazyViewTransport(private val watching: Boolean = false, private val resolve: suspend () -> BeaconClient?) : ViewTransport {
    private var inner: BeaconViewTransport? = null
    private suspend fun get(): BeaconViewTransport = inner ?: (resolve()?.let { BeaconViewTransport(it, watching) } ?: throw TicketRefused(401, "signed out")).also { inner = it }
    override suspend fun ticket(): ViewTicket = get().ticket()
    override fun open(ticket: ViewTicket, listener: ViewListener): ViewConnection = requireNotNull(inner) { "no ticket yet" }.open(ticket, listener)
    override suspend fun start(): StartAnswer = get().start()
    override suspend fun phone(): Pair<String, String> = get().phone()
    override suspend fun phoneNow(): PhoneNow = get().phoneNow()
    override suspend fun handBack(epoch: Long) { if (!watching) get().handBack(epoch) }
}
