package dev.hermes.android.uiux.share

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.preferences
import dev.hermes.android.share.ShareActivity
import dev.hermes.android.share.SharedIntake
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Beacon in Android's share sheet, on the emulator with a synthetic saved account: the chooser opens filled from the saved chat list,
 * says in one line what is missing when signed out or when the content is not something Beacon takes, and hands the choice to the app.
 */
class ShareChooserDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val scope = Scope("https://share-lane.invalid", "synthetic-owner", "default", nativeProvider = "fixture")
    private val originKey = stringPreferencesKey("origin")
    private val completeKey = booleanPreferencesKey("setup_complete")
    private val activeKey = booleanPreferencesKey("setup_active")
    private lateinit var previousPrefs: Triple<String?, Boolean?, Boolean?>
    private val previousAuth = app.auth.read(scope.origin)
    private val inserted = mutableListOf<Uri>()

    private fun shell(command: String) =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    @Before fun setUp() {
        assumeTrue("The chooser lines are English", Locale.getDefault().language == "en")
        // A chooser over a sleeping screen stops at once; another case may have left the display off.
        val power = app.getSystemService(android.os.PowerManager::class.java)
        shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
        val awake = System.currentTimeMillis() + 5_000
        while (!power.isInteractive && System.currentTimeMillis() < awake) Thread.sleep(50)
        assertTrue("the screen is on", power.isInteractive)
        runBlocking { app.preferences.edit { values -> previousPrefs = Triple(values[originKey], values[completeKey], values[activeKey]) } }
    }

    @After fun tearDown() {
        runBlocking {
            app.preferences.edit { values ->
                previousPrefs.first?.let { values[originKey] = it } ?: values.remove(originKey)
                previousPrefs.second?.let { values[completeKey] = it } ?: values.remove(completeKey)
                previousPrefs.third?.let { values[activeKey] = it } ?: values.remove(activeKey)
            }
            app.cache.delete(scope, "conversation-index")
        }
        app.auth.write(previousAuth)
        inserted.forEach { app.contentResolver.delete(it, null, null) }
        resumed().filterIsInstance<BeaconActivity>().forEach { it.runOnUiThread { it.finish() } }
    }

    /** The activities in front now; Android's monitor answers on the main thread only. */
    private fun resumed(): List<android.app.Activity> {
        var found = emptyList<android.app.Activity>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).toList() }
        return found
    }

    private fun signedIn(recents: JSONArray = JSONArray()) {
        app.auth.write(AuthContext(
            scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider,
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
        ))
        runBlocking {
            app.preferences.edit { values -> values[originKey] = scope.origin; values[completeKey] = true; values[activeKey] = false }
            app.cache.write(scope, "conversation-index", json("sessions" to recents).toString())
        }
    }

    private fun signedOut() = runBlocking {
        app.preferences.edit { values -> values[originKey] = scope.origin; values[completeKey] = true; values[activeKey] = false }
        app.auth.write(previousAuth)
    }

    private fun send(text: String? = null, subject: String? = null, vararg images: Uri): Intent {
        val intent = Intent(if (images.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).setClass(app, ShareActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.type = if (images.isEmpty()) "text/plain" else "*/*"
        text?.let { intent.putExtra(Intent.EXTRA_TEXT, it) }
        subject?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        when (images.size) {
            0 -> Unit
            1 -> intent.putExtra(Intent.EXTRA_STREAM, images.single())
            else -> intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(images.toList()))
        }
        return intent
    }

    private fun image(name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/BeaconShareTest")
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        // The smallest valid PNG: a single transparent pixel.
        val png = android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==", android.util.Base64.DEFAULT)
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(png) }
        inserted += uri
        return uri
    }

    /** A synthetic document in Downloads (CHAT-121 follow-up 3), removed after the test like the images. */
    private fun document(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime); put(MediaStore.Downloads.RELATIVE_PATH, "Download/BeaconShareTest")
        }
        val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write("%PDF-1.4 synthetic".toByteArray()) }
        inserted += uri
        return uri
    }

    /** CHAT-121 follow-up 3: a document shared from another app is offered to a chat (before, the chooser said Beacon could only take images). */
    @Test fun documentsAreOfferedAndCountedAsFiles() {
        signedIn()
        ActivityScenario.launch<ShareActivity>(send(null, null, image("five"), document("ملف تجريبي.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))).use {
            compose.onNodeWithText("2 files").assertIsDisplayed()
        }
        val pdf = document("Synthetic summary 2026.pdf", "application/pdf")
        ActivityScenario.launch<ShareActivity>(send(null, null, pdf)).use {
            compose.onNodeWithText("1 file").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-note").assertDoesNotExist()
            // Picking New chat hands the document itself to the app (with the read grant, as for images).
            compose.onNodeWithTag("beacon-share-new").performClick()
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until && SharedIntake.lastOffered?.content?.files != listOf(pdf.toString())) Thread.sleep(25)
            val share = requireNotNull(SharedIntake.lastOffered) { "nothing was handed to the app" }
            assertEquals(dev.hermes.android.share.ShareTarget.NewChat, share.target)
            assertEquals(listOf(pdf.toString()), share.content.files)
            assertTrue("a document is not an image", !share.content.onlyImages)
        }
    }

    private fun row(id: String, title: String, vararg extra: Pair<String, Any>) = json("session_id" to id, "title" to title).apply { extra.forEach { put(it.first, it.second) } }

    @Test fun theChooserOpensFilledFromTheSavedChatsAndNamesWhatWasShared() {
        signedIn(JSONArray().put(row("chat-a", "Weekend in Lisbon")).put(row("chat-b", "From Telegram", "source" to "telegram")).put(row("chat-c", "Reading list")))
        ActivityScenario.launch<ShareActivity>(send("https://example.com/lisbon", "Lisbon guide")).use {
            compose.onNodeWithTag("beacon-share-sheet").assertIsDisplayed()
            compose.onNodeWithText("Add to a chat").assertIsDisplayed()
            compose.onNodeWithText("Lisbon guide").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-beacon").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-new").assertIsDisplayed()
            compose.onNodeWithText("Recent chats").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-chat-chat-a").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-chat-chat-c").assertIsDisplayed()
            compose.onNodeWithText("From Telegram").assertDoesNotExist()
            compose.onNodeWithTag("beacon-share-note").assertDoesNotExist()
        }
    }

    @Test fun oneOrSeveralImagesAreCountedAndAnythingElseIsSaidInOneLine() {
        signedIn()
        ActivityScenario.launch<ShareActivity>(send(null, null, image("one"))).use {
            compose.onNodeWithText("1 image").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-new").assertIsDisplayed()
        }
        ActivityScenario.launch<ShareActivity>(send(null, null, image("two"), image("three"))).use {
            compose.onNodeWithText("2 images").assertIsDisplayed()
        }
        ActivityScenario.launch<ShareActivity>(send("caption", null, image("four"))).use {
            compose.onNodeWithText("caption").assertIsDisplayed()
            compose.onNodeWithText("1 image").assertIsDisplayed()
        }
        ActivityScenario.launch<ShareActivity>(send(null)).use {
            compose.onNodeWithText("Beacon can only take text, links, images and documents.").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-beacon").assertDoesNotExist()
        }
        ActivityScenario.launch<ShareActivity>(send("x".repeat(40_001))).use {
            compose.onNodeWithText("That is too long to share here.").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-new").assertDoesNotExist()
        }
        // A file path and one of Beacon's own providers are never read.
        val own = Uri.parse("content://${app.packageName}.files/shared/secret.png")
        ActivityScenario.launch<ShareActivity>(send(null, null, own)).use {
            compose.onNodeWithText("Beacon can only take text, links, images and documents.").assertIsDisplayed()
        }
    }

    @Test fun signedOutSaysWhatIsNeededAndOffersNothing() {
        signedOut()
        ActivityScenario.launch<ShareActivity>(send("hello")).use {
            compose.onNodeWithText("Open Beacon and connect to your Hermes first.").assertIsDisplayed()
            compose.onNodeWithTag("beacon-share-beacon").assertDoesNotExist()
            compose.onNodeWithTag("beacon-share-new").assertDoesNotExist()
        }
    }

    @Test fun pickingAChatHandsTheShareToTheAppAndLeavesTheChooser() {
        signedIn(JSONArray().put(row("chat-a", "Weekend in Lisbon")))
        ActivityScenario.launch<ShareActivity>(send("remember the milk")).use { scenario ->
            compose.onNodeWithTag("beacon-share-chat-chat-a").performClick()
            // A Beacon view model left running by an earlier test claims the share at once, so the offer is read where it was made.
            val until = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < until && SharedIntake.lastOffered?.content?.text != "remember the milk") Thread.sleep(25)
            val share = requireNotNull(SharedIntake.lastOffered) { "nothing was handed to the app" }
            assertEquals(dev.hermes.android.share.ShareTarget.Chat("chat-a"), share.target)
            assertEquals("remember the milk", share.content.text)
            assertTrue(share.content.files.isEmpty())
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline && resumed().none { it is BeaconActivity }) Thread.sleep(50)
            assertTrue("Beacon opened with the choice", resumed().any { it is BeaconActivity })
            val gone = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < gone && scenario.state != androidx.lifecycle.Lifecycle.State.DESTROYED) Thread.sleep(50)
            assertEquals("the chooser is gone", androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
