package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pickup detector is the security-critical stage, so these tests are
 * written as the acceptance criteria they correspond to.
 */
class PickupTransitionDetectorTest {

    private fun run(trace: List<SensorSample>, config: MotionConfig = MotionConfig()): PipelineHarness =
        PipelineHarness(config).also { it.feed(trace) }

    // -- positives ----------------------------------------------------------

    @Test
    fun `a phone lifted off a desk produces a high-confidence pickup`() {
        val h = run(SyntheticTraces.pickupFromDesk())
        val accepted = h.acceptedPickups()
        assertEquals("expected exactly one pickup, got ${h.explain()}", 1, accepted.size)

        val pickup = accepted.single()
        assertTrue("confidence ${pickup.confidence} should be high", pickup.confidence > 0.8f)
        assertTrue(pickup.priorStillnessMillis >= 3_000)
        assertTrue(pickup.evidence.contains(PickupEvidence.PRIOR_STILLNESS))
        assertTrue(pickup.evidence.contains(PickupEvidence.ORIENTATION_CHANGE))
        assertTrue(pickup.evidence.contains(PickupEvidence.SUSTAINED_MOTION))
        assertTrue(pickup.evidence.contains(PickupEvidence.DID_NOT_RETURN_TO_REST))
        assertNull(pickup.rejection)
    }

    /**
     * A slow, careful lift is precisely how someone quietly takes a phone off a
     * table, so it must be detected even though its onset impulse is weak.
     */
    @Test
    fun `a gentle pickup is still detected despite a weak onset`() {
        val h = run(SyntheticTraces.gentlePickupFromDesk())
        val accepted = h.acceptedPickups()
        assertEquals("expected the gentle pickup to be detected: ${h.explain()}", 1, accepted.size)
        assertTrue(accepted.single().confidence >= MotionConfig().pickupConfidenceThreshold)
    }

    @Test
    fun `a pickup after a longer rest scores at least as high`() {
        val short = run(SyntheticTraces.pickupFromDesk(stillMillis = 4_000)).acceptedPickups().single()
        val long = run(SyntheticTraces.pickupFromDesk(stillMillis = 20_000)).acceptedPickups().single()
        assertTrue(long.priorStillnessMillis > short.priorStillnessMillis)
        assertTrue(long.confidence >= short.confidence - 0.05f)
    }

    // -- negatives ----------------------------------------------------------

    @Test
    fun `a desk bump is rejected because the phone settles back down`() {
        val h = run(SyntheticTraces.deskBump())
        assertTrue("a bump must never lock: ${h.explain()}", h.acceptedPickups().isEmpty())
        val rejected = h.rejectedPickups()
        assertTrue("expected the bump to be assessed and rejected", rejected.isNotEmpty())
        assertEquals(PickupRejection.RETURNED_TO_REST, rejected.last().rejection)
    }

    @Test
    fun `walking never produces a pickup because the detector never arms`() {
        val h = run(SyntheticTraces.walking(20_000))
        assertTrue(h.assessments.isEmpty())
        assertTrue(h.locks.isEmpty())
    }

    @Test
    fun `running never produces a pickup`() {
        val h = run(SyntheticTraces.running(20_000))
        assertTrue(h.assessments.isEmpty())
        assertTrue(h.locks.isEmpty())
    }

    @Test
    fun `vehicle vibration never produces a pickup`() {
        val h = run(SyntheticTraces.vehicleMounted(30_000))
        assertTrue(h.assessments.isEmpty())
        assertTrue(h.locks.isEmpty())
    }

    /**
     * Taking the phone out of a car mount with the engine running: there is no
     * stationary-surface baseline, so no pickup can be reported. This is a
     * structural consequence of the state machine, not a threshold.
     */
    @Test
    fun `lifting the phone from a vibrating car mount does not lock`() {
        val h = run(SyntheticTraces.pickupFromCarMount())
        assertTrue(h.locks.isEmpty())
    }

    @Test
    fun `raising and lowering the phone while walking does not lock`() {
        val h = run(SyntheticTraces.raiseAndLowerWhileWalking())
        assertTrue("changing grip mid-walk must not lock: ${h.explain()}", h.locks.isEmpty())
    }

    @Test
    fun `putting the phone down does not produce a pickup`() {
        val h = run(SyntheticTraces.hardSetDown())
        assertTrue(h.acceptedPickups().isEmpty())
        assertTrue(h.lockReasons().none { it == LockReason.PICKUP_FROM_STATIONARY })
    }

    @Test
    fun `an undisturbed phone produces nothing at all`() {
        val h = run(SyntheticTraces.stationaryOnDesk(30_000))
        assertTrue(h.assessments.isEmpty())
        assertTrue(h.decisions.isEmpty())
    }

    // -- required stillness -------------------------------------------------

    @Test
    fun `a pickup before the required stillness has accrued is not detected`() {
        // Only 1 s of rest before the lift, against a 3 s requirement.
        val trace = SyntheticTraces.pickupFromDesk(stillMillis = 1_000)
        val h = run(trace)
        assertTrue("1s of rest is below the 3s requirement: ${h.explain()}", h.acceptedPickups().isEmpty())
    }

    @Test
    fun `lowering the required stillness makes a short-rest pickup detectable`() {
        val config = MotionConfig(requiredStillnessMillis = 800)
        val h = run(SyntheticTraces.pickupFromDesk(stillMillis = 2_500), config)
        assertEquals(1, h.acceptedPickups().size)
    }

    // -- staging ------------------------------------------------------------

    @Test
    fun `the detector only arms from a committed stationary state`() {
        val detector = PickupTransitionDetector()
        val features = FeatureSnapshot.empty(1_000_000_000L).copy(
            sampleCount = 40,
            currentlyStill = true,
            stillnessMillis = 10_000,
        )
        // Right features, wrong state: must not arm.
        detector.update(features, MotionState.HANDHELD_STILL)
        assertEquals(PickupTransitionDetector.Stage.IDLE, detector.stage)

        detector.update(features, MotionState.STATIONARY_SURFACE)
        assertEquals(PickupTransitionDetector.Stage.ARMED, detector.stage)
    }

    @Test
    fun `after concluding, a fresh rest period is required before arming again`() {
        val h = run(SyntheticTraces.pickupFromDesk())
        assertEquals(1, h.acceptedPickups().size)
        assertEquals(
            "the detector must return to IDLE so one gesture cannot fire repeatedly",
            PickupTransitionDetector.Stage.IDLE,
            h.pipeline.pickupStage,
        )
    }

    @Test
    fun `one long disturbance does not emit a burst of pickups`() {
        val trace = SyntheticTraces.Builder(seed = 42)
            .setTilt(0f)
            .quiet(8_000, SyntheticTraces.ACCEL_SIGMA_DESK, SyntheticTraces.GYRO_SIGMA_DESK)
            .rotateDevice(600, angleDeg = 40f, axis = Vec3(1f, 0.2f, 0f), linearPeak = Vec3(0.8f, 0.6f, 2.6f))
            // A long stretch of being carried around afterwards.
            .oscillate(20_000, 1.9f, Vec3(0.9f, 0.7f, 2.4f), Vec3(0.35f, 0.25f, 0.15f), 0.35f, 0.05f, spikeScale = 1.6f)
            .build()
        val h = run(trace)
        assertTrue(
            "expected at most one pickup from a single gesture, got ${h.acceptedPickups().size}",
            h.acceptedPickups().size <= 1,
        )
    }

    @Test
    fun `reset clears the armed baseline`() {
        val detector = PickupTransitionDetector()
        val features = FeatureSnapshot.empty(1_000_000_000L).copy(
            sampleCount = 40,
            currentlyStill = true,
            stillnessMillis = 10_000,
        )
        detector.update(features, MotionState.STATIONARY_SURFACE)
        assertEquals(PickupTransitionDetector.Stage.ARMED, detector.stage)
        detector.reset()
        assertEquals(PickupTransitionDetector.Stage.IDLE, detector.stage)
    }

    // -- explainability -----------------------------------------------------

    @Test
    fun `an accepted pickup explains itself in the documented form`() {
        val pickup = run(SyntheticTraces.pickupFromDesk()).acceptedPickups().single()
        val text = pickup.explain()
        assertTrue(text, text.contains("stationary for"))
        assertTrue(text, text.contains("pickup confidence"))
        assertTrue(text, text.contains("orientation change"))
    }

    @Test
    fun `a rejected pickup names the gate it failed`() {
        val rejected = run(SyntheticTraces.deskBump()).rejectedPickups().last()
        assertNotNull(rejected.rejection)
        assertTrue(rejected.explain(), rejected.explain().contains("settled back"))
    }

    @Test
    fun `every rejection reason renders a non-empty explanation`() {
        for (reason in PickupRejection.entries) {
            val assessment = PickupAssessment(
                accepted = false,
                confidence = 0.1f,
                atNanos = 0L,
                evidence = emptySet(),
                rejection = reason,
                priorStillnessMillis = 0,
                orientationDeltaDeg = 0f,
                angularTravelRad = 0f,
                onsetPeak = 0f,
                onsetJerk = 0f,
                sustainedRms = 0f,
            )
            assertTrue("$reason produced an empty explanation", assessment.explain().isNotBlank())
        }
    }

    // -- degraded hardware --------------------------------------------------

    @Test
    fun `a pickup is still detectable without a gyroscope`() {
        val trace = SyntheticTraces.pickupFromDesk().map {
            it.copy(gyroX = 0f, gyroY = 0f, gyroZ = 0f, hasGyro = false)
        }
        val h = run(trace)
        assertTrue(
            "gravity-vector rotation alone should carry a pickup: ${h.explain()}",
            h.acceptedPickups().isNotEmpty(),
        )
        val pickup = h.acceptedPickups().first()
        assertFalse(
            "no gyro means no angular-travel evidence",
            pickup.evidence.contains(PickupEvidence.ANGULAR_TRAVEL),
        )
    }
}
