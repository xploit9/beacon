package dev.hermes.android

import android.os.SystemClock
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.push.receivePush
import dev.hermes.android.voice.IncomingRingtoneService
import java.util.UUID
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.hermes.android.voice.IncomingCall
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncomingDeadlineTest {
    @Test fun realDeviceElapsedClockDoesNotRestartACopiedInvitation() {
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val invite = IncomingCall("qa-source", "qa-deadline", wall / 1000 + 120,
            arrivedAt = wall - 19900, arrivedElapsed = elapsed - 19900, boot = 7)
        val before = invite.ringDurationMillis(wall, elapsed, 7)
        assertEquals(100L, before)
        SystemClock.sleep(150)
        val observedElapsed = SystemClock.elapsedRealtime()
        assertTrue(observedElapsed - elapsed >= 150)
        assertEquals(0L, invite.copy().ringDurationMillis(System.currentTimeMillis(), observedElapsed, 7))
        assertEquals(0L, invite.ringDurationMillis(wall, elapsed, 8))
        assertEquals(0L, invite.copy(state = "dismissed").ringDurationMillis(wall, elapsed, 7))
    }
    @Test fun actualIncomingDeadlineSurvivesDuplicatesAndServiceRestartAndPostsOneMissedCard() {
        fixture { app,source,event,manager ->
            assertTrue("Grant notification permission before running the device gate",manager.areNotificationsEnabled())
            assertFalse("Run synthetic probe without an active call",app.calls.state.value.active)
            assertTrue("Do not replace an existing incoming foreground notification",manager.activeNotifications.none { it.id==42 })
            val data=mapOf("kind" to "incoming_call","source_ref" to source,"event_id" to event,
                "conversation_ref" to "qa-conversation","expires_at" to (System.currentTimeMillis()/1000+120).toString())
            val arrival=SystemClock.elapsedRealtime()
            receivePush(app,data)
            val first=checkNotNull(app.push.deadlineInvitation(source,event))
            var original:Notification?=null
            await(3000) {
                original=manager.activeNotifications.firstOrNull { it.id==42 }?.notification
                original!=null
            }
            assertNotNull("Device must actually run the foreground ringtone service, not silently pass its fallback",original)
            if(android.os.Build.VERSION.SDK_INT>=36) {
                assertTrue(original!!.extras.getBoolean("android.requestPromotedOngoing"))
                if(manager.canPostPromotedNotifications()) assertTrue(original!!.hasPromotableCharacteristics())
            }
            waitUntil(arrival+5000)
            receivePush(app,data)
            assertEquals(first,app.push.deadlineInvitation(source,event))
            assertEquals(first,dev.hermes.android.push.PushManager(app).deadlineInvitation(source,event))
            val audibleObserved=IncomingRingtoneService.playback(source,event)?.first==true
            ContextCompat.startForegroundService(app,Intent(app,IncomingRingtoneService::class.java)
                .putExtra("push_source",source).putExtra("push_event",event).putExtra("notification",original))
            await(2000) { manager.activeNotifications.firstOrNull { it.id==42 }?.notification?.timeoutAfter?.let { it in 1..16000 }==true }
            val remaining=manager.activeNotifications.single { it.id==42 }.notification.timeoutAfter
            assertTrue("A restarted foreground card cannot receive a new twenty-second timeout",remaining in 1..16000)
            waitUntil(arrival+19000)
            receivePush(app,data)
            assertEquals(first,app.push.deadlineInvitation(source,event))
            var missedAt=0L
            await(2500) {
                if(app.push.missedInvitation(source,event)!=null) { missedAt=SystemClock.elapsedRealtime();true } else false
            }
            val measured=missedAt-arrival
            assertTrue("Must observe a real twenty-second transition, not a pre-expired fixture",measured>=19500)
            assertTrue("Missed state must not be postponed by duplicate delivery or restart: $measured ms",measured<=21000)
            await(1000) { manager.activeNotifications.none { it.id==42 } }
            assertNull("Ringtone playback must end with the missed transition",IncomingRingtoneService.playback(source,event))
            val cards=manager.activeNotifications.filter { it.tag==first.key }
            assertEquals(1,cards.size);assertEquals(2,cards.single().id)
            val missed=cards.single().notification
            assertEquals("Cypher · Missed call",missed.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("Call back"),missed.actions.map { it.title.toString() })
            assertTrue(missed.actions.all { it.actionIntent.creatorPackage==app.packageName })
            assertFalse(app.push.claimInvitation(source,event))
            java.io.File(app.filesDir,"incoming-deadline-probe.json").writeText(json("measured_ms" to measured,
                "poll_resolution_ms" to 20,"restarted_remaining_ms" to remaining,"duplicate_first_arrival_unchanged" to true,
                "missed_cards" to cards.size,"answer_removed" to true,"foreground_stopped" to true,"audible_observed" to audibleObserved).toString())
        }
    }

    @Test fun persistedClaimAndLocalDeclineCancelDeadlineWithoutNativeNetwork() {
        fixture { app,source,event,manager ->
            val suffixes=listOf("claim","decline")
            suffixes.forEach { action ->
                val id="$event-$action"
                assertTrue(app.push.saveInvitation(IncomingCall(source,id,System.currentTimeMillis()/1000+120,conversation="qa-conversation")))
                val saved=checkNotNull(app.push.deadlineInvitation(source,id))
                if(action=="claim") { assertTrue(app.push.claimInvitation(source,id));assertFalse(app.push.claimInvitation(source,id)) }
                else app.push.dismissInvitation(source,id) // Exact local decline suppression; no server request is authorized by this test.
                val prefs=app.getSharedPreferences("push-binding",Context.MODE_PRIVATE)
                val key="incoming:${saved.key}"
                val encoded=checkNotNull(prefs.getString(key,null))
                val state=org.json.JSONObject(String(app.cipher.decrypt(android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP),key)))
                assertEquals("dismissed",state.getString("state"))
                state.put("arrived_at",System.currentTimeMillis()-21000).put("arrived_elapsed",SystemClock.elapsedRealtime()-21000)
                persist(app,key,state)
                app.push.expireInvitation(source,id)
                assertNull(app.push.missedInvitation(source,id))
                assertTrue(manager.activeNotifications.none { it.tag==saved.key })
                app.push.discardInvitation(source,id);assertTrue(prefs.edit().remove(key).commit())
            }
        }
    }

    @Test fun corruptActivePointerCannotKeepAnExactScopeSourceAuthorized() = kotlinx.coroutines.runBlocking {
        fixture { app,source,_,_ ->
            val binding=checkNotNull(app.push.binding(source))
            val client=app.push.clientFor(binding)
            val prefs=app.getSharedPreferences("push-binding",Context.MODE_PRIVATE)
            val active="active:${client.scope.key}"
            try {
                assertTrue(prefs.edit().putString(active,"invalid encrypted fixture").commit())
                kotlinx.coroutines.runBlocking { app.push.revoke(client) }
                assertFalse(prefs.contains(active))
                assertNull(app.push.binding(source));assertFalse(app.push.permitsIncoming(source))
            } finally { prefs.edit().remove(active).commit() }
        }
    }

    private fun waitUntil(deadline:Long) { while(SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(minOf(20L,deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) }
    private fun await(timeout:Long,ready:()->Boolean) {
        val until=SystemClock.elapsedRealtime()+timeout
        while(!ready()&&SystemClock.elapsedRealtime()<until) SystemClock.sleep(20)
        assertTrue("Device observation timed out after $timeout ms",ready())
    }
    private fun persist(app:CypherApplication,key:String,value:org.json.JSONObject) {
        val bytes=app.cipher.encrypt(value.toString().toByteArray(),key)
        assertTrue(app.getSharedPreferences("push-binding",Context.MODE_PRIVATE).edit()
            .putString(key,android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).commit())
    }
    private fun fixture(test:(CypherApplication,String,String,NotificationManager)->Unit) {
        val app=ApplicationProvider.getApplicationContext<CypherApplication>()
        check(BuildConfig.DEBUG) { "Synthetic incoming probe is debug-only" }
        check(!app.calls.state.value.active&&app.push.incoming.value==null) { "Run the fixture without an owner call or incoming invitation" }
        val source="qa-incoming-${UUID.randomUUID()}";val event="qa-event-${UUID.randomUUID()}"
        val origin="https://${UUID.randomUUID()}.incoming-fixture.invalid"
        val previous=app.auth.read(origin)
        val auth=AuthContext(origin,emptyList(),"qa-owner","default",backend="hermes-dashboard")
        val key="source:${source.digest()}"
        val prefs=app.getSharedPreferences("push-binding",Context.MODE_PRIVATE)
        val manager=app.getSystemService(NotificationManager::class.java)
        try {
            app.auth.write(auth)
            persist(app,key,json("source" to source,"origin" to origin,"owner" to auth.owner,"profile" to auth.profile,"backend" to "hermes-dashboard"))
            test(app,source,event,manager)
        } finally {
            listOf(event,"$event-claim","$event-decline").forEach { id ->
                app.push.discardInvitation(source,id)
                val tag="$source\u0000$id".digest()
                manager.cancel(tag,1);manager.cancel(tag,2)
                assertTrue(prefs.edit().remove("incoming:$tag").remove("seen:$tag").commit())
            }
            assertTrue(prefs.edit().remove(key).commit());app.auth.write(previous)
        }
    }
}
