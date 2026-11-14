package dev.hermes.android

import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The 15-second wait the UI cases use, with the same limit and the same failure, but a timeout now says what the app was doing.
 * A stalled main thread and a signal that never came both end as a bare "Condition still not satisfied after 15000 ms"; the
 * message adds how long this process's earlier waits took, where the main thread and the other app threads were, and the
 * case's own view of the state.
 */
internal object AwaitDiagnostics {
    private val earlier = ConcurrentLinkedDeque<Long>()

    fun await(rule: AndroidComposeTestRule<*, *>, state: () -> String, condition: () -> Boolean) {
        val started = SystemClock.elapsedRealtime()
        try {
            rule.waitUntil(15_000, condition)
            earlier.addLast(SystemClock.elapsedRealtime() - started)
            while (earlier.size > 16) earlier.pollFirst()
        } catch (error: Throwable) {
            if (error.javaClass.simpleName != "ComposeTimeoutException") throw error
            throw AssertionError(
                "${error.message}\nWaited ${SystemClock.elapsedRealtime() - started} ms. Earlier waits in this process, ms, oldest first: ${earlier.joinToString()}\n" +
                    "State: ${runCatching(state).getOrElse { "unavailable: $it" }}\n${threads()}",
                error,
            )
        }
    }

    private fun threads(): String {
        val main = Looper.getMainLooper().thread
        val own = Thread.currentThread()
        val app = Thread.getAllStackTraces().filter { (thread, frames) ->
            thread !== own && thread !== main && frames.any { it.className.startsWith("dev.hermes.android") }
        }
        fun describe(thread: Thread, frames: Array<StackTraceElement>) = "${thread.name} [${thread.state}]: " + frames.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return "Main thread: ${describe(main, main.stackTrace)}\nApp threads (${app.size}): " +
            app.entries.take(6).joinToString("\n  ", prefix = "\n  ") { (thread, frames) -> describe(thread, frames) }
    }
}
