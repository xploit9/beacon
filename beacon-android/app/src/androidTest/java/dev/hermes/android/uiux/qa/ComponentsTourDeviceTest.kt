package dev.hermes.android.uiux.qa

import android.graphics.Bitmap
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.assist.ApprovalShow
import dev.hermes.android.assist.AssistOverlayState
import dev.hermes.android.assist.GROUND_LEAD_DP
import dev.hermes.android.assist.LineRowBody
import dev.hermes.android.assist.LiveAbovePill
import dev.hermes.android.assist.OrbFace
import dev.hermes.android.assist.OverlayLine
import dev.hermes.android.assist.OverlayPhase
import dev.hermes.android.assist.OverlaySurface
import dev.hermes.android.assist.Pill
import dev.hermes.android.assist.PillInput
import dev.hermes.android.assist.groundAlpha
import dev.hermes.android.assist.liveCaptions
import dev.hermes.android.assist.liveStatusLabel
import dev.hermes.android.assist.orbFace
import dev.hermes.android.core.json
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.preferences
import dev.hermes.android.ui.AssistantSettings
import dev.hermes.android.ui.CallBackground
import dev.hermes.android.ui.CallUiActions
import dev.hermes.android.ui.EmailRuleSheet
import dev.hermes.android.ui.EmailRuleSuggestion
import dev.hermes.android.ui.Header
import dev.hermes.android.ui.LiveCallSurface
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLanguages
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.PhoneApprovalCard
import dev.hermes.android.ui.PhoneCallCountdownDialog
import dev.hermes.android.ui.callStatusText
import dev.hermes.android.ui.phoneLineText
import dev.hermes.android.ui.uiText
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolApproval
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.CallTranscript
import dev.hermes.android.voice.PhoneApprovalText
import dev.hermes.android.voice.activitySubject
import dev.hermes.android.voice.statusLabel
import dev.hermes.android.voice.statusTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Opt-in screenshot tour of the surfaces the main visual tour cannot reach: the live call window, the side-button overlay in its live
 * states, the approval and countdown cards, Hermes' phone request, and the email rule sheet and rules list. It needs no Hermes, network
 * or microphone: every surface is drawn by the app's own composables from synthetic state (the same fixtures the Compose tests use), full
 * screen, and saved with the screenshot call of [VisualTourDeviceTest] to files/components-tour/<tourCase>/ with a shots.json.
 * Arguments: componentsTour=1, tourCase=<folder name>, tourTheme=dark|light, tourLanguage=<code> (ar draws right to left), tourFontScale=<number>.
 */
class ComponentsTourDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val shots = JSONArray()
    private var index = 0
    private lateinit var dir: File

    // The one composition the whole tour runs in: a screen is swapped in by replacing [page], and [shown] gives it a fresh start.
    private var page by mutableStateOf<@Composable () -> Unit>({})
    private var shown by mutableIntStateOf(0)

    private fun capture(name: String, note: String) {
        compose.waitForIdle()
        // Lets sheets and dialogs finish sliding in, and the rules list its first load, before the picture.
        Thread.sleep(1_500)
        compose.waitForIdle()
        val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val file = "%02d-%s.png".format(index, name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'))
        File(dir, file).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        shots.put(JSONObject().put("file", file).put("screen", name).apply { if (note.isNotBlank()) put("note", note) })
        File(dir, "shots.json").writeText(shots.toString(2), Charsets.UTF_8)
    }

    /** Shows [content] full screen and saves its picture; [prepare] runs once it is on screen (a wait or a scroll) and [overlay] marks the overlay's glass. */
    private fun screen(name: String, overlay: Boolean = false, prepare: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.runOnIdle { page = content; shown++ }
        compose.waitForIdle()
        prepare()
        index++
        capture(name, if (overlay && !OverlayGlass.available) "The overlay's glass could not be loaded: its cards are drawn without the glass fill." else "")
    }

    @Test(timeout = 900_000) fun hardToReachSurfaces() {
        assumeTrue(args.getString("componentsTour") == "1")
        val theme = args.getString("tourTheme", "dark")
        require(theme in setOf("dark", "light")) { "tourTheme is dark or light" }
        val language = args.getString("tourLanguage", "en")
        require(language in NativeLanguages) { "tourLanguage is one of the app's languages" }
        val fontScale = args.getString("tourFontScale")?.let { requireNotNull(it.toFloatOrNull()?.takeIf { scale -> scale in .5f..3f }) { "tourFontScale is a number from 0.5 to 3" } }
        val dark = theme == "dark"
        val app = compose.activity.application as BeaconApplication
        dir = File(compose.activity.filesDir, "components-tour/${args.getString("tourCase", "default")}").apply { deleteRecursively(); mkdirs() }
        // The first frame is already in the chosen language and nothing moves while the pictures are taken.
        if (language != "en") app.readTranslations()
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            )
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalAssistantName provides "Beacon",
            ) {
                NativeLocalization(language) {
                    BeaconTheme(dark) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { key(shown) { page() } }
                    }
                }
            }
        }

        val actions = CallUiActions(mute = {}, camera = {}, flip = {}, allowBubble = {}, end = {}, route = {})
        screen("Live call quiet") { CallWindow(remember { quietCall() }, actions) }
        screen("Live call busy") { CallWindow(remember { busyCall() }, actions) }
        screen("Live call busy 360x640 phone") {
            // A small phone inside the screen: its own size with a status bar's height on top, the rest of the screen black.
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(360.dp, 640.dp).background(CallBackground).consumeWindowInsets(WindowInsets.safeDrawing).padding(top = 24.dp)) {
                    CallWindow(remember { busyCall() }, actions)
                }
            }
        }
        screen("Overlay live conversation", overlay = true) { OverlayStage(remember { busyCall() }, locked = false) }
        // Lane LK: the side button on a locked phone asks for the unlock first; this one line is all the overlay shows until then.
        screen("Overlay locked", overlay = true) { OverlayUnlockStage() }
        screen("Overlay approval with a 500 character note", overlay = true) { OverlayStage(remember { busyCall() }, locked = false, approval = eventApproval(), show = ApprovalShow.Card) }
        screen("Overlay call countdown", overlay = true) { OverlayStage(remember { busyCall() }, locked = false, approval = callApproval(), show = ApprovalShow.Card) }
        screen("App call countdown dialog") {
            Box(Modifier.fillMaxSize()) {
                CallWindow(remember { quietCall() }, actions)
                PhoneCallCountdownDialog(callApproval(), {}, now = { ASKED }, locked = { false })
            }
        }
        screen("Hermes phone request") {
            val headline = phoneLineText(PhoneLine("Write to {0}", listOf(CONTACT)))
            val details = listOf(phoneLineText(PhoneLine("Opens a draft. You press send.")))
            PhoneApprovalCard(headline, expired = false, locked = false, busy = false, onAllow = {}, onDeny = {}, onClose = {}, target = SHOWN_NUMBER, details = details, preview = MESSAGE)
        }
        screen("Email rule sheet loading") {
            EmailRuleSheet("important", null, busy = false, error = "", onSave = {}, onJustThisEmail = {}, onDismiss = {})
        }
        screen("Email rule sheet with a suggestion") {
            EmailRuleSheet("important", EmailRuleSuggestion(sender = CONTACT, topic = "Client moved the review", rule = "Sent by $CONTACT"),
                busy = false, error = "", onSave = {}, onJustThisEmail = {}, onDismiss = {})
        }

        // The rules list is the Assistant page of Settings, drawn by the app's own code on an in-process fixture client: no request leaves the phone.
        val prior = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        try {
            check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
            val vm = rulesViewModel(app, store, fixture)
            screen("Rules list with importance rules and earlier ratings", prepare = {
                compose.waitUntil(30_000) { vm.data.value.values.any { it.optJSONArray("rules")?.length() == 3 && it.has("learned_proposal") } }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("assistant-settings").fetchSemanticsNodes().isNotEmpty() }
                // The list opens on "Your rules": the importance rules and the Earlier ratings card sit under it.
                compose.onNodeWithTag("assistant-settings").performScrollToNode(hasTestTag("proactive-rule-rule-important"))
            }) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Header(uiText("Assistant"), back = {})
                    Box(Modifier.weight(1f)) { AssistantSettings(vm) }
                }
            }
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative() }
            runBlocking { app.auth.remove(fixture.scope.origin); app.preferences.updateData { prior } }
        }
        // Last, so the numbering above holds: a conversation started unlocked keeps running when the phone locks, with its words off the window.
        screen("Overlay locked during a running conversation", overlay = true) { OverlayStage(remember { busyCall() }, locked = true) }
    }

    /** The Assistant page's ViewModel signed in to [fixture] with a rules list that has two importance rules, a delivery rule and earlier ratings. */
    private fun rulesViewModel(app: BeaconApplication, store: ViewModelStore, fixture: NativeExperienceFixture): BeaconViewModel {
        val profile = fixture.scope.profile
        fixture.profileDisplayNames = mapOf(profile to "Personal profile")
        fixture.proactiveEnabled[profile] = true
        fixture.proactiveHealth[profile] = json("state" to "ok", "checked_at" to System.currentTimeMillis() / 1000 - 120, "message" to "")
        fixture.proactiveRequests[profile] = CopyOnWriteArrayList(listOf(
            json("id" to "rule-important", "text" to "Sent by $CONTACT", "action" to "important", "signal" to "email", "night" to false, "enabled" to true, "created_at" to 1),
            json("id" to "rule-quiet", "text" to "About weekly deals and offers", "action" to "not_important", "signal" to "email", "night" to false, "enabled" to true, "created_at" to 2),
            json("id" to "rule-call", "text" to "Call me when a client moves a review", "action" to "call", "signal" to null, "night" to false, "enabled" to true, "created_at" to 3),
        ))
        fixture.proactiveLearned[profile] = CopyOnWriteArrayList(listOf(
            "Beacon importance: email: Important: \"$CONTACT / Client moved the review\"",
            "Beacon importance: email: Not important: \"Offers Team / Weekly deals\"",
        ))
        fixture.proactiveProposal[profile] = CopyOnWriteArrayList(listOf(
            json("verdict" to "important", "source" to "email", "sender" to CONTACT, "topic" to "Client moved the review", "rule" to "Sent by $CONTACT"),
            json("verdict" to "not_important", "source" to "email", "sender" to "Offers Team", "topic" to "Weekly deals", "rule" to "Sent by Offers Team"),
        ))
        // A blank address keeps the ViewModel from signing in to anything; the fixture client is handed to it directly.
        runBlocking {
            app.preferences.edit { it[stringPreferencesKey("origin")] = "" }
            app.auth.persist(fixture.client.auth)
        }
        lateinit var vm: BeaconViewModel
        compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
        compose.runOnIdle {
            field(vm, "originGeneration", 100)
            field(vm, "client", fixture.client)
            field(vm, "sourceScope", fixture.scope.session("chat-a"))
            flow<AppState>(vm, "_ui").value = AppState(
                origin = fixture.scope.origin, owner = fixture.scope.owner, profile = profile, authenticated = true, connected = true, restoringSession = false,
                bootstrap = json("voice" to json("available" to false), "optional_features" to json("plugin" to "available")),
            )
        }
        return vm
    }

    private fun field(vm: BeaconViewModel, name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    @Composable private fun CallWindow(call: CallState, actions: CallUiActions) = LiveCallSurface(call, actions, false, Modifier.fillMaxSize(), onConversation = {}) { Box(it) }

    /**
     * The side-button overlay's live conversation as the overlay stacks it, bottom up: the text box, the captions and tool activity above it,
     * any approval above them. [locked] is the lock screen: the words stay off the window and only the state shows. The app beneath is a plain
     * tone with the overlay's own ground fading in under the stack; the dust is not drawn.
     */
    @Composable private fun OverlayStage(call: CallState, locked: Boolean, approval: CallToolApproval? = null, show: ApprovalShow = ApprovalShow.None) {
        var draft by remember { mutableStateOf(TextFieldValue()) }
        val clock = remember { mutableFloatStateOf(0f) }
        val field = remember { FocusRequester() }
        val tool = call.toolReceipts.lastOrNull { it.statusLabel() != null }
        val activity = tool?.statusTemplate()?.let { uiText(it).replace("{0}", tool.activitySubject()) }
        val status = uiText(liveStatusLabel(call, locked))
        OverlayFrame {
            LiveAbovePill(liveCaptions(call.transcripts, emptyList()), activity, approval, show, {}, now = { ASKED }, locked = { false }, phoneLocked = locked)
            Pill(PillInput(
                state = AssistOverlayState(surface = OverlaySurface.Live, phase = OverlayPhase.Open),
                draft = { draft }, onDraft = { draft = it }, screen = false, screenAttached = false, onAttach = {}, heardLevel = { 0f },
                face = orbFace(true, call), status = status, voice = { 0f }, onSend = {}, onDictate = {}, onOrb = {},
            ), field, motion = false, clock = clock)
        }
    }

    /**
     * The overlay on a locked phone with no conversation running: the one line asking for the unlock above a text box that has nothing to
     * type, send or dictate (its orb only asks again), and no chip, caption or tool line.
     */
    @Composable private fun OverlayUnlockStage() {
        var draft by remember { mutableStateOf(TextFieldValue()) }
        val clock = remember { mutableFloatStateOf(0f) }
        val field = remember { FocusRequester() }
        OverlayFrame {
            LineRowBody(OverlayLine.Unlock, null, onAction = {}, onClear = {})
            Pill(PillInput(
                state = AssistOverlayState(surface = OverlaySurface.Pill, phase = OverlayPhase.Open, line = OverlayLine.Unlock),
                draft = { draft }, onDraft = { draft = it }, screen = false, screenAttached = false, onAttach = {}, heardLevel = { 0f },
                face = OrbFace.Talk, status = "", voice = { 0f }, onSend = {}, onDictate = {}, onOrb = {}, locked = true,
            ), field, motion = false, clock = clock)
        }
    }

    /** The overlay's frame: a plain tone for the app beneath, the overlay's own ground fading in under the stack, and the stack at the bottom. */
    @Composable private fun OverlayFrame(stack: @Composable ColumnScope.() -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val dark = scheme.background.luminance() < .5f
        var bounds by remember { mutableStateOf(Rect.Zero) }
        val ground = scheme.background.copy(alpha = groundAlpha(dark))
        WithOverlayGlass {
            Box(Modifier.fillMaxSize().background(scheme.surfaceVariant).drawBehind {
                if (bounds.height <= 0f) return@drawBehind
                val edge = (bounds.top - GROUND_LEAD_DP.dp.toPx()).coerceAtLeast(0f)
                val ramp = 56.dp.toPx()
                drawRect(Brush.verticalGradient(listOf(ground.copy(alpha = 0f), ground), edge - ramp, edge), Offset(0f, edge - ramp), Size(size.width, ramp))
                drawRect(ground, Offset(0f, edge), Size(size.width, size.height - edge))
            }) {
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Vertical))
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .onGloballyPositioned { bounds = it.boundsInRoot() },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = stack,
                )
            }
        }
    }

    /** A call with everything on it: the tool window with its approval line, two caption lines, a context notice and the save state (as LiveCallFitUiTest's). */
    private fun busyCall() = CallState(
        active = true, state = "speaking", callId = "tour-busy", startedAt = System.currentTimeMillis() - 84_000,
        toolReceipts = listOf(requireNotNull(CallToolReceipt.from(JSONObject()
            .put("request_id", "request-1").put("tool_name", "mcp__gmail__search_messages")
            .put("status", "running").put("approved_by", "chat_yolo")))),
        transcripts = listOf(
            CallTranscript("tour-busy:user:1", "user", "Could you check my inbox and tell me what needs a reply today, and also move my three o'clock to the afternoon?"),
            CallTranscript("tour-busy:assistant:1", "assistant", "Yes. Two messages need a reply and the three o'clock can move to the afternoon. I will check the calendar first, then tell you what I found in each place."),
        ),
        transcriptStatus = "unavailable",
        contextNotice = "Earlier parts of this conversation were shortened to keep the call going.",
    )

    private fun quietCall() = CallState(active = true, state = "listening", callId = "tour-quiet", startedAt = System.currentTimeMillis() - 84_000)

    /** The calendar request the assistant waits on: a headline, two detail lines and a note of exactly 500 characters, shown whole. */
    @Composable private fun eventApproval(): CallToolApproval {
        val headline = phoneLineText(PhoneLine("Add '{0}' {1} to {2}", listOf("Dentist", "Tue 4 Nov 3:00 PM", "Personal")))
        val place = phoneLineText(PhoneLine("Place: {0}", listOf("Clinic on the second floor")))
        val alert = phoneLineText(PhoneLine("Alert {0} min before", listOf("30")))
        return CallToolApproval("tour-event", "e".repeat(32), "phone_calendar_create", "{}", allowCall = false,
            phone = PhoneApprovalText(headline, "", listOf(place, alert).joinToString("\n"), NOTE))
    }

    /** A call to a saved contact, asked just now: it counts down from 3. */
    @Composable private fun callApproval(): CallToolApproval =
        CallToolApproval("tour-call", "c".repeat(32), "phone_call", """{"number":"$PHONE_NUMBER"}""", allowCall = false,
            phone = PhoneApprovalText(phoneLineText(PhoneLine("Call {0}", listOf(CONTACT))), SHOWN_NUMBER, "", "", countdown = 3, askedAt = ASKED))

    private companion object {
        /** The phone's elapsed clock when the countdown cards were asked; the cards read this same time, so a countdown stands at "Calling in 3". */
        const val ASKED = 100_000L
        const val CONTACT = "Alex Example"
        // Built at run time: the public source carries no number-shaped text (a 555 number, as the release privacy scan expects).
        val PHONE_NUMBER = listOf("+1", "555", "0100").joinToString("")
        /** As the phone engine shows a number on a card: between left-to-right isolates, so it reads the same in a right-to-left line. */
        val SHOWN_NUMBER = "\u2066$PHONE_NUMBER\u2069"
        val NOTE = (1..40).joinToString(" ") { "Bring signed form $it to the front desk." }.take(500)
        const val MESSAGE = "Hi Alex, I am running ten minutes late for the review. Please start without me and I will join as soon as I have parked. " +
            "Could you also send me the agenda and the room number before then? Thank you."
    }
}

/**
 * The overlay's glass (the fill, rim light and glow its cards and text box are drawn with) is private to the overlay's file, so a Compose
 * test cannot ask for it. Its two private parts are read here by name, so the cards in the tour are painted as on the phone; if either
 * has moved, [available] says so and the tour notes it in shots.json and logs it instead of failing.
 */
private object OverlayGlass {
    private class Parts(val local: ProvidableCompositionLocal<Any?>, val make: (ColorScheme, Float) -> Any)

    private val parts = runCatching {
        val file = Class.forName("dev.hermes.android.assist.AssistOverlayKt")
        @Suppress("UNCHECKED_CAST")
        val local = file.getDeclaredField("LocalGlass").apply { isAccessible = true }.get(null) as ProvidableCompositionLocal<Any?>
        val constructor = Class.forName("dev.hermes.android.assist.GlassStyle").getDeclaredConstructor(ColorScheme::class.java, java.lang.Float.TYPE).apply { isAccessible = true }
        Parts(local) { scheme, radius -> constructor.newInstance(scheme, radius) }
    }.onFailure { Log.w("ComponentsTour", "The overlay's glass could not be loaded", it) }.getOrNull()

    val available get() = parts != null

    @Composable fun Provide(content: @Composable () -> Unit) {
        val glass = parts
        if (glass == null) { content(); return }
        val scheme = MaterialTheme.colorScheme
        val radius = with(LocalDensity.current) { 14.dp.toPx() }
        val style = remember(scheme, radius) { glass.make(scheme, radius) }
        CompositionLocalProvider(glass.local provides style, content = content)
    }
}

@Composable private fun WithOverlayGlass(content: @Composable () -> Unit) = OverlayGlass.Provide(content)
