package dev.hermes.android.voice

internal fun playedPcmBytes(head:Long,startFrame:Long,availableBytes:Int):Int =
    ((head-startFrame).coerceIn(0,availableBytes/2L)*2).toInt()

// Both providers can burst a full reply. Start immediately; retain at most 60 seconds in RAM.
internal class PcmQueue(private val limit:Int=60*48000) {
    class Frame(val epoch:Long,val pcm:ByteArray)
    private val frames=ArrayDeque<Frame>()
    private var bytes=0
    @Volatile var epoch=0L;private set
    @Synchronized fun offer(pcm:ByteArray):Boolean {
        if(pcm.isEmpty()||pcm.size%2!=0||pcm.size>limit-bytes) return false
        frames.addLast(Frame(epoch,pcm.copyOf()));bytes+=pcm.size;return true
    }
    @Synchronized fun take():Frame? = frames.removeFirstOrNull()?.also { bytes-=it.pcm.size }
    @Synchronized fun interrupt() { epoch++;frames.clear();bytes=0 }
}
