package dev.hermes.android.voice

internal fun accumulatedCaption(previous:String,delta:String,limit:Int=16000):String {
    val text=previous+delta
    if(text.length<=limit) return text
    val end=if(text[limit-1].isHighSurrogate()&&text[limit].isLowSurrogate()) limit-1 else limit
    return text.take(end)
}
