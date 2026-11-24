package dev.hermes.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S1 (1.2.3), the box's Send (R2C on Q2's Hermes, 22 Nov, 17:29: "the box's Send showed nothing within 10 s"). A tap is drainQueued(explicit = true);
 * it returned without a word while a passive drain was running, and that drain's turn look could wait on a dead link for the whole RPC timeout.
 * The view model's own paths are proven on the device; this pins the two rules in its source, as ChatFix121Test does.
 */
class S1SendNeverSilentTest {
    private fun drainSource(): String {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val start = vm.indexOf("private fun drainQueued(")
        return vm.substring(start, vm.indexOf("\n    fun cancel()", start))
    }

    @Test fun aTapOnTheBoxSendTakesOverARunningDrainInsteadOfReturningSilently() {
        val drain = drainSource()
        assertFalse("no bare return while a drain runs", drain.contains("if (queueDrains[scope.key]?.isActive == true) return\n"))
        assertTrue("the owner's tap cancels the passive drain and runs its own", drain.contains("if (explicit) it.cancel() else return"))
    }

    /**
     * The turn look's own socket resumed the chat, so Hermes sends it every frame of the turn, and the stream runs on a copy of its own: after a
     * successful attach the look's socket stayed open until 256 frames overflowed it, or for good after a short turn (one per follow).
     */
    @Test fun aTurnLookClosesItsOwnSocketWhateverTheAttachDid() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val start = vm.indexOf("private suspend fun followRunningTurn(")
        val follow = vm.substring(start, vm.indexOf("\n    }\n", start))
        assertFalse("not only when the attach failed", follow.contains("else if (!attachStream(c, scope, stream, terminalAlreadyObserved = status == \"idle\")) c.closeNative()"))
        assertTrue("closed after the attach, never the app's own client or a stream's", follow.contains("if (c !== client && streamClients.values.none { it === c }) c.closeNative()"))
    }

    /**
     * R2 review (Root msg_4132eb778306), on the combined tree: the owner's 17:09 shape, an assistant row that called a tool and no tool row, keeps a shown
     * turn through a passive read (R2C), so a turn Hermes reaped ends only through the lookup: Hermes' "idle" attaches it as already ended, which settles
     * it from the saved chat; the box waits for that blank stream. Device: ReattachRunDeviceTest.aTurnHermesReapedWhileBeaconWasGone….
     */
    @Test fun aReapedTurnEndsOnlyThroughTheLookupAndTheBoxWaitsForIt() {
        val ask = org.json.JSONObject().put("role", "user").put("content", "Book the cheapest flight.").put("row_id", 1)
        val asked = org.json.JSONObject().put("role", "assistant").put("content", "").put("row_id", 2).put("finish_reason", "tool_calls")
            .put("tool_calls", org.json.JSONArray().put(org.json.JSONObject().put("id", "call-1").put("function", org.json.JSONObject().put("name", "clarify"))))
        assertTrue("a read cannot end it", passiveReadKeepsTurn("runtime-a", namesStream = false, rows = listOf(ask, asked)))
        assertTrue("the box waits for a blank stream", drainSource().contains("it.streamId.isBlank()"))
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val start = vm.indexOf("private suspend fun followRunningTurn(")
        assertTrue("Hermes' idle ends the shown turn through the attach", vm.substring(start, vm.indexOf("\n    }\n", start))
            .contains("attachStream(c, scope, stream, terminalAlreadyObserved = status == \"idle\")"))
    }

    /**
     * Owner 17:07: a photo send showed its bubble, then vanished, and the draft came back with no word, four times, until a force quit. send() stopped
     * it before any request (the model not in front, or the chat changing during the receipt's save) and dropped it silently, while the composer still
     * held it. Device: BusyTurnSendUiTest.aPhotoSendStoppedBeforeHermesWaitsInTheBoxAsNotSentNeverBackInTheDraft.
     */
    @Test fun aSendStoppedBeforeHermesWaitsInTheBoxNeverBackInTheDraft() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val send = vm.substring(vm.indexOf("    fun send(queued: QueuedMessage? = null)"), vm.indexOf("val model = queued?.model ?: _chat.value.raw.string(\"model\")"))
        val stop = send.substring(send.indexOf("if (!request.current() || !foreground || blocked) {"))
        assertTrue("a direct send stopped here waits in the box and goes by itself", stop.contains("else try { returnUnsentWords(scope, send, offline = true) }"))
        assertTrue("and the chat says so", stop.contains("notice(\"Couldn't send it now. It's in the queue.\")"))
        val unsent = vm.substring(vm.indexOf("private suspend fun returnUnsentWords("), vm.indexOf("private suspend fun queueOffline("))
        assertFalse("never back into the composer", unsent.contains("toComposer"))
        assertTrue("only the Beacon-off page, which says why with its Retry (G3H), keeps them in the composer",
            unsent.contains("val back = page && ") && vm.contains("returnUnsentWords(scope, send, offline, page = sendFailureView(e) == SendFailureView.BeaconOffPage, gone = gone)"))
        assertTrue("words the send still held leave the composer", unsent.contains("if (leaves) { _composer.value = TextFieldValue(); _attachments.value = emptyList(); composerRevision++ }"))
    }

    /**
     * Why the 17:07 sends kept stopping: a system relaunch (theme, display size, a fold while away) composes the new activity only after onStart, and
     * onStart reached the model only through the vm field that composition set, so the model stayed "not in front" until a force quit. Device:
     * ForegroundAfterRelaunchDeviceTest (four relaunches; Activity.recreate() keeps the window and composes first, so it never showed this).
     */
    @Test fun theActivityHoldsItsModelBeforeOnStart() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val activity = File(module, "src/main/java/dev/hermes/android/BeaconActivity.kt").readText().replace("\r\n", "\n")
        val create = activity.substring(activity.indexOf("override fun onCreate("), activity.indexOf("setContent {"))
        assertTrue("onCreate sets vm before composition", create.contains("vm = androidx.lifecycle.ViewModelProvider(this)[BeaconViewModel::class.java]"))
        assertTrue("onStart tells it", activity.contains("vm?.foreground(true) }"))
    }

    /** S1d (R3 review #4): every replacement of the own client (profile switch, reconnect, sign-out, a notification's profile) closes the one it replaces. */
    @Test fun aReplacedOwnClientIsClosedWhereverItIsReplaced() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val setter = vm.substring(vm.indexOf("@Volatile var client: BeaconClient? = null"), vm.indexOf("internal var profileSwitchClientFactoryForTest"))
        assertTrue("the setter closes the client it replaces", setter.contains("if (previous != null && previous !== value && previous !== settingsClient && !ownSendsOnTheirWay.containsKey(previous)) previous.closeNative()"))
        assertTrue("after the new one is in place", setter.indexOf("field=value") < setter.indexOf("previous.closeNative()"))
        // R3's case: a new chat's first send rides that client; closing it under the send lost the answer and left the message unknown for good.
        assertTrue("a send on it holds the close", vm.contains("if (c === active) { ownHeld = true; ownSendsOnTheirWay.merge(c, 1, Int::plus) }\n            val o = c.post(\"/api/chat/start\""))
        assertEquals("and lets go once answered or failed", 2, vm.split("if (ownHeld) { ownHeld = false; ownSendDone(c) }").size - 1)
        assertTrue("closing it then if it was replaced", vm.contains("== null && c !== client && c !== settingsClient) c.closeNative()"))
    }

    @Test fun theTapsTurnLookIsBoundedAndATimeoutSaysWhy() {
        val drain = drainSource()
        assertTrue("the explicit look has its own bound", drain.contains("withTimeoutOrNull(if (explicit) EXPLICIT_DRAIN_LOOK_MS else Long.MAX_VALUE)"))
        assertTrue("a look that did not answer counts as unreachable, which the tap says", drain.contains("val unreachable = !looked"))
        assertTrue(EXPLICIT_DRAIN_LOOK_MS in 5_000L..15_000L)
    }
}
