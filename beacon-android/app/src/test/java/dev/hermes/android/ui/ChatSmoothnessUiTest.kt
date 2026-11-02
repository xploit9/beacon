package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Perf lane A1, the parts of chat smoothness that live in the UI layer. */
class ChatSmoothnessUiTest {
    /** uiText before the English fast path: the same lookups, always normalizing. */
    private fun reference(dictionary: JSONObject, value: String): String =
        dictionary.optString(normalized(value)).ifBlank { dictionary.optString(normalized(nativeAliases[value] ?: value)).ifBlank { value } }

    private val italian = JSONObject("""{"history":"Cronologia","sessions":"Sessioni","loading...":"Caricamento…","search in conversation":"Cerca nella conversazione","new chat":"Nuova chat"}""")

    @Test fun englishReturnsTheSameStringWithoutLookingAnythingUp() {
        for (value in listOf("History", "Conversations", "Loading…", "  Search   this conversation ", "New conversation", "", "Beacon message")) {
            // The shared empty dictionary is what English and a still-loading catalog use.
            assertSame(value, translateUi(EmptyUiStrings, value))
            assertEquals(reference(EmptyUiStrings, value), translateUi(EmptyUiStrings, value))
        }
    }

    @Test fun anEmptyCatalogThatIsNotTheSharedOneStillGivesTheValue() {
        for (value in listOf("History", "Conversations", "Loading…")) assertEquals(value, translateUi(JSONObject(), value))
    }

    @Test fun otherLanguagesLookUpExactlyAsBefore() {
        val values = listOf("History", "Conversations", "Loading…", "Search this conversation", "New conversation", "Search   this conversation", "No such text", "")
        for (value in values) assertEquals("for \"$value\"", reference(italian, value), translateUi(italian, value))
        // Direct hit, hit through the app's alias, ellipsis normalized, and a miss that returns the value.
        assertEquals("Cronologia", translateUi(italian, "History"))
        assertEquals("Sessioni", translateUi(italian, "Conversations"))
        assertEquals("Caricamento…", translateUi(italian, "Loading…"))
        assertEquals("Cerca nella conversazione", translateUi(italian, "Search this conversation"))
        assertEquals("No such text", translateUi(italian, "No such text"))
    }

    @Test fun theColdChatSkeletonIsMessageShapedOnBothSides() {
        assertTrue(ChatSkeletonBubbles.size >= 4)
        assertTrue("own and received bubbles both show", ChatSkeletonBubbles.any { it.first } && ChatSkeletonBubbles.any { !it.first })
        assertTrue(ChatSkeletonBubbles.all { it.second in .2f..1f })
        assertFalse("the first bubble is the assistant's", ChatSkeletonBubbles.first().first)
    }

    // ---- source structure the instrumented UI cannot be run for here

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText(Charsets.UTF_8)

    @Test fun theAnimationSettingIsWatchedOnceAtTheThemeRoot() {
        val theme = source("ui/NativeTheme.kt")
        assertEquals("one content observer for the whole app window", 1, Regex("\\.registerContentObserver\\(").findAll(theme).count())
        assertTrue("NativeSkinTheme installs it", "LocalNativeMotion provides observeNativeMotion()" in theme && "NativeMotionProvider(content)" in theme)
        assertTrue("screens read the provided value", "fun rememberNativeMotionEnabled(): Boolean = LocalNativeMotion.current ?: observeNativeMotion()" in theme)
    }

    @Test fun theSphereAndSignalAnimationsAreReadWhereTheyDraw() {
        val chat = source("ui/ChatScreen.kt")
        assertFalse("the conversation root no longer unwraps the animated sphere values", "val sphereIntensity by" in chat || "val sphereToolCallBurst by" in chat)
        assertTrue("ChatSphere takes the States and reads them itself", "intensity: State<Float>, toolCallBurst: State<Float>" in chat && "intensity.value, toolCallBurst.value" in chat)
        val signal = source("ui/Components.kt")
        assertFalse("BeaconSignal keeps the transition value out of composition", "val value by transition.animateFloat" in signal)
        assertTrue("and reads it in the canvas", "val pulse = phase.value" in signal)
    }

    @Test fun theDrawerWorksOutItsRowsOncePerInput() {
        val chat = source("ui/ChatScreen.kt")
        for (name in listOf("loaded", "matches", "rows")) assertTrue("$name is remembered", Regex("val $name = remember\\(").containsMatchIn(chat))
        assertFalse("the lazy list scope no longer filters sessions itself", "val loaded = ui.sessions.filter" in chat)
    }
}
