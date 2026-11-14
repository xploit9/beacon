package dev.hermes.android

import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.*
import dev.hermes.android.data.NativeOperations
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The synthetic Hermes keeps a turn's `message.start` for replay as Hermes does. A second attach to a turn that began earlier takes its baseline from that start
 * (NativeGateway.prepareReplay) and finds what happened before it subscribed; without a start the baseline is the latest sequence and those events are lost.
 */
class FixtureReplayDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    @Test fun aSecondAttachFindsTheEventsOfTheTurnItMissed() {
        val fixture = NativeExperienceFixture()
        val client = fixture.client
        runBlocking { app.auth.persist(client.auth) }
        try {
            val runtime = runBlocking { client.nativeSession("chat-a") }.runtimeSessionId
            runBlocking { client.nativeRpc("prompt.submit", json("session_id" to runtime, "text" to "Hello")) }
            val kept = runBlocking { client.nativeRpc("session.events.since", json("session_id" to runtime, "last_seen" to 0)) }.rows("events")
            // The reply happened while no collector was following the turn (the first attach was replaced, or has not subscribed yet).
            fixture.retainNativeEvent(runtime, "message.delta", json("text" to "Early reply"))
            fixture.retainNativeEvent(runtime, "message.complete", json("status" to "completed"))
            val types = mutableListOf<String>()
            val ended = runBlocking {
                withTimeoutOrNull(10_000) {
                    NativeOperations(client.detached()).stream("/api/chat/stream", mapOf("session_id" to "chat-a"), "") { types += it.type }
                }
            }
            assertNotNull("The second attach never found the end of the turn; it saw $types", ended)
            assertEquals(listOf("server_turn_started", "token", "done"), types)
            assertEquals("Hermes retains the start of the turn it accepted", listOf("message.start"), kept.map { it.string("type") })
        } finally {
            client.closeNative()
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }
}
