package dev.hermes.android

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.KeepConnectedService
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * K2 (22 Nov 2026): the owner's phone sat on "Reconnecting… · Retrying…" and sent nothing after its Hermes' network dropped twice.
 * The real service runs with no Beacon screen open and the screen off against the K2 host fixture (`.unlazy/k2-123/k2_fixture.py`,
 * `--arg k2_fixture_port=<port>`; skipped without it). Each step breaks the path and then waits for a poll sent after the break to be
 * answered: a 2 s network drop, two drops in a row, a link that goes silent with its socket open, Hermes answering 12 s late, a try
 * stuck where no timeout reaches it, and Android pausing the process for a minute. The fixture runs the device actions with adb, so
 * this test needs no UiAutomation (another tool on the emulator may hold the only one).
 */
class KeepConnectedRecoveryDeviceTest {
    @Test fun keepConnectedPollsAgainAfterDropsStallsAndPausesInTheBackground() {
        val port = InstrumentationRegistry.getArguments().getString("k2_fixture_port")
        assumeTrue("needs the K2 host fixture", port != null)
        // The test emulator routes HTTP through a CONNECT-only proxy (10.0.2.2:8899) for ts.net names; the fixture is reached directly.
        fun control(path: String): JSONObject? = runCatching {
            (java.net.URL("http://10.0.2.2:$port$path").openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection).run {
                connectTimeout = 2_000; readTimeout = 10_000
                try { JSONObject(inputStream.bufferedReader().readText()) } finally { disconnect() }
            }
        }.getOrNull()
        fun note(text: String) { println("K2_STEP $text"); control("/__note?text=" + java.net.URLEncoder.encode(text, "UTF-8")) }
        fun device(action: String) { checkNotNull(control("/__shell?do=$action")) { "K2 fixture unreachable" } }
        /** Airplane mode for [seconds], run by the fixture; returns once this phone reaches the fixture again. */
        fun dropNetwork(seconds: Int) {
            checkNotNull(control("/__drop?seconds=$seconds")) { "K2 fixture unreachable" }
            Thread.sleep(seconds * 1_000L + 1_000)
            val end = SystemClock.elapsedRealtime() + 60_000
            while (control("/__stats")?.optBoolean("dropping") != false) {
                check(SystemClock.elapsedRealtime() < end) { "the network did not come back" }
                Thread.sleep(250)
            }
        }
        val wedge = AtomicBoolean(false)
        val unwedge = CountDownLatch(1)
        try {
            KeepConnectedUiTest.LifecycleFixture().use { f ->
                KeepConnectedService.clientFactoryForTest = { auth, store ->
                    BeaconClient(auth, store, null, null, persistContext = false, configureHttp = { builder ->
                        builder.proxy(java.net.Proxy.NO_PROXY).addInterceptor { chain ->
                            // A try stuck where no timeout reaches it: the thread waits and ignores the call's cancel.
                            if (wedge.compareAndSet(true, false)) unwedge.await()
                            val request = chain.request()
                            chain.proceed(request.newBuilder().url(request.url.newBuilder().scheme("http").host("10.0.2.2").port(port!!.toInt()).build()).build())
                        }
                    }).also { f.clients += it }
                }
                val manager = f.real.getSystemService(NotificationManager::class.java)
                fun seen(): String {
                    val shown = runCatching { manager.activeNotifications.firstOrNull { it.id == 4082 }?.notification?.extras }.getOrNull()
                    return "status=${KeepConnectedService.status(f.app)} reason='${KeepConnectedService.failureReason(f.app)}' " +
                        "shown='${shown?.getCharSequence(Notification.EXTRA_TITLE)} · ${shown?.getCharSequence(Notification.EXTRA_TEXT)}' loop=${f.pollAlive()}"
                }
                val keep = f.app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
                /** Where a break starts: how many polls the fixture has had, and the phone's clock. */
                fun mark(): Pair<Int, Long> = checkNotNull(control("/__stats")) { "K2 fixture unreachable" }.getInt("received") to SystemClock.elapsedRealtime()
                val results = linkedMapOf<String, Long>()
                /**
                 * Waits until a poll sent after the break ([since]) is answered and the phone has taken an answer since the break began,
                 * timed from [from] (the end of the break); records each change of what the phone shows.
                 */
                fun awaitPoll(step: String, since: Pair<Int, Long>, boundMillis: Long, from: Long = SystemClock.elapsedRealtime()) {
                    val trail = mutableListOf<String>()
                    while (true) {
                        val elapsed = SystemClock.elapsedRealtime() - from
                        val now = seen()
                        if (trail.lastOrNull()?.substringAfter(": ") != now) trail += "+${elapsed / 1000}s: $now".also { println("K2_TRAIL $step $it") }
                        if ((control("/__stats")?.optInt("answered_seq") ?: 0) > since.first && keep.getLong(KeepConnectedService.KEY_LAST_RESPONSE, 0) > since.second) {
                            results[step] = elapsed
                            note("$step polled again $elapsed ms after the break ended | ${trail.joinToString(" | ")}")
                            return
                        }
                        assertTrue("$step: no answered poll within ${boundMillis / 1000} s; results so far $results\n${trail.joinToString("\n")}", elapsed < boundMillis)
                        Thread.sleep(1_000)
                    }
                }
                control("/__mode?m=normal")
                note("start")
                var since = mark()
                KeepConnectedService.setEnabled(f.app, f.oldAuth, true); f.await { f.pollAlive() }
                awaitPoll("start", since, 30_000)
                device("home")
                device("sleep")
                Thread.sleep(8_000)
                note("screen off: ${seen()}")

                since = mark()
                dropNetwork(2)
                awaitPoll("network-drop-2s", since, 60_000)

                Thread.sleep(10_000)
                dropNetwork(2)
                Thread.sleep(20_000)
                since = mark()
                dropNetwork(2)
                awaitPoll("network-drop-twice", since, 60_000)

                Thread.sleep(10_000)
                since = mark()
                control("/__mode?m=stall"); Thread.sleep(45_000); control("/__mode?m=normal")
                awaitPoll("silent-link-45s", since, 60_000)

                since = mark()
                control("/__mode?m=slow")
                val slowFrom = SystemClock.elapsedRealtime()
                Thread.sleep(45_000)
                // A held poll then runs 37 s, past its 35 s timeout: one try fails and the next, answered 12 s late, connects.
                awaitPoll("hermes-12s-late", since, 90_000, slowFrom)
                control("/__mode?m=normal")

                Thread.sleep(10_000)
                since = mark()
                wedge.set(true)
                try { awaitPoll("stuck-try", since, 240_000) } finally { unwedge.countDown() }  // The fixture's close joins every loop.

                Thread.sleep(10_000)
                since = mark()
                control("/__freeze?pid=${android.os.Process.myPid()}&seconds=60")
                // The fixture stops this process for 60 s and drops the link meanwhile; this sleep ends after the thaw.
                Thread.sleep(64_000)
                awaitPoll("paused-60s", since, 60_000)
                note("results $results")
            }
        } finally {
            unwedge.countDown()
            control("/__mode?m=normal")
            control("/__shell?do=wake")
        }
    }
}
