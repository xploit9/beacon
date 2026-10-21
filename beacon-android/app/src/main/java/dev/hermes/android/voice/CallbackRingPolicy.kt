package dev.hermes.android.voice

internal fun callbackMayAlert(beaconActive:Boolean,audioNormal:Boolean):Boolean = !beaconActive&&audioNormal
