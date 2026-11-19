package dev.hermes.android.agentphone

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.AgentPhoneApi
import dev.hermes.android.data.AgentPhoneResult
import dev.hermes.android.data.ClientAgentPhoneEndpoint
import dev.hermes.android.data.NativePairing
import dev.hermes.android.loginLiveTest
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.ui.AGENT_PHONE_ROUTE
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * Opt-in, against the isolated demo Hermes only (served on 8446), for the joined lanes' live passes (EMU-JOIN fu2: G9, G11 and the
 * owner's journey). Each test is its own step, chosen by one argument:
 * - beaconJoinSignIn=1: signs Beacon in to the demo's own profile from the sign-in demo-fixture.py staged (files/live-auth.json,
 *   deleted once read), links this phone as the app does, finishes setup with Keep connected on, and records the link and Keep
 *   connected's state. The app stays signed in for the passes that then drive it by what the screen says.
 * - beaconJoinProbe=1: opens {name}'s phone through the app's own navigation and records, for 20 s, where the app is and what each
 *   of the page's routes answers; fails if the page leaves by itself.
 * - beaconJoinUnlink=1: unlinks the phone step 1 linked. demo-fixture.py restore then puts the app's own sign-in back.
 * - beaconJoinClip=1: puts "café" on this phone's clipboard, as the owner copying a word would (adb can't type non-ASCII), for
 *   the live view's Paste: the agent phone gets it through its helper (Root's clipboard-chip check, fu2).
 * Records go to the app's external files (join-live/<step>.json), read back with adb. Synthetic data only.
 */
class AgentPhoneJoinSignInTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = compose.activity.application as BeaconApplication

    private fun record(name: String, value: JSONObject) {
        val dir = File(app.getExternalFilesDir(null), "join-live").apply { mkdirs() }
        File(dir, "$name.json").writeText(value.toString(2), Charsets.UTF_8)
    }

    private fun model(): BeaconViewModel {
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        return vm
    }

    @Test(timeout = 180_000) fun signsInLinksAndStaysSignedIn() {
        assumeTrue(args.getString("beaconJoinSignIn") == "1")
        val staged = File(app.filesDir, "live-auth.json")
        val credentials = JSONObject(staged.readText(Charsets.UTF_8).removePrefix("﻿"))
        check(staged.delete() && credentials.optString("purpose") == "beacon_visual_tour_demo")
        val origin = credentials.getString("origin")
        require(android.net.Uri.parse(origin).let { it.scheme == "https" && it.port == 8446 }) { "Only the isolated demo Hermes is ever signed in to" }
        val vm = model()
        runBlocking { loginLiveTest(app, origin, credentials) }
        compose.runOnIdle { vm.connect(origin) }
        compose.waitUntil(90_000) { vm.ui.value.authenticated && vm.client != null && !vm.ui.value.loading }
        val client = checkNotNull(vm.client)
        val scope = client.scope.copy(session = "")
        val device = checkNotNull(runBlocking { NativePairing.deviceId(app, scope) ?: NativePairing.link(app, client) }) { "not linked" }
        compose.runOnIdle { vm.finishSetupWizard(keepConnected = true) }
        compose.waitUntil(30_000) { !vm.ui.value.setupWizard && !vm.ui.value.loading }
        val connected = runCatching { compose.waitUntil(45_000) { KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED } }.isSuccess
        record("signin", JSONObject().put("profile", scope.profile).put("device", device.take(8)).put("keep_connected", KeepConnectedService.status(app))
            .put("keep_connected_reached", connected))
    }

    @Test(timeout = 180_000) fun thePhonePageStaysOpen() {
        assumeTrue(args.getString("beaconJoinProbe") == "1")
        val vm = model()
        compose.waitUntil(90_000) { vm.ui.value.authenticated && vm.client != null && !vm.ui.value.loading }
        val client = checkNotNull(vm.client)
        val profile = vm.preferences.value.assistantProfile
        val api = AgentPhoneApi(ClientAgentPhoneEndpoint(client.forAuthorizedProfile(profile)))
        fun <T> said(result: AgentPhoneResult<T>) = when (result) {
            is AgentPhoneResult.Done -> "ok"
            is AgentPhoneResult.Refused -> "refused ${result.status} ${result.line.code ?: result.line.template}"
        }
        val routes = runBlocking {
            JSONObject().put("status", said(api.status())).put("activity", said(api.activity())).put("approvals", said(api.needsYou()))
                .put("policy", said(api.policy()))
        }
        compose.runOnIdle { vm.more(AGENT_PHONE_ROUTE) }
        val samples = JSONArray()
        var last = ""
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20_000) {
            val ui = vm.ui.value
            val now = listOf(ui.tab, ui.morePage, System.identityHashCode(vm.client), vm.client?.scope?.profile, ui.authenticated, ui.loading).joinToString("|")
            if (now != last) samples.put(JSONObject().put("ms", System.currentTimeMillis() - start).put("tab", ui.tab).put("page", ui.morePage)
                .put("client", System.identityHashCode(vm.client)).put("profile", vm.client?.scope?.profile).put("authenticated", ui.authenticated)
                .put("loading", ui.loading))
            last = now
            Thread.sleep(250)
        }
        record("probe", JSONObject().put("assistant_profile", profile).put("app_profile", client.scope.profile).put("routes", routes).put("samples", samples))
        assertEquals("the page left by itself: $samples", AGENT_PHONE_ROUTE, vm.ui.value.morePage)
    }

    @Test(timeout = 60_000) fun putsANonAsciiWordOnTheClipboard() {
        assumeTrue(args.getString("beaconJoinClip") == "1")
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("", CLIP_WORD))
        }
        var read: String? = null
        compose.runOnIdle { read = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString() }
        record("clip", JSONObject().put("set", CLIP_WORD).put("read_back", read))
        assertEquals(CLIP_WORD, read)
    }

    @Test(timeout = 120_000) fun unlinksTheJoinPhone() {
        assumeTrue(args.getString("beaconJoinUnlink") == "1")
        val vm = model()
        compose.waitUntil(90_000) { vm.client != null }
        val client = checkNotNull(vm.client)
        val scope = client.scope.copy(session = "")
        val device = NativePairing.deviceId(app, scope)
        if (device != null) runBlocking { NativePairing.remove(client, device) }
        record("unlink", JSONObject().put("device", device?.take(8)).put("removed", device != null))
    }

    private companion object {
        const val CLIP_WORD = "café"
    }
}
