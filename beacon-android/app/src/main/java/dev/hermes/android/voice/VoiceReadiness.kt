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

/**
 * Backlog 81 (CHAT-121 follow-up 11): the provider refuses for money. Its own reason names credits, billing or a prepaid plan, whatever the close
 * code (Gemini Live closes 1011 "Your prepayment credits are depleted…" and "…please check your plan and billing details"), so trying again
 * cannot help until the owner adds credits. A rate limit ("Resource has been exhausted", "quota" alone) passes by itself and is not this.
 */
internal fun providerMoneyRefusal(reason:String):Boolean = PROVIDER_MONEY_WORDS.containsMatchIn(reason)
private val PROVIDER_MONEY_WORDS = Regex("""(?i)\b(credits?|billing|prepay(ment|ed)?|payment required|insufficient (funds|balance))\b""")
/** [providerRejectionMessage]'s reason for a refusal for money. */
internal const val PROVIDER_MONEY_REFUSAL = "credits"

/** A provider refused the call setup (close 1007/1008 before the greeting), or for money at any time: say which part to fix. */
internal fun providerRejectionMessage(provider:String,reason:String):String {
    val name=when(provider) { "grok" -> "Grok";"openai" -> "OpenAI";else -> "Gemini" }
    if(reason==PROVIDER_MONEY_REFUSAL) return "Your $name credits ran out."
    return if(reason.contains("key",ignoreCase=true)) "$name didn't accept the API key. Check it in Settings › Voice & calls."
    else "$name couldn't start the call. Choose another model in Settings › Voice & calls."
}

internal fun classifyVoiceProviderClose(code:Int,reason:String):VoiceProviderTestResult =
    if(code in setOf(1007,1008)&&reason.contains("key",ignoreCase=true)) VoiceProviderTestResult.KeyRejected else VoiceProviderTestResult.ProviderError
