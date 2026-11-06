package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.sha256
import dev.hermes.android.core.string
import dev.hermes.android.data.botModeDisbanded
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.UUID

class OwnerBotsLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 180_000) fun qaBotsCreateReadRenameAndDisbandTheirOwnSpace() = runBlocking {
        val h = OwnerJourneyHarness(compose, "bots")
        h.checked {
            qa()
            val args = InstrumentationRegistry.getArguments()
            val peer = args.getString("beaconQaPeerProfile").orEmpty()
            val peerReady = peer.matches(Regex("qa-[0-9a-f]{32}")) && peer != scope.profile
            if (!peerReady) mark("W-06", "qa_peer_required", "needs_human")
            assumeTrue(peerReady)
            check(!vm.ui.value.botModeEnabled)
            val path = "/api/plugins/beacon/mobile/bot-mode"
            val wantedName = "Beacon QA ${runId}"
            var roomId = ""
            var currentName = "Shared space"
            var writeInFlight = false
            var entered = false
            suspend fun readRoom() = client.nativeRequest("$path/space/$roomId").getJSONObject("room").also { current() }
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                if (compose.onAllNodesWithTag("navigation-bots").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("navigation-more").performClick()
                entered = true
                compose.onNodeWithTag("navigation-bots").performClick()
                await { vm.ui.value.botModeEnabled && !vm.ui.value.botModeSwitching }
                current()
                val roster = client.nativeRequest(path)
                val known = roster.rows("bots").map { it.string("profile") }.toSet()
                check(scope.profile in known && peer in known)
                val capabilities = roster.getJSONObject("capabilities")
                check(capabilities.opt("rename") == true && capabilities.opt("delete") == true)
                mark("W-05", "bot_mode_roster", "passed")
                val before = roster.rows("spaces").map { it.string("room_id") }.toSet()
                val request = UUID.randomUUID().toString()
                check(scope.owner.matches(Regex("[A-Za-z0-9_.@:-]{1,256}")) && scope.nativeProvider.matches(Regex("[A-Za-z0-9_.@:-]{1,256}")))
                val identity = JSONArray(listOf(scope.owner, scope.nativeProvider, scope.profile, request)).toString().toByteArray(Charsets.UTF_8)
                val expectedRoom = "beacon-" + sha256(identity)
                mark("W-06", "space_request_recorded", "passed", qaId = request)
                current(); writeInFlight = true
                val created = client.nativeRequest("$path/space", "POST", json("profiles" to JSONArray(listOf(scope.profile, peer)), "request_id" to request))
                    .getJSONObject("room")
                current()
                val candidate = created.string("room_id")
                val members = created.rows("members")
                check(candidate == expectedRoom && candidate !in before && created.string("name") == currentName)
                check(members.map { it.string("profile") }.toSet() == setOf(scope.profile, peer) && members.size == 2 &&
                    members.all { it.optJSONObject("target")?.string("kind") == "local" && it.optJSONObject("target")?.string("profile") == it.string("profile") })
                roomId = candidate; writeInFlight = false
                check(readRoom().string("room_id") == roomId)
                mark("W-06", "space_create_readback", "passed")
                writeInFlight = true
                val renamed = client.nativeRequest("$path/space/$roomId/rename", "POST", json("name" to wantedName,
                    "expected_name" to currentName, "event_id" to UUID.randomUUID().toString())).getJSONObject("room")
                current()
                currentName = wantedName
                check(renamed.string("room_id") == roomId && renamed.string("name") == currentName && readRoom().string("name") == currentName)
                writeInFlight = false
                mark("W-06", "space_rename_readback", "passed")
                if (args.getString("beaconQaBotTurn") == "1") {
                    val text = "QA only: reply briefly without tools or contacting anyone."
                    val event = UUID.randomUUID().toString()
                    writeInFlight = true
                    val sent = client.nativeRequest("$path/space/$roomId/messages", "POST", json("text" to text, "event_id" to event))
                    current()
                    val nativeEvent = sent.getJSONObject("event")
                    check(sent.opt("accepted") == true && sent.string("client_event_id") == event && nativeEvent.string("kind") == "message.user")
                    val events = client.nativeRequest("$path/space/$roomId").rows("events")
                    check(events.any { it.string("event_id") == nativeEvent.string("event_id") && it.optJSONObject("payload")?.string("text") == text })
                    writeInFlight = false
                    mark("W-06", "space_message_readback", "passed", qaId = event)
                } else mark("W-06", "space_message_readback", "needs_human")
            } finally {
                try {
                    if (writeInFlight) mark("W-06", "space_write_unconfirmed", "needs_human")
                    else if (roomId.isNotBlank()) {
                        check(readRoom().string("name") == currentName)
                        current(); writeInFlight = true
                        val receipt = client.nativeRequest("$path/space/$roomId/delete", "POST", json("event_id" to UUID.randomUUID().toString(), "expected_name" to currentName))
                        current(); writeInFlight = false
                        check(botModeDisbanded(receipt, roomId))
                        check(client.nativeRequest(path).rows("spaces").none { it.string("room_id") == roomId })
                        mark("W-06", "space_disband_readback", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("W-06", "space_cleanup_unconfirmed", "needs_human") }; throw error
                } finally {
                    if (entered) {
                        await(30_000) { !vm.ui.value.botModeSwitching }
                        if (vm.ui.value.botModeEnabled) {
                            compose.runOnIdle { vm.toggleBotMode() }
                            await { !vm.ui.value.botModeSwitching && !vm.ui.value.botModeEnabled }
                        }
                    }
                }
            }
        }
    }
}
