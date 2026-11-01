package dev.hermes.android.uiux.assist

import dev.hermes.android.assist.ASSIST_WINDOW_MILLIS
import dev.hermes.android.assist.AssistGestures
import dev.hermes.android.assist.AssistReadiness
import dev.hermes.android.assist.AssistStep
import dev.hermes.android.assist.assistFresh
import dev.hermes.android.assist.assistStep
import dev.hermes.android.assist.assistantSettingsActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Lane assist (uiux-20261031): where the phone's assist gesture goes, that it is served once, and what the manifest exposes. */
class AssistLaunchTest {
    private val ready = AssistReadiness(restoring = false, loading = false, authenticated = true, setupWizard = false, callActive = false, pluginAbsent = false, assistantRole = true)
    private fun step(readiness: AssistReadiness = ready, ageMillis: Long = 1_000L) = assistStep(readiness, issuedAt = 100_000L, now = 100_000L + ageMillis)

    @After fun cleanUp() = AssistGestures.clear()

    // --- the routing decision ---

    @Test fun aReadySignedInAppStartsTheCall() = assertEquals(AssistStep.Call, step())

    @Test fun aStartingAppWaitsUntilItKnowsWhoIsSignedIn() {
        assertEquals(AssistStep.Wait, step(ready.copy(restoring = true, authenticated = false)))
        assertEquals(AssistStep.Wait, step(ready.copy(loading = true)))
    }

    @Test fun signedOutStopsAtTheSignInScreenInsteadOfCalling() {
        assertEquals(AssistStep.SignIn, step(ready.copy(authenticated = false)))
        assertEquals(AssistStep.SignIn, step(ready.copy(authenticated = false, setupWizard = true)))
        // Re-running the setup wizard on a signed-in phone is also not a place to start a call from.
        assertEquals(AssistStep.SignIn, step(ready.copy(setupWizard = true)))
    }

    @Test fun aRunningCallIsBroughtToTheFrontNotStartedAgain() {
        assertEquals(AssistStep.ShowCall, step(ready.copy(callActive = true)))
        // Even with the plugin reported missing, the call that is running comes first.
        assertEquals(AssistStep.ShowCall, step(ready.copy(callActive = true, pluginAbsent = true)))
    }

    @Test fun aMissingPluginOpensTheAssistantChatWhichSaysSo() = assertEquals(AssistStep.OpenChat, step(ready.copy(pluginAbsent = true)))

    @Test fun aMissingVoiceKeyOrMicrophoneStillHandsOverToTheChatCallThatAsksForIt() {
        // The route depends on exactly these inputs: the key and the microphone are asked for by the chat's own call entry
        // (voice key page, permission request), so adding either here would turn those prompts into a silent drop.
        val inputs = AssistReadiness::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertEquals(setOf("restoring", "loading", "authenticated", "setupWizard", "callActive", "pluginAbsent", "assistantRole"), inputs)
        assertEquals(AssistStep.Call, step())
    }

    @Test fun anOldGestureIsNeverServedEvenWhenTheAppIsReady() {
        assertEquals(AssistStep.Call, step(ageMillis = ASSIST_WINDOW_MILLIS))
        assertEquals(AssistStep.Expired, step(ageMillis = ASSIST_WINDOW_MILLIS + 1))
        assertEquals(AssistStep.Expired, step(ready.copy(restoring = true), ageMillis = ASSIST_WINDOW_MILLIS + 1))
        assertEquals(AssistStep.Expired, step(ageMillis = -1))
    }

    @Test fun freshnessCoversTheWholeWindowAndNothingAfterIt() {
        assertTrue(assistFresh(500L, 500L))
        assertTrue(assistFresh(500L, 500L + ASSIST_WINDOW_MILLIS))
        assertTrue(!assistFresh(500L, 501L + ASSIST_WINDOW_MILLIS))
        assertTrue(!assistFresh(500L, 499L))
    }

    // --- one call per gesture ---

    @Test fun aGestureTokenIsTakenOnceSoARecreatedActivityCannotCallAgain() {
        val token = AssistGestures.issue(now = 5_000L)
        assertEquals(5_000L, AssistGestures.take(token, now = 5_100L))
        // The same intent is delivered again after a rotation or a process restore: nothing is left to serve.
        assertNull(AssistGestures.take(token, now = 5_200L))
    }

    @Test fun twoGesturesGetTwoTokensAndEachIsServedOnce() {
        val first = AssistGestures.issue(now = 1_000L)
        val second = AssistGestures.issue(now = 2_000L)
        assertNotEquals(first, second)
        assertEquals(2_000L, AssistGestures.take(second, now = 2_050L))
        assertEquals(1_000L, AssistGestures.take(first, now = 2_050L))
    }

    @Test fun anUnknownMissingOrOutdatedTokenIsIgnored() {
        assertNull(AssistGestures.take(null))
        assertNull(AssistGestures.take("not-issued-here"))
        val old = AssistGestures.issue(now = 1_000L)
        assertNull(AssistGestures.take(old, now = 1_001L + ASSIST_WINDOW_MILLIS))
        // An outdated token is used up too, so it cannot be tried again later.
        assertNull(AssistGestures.take(old, now = 1_002L))
    }

    @Test fun onlyTheLatestTokensAreKept() {
        val tokens = (1..12).map { AssistGestures.issue(now = it * 10L) }
        assertNull(AssistGestures.take(tokens.first(), now = 130L))
        assertNotNull(AssistGestures.take(tokens.last(), now = 130L))
    }

    // --- the Settings row and the manifest ---

    @Test fun theRowOpensAndroidsAssistantScreenThenTheDefaultAppsList() {
        assertEquals(listOf("android.settings.VOICE_INPUT_SETTINGS", "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"), assistantSettingsActions)
    }

    private fun manifest(): Element {
        val file = listOf(".", "app", "beacon-android/app").map { File(it, "src/main/AndroidManifest.xml") }.first { it.isFile }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private fun Element.android(name: String) = getAttributeNS(androidNs, name)
    private fun Element.children(tag: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }
    private fun activities() = manifest().children("application").single().let { it.children("activity") + it.children("activity-alias") }
    private fun Element.actions() = children("intent-filter").flatMap { f -> f.children("action").map { it.android("name") } }

    @Test fun onlyTheEntryActivityAnswersTheAssistGestureAndItIsLight() {
        val assisting = activities().filter { "android.intent.action.ASSIST" in it.actions() }
        assertEquals(listOf(".assist.AssistEntryActivity"), assisting.map { it.android("name") })
        val entry = assisting.single()
        assertEquals("true", entry.android("exported"))
        assertEquals("true", entry.android("excludeFromRecents"))
        assertEquals("true", entry.android("noHistory"))
        // Its own task, so the app's task is not hidden from Recents; and nothing is drawn over the lock screen.
        assertEquals("", entry.android("taskAffinity"))
        assertTrue(entry.hasAttributeNS(androidNs, "taskAffinity"))
        assertEquals("", entry.android("showWhenLocked"))
        assertEquals("", entry.android("turnScreenOn"))
        val filter = entry.children("intent-filter").single()
        assertEquals(listOf("android.intent.category.DEFAULT"), filter.children("category").map { it.android("name") })
    }

    @Test fun nothingIsClaimedForTheHeadsetVoiceButtonAndOnlyAndroidCanBindTheAssistant() {
        // VOICE_COMMAND would make Android ask which app to use for every headset button hold; the assistant is enough to be listed.
        assertTrue(activities().none { "android.intent.action.VOICE_COMMAND" in it.actions() })
        val services = manifest().children("application").single().children("service")
        // Beacon's own overlay (lane assist-overlay): the voice interaction service and its session service only Android may bind.
        val assistant = services.filter { "android.service.voice.VoiceInteractionService" in it.actions() }
        assertEquals(listOf(".assist.BeaconVoiceInteractionService"), assistant.map { it.android("name") })
        val guarded = services.filter { it.android("permission") == "android.permission.BIND_VOICE_INTERACTION" }.map { it.android("name") }
        assertEquals(setOf(".assist.BeaconVoiceInteractionService", ".assist.BeaconAssistSessionService"), guarded.toSet())
        // The recognizer Android requires beside it answers only the speech recognition interface: other apps' speech input must
        // still reach it, and Android's RecognitionService refuses callers without the microphone permission.
        val recognizer = services.single { it.android("name") == ".assist.BeaconRecognitionService" }
        assertEquals(listOf("android.speech.RecognitionService"), recognizer.actions())
        assertEquals("true", recognizer.android("exported"))
    }

    @Test fun noNewPermissionIsRequestedForTheAssistantRole() {
        val permissions = manifest().children("uses-permission").map { it.android("name") }
        assertTrue(permissions.none { it.contains("VOICE_INTERACTION") || it.contains("ROLE") })
    }

    @Test fun aLaunchWhileBeaconIsNotThePhonesAssistantOpensTheChatInsteadOfCalling() {
        // Another app can open the assist entry on purpose; without the owner's choice of Beacon it never starts the microphone.
        assertEquals(AssistStep.OpenChat, assistStep(ready.copy(assistantRole = false), 1_000, 1_500))
        assertEquals(AssistStep.ShowCall, assistStep(ready.copy(assistantRole = false, callActive = true), 1_000, 1_500))
    }
}
