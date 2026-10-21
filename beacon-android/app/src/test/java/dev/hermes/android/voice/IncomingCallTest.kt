package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import dev.hermes.android.data.ApiFailure
import java.io.IOException
import dev.hermes.android.core.Scope
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.authorizesPushSource
import kotlinx.coroutines.test.runTest

class IncomingCallTest {
    private fun route() = JSONObject("""{"source_ref":"source-1","profile":"default","kind":"incoming_call","state":"pending","callback_id":"callback-1","expires_at":1120}""")

    @Test fun incomingRecordsExpireAfterTheirStateSpecificRetentionWindow() {
        val now = 1_000_000_000_000L

        assertFalse(incomingRecordMayBePruned("ended", 999_999_700L, now))
        assertTrue(incomingRecordMayBePruned("ended", 999_999_699L, now))
        assertFalse(incomingRecordMayBePruned("missed", 999_395_200L, now))
        assertTrue(incomingRecordMayBePruned("missed", 999_395_199L, now))
        assertFalse(incomingRecordMayBePruned("missed", 0L, now))
    }

    @Test fun transientEngineLeaseAndNetworkFailuresKeepInvitationRetryable() {
        listOf(ApiFailure(503, "Engine unavailable"), ApiFailure(500, "Engine failed"), ApiFailure(409, "Call lease busy"), ApiFailure(400, "Unknown callback failure"), IOException("Response lost")).forEach { assertFalse(incomingFailureTerminal(it)) }
        listOf(401, 403, 404, 410).forEach { assertTrue(incomingFailureTerminal(ApiFailure(it, "Invitation authorization ended"))) }
        assertFalse(incomingRouteEnded(route(), "source-1", "default"))
        listOf("answered", "declined", "expired").forEach { state ->
            val ended = route().put("state", state)
            assertTrue(incomingRouteEnded(ended, "source-1", "default"))
            assertFalse(incomingRouteEnded(ended, "other-source", "default"))
            assertFalse(incomingRouteEnded(ended, "source-1", "other-profile"))
        }
    }

    @Test fun opaqueAndExpiryBoundariesFailClosed() {
        assertTrue(validIncoming("source-1", "event:1", 1120, 1000))
        listOf(0L, 999L, 1000L, 1151L, Long.MAX_VALUE).forEach { assertFalse(validIncoming("source-1", "event:1", it, 1000)) }
        listOf("", ".", "..", "../event", "https://evil.invalid", "a\nb", "a".repeat(201)).forEach { value ->
            assertFalse(validIncoming(value, "event", 1060, 1000))
            assertFalse(validIncoming("source", value, 1060, 1000))
        }
    }

    @Test fun resolvedInvitationMustMatchSourceProfilePendingKindAndExpiry() {
        requireIncomingRoute(route(), "source-1", "default", 1000)
        listOf("source_ref" to "source-2", "profile" to "other", "kind" to "task_result", "state" to "answered", "state" to "declined", "state" to "expired", "callback_id" to "../unsafe", "expires_at" to 1000, "expires_at" to 1151).forEach { (key, value) ->
            assertThrows(IllegalArgumentException::class.java) { requireIncomingRoute(route().put(key, value), "source-1", "default", 1000) }
        }
        assertThrows(IllegalArgumentException::class.java) { requireIncomingRoute(route(), "source-2", "default", 1000) }
        assertThrows(IllegalArgumentException::class.java) { requireIncomingRoute(route(), "source-1", "other", 1000) }
    }

    @Test fun declineAuthorizationRunsAfterPreflightAndImmediatelyBeforeTheRequest() = runTest {
        val order = mutableListOf<String>()
        val preflightFailure = runCatching {
            performIncomingDeclineRequest(
                pending = { order += "pending"; false },
                authorize = { order += "authorize"; true },
                request = { order += "request" },
            )
        }.exceptionOrNull()
        assertTrue(preflightFailure is IllegalArgumentException)
        assertEquals(listOf("pending"), order)

        order.clear()
        val requestFailure = runCatching {
            performIncomingDeclineRequest(
                pending = { order += "pending"; true },
                authorize = { order += "authorize"; true },
                request = { order += "request"; throw IOException("response lost") },
            )
        }.exceptionOrNull()
        assertTrue(requestFailure is IOException)
        assertEquals(listOf("pending", "authorize", "request"), order)
    }

    @Test fun serverTwoMinuteInvitationAcceptsSevenSecondSkewAndThirtySecondBoundary() {
        val serverNow = 1000L
        val expiry = serverNow + 120
        assertTrue(validIncoming("source-1", "event-1", expiry, serverNow - 7))
        requireIncomingRoute(route(), "source-1", "default", serverNow - 7)
        assertTrue(validIncoming("source-1", "event-1", expiry, serverNow - 30))
        assertFalse(validIncoming("source-1", "event-1", expiry, serverNow - 31))
        assertFalse(validIncoming("source-1", "event-1", expiry, expiry))
        assertFalse(validIncoming("source-1", "event-1", expiry, -1))
    }
    @Test fun skewDoesNotAuthorizeMalformedOrForeignInvitations() {
        assertFalse(validIncoming("../source", "event-1", 1120, 993))
        assertFalse(validIncoming("source-1", "../event", 1120, 993))
        assertFalse(validIncoming("source-1", "event-1", 0, 993))
        assertThrows(IllegalArgumentException::class.java) { requireIncomingRoute(route(), "foreign-source", "default", 993) }
        assertThrows(IllegalArgumentException::class.java) { requireIncomingRoute(route(), "source-1", "foreign-profile", 993) }
        val scope = Scope("https://hermes.example", "owner-1", "default")
        assertTrue(AuthContext(scope.origin, emptyList(), scope.owner,backend="hermes-dashboard").authorizesPushSource(scope))
        assertFalse(AuthContext(scope.origin, emptyList(), scope.owner).authorizesPushSource(scope))
        assertFalse(AuthContext(scope.origin, emptyList()).authorizesPushSource(scope))
        assertFalse(AuthContext(scope.origin, emptyList(), "foreign-owner",backend="hermes-dashboard").authorizesPushSource(scope))
        assertFalse(AuthContext("https://foreign.example", emptyList(), scope.owner,backend="hermes-dashboard").authorizesPushSource(scope))
    }
    @Test fun localDeadlineSurvivesCopiesAndFailsClosedOnClockOrBootChanges() {
        val invite = IncomingCall("source-1", "event-1", 1120, arrivedAt = 1000000, arrivedElapsed = 50000, boot = 7)
        assertEquals(20000L, invite.ringDurationMillis(1000000, 50000, 7))
        assertEquals(1L, invite.copy().ringDurationMillis(1019999, 69999, 7))
        assertEquals(0L, invite.ringDurationMillis(1020000, 70000, 7))
        assertEquals(0L, invite.ringDurationMillis(999999, 50001, 7))
        assertEquals(0L, invite.ringDurationMillis(1000001, 49999, 7))
        assertEquals(0L, invite.ringDurationMillis(1000001, 50001, 8))
        assertEquals(0L, invite.copy(state = "dismissed").ringDurationMillis(1000001, 50001, 7))
        assertEquals(0L, invite.copy(state = "missed").ringDurationMillis(1000001, 50001, 7))
        assertEquals(0L, invite.copy(expiresAt = 1000).ringDurationMillis(1000000, 50000, 7))
        assertEquals(0L, invite.copy(arrivedAt = 0).ringDurationMillis(1000000, 50000, 7))
        assertEquals(15000L, invite.ringDurationMillis(1000100, 55000, 7))
        assertEquals(10000L, invite.ringDurationMillis(1010000, 50100, 7))
    }
    @Test fun bindingPairCommitsTogetherAndFailedCommitRestoresExistingAuthority() {
        for(fail in listOf(false,true)) {
            val stored=mutableMapOf("source-old" to "old-source","active" to "old-active")
            var commits=0
            fun editor():android.content.SharedPreferences.Editor {
                val updates=linkedMapOf<String,String?>()
                return java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(android.content.SharedPreferences.Editor::class.java)) { proxy,method,args ->
                    when(method.name) {
                        "putString" -> { updates[args!![0] as String]=args[1] as String?;proxy }
                        "remove" -> { updates[args!![0] as String]=null;proxy }
                        "commit" -> {
                            updates.forEach { (key,value) -> if(value==null) stored.remove(key) else stored[key]=value }
                            commits++;!(fail&&commits==1)
                        }
                        else -> error("Unexpected Editor operation ${method.name}")
                    }
                } as android.content.SharedPreferences.Editor
            }
            val prefs=java.lang.reflect.Proxy.newProxyInstance(android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java)) { _,method,args ->
                when(method.name) { "getString" -> stored[args!![0]]?:args[1];"edit" -> editor();else -> error("Unexpected preference operation ${method.name}") }
            } as android.content.SharedPreferences
            val updates=linkedMapOf<String,String?>("source-new" to "new-source","active" to "new-active","source-old" to null)
            if(fail) {
                assertThrows(IllegalStateException::class.java) { dev.hermes.android.push.persistPushBindings(prefs,updates) }
                assertEquals(mapOf("source-old" to "old-source","active" to "old-active"),stored)
                assertEquals(2,commits)
            } else {
                dev.hermes.android.push.persistPushBindings(prefs,updates)
                assertEquals(mapOf("source-new" to "new-source","active" to "new-active"),stored)
                assertEquals(1,commits)
            }
        }
    }
}
