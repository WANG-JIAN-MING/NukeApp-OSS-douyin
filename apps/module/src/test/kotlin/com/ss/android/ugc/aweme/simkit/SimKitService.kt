package com.ss.android.ugc.aweme.simkit

// Test stand-in for the host class that ForceHighVideoQuality loads by name.
class SimKitService {
    var quality = 0
    fun setBitrateQuality(mode: Int) { quality = mode }
    fun getBitrateQuality(): Int = quality
}
