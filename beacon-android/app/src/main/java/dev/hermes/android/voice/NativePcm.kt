package dev.hermes.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

internal class NativePcm(private val scope:CoroutineScope,private val input:(ByteArray,Long)->Unit,
    private val played:(ByteArray,Long)->Unit,private val failure:(String)->Unit,private val level:(Float,Boolean)->Unit,
    private val queue:PcmQueue=PcmQueue(),private val captureRate:Int=16000) {
    private val downsampler=if(captureRate==48000) Pcm48To24() else null
    private val capture=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    private val output=Channel<Unit>(Channel.CONFLATED)
    private val ledger=PlaybackLedger()
    private val lock=Any()
    private var recorder:AudioRecord?=null
    private var player:AudioTrack?=null
    private var echo:AcousticEchoCanceler?=null
    private var noise:NoiseSuppressor?=null
    private var captureJob:Job?=null
    private var playbackJob:Job?=null
    private var presentationJob:Job?=null
    private var watchdogJob:Job?=null
    private val released=CompletableDeferred<Unit>()
    private val captureObserved=AtomicBoolean(false)
    private val startedNs=System.nanoTime()
    private var playbackEpochNs=System.nanoTime()
    fun clock()=(System.nanoTime()-startedNs)/1_000_000

    fun start(context:Context) {
        if(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) throw SecurityException("Microphone permission unavailable")
        require(captureRate==16000||captureRate==48000)
        val inputSize=AudioRecord.getMinBufferSize(captureRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
        val outputSize=AudioTrack.getMinBufferSize(24000,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT)
        require(inputSize>0&&outputSize>0)
        lateinit var rec:AudioRecord
        lateinit var track:AudioTrack
        try {
        rec=AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,captureRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,maxOf(inputSize,captureRate*2/5))
        recorder=rec;check(rec.state==AudioRecord.STATE_INITIALIZED)
        if(AcousticEchoCanceler.isAvailable()) echo=AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled=true }
        if(NoiseSuppressor.isAvailable()) noise=NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled=true }
        track=AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(outputSize,PLAYBACK_CAPACITY_BYTES)).setTransferMode(AudioTrack.MODE_STREAM).build()
        player=track;check(track.state==AudioTrack.STATE_INITIALIZED)
        // A jitter buffer: speech starts, and resumes after a gap, only with 200 ms queued, so bursty delivery plays
        // smoothly (120 ms with a 50 ms restart still underran 15 times in a live Gemini call). Barge-in still stops at
        // once because interrupt() pauses and flushes the track.
        check(track.setBufferSizeInFrames(PLAYBACK_BUFFER_FRAMES)>0)
        if(Build.VERSION.SDK_INT>=31) track.setStartThresholdInFrames(PLAYBACK_START_FRAMES)
        playbackEpochNs=System.nanoTime();track.play();rec.startRecording()
        } catch(error:Exception) {
            closed.set(true);capture.set(false)
            val failures=releaseResources()
            if(failures.isEmpty()) released.complete(Unit) else released.completeExceptionally(IllegalStateException("Native audio release unconfirmed",failures.first()))
            throw error
        }
        val captureEpochNs=System.nanoTime()
        captureJob=scope.launch(Dispatchers.IO) {
            try {
                val bytes=ByteArray(captureRate*2/50);var readFrames=0L
                while(isActive&&!closed.get()) {
                    val count=rec.read(bytes,0,bytes.size,AudioRecord.READ_BLOCKING)
                    check(count>=0&&count%2==0) { "Microphone read failed" }
                    if(count==0) { delay(5);continue }
                    val firstFrame=readFrames;readFrames+=count/2;captureObserved.set(true)
                    val stamp=AudioTimestamp()
                    val at=if(rec.getTimestamp(stamp,AudioTimestamp.TIMEBASE_MONOTONIC)==AudioRecord.SUCCESS&&stamp.nanoTime>=captureEpochNs)
                        (stamp.nanoTime-startedNs)/1_000_000-(stamp.framePosition-firstFrame)*1000/captureRate
                    else clock()-count*1000L/(captureRate*2)
                    synchronized(lock) { if(capture.get()&&!closed.get()) {
                        val copy=bytes.copyOf(count)
                        val pcm=downsampler?.convert(copy)?:copy
                        if(pcm.isNotEmpty()) { input(pcm,at);level(amplitude(pcm),true) }
                    } }
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) { if(!closed.get()) failure("Microphone capture failed") }
        }
        watchdogJob=scope.launch { delay(5000);if(!captureObserved.get()&&!closed.get()) failure("Native microphone did not produce audio") }
        // Feed hardware without waiting for presentation of each 20ms write.
        playbackJob=scope.launch(Dispatchers.IO) {
            try {
                for(signal in output) while(true) {
                    val frame=queue.take()?:break;var offset=0
                    while(offset<frame.pcm.size&&!closed.get()) {
                        val count=synchronized(lock) {
                            if(frame.epoch!=queue.epoch) -1 else {
                                val written=track.write(frame.pcm,offset,minOf(960,frame.pcm.size-offset),AudioTrack.WRITE_NON_BLOCKING)
                                check(written>=0&&written%2==0) { "Speaker write failed" }
                                if(written>0) ledger.wrote(frame.pcm.copyOfRange(offset,offset+written))
                                written
                            }
                        }
                        if(count<0) break
                        if(count==0) delay(2) else offset+=count
                    }
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) { if(!closed.get()) failure("Speaker playback failed") }
        }
        presentationJob=scope.launch(Dispatchers.IO) {
            try {
                var lastEpoch=queue.epoch;var lastHead=0L;var progressAt=SystemClock.elapsedRealtime()
                while(isActive&&!closed.get()) {
                    synchronized(lock) {
                        if(!closed.get()) {
                            val head=track.playbackHeadPosition.toLong() and 0xffffffffL
                            val now=SystemClock.elapsedRealtime()
                            if(lastEpoch!=queue.epoch||head!=lastHead||ledger.pendingBytes==0) { progressAt=now;lastEpoch=queue.epoch;lastHead=head }
                            check(ledger.pendingBytes==0||now-progressAt<5000) { "Playback hardware stopped advancing" }
                            confirmPresentation(track,head)
                            if(ledger.pendingBytes==0) level(0f,false)
                        }
                    }
                    delay(5)
                }
            } catch(error:CancellationException) { throw error }
            catch(error:Exception) { if(!closed.get()) failure("Speaker playback failed") }
        }
    }
    fun mute(value:Boolean)=synchronized(lock) { capture.set(!value&&!closed.get()) }
    fun enqueue(bytes:ByteArray):Boolean=synchronized(lock) { if(closed.get()||!queue.offer(bytes)) false else { output.trySend(Unit);true } }
    private fun confirmPresentation(track:AudioTrack,head:Long) {
        val stamp=AudioTimestamp()
        val stamped=track.getTimestamp(stamp)&&stamp.nanoTime>=playbackEpochNs
        for(slice in ledger.presented(head)) {
            val target=slice.startFrame+slice.pcm.size/2
            val at=if(stamped) (stamp.nanoTime-startedNs)/1_000_000-(stamp.framePosition-slice.startFrame)*1000/24000
                else clock()-(head-target)*1000/24000-slice.pcm.size*1000L/48000
            played(slice.pcm,at);level(amplitude(slice.pcm),false)
        }
    }
    fun interrupt()=synchronized(lock) {
        player?.pause();player?.let { confirmPresentation(it,it.playbackHeadPosition.toLong() and 0xffffffffL) }
        queue.interrupt();ledger.reset();player?.flush();playbackEpochNs=System.nanoTime()
        if(!closed.get()) player?.play()
        level(0f,false)
    }
    private fun releaseResources():List<Throwable> {
        val errors=listOf(runCatching { recorder?.release();recorder=null }.exceptionOrNull(),runCatching { player?.release();player=null }.exceptionOrNull()).filterNotNull()
        runCatching { echo?.release();echo=null };runCatching { noise?.release();noise=null }
        return errors
    }
    suspend fun close()=withContext(NonCancellable+Dispatchers.IO) {
        if(!closed.compareAndSet(false,true)) { released.await();return@withContext }
        try {
            mute(true);runCatching { interrupt() };output.close();watchdogJob?.cancel()
            // Stop/unblock read first, then join off-main before releasing native objects.
            runCatching { recorder?.stop() };runCatching { player?.pause();player?.flush() }
            // A count of zero means the call's speech played without gaps.
            player?.let { runCatching { android.util.Log.i("BeaconAudio","call playback underruns=${it.underrunCount}") } }
            val jobs=listOfNotNull(captureJob,playbackJob,presentationJob)
            jobs.forEach { it.cancel() }
            check(withTimeoutOrNull(2000) { jobs.joinAll();true }==true) { "Native audio threads did not stop; release unconfirmed" }
            val errors=releaseResources()
            if(errors.isNotEmpty()) throw IllegalStateException("Native audio release unconfirmed",errors.first())
            released.complete(Unit)
        } catch(error:Exception) { released.completeExceptionally(error);throw error }
    }
    private fun amplitude(bytes:ByteArray):Float {
        var peak=0
        for(i in 0 until bytes.size-1 step 2) { val sample=((bytes[i].toInt() and 255) or (bytes[i+1].toInt() shl 8)).toShort().toInt();peak=maxOf(peak,kotlin.math.abs(sample)) }
        return peak/32768f
    }
}

/** Pair averaging gives OpenAI's 24 kHz input a simple low-pass before decimation. */
internal class Pcm48To24 {
    private var pending: Short? = null

    fun convert(input: ByteArray): ByteArray {
        require(input.size % 2 == 0)
        val samples=input.size/2
        val output=ByteArray(((samples+(if(pending==null) 0 else 1))/2)*2)
        var first=pending
        pending=null
        var offset=0
        for(index in 0 until samples) {
            val sample=((input[index*2].toInt() and 255) or (input[index*2+1].toInt() shl 8)).toShort()
            if(first==null) first=sample else {
                val average=((first.toInt()+sample.toInt())/2).toShort()
                output[offset++]=average.toInt().toByte()
                output[offset++]=(average.toInt() shr 8).toByte()
                first=null
            }
        }
        pending=first
        return if(offset==output.size) output else output.copyOf(offset)
    }
}

private const val PLAYBACK_CAPACITY_BYTES=24000 // 500 ms at 24 kHz mono 16-bit
private const val PLAYBACK_BUFFER_FRAMES=9600   // 400 ms at 24 kHz
private const val PLAYBACK_START_FRAMES=4800    // 200 ms at 24 kHz
