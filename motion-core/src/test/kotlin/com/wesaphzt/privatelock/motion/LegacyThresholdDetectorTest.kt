package com.wesaphzt.privatelock.motion

import com.wesaphzt.privatelock.motion.legacy.LegacyThresholdDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterisation tests for the original algorithm.
 *
 * These do not assert that the old behaviour is *good*. They pin down what it
 * actually does, so that the replacement can be justified with measurements
 * rather than assertion, and so the preserved fallback path cannot drift.
 *
 * The original registered its listener at `SENSOR_DELAY_NORMAL`, which is
 * roughly 5 Hz, so the scenario tests evaluate it at that cadence -- that is
 * the behaviour users actually experienced.
 */
class LegacyThresholdDetectorTest {

    /** The delivery rate the shipped app really used. */
    private val legacyRateDecimation = 10 // 50 Hz synthetic -> 5 Hz

    private fun hitsAtLegacyRate(trace: List<SensorSample>): Int {
        val detector = LegacyThresholdDetector()
        return SyntheticTraces.decimate(trace, legacyRateDecimation).count { detector.onSample(it) }
    }

    // -- mechanical behaviour ----------------------------------------------

    @Test
    fun `first sample never triggers because it only seeds the baseline`() {
        val detector = LegacyThresholdDetector()
        assertFalse(detector.onSample(SensorSample(0, 100f, 100f, 100f)))
    }

    @Test
    fun `a single sample pair is enough to lock - the core design flaw`() {
        val detector = LegacyThresholdDetector()
        detector.onSample(SensorSample(0, 0f, 0f, 9.81f))
        assertTrue(
            "legacy detector locks from one sample pair",
            detector.onSample(SensorSample(20_000_000, 0f, 0f, 25f)),
        )
    }

    @Test
    fun `reset clears the baseline so the next sample cannot trigger`() {
        val detector = LegacyThresholdDetector()
        detector.onSample(SensorSample(0, 0f, 0f, 9.81f))
        detector.reset()
        assertFalse(detector.onSample(SensorSample(20_000_000, 0f, 0f, 40f)))
    }

    @Test
    fun `per-axis dead-band suppresses a smooth change that is large overall`() {
        val detector = LegacyThresholdDetector()
        detector.onSample(SensorSample(0, 0f, 0f, 0f))
        // 1.9 on each axis: magnitude 3.29, but every axis is under NOISE=2.0,
        // so the legacy detector scores exactly zero.
        assertFalse(detector.onSample(SensorSample(20_000_000, 1.9f, 1.9f, 1.9f)))
        assertEquals(0f, detector.lastScore, 1e-6f)
    }

    @Test
    fun `threshold comparison is strictly greater-than`() {
        val detector = LegacyThresholdDetector(sensitivity = 10f)
        detector.onSample(SensorSample(0, 0f, 0f, 0f))
        assertFalse(detector.onSample(SensorSample(20_000_000, 10f, 0f, 0f)))
    }

    // -- scenario behaviour, measured --------------------------------------

    @Test
    fun `still phone on a desk does not trigger`() {
        assertEquals(0, hitsAtLegacyRate(SyntheticTraces.stationaryOnDesk(10_000)))
    }

    @Test
    fun `handheld still does not trigger`() {
        assertEquals(0, hitsAtLegacyRate(SyntheticTraces.handheldStill(10_000)))
    }

    /**
     * The headline failure. The app's entire purpose is to lock when the phone
     * is lifted off a surface, and at the rate the app actually sampled, the
     * legacy detector does not notice a normal pickup at all: a lift is a
     * smooth, sub-threshold change spread over several hundred milliseconds.
     */
    @Test
    fun `legacy detector misses a real pickup entirely - the headline failure`() {
        assertEquals(
            "legacy detector should not detect the pickup it exists to detect",
            0,
            hitsAtLegacyRate(SyntheticTraces.pickupFromDesk()),
        )
        assertEquals(0, hitsAtLegacyRate(SyntheticTraces.gentlePickupFromDesk()))
    }

    /** Fires while the user is merely jogging with their own phone. */
    @Test
    fun `running triggers the legacy detector repeatedly`() {
        val hits = hitsAtLegacyRate(SyntheticTraces.running(12_000))
        assertTrue("expected repeated false positives while running, got $hits", hits > 10)
    }

    /** Fires when the owner puts the phone down, which locks for no reason. */
    @Test
    fun `putting the phone down firmly triggers the legacy detector`() {
        assertTrue(hitsAtLegacyRate(SyntheticTraces.hardSetDown()) > 0)
    }

    /**
     * Walking is borderline rather than a guaranteed false positive at the
     * default sensitivity of 10; recorded here so the claim is not overstated.
     */
    @Test
    fun `walking sits just under the default threshold`() {
        assertEquals(0, hitsAtLegacyRate(SyntheticTraces.walking(12_000)))
    }

    /** Drop is only caught at high sample rates, not the rate the app used. */
    @Test
    fun `drop is missed at the legacy sample rate but caught at 50 Hz`() {
        assertEquals(0, hitsAtLegacyRate(SyntheticTraces.probableDrop()))

        val fast = LegacyThresholdDetector()
        assertTrue(SyntheticTraces.probableDrop().count { fast.onSample(it) } > 0)
    }
}
