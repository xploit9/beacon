package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.data.CredentialEpoch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

internal data class OverlayAuthorization(val owner: Scope, val validUntil: Long)
/** Main-thread gesture checks read atomic metadata only; credential I/O is always on IO. */
internal class OverlayAuthority(private val credentials: CredentialEpoch, private val read: () -> OverlayAuthorization?) {
    private data class State(val generation: Long=0, val closed: Boolean=false, val version: Long?=null, val grant: OverlayAuthorization?=null)
    private val state=AtomicReference(State())
    fun invalidate() { state.updateAndGet { it.copy(generation=it.generation+1,version=null,grant=null) } }
    fun close() { state.updateAndGet { it.copy(generation=it.generation+1,closed=true,version=null,grant=null) } }
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val before=state.get()
        if(before.closed) return@withContext
        val version=credentials.readVersion() ?: return@withContext
        val grant=runCatching(read).getOrNull()
        // A slow read must never publish after logout, replacement, close or a credential write.
        if(credentials.current(version)) state.compareAndSet(before,before.copy(version=version,grant=grant))
    }
    fun permits(call: CallState, expectedId: String, owner: Scope?, unlocked: Boolean, now: Long): Boolean {
        val current=state.get()
        val grant=current.grant ?: return false
        return !current.closed && current.version?.let(credentials::current)==true && now<grant.validUntil &&
            owner==grant.owner && overlayOwnsCall(call,expectedId,owner,true,unlocked) && state.get()===current
    }
    companion object { const val LIFETIME_MS=2_000L }
}
