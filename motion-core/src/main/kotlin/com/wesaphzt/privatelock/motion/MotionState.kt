package com.wesaphzt.privatelock.motion

/**
 * Broad motion context of the device.
 *
 * These are contexts, not events. A transition *between* two of them is what
 * carries security meaning -- particularly [STATIONARY_SURFACE] to anything
 * held -- which is why the pickup detector consumes transitions rather than
 * raw samples.
 */
enum class MotionState {
    /** Not enough data yet, or a sensor gap. Never treated as "safe". */
    UNKNOWN,

    /**
     * Resting on a surface: acceleration and gyro noise at the sensor floor.
     * The only state from which a pickup can be detected.
     */
    STATIONARY_SURFACE,

    /** Held in a hand and roughly still. Hand tremor is clearly above a table. */
    HANDHELD_STILL,

    /** Carried while walking; periodic gait signature around 1.2-2.6 Hz. */
    WALKING,

    /** Carried while running; faster cadence and much larger peaks. */
    RUNNING,

    /**
     * Low-amplitude broadband vibration without gait periodicity. Inferred
     * weakly from the IMU alone; corroborated by Activity Recognition when the
     * optional provider is enabled.
     */
    IN_VEHICLE,

    /** Reported only when an activity-recognition provider says so. */
    ON_BICYCLE,

    /** Something is happening but has not resolved into a steady context. */
    DISTURBED,

    /** Free-fall interval and/or a severe impact. */
    POSSIBLE_DROP,
    ;

    /** True for states in which the device is already being carried or used. */
    val isCarried: Boolean
        get() = this == HANDHELD_STILL || this == WALKING || this == RUNNING || this == ON_BICYCLE

    /** True for contexts in which ordinary motion must never trigger a lock. */
    val suppressesPickupLock: Boolean
        get() = isCarried || this == IN_VEHICLE

    /** True only for the resting state a pickup can start from. */
    val isAtRest: Boolean
        get() = this == STATIONARY_SURFACE
}

/**
 * A committed change of [MotionState], with the evidence that produced it.
 */
data class MotionStateTransition(
    val from: MotionState,
    val to: MotionState,
    val atNanos: Long,
    /** How long [from] had been held when the change was committed, ms. */
    val previousStateDurationMillis: Long,
    /** Features at the moment of commitment. */
    val features: FeatureSnapshot,
)
