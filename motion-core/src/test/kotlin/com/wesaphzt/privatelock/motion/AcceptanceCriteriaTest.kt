package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end checks written directly against the product's stated acceptance
 * criteria, each running a whole trace through the real pipeline.
 *
 * These are deliberately phrased as the requirement rather than as an
 * implementation detail: if the pipeline is rearranged, these are the tests
 * that must keep passing.
 */
class AcceptanceCriteriaTest {

    private fun run(
        trace: List<SensorSample>,
        trusted: Boolean = false,
        config: MotionConfig = MotionConfig(),
    ): PipelineHarness = PipelineHarness(config, trusted = trusted).also { it.feed(trace) }

    @Test
    fun `a phone that was very still and is then picked up locks`() {
        val h = run(SyntheticTraces.pickupFromDesk())
        assertEquals(
            "expected exactly one pickup lock: ${h.explain()}",
            listOf(LockReason.PICKUP_FROM_STATIONARY),
            h.lockReasons(),
        )
    }

    @Test
    fun `a phone already being carried while walking does not lock from an ordinary jerk`() {
        val h = run(SyntheticTraces.raiseAndLowerWhileWalking())
        assertTrue("walking with grip changes must not lock: ${h.explain()}", h.locks.isEmpty())
    }

    @Test
    fun `jogging does not lock`() {
        assertTrue(run(SyntheticTraces.running(25_000)).locks.isEmpty())
    }

    @Test
    fun `vehicle motion does not ordinarily trigger pickup locking`() {
        assertTrue(run(SyntheticTraces.vehicleMounted(40_000)).locks.isEmpty())
        assertTrue(run(SyntheticTraces.pickupFromCarMount()).locks.isEmpty())
    }

    @Test
    fun `a small desk bump does not lock`() {
        assertTrue(run(SyntheticTraces.deskBump()).locks.isEmpty())
    }

    @Test
    fun `setting the phone down does not lock`() {
        assertTrue(run(SyntheticTraces.hardSetDown()).locks.isEmpty())
    }

    @Test
    fun `a probable severe impact locks when drop locking is enabled`() {
        val h = run(SyntheticTraces.probableDrop())
        assertTrue(
            "expected a drop-related lock: ${h.explain()}",
            h.lockReasons().any { it == LockReason.SEVERE_IMPACT || it == LockReason.PROBABLE_DROP },
        )
    }

    @Test
    fun `a probable drop does not lock when drop locking is disabled`() {
        val h = run(SyntheticTraces.probableDrop(), config = MotionConfig(dropLockEnabled = false))
        assertTrue(h.lockReasons().none { it == LockReason.SEVERE_IMPACT || it == LockReason.PROBABLE_DROP })
    }

    /**
     * Trusted context must change *only* the grace period. The same pickup
     * trace has to lock identically in both contexts.
     */
    @Test
    fun `trusted context changes the grace period but never suppresses a pickup`() {
        val untrusted = run(SyntheticTraces.pickupFromDesk(), trusted = false)
        val trusted = run(SyntheticTraces.pickupFromDesk(), trusted = true)
        assertEquals(untrusted.lockReasons(), trusted.lockReasons())
        assertEquals(listOf(LockReason.PICKUP_FROM_STATIONARY), trusted.lockReasons())
    }

    @Test
    fun `trusted context extends the screen-off grace period and never unlocks`() {
        val h = PipelineHarness(trusted = true)
        h.feed(SyntheticTraces.stationaryOnDesk(6_000))
        h.pipeline.policy.onScreenOff()

        h.advanceIdleMillis(90_000)
        assertTrue(
            "a trusted context must survive well past the untrusted deadline",
            h.locks.isEmpty(),
        )

        h.advanceIdleMillis(120_000)
        assertEquals(listOf(LockReason.SCREEN_OFF_GRACE_EXPIRED), h.lockReasons())

        // Nothing anywhere in the API can unlock; the only outcomes are Lock,
        // Suppress and Note.
        assertTrue(h.decisions.none { it is LockDecision.Lock && it.reason == LockReason.MANUAL })
    }

    @Test
    fun `an untrusted context locks after the shorter grace period`() {
        val h = PipelineHarness(trusted = false)
        h.feed(SyntheticTraces.stationaryOnDesk(6_000))
        h.pipeline.policy.onScreenOff()
        h.advanceIdleMillis(65_000)
        assertEquals(listOf(LockReason.SCREEN_OFF_GRACE_EXPIRED), h.lockReasons())
    }

    @Test
    fun `every lock decision carries an explanation`() {
        val h = run(SyntheticTraces.pickupFromDesk())
        for (decision in h.decisions) {
            assertTrue("decision without explanation: $decision", decision.explanation.isNotBlank())
        }
    }

    @Test
    fun `losing the lock method reports a suppression rather than failing silently`() {
        val h = PipelineHarness(lockMethodAvailable = false)
        h.feed(SyntheticTraces.pickupFromDesk())
        assertTrue(h.locks.isEmpty())
        assertTrue(
            "the pickup must still be reported as suppressed: ${h.explain()}",
            h.suppressReasons().contains(SuppressReason.NO_LOCK_METHOD),
        )
    }

    @Test
    fun `an already-locked device is never locked again by motion`() {
        val h = PipelineHarness(deviceLocked = true)
        h.feed(SyntheticTraces.pickupFromDesk())
        assertTrue(h.locks.isEmpty())
        assertTrue(h.suppressReasons().contains(SuppressReason.ALREADY_LOCKED))
    }

    /**
     * The pipeline must never decide policy inside a sensor callback. Feeding
     * samples alone can produce nothing; only an evaluation tick can.
     */
    @Test
    fun `feeding samples alone never produces a lock`() {
        val environment = object : PolicyEnvironment {
            override fun isDeviceLocked() = false
            override fun isLockMethodAvailable() = true
            override fun trustedContext() = TrustedContextAggregator.Result(false, emptyList())
        }
        val pipeline = MotionPipeline(ManualClock(), MotionConfig(), environment)
        SyntheticTraces.pickupFromDesk().forEach { pipeline.onSample(it) }
        assertTrue(pipeline.policy.recentDecisions().isEmpty())
    }

    /**
     * Guards the battery saving. A phone sitting at its sensor noise floor
     * must fall back to the low sample rate; anything else pins the sensors at
     * the high rate for the whole time the service runs.
     */
    @Test
    fun `adaptive sampling drops to the idle rate once the phone settles`() {
        val settle = SyntheticTraces.Builder(seed = 77)
            .setTilt(0f)
            .quiet(20_000, SyntheticTraces.ACCEL_SIGMA_DESK, SyntheticTraces.GYRO_SIGMA_DESK)
        val h = PipelineHarness()
        h.feed(settle.build())
        assertEquals(
            "a phone resting undisturbed must not hold the sensors at the high rate",
            h.config.idleSamplingPeriodMicros,
            h.pipeline.desiredSamplingPeriodMicros(),
        )

        // Continue the *same* timeline rather than starting a new trace.
        h.feed(settle.impulse(200, peak = Vec3(2f, 2f, 5f)).build().takeLast(10))
        assertEquals(
            "a disturbance must raise the sampling rate",
            h.config.activeSamplingPeriodMicros,
            h.pipeline.desiredSamplingPeriodMicros(),
        )
    }
}
