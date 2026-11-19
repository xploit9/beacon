package dev.hermes.agentphone.helper.core

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/** L9's contract and vectors, copied byte for byte into the test resources; their sha256 is pinned here. */
object Fixtures {
    const val VECTORS_SHA256 = "71bbafebdd970ebcea9b94f262611a4f3459ab88f091a578ff79e31df0f58d97"
    const val CONTRACT_SHA256 = "1208dbf4354157cb289837a9aba8eea0f52b74ade1c2ed13ec235ec9bdace32f"

    fun bytes(name: String): ByteArray =
        Fixtures::class.java.getResourceAsStream("/channel-v1/$name")!!.use { it.readBytes() }

    @Suppress("UNCHECKED_CAST")
    val vectors: Map<String, Any?> by lazy { Json.parseUtf8(bytes("agent_phone_channel_v1_vectors.json")) as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    val handshake: Map<String, Any?> get() = vectors["handshake"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    val sealed: List<Map<String, Any?>> get() = vectors["sealed"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val negative: List<Map<String, Any?>> get() = vectors["negative"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    val notifications: Map<String, Any?> get() = vectors["notifications"] as Map<String, Any?>

    fun h(key: String): ByteArray = hex(handshake[key] as String)

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun sha256(b: ByteArray) = hexOf(MessageDigest.getInstance("SHA-256").digest(b))

    val HELLO: Map<String, Any?> = linkedMapOf("t" to "hello", "proto_min" to 1, "proto_max" to 1, "helper_version" to "1.2.1", "version_code" to 121)
}

/** A Conn over a real loopback socket (tests only; the helper itself dials 10.0.2.2). */
class SocketConn(private val socket: Socket) : Conn {
    override val input: InputStream = socket.getInputStream()
    override val output = BufferedOutputStream(socket.getOutputStream())
    override fun timeout(ms: Int) { socket.soTimeout = ms }
    override fun close() { socket.close() }
}

/** The host end of the contract, built from the same Wire functions, for driving the helper's end in tests. */
class TestHost(private val token: ByteArray, private val ns: ByteArray = ByteArray(32) { (0x40 + it).toByte() }) : Closeable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val dialer = Dialer { port -> SocketConn(Socket(InetAddress.getLoopbackAddress(), port)) }

    fun accept(timeoutMs: Int = 5000): HostConn {
        server.soTimeout = timeoutMs
        return HostConn(server.accept(), token, ns)
    }

    override fun close() = server.close()
}

class HostConn(val socket: Socket, private val token: ByteArray, private val ns: ByteArray) : Closeable {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()
    private var kH2C = ByteArray(0)
    private var kC2H = ByteArray(0)
    private var sendSeq = 0L
    private var recvSeq = 0L
    lateinit var ch: ByteArray

    init { socket.soTimeout = 5000 }

    /** Reads CH, answers SH (proved with [proofToken]), reads CP: true when the helper's proof holds. */
    fun handshake(proofToken: ByteArray = token): Boolean {
        ch = readBody(Wire.CH_LEN)
        val nc = Wire.readClientHello(ch)
        val ka = Wire.authKey(proofToken)
        val sh0 = byteArrayOf(0x42, 0x41, 0x50, 0x43, 1) + ns
        val ps = Wire.hostProof(ka, ch, sh0)
        writeRaw(Wire.frame(sh0 + ps))
        val cp = try { readBody(Wire.CP_LEN) } catch (e: EOFException) { return false }
        if (!MessageDigest.isEqual(cp, Wire.helperProof(Wire.authKey(token), ch, sh0, ps))) return false
        val keys = Wire.linkKeys(token, nc, ns)
        kH2C = keys.first
        kC2H = keys.second
        return true
    }

    fun readBody(expected: Int?): ByteArray {
        val header = readExactly(4)
        val n = Wire.frameLength(header, expected)
        return readExactly(n)
    }

    fun readExactly(n: Int): ByteArray {
        val b = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) throw EOFException()
            got += r
        }
        return b
    }

    /** True when the helper closed the connection (end of stream) within the socket timeout. */
    fun closedByHelper(): Boolean = try { input.read() < 0 } catch (e: java.net.SocketException) { true }

    fun writeRaw(b: ByteArray) { output.write(b); output.flush() }

    fun send(message: Map<String, Any?>) = sendPlain(Json.encode(message))

    fun sendPlain(plain: ByteArray) = writeRaw(Wire.frame(Wire.seal(kH2C, Wire.H2C, sendSeq++, plain)))

    fun readSealedBody(): ByteArray = readBody(null)

    fun read(): Map<String, Any?> = Messages.decode(Wire.open(kC2H, Wire.C2H, recvSeq++, readBody(null)), Wire.C2H)

    override fun close() = socket.close()
}

/** A Helper that records what the link hands it. */
class RecordingHelper : Helper {
    val requests = java.util.concurrent.LinkedBlockingQueue<Map<String, Any?>>()
    val answers = java.util.concurrent.LinkedBlockingQueue<Pair<String, Map<String, String>?>>()
    @Volatile var ended = 0
    var reply: (String, Map<String, Any?>) -> Reply = { t, _ ->
        when (t) {
            "ping" -> Reply("pong")
            "context" -> Reply("context_result", mapOf("context" to null))
            else -> Reply("ack")
        }
    }

    override fun request(t: String, message: Map<String, Any?>): Reply {
        requests.add(message)
        return reply(t, message)
    }

    override fun answer(req: String, values: Map<String, String>?) {
        answers.add(req to values)
    }

    override fun linkEnded() {
        ended++
    }
}
