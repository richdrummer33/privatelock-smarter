package com.wesaphzt.privatelock.motion

/**
 * Everything the policy needs to know about the world, supplied by the caller
 * so that the engine itself stays free of platform dependencies.
 */
interface PolicyEnvironment {
    /** Whether the device is currently locked. */
    fun isDeviceLocked(): Boolean

    /** Whether some lock method is currently usable. */
    fun isLockMethodAvailable(): Boolean

    /** Current trusted-context reading. */
    fun trustedContext(): TrustedContextAggregator.Result
}

/**
 * Decides whether a detected event should actually lock the device.
 *
 * ## Why this is separate from detection
 *
 * Detection answers "what physically happened". Policy answers "given the
 * user's settings, the trust context, what we did a moment ago, and whether
 * the device is even unlocked, should we act". Mixing the two is what made the
 * original app hard to reason about: `lockNow()` was called from inside a raw
 * `SensorEventListener` callback, so there was no place to express "not while
 * paused", "not twice in a row", or "not while the phone is already locked".
 *
 * The engine is a pure state machine over an explicit [Clock]. It performs no
 * I/O and touches no Android API, so every rule below is unit-testable with
 * deterministic time.
 *
 * ## Rules
 *
 *  - A high-confidence pickup from a stationary surface locks immediately,
 *    regardless of any remaining grace period.
 *  - Motion while already walking, running, cycling, held or in a vehicle
 *    never triggers a motion lock.
 *  - A probable drop or severe impact locks immediately, when enabled.
 *  - After the screen goes off while unlocked, a grace period runs; when it
 *    expires the device is locked. The period is longer in a trusted context.
 *  - A cooldown after any lock stops repeated triggering.
 *  - A manual pause suspends everything except explicit user action.
 */
class LockPolicyEngine(
    private val clock: Clock,
    private var config: MotionConfig = MotionConfig(),
    private val environment: PolicyEnvironment,
) {
    private var lastLockNanos: Long = Long.MIN_VALUE
    private var screenOffSinceNanos: Long = -1L
    private var pausedUntilNanos: Long = -1L
    private var screenOn: Boolean = true

    /** Bounded ring of recent decisions, newest last. For diagnostics only. */
    private val decisions = ArrayDeque<LockDecision>()

    /** Snapshot of recent decisions, oldest first. */
    fun recentDecisions(): List<LockDecision> = decisions.toList()

    /** Last decision that actually locked, if any. */
    fun lastLockDecision(): LockDecision.Lock? =
        decisions.filterIsInstance<LockDecision.Lock>().lastOrNull()

    val isPaused: Boolean get() = pausedUntilNanos > clock.nowNanos()

    /** Remaining pause, ms; zero when not paused. */
    fun pauseRemainingMillis(): Long {
        val now = clock.nowNanos()
        return if (pausedUntilNanos <= now) 0L else (pausedUntilNanos - now) / 1_000_000L
    }

    fun updateConfig(newConfig: MotionConfig) {
        config = newConfig
    }

    fun currentConfig(): MotionConfig = config

    // -- lifecycle inputs ---------------------------------------------------

    /** The user paused the service for [durationMillis]. */
    fun pauseFor(durationMillis: Long): LockDecision {
        pausedUntilNanos = clock.nowNanos() + durationMillis * 1_000_000L
        return record(
            LockDecision.Note(
                clock.nowNanos(),
                "paused for ${durationMillis / 1000} s",
            ),
        )
    }

    fun resume(): LockDecision {
        pausedUntilNanos = -1L
        return record(LockDecision.Note(clock.nowNanos(), "resumed"))
    }

    fun onScreenOff() {
        screenOn = false
        // Only start the timer if the device is actually still unlocked.
        // Screen-off and locked are different things on Android: with a lock
        // timeout the device may stay unlocked for minutes after the display
        // turns off, and that window is exactly what this guards.
        screenOffSinceNanos = if (environment.isDeviceLocked()) -1L else clock.nowNanos()
    }

    fun onScreenOn() {
        screenOn = true
        screenOffSinceNanos = -1L
    }

    /** The user authenticated; any pending grace timer is void. */
    fun onUserPresent() {
        screenOn = true
        screenOffSinceNanos = -1L
    }

    /** Resets timers without clearing the cooldown, e.g. after a service restart. */
    fun onServiceRestarted() {
        screenOffSinceNanos = -1L
    }

    // -- event inputs -------------------------------------------------------

    /**
     * A pickup gesture concluded.
     *
     * @param assessment the detector's verdict
     * @param state the motion state at the time
     */
    fun onPickup(assessment: PickupAssessment, state: MotionState): LockDecision {
        val now = clock.nowNanos()

        if (!config.pickupLockEnabled) {
            return record(LockDecision.Suppress(now, SuppressReason.PICKUP_LOCK_DISABLED, "pickup locking is disabled"))
        }
        if (isPaused) {
            return record(LockDecision.Suppress(now, SuppressReason.PAUSED, "service paused"))
        }
        if (state.suppressesPickupLock) {
            return record(
                LockDecision.Suppress(
                    now,
                    SuppressReason.MOTION_CONTEXT,
                    "already ${state.name.lowercase().replace('_', ' ')} before the impulse",
                ),
            )
        }
        if (!assessment.accepted) {
            return record(
                LockDecision.Suppress(now, SuppressReason.NO_PICKUP_EVIDENCE, assessment.explain()),
            )
        }
        if (assessment.confidence < config.pickupConfidenceThreshold) {
            return record(
                LockDecision.Suppress(
                    now,
                    SuppressReason.NO_PICKUP_EVIDENCE,
                    "pickup confidence %.2f below threshold %.2f"
                        .format(assessment.confidence, config.pickupConfidenceThreshold),
                ),
            )
        }

        return attemptLock(now, LockReason.PICKUP_FROM_STATIONARY, assessment.explain())
    }

    /**
     * A probable drop or severe impact was detected.
     *
     * Unlike a pickup this is not suppressed by the carried states: a phone
     * dropped while its owner is walking is exactly as exposed as one dropped
     * from a table.
     */
    fun onDrop(features: FeatureSnapshot): LockDecision {
        val now = clock.nowNanos()

        if (!config.dropLockEnabled) {
            return record(LockDecision.Suppress(now, SuppressReason.DROP_LOCK_DISABLED, "drop locking is disabled"))
        }
        if (isPaused) {
            return record(LockDecision.Suppress(now, SuppressReason.PAUSED, "service paused"))
        }

        val severe = features.rawAccPeak >= config.severeImpactMin
        val reason = if (severe) LockReason.SEVERE_IMPACT else LockReason.PROBABLE_DROP
        val explanation = if (severe) {
            "probable severe impact, peak %.0f m/s^2".format(features.rawAccPeak)
        } else {
            "probable drop, %d ms free fall then %.0f m/s^2 impact"
                .format(features.freefallMillis, features.rawAccPeak)
        }
        return attemptLock(now, reason, explanation)
    }

    /**
     * Periodic tick that advances the screen-off grace timer.
     *
     * Driven by the service rather than by an exact alarm: a grace period is a
     * convenience boundary, not a deadline, and burning the exact-alarm budget
     * on it would be an abuse of a restricted API.
     */
    fun onTick(): LockDecision {
        val now = clock.nowNanos()

        if (!config.screenOffGraceEnabled) return LockDecision.NoAction(now)
        if (screenOn || screenOffSinceNanos < 0L) return LockDecision.NoAction(now)
        if (isPaused) return LockDecision.NoAction(now)
        if (environment.isDeviceLocked()) {
            screenOffSinceNanos = -1L
            return LockDecision.NoAction(now)
        }

        val trust = environment.trustedContext()
        val graceMillis = if (trust.trusted) config.trustedGraceMillis else config.untrustedGraceMillis
        val elapsedMillis = (now - screenOffSinceNanos) / 1_000_000L
        if (elapsedMillis < graceMillis) return LockDecision.NoAction(now)

        val context = if (trust.trusted) {
            "trusted context (${trust.describe()})"
        } else {
            "untrusted context"
        }
        return attemptLock(
            now,
            LockReason.SCREEN_OFF_GRACE_EXPIRED,
            "screen off for %d s in %s, grace period %d s elapsed"
                .format(elapsedMillis / 1000, context, graceMillis / 1000),
        )
    }

    /**
     * Reports which grace period is in force, for the diagnostics screen.
     * Produces the "Grace period extended: trusted Wi-Fi active" line.
     */
    fun describeGrace(): String {
        val trust = environment.trustedContext()
        return if (trust.trusted) {
            "Grace period extended: ${trust.describe()} -- %d s instead of %d s"
                .format(config.trustedGraceMillis / 1000, config.untrustedGraceMillis / 1000)
        } else {
            "Grace period %d s (no trusted context)".format(config.untrustedGraceMillis / 1000)
        }
    }

    /** The user asked to lock right now. Bypasses cooldown, honours nothing else. */
    fun lockNowRequested(): LockDecision {
        val now = clock.nowNanos()
        if (!environment.isLockMethodAvailable()) {
            return record(LockDecision.Suppress(now, SuppressReason.NO_LOCK_METHOD, "no lock method available"))
        }
        lastLockNanos = now
        return record(LockDecision.Lock(now, LockReason.MANUAL, "requested by the user"))
    }

    // -- shared lock path ---------------------------------------------------

    private fun attemptLock(nowNanos: Long, reason: LockReason, explanation: String): LockDecision {
        if (environment.isDeviceLocked()) {
            return record(LockDecision.Suppress(nowNanos, SuppressReason.ALREADY_LOCKED, "device is already locked"))
        }
        if (!environment.isLockMethodAvailable()) {
            return record(
                LockDecision.Suppress(
                    nowNanos,
                    SuppressReason.NO_LOCK_METHOD,
                    "no lock method available; check the setup screen",
                ),
            )
        }
        // Cooldown is checked after the cheaper guards so that "already locked"
        // is reported in preference to "cooling down", which is more useful.
        if (lastLockNanos != Long.MIN_VALUE) {
            val sinceMillis = (nowNanos - lastLockNanos) / 1_000_000L
            if (sinceMillis < config.lockCooldownMillis) {
                return record(
                    LockDecision.Suppress(
                        nowNanos,
                        SuppressReason.COOLDOWN,
                        "locked %d ms ago, cooldown %d ms".format(sinceMillis, config.lockCooldownMillis),
                    ),
                )
            }
        }

        lastLockNanos = nowNanos
        screenOffSinceNanos = -1L
        return record(LockDecision.Lock(nowNanos, reason, explanation))
    }

    private fun record(decision: LockDecision): LockDecision {
        decisions.addLast(decision)
        while (decisions.size > config.decisionLogCapacity) decisions.removeFirst()
        return decision
    }
}
