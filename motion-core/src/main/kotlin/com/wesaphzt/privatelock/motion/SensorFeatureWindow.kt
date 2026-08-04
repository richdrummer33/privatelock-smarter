package com.wesaphzt.privatelock.motion

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rolling short-term and baseline windows over the sensor stream, and the
 * feature extraction that runs on them.
 *
 * ## Gravity separation
 *
 * Classification needs acceleration *of the device*, separated from the
 * constant 9.81 m/s^2 of gravity. Where the platform offers a fused
 * `TYPE_GRAVITY` value it is used directly; otherwise a single-pole low-pass
 * filter with a configurable time constant estimates it:
 *
 *     alpha = dt / (tau + dt)
 *     g    += alpha * (a - g)
 *
 * The alpha is recomputed per sample from the actual inter-sample time rather
 * than assumed constant, because the sampler deliberately changes rate at
 * runtime and Android delivers sensor events with jitter. A fixed alpha would
 * silently change the filter's cutoff whenever the rate changed.
 *
 * tau = 0.5 s is a deliberate compromise. Shorter, and real linear
 * acceleration leaks into the gravity estimate, blunting the very features a
 * pickup depends on. Longer, and a genuine reorientation takes too long to
 * register inside the bounded pickup window.
 *
 * ## Cost
 *
 * [push] is O(short window) -- a few dozen samples -- because stillness has to
 * be tracked continuously. The expensive part, autocorrelation over the
 * baseline window, only runs inside [snapshot] and is rate-limited by
 * `periodicityIntervalMillis`, so raising the sensor rate does not raise the
 * autocorrelation cost.
 *
 * Storage is primitive arrays in a fixed-capacity circular buffer: an
 * always-on service must not allocate an object per sensor event.
 *
 * Not thread-safe. The sampler owns it and drives it from one thread.
 */
class SensorFeatureWindow(private val config: MotionConfig = MotionConfig()) {

    private val capacity = config.maxWindowSamples

    // Circular buffer, parallel primitive arrays.
    private val ts = LongArray(capacity)
    private val linX = FloatArray(capacity)
    private val linY = FloatArray(capacity)
    private val linZ = FloatArray(capacity)
    private val linMag = FloatArray(capacity)
    private val rawMag = FloatArray(capacity)
    private val gyroMag = FloatArray(capacity)
    private val gravX = FloatArray(capacity)
    private val gravY = FloatArray(capacity)
    private val gravZ = FloatArray(capacity)

    /** Index one past the newest element. */
    private var head = 0
    private var size = 0

    private var gravityInitialised = false
    private var gx = 0f
    private var gy = 0f
    private var gz = STANDARD_GRAVITY

    private var sawGyro = false
    private var lastTimestamp = 0L

    /** Start of the current uninterrupted stillness run; -1 when not still. */
    private var stillSinceNanos = -1L

    // Cached autocorrelation result. The "have we ever computed it" flag is a
    // separate boolean rather than a sentinel timestamp: `now - Long.MIN_VALUE`
    // overflows for any positive `now`, which would make the staleness check
    // read as false forever and silently disable periodicity detection.
    private var periodicityComputed = false
    private var cachedPeriodicityAtNanos = 0L
    private var cachedDominantFreqHz = 0f
    private var cachedPeriodicity = 0f

    val sampleCount: Int get() = size

    /** Current gravity estimate in the device frame. */
    val gravity: Vec3 get() = Vec3(gx, gy, gz)

    /** Discards all history. Used on sensor gaps, restarts and unpause. */
    fun reset() {
        head = 0
        size = 0
        gravityInitialised = false
        sawGyro = false
        stillSinceNanos = -1L
        lastTimestamp = 0L
        periodicityComputed = false
        cachedPeriodicityAtNanos = 0L
        cachedDominantFreqHz = 0f
        cachedPeriodicity = 0f
    }

    /**
     * Feeds one sample.
     *
     * Samples arriving out of order, or after a gap longer than
     * `maxSampleGapMillis`, discard the window rather than being stitched onto
     * stale history.
     */
    fun push(sample: SensorSample) {
        if (size > 0) {
            val gapNanos = sample.timestampNanos - lastTimestamp
            if (gapNanos <= 0L || gapNanos > config.maxSampleGapMillis * 1_000_000L) {
                // Out-of-order delivery or a stall (Doze, batching, restart).
                // Either way the buffered signal is no longer continuous.
                reset()
            }
        }

        val dtSeconds: Float = if (size == 0) {
            0f
        } else {
            (sample.timestampNanos - lastTimestamp) / 1e9f
        }

        updateGravity(sample, dtSeconds)

        val lx = sample.ax - gx
        val ly = sample.ay - gy
        val lz = sample.az - gz

        ts[head] = sample.timestampNanos
        linX[head] = lx
        linY[head] = ly
        linZ[head] = lz
        linMag[head] = sqrt(lx * lx + ly * ly + lz * lz)
        rawMag[head] = sample.accelMagnitude
        gyroMag[head] = sample.gyroMagnitude
        gravX[head] = gx
        gravY[head] = gy
        gravZ[head] = gz

        head = (head + 1) % capacity
        if (size < capacity) size++

        if (sample.hasGyro) sawGyro = true
        lastTimestamp = sample.timestampNanos

        trimTo(config.baselineWindowMillis, sample.timestampNanos)
        updateStillness(sample.timestampNanos)
    }

    private fun updateGravity(sample: SensorSample, dtSeconds: Float) {
        if (sample.hasHardwareGravity) {
            // Trust the platform's fused estimate when it exists; it uses the
            // gyro and is better than anything a low-pass can do.
            gx = sample.gravityX!!
            gy = sample.gravityY!!
            gz = sample.gravityZ!!
            gravityInitialised = true
            return
        }
        if (!gravityInitialised) {
            // Seed from the first sample. Starting from zero would introduce a
            // multi-second settling transient that looks like huge linear
            // acceleration and would poison the first stillness assessment.
            gx = sample.ax
            gy = sample.ay
            gz = sample.az
            gravityInitialised = true
            return
        }
        val tau = config.gravityFilterTauSeconds
        val dt = dtSeconds.coerceIn(0f, tau) // a long gap must not jump the filter
        val alpha = if (dt <= 0f) 0f else dt / (tau + dt)
        gx += alpha * (sample.ax - gx)
        gy += alpha * (sample.ay - gy)
        gz += alpha * (sample.az - gz)
    }

    /** Physical index of the [offset]-th newest element (0 = newest). */
    private fun idxFromNewest(offset: Int): Int = ((head - 1 - offset) % capacity + capacity) % capacity

    /** Physical index of the [offset]-th oldest element (0 = oldest). */
    private fun idxFromOldest(offset: Int): Int = ((head - size + offset) % capacity + capacity) % capacity

    private fun trimTo(windowMillis: Long, nowNanos: Long) {
        val cutoff = nowNanos - windowMillis * 1_000_000L
        while (size > 0 && ts[idxFromOldest(0)] < cutoff) {
            size--
        }
    }

    /** Number of newest samples that fall inside [windowMillis]. */
    private fun countWithin(windowMillis: Long, nowNanos: Long): Int {
        val cutoff = nowNanos - windowMillis * 1_000_000L
        var n = 0
        while (n < size && ts[idxFromNewest(n)] >= cutoff) n++
        return n
    }

    /**
     * Continuously maintained so that "how long has this been sitting still"
     * is available at any instant rather than only when a snapshot is taken.
     */
    private fun updateStillness(nowNanos: Long) {
        val n = countWithin(config.shortWindowMillis, nowNanos)
        if (n < MIN_SAMPLES_FOR_STILLNESS) {
            stillSinceNanos = -1L
            return
        }
        var sumSq = 0.0
        var peak = 0f
        var gyroSum = 0.0
        for (i in 0 until n) {
            val idx = idxFromNewest(i)
            val m = linMag[idx]
            sumSq += (m * m).toDouble()
            if (m > peak) peak = m
            gyroSum += gyroMag[idx].toDouble()
        }
        val rms = sqrt(sumSq / n).toFloat()
        val gyroMean = (gyroSum / n).toFloat()

        val still = rms <= config.stationaryRmsMax &&
            peak <= config.stationaryPeakMax &&
            (!sawGyro || gyroMean <= config.stationaryGyroMeanMax)

        if (still) {
            if (stillSinceNanos < 0L) {
                // The run began when the window that proves it began, not now.
                stillSinceNanos = ts[idxFromNewest(n - 1)]
            }
        } else {
            stillSinceNanos = -1L
        }
    }

    /** Milliseconds of uninterrupted stillness up to [nowNanos]. */
    fun stillnessMillis(nowNanos: Long): Long =
        if (stillSinceNanos < 0L) 0L else max(0L, (nowNanos - stillSinceNanos) / 1_000_000L)

    /**
     * Computes the full feature set over the current windows.
     *
     * Returns [FeatureSnapshot.empty] until enough samples have accumulated;
     * downstream stages treat that as UNKNOWN rather than as "still", so a
     * cold start cannot be mistaken for a resting device.
     */
    fun snapshot(): FeatureSnapshot {
        if (size < MIN_SAMPLES_FOR_STILLNESS) return FeatureSnapshot.empty(lastTimestamp)

        val now = lastTimestamp
        val shortN = max(2, countWithin(config.shortWindowMillis, now))
        val baseN = size

        // -- short-window linear acceleration -------------------------------
        var sum = 0.0
        var sumSq = 0.0
        var peak = 0f
        var rawPeak = 0f
        var rawMin = Float.MAX_VALUE
        var gyroSum = 0.0
        var gyroPeak = 0f
        var angularTravel = 0f
        var jerkPeak = 0f
        var impulse = 0f
        var freefallRunNanos = 0L
        var freefallBestNanos = 0L

        // Iterate oldest -> newest inside the short window so that differences
        // between consecutive samples are well defined.
        val firstShort = shortN - 1
        var prevIdx = -1
        for (i in firstShort downTo 0) {
            val idx = idxFromNewest(i)
            val m = linMag[idx]
            sum += m.toDouble()
            sumSq += (m * m).toDouble()
            if (m > peak) peak = m

            val rm = rawMag[idx]
            if (rm > rawPeak) rawPeak = rm
            if (rm < rawMin) rawMin = rm

            val gm = gyroMag[idx]
            gyroSum += gm.toDouble()
            if (gm > gyroPeak) gyroPeak = gm

            if (prevIdx >= 0) {
                val dtNanos = ts[idx] - ts[prevIdx]
                if (dtNanos > 0) {
                    val dt = dtNanos / 1e9f
                    val dx = linX[idx] - linX[prevIdx]
                    val dy = linY[idx] - linY[prevIdx]
                    val dz = linZ[idx] - linZ[prevIdx]
                    val jerk = sqrt(dx * dx + dy * dy + dz * dz) / dt
                    if (jerk > jerkPeak) jerkPeak = jerk

                    angularTravel += gm * dt
                    impulse += abs(rm - STANDARD_GRAVITY) * dt

                    if (rm < config.freefallAccelMax) {
                        freefallRunNanos += dtNanos
                        if (freefallRunNanos > freefallBestNanos) freefallBestNanos = freefallRunNanos
                    } else {
                        freefallRunNanos = 0L
                    }
                }
            }
            prevIdx = idx
        }

        val linMean = (sum / shortN).toFloat()
        val meanSq = (sumSq / shortN).toFloat()
        val rms = sqrt(meanSq)
        val variance = max(0f, meanSq - linMean * linMean)
        val gyroMean = (gyroSum / shortN).toFloat()

        // -- baseline-window linear acceleration ----------------------------
        var baseSumSq = 0.0
        var basePeak = 0f
        for (i in 0 until baseN) {
            val idx = idxFromNewest(i)
            val m = linMag[idx]
            baseSumSq += (m * m).toDouble()
            if (m > basePeak) basePeak = m
        }
        val baseRms = sqrt(baseSumSq / baseN).toFloat()

        // -- orientation ----------------------------------------------------
        val newest = idxFromNewest(0)
        val gNow = Vec3(gravX[newest], gravY[newest], gravZ[newest])
        val gShortStart = idxFromNewest(shortN - 1).let { Vec3(gravX[it], gravY[it], gravZ[it]) }
        val gBaseStart = idxFromOldest(0).let { Vec3(gravX[it], gravY[it], gravZ[it]) }

        val spanNanos = ts[newest] - ts[idxFromOldest(0)]
        val rateHz = if (spanNanos > 0) (baseN - 1) * 1e9f / spanNanos else 0f

        // -- periodicity (rate-limited) -------------------------------------
        if (!periodicityComputed ||
            now - cachedPeriodicityAtNanos >= config.periodicityIntervalMillis * 1_000_000L
        ) {
            computePeriodicity(baseN, rateHz)
            periodicityComputed = true
            cachedPeriodicityAtNanos = now
        }

        return FeatureSnapshot(
            timestampNanos = now,
            sampleCount = shortN,
            effectiveRateHz = rateHz,
            linAccMean = linMean,
            linAccVariance = variance,
            linAccRms = rms,
            linAccPeak = peak,
            baselineLinAccRms = baseRms,
            baselineLinAccPeak = basePeak,
            gyroMean = gyroMean,
            gyroPeak = gyroPeak,
            angularTravelRad = angularTravel,
            gyroAvailable = sawGyro,
            translationRotationRatio = if (!sawGyro) 0f else rms / (gyroMean + GYRO_RATIO_EPSILON),
            jerkPeak = jerkPeak,
            gravity = gNow,
            orientationDeltaDeg = angleBetweenDeg(gNow, gShortStart),
            baselineOrientationDeltaDeg = angleBetweenDeg(gNow, gBaseStart),
            tiltFromFlatDeg = angleBetweenDeg(gNow, FLAT_GRAVITY),
            rawAccPeak = rawPeak,
            rawAccMin = if (rawMin == Float.MAX_VALUE) 0f else rawMin,
            freefallMillis = freefallBestNanos / 1_000_000L,
            impactImpulse = impulse,
            dominantFreqHz = cachedDominantFreqHz,
            periodicity = cachedPeriodicity,
            stillnessMillis = stillnessMillis(now),
            currentlyStill = stillSinceNanos >= 0L,
        )
    }

    /**
     * Normalised autocorrelation of the linear-acceleration magnitude over the
     * baseline window.
     *
     * Gait is strongly periodic -- roughly 1.2-2.6 Hz walking and 2.2-4.5 Hz
     * running -- while hand tremor, engine vibration and a single disturbance
     * are not periodic in that band. Autocorrelation is used rather than an FFT
     * because the useful output here is a *strength* at a *dominant lag*, which
     * autocorrelation gives directly, at roughly 20k multiply-adds for a 4 s
     * window at 50 Hz. An FFT would cost more, need windowing, and give
     * frequency resolution this decision does not benefit from.
     *
     * Writes to the cached fields; call only from [snapshot].
     */
    private fun computePeriodicity(n: Int, rateHz: Float) {
        cachedDominantFreqHz = 0f
        cachedPeriodicity = 0f
        if (rateHz <= 0f || n < MIN_SAMPLES_FOR_PERIODICITY) return

        val minLag = max(2, (rateHz / MAX_GAIT_FREQ_HZ).toInt())
        val maxLag = min(n / 2, (rateHz / MIN_GAIT_FREQ_HZ).toInt())
        if (maxLag <= minLag) return

        // Copy oldest -> newest and remove the mean; autocorrelation of a
        // signal with a large DC offset is dominated by the offset.
        val x = FloatArray(n)
        var mean = 0.0
        for (i in 0 until n) {
            val v = linMag[idxFromOldest(i)]
            x[i] = v
            mean += v.toDouble()
        }
        val m = (mean / n).toFloat()
        var energy = 0.0
        for (i in 0 until n) {
            x[i] -= m
            energy += (x[i] * x[i]).toDouble()
        }
        if (energy < 1e-9) return

        val r = FloatArray(maxLag + 1)
        var bestR = 0f
        for (lag in minLag..maxLag) {
            var acc = 0.0
            val count = n - lag
            for (i in 0 until count) {
                acc += (x[i] * x[i + lag]).toDouble()
            }
            // Normalise by the energy the overlapping span would contribute at
            // zero lag, so that longer lags are not penalised merely for having
            // fewer terms. A perfectly periodic signal scores ~1 at its period.
            val normalised = (acc / (energy * count / n)).toFloat()
            r[lag] = normalised
            if (normalised > bestR) bestR = normalised
        }
        if (bestR <= 0f) return

        // Octave-error correction.
        //
        // A signal with period P also correlates strongly at 2P, 3P, ... and
        // noise can push one of those multiples marginally above the true peak.
        // Taking the global maximum therefore reports a sub-harmonic: a 2.9 Hz
        // running cadence comes back as 0.72 Hz, which falls outside the gait
        // band and makes running look like no gait at all.
        //
        // Standard fix, borrowed from pitch detection: among all lags that
        // score within a tolerance of the best, take the *shortest*. The true
        // fundamental is always the shortest strongly-correlating lag.
        val acceptable = bestR * OCTAVE_TOLERANCE
        var bestLag = 0
        for (lag in minLag..maxLag) {
            if (r[lag] >= acceptable) {
                // Only accept a local maximum, so we land on the peak rather
                // than on its rising flank.
                val leftLower = lag == minLag || r[lag - 1] <= r[lag]
                val rightLower = lag == maxLag || r[lag + 1] <= r[lag]
                if (leftLower && rightLower) {
                    bestLag = lag
                    break
                }
            }
        }
        if (bestLag == 0) return

        cachedDominantFreqHz = rateHz / bestLag
        cachedPeriodicity = r[bestLag].coerceIn(0f, 1f)
    }

    companion object {
        /** Below this the window cannot say anything meaningful. */
        const val MIN_SAMPLES_FOR_STILLNESS = 8
        const val MIN_SAMPLES_FOR_PERIODICITY = 32

        /**
         * Guards the translation/rotation ratio against division by a gyro
         * mean of zero. Sized at roughly a Pixel-class gyro noise floor so a
         * genuinely motionless device does not produce an enormous ratio.
         */
        const val GYRO_RATIO_EPSILON = 0.004f

        /**
         * Widest periodicity band searched. Wider than the gait bands in
         * [MotionConfig] on purpose: hand tremor sits around 4-12 Hz, and the
         * search has to be able to *represent* it in order to report it as
         * "periodic but not gait" rather than aliasing it down into the gait
         * band.
         */
        const val MIN_GAIT_FREQ_HZ = 0.7f
        const val MAX_GAIT_FREQ_HZ = 8.0f

        /**
         * A lag counts as "as good as the best" if it scores at least this
         * fraction of the peak correlation. Loose enough to catch a true
         * fundamental that noise pushed slightly below a harmonic, tight
         * enough not to latch onto unrelated short-lag structure.
         */
        const val OCTAVE_TOLERANCE = 0.85f

        private val FLAT_GRAVITY = Vec3(0f, 0f, STANDARD_GRAVITY)

        /** Angle between two vectors in degrees; 0 when either is degenerate. */
        fun angleBetweenDeg(a: Vec3, b: Vec3): Float {
            val ma = a.magnitude
            val mb = b.magnitude
            if (ma < 1e-4f || mb < 1e-4f) return 0f
            val cos = (a.dot(b) / (ma * mb)).coerceIn(-1f, 1f)
            return Math.toDegrees(acos(cos).toDouble()).toFloat()
        }
    }
}
