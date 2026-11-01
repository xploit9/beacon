package dev.hermes.android.assist

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Lane assist-overlay: what the overlay's buttons open in the app, which recognizer listens, and what Android is told. */
class AssistHandOffTest {
    @After fun cleanUp() = AssistGestures.clear()

    // --- hand-offs to the app ---

    @Test fun eachTokenRemembersWhatItWasIssuedFor() {
        val chat = AssistGestures.issue(now = 1_000L, target = AssistTarget(AssistPurpose.OpenChat, "c1", "work"))
        val gesture = AssistGestures.issue(now = 2_000L)
        assertEquals(1_000L, AssistGestures.take(chat, now = 1_500L))
        assertEquals(2_000L, AssistGestures.take(gesture, now = 2_500L))
        assertEquals(AssistTarget(AssistPurpose.OpenChat, "c1", "work"), AssistGestures.target(1_000L))
        // The gesture itself is a call, exactly as before the overlay existed.
        assertEquals(AssistTarget(), AssistGestures.target(2_000L))
        assertNull(AssistGestures.target(3_000L))
    }

    @Test fun anOutdatedTokenNamesNothing() {
        val old = AssistGestures.issue(now = 1_000L, target = AssistTarget(AssistPurpose.OpenAssistant))
        assertNull(AssistGestures.take(old, now = 1_001L + ASSIST_WINDOW_MILLIS))
        assertNull(AssistGestures.target(1_000L))
    }

    @Test fun onlyTheGestureItselfEverStartsACall() {
        AssistPurpose.entries.forEach { purpose ->
            val action = assistAction(AssistStep.Call, purpose)
            assertEquals(purpose == AssistPurpose.Call, action == AssistAction.StartCall)
        }
        assertEquals(AssistAction.OpenAssistant, assistAction(AssistStep.Call, AssistPurpose.OpenAssistant))
        assertEquals(AssistAction.OpenChat, assistAction(AssistStep.Call, AssistPurpose.OpenChat))
        // Open call after the call ended: the assistant's chat, which holds what was said.
        assertEquals(AssistAction.OpenAssistant, assistAction(AssistStep.Call, AssistPurpose.ShowCall))
    }

    @Test fun aRunningCallComesFirstUnlessTheOwnerPickedAnotherChat() {
        listOf(AssistPurpose.Call, AssistPurpose.OpenAssistant, AssistPurpose.ShowCall).forEach { assertEquals(AssistAction.ShowCall, assistAction(AssistStep.ShowCall, it)) }
        assertEquals(AssistAction.OpenChat, assistAction(AssistStep.ShowCall, AssistPurpose.OpenChat))
    }

    @Test fun theGesturesOwnRoutesAreUnchanged() {
        assertEquals(AssistAction.OpenAssistant, assistAction(AssistStep.OpenChat, AssistPurpose.Call))
        assertEquals(AssistAction.Expired, assistAction(AssistStep.Expired, AssistPurpose.OpenChat))
        assertEquals(AssistAction.None, assistAction(AssistStep.SignIn, AssistPurpose.OpenAssistant))
        assertEquals(AssistAction.None, assistAction(AssistStep.Wait, AssistPurpose.Call))
    }

    @Test fun anIntentPurposeIsReadOnlyWhenItIsOneOfOurs() {
        assertEquals(AssistTarget(AssistPurpose.OpenChat, "c1", "work"), assistTargetOf("OpenChat", "c1", "work"))
        assertEquals(AssistTarget(AssistPurpose.ShowCall), assistTargetOf("ShowCall", null, null))
        assertNull(assistTargetOf("Unlock", "c1", "work"))
        assertNull(assistTargetOf(null, null, null))
    }

    // --- who listens ---

    private val own = "dev.hermes.android.debug"
    private val beacon = "$own/dev.hermes.android.assist.BeaconRecognitionService"
    private val google = "com.google.android.tts/com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"
    private val other = "org.example.speech/org.example.speech.Recognizer"

    @Test fun theOwnersRecognizerListensNeverBeacons() {
        assertEquals(RecognizerChoice.Component(google), pickRecognizer(own, google, onDevice = true, installed = listOf(beacon, google)))
        // Beacon chosen as the default (Android 10 and 11 can do that for an assistant): the phone's own recognizer instead.
        assertEquals(RecognizerChoice.OnDevice, pickRecognizer(own, beacon, onDevice = true, installed = listOf(beacon, google)))
        assertEquals(RecognizerChoice.Component(other), pickRecognizer(own, beacon, onDevice = false, installed = listOf(beacon, other, google)))
        assertEquals(RecognizerChoice.Component(other), pickRecognizer(own, null, onDevice = false, installed = listOf(other)))
        assertNull(pickRecognizer(own, beacon, onDevice = false, installed = listOf(beacon)))
        assertNull(pickRecognizer(own, null, onDevice = false, installed = emptyList()))
    }

    @Test fun theMicLevelFollowsTheVoice() {
        assertEquals(0f, dictationLevel(-2f))
        assertEquals(1f, dictationLevel(10f))
        assertEquals(.5f, dictationLevel(4f), .001f)
        assertEquals(0f, dictationLevel(Float.NaN))
        assertEquals(1f, dictationLevel(40f))
    }

    // --- what Android is told ---

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile })
    private val androidNs = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(app, path)).documentElement
    private fun Element.android(name: String) = getAttributeNS(androidNs, name)
    private fun Element.children(tag: String) = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tag }

    @Test fun beaconIsAnAssistantThatOpensFromTheLockScreen() {
        val info = xml("src/main/res/xml/beacon_voice_interaction.xml")
        assertEquals("voice-interaction-service", info.tagName)
        assertEquals("true", info.android("supportsAssist"))
        assertEquals("true", info.android("supportsLaunchVoiceAssistFromKeyguard"))
        val application = xml("src/main/AndroidManifest.xml").children("application").single()
        val services = application.children("service").associateBy { it.android("name") }
        // The metadata names services that exist, by their full class names.
        assertEquals("dev.hermes.android.assist.BeaconAssistSessionService", info.android("sessionService"))
        assertEquals("dev.hermes.android.assist.BeaconRecognitionService", info.android("recognitionService"))
        assertTrue(".assist.BeaconAssistSessionService" in services && ".assist.BeaconRecognitionService" in services)
        val vis = services.getValue(".assist.BeaconVoiceInteractionService")
        assertEquals("@xml/beacon_voice_interaction", vis.children("meta-data").single { it.android("name") == "android.voice_interaction" }.android("resource"))
        // Never offered as the phone's default speech recognizer: it only passes requests on.
        assertEquals("false", xml("src/main/res/xml/beacon_recognition.xml").android("selectableAsDefault"))
        // The unlock step draws over the lock screen but nothing outside Beacon can start it.
        val unlock = application.children("activity").single { it.android("name") == ".assist.AssistUnlockActivity" }
        assertEquals("false", unlock.android("exported"))
        assertEquals("true", unlock.android("showWhenLocked"))
        assertTrue(unlock.children("intent-filter").isEmpty())
    }
}
