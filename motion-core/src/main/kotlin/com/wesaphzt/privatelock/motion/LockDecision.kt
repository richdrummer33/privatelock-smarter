package com.wesaphzt.privatelock.motion

/** Why the policy engine decided to lock. */
enum class LockReason {
    PICKUP_FROM_STATIONARY,
    PROBABLE_DROP,
    SEVERE_IMPACT,
    SCREEN_OFF_GRACE_EXPIRED,
    MANUAL,
}

/** Why a candidate lock was not carried out. */
enum class SuppressReason {
    /** Another lock happened too recently. */
    COOLDOWN,
    /** The user paused the service. */
    PAUSED,
    /** The device is already locked. */
    ALREADY_LOCKED,
    /** Motion-triggered locking is turned off. */
    PICKUP_LOCK_DISABLED,
    /** Drop locking is turned off. */
    DROP_LOCK_DISABLED,
    /** Screen-off grace locking is turned off. */
    GRACE_DISABLED,
    /** The device was already being carried, so there was no pickup. */
    MOTION_CONTEXT,
    /** The pickup gesture did not satisfy its evidence gates. */
    NO_PICKUP_EVIDENCE,
    /** The grace period has not run out yet. */
    GRACE_NOT_EXPIRED,
    /** The screen is on, so no grace timer is running. */
    SCREEN_ON,
    /** No lock method is available or permitted right now. */
    NO_LOCK_METHOD,
}

/**
 * The policy engine's verdict.
 *
 * Sealed so that a caller cannot forget to handle a case, and carrying a
 * pre-rendered [explanation] so the diagnostics screen and the debug export
 * show exactly the sentence the engine reasoned with.
 */
sealed interface LockDecision {

    /** Monotonic time the decision was made. */
    val atNanos: Long

    /** One-line human-readable account of the decision. */
    val explanation: String

    data class Lock(
        override val atNanos: Long,
        val reason: LockReason,
        override val explanation: String,
    ) : LockDecision

    data class Suppress(
        override val atNanos: Long,
        val reason: SuppressReason,
        override val explanation: String,
    ) : LockDecision

    data class Note(
        override val atNanos: Long,
        override val explanation: String,
    ) : LockDecision

    data class NoAction(override val atNanos: Long) : LockDecision {
        override val explanation: String get() = "no action"
    }

    /** Formatted for the diagnostics list, matching the documented examples. */
    fun format(): String = when (this) {
        is Lock -> "Locked: $explanation"
        is Suppress -> "Suppressed: $explanation"
        is Note -> explanation
        is NoAction -> "No action"
    }
}
