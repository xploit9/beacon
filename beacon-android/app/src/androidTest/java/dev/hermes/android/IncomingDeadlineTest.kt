package dev.hermes.android

import android.os.SystemClock
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.receivePush
import dev.hermes.android.voice.declineIncoming
import dev.hermes.android.voice.IncomingRingtoneService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
            arrivedAt = wall - 44900, arrivedElapsed = elapsed - 44900, boot = 7)
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
            assertEquals("Run the incoming probe in normal audio mode",AudioManager.MODE_NORMAL,app.getSystemService(AudioManager::class.java).mode)
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
            assertEquals(Notification.CATEGORY_CALL,original!!.category)
            val audio=app.getSystemService(AudioManager::class.java)
            val callChannel=manager.getNotificationChannel(original!!.channelId)
            val alerting=dev.hermes.android.push.incomingCallMayAlert(false,true,true,false,manager.areNotificationsEnabled(),
                callChannel?.importance?:NotificationManager.IMPORTANCE_NONE,callChannel?.sound!=null,audio.ringerMode,audio.getStreamVolume(AudioManager.STREAM_RING))
            if(audio.ringerMode==AudioManager.RINGER_MODE_NORMAL&&audio.getStreamVolume(AudioManager.STREAM_RING)>0) {
                assertTrue("The call channel owns the ringtone",callChannel?.sound!=null)
                assertTrue("Ringer on, volume up and notifications allowed: the card must alert",alerting)
            }
            assertEquals(!alerting,original!!.group=="silent")
            assertEquals(alerting,(original!!.flags and Notification.FLAG_INSISTENT)!=0)
            assertEquals("Full-screen intent is only attached to an alerting card",alerting,original!!.fullScreenIntent!=null)
            assertNull("The notification channel is the sole ringtone source",original!!.sound)
            val channelToneConfigured=callChannel?.sound!=null
            if(android.os.Build.VERSION.SDK_INT>=36) {
                assertTrue(original!!.extras.getBoolean("android.requestPromotedOngoing"))
                if(manager.canPostPromotedNotifications()) assertTrue(original!!.hasPromotableCharacteristics())
            }
            waitUntil(arrival+5000)
            receivePush(app,data)
            assertEquals(first,app.push.deadlineInvitation(source,event))
            assertEquals(first,dev.hermes.android.push.PushManager(app).deadlineInvitation(source,event))
            ContextCompat.startForegroundService(app,Intent(app,IncomingRingtoneService::class.java)
                .putExtra("push_source",source).putExtra("push_event",event).putExtra("notification",original))
            await(2000) { manager.activeNotifications.firstOrNull { it.id==42 }?.notification?.timeoutAfter?.let { it in 1..41000 }==true }
            val remaining=manager.activeNotifications.single { it.id==42 }.notification.timeoutAfter
            assertTrue("A restarted foreground card cannot receive a new forty-five-second timeout",remaining in 1..41000)
            waitUntil(arrival+44000)
            receivePush(app,data)
            assertEquals(first,app.push.deadlineInvitation(source,event))
            var missedAt=0L
            await(2500) {
                if(app.push.missedInvitation(source,event)!=null) { missedAt=SystemClock.elapsedRealtime();true } else false
            }
            val measured=missedAt-arrival
            assertTrue("Must observe a real forty-five-second transition, not a pre-expired fixture",measured>=44500)
            assertTrue("Missed state must not be postponed by duplicate delivery or restart: $measured ms",measured<=46000)
            await(1000) { manager.activeNotifications.none { it.id==42 } }
            val cards=manager.activeNotifications.filter { it.tag==first.key }
            assertEquals(1,cards.size);assertEquals(2,cards.single().id)
            val missed=cards.single().notification
        assertEquals("Beacon · Missed call",missed.extras.getString(Notification.EXTRA_TITLE))
            assertEquals(listOf("Call back"),missed.actions.map { it.title.toString() })
            assertTrue(missed.actions.all { it.actionIntent.creatorPackage==app.packageName })
            assertFalse(app.push.claimInvitation(source,event))
            java.io.File(app.filesDir,"incoming-deadline-probe.json").writeText(json("measured_ms" to measured,
                "poll_resolution_ms" to 20,"restarted_remaining_ms" to remaining,"duplicate_first_arrival_unchanged" to true,
                "missed_cards" to cards.size,"answer_removed" to true,"foreground_stopped" to true,"channel_tone_configured" to channelToneConfigured).toString())
        }
    }

    @Test fun persistedClaimAndLocalDismissalPreventReplayWithoutNativeNetwork() {
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
                assertEquals(if(action=="claim") "claimed" else "dismissed",state.getString("state"))
                state.put("arrived_at",System.currentTimeMillis()-46000).put("arrived_elapsed",SystemClock.elapsedRealtime()-46000)
                persist(app,key,state)
                app.push.expireInvitation(source,id)
                assertNull(app.push.missedInvitation(source,id))
                assertTrue(manager.activeNotifications.none { it.tag==saved.key })
                app.push.discardInvitation(source,id);assertTrue(prefs.edit().remove(key).commit())
            }
        }
    }

    @Test fun transientFailureAfterClaimCanRetryPreflightAndDeclineWithoutRingingAgain() {
        listOf("/api/auth/me", "event", "decline").forEach { failureStage ->
            fixture { app,source,event,manager ->
                assertTrue(app.push.saveInvitation(IncomingCall(source,event,System.currentTimeMillis()/1000+120),receiptRequired=false))
                val original=checkNotNull(app.push.deadlineInvitation(source,event))
                val eventPath="/api/plugins/beacon/mobile/notifications/events/$event"
                val declinePath="/api/plugins/beacon/mobile/callbacks/$event/decline"
                val failingPath=when(failureStage) { "event" -> eventPath; "decline" -> declinePath; else -> failureStage }
                var failed=false
                var declined=false
                withIncomingHost(app,source,event,{ path ->
                    assertFalse("The active action must own the local claim",app.push.claimInvitation(source,event))
                    if(path==failingPath&&!failed) { failed=true;503 to json("detail" to "Transient fixture failure") }
                    else if(path==declinePath) { declined=true;200 to json("declined" to true) }
                    else if(path==eventPath) 200 to incomingRoute(source)
                    else null
                }) {
                    val failure=runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.exceptionOrNull()
                    assertTrue(failure is ApiFailure && failure.status==503)
                    val retry=checkNotNull(app.push.deadlineInvitation(source,event))
                    assertEquals(original.copy(state="retryable"),retry)
                    assertEquals(0L,retry.ringDurationMillis())
                    assertTrue(IncomingRingtoneService.isSilenced(source,event))
                    app.push.presentInvitation(source,event)
                    assertEquals(original,app.push.incoming.value)
                    assertTrue("Reopening a retry must not post an alert",manager.activeNotifications.none { it.tag==original.key })
                    assertFalse(app.push.saveInvitation(original,receiptRequired=false))
                    kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) }
                    assertTrue(declined)
                    assertNull(app.push.invitation(source,event))
                    assertFalse("A settled action cannot be replayed",app.push.claimInvitation(source,event))
                }
            }
        }
    }

    @Test fun authenticatedTerminalHostStateRetiresARetryInsteadOfSendingDecline() {
        listOf("answered","declined","expired","missed").forEach { terminal ->
            fixture { app,source,event,_ ->
                assertTrue(app.push.saveInvitation(IncomingCall(source,event,System.currentTimeMillis()/1000+120),receiptRequired=false))
                var failed=false
                withIncomingHost(app,source,event,{ path ->
                    if(path=="/api/auth/me"&&!failed) { failed=true;503 to json("detail" to "Transient preflight failure") }
                    else if(path.endsWith("/notifications/events/$event")) 200 to incomingRoute(source,terminal)
                    else null
                }) {
                    assertTrue(runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.isFailure)
                    assertNotNull(app.push.ringingInvitation(source,event))
                    assertTrue(runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.isFailure)
                    assertEquals("ended",app.push.deadlineInvitation(source,event)?.state)
                    assertFalse(app.push.claimInvitation(source,event))
                }
            }
        }
    }

    @Test fun nativeDeclineFailureDoesNotConsumeTheKeepConnectedReceiptBeforeHostSettlement() {
        fixture(eventOverride=UUID.randomUUID().toString()) { app,source,event,_ ->
            val auth=app.auth.read(checkNotNull(app.push.binding(source)).getString("origin"))
            val prefs=app.getSharedPreferences(KeepConnectedService.PREFS,Context.MODE_PRIVATE)
            val keys=listOf(KeepConnectedService.KEY_ENABLED,KeepConnectedService.KEY_ORIGIN,KeepConnectedService.KEY_OWNER,KeepConnectedService.KEY_NATIVE_PROVIDER)
            val before=keys.associateWith { prefs.all[it] }
            try {
                assertTrue(prefs.edit().putBoolean(KeepConnectedService.KEY_ENABLED,true).putString(KeepConnectedService.KEY_ORIGIN,auth.origin)
                    .putString(KeepConnectedService.KEY_OWNER,auth.owner).putString(KeepConnectedService.KEY_NATIVE_PROVIDER,auth.nativeProvider).commit())
                val wire=incomingRoute(source).put("event_id",event).put("callback_id",event).put("owner_id",auth.owner)
                    .put("session_id","qa-conversation").put("conversation_id","qa-conversation").put("conversation_ref","qa-conversation")
                val token=checkNotNull(app.push.prepareKeepConnectedCall(auth.scope(),wire,System.currentTimeMillis(),SystemClock.elapsedRealtime()))
                assertTrue(app.push.saveInvitation(IncomingCall(source,event,wire.getLong("expires_at"),actionToken=token),receiptRequired=true))
                assertFalse(app.push.authorizeKeepConnectedAction(source,event,"unknown"))
                assertTrue(app.push.authorizeKeepConnectedAction(source,event,"answer"))
                assertTrue("Preflight authorization must not consume admission",app.push.authorizeKeepConnectedAction(source,event,"answer"))
                var declines=0
                withIncomingHost(app,source,event,{ path ->
                    when(path) {
                        "/api/plugins/beacon/mobile/notifications/events/$event" -> 200 to wire
                        "/api/plugins/beacon/mobile/callbacks/$event/decline" -> {
                            declines++
                            if(declines==1) 503 to json("detail" to "Transient decline failure") else 200 to json("declined" to true)
                        }
                        else -> null
                    }
                }) {
                    val failure=runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.exceptionOrNull()
                    assertTrue(failure is ApiFailure && failure.status==503)
                    assertEquals("retryable",app.push.deadlineInvitation(source,event)?.state)
                    kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) }
                    assertEquals(2,declines)
                    assertFalse(app.push.authorizeKeepConnectedAction(source,event,"decline"))
                    assertFalse(app.push.claimInvitation(source,event))
                }
            } finally {
                assertTrue(prefs.edit().apply { before.forEach { (key,value) -> when(value) {
                    null -> remove(key)
                    is Boolean -> putBoolean(key,value)
                    is String -> putString(key,value)
                    else -> error("Unexpected Keep connected fixture preference")
                } } }.commit())
            }
        }
    }

    @Test fun expiredOrForeignRetryCannotClaimTheSavedInvitation() {
        fixture { app,source,event,_ ->
            assertTrue(app.push.saveInvitation(IncomingCall(source,event,System.currentTimeMillis()/1000+120),receiptRequired=false))
            withIncomingHost(app,source,event,{ path -> if(path=="/api/auth/me") 503 to json("detail" to "Transient preflight failure") else null }) {
                assertTrue(runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.isFailure)
                assertNotNull(app.push.ringingInvitation(source,event))
                assertFalse(app.push.claimInvitation("foreign-source",event))
                val binding=checkNotNull(app.push.binding(source))
                val origin=binding.getString("origin")
                val auth=app.auth.read(origin)
                app.auth.write(auth.copy(owner="foreign-owner"))
                assertFalse(app.push.claimInvitation(source,event))
                app.auth.write(auth)
                val saved=checkNotNull(app.push.deadlineInvitation(source,event))
                val key="incoming:${saved.key}"
                val prefs=app.getSharedPreferences("push-binding",Context.MODE_PRIVATE)
                val encoded=checkNotNull(prefs.getString(key,null))
                val state=org.json.JSONObject(String(app.cipher.decrypt(android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP),key)))
                state.put("arrived_at",System.currentTimeMillis()-46000).put("arrived_elapsed",SystemClock.elapsedRealtime()-46000)
                persist(app,key,state)
                assertFalse("The original local deadline still bounds a retry",app.push.claimInvitation(source,event))
                assertNull(app.push.ringingInvitation(source,event))
            }
        }
    }

    @Test fun claimFailureAfterRevocationOrDeadlineDoesNotRestoreAnAction() {
        listOf("owner","deadline","server").forEach { ended ->
            fixture { app,source,event,_ ->
                assertTrue(app.push.saveInvitation(IncomingCall(source,event,System.currentTimeMillis()/1000+120),receiptRequired=false))
                withIncomingHost(app,source,event,{ path ->
                    check(path=="/api/auth/me")
                    val binding=checkNotNull(app.push.binding(source))
                    if(ended=="owner") app.auth.write(app.auth.read(binding.getString("origin")).copy(owner="foreign-owner"))
                    else {
                        val saved=checkNotNull(app.push.deadlineInvitation(source,event))
                        val key="incoming:${saved.key}"
                        val encoded=checkNotNull(app.getSharedPreferences("push-binding",Context.MODE_PRIVATE).getString(key,null))
                        val state=org.json.JSONObject(String(app.cipher.decrypt(android.util.Base64.decode(encoded,android.util.Base64.NO_WRAP),key)))
                        if(ended=="server") state.put("expires_at",System.currentTimeMillis()/1000)
                        else state.put("arrived_at",System.currentTimeMillis()-46000).put("arrived_elapsed",SystemClock.elapsedRealtime()-46000)
                        persist(app,key,state)
                    }
                    503 to json("detail" to "Transient preflight failure")
                }) {
                    assertTrue(runCatching { kotlinx.coroutines.runBlocking { declineIncoming(app,source,event) } }.isFailure)
                    assertEquals("ended",app.push.deadlineInvitation(source,event)?.state)
                    assertNull(app.push.incoming.value)
                    assertFalse(app.push.claimInvitation(source,event))
                }
            }
        }
    }

    private fun incomingRoute(source:String,state:String="pending")=json("source_ref" to source,"profile" to "default","kind" to "incoming_call",
        "state" to state,"callback_id" to "qa-callback","expires_at" to System.currentTimeMillis()/1000+120)

    private fun withIncomingHost(app:BeaconApplication,source:String,event:String,response:(String)->Pair<Int,org.json.JSONObject>?,test:()->Unit) {
        val binding=checkNotNull(app.push.binding(source))
        val origin=binding.getString("origin")
        val previous=KeepConnectedService.clientFactoryForTest
        KeepConnectedService.clientFactoryForTest={ auth,store ->
            check(auth.origin==origin)
            BeaconClient(auth,store,persistContext=false,configureHttp={ builder -> builder.addInterceptor { chain ->
                val request=chain.request()
                check(request.url.toString().startsWith("$origin/"))
                val path=request.url.encodedPath
                val (status,body)=response(path)?:when(path) {
                    "/api/auth/me" -> 200 to json("user_id" to auth.owner,"provider" to auth.nativeProvider)
                    "/api/profiles" -> 200 to json("profiles" to org.json.JSONArray().put(json("name" to "default")))
                    else -> error("Unexpected incoming fixture request: $path for $event")
                }
                okhttp3.Response.Builder().request(request).protocol(okhttp3.Protocol.HTTP_1_1).code(status).message("Incoming fixture")
                    .body(body.toString().toResponseBody("application/json".toMediaType())).build()
            } })
        }
        try { test() } finally { KeepConnectedService.clientFactoryForTest=previous }
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
    private fun persist(app:BeaconApplication,key:String,value:org.json.JSONObject) {
        val bytes=app.cipher.encrypt(value.toString().toByteArray(),key)
        assertTrue(app.getSharedPreferences("push-binding",Context.MODE_PRIVATE).edit()
            .putString(key,android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)).commit())
    }
    private fun fixture(eventOverride:String?=null,test:(BeaconApplication,String,String,NotificationManager)->Unit) {
        val app=ApplicationProvider.getApplicationContext<BeaconApplication>()
        check(BuildConfig.DEBUG) { "Synthetic incoming probe is debug-only" }
        check(!app.calls.state.value.active&&app.push.incoming.value==null) { "Run the fixture without an owner call or incoming invitation" }
        val source="qa-incoming-${UUID.randomUUID()}";val event=eventOverride?:"qa-event-${UUID.randomUUID()}"
        val origin="https://${UUID.randomUUID()}.incoming-fixture.invalid"
        val previous=app.auth.read(origin)
        val auth=AuthContext(origin,emptyList(),"qa-owner","default",backend="hermes-dashboard",nativeProvider="fixture")
        val key="source:${source.digest()}"
        val prefs=app.getSharedPreferences("push-binding",Context.MODE_PRIVATE)
        val manager=app.getSystemService(NotificationManager::class.java)
        try {
            app.auth.write(auth)
            persist(app,key,json("source" to source,"origin" to origin,"owner" to auth.owner,"profile" to auth.profile,"native_provider" to auth.nativeProvider,"backend" to "hermes-dashboard"))
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
