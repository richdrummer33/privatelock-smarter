package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockPolicyEngineTest {

    private class Env(
        var locked: Boolean = false,
        var lockAvailable: Boolean = true,
        var trusted: Boolean = false,
    ) : PolicyEnvironment {
        override fun isDeviceLocked() = locked
        override fun isLockMethodAvailable() = lockAvailable
        override fun trustedContext() = TrustedContextAggregator.Result(
            trusted = trusted,
            signals = listOf(TrustSignal(trusted, "wifi", 0.5f, "home network")),
        )
    }

    private fun engine(
        env: Env = Env(),
        config: MotionConfig = MotionConfig(),
        clock: ManualClock = ManualClock(1_000_000_000L),
    ) = Triple(LockPolicyEngine(clock, config, env), env, clock)

    private fun acceptedPickup(confidence: Float = 0.9f) = PickupAssessment(
        accepted = true,
        confidence = confidence,
        atNanos = 0L,
        evidence = setOf(PickupEvidence.PRIOR_STILLNESS, PickupEvidence.SUSTAINED_MOTION),
        rejection = null,
        priorStillnessMillis = 18_400,
        orientationDeltaDeg = 47f,
        angularTravelRad = 0.9f,
        onsetPeak = 3f,
        onsetJerk = 60f,
        sustainedRms = 0.3f,
    )

    private fun rejectedPickup() = acceptedPickup().copy(
        accepted = false,
        confidence = 0.2f,
        rejection = PickupRejection.NO_SUSTAINED_MOTION,
    )

    private fun dropFeatures(peak: Float = 40f, freefall: Long = 200) =
        FeatureSnapshot.empty(0L).copy(rawAccPeak = peak, freefallMillis = freefall)

    // -- pickup -------------------------------------------------------------

    @Test
    fun `a high-confidence pickup from rest locks immediately`() {
        val (policy, _, _) = engine()
        val decision = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertTrue(decision is LockDecision.Lock)
        assertEquals(LockReason.PICKUP_FROM_STATIONARY, (decision as LockDecision.Lock).reason)
    }

    @Test
    fun `a pickup while already walking is suppressed by motion context`() {
        val (policy, _, _) = engine()
        val decision = policy.onPickup(acceptedPickup(), MotionState.WALKING)
        assertEquals(SuppressReason.MOTION_CONTEXT, (decision as LockDecision.Suppress).reason)
        assertTrue(decision.explanation, decision.explanation.contains("walking"))
    }

    @Test
    fun `a pickup while in a vehicle is suppressed`() {
        val (policy, _, _) = engine()
        val decision = policy.onPickup(acceptedPickup(), MotionState.IN_VEHICLE)
        assertEquals(SuppressReason.MOTION_CONTEXT, (decision as LockDecision.Suppress).reason)
    }

    @Test
    fun `every carried state suppresses a motion lock`() {
        for (state in MotionState.entries.filter { it.suppressesPickupLock }) {
            val (policy, _, _) = engine()
            val decision = policy.onPickup(acceptedPickup(), state)
            assertEquals("$state should suppress", SuppressReason.MOTION_CONTEXT, (decision as LockDecision.Suppress).reason)
        }
    }

    @Test
    fun `a rejected pickup does not lock and reports the detector's reason`() {
        val (policy, _, _) = engine()
        val decision = policy.onPickup(rejectedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.NO_PICKUP_EVIDENCE, (decision as LockDecision.Suppress).reason)
        assertTrue(decision.explanation, decision.explanation.contains("sustained"))
    }

    @Test
    fun `a pickup below the confidence threshold does not lock`() {
        val (policy, _, _) = engine(config = MotionConfig(pickupConfidenceThreshold = 0.9f))
        val decision = policy.onPickup(acceptedPickup(confidence = 0.7f), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.NO_PICKUP_EVIDENCE, (decision as LockDecision.Suppress).reason)
    }

    @Test
    fun `pickup locking can be turned off entirely`() {
        val (policy, _, _) = engine(config = MotionConfig(pickupLockEnabled = false))
        val decision = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.PICKUP_LOCK_DISABLED, (decision as LockDecision.Suppress).reason)
    }

    // -- drop ---------------------------------------------------------------

    @Test
    fun `a severe impact locks and is labelled as such`() {
        val (policy, _, _) = engine()
        val decision = policy.onDrop(dropFeatures(peak = 50f))
        assertEquals(LockReason.SEVERE_IMPACT, (decision as LockDecision.Lock).reason)
        assertTrue(decision.explanation, decision.explanation.contains("severe impact"))
    }

    @Test
    fun `a free-fall drop below the severe threshold is labelled a probable drop`() {
        val (policy, _, _) = engine()
        val decision = policy.onDrop(dropFeatures(peak = 28f, freefall = 200))
        assertEquals(LockReason.PROBABLE_DROP, (decision as LockDecision.Lock).reason)
        assertTrue(decision.explanation, decision.explanation.contains("free fall"))
    }

    @Test
    fun `drop locking can be turned off`() {
        val (policy, _, _) = engine(config = MotionConfig(dropLockEnabled = false))
        val decision = policy.onDrop(dropFeatures())
        assertEquals(SuppressReason.DROP_LOCK_DISABLED, (decision as LockDecision.Suppress).reason)
    }

    /** A phone dropped while its owner walks is just as exposed. */
    @Test
    fun `a drop is not suppressed by a carried motion context`() {
        val (policy, _, _) = engine()
        assertTrue(policy.onDrop(dropFeatures()) is LockDecision.Lock)
    }

    // -- screen-off grace ---------------------------------------------------

    @Test
    fun `the untrusted grace period is one minute by default`() {
        val (policy, env, clock) = engine()
        env.trusted = false
        policy.onScreenOff()

        clock.advanceMillis(59_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)

        clock.advanceMillis(2_000)
        val decision = policy.onTick()
        assertEquals(LockReason.SCREEN_OFF_GRACE_EXPIRED, (decision as LockDecision.Lock).reason)
    }

    @Test
    fun `the trusted grace period is three minutes by default`() {
        val (policy, env, clock) = engine()
        env.trusted = true
        policy.onScreenOff()

        clock.advanceMillis(120_000)
        assertTrue("a trusted context must survive the untrusted deadline", policy.onTick() is LockDecision.NoAction)

        clock.advanceMillis(65_000)
        val decision = policy.onTick()
        assertEquals(LockReason.SCREEN_OFF_GRACE_EXPIRED, (decision as LockDecision.Lock).reason)
        assertTrue(decision.explanation, decision.explanation.contains("trusted context"))
    }

    /**
     * The core safety property of the trusted-context feature: it can only
     * ever lengthen the grace period. It must never unlock, never shorten a
     * lock, and never suppress a pickup.
     */
    @Test
    fun `trusted context never suppresses a pickup lock`() {
        val (policy, env, _) = engine()
        env.trusted = true
        val decision = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertTrue("trusted context must not prevent a pickup lock", decision is LockDecision.Lock)
    }

    @Test
    fun `trusted context never suppresses a drop lock`() {
        val (policy, env, _) = engine()
        env.trusted = true
        assertTrue(policy.onDrop(dropFeatures()) is LockDecision.Lock)
    }

    @Test
    fun `no grace timer runs while the screen is on`() {
        val (policy, _, clock) = engine()
        policy.onScreenOn()
        clock.advanceMillis(600_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)
    }

    @Test
    fun `no grace timer starts if the device was already locked at screen-off`() {
        val (policy, env, clock) = engine()
        env.locked = true
        policy.onScreenOff()
        clock.advanceMillis(600_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)
    }

    @Test
    fun `authenticating cancels a pending grace timer`() {
        val (policy, _, clock) = engine()
        policy.onScreenOff()
        clock.advanceMillis(30_000)
        policy.onUserPresent()
        clock.advanceMillis(120_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)
    }

    @Test
    fun `grace locking can be turned off`() {
        val (policy, _, clock) = engine(config = MotionConfig(screenOffGraceEnabled = false))
        policy.onScreenOff()
        clock.advanceMillis(600_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)
    }

    @Test
    fun `the grace description names the trusted source`() {
        val (policy, env, _) = engine()
        env.trusted = true
        assertTrue(policy.describeGrace(), policy.describeGrace().startsWith("Grace period extended"))
        env.trusted = false
        assertTrue(policy.describeGrace(), policy.describeGrace().contains("no trusted context"))
    }

    // -- cooldown, pause, availability --------------------------------------

    @Test
    fun `a second lock within the cooldown is suppressed`() {
        val (policy, _, clock) = engine()
        assertTrue(policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) is LockDecision.Lock)

        clock.advanceMillis(1_000)
        val second = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.COOLDOWN, (second as LockDecision.Suppress).reason)

        clock.advanceMillis(10_000)
        assertTrue(policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) is LockDecision.Lock)
    }

    @Test
    fun `repeated identical events do not produce duplicate locks`() {
        val (policy, _, clock) = engine()
        var lockCount = 0
        repeat(20) {
            if (policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) is LockDecision.Lock) lockCount++
            clock.advanceMillis(100)
        }
        assertEquals("20 events over 2 seconds must produce exactly one lock", 1, lockCount)
    }

    @Test
    fun `pausing suppresses motion and drop locks`() {
        val (policy, _, clock) = engine()
        policy.pauseFor(60_000)
        assertTrue(policy.isPaused)

        assertEquals(
            SuppressReason.PAUSED,
            (policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) as LockDecision.Suppress).reason,
        )
        assertEquals(
            SuppressReason.PAUSED,
            (policy.onDrop(dropFeatures()) as LockDecision.Suppress).reason,
        )

        clock.advanceMillis(61_000)
        assertFalse(policy.isPaused)
        assertTrue(policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) is LockDecision.Lock)
    }

    @Test
    fun `a pause does not start the grace timer running in the background`() {
        val (policy, _, clock) = engine()
        policy.onScreenOff()
        policy.pauseFor(300_000)
        clock.advanceMillis(120_000)
        assertTrue(policy.onTick() is LockDecision.NoAction)
    }

    @Test
    fun `resuming clears a pause early`() {
        val (policy, _, _) = engine()
        policy.pauseFor(600_000)
        assertTrue(policy.isPaused)
        policy.resume()
        assertFalse(policy.isPaused)
        assertEquals(0L, policy.pauseRemainingMillis())
    }

    @Test
    fun `nothing is locked when no lock method is available`() {
        val (policy, env, _) = engine()
        env.lockAvailable = false
        val decision = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.NO_LOCK_METHOD, (decision as LockDecision.Suppress).reason)
        assertTrue(
            "losing the lock method must be reported, not silently ignored",
            decision.explanation.isNotBlank(),
        )
    }

    @Test
    fun `an already-locked device is not locked again`() {
        val (policy, env, _) = engine()
        env.locked = true
        val decision = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(SuppressReason.ALREADY_LOCKED, (decision as LockDecision.Suppress).reason)
    }

    @Test
    fun `a manual lock request bypasses the cooldown`() {
        val (policy, _, clock) = engine()
        assertTrue(policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE) is LockDecision.Lock)
        clock.advanceMillis(100)
        val manual = policy.lockNowRequested()
        assertEquals(LockReason.MANUAL, (manual as LockDecision.Lock).reason)
    }

    // -- time source --------------------------------------------------------

    /**
     * Every duration must come from the monotonic clock. If wall-clock time
     * were used, an NTP correction or a user changing the date could skip or
     * indefinitely postpone a grace-period lock.
     */
    @Test
    fun `grace timing depends only on the injected monotonic clock`() {
        val env = Env()
        val clockA = ManualClock(0L)
        val clockB = ManualClock(9_000_000_000_000_000L) // a wildly different origin
        val a = LockPolicyEngine(clockA, MotionConfig(), env)
        val b = LockPolicyEngine(clockB, MotionConfig(), env)

        a.onScreenOff()
        b.onScreenOff()
        clockA.advanceMillis(61_000)
        clockB.advanceMillis(61_000)

        assertTrue(a.onTick() is LockDecision.Lock)
        assertTrue(b.onTick() is LockDecision.Lock)
    }

    // -- decision log -------------------------------------------------------

    @Test
    fun `the decision log is bounded and keeps the newest entries`() {
        val (policy, _, clock) = engine(config = MotionConfig(decisionLogCapacity = 10))
        repeat(50) {
            policy.onPickup(rejectedPickup(), MotionState.STATIONARY_SURFACE)
            clock.advanceMillis(100)
        }
        assertEquals(10, policy.recentDecisions().size)
    }

    @Test
    fun `the last lock decision is retrievable for the diagnostics screen`() {
        val (policy, _, _) = engine()
        assertNull(policy.lastLockDecision())
        policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertEquals(LockReason.PICKUP_FROM_STATIONARY, policy.lastLockDecision()?.reason)
    }

    @Test
    fun `decisions format for display in the documented style`() {
        val (policy, _, _) = engine()
        val lock = policy.onPickup(acceptedPickup(), MotionState.STATIONARY_SURFACE)
        assertTrue(lock.format(), lock.format().startsWith("Locked: "))

        val suppressed = policy.onPickup(acceptedPickup(), MotionState.WALKING)
        assertTrue(suppressed.format(), suppressed.format().startsWith("Suppressed: "))
    }

    @Test
    fun `config can be replaced at runtime without restarting the engine`() {
        val (policy, _, clock) = engine()
        policy.updateConfig(MotionConfig(untrustedGraceMillis = 5_000))
        policy.onScreenOff()
        clock.advanceMillis(6_000)
        assertTrue(policy.onTick() is LockDecision.Lock)
        assertEquals(5_000L, policy.currentConfig().untrustedGraceMillis)
    }
}
