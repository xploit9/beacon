package dev.hermes.android.voice

internal class VoiceReconnects(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    @Volatile var failures=0
        private set
    @Volatile private var connectedAt: Long? = null
    val exhausted get()=failures>4
    fun failed() {
        if (connectedAt?.let { clock()-it >= 30_000 } == true) failures=0
        connectedAt=null; failures++
    }
    fun admitted() { connectedAt=clock() }
}

internal fun voiceTransportCause(error:Throwable):String = when(error) {
    is javax.net.ssl.SSLHandshakeException -> "ssl_handshake"
    is javax.net.ssl.SSLPeerUnverifiedException -> "ssl_peer"
    is javax.net.ssl.SSLProtocolException -> "ssl_protocol"
    is javax.net.ssl.SSLException -> "ssl_transport"
    is java.net.SocketTimeoutException -> "socket_timeout"
    is java.net.UnknownHostException -> "dns"
    is java.net.ConnectException -> "connect"
    is java.io.EOFException -> "eof"
    is java.io.IOException -> "io"
    else -> "other"
}

internal fun voiceNeedsNativeEvents(status:String)=status in setOf("working","needs_input")

internal fun voiceProviderCurrent(generation:Long,current:Long,stopped:Boolean,ended:Boolean)=
    generation==current&&!stopped&&!ended

internal fun callbackDefinitelyRejected(status:Int,message:String)=status in setOf(401,403,404,413,429) ||
    status==409&&message in setOf("Choose delay_seconds or at","Invalid callback delay","Expected timezone-aware ISO callback time",
        "Callback time needs a timezone","Callback must be within the next year","Scheduled callbacks are disabled on the host",
        "Stable callback request_id required","Brief callback reason required","Unknown callback field","Call ended before callback request was accepted")

internal class VoiceProviderRejected(message:String):IllegalStateException(message)

/** A provider refused the call setup (close 1007/1008 before the greeting): say which part to fix. */
internal fun providerRejectionMessage(provider:String,reason:String):String {
    val name=when(provider) { "grok" -> "Grok";"openai" -> "OpenAI";else -> "Gemini" }
    return if(reason.contains("key",ignoreCase=true)) "$name didn't accept the API key. Check it in Settings › Voice & calls."
    else "$name couldn't start the call. Choose another model in Settings › Voice & calls."
}

internal fun classifyVoiceProviderClose(code:Int,reason:String):VoiceProviderTestResult =
    if(code in setOf(1007,1008)&&reason.contains("key",ignoreCase=true)) VoiceProviderTestResult.KeyRejected else VoiceProviderTestResult.ProviderError
