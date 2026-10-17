package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class PlaybackLedgerTest {
    @Test fun writesAheadBeforeHardwareStartsAndOnlyPresentedPrefixIsArchived() {
        val ledger=PlaybackLedger();ledger.wrote(ByteArray(960){1});ledger.wrote(ByteArray(960){2})
        assertEquals(960L,ledger.writtenFrames);assertTrue(ledger.presented(0).isEmpty())
        val prefix=ledger.presented(100).single();assertEquals(200,prefix.pcm.size);assertEquals(0L,prefix.startFrame)
        val remaining=ledger.presented(960);assertEquals(1720,remaining.sumOf { it.pcm.size });assertEquals(100L,remaining.first().startFrame)
        assertEquals(0,ledger.pendingBytes);assertTrue(ledger.presented(960).isEmpty())
    }
    @Test fun interruptedHardwarePrefixIsRetainedAndUnheardTailCannotRevive() {
        val ledger=PlaybackLedger();ledger.wrote(ByteArray(24000))
        assertEquals(480,ledger.presented(240).sumOf { it.pcm.size })
        ledger.reset();assertTrue(ledger.presented(12000).isEmpty());assertEquals(0,ledger.pendingBytes)
        ledger.wrote(ByteArray(960));assertEquals(960,ledger.presented(480).sumOf { it.pcm.size })
    }
    @Test fun unpresentedWritesCannotGrowWithoutBound() {
        val ledger=PlaybackLedger(1920);ledger.wrote(ByteArray(1920))
        assertTrue(runCatching { ledger.wrote(ByteArray(2)) }.isFailure)
        ledger.presented(480);ledger.wrote(ByteArray(960));assertEquals(1920,ledger.pendingBytes)
    }
}
