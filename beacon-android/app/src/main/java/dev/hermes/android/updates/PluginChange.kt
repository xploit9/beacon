package dev.hermes.android.updates

import java.util.concurrent.atomic.AtomicBoolean

/**
 * One change to the Beacon plugin at a time. Repair, the missing-plugin install and the app update all post the same forced install and
 * restart the same dashboard, so the second one asked for waits for the owner to try again instead of stacking a second install and a
 * second restart on the first. Whoever [begin]s a change calls [finish] when it ends, in a `finally`.
 */
internal object PluginChange {
    private val running = AtomicBoolean(false)

    /** True when this caller now owns the change. */
    fun begin(): Boolean = running.compareAndSet(false, true)
    fun finish() { running.set(false) }
    val active: Boolean get() = running.get()
}
