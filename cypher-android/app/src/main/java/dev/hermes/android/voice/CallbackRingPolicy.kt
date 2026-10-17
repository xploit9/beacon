package dev.hermes.android.voice

internal fun callbackMayAlert(cypherActive:Boolean,audioNormal:Boolean):Boolean = !cypherActive&&audioNormal
