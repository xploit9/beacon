package dev.hermes.android.data

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Invalidates readers before a writer waits for the encrypted-store monitor. */
internal class CredentialEpoch {
    private val revision=AtomicLong()
    private val writers=AtomicInteger()
    fun readVersion(): Long? = revision.get().takeIf { writers.get()==0 }
    fun current(version: Long): Boolean = writers.get()==0 && revision.get()==version
    fun <T> mutate(write: () -> T): T {
        writers.incrementAndGet();revision.incrementAndGet()
        try { return write() }
        finally { revision.incrementAndGet();writers.decrementAndGet() }
    }
}
