package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SensorFeatureWindowTest {

    private fun feed(window: SensorFeatureWindow, trace: List<SensorSample>) {
        trace.forEach { window.push(it) }
    }

    // -- gravity separation -------------------------------------------------

    @Test
    fun `gravity is seeded from the first sample so there is no settling transient`() {
        val window = SensorFeatureWindow()
        // A device resting on its side: gravity entirely on x.
        feed(window, SyntheticTraces.Builder().setTilt(90f).quiet(2_000, 0.02f, 0.002f).build())
        val f = window.snapshot()
        assertTrue(
            "gravity should have converged onto the x axis, got ${f.gravity}",
            abs(f.gravity.x) > 9.0f,
        )
        assertTrue("no spurious linear acceleration at rest", f.linAccRms < 0.1f)
    }

    @Test
    fun `linear acceleration at rest sits at the sensor noise floor`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(6_000))
        val f = window.snapshot()
        assertTrue("rms ${f.linAccRms} should be tiny at rest", f.linAccRms < 0.08f)
        assertTrue("raw magnitude should be ~1g, got ${f.rawAccPeak}", abs(f.rawAccPeak - STANDARD_GRAVITY) < 0.5f)
    }

    @Test
    fun `tilt is reported relative to lying flat`() {
        val flat = SensorFeatureWindow().also { feed(it, SyntheticTraces.stationaryOnDesk(3_000, tiltDeg = 0f)) }
        val angled = SensorFeatureWindow().also { feed(it, SyntheticTraces.stationaryOnDesk(3_000, tiltDeg = 40f)) }
        assertTrue(flat.snapshot().tiltFromFlatDeg < 5f)
        assertTrue(
            "40 degree tilt should be reported near 40, got ${angled.snapshot().tiltFromFlatDeg}",
            abs(angled.snapshot().tiltFromFlatDeg - 40f) < 8f,
        )
    }

    @Test
    fun `rotating the device produces a matching orientation delta`() {
        val window = SensorFeatureWindow()
        val trace = SyntheticTraces.Builder()
            .setTilt(0f)
            .quiet(3_000, 0.02f, 0.002f)
            .rotateDevice(600, angleDeg = 50f, axis = Vec3(1f, 0f, 0f))
            .quiet(400, 0.05f, 0.01f)
            .build()
        feed(window, trace)
        val f = window.snapshot()
        assertTrue(
            "baseline orientation delta ${f.baselineOrientationDeltaDeg} should approach 50 degrees",
            f.baselineOrientationDeltaDeg > 35f,
        )
        assertTrue(
            "integrated gyro ${f.angularTravelRad} rad should be non-trivial",
            f.angularTravelRad > 0.1f,
        )
    }

    // -- stillness ----------------------------------------------------------

    @Test
    fun `stillness duration accumulates while resting and uses sample timestamps`() {
        val window = SensorFeatureWindow()
        val trace = SyntheticTraces.stationaryOnDesk(10_000)
        feed(window, trace)
        val f = window.snapshot()
        assertTrue("expected several seconds of stillness, got ${f.stillnessMillis}ms", f.stillnessMillis > 8_000)
        assertTrue(f.currentlyStill)

        // The value must come from the trace's own timestamps, not from any
        // ambient clock: replaying the same trace shifted far into the future
        // must produce the same answer.
        val shifted = SensorFeatureWindow()
        feed(shifted, trace.map { it.copy(timestampNanos = it.timestampNanos + 500_000_000_000L) })
        assertEquals(f.stillnessMillis, shifted.snapshot().stillnessMillis)
    }

    @Test
    fun `stillness resets the moment the device is disturbed`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(6_000))
        assertTrue(window.snapshot().stillnessMillis > 4_000)

        feed(window, SyntheticTraces.Builder(seed = 99).setTilt(0f).impulse(120, peak = Vec3(2f, 2f, 5f)).build())
        assertEquals(0L, window.snapshot().stillnessMillis)
    }

    @Test
    fun `a handheld device is never reported as still`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.handheldStill(8_000))
        val f = window.snapshot()
        assertFalse("hand tremor must not read as surface-still", f.currentlyStill)
        assertEquals(0L, f.stillnessMillis)
    }

    // -- gaps and ordering --------------------------------------------------

    @Test
    fun `a delivery gap discards the window rather than stitching stale data`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(8_000))
        assertTrue(window.snapshot().stillnessMillis > 6_000)

        // Simulate the device dozing for a minute and then delivering again.
        val last = 8_000_000_000L
        window.push(SensorSample(last + 60_000_000_000L, 0f, 0f, STANDARD_GRAVITY, hasGyro = true))
        assertEquals("history across a stall must not count as continuous stillness", 0L, window.snapshot().stillnessMillis)
    }

    @Test
    fun `out-of-order samples do not corrupt the window`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(4_000))
        val before = window.sampleCount
        // A sample from the past: must reset rather than produce a negative dt.
        window.push(SensorSample(1_000L, 0f, 0f, STANDARD_GRAVITY, hasGyro = true))
        assertTrue(window.sampleCount <= before)
        assertEquals(0L, window.snapshot().stillnessMillis)
    }

    @Test
    fun `window is bounded by the baseline duration regardless of rate`() {
        val config = MotionConfig(baselineWindowMillis = 2_000)
        val window = SensorFeatureWindow(config)
        feed(window, SyntheticTraces.stationaryOnDesk(20_000))
        // 2 s at 50 Hz is about 100 samples; allow slack for boundary handling.
        assertTrue("window grew to ${window.sampleCount} samples", window.sampleCount in 80..120)
    }

    @Test
    fun `an empty window reports nothing rather than guessing`() {
        val f = SensorFeatureWindow().snapshot()
        assertEquals(0, f.sampleCount)
        assertFalse(f.currentlyStill)
    }

    // -- periodicity --------------------------------------------------------

    @Test
    fun `walking cadence is recovered at the correct frequency`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.walking(12_000))
        val f = window.snapshot()
        assertTrue("periodicity ${f.periodicity} should be high for gait", f.periodicity > 0.35f)
        assertTrue(
            "expected ~1.9 Hz walking cadence, got ${f.dominantFreqHz}",
            abs(f.dominantFreqHz - 1.9f) < 0.5f,
        )
    }

    /**
     * Guards the octave-error correction. Autocorrelation peaks at every
     * multiple of the true period, so a naive global maximum reports a
     * sub-harmonic -- 2.9 Hz running came back as 0.72 Hz before the fix, which
     * put it outside the gait band entirely.
     */
    @Test
    fun `running cadence is not reported as a sub-harmonic`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.running(12_000))
        val f = window.snapshot()
        assertTrue("periodicity ${f.periodicity} should be high for gait", f.periodicity > 0.35f)
        assertTrue(
            "expected ~2.9 Hz running cadence, got ${f.dominantFreqHz}",
            abs(f.dominantFreqHz - 2.9f) < 0.6f,
        )
    }

    @Test
    fun `a resting device has no meaningful periodicity`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(8_000))
        assertTrue(window.snapshot().periodicity < 0.35f)
    }

    // -- impact and free fall ----------------------------------------------

    @Test
    fun `free fall is measured and an impact peak is recorded`() {
        val window = SensorFeatureWindow()
        var maxFreefall = 0L
        var maxImpact = 0f
        SyntheticTraces.probableDrop().forEach {
            window.push(it)
            val f = window.snapshot()
            if (f.freefallMillis > maxFreefall) maxFreefall = f.freefallMillis
            if (f.rawAccPeak > maxImpact) maxImpact = f.rawAccPeak
        }
        assertTrue("expected a measurable free-fall interval, got ${maxFreefall}ms", maxFreefall >= 120)
        assertTrue("expected a large impact peak, got $maxImpact", maxImpact > 25f)
    }

    @Test
    fun `resting device shows no free fall`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(6_000))
        assertEquals(0L, window.snapshot().freefallMillis)
    }

    // -- coupling ratio -----------------------------------------------------

    @Test
    fun `a rigidly mounted device shows far higher translation-rotation coupling than a hand`() {
        val mounted = SensorFeatureWindow().also { feed(it, SyntheticTraces.vehicleMounted(12_000)) }
        val held = SensorFeatureWindow().also { feed(it, SyntheticTraces.handheldStill(12_000)) }
        val mountedRatio = mounted.snapshot().translationRotationRatio
        val heldRatio = held.snapshot().translationRotationRatio
        assertTrue(
            "mounted=$mountedRatio should clearly exceed held=$heldRatio",
            mountedRatio > heldRatio * 2f,
        )
    }

    @Test
    fun `without a gyroscope the gyro-derived features read zero rather than garbage`() {
        val window = SensorFeatureWindow()
        val trace = SyntheticTraces.stationaryOnDesk(4_000).map {
            it.copy(gyroX = 0f, gyroY = 0f, gyroZ = 0f, hasGyro = false)
        }
        feed(window, trace)
        val f = window.snapshot()
        assertFalse(f.gyroAvailable)
        assertEquals(0f, f.gyroMean, 1e-6f)
        assertEquals(0f, f.translationRotationRatio, 1e-6f)
        // Stillness must still work; the accelerometer alone is enough.
        assertTrue(f.currentlyStill)
    }

    @Test
    fun `reset clears all history`() {
        val window = SensorFeatureWindow()
        feed(window, SyntheticTraces.stationaryOnDesk(6_000))
        assertNotEquals(0, window.sampleCount)
        window.reset()
        assertEquals(0, window.sampleCount)
        assertEquals(0, window.snapshot().sampleCount)
    }
}
