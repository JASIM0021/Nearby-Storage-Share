package com.nearbyshare.core.security

/** Token bucket: [capacity] burst, refilled at [perSecond]. One instance per session. */
class RateLimiter(private val capacity: Int = 30, private val perSecond: Double = 15.0) {
    private var tokens = capacity.toDouble()
    private var last = System.nanoTime()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = System.nanoTime()
        tokens = minOf(capacity.toDouble(), tokens + (now - last) / 1e9 * perSecond)
        last = now
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
