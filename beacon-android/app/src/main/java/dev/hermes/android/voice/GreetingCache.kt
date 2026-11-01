package dev.hermes.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.VoiceSessionCredentials
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Cache layout version; a change leaves older files unread until [GreetingCache.prune] removes them. */
internal const val GREETING_FORMAT = "greeting-v1"
/** Refresh renders allowed per account in any 24 hours; a call's own greeting fills its line for free. */
internal const val GREETING_RENDERS_PER_DAY = 8

/**
 * Everything a cached greeting depends on. Another account, profile, owner name, provider, model, voice, personality or
 * phone key never plays it: each lands in another directory, or the plan does not match.
 */
internal data class GreetingIdentity(val account: Scope, val ownerName: String, val provider: String, val model: String,
    val voice: String, val personality: String, val keyRevision: Long) {
    // Digests only: directory names carry no account, voice or name details.
    val accountDir: String get() = JSONArray(listOf(GREETING_FORMAT, account.origin, account.nativeProvider, account.owner, account.profile)).toString().digest()
    val voiceDir: String get() = JSONArray(listOf(GREETING_FORMAT, provider, model, GeminiSpeech.MODEL, voice, personality, keyRevision)).toString().digest()
    companion object {
        fun of(scope: Scope, ownerName: String, credentials: VoiceSessionCredentials) = GreetingIdentity(scope.copy(session = ""), ownerName.trim(),
            credentials.provider, credentials.model, credentials.voice, credentials.personality, credentials.keyRevision)
    }
}

/** The ordinary call's opening line for each hour of the profile's day, in its time zone; a blank hour has no known line. */
internal class GreetingPlan(val ownerName: String, val zone: String, val lines: List<String>) {
    init { require(lines.size == 24) }
    fun lineAt(now: Long): String? = lines[hourAt(now, zone)].ifBlank { null }
    fun json(): JSONObject = JSONObject().put("owner_name", ownerName).put("zone", zone).put("lines", JSONArray(lines))
    companion object {
        private fun zoneOf(zone: String): ZoneId? = runCatching { ZoneId.of(zone) }.getOrNull()
        fun hourAt(now: Long, zone: String): Int = Instant.ofEpochMilli(now).atZone(zoneOf(zone) ?: ZoneId.systemDefault()).hour
        private fun line(value: String) = value.trim().takeIf { it.isNotEmpty() && it.length <= 160 }
        fun read(value: JSONObject): GreetingPlan? = runCatching {
            val lines = value.getJSONArray("lines")
            require(lines.length() == 24)
            GreetingPlan(value.getString("owner_name"), value.getString("zone").also { require(zoneOf(it) != null) },
                List(24) { lines.getString(it).let { text -> if (text.isEmpty()) "" else requireNotNull(line(text)) } })
        }.getOrNull()
        /**
         * The plan after a verified call context: the plugin's `greeting_plan` (every line of the profile's day) when it
         * sends one; an older plugin's line is known only for this hour of the phone's day.
         */
        fun observed(context: JSONObject, spoken: String, ownerName: String, previous: GreetingPlan?, now: Long, phoneZone: String): GreetingPlan? {
            context.optJSONObject("greeting_plan")?.let { plan -> fromServer(plan, ownerName)?.let { return it } }
            val text = line(spoken) ?: return previous
            val kept = previous?.takeIf { it.zone == phoneZone }?.lines ?: List(24) { "" }
            val hour = hourAt(now, phoneZone)
            return GreetingPlan(ownerName, phoneZone, kept.mapIndexed { index, old -> if (index == hour) text else old })
        }
        private fun fromServer(plan: JSONObject, ownerName: String): GreetingPlan? = runCatching {
            val zone = plan.getString("zone").also { require(zoneOf(it) != null) }
            val variants = plan.getJSONArray("variants")
            require(variants.length() in 1..24)
            val starts = (0 until variants.length()).map { index ->
                val item = variants.getJSONObject(index)
                item.getInt("from_hour").also { require(it in 0..23) } to requireNotNull(line(item.getString("text")))
            }.sortedBy { it.first }
            require(starts.first().first == 0 && starts.map { it.first }.distinct().size == starts.size)
            GreetingPlan(ownerName, zone, List(24) { hour -> starts.last { it.first <= hour }.second })
        }.getOrNull()
    }
}

/**
 * Synthesized greeting audio on this phone, under `noBackupFilesDir`: per account and profile, per voice, one 24 kHz PCM
 * file per line. It holds only assistant greetings rendered with the owner's own voice settings, never the owner's
 * speech or call audio. Signed-out accounts, other profiles and old voices are deleted ([prune], [store]).
 */
internal class GreetingCache(private val root: File, private val now: () -> Long = System::currentTimeMillis,
    private val phoneZone: () -> String = { ZoneId.systemDefault().id }) {
    private fun accountDir(id: GreetingIdentity) = File(root, id.accountDir)
    private fun voiceDir(id: GreetingIdentity) = File(accountDir(id), id.voiceDir)
    private fun audioFile(id: GreetingIdentity, text: String) = File(voiceDir(id), text.digest() + ".pcm")
    private fun readJson(file: File): JSONObject? = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    private fun writeAtomically(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) { "Greeting cache write failed" } }
    }

    @Synchronized fun plan(id: GreetingIdentity): GreetingPlan? =
        readJson(File(accountDir(id), "plan.json"))?.let(GreetingPlan::read)?.takeIf { it.ownerName == id.ownerName }

    /** The line for this hour and its audio, ready to play at the tap; null when this hour has none. */
    @Synchronized fun ready(id: GreetingIdentity): Pair<String, ByteArray>? {
        val text = plan(id)?.lineAt(now()) ?: return null
        val file = audioFile(id, text)
        val pcm = runCatching { file.readBytes() }.getOrNull() ?: return null
        return if (playable(pcm)) text to pcm else { file.delete(); null }
    }

    /** Records the lines a verified call context promises; [spoken] is the greeting this call speaks. */
    @Synchronized fun observe(id: GreetingIdentity, context: JSONObject, spoken: String) {
        val next = GreetingPlan.observed(context, spoken, id.ownerName, plan(id), now(), phoneZone()) ?: return
        val account = JSONObject().put("origin", id.account.origin).put("owner", id.account.owner).put("profile", id.account.profile)
            .put("native_provider", id.account.nativeProvider)
        writeAtomically(File(accountDir(id), "account.json"), account.toString().toByteArray(Charsets.UTF_8))
        writeAtomically(File(accountDir(id), "plan.json"), next.json().toString().toByteArray(Charsets.UTF_8))
        val keep = next.lines.filter(String::isNotEmpty).map { it.digest() + ".pcm" }.toSet()
        voiceDir(id).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
    }

    /** Keeps [pcm] as the audio of [text] for this voice, without its leading silence; other voices' audio is removed. */
    @Synchronized fun store(id: GreetingIdentity, text: String, pcm: ByteArray) {
        val trimmed = withoutLeadingSilence(pcm)
        if (text.isBlank() || text.length > 160 || !playable(trimmed)) return
        accountDir(id).listFiles()?.filter { it.isDirectory && it.name != id.voiceDir }?.forEach { it.deleteRecursively() }
        writeAtomically(audioFile(id, text), trimmed)
    }

    /** Lines of this hour's plan that have no audio yet, within the day's render allowance; this hour's line comes first. */
    @Synchronized fun missing(id: GreetingIdentity): List<String> {
        val plan = plan(id) ?: return emptyList()
        val current = plan.lineAt(now())
        val wanted = (listOfNotNull(current) + plan.lines.filter(String::isNotEmpty)).distinct().filter { !audioFile(id, it).isFile }
        return wanted.take((GREETING_RENDERS_PER_DAY - renders(id).size).coerceAtLeast(0))
    }

    /** Counts one refresh render against today's allowance. */
    @Synchronized fun spend(id: GreetingIdentity) {
        val kept = renders(id) + now()
        writeAtomically(File(accountDir(id), "renders.json"), JSONArray(kept).toString().toByteArray(Charsets.UTF_8))
    }
    private fun renders(id: GreetingIdentity): List<Long> {
        val raw = runCatching { JSONArray(File(accountDir(id), "renders.json").readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val since = now() - 24 * 3_600_000L
        return (0 until raw.length()).map { raw.optLong(it) }.filter { it in since..now() }
    }

    /** Deletes every account directory whose account is no longer signed in on this profile ([signedIn] is false). */
    @Synchronized fun prune(signedIn: (Scope) -> Boolean) {
        for (dir in root.listFiles().orEmpty()) {
            val account = readJson(File(dir, "account.json"))?.let {
                runCatching { Scope(it.getString("origin"), it.getString("owner"), it.getString("profile"), nativeProvider = it.getString("native_provider")) }.getOrNull()
            }
            val keep = account != null && dir.isDirectory && dir.name == GreetingIdentity(account, "", "", "", "", "", 0).accountDir &&
                runCatching { signedIn(account) }.getOrDefault(false)
            if (!keep) dir.deleteRecursively()
        }
    }

    companion object {
        /** 24 kHz mono 16-bit, at most 60 seconds, as the call's own speech. */
        fun playable(pcm: ByteArray) = pcm.size >= 2 * 2400 && pcm.size % 2 == 0 && pcm.size <= GeminiSpeech.MAX_PCM_BYTES
        /** Rendered speech often opens with silence; the tap should hear the first word, so it starts 20 ms before it. */
        fun withoutLeadingSilence(pcm: ByteArray, threshold: Int = 500): ByteArray {
            var first = 0
            while (first + 1 < pcm.size) {
                val sample = ((pcm[first].toInt() and 255) or (pcm[first + 1].toInt() shl 8)).toShort().toInt()
                if (kotlin.math.abs(sample) >= threshold) break
                first += 2
            }
            if (first + 1 >= pcm.size) return ByteArray(0)
            return pcm.copyOfRange((first - 2 * 480).coerceAtLeast(0), pcm.size)
        }
    }
}

/** Where the early greeting is heard. The call uses a voice-call [AudioTrack]; tests use a fake. */
internal interface GreetingOutput {
    /** Starts presenting [pcm] (24 kHz mono 16-bit); false when the device refused it. */
    fun start(pcm: ByteArray): Boolean
    /** Frames presented so far. */
    fun presented(): Long
    fun release()
}

/** A static voice-communication track: the whole greeting is written once, then played, so the first frame needs no network. */
internal class TrackGreetingOutput : GreetingOutput {
    private var track: AudioTrack? = null
    @Synchronized override fun start(pcm: ByteArray): Boolean = runCatching {
        val built = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(GeminiWire.OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(pcm.size).setTransferMode(AudioTrack.MODE_STATIC).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build()
        track = built
        check(built.write(pcm, 0, pcm.size) == pcm.size && built.state == AudioTrack.STATE_INITIALIZED)
        built.play()
        true
    }.getOrDefault(false)
    @Synchronized override fun presented(): Long = track?.let { runCatching { it.playbackHeadPosition.toLong() and 0xffffffffL }.getOrNull() } ?: 0L
    @Synchronized override fun release() {
        track?.let { runCatching { it.pause() }; runCatching { it.release() } }
        track = null
    }
}

/**
 * The cached greeting, heard from the tap while the grant, provider and context start exactly as before. It is the
 * call's one greeting: at admission the call [join]s it instead of rendering another. Until then the call shows its
 * connecting state; nothing here sends audio anywhere.
 */
internal class EarlyGreeting(private val elapsedMs: () -> Long, private val level: (EarlyGreeting, Float) -> Unit = { _, _ -> },
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private var stage = LOADING // guarded by lock
    private var onAudio: ((Long) -> Unit)? = null
    private var onEnd: ((Int) -> Unit)? = null
    private var output: GreetingOutput? = null
    private var job: Job? = null
    @Volatile var text = ""; private set
    /** First presented frame, and the end, on the call's tap clock; -1 until then. */
    @Volatile var firstAudioMs = -1L; private set
    @Volatile var endedMs = -1L; private set
    /** False once it cannot be heard: nothing cached, the device refused it, or the call stopped it first. */
    val available: Boolean get() = synchronized(lock) { stage != FAILED && stage != STOPPED }
    val speaking: Boolean get() = synchronized(lock) { stage == PLAYING }

    fun begin(scope: CoroutineScope, openOutput: () -> GreetingOutput, load: suspend () -> Pair<String, ByteArray>?) {
        val started = scope.launch {
            val ready = try { load() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }
            if (ready == null) { end(FAILED); return@launch }
            val sink = try { openOutput() } catch (_: Exception) { end(FAILED); return@launch }
            val claimed = synchronized(lock) { if (stage != LOADING) false else { text = ready.first; output = sink; true } }
            if (!claimed) { sink.release(); return@launch }
            if (!sink.start(ready.second)) { end(FAILED); return@launch }
            watch(sink, ready.second)
        }
        synchronized(lock) { job = started }
    }

    private suspend fun watch(sink: GreetingOutput, pcm: ByteArray) {
        val frames = pcm.size / 2L
        var head = 0L; var progressAt = clock(); var levelAt = Long.MIN_VALUE
        while (currentCoroutineContext().isActive) {
            val now = clock()
            val presented = sink.presented().coerceIn(0, frames)
            if (presented > head) { head = presented; progressAt = now }
            if (head > 0) firstAudio()
            if (levelAt == Long.MIN_VALUE || now - levelAt >= 40) { levelAt = now; if (head > 0) level(this, peak(pcm, head)) }
            if (head >= frames) { end(DONE); return }
            // A track that never starts gives the opening back to the call; one that stops mid-line was cut.
            if (now - progressAt > if (head == 0L) START_LIMIT_MS else STALL_LIMIT_MS) { end(if (head == 0L) FAILED else CUT); return }
            delay(10)
        }
    }

    private fun firstAudio() {
        val notify = synchronized(lock) {
            if (stage != LOADING || firstAudioMs >= 0) return
            firstAudioMs = elapsedMs(); stage = PLAYING
            onAudio.also { onAudio = null }
        }
        notify?.invoke(firstAudioMs)
    }

    private fun end(result: Int) {
        val (notify, sink) = synchronized(lock) {
            if (stage !in setOf(LOADING, PLAYING)) return
            stage = if (result == FAILED && firstAudioMs >= 0) CUT else result
            endedMs = elapsedMs(); onAudio = null
            (onEnd.also { onEnd = null }) to output.also { output = null }
        }
        sink?.release()
        level(this, 0f)
        notify?.invoke(synchronized(lock) { stage })
    }

    /**
     * The call takes this line as its greeting: [onAudio] gets the first presented frame's time (at once if already heard),
     * [onEnd] the outcome ([DONE], [CUT], [STOPPED] or [FAILED]). False when it can no longer be heard.
     */
    fun join(onAudio: (Long) -> Unit, onEnd: (Int) -> Unit): Boolean {
        val (heard, ended) = synchronized(lock) {
            when (stage) {
                FAILED, STOPPED -> return false
                LOADING -> { this.onAudio = onAudio; this.onEnd = onEnd; null to null }
                PLAYING -> { this.onEnd = onEnd; firstAudioMs to null }
                else -> firstAudioMs to stage
            }
        }
        heard?.let(onAudio)
        ended?.let(onEnd)
        return true
    }

    /** Silences it now: hang-up, failure, hold or the owner talking over it. */
    fun stop() {
        end(if (firstAudioMs >= 0) CUT else STOPPED)
        synchronized(lock) { job }?.cancel()
    }

    private fun peak(pcm: ByteArray, frame: Long): Float {
        var peak = 0
        val start = (frame * 2).toInt().coerceIn(0, pcm.size)
        val end = minOf(pcm.size - 1, start + 2 * 960)
        var index = start
        while (index < end) {
            val sample = ((pcm[index].toInt() and 255) or (pcm[index + 1].toInt() shl 8)).toShort().toInt()
            peak = maxOf(peak, kotlin.math.abs(sample)); index += 2
        }
        return peak / 32768f
    }

    companion object {
        const val LOADING = 0; const val PLAYING = 1
        /** Heard to its end. */
        const val DONE = 2
        /** Heard, then silenced before its end. */
        const val CUT = 3
        /** Silenced before its first frame. */
        const val STOPPED = 4
        /** Could not be heard: no cached line, or the device refused or never started it. */
        const val FAILED = 5
        const val START_LIMIT_MS = 1_500L
        const val STALL_LIMIT_MS = 2_000L
    }
}

/**
 * The cached greeting's level drives the presence, but never the call's word for its state: connecting (or thinking)
 * stays until the provider is admitted with a verified context; after that it reads as speaking, like any reply.
 */
internal fun CallState.withGreetingLevel(level: Float): CallState {
    val normalized = normalizedVoiceLevel(level) ?: return this
    val talking = normalized > .01f
    return copy(remoteAudioLevel = normalized.takeIf { it > 0f }, remoteSpeaking = talking,
        state = if (state == "listening" || state == "speaking") (if (talking) "speaking" else "listening") else state)
}

/** One outgoing call's link to the cache: the line heard since the tap, and where verified lines and rendered greeting audio go. */
internal class CallGreeting(val early: EarlyGreeting?, private val observe: (JSONObject, String) -> Unit = { _, _ -> },
    private val store: (String, ByteArray) -> Unit = { _, _ -> }) {
    fun observed(context: JSONObject, spoken: String) { runCatching { observe(context, spoken) } }
    fun rendered(text: String, pcm: ByteArray) { runCatching { store(text, pcm) } }
}
