package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import dev.hermes.android.data.CredentialEpoch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OverlayAuthorityTest {
    private val owner=Scope("https://synthetic.invalid","owner","default")
    private val call=CallState(active=true,callId="current",conversationScope=owner.session("chat"))
    private fun allowed(authority: OverlayAuthority, actor: Scope?=owner, unlocked: Boolean=true, now: Long=100) =
        authority.permits(call,"current",actor,unlocked,now)

    @Test(timeout=10_000) fun dragCheckDoesNotReadCryptoOrWaitForSlowEncryptedWrite() = runBlocking {
        val epoch=CredentialEpoch();val monitor=Any();val calls=AtomicInteger()
        val authority=OverlayAuthority(epoch) { synchronized(monitor) { calls.incrementAndGet();OverlayAuthorization(owner,2000) } }
        authority.refresh();assertTrue(allowed(authority));assertEquals(1,calls.get())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val writer=async(Dispatchers.IO) { epoch.mutate { synchronized(monitor) { entered.countDown();release.await() } } }
        val main=Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"synthetic-main-drag") }
        try {
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            // The monitor remains held until after this future completes. No timing/performance claim.
            assertFalse(main.submit<Boolean>{ allowed(authority) }.get(1,TimeUnit.SECONDS))
            authority.refresh();assertEquals("Busy credentials must not invoke decrypt",1,calls.get())
        } finally { release.countDown();writer.await();main.shutdownNow() }
        assertFalse("Completed write cannot revive the old snapshot",allowed(authority))
        authority.refresh();assertTrue(allowed(authority))
    }
    @Test(timeout=10_000) fun slowReadIsOffCallerThreadAndCannotPublishAfterLogoutInvalidation() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val mainThread=Thread.currentThread()
        val authority=OverlayAuthority(CredentialEpoch()) {
            assertNotSame(mainThread,Thread.currentThread());entered.countDown();release.await();OverlayAuthorization(owner,2000)
        }
        val read=async { authority.refresh() }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
            authority.invalidate();assertFalse(allowed(authority));assertFalse(allowed(authority,actor=null))
        } finally { release.countDown();read.await() }
        assertFalse("Late pre-logout read must stay invalid",allowed(authority))
    }
    @Test(timeout=10_000) fun invalidationBeforeBackgroundReadAllowsTheFirstGrantWithoutRetry() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val reads=AtomicInteger()
        val authority=OverlayAuthority(CredentialEpoch()) {
            reads.incrementAndGet();entered.countDown();release.await();OverlayAuthorization(owner,2000)
        }
        authority.invalidate()
        val read=async { authority.refresh() }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
            assertFalse("An unfinished read cannot authorize the bubble",allowed(authority))
        } finally { release.countDown();read.await() }
        assertTrue("The first read started after invalidation must publish",allowed(authority))
        assertEquals(1,reads.get())
        authority.invalidate();assertFalse("Later replacement still revokes immediately",allowed(authority))
    }
    @Test fun cachedWrongOwnerProfileOrOriginCannotAuthorizeTheCurrentCall() = runBlocking {
        for(wrong in listOf(owner.copy(owner="other"),owner.copy(profile="work"),owner.copy(origin="https://other.invalid"))) {
            val authority=OverlayAuthority(CredentialEpoch()) { OverlayAuthorization(wrong,2000) }
            authority.refresh();assertFalse(allowed(authority));assertFalse(allowed(authority,actor=wrong))
        }
    }
    @Test fun lockOwnerReplacementExpiryAndCurrentCallFenceAreImmediate() = runBlocking {
        val authority=OverlayAuthority(CredentialEpoch()) { OverlayAuthorization(owner,2000) }
        assertFalse(allowed(authority));authority.refresh();assertTrue(allowed(authority))
        assertFalse(allowed(authority,unlocked=false));assertFalse(allowed(authority,actor=null))
        assertFalse(allowed(authority,actor=owner.copy(owner="replacement")))
        assertFalse(allowed(authority,actor=owner.copy(profile="other")))
        assertFalse(allowed(authority,now=2000))
        assertFalse(authority.permits(call,"replaced",owner,true,100))
        assertFalse(authority.permits(call.copy(active=false),"current",owner,true,100))
    }
    @Test(timeout=10_000) fun slowReadCannotPublishAfterCredentialMutationOrServiceClose() = runBlocking {
        for(close in listOf(false,true)) {
            val epoch=CredentialEpoch();val entered=CountDownLatch(1);val release=CountDownLatch(1)
            val authority=OverlayAuthority(epoch) { entered.countDown();release.await();OverlayAuthorization(owner,2000) }
            val read=async { authority.refresh() }
            try {
                withContext(Dispatchers.IO) { assertTrue(entered.await(3,TimeUnit.SECONDS)) }
                if(close) authority.close() else epoch.mutate { Unit }
            } finally { release.countDown();read.await() }
            assertFalse(allowed(authority))
            if(close) { authority.invalidate();authority.refresh();assertFalse(allowed(authority)) }
        }
    }
    @Test(timeout=10_000) fun overlappingAndFailedWritesRemainFailClosed() = runBlocking {
        val epoch=CredentialEpoch();val original=epoch.readVersion()!!;val first=CountDownLatch(1);val second=CountDownLatch(1)
        val releaseFirst=CountDownLatch(1);val releaseSecond=CountDownLatch(1)
        val a=async(Dispatchers.IO) { epoch.mutate { first.countDown();releaseFirst.await() } }
        val b=async(Dispatchers.IO) { epoch.mutate { second.countDown();releaseSecond.await() } }
        try {
            assertTrue(first.await(3,TimeUnit.SECONDS));assertTrue(second.await(3,TimeUnit.SECONDS))
            assertNull(epoch.readVersion());assertFalse(epoch.current(original))
            releaseFirst.countDown();a.await();assertNull(epoch.readVersion())
        } finally { releaseFirst.countDown();releaseSecond.countDown();a.await();b.await() }
        val beforeFailure=epoch.readVersion()!!
        try { epoch.mutate { error("synthetic encryption failure") };fail() } catch (_: IllegalStateException) { }
        assertFalse(epoch.current(beforeFailure));assertTrue(epoch.current(epoch.readVersion()!!))
    }
}
