package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class PcmQueueTest {
    @Test fun interruptedPlaybackCopiesOnlyHardwarePresentedPrefix() {
        assertEquals(0,playedPcmBytes(99,100,960))
        assertEquals(240,playedPcmBytes(220,100,960))
        assertEquals(960,playedPcmBytes(900,100,960))
    }
    @Test fun byteBudgetRejectsHugeAndOddFramesAndCopiesCallerMemory() {
        val queue=PcmQueue(8);val source=byteArrayOf(1,2,3,4)
        assertTrue(queue.offer(source));source[0]=99
        assertTrue(queue.offer(ByteArray(4)));assertFalse(queue.offer(ByteArray(2)));assertFalse(queue.offer(ByteArray(1)))
        assertEquals(1,queue.take()!!.pcm[0].toInt());assertTrue(queue.offer(ByteArray(4)))
    }
    @Test fun validGeminiBurstFitsWithoutInterruptingSpeechAndRemainsBounded() {
        val queue=PcmQueue()
        val largest=ByteArray(192000)
        assertTrue(GeminiWire.validAudio("audio/pcm;rate=24000",largest.size))
        repeat(15) { assertTrue("Full replies must fit while hardware plays immediately",queue.offer(largest)) }
        assertFalse("Unbounded buffering is forbidden",queue.offer(ByteArray(2)))
        val inFlight=queue.take()!!
        queue.interrupt()
        assertNull(queue.take());assertNotEquals(inFlight.epoch,queue.epoch)
        assertTrue(queue.offer(largest))
    }
    @Test fun interruptionDropsQueuedAudioAndInvalidatesDequeuedFrames() {
        val queue=PcmQueue();queue.offer(ByteArray(640));val old=queue.take()!!;queue.offer(ByteArray(640))
        queue.interrupt();assertNull(queue.take());assertNotEquals(old.epoch,queue.epoch)
        queue.offer(ByteArray(640));assertEquals(queue.epoch,queue.take()!!.epoch)
    }
    @Test fun fastGeneratedSpeechFitsWithoutDelayingFirstFrameAndInterruptStillClearsIt() {
        val queue=PcmQueue()
        val frame=ByteArray(48000)
        repeat(60) { assertTrue(queue.offer(frame)) }
        assertFalse("Grok queue is bounded to one minute",queue.offer(byteArrayOf(0,0)))
        val first=queue.take()!!;assertEquals(frame.size,first.pcm.size)
        queue.interrupt();assertNull(queue.take());assertNotEquals(first.epoch,queue.epoch)
        assertTrue(queue.offer(frame))
    }
    @Test fun aLongGeminiEmailReadoutDoesNotLoseItsQueuedSpeech() {
        val queue=PcmQueue()
        repeat(30) { assertTrue("Thirty seconds of generated speech must stay intact",queue.offer(ByteArray(48000))) }
    }
}
