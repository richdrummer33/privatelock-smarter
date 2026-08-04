package com.wesaphzt.privatelock.motion

/**
 * Monotonic time source.
 *
 * Every duration in this module is measured against a monotonic clock, never
 * wall-clock time. On Android the production implementation is backed by
 * `SystemClock.elapsedRealtimeNanos()`, which keeps counting through deep sleep
 * and is immune to NTP steps, time-zone changes and user clock edits. Wall
 * clock (`System.currentTimeMillis`) must never be used for stillness
 * durations, grace periods or cooldowns: a clock adjustment could otherwise
 * silently suppress a lock or fabricate one.
 */
interface Clock {
    /** Monotonic nanoseconds since an arbitrary but fixed origin. */
    fun nowNanos(): Long

    /** Convenience: the same instant expressed in milliseconds. */
    fun nowMillis(): Long = nowNanos() / 1_000_000L
}

/**
 * Test clock that only advances when told to.
 *
 * Deterministic time is what makes hysteresis, dwell, grace and cooldown
 * behaviour testable without sleeping.
 */
class ManualClock(private var nanos: Long = 0L) : Clock {
    override fun nowNanos(): Long = nanos

    fun advanceNanos(delta: Long) {
        require(delta >= 0) { "time must not move backwards: $delta" }
        nanos += delta
    }

    fun advanceMillis(delta: Long) = advanceNanos(delta * 1_000_000L)

    fun setNanos(value: Long) {
        require(value >= nanos) { "time must not move backwards" }
        nanos = value
    }
}
