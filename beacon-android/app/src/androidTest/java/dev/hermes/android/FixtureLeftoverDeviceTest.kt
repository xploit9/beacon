package dev.hermes.android

import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.Scope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The fixture's chat ids (chat-a, chat-b, new-N) repeat in every case of every class, and the app's cache is keyed only by them. What an earlier case left for such a chat
 * (a saved live frame with its replay cursor, the running-turn marker, a draft or a receipt) must not reach the next case's chat of the same id: a stale cursor makes a later
 * attach skip the new turn's first events, so that case waits on a turn that never ends.
 */
class FixtureLeftoverDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val owner = "leftover-owner"

    @Test fun aNewFixtureStartsWithoutWhatAnEarlierCaseLeftForTheSameChatIds() {
        val kinds = listOf("live:runtime-new-2" to "new-2", "live:runtime-chat-a" to "chat-a", "live:new-3" to "new-3", "running-turn" to "new-2", "pending" to "chat-b", "draft" to "new-4",
            "session" to "new-5", "client-created-shell" to "new-6", "side-question" to "new-7")
        val stale = listOf("default", "coder").flatMap { profile ->
            val scope = Scope("https://ui-fixture.invalid", owner, profile, nativeProvider = "fixture")
            kinds.map { (kind, id) -> scope.session(id) to kind }
        }
        try {
            runBlocking { stale.forEach { (scope, kind) -> app.cache.write(scope, kind, """{"cursor":"runtime-${scope.session}:9","text":"stale"}""") } }
            NativeExperienceFixture(owner).client.closeNative()
            val survived = runBlocking { stale.filter { (scope, kind) -> app.cache.read(scope, kind) != null }.map { (scope, kind) -> "${scope.profile}/${scope.session}/$kind" } }
            assertEquals("Entries a new fixture should not inherit", emptyList<String>(), survived)
        } finally {
            runBlocking { stale.forEach { (scope, kind) -> app.cache.delete(scope, kind) } }
        }
    }
}
