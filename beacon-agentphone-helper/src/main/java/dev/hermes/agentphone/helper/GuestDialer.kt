package dev.hermes.agentphone.helper

import dev.hermes.agentphone.helper.core.Conn
import dev.hermes.agentphone.helper.core.Dialer
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The only connection the helper ever opens (contract §1.5): to the emulator's alias for the host's loopback, at the
 * port the start intent named. The helper listens on nothing. A unit test checks no other socket code exists.
 */
object GuestDialer : Dialer {
    const val GUEST_HOST = "10.0.2.2"
    private const val CONNECT_MS = 2000

    override fun dial(port: Int): Conn {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(GUEST_HOST, port), CONNECT_MS)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
        val ins = socket.getInputStream()
        val outs = BufferedOutputStream(socket.getOutputStream())
        return object : Conn {
            override val input = ins
            override val output = outs
            override fun timeout(ms: Int) { socket.soTimeout = ms }
            override fun close() { socket.close() }
        }
    }
}
