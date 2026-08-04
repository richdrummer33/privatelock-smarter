package com.wesaphzt.privatelock.motion

/**
 * Everything the classifier and the pickup detector are allowed to look at.
 *
 * Producing one immutable snapshot per evaluation, rather than letting
 * downstream stages reach back into the sample buffer, is what keeps the
 * pipeline testable: a snapshot can be constructed by hand in a unit test, and
 * a snapshot can be written to a debug trace and replayed later.
 *
 * Units: acceleration m/s^2, angular velocity rad/s, jerk m/s^3, angles
 * degrees, durations milliseconds, frequency Hz.
 */
data class FeatureSnapshot(
    /** Monotonic timestamp of the newest sample in the window. */
    val timestampNanos: Long,

    /** Number of samples in the short window. */
    val sampleCount: Int,
    /** Observed delivery rate over the baseline window. */
    val effectiveRateHz: Float,

    // -- linear acceleration, short window ---------------------------------
    /** Mean magnitude of gravity-compensated acceleration. */
    val linAccMean: Float,
    /** Variance of that magnitude. */
    val linAccVariance: Float,
    /** Root-mean-square energy of gravity-compensated acceleration. */
    val linAccRms: Float,
    /** Largest gravity-compensated magnitude seen in the short window. */
    val linAccPeak: Float,

    // -- linear acceleration, baseline window ------------------------------
    val baselineLinAccRms: Float,
    val baselineLinAccPeak: Float,

    // -- gyroscope ----------------------------------------------------------
    val gyroMean: Float,
    val gyroPeak: Float,
    /** Integral of |omega| dt over the short window, radians. */
    val angularTravelRad: Float,
    /** False when the device has no gyroscope; gyro features read zero. */
    val gyroAvailable: Boolean,
    /**
     * Linear-acceleration RMS divided by mean angular rate.
     *
     * How tightly translation is coupled to rotation. A device clamped in a
     * car mount shakes without turning, giving a high ratio; a device held in
     * a hand pivots about the wrist and elbow, so translation always comes
     * with rotation and the ratio is low. This separates vehicle vibration
     * from hand tremor far more reliably than amplitude alone, because the two
     * overlap almost completely in amplitude. Zero when no gyroscope.
     */
    val translationRotationRatio: Float,

    // -- jerk ---------------------------------------------------------------
    /** Largest sample-to-sample rate of change of linear acceleration. */
    val jerkPeak: Float,

    // -- orientation --------------------------------------------------------
    /** Current gravity direction estimate, device frame. */
    val gravity: Vec3,
    /** Angle between the gravity estimate now and at the start of the short window. */
    val orientationDeltaDeg: Float,
    /** Angle between the gravity estimate now and at the start of the baseline window. */
    val baselineOrientationDeltaDeg: Float,
    /** Angle away from screen-up-flat, useful for describing resting posture. */
    val tiltFromFlatDeg: Float,

    // -- raw magnitude / impact --------------------------------------------
    /** Largest raw |a| in the short window; ~9.81 at rest, large on impact. */
    val rawAccPeak: Float,
    /** Smallest raw |a| in the short window; near zero in free fall. */
    val rawAccMin: Float,
    /** Longest contiguous free-fall-like interval in the short window, ms. */
    val freefallMillis: Long,
    /**
     * Impact impulse: integral of |a - g| over the short window, m/s.
     * Distinguishes a genuine collision from a single noisy sample.
     */
    val impactImpulse: Float,

    // -- periodicity --------------------------------------------------------
    /** Dominant repetition frequency over the baseline window, 0 if none. */
    val dominantFreqHz: Float,
    /** Normalised autocorrelation at that frequency, 0..1. */
    val periodicity: Float,

    // -- history ------------------------------------------------------------
    /** How long the device has continuously satisfied the stillness test, ms. */
    val stillnessMillis: Long,
    /** True when the current short window itself satisfies the stillness test. */
    val currentlyStill: Boolean,
) {
    companion object {
        /** A neutral snapshot, used before enough samples have arrived. */
        fun empty(timestampNanos: Long = 0L) = FeatureSnapshot(
            timestampNanos = timestampNanos,
            sampleCount = 0,
            effectiveRateHz = 0f,
            linAccMean = 0f,
            linAccVariance = 0f,
            linAccRms = 0f,
            linAccPeak = 0f,
            baselineLinAccRms = 0f,
            baselineLinAccPeak = 0f,
            gyroMean = 0f,
            gyroPeak = 0f,
            angularTravelRad = 0f,
            gyroAvailable = false,
            translationRotationRatio = 0f,
            jerkPeak = 0f,
            gravity = Vec3(0f, 0f, STANDARD_GRAVITY),
            orientationDeltaDeg = 0f,
            baselineOrientationDeltaDeg = 0f,
            tiltFromFlatDeg = 0f,
            rawAccPeak = STANDARD_GRAVITY,
            rawAccMin = STANDARD_GRAVITY,
            freefallMillis = 0,
            impactImpulse = 0f,
            dominantFreqHz = 0f,
            periodicity = 0f,
            stillnessMillis = 0,
            currentlyStill = false,
        )
    }
}
