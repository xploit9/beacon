package dev.hermes.android.voice

/** Accepted writes are distinct from hardware presentation; callers serialize with AudioTrack. */
internal class PlaybackLedger(private val limit:Int=96000) {
    data class Presented(val startFrame:Long,val pcm:ByteArray)
    private data class Segment(val startFrame:Long,val pcm:ByteArray,var consumed:Int=0)
    private val segments=ArrayDeque<Segment>()
    var writtenFrames=0L;private set
    var pendingBytes=0;private set
    fun wrote(pcm:ByteArray) {
        require(pcm.isNotEmpty()&&pcm.size%2==0&&pcm.size<=limit-pendingBytes)
        segments.addLast(Segment(writtenFrames,pcm.copyOf()));writtenFrames+=pcm.size/2;pendingBytes+=pcm.size
    }
    fun presented(head:Long):List<Presented> {
        val result=mutableListOf<Presented>()
        while(true) {
            val segment=segments.firstOrNull()?:break
            val end=playedPcmBytes(head,segment.startFrame,segment.pcm.size)
            if(end<=segment.consumed) break
            result+=Presented(segment.startFrame+segment.consumed/2,segment.pcm.copyOfRange(segment.consumed,end))
            pendingBytes-=end-segment.consumed;segment.consumed=end
            if(end==segment.pcm.size) segments.removeFirst() else break
        }
        return result
    }
    fun reset() { segments.clear();writtenFrames=0;pendingBytes=0 }
}
