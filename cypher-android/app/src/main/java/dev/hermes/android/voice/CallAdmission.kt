package dev.hermes.android.voice

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Allocation and teardown share the same lock; a delayed admission cannot reopen a closed call. */
internal suspend fun admitCallTransport(lock: Mutex, closed: AtomicBoolean, owns: () -> Boolean, open: () -> Unit) =
    lock.withLock {
        currentCoroutineContext().ensureActive()
        if (!closed.get() && owns()) open()
    }
