package dev.hermes.android.uiux.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.receivePush
import dev.hermes.android.push.refreshNotificationPreview
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * Reply from the notification, on the emulator, against the synthetic Hermes fixture only: the Reply action appears on a chat's reply
 * notice and on nothing else, a reply goes to that notice's own chat and the notice then reads Sent, a failed send keeps the text for one
 * tap, a notice of another owner or profile sends nothing, and the answer takes the place of the Sent notice.
 */
class ReplyFromNotificationDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val fixture = NativeExperienceFixture()
    private val scope = fixture.scope
    private val pushPrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
    private val previousAuth = app.auth.read(scope.origin)
    private val bindingKeys = mutableListOf<String>()
    private val tags = mutableListOf<String>()

    @Before fun setUp() {
        assumeTrue("The notification lines are English", Locale.getDefault().language == "en")
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.auth.write(AuthContext(
            scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider,
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
        ))
        KeepConnectedService.clientFactoryForTest = { current, store -> fixture.clientFor(current, store) }
        app.calls.uiVisible = false
        // Android sheds a package's notifications above about five a second; the earlier case's last updates must have settled.
        pace(1_200)
    }

    @After fun tearDown() {
        KeepConnectedService.clientFactoryForTest = null
        tags.forEach { manager.cancel(it, 1) }
        pushPrefs.edit().apply { bindingKeys.forEach { remove(it) } }.commit()
        app.auth.write(previousAuth)
    }

    /** Keeps the fixture under Android's notification rate limit (above about five an update a second it drops updates, which a person never reaches). */
    private fun pace(millis: Long = 600) = SystemClock.sleep(millis)

    /** A notice of [kind] for the fixture's chat-a, as the phone first shows it, then filled in from Hermes' own route. */
    private fun post(kind: String = "completion", conversation: String? = null, edit: (JSONObject) -> Unit = {}): JSONObject {
        val event = fixture.emitTaskResult().apply {
            put("kind", kind)
            conversation?.let { put("conversation_ref", it) }
            put("preview", json("title" to "Weekend plan", "body" to "Dinner is booked for seven."))
            edit(this)
        }
        fixture.incomingNotification = event
        val source = event.string("source_ref")
        val key = "source:${source.digest()}"
        val binding = JSONObject().put("source", source).put("origin", scope.origin).put("owner", scope.owner).put("native_provider", scope.nativeProvider)
            .put("profile", scope.profile).put("backend", "hermes-dashboard").put("target", "fixture-target")
        persistPushBindings(pushPrefs, mapOf(key to android.util.Base64.encodeToString(app.cipher.encrypt(binding.toString().toByteArray(), key), android.util.Base64.NO_WRAP)))
        bindingKeys += key
        tags += "$source\u0000${event.string("event_id")}".digest()
        val data = mapOf("source_ref" to source, "event_id" to event.string("event_id"), "conversation_ref" to event.string("conversation_ref"), "kind" to kind,
            "created_at" to (System.currentTimeMillis() / 1000).toString())
        pace()
        val preview = receivePush(app, data, null, deferPreview = true)
        assertNotNull("the notice is posted before its text is fetched", preview)
        pace()
        runBlocking { refreshNotificationPreview(app, source, event.string("event_id"), event.string("conversation_ref"), kind) }
        return event
    }

    private fun tagOf(event: JSONObject) = "${event.string("source_ref")}\u0000${event.string("event_id")}".digest()
    private fun shown(event: JSONObject): Notification? = manager.activeNotifications.firstOrNull { it.tag == tagOf(event) && it.id == 1 }?.notification
    private fun lineOf(event: JSONObject) = shown(event)?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun await(what: String, timeoutMillis: Long = 20_000, ready: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMillis
        while (!ready() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue("Timed out waiting for $what", ready())
    }
    private fun promptSubmissions() = fixture.requests.filter { it.optString("method") == "prompt.submit" }.map { it.getJSONObject("params") }

    /**
     * What the system does when the owner types in the shade: fills the typed text into the action's own PendingIntent. The Reply action
     * arrives with the preview's update, which a long run can delay (Android paces a package's notification updates), so it is waited for.
     */
    private fun typeReply(event: JSONObject, text: String) {
        await("the Reply action") { shown(event)?.actions?.any { !it.remoteInputs.isNullOrEmpty() } == true }
        pace()
        val action = shown(event)!!.actions.single { !it.remoteInputs.isNullOrEmpty() }
        val fill = Intent()
        android.app.RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { putCharSequence(action.remoteInputs.single().resultKey, text) })
        action.actionIntent.send(app, 0, fill)
    }

    @Test fun onlyAChatsReplyNoticeOffersReplyAndOnlyOnce() {
        for (kind in listOf("completion", "task_result")) {
            val event = post(kind)
            // The Reply action comes with the preview's update; Android may show that update a moment after it is posted.
            await("$kind with its Reply action") { shown(event)?.actions?.isNotEmpty() == true }
            val notice = shown(event) ?: throw AssertionError("$kind was not posted")
            val reply = notice.actions.single()
            assertEquals("Reply", reply.title.toString())
            assertEquals("beacon_reply", reply.remoteInputs.single().resultKey)
            assertTrue(reply.remoteInputs.single().allowFreeFormInput)
            assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
            assertEquals(app.packageName, reply.actionIntent.creatorPackage)
            if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue("on a locked phone Android asks for the unlock first", reply.isAuthenticationRequired)
            assertEquals("the lock screen still shows nothing private", Notification.VISIBILITY_PRIVATE, notice.visibility)
            assertNotNull(notice.publicVersion)
            assertTrue("the lock-screen version carries no Reply", notice.publicVersion.actions.isNullOrEmpty())
            assertEquals("Dinner is booked for seven.", lineOf(event))
            // A second preview refresh (inline, then the job) must not add a second Reply.
            runBlocking { refreshNotificationPreview(app, event.string("source_ref"), event.string("event_id"), event.string("conversation_ref"), kind) }
            assertEquals(1, shown(event)?.actions?.size)
        }
        for (kind in listOf("failure", "test")) {
            val event = post(kind)
            assertTrue("$kind takes no reply", shown(event)?.actions.isNullOrEmpty())
        }
        val noChat = post("completion") { it.put("session_id", "") }
        assertTrue("a notice Hermes names no chat for takes no reply", shown(noChat)?.actions.isNullOrEmpty())
    }

    @Test fun aReplyGoesToTheNoticesOwnChatReadsSentAndGivesWayToTheAnswer() {
        val first = post()
        val conversation = first.string("conversation_ref")
        typeReply(first, "on my way")
        await("the Sent notice") { lineOf(first) == "Sent" }
        val submitted = promptSubmissions()
        assertEquals("exactly one message was started", 1, submitted.size)
        assertEquals("on my way", submitted.single().getString("text"))
        assertTrue("in the notice's own chat (chat-a), not another", submitted.single().getString("session_id").endsWith("chat-a"))
        assertTrue("a sent reply offers no second reply", shown(first)?.actions.isNullOrEmpty())
        assertEquals(Notification.VISIBILITY_PRIVATE, shown(first)?.visibility)
        assertNotNull("the lock screen version is kept", shown(first)?.publicVersion)

        // The answer arrives for the same chat: it takes the Sent notice's place.
        val answer = post("completion", conversation = conversation) { it.put("preview", json("title" to "Weekend plan", "body" to "Great, see you then.")) }
        await("the Sent notice to give way") { shown(first) == null }
        assertEquals("Great, see you then.", lineOf(answer))
    }

    @Test fun aFailedSendKeepsTheTextForOneTapAndNeverClaimsSent() {
        val event = post()
        fixture.refuseConnection = true
        typeReply(event, "call me when you land")
        await("the failure line") { lineOf(event) == "Couldn't send. Open Beacon to try again." }
        assertTrue("nothing reached Hermes", promptSubmissions().isEmpty())
        val again = shown(event)!!.actions.single()
        assertEquals("Send again", again.title.toString())
        assertTrue(again.remoteInputs.isNullOrEmpty())
        fixture.refuseConnection = false
        again.actionIntent.send()
        await("the Sent notice") { lineOf(event) == "Sent" }
        assertEquals("call me when you land", promptSubmissions().single().getString("text"))
    }

    @Test fun aNoticeOfAnotherOwnerSendsNothing() {
        val event = post()
        // Hermes now names a different owner for this notice: whatever was typed must not go anywhere.
        fixture.incomingNotification = JSONObject(event.toString()).put("owner_id", "someone-else")
        typeReply(event, "wrong place")
        await("the failure line") { lineOf(event) == "Couldn't send. Open Beacon to try again." }
        assertTrue(promptSubmissions().isEmpty())
    }

    @Test fun aNoticeHermesNoLongerKnowsSendsNothing() {
        val event = post()
        fixture.incomingNotification = null
        typeReply(event, "gone")
        await("the failure line") { lineOf(event) == "Couldn't send. Open Beacon to try again." }
        assertTrue(promptSubmissions().isEmpty())
        assertFalse("the text stays in the notice for another try", shown(event)?.actions.isNullOrEmpty())
    }
}
