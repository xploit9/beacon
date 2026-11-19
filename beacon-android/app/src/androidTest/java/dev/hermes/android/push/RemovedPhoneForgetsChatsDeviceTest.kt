package dev.hermes.android.push

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.data.NativeDeviceRemoval
import dev.hermes.android.data.NativePairing
import dev.hermes.android.ui.GLANCE_PROFILES_KIND
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHATFIX-121 (REVIEW-JOIN-121 P3-6, Root): Hermes removes this phone while no view model is alive (the app is closed when the push comes). The removed
 * account's chat transcripts, chat glances, chat list and received files leave the phone, as a sign-out's forgetScreens takes them; another account's stay.
 * Synthetic accounts and data only.
 */
class RemovedPhoneForgetsChatsDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private fun auth(origin: String, owner: String) = AuthContext(origin, emptyList(), owner, backend = "hermes-dashboard",
        bearer = NativeBearerRecord("synthetic-access-$owner", "synthetic-refresh-$owner", 4_000_000_000, origin, owner, "default", "basic"))
    private fun profile(scope: Scope) = scope.copy(session = "")
    private fun account(scope: Scope) = scope.copy(profile = "", session = "")
    private fun chat(scope: Scope) = profile(scope).session("chat-synthetic")
    private fun receivedFile(scope: Scope) = File(app.filesDir, "received/${account(scope).key}/synthetic/received.txt")

    /** What an account leaves on the phone: its profile list, chat list, chat glances, one chat's transcript and one received file. */
    private suspend fun seed(scope: Scope) {
        app.cache.write(account(scope), GLANCE_PROFILES_KIND, JSONArray(listOf(scope.profile)).toString(), protected = true)
        app.cache.write(profile(scope), "conversation-index", JSONObject().put("sessions", JSONArray().put(JSONObject().put("session_id", "chat-synthetic").put("title", "Synthetic chat"))).toString(), protected = true)
        app.cache.write(profile(scope), "chat-glances", JSONObject().put("rows", JSONArray()).toString(), protected = true)
        app.cache.writeSession(chat(scope), JSONObject().put("session_id", "chat-synthetic").put("messages", JSONArray().put(JSONObject().put("role", "user").put("text", "Synthetic words"))).toString())
        receivedFile(scope).apply { parentFile!!.mkdirs(); writeText("synthetic received file") }
    }
    private suspend fun leftOf(scope: Scope): List<String> = listOfNotNull(
        "transcript".takeIf { app.cache.read(chat(scope), "session") != null },
        "chat list".takeIf { app.cache.read(profile(scope), "conversation-index") != null },
        "chat glances".takeIf { app.cache.read(profile(scope), "chat-glances") != null },
        "received file".takeIf { receivedFile(scope).exists() },
    )

    @Test fun aPhoneRemovedWithNoViewModelLeavesNothingOfThatAccountsChatsAndTheOtherAccountKeepsItsOwn() = runBlocking {
        val removed = auth("https://removed-phone.example", "synthetic-removed-owner")
        val kept = auth("https://kept-account.example", "synthetic-kept-owner")
        val removedScope = removed.scope()
        val keptScope = kept.scope()
        app.push // the push side registers its removal hook (PushManager's init)
        val viewModelHook = NativeDeviceRemoval.onForgetScreens
        try {
            // No view model is alive: only the push side's removal runs.
            NativeDeviceRemoval.onForgetScreens = null
            app.auth.persist(removed)
            seed(removedScope)
            seed(keptScope)
            assertTrue("the removed account's data is on the phone first", leftOf(removedScope).size == 4)
            val removal = requireNotNull(app.auth.deviceRemovalAuthority(removedScope)) { "no removal authority for the synthetic account" }
            NativeDeviceRemoval.removed(removal)
            // The push side's cleanup runs on its own scope.
            val deadline = System.currentTimeMillis() + 15_000
            while (leftOf(removedScope).isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue("the removed account leaves nothing: ${leftOf(removedScope)}", leftOf(removedScope).isEmpty())
            assertTrue("the other account keeps its own: ${leftOf(keptScope)}", leftOf(keptScope).size == 4)
        } finally {
            NativeDeviceRemoval.onForgetScreens = viewModelHook
            for (scope in listOf(removedScope, keptScope)) {
                app.cache.delete(chat(scope), "session")
                for (kind in listOf("conversation-index", "chat-glances")) app.cache.delete(profile(scope), kind)
                app.cache.delete(account(scope), GLANCE_PROFILES_KIND)
                File(app.filesDir, "received/${account(scope).key}").deleteRecursively()
            }
            app.auth.remove(removed.origin)
            app.getSharedPreferences("push-binding", Context.MODE_PRIVATE).edit().remove("removed:${NativePairing.accountKey(removedScope).removePrefix("device:")}").commit()
        }
    }
}
