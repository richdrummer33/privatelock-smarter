package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionStateClassifierTest {

    // -- scenario classification -------------------------------------------

    @Test
    fun `a phone resting on a desk is classified as stationary surface`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.stationaryOnDesk(12_000))
        assertEquals(MotionState.STATIONARY_SURFACE, h.dominantState())
        assertTrue(h.fractionIn(MotionState.STATIONARY_SURFACE) > 0.8f)
    }

    @Test
    fun `a phone resting at an angle is still classified as stationary surface`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.stationaryOnDesk(12_000, tiltDeg = 35f))
        assertEquals(MotionState.STATIONARY_SURFACE, h.dominantState())
    }

    /**
     * The single most important negative in the classifier. Hand tremor is a
     * genuinely different noise floor from a hard surface, and if this line
     * blurs the pickup detector arms while the phone is already in a hand.
     */
    @Test
    fun `a phone held still is handheld and never stationary surface`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.handheldStill(12_000))
        assertEquals(MotionState.HANDHELD_STILL, h.dominantState())
        assertFalse(
            "held-still must never be mistaken for resting on a surface",
            h.everEntered(MotionState.STATIONARY_SURFACE),
        )
    }

    @Test
    fun `walking is classified as walking`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.walking(14_000))
        assertEquals(MotionState.WALKING, h.dominantState())
        assertFalse(h.everEntered(MotionState.STATIONARY_SURFACE))
    }

    @Test
    fun `running is classified as running and not as walking`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.running(14_000))
        assertEquals(MotionState.RUNNING, h.dominantState())
        assertFalse(h.everEntered(MotionState.STATIONARY_SURFACE))
    }

    @Test
    fun `raising and lowering the phone mid-walk stays in a carried state`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.raiseAndLowerWhileWalking())
        assertFalse(
            "changing grip while walking must never look like resting on a surface",
            h.everEntered(MotionState.STATIONARY_SURFACE),
        )
        assertTrue(h.everEntered(MotionState.WALKING))
    }

    @Test
    fun `sustained vehicle vibration eventually resolves to in-vehicle`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.vehicleMounted(30_000))
        assertTrue("expected IN_VEHICLE after sustained vibration", h.everEntered(MotionState.IN_VEHICLE))
        assertFalse(
            "a vibrating mount must never be mistaken for a still surface",
            h.everEntered(MotionState.STATIONARY_SURFACE),
        )
    }

    /**
     * IMU-only vehicle inference is weak, so it must persist before being
     * believed. A knocked desk briefly looks vehicle-like -- rigidly coupled,
     * low broadband energy, no gait -- and must not commit to that state.
     */
    @Test
    fun `a brief vehicle-like disturbance does not commit to in-vehicle`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.deskBump())
        assertFalse(h.everEntered(MotionState.IN_VEHICLE))
    }

    @Test
    fun `a desk bump does not knock the phone out of the stationary state for long`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.deskBump())
        assertEquals(MotionState.STATIONARY_SURFACE, h.dominantState())
        assertEquals(
            "the phone is on the desk at the end of the trace",
            MotionState.STATIONARY_SURFACE,
            h.timeline.last().second,
        )
    }

    @Test
    fun `picking the phone up moves it from stationary to a carried state`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.pickupFromDesk())
        assertTrue(h.everEntered(MotionState.STATIONARY_SURFACE))
        assertEquals(MotionState.HANDHELD_STILL, h.timeline.last().second)

        val order = h.transitions.map { it.to }
        assertTrue(
            "expected to leave STATIONARY_SURFACE during the pickup, saw $order",
            order.contains(MotionState.HANDHELD_STILL),
        )
    }

    @Test
    fun `setting the phone down ends in the stationary state`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.hardSetDown())
        assertEquals(MotionState.STATIONARY_SURFACE, h.timeline.last().second)
    }

    @Test
    fun `a drop is flagged as a possible drop`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.probableDrop())
        assertTrue(h.everEntered(MotionState.POSSIBLE_DROP))
    }

    // -- hysteresis and dwell ----------------------------------------------

    private fun snapshotAt(nanos: Long, rms: Float, still: Boolean, peak: Float = rms * 2f) =
        FeatureSnapshot.empty(nanos).copy(
            sampleCount = 40,
            effectiveRateHz = 50f,
            linAccRms = rms,
            linAccPeak = peak,
            linAccMean = rms,
            gyroAvailable = true,
            gyroMean = if (still) 0.003f else 0.05f,
            gyroPeak = if (still) 0.008f else 0.1f,
            translationRotationRatio = if (still) 1f else 5f,
            currentlyStill = still,
            stillnessMillis = if (still) 5_000 else 0,
        )

    @Test
    fun `a challenger must lead for the full dwell before the state changes`() {
        val config = MotionConfig(minDwellMillis = 700)
        val classifier = MotionStateClassifier(config)

        // Settle into STATIONARY_SURFACE.
        var t = 0L
        repeat(20) {
            classifier.update(snapshotAt(t, 0.02f, still = true))
            t += 100_000_000L
        }
        assertEquals(MotionState.STATIONARY_SURFACE, classifier.state)

        // Challenger appears but does not yet hold long enough.
        repeat(6) {
            classifier.update(snapshotAt(t, 0.3f, still = false))
            t += 100_000_000L
        }
        assertEquals(
            "600ms of challenger is under the 700ms dwell",
            MotionState.STATIONARY_SURFACE,
            classifier.state,
        )

        repeat(3) {
            classifier.update(snapshotAt(t, 0.3f, still = false))
            t += 100_000_000L
        }
        assertEquals(MotionState.HANDHELD_STILL, classifier.state)
    }

    @Test
    fun `alternating borderline snapshots do not make the state flap`() {
        val classifier = MotionStateClassifier()
        var t = 0L
        repeat(20) {
            classifier.update(snapshotAt(t, 0.02f, still = true))
            t += 100_000_000L
        }
        assertEquals(MotionState.STATIONARY_SURFACE, classifier.state)

        var transitions = 0
        repeat(40) { i ->
            val still = i % 2 == 0
            val rms = if (still) 0.02f else 0.3f
            if (classifier.update(snapshotAt(t, rms, still)) != null) transitions++
            t += 100_000_000L
        }
        assertEquals("a signal that alternates every 100ms must not commit anything", 0, transitions)
    }

    @Test
    fun `a drop is committed immediately without waiting for a dwell`() {
        val classifier = MotionStateClassifier()
        var t = 0L
        repeat(20) {
            classifier.update(snapshotAt(t, 0.02f, still = true))
            t += 100_000_000L
        }
        val impact = snapshotAt(t, 6f, still = false, peak = 20f).copy(
            rawAccPeak = 60f,
            freefallMillis = 200,
        )
        val transition = classifier.update(impact)
        assertEquals(MotionState.POSSIBLE_DROP, transition?.to)
    }

    @Test
    fun `an empty snapshot returns to unknown rather than holding a stale belief`() {
        val classifier = MotionStateClassifier()
        var t = 0L
        repeat(20) {
            classifier.update(snapshotAt(t, 0.02f, still = true))
            t += 100_000_000L
        }
        assertEquals(MotionState.STATIONARY_SURFACE, classifier.state)

        classifier.update(FeatureSnapshot.empty(t))
        assertEquals(
            "losing the sensor must not leave the classifier believing the phone is at rest",
            MotionState.UNKNOWN,
            classifier.state,
        )
    }

    @Test
    fun `transitions carry how long the previous state was held`() {
        val classifier = MotionStateClassifier()
        var t = 0L
        repeat(30) {
            classifier.update(snapshotAt(t, 0.02f, still = true))
            t += 100_000_000L
        }
        var transition: MotionStateTransition? = null
        repeat(15) {
            classifier.update(snapshotAt(t, 0.3f, still = false))?.let { transition = it }
            t += 100_000_000L
        }
        val committed = requireNonNull(transition)
        assertEquals(MotionState.STATIONARY_SURFACE, committed.from)
        assertTrue(
            "previous state duration ${committed.previousStateDurationMillis}ms should reflect the rest period",
            committed.previousStateDurationMillis >= 2_000,
        )
    }

    @Test
    fun `reset returns the classifier to unknown`() {
        val h = ClassifierHarness()
        h.feed(SyntheticTraces.stationaryOnDesk(6_000))
        assertEquals(MotionState.STATIONARY_SURFACE, h.classifier.state)
        h.classifier.reset()
        assertEquals(MotionState.UNKNOWN, h.classifier.state)
    }

    // -- activity hints -----------------------------------------------------

    @Test
    fun `an activity hint alone cannot change the state`() {
        val classifier = MotionStateClassifier()
        classifier.activityHint = ActivityHint.RUNNING
        // Features that are unambiguously "resting on a desk".
        var t = 0L
        var lastTransition: MotionStateTransition? = null
        repeat(40) {
            classifier.update(snapshotAt(t, 0.02f, still = true))?.let { lastTransition = it }
            t += 100_000_000L
        }
        assertEquals(
            "a late-arriving hint must never override the live sensor evidence",
            MotionState.STATIONARY_SURFACE,
            classifier.state,
        )
        assertEquals(MotionState.STATIONARY_SURFACE, lastTransition?.to)
    }

    @Test
    fun `a corroborating vehicle hint lets in-vehicle commit on the normal dwell`() {
        val withoutHint = ClassifierHarness()
        withoutHint.feed(SyntheticTraces.vehicleMounted(9_000))
        assertFalse(
            "9 seconds is under the 12 second local vehicle dwell",
            withoutHint.everEntered(MotionState.IN_VEHICLE),
        )

        val withHint = ClassifierHarness()
        withHint.classifier.activityHint = ActivityHint.IN_VEHICLE
        withHint.feed(SyntheticTraces.vehicleMounted(9_000))
        assertTrue(
            "an independent vehicle report justifies believing it sooner",
            withHint.everEntered(MotionState.IN_VEHICLE),
        )
    }

    // -- state helpers ------------------------------------------------------

    @Test
    fun `state helper predicates match their intent`() {
        assertTrue(MotionState.STATIONARY_SURFACE.isAtRest)
        assertFalse(MotionState.HANDHELD_STILL.isAtRest)
        assertFalse(MotionState.UNKNOWN.isAtRest)

        assertTrue(MotionState.WALKING.suppressesPickupLock)
        assertTrue(MotionState.RUNNING.suppressesPickupLock)
        assertTrue(MotionState.IN_VEHICLE.suppressesPickupLock)
        assertTrue(MotionState.ON_BICYCLE.suppressesPickupLock)
        assertTrue(MotionState.HANDHELD_STILL.suppressesPickupLock)
        assertFalse(MotionState.STATIONARY_SURFACE.suppressesPickupLock)
        assertFalse(MotionState.UNKNOWN.suppressesPickupLock)
    }

    @Test
    fun `unknown scores above nothing so a silent classifier admits uncertainty`() {
        val classifier = MotionStateClassifier()
        assertNull(classifier.update(FeatureSnapshot.empty(0L)))
        assertEquals(MotionState.UNKNOWN, classifier.state)
    }

    private fun <T : Any> requireNonNull(v: T?): T = requireNotNull(v) { "expected a committed transition" }
}
