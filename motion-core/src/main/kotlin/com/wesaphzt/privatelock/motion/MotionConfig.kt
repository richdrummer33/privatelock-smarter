package com.wesaphzt.privatelock.motion

/**
 * Every tunable number used by the detection pipeline, in one place.
 *
 * Nothing in this module is allowed to hard-code a threshold. If a new
 * magic number is needed, it belongs here so that it can be surfaced in
 * settings, exported with a debug trace, and swept during calibration.
 *
 * Defaults were chosen for a Pixel 8 Pro (BMI3xx-class IMU, accelerometer
 * noise density around 100-200 ug/sqrt(Hz)). They are a *starting point*, not
 * a universal calibration: sensor noise floors, case mass and surface coupling
 * vary enough between devices that the calibration mode exists precisely so a
 * user can measure their own values.
 */
data class MotionConfig(
    /** Schema version, bumped when a migration is required. */
    val version: Int = CURRENT_VERSION,

    // -- windows ------------------------------------------------------------
    /** Short "what is happening right now" window. */
    val shortWindowMillis: Long = 800,
    /** Longer window used for context, periodicity and stillness. */
    val baselineWindowMillis: Long = 4_000,
    /** Time constant of the gravity low-pass filter, seconds. */
    val gravityFilterTauSeconds: Float = 0.5f,
    /** Hard cap on retained samples, protects memory if a sensor floods us. */
    val maxWindowSamples: Int = 1_024,
    /**
     * A gap larger than this means delivery stalled (Doze, sensor batching,
     * the service being restarted). Samples either side of such a gap must not
     * be treated as a continuous signal, so the window is discarded.
     */
    val maxSampleGapMillis: Long = 1_000,
    /** Minimum interval between autocorrelation recomputations, ms. */
    val periodicityIntervalMillis: Long = 200,

    // -- stationary / stillness --------------------------------------------
    /** Linear-acceleration RMS below which the device looks surface-still. */
    val stationaryRmsMax: Float = 0.08f,
    /** Peak linear acceleration allowed while still counting as surface-still. */
    val stationaryPeakMax: Float = 0.35f,
    /** Mean gyro magnitude below which the device looks surface-still, rad/s. */
    val stationaryGyroMeanMax: Float = 0.02f,

    // -- handheld -----------------------------------------------------------
    /** Upper bound of linear-acceleration RMS for "held but not walking". */
    val handheldRmsMax: Float = 0.9f,
    /** Above this gyro peak we are clearly doing more than holding, rad/s. */
    val handheldGyroPeakMax: Float = 1.5f,

    // -- gait ---------------------------------------------------------------
    /** Normalised autocorrelation peak needed to call a signal periodic. */
    val gaitPeriodicityMin: Float = 0.35f,
    val walkFreqMinHz: Float = 1.2f,
    val walkFreqMaxHz: Float = 2.6f,
    val walkRmsMin: Float = 0.6f,
    val runFreqMinHz: Float = 2.2f,
    val runFreqMaxHz: Float = 4.5f,
    val runRmsMin: Float = 3.5f,
    val runPeakMin: Float = 12.0f,

    // -- vehicle (weak, advisory) ------------------------------------------
    /**
     * Vehicle detection from IMU alone is genuinely unreliable; these bounds
     * describe "sustained low-amplitude broadband energy without gait
     * periodicity and without meaningful reorientation".
     */
    val vehicleRmsMin: Float = 0.05f,
    val vehicleRmsMax: Float = 1.2f,
    val vehicleGyroMeanMax: Float = 0.08f,
    val vehicleOrientationDeltaMaxDeg: Float = 8f,
    /**
     * Minimum translation/rotation coupling ratio for a vehicle-like signal.
     * A rigidly mounted device shakes without turning; a hand-held one cannot.
     */
    val vehicleTranslationRotationRatioMin: Float = 14f,
    /**
     * Above this ratio the signal is too rigidly coupled to be a hand, so the
     * handheld score is damped.
     */
    val handheldTranslationRotationRatioMax: Float = 12f,
    /** How long the vehicle-like pattern must persist before we believe it. */
    val vehicleMinDurationMillis: Long = 12_000,

    // -- disturbance / impact / drop ---------------------------------------
    /** Linear acceleration that counts as "something happened", m/s^2. */
    val disturbancePeakMin: Float = 1.2f,
    /** Jerk that counts as an onset impulse, m/s^3. */
    val onsetJerkMin: Float = 25f,
    /** Gyro peak that counts as an onset impulse, rad/s. */
    val onsetGyroMin: Float = 0.35f,
    /** Raw acceleration magnitude below this looks like free fall, m/s^2. */
    val freefallAccelMax: Float = 2.5f,
    /** Contiguous free-fall time needed before we call it a fall, ms. */
    val freefallMinMillis: Long = 120,
    /** Raw acceleration peak that counts as an impact after free fall. */
    val dropImpactMin: Float = 25f,
    /** Raw acceleration peak that counts as a severe impact on its own. */
    val severeImpactMin: Float = 35f,

    // -- classifier hysteresis ---------------------------------------------
    /** A challenger state must beat the incumbent's score by this margin. */
    val hysteresisMargin: Float = 0.12f,
    /** Default minimum time a challenger must lead before it is committed. */
    val minDwellMillis: Long = 700,
    /** Shorter dwell for states that must react fast. */
    val fastDwellMillis: Long = 200,

    // -- pickup detection ---------------------------------------------------
    /** Prior surface-stillness required before the detector arms, ms. */
    val requiredStillnessMillis: Long = 3_000,
    /** Whole pickup gesture must complete within this window after onset, ms. */
    val pickupWindowMillis: Long = 2_500,
    /** Wait after onset before judging "is it still being held", ms. */
    val pickupSettleMillis: Long = 500,
    /** Length of the trailing window used for the sustained-motion test, ms. */
    val pickupSustainWindowMillis: Long = 800,
    /** Minimum gravity-vector angle change over the gesture, degrees. */
    val minOrientationDeltaDeg: Float = 12f,
    /** Orientation change at which the orientation evidence score saturates. */
    val fullOrientationDeltaDeg: Float = 35f,
    /**
     * Minimum integrated |omega| dt over the gesture, radians.
     *
     * Kept numerically consistent with [minOrientationDeltaDeg]: 12 degrees is
     * 0.21 rad, so demanding much more integrated rotation than that would
     * reject gestures the orientation gate accepts, for no physical reason.
     */
    val minAngularTravelRad: Float = 0.25f,
    /** Integrated rotation at which the angular evidence score saturates. */
    val fullAngularTravelRad: Float = 1.0f,
    /** Linear-acceleration RMS the device must sustain to count as held. */
    val heldSustainRmsMin: Float = 0.09f,
    /** Mean gyro the device must sustain to count as held, rad/s. */
    val heldSustainGyroMin: Float = 0.008f,
    /** Confidence at or above which a pickup is actionable. */
    val pickupConfidenceThreshold: Float = 0.62f,

    // -- evidence weights (need not sum to 1; they are normalised) ----------
    //
    // Onset strength is deliberately the *lowest* weighted evidence. It is the
    // least diagnostic signal available: a thief lifting a phone carefully off
    // a table produces a weak onset, so weighting it heavily would penalise
    // precisely the case this app exists to catch. What actually distinguishes
    // a pickup is the combination of prior stillness, real reorientation, and
    // the device continuing to be held afterwards.
    val weightPriorStillness: Float = 0.20f,
    val weightOnsetImpulse: Float = 0.10f,
    val weightOrientation: Float = 0.30f,
    val weightAngularTravel: Float = 0.15f,
    val weightSustainedMotion: Float = 0.25f,

    // -- policy -------------------------------------------------------------
    /** Screen-off grace before an unlocked device is locked, trusted context. */
    val trustedGraceMillis: Long = 3 * 60_000,
    /** Same, untrusted context. */
    val untrustedGraceMillis: Long = 60_000,
    /** Minimum time between two locks issued by this app. */
    val lockCooldownMillis: Long = 5_000,
    /** Lock on a probable drop / severe impact. */
    val dropLockEnabled: Boolean = true,
    /** Master switch for motion-triggered locking. */
    val pickupLockEnabled: Boolean = true,
    /** Apply the screen-off grace timer at all. */
    val screenOffGraceEnabled: Boolean = true,

    // -- sampling -----------------------------------------------------------
    /** Sampling period while idle/stationary, microseconds (~12.5 Hz). */
    val idleSamplingPeriodMicros: Int = 80_000,
    /** Sampling period once a disturbance starts, microseconds (~50 Hz). */
    val activeSamplingPeriodMicros: Int = 20_000,
    /** How long to stay at the active rate after things settle, ms. */
    val activeSamplingHoldMillis: Long = 6_000,

    // -- diagnostics --------------------------------------------------------
    val debugLoggingEnabled: Boolean = false,
    /** Cap on retained decision-log entries; keeps memory bounded. */
    val decisionLogCapacity: Int = 100,
) {
    init {
        require(shortWindowMillis in 100..10_000) { "shortWindowMillis out of range" }
        require(baselineWindowMillis >= shortWindowMillis) { "baseline window must be >= short window" }
        require(gravityFilterTauSeconds > 0f) { "gravity tau must be positive" }
        require(maxWindowSamples >= 32) { "maxWindowSamples too small" }
        require(pickupConfidenceThreshold in 0f..1f) { "confidence threshold must be 0..1" }
        require(walkFreqMinHz < walkFreqMaxHz) { "walk frequency band inverted" }
        require(runFreqMinHz < runFreqMaxHz) { "run frequency band inverted" }
        require(pickupSettleMillis + pickupSustainWindowMillis <= pickupWindowMillis) {
            "settle + sustain window must fit inside the pickup window"
        }
        require(weightSum > 0f) { "evidence weights must not all be zero" }
    }

    val weightSum: Float
        get() = weightPriorStillness + weightOnsetImpulse + weightOrientation +
            weightAngularTravel + weightSustainedMotion

    companion object {
        /** Bump when a field's meaning changes and a migration is needed. */
        const val CURRENT_VERSION = 1

        /** Ships as the app default. */
        val PIXEL_8_PRO_DEFAULT = MotionConfig()

        /**
         * Fires more readily: shorter required stillness, lower confidence bar.
         * More convenient, measurably more false positives.
         */
        val SENSITIVE = MotionConfig(
            requiredStillnessMillis = 1_500,
            minOrientationDeltaDeg = 8f,
            minAngularTravelRad = 0.15f,
            pickupConfidenceThreshold = 0.5f,
            disturbancePeakMin = 0.8f,
        )

        /** Demands a clearer gesture; fewer false positives, more misses. */
        val CONSERVATIVE = MotionConfig(
            requiredStillnessMillis = 5_000,
            minOrientationDeltaDeg = 18f,
            minAngularTravelRad = 0.4f,
            pickupConfidenceThreshold = 0.75f,
            disturbancePeakMin = 1.6f,
        )
    }
}
