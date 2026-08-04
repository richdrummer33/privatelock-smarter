package com.wesaphzt.privatelock.motion

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic sensor traces.
 *
 * These stand in for recorded device captures so that the detection pipeline
 * has regression coverage that runs on a bare JVM. Every generator is seeded,
 * so a test that passes once passes forever unless the algorithm changes.
 *
 * The traces are *physically self-consistent* in the ways that matter to the
 * detector: when the device rotates, the gravity vector in the device frame
 * rotates by the corresponding amount and the gyroscope reports the angular
 * velocity that produced it. A detector cannot pass these by exploiting a
 * mismatch that real hardware would not have.
 *
 * They are not a substitute for real captures. `TraceReplayer` exists so that
 * traces recorded on an actual Pixel 8 Pro can be dropped into the same
 * harness; see `docs/motion-detection.md`.
 */
object SyntheticTraces {

    const val DEFAULT_RATE_HZ = 50

    // -- vector helpers -----------------------------------------------------

    private fun normalise(v: Vec3): Vec3 {
        val m = v.magnitude
        return if (m < 1e-6f) Vec3(0f, 0f, 1f) else Vec3(v.x / m, v.y / m, v.z / m)
    }

    private fun cross(a: Vec3, b: Vec3) = Vec3(
        a.y * b.z - a.z * b.y,
        a.z * b.x - a.x * b.z,
        a.x * b.y - a.y * b.x,
    )

    /** Rodrigues rotation of [v] about unit axis [k] by [angleRad]. */
    private fun rotate(v: Vec3, k: Vec3, angleRad: Float): Vec3 {
        val c = cos(angleRad)
        val s = sin(angleRad)
        val kv = cross(k, v)
        val kdotv = k.dot(v)
        return Vec3(
            v.x * c + kv.x * s + k.x * kdotv * (1 - c),
            v.y * c + kv.y * s + k.y * kdotv * (1 - c),
            v.z * c + kv.z * s + k.z * kdotv * (1 - c),
        )
    }

    /** Smooth 0->1 ramp with zero derivative at both ends. */
    private fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** Derivative of [smoothstep], used to keep gyro consistent with rotation. */
    private fun smoothstepDerivative(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return 6f * x * (1f - x)
    }

    /**
     * Gravity direction in the device frame for a device tilted [tiltDeg] from
     * flat-on-its-back, rotated [azimuthDeg] about the device's own z axis.
     */
    fun gravityForTilt(tiltDeg: Float, azimuthDeg: Float = 0f): Vec3 {
        val t = tiltDeg * PI.toFloat() / 180f
        val a = azimuthDeg * PI.toFloat() / 180f
        // Flat on a table: gravity reads +z in the device frame (~ +9.81).
        return Vec3(
            sin(t) * cos(a) * STANDARD_GRAVITY,
            sin(t) * sin(a) * STANDARD_GRAVITY,
            cos(t) * STANDARD_GRAVITY,
        )
    }

    // -- builder ------------------------------------------------------------

    /**
     * Accumulates samples on a fixed cadence, tracking the running timestamp so
     * segments can be concatenated seamlessly.
     */
    class Builder(
        private val rateHz: Int = DEFAULT_RATE_HZ,
        seed: Int = 20260804,
        startNanos: Long = 1_000_000_000L,
    ) {
        private val rng = Random(seed)
        private val out = ArrayList<SensorSample>()
        private val periodNanos = 1_000_000_000L / rateHz
        private var t = startNanos

        /** Current gravity direction in the device frame. */
        var gravity: Vec3 = gravityForTilt(0f)
            private set

        val samples: List<SensorSample> get() = out
        val durationMillis: Long get() = if (out.isEmpty()) 0 else (out.last().timestampNanos - out.first().timestampNanos) / 1_000_000

        private fun gauss(sigma: Float): Float {
            if (sigma <= 0f) return 0f
            // Box-Muller; one draw per call is fine for test data.
            val u1 = (rng.nextDouble() + 1e-12).coerceAtMost(1.0)
            val u2 = rng.nextDouble()
            return (sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * PI * u2)).toFloat() * sigma
        }

        private fun emit(linear: Vec3, gyro: Vec3, accelSigma: Float, gyroSigma: Float) {
            out += SensorSample(
                timestampNanos = t,
                ax = gravity.x + linear.x + gauss(accelSigma),
                ay = gravity.y + linear.y + gauss(accelSigma),
                az = gravity.z + linear.z + gauss(accelSigma),
                gyroX = gyro.x + gauss(gyroSigma),
                gyroY = gyro.y + gauss(gyroSigma),
                gyroZ = gyro.z + gauss(gyroSigma),
                hasGyro = true,
            )
            t += periodNanos
        }

        /** Quiescent segment with the given noise floor. */
        fun quiet(
            durationMillis: Long,
            accelSigma: Float,
            gyroSigma: Float,
            bias: Vec3 = Vec3.ZERO,
        ): Builder {
            repeat(sampleCount(durationMillis)) {
                emit(bias, Vec3.ZERO, accelSigma, gyroSigma)
            }
            return this
        }

        /**
         * Periodic segment, used for gait and vibration.
         *
         * [spikeScale] adds a narrow per-cycle transient on top of the
         * sinusoid. Real gait is not sinusoidal: heel strike produces a short,
         * sharp impulse, and that transient is both what a naive
         * delta-threshold detector reacts to and what an autocorrelator locks
         * onto. Leaving it out would make walking traces unrealistically easy
         * to ignore. Set to 0 for smooth vibration such as an engine.
         */
        fun oscillate(
            durationMillis: Long,
            freqHz: Float,
            amplitude: Vec3,
            gyroAmplitude: Vec3 = Vec3.ZERO,
            accelSigma: Float = 0.15f,
            gyroSigma: Float = 0.02f,
            harmonic2: Float = 0.35f,
            spikeScale: Float = 0f,
            spikeWidthCycles: Float = 0.04f,
        ): Builder {
            val n = sampleCount(durationMillis)
            val dt = 1f / rateHz
            var phase = rng.nextFloat() * 2f * PI.toFloat()
            val twoPi = 2f * PI.toFloat()
            repeat(n) {
                val s = sin(phase)
                val s2 = sin(2f * phase) * harmonic2
                var w = s + s2
                var gyroW = s
                if (spikeScale > 0f) {
                    // Distance to the nearest cycle boundary, wrapped to [-0.5, 0.5).
                    var cyclePos = ((phase / twoPi) % 1f + 1f) % 1f
                    if (cyclePos > 0.5f) cyclePos -= 1f
                    val e = cyclePos / spikeWidthCycles
                    val spike = kotlin.math.exp(-0.5f * e * e)
                    w += spikeScale * spike
                    gyroW += spikeScale * 0.5f * spike
                }
                emit(
                    Vec3(amplitude.x * w, amplitude.y * w, amplitude.z * w),
                    Vec3(gyroAmplitude.x * gyroW, gyroAmplitude.y * gyroW, gyroAmplitude.z * gyroW),
                    accelSigma,
                    gyroSigma,
                )
                phase += twoPi * freqHz * dt
            }
            return this
        }

        /**
         * Rotates the device by [angleDeg] about [axis] over [durationMillis],
         * emitting a physically consistent gyro signal and, optionally, a
         * translational acceleration profile on top.
         */
        fun rotateDevice(
            durationMillis: Long,
            angleDeg: Float,
            axis: Vec3 = Vec3(1f, 0f, 0f),
            linearPeak: Vec3 = Vec3.ZERO,
            accelSigma: Float = 0.08f,
            gyroSigma: Float = 0.01f,
        ): Builder {
            val n = sampleCount(durationMillis)
            if (n == 0) return this
            val k = normalise(axis)
            val totalRad = angleDeg * PI.toFloat() / 180f
            val durSec = durationMillis / 1000f
            val startGravity = gravity
            repeat(n) { i ->
                val u = (i + 1).toFloat() / n
                val frac = smoothstep(u)
                // Gravity in the device frame counter-rotates as the device turns.
                gravity = rotate(startGravity, k, -totalRad * frac)
                val omega = totalRad * smoothstepDerivative(u) / durSec
                // Translational component follows the same bell shape.
                val bell = smoothstepDerivative(u) / 1.5f
                emit(
                    Vec3(linearPeak.x * bell, linearPeak.y * bell, linearPeak.z * bell),
                    Vec3(k.x * omega, k.y * omega, k.z * omega),
                    accelSigma,
                    gyroSigma,
                )
            }
            return this
        }

        /** Short acceleration impulse without net reorientation (a bump/tap). */
        fun impulse(
            durationMillis: Long,
            peak: Vec3,
            gyroPeak: Vec3 = Vec3.ZERO,
            accelSigma: Float = 0.05f,
            gyroSigma: Float = 0.008f,
        ): Builder {
            val n = sampleCount(durationMillis)
            if (n == 0) return this
            repeat(n) { i ->
                val u = (i + 0.5f) / n
                val bell = sin(PI.toFloat() * u)
                emit(
                    Vec3(peak.x * bell, peak.y * bell, peak.z * bell),
                    Vec3(gyroPeak.x * bell, gyroPeak.y * bell, gyroPeak.z * bell),
                    accelSigma,
                    gyroSigma,
                )
            }
            return this
        }

        /** Free fall: the accelerometer reads ~0 in every axis. */
        fun freefall(durationMillis: Long, tumbleRadPerSec: Float = 4f): Builder {
            val n = sampleCount(durationMillis)
            val saved = gravity
            gravity = Vec3.ZERO
            repeat(n) {
                emit(Vec3.ZERO, Vec3(tumbleRadPerSec, tumbleRadPerSec * 0.4f, 0f), 0.15f, 0.05f)
            }
            gravity = saved
            return this
        }

        fun setTilt(tiltDeg: Float, azimuthDeg: Float = 0f): Builder {
            gravity = gravityForTilt(tiltDeg, azimuthDeg)
            return this
        }

        fun build(): List<SensorSample> = out.toList()

        private fun sampleCount(durationMillis: Long) = ((durationMillis * rateHz) / 1000L).toInt()
    }

    /**
     * Drops samples to emulate a slower sensor delivery rate. The original app
     * registered at `SENSOR_DELAY_NORMAL` (~5 Hz), so legacy-behaviour tests
     * must evaluate the old detector at that cadence rather than at 50 Hz.
     */
    fun decimate(trace: List<SensorSample>, factor: Int): List<SensorSample> {
        require(factor >= 1)
        return trace.filterIndexed { i, _ -> i % factor == 0 }
    }

    // -- named scenarios ----------------------------------------------------

    /** Phone lying flat and still on a hard surface. */
    fun stationaryOnDesk(durationMillis: Long = 10_000, tiltDeg: Float = 0f, seed: Int = 1): List<SensorSample> =
        Builder(seed = seed).setTilt(tiltDeg).quiet(durationMillis, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK).build()

    /** Phone held in a hand, user trying to keep it still. Tremor dominates. */
    fun handheldStill(durationMillis: Long = 10_000, seed: Int = 2): List<SensorSample> =
        Builder(seed = seed).setTilt(35f)
            .oscillate(
                durationMillis,
                freqHz = 6f,
                amplitude = Vec3(0.12f, 0.10f, 0.14f),
                gyroAmplitude = Vec3(0.03f, 0.04f, 0.02f),
                accelSigma = 0.10f,
                gyroSigma = 0.012f,
                harmonic2 = 0.6f,
            ).build()

    /** Walking while carrying the phone: ~1.9 Hz gait. */
    fun walking(durationMillis: Long = 12_000, seed: Int = 3): List<SensorSample> =
        Builder(seed = seed).setTilt(40f)
            .oscillate(
                durationMillis,
                freqHz = 1.9f,
                amplitude = Vec3(0.9f, 0.7f, 2.4f),
                gyroAmplitude = Vec3(0.35f, 0.25f, 0.15f),
                accelSigma = 0.35f,
                gyroSigma = 0.05f,
                spikeScale = 1.6f,
            ).build()

    /** Jogging: faster cadence, much larger peaks. */
    fun running(durationMillis: Long = 12_000, seed: Int = 4): List<SensorSample> =
        Builder(seed = seed).setTilt(45f)
            .oscillate(
                durationMillis,
                freqHz = 2.9f,
                amplitude = Vec3(3.0f, 2.4f, 8.5f),
                gyroAmplitude = Vec3(1.1f, 0.8f, 0.5f),
                accelSigma = 0.9f,
                gyroSigma = 0.12f,
                spikeScale = 1.5f,
            ).build()

    /** Phone in a car mount, engine running, normal road. */
    fun vehicleMounted(durationMillis: Long = 20_000, seed: Int = 5): List<SensorSample> =
        Builder(seed = seed).setTilt(70f)
            .oscillate(
                durationMillis,
                freqHz = 14f,
                amplitude = Vec3(0.18f, 0.15f, 0.22f),
                gyroAmplitude = Vec3(0.01f, 0.01f, 0.01f),
                accelSigma = 0.30f,
                gyroSigma = 0.010f,
                harmonic2 = 0.8f,
            ).build()

    /** Someone knocks the desk: brief impulse, phone stays put. */
    fun deskBump(seed: Int = 6): List<SensorSample> =
        Builder(seed = seed).setTilt(0f)
            .quiet(6_000, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .impulse(70, peak = Vec3(1.5f, 1.2f, 4.5f), gyroPeak = Vec3(0.25f, 0.18f, 0.05f))
            .quiet(6_000, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .build()

    /**
     * The scenario the whole app exists for: phone sits untouched, then someone
     * lifts it off the surface and holds it.
     */
    fun pickupFromDesk(
        stillMillis: Long = 8_000,
        rotationDeg: Float = 42f,
        seed: Int = 7,
    ): List<SensorSample> =
        Builder(seed = seed).setTilt(0f)
            .quiet(stillMillis, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .impulse(90, peak = Vec3(0.6f, 0.5f, 2.2f), gyroPeak = Vec3(0.5f, 0.3f, 0.15f))
            .rotateDevice(
                durationMillis = 620,
                angleDeg = rotationDeg,
                axis = Vec3(1f, 0.25f, 0f),
                linearPeak = Vec3(0.8f, 0.6f, 2.6f),
                accelSigma = 0.20f,
                gyroSigma = 0.02f,
            )
            .oscillate(
                3_000,
                freqHz = 6f,
                amplitude = Vec3(0.14f, 0.11f, 0.16f),
                gyroAmplitude = Vec3(0.04f, 0.05f, 0.03f),
                accelSigma = 0.11f,
                gyroSigma = 0.014f,
                harmonic2 = 0.6f,
            ).build()

    /** A careful, slow lift: weak onset, smaller rotation, still a real pickup. */
    fun gentlePickupFromDesk(seed: Int = 8): List<SensorSample> =
        Builder(seed = seed).setTilt(0f)
            .quiet(8_000, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .impulse(140, peak = Vec3(0.25f, 0.2f, 0.9f), gyroPeak = Vec3(0.22f, 0.15f, 0.06f))
            .rotateDevice(
                durationMillis = 1_000,
                angleDeg = 26f,
                axis = Vec3(1f, 0.2f, 0f),
                linearPeak = Vec3(0.3f, 0.25f, 1.0f),
                accelSigma = 0.12f,
                gyroSigma = 0.015f,
            )
            .oscillate(
                3_000,
                freqHz = 6f,
                amplitude = Vec3(0.13f, 0.10f, 0.15f),
                gyroAmplitude = Vec3(0.035f, 0.045f, 0.03f),
                accelSigma = 0.10f,
                gyroSigma = 0.013f,
                harmonic2 = 0.6f,
            ).build()

    /**
     * Phone taken off a car mount while the engine idles. There is no
     * surface-still baseline here, so this must NOT be treated as a pickup.
     */
    fun pickupFromCarMount(seed: Int = 9): List<SensorSample> =
        Builder(seed = seed).setTilt(70f)
            .oscillate(
                8_000,
                freqHz = 14f,
                amplitude = Vec3(0.18f, 0.15f, 0.22f),
                gyroAmplitude = Vec3(0.01f, 0.01f, 0.01f),
                accelSigma = 0.30f,
                gyroSigma = 0.010f,
                harmonic2 = 0.8f,
            )
            .impulse(90, peak = Vec3(1.0f, 0.8f, 3.0f), gyroPeak = Vec3(0.6f, 0.4f, 0.2f))
            .rotateDevice(700, angleDeg = 45f, axis = Vec3(1f, 0.2f, 0f), linearPeak = Vec3(1.0f, 0.8f, 2.8f))
            .oscillate(
                3_000,
                freqHz = 6f,
                amplitude = Vec3(0.14f, 0.11f, 0.16f),
                gyroAmplitude = Vec3(0.04f, 0.05f, 0.03f),
                accelSigma = 0.12f,
                gyroSigma = 0.015f,
            ).build()

    /** Raising and lowering the phone to look at it, mid-walk. */
    fun raiseAndLowerWhileWalking(seed: Int = 10): List<SensorSample> =
        Builder(seed = seed).setTilt(40f)
            .oscillate(6_000, 1.9f, Vec3(0.9f, 0.7f, 2.4f), Vec3(0.35f, 0.25f, 0.15f), 0.35f, 0.05f, spikeScale = 1.6f)
            .rotateDevice(600, angleDeg = 35f, axis = Vec3(1f, 0f, 0f), linearPeak = Vec3(1.2f, 0.9f, 3.0f), accelSigma = 0.4f, gyroSigma = 0.06f)
            .oscillate(2_500, 1.9f, Vec3(0.9f, 0.7f, 2.4f), Vec3(0.35f, 0.25f, 0.15f), 0.35f, 0.05f, spikeScale = 1.6f)
            .rotateDevice(600, angleDeg = -35f, axis = Vec3(1f, 0f, 0f), linearPeak = Vec3(1.2f, 0.9f, 3.0f), accelSigma = 0.4f, gyroSigma = 0.06f)
            .oscillate(4_000, 1.9f, Vec3(0.9f, 0.7f, 2.4f), Vec3(0.35f, 0.25f, 0.15f), 0.35f, 0.05f, spikeScale = 1.6f)
            .build()

    /** Free fall onto a hard floor, then the phone comes to rest. */
    fun probableDrop(seed: Int = 11): List<SensorSample> =
        Builder(seed = seed).setTilt(30f)
            .quiet(4_000, 0.12f, 0.02f)
            .freefall(320)
            .impulse(40, peak = Vec3(12f, 9f, 38f), gyroPeak = Vec3(3f, 2f, 1.5f), accelSigma = 0.5f, gyroSigma = 0.2f)
            .setTilt(180f)
            .quiet(4_000, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .build()

    /** Hard set-down on a desk: big impact, but the phone ends up at rest. */
    fun hardSetDown(seed: Int = 12): List<SensorSample> =
        Builder(seed = seed).setTilt(20f)
            .oscillate(3_000, 6f, Vec3(0.14f, 0.11f, 0.16f), Vec3(0.04f, 0.05f, 0.03f), 0.11f, 0.014f)
            .rotateDevice(400, angleDeg = -20f, axis = Vec3(1f, 0f, 0f), linearPeak = Vec3(0.5f, 0.4f, 1.5f))
            .impulse(45, peak = Vec3(3f, 2.5f, 14f), gyroPeak = Vec3(0.4f, 0.3f, 0.1f))
            .quiet(6_000, ACCEL_SIGMA_DESK, GYRO_SIGMA_DESK)
            .build()

    /** Typical hard-surface accelerometer noise floor, m/s^2 per axis. */
    const val ACCEL_SIGMA_DESK = 0.018f

    /** Typical gyro noise floor at rest, rad/s per axis. */
    const val GYRO_SIGMA_DESK = 0.0025f
}
