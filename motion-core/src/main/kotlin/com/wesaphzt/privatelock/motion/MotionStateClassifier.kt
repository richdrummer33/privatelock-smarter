package com.wesaphzt.privatelock.motion

import kotlin.math.max
import kotlin.math.min

/**
 * Turns a stream of [FeatureSnapshot]s into a stable [MotionState].
 *
 * ## Scoring
 *
 * Each candidate state gets a score in 0..1 from a small set of membership
 * functions over the features. The highest scorer is the *challenger*. This is
 * a deliberately deterministic, inspectable first implementation: every score
 * can be printed in the diagnostics screen and traced back to a threshold in
 * [MotionConfig]. A learned model could replace `score()` later without
 * touching anything downstream, which is why scoring is isolated here.
 *
 * ## Hysteresis
 *
 * A challenger replaces the incumbent only when **both** hold:
 *
 *  1. it has out-scored the incumbent by at least `hysteresisMargin`
 *     continuously for its minimum dwell time, and
 *  2. it is still the leader at the end of that period.
 *
 * Without this the state flaps every few hundred milliseconds around any
 * boundary -- for instance during the pause between footsteps, where the
 * instantaneous signal genuinely does look handheld-still. Flapping matters
 * here because the pickup detector arms on entering STATIONARY_SURFACE: a
 * classifier that dipped into that state between footsteps would arm the
 * detector while the user was walking, which is exactly the false positive the
 * whole design exists to prevent.
 *
 * Fast-reacting states ([MotionState.POSSIBLE_DROP], [MotionState.DISTURBED])
 * use `fastDwellMillis` instead: a drop that took 700 ms to acknowledge would
 * be useless.
 *
 * Not thread-safe; owned by the pipeline.
 */
class MotionStateClassifier(
    private val config: MotionConfig = MotionConfig(),
) {
    private var current: MotionState = MotionState.UNKNOWN
    private var currentSinceNanos: Long = 0L

    private var challenger: MotionState? = null
    private var challengerSinceNanos: Long = 0L

    /** Latest per-state scores, exposed for diagnostics. */
    var lastScores: Map<MotionState, Float> = emptyMap()
        private set

    /** Advisory context from an activity-recognition provider, if any. */
    var activityHint: ActivityHint = ActivityHint.NONE

    val state: MotionState get() = current

    fun stateDurationMillis(nowNanos: Long): Long =
        max(0L, (nowNanos - currentSinceNanos) / 1_000_000L)

    fun reset() {
        current = MotionState.UNKNOWN
        currentSinceNanos = 0L
        challenger = null
        challengerSinceNanos = 0L
        lastScores = emptyMap()
    }

    /**
     * Feeds one snapshot.
     *
     * @return the transition if one was committed on this update, else null.
     */
    fun update(features: FeatureSnapshot): MotionStateTransition? {
        val now = features.timestampNanos
        if (features.sampleCount == 0) {
            // No data. Fall back to UNKNOWN immediately rather than holding a
            // stale "stationary" belief across a sensor gap.
            return commitIfChanged(MotionState.UNKNOWN, now, features)
        }

        val scores = score(features)
        lastScores = scores

        val leader = scores.maxByOrNull { it.value } ?: return null
        val incumbentScore = scores[current] ?: 0f

        if (current == MotionState.UNKNOWN && currentSinceNanos == 0L) {
            currentSinceNanos = now
        }

        if (leader.key == current) {
            challenger = null
            return null
        }

        val leads = leader.value >= incumbentScore + config.hysteresisMargin
        if (!leads) {
            challenger = null
            return null
        }

        val dwell = dwellFor(leader.key)
        if (challenger != leader.key) {
            challenger = leader.key
            challengerSinceNanos = now
        }

        val heldMillis = (now - challengerSinceNanos) / 1_000_000L
        if (heldMillis < dwell) return null

        return commitIfChanged(leader.key, now, features)
    }

    private fun commitIfChanged(
        next: MotionState,
        nowNanos: Long,
        features: FeatureSnapshot,
    ): MotionStateTransition? {
        if (next == current) return null
        val previousDuration = if (currentSinceNanos == 0L) 0L else (nowNanos - currentSinceNanos) / 1_000_000L
        val transition = MotionStateTransition(
            from = current,
            to = next,
            atNanos = nowNanos,
            previousStateDurationMillis = max(0L, previousDuration),
            features = features,
        )
        current = next
        currentSinceNanos = nowNanos
        challenger = null
        return transition
    }

    private fun dwellFor(state: MotionState): Long = when (state) {
        MotionState.POSSIBLE_DROP -> 0L // must be immediate to be useful
        MotionState.DISTURBED -> config.fastDwellMillis
        MotionState.UNKNOWN -> config.fastDwellMillis

        // Vehicle is the least trustworthy local inference, so it has to earn
        // the state by persisting. Without this, a phone sitting on a desk that
        // someone knocks looks vehicle-like for a second or two: rigidly
        // coupled (no gyro response), low broadband energy, no gait. Requiring
        // the pattern to hold for a long stretch removes that whole class of
        // misfire. When an activity-recognition provider independently reports
        // a vehicle, the corroboration justifies the ordinary dwell instead.
        MotionState.IN_VEHICLE ->
            if (activityHint == ActivityHint.IN_VEHICLE) config.minDwellMillis
            else config.vehicleMinDurationMillis

        else -> config.minDwellMillis
    }

    /**
     * Membership score per state. Not a probability distribution: scores are
     * independent and are only ever compared to each other.
     */
    private fun score(f: FeatureSnapshot): Map<MotionState, Float> {
        val scores = HashMap<MotionState, Float>(9)

        // -- POSSIBLE_DROP --------------------------------------------------
        // Two independent routes: free fall followed by an impact, or an
        // impact severe enough to stand on its own.
        val freefallScore = if (f.freefallMillis >= config.freefallMinMillis && f.rawAccPeak >= config.dropImpactMin) {
            ramp(f.rawAccPeak, config.dropImpactMin, config.severeImpactMin) * 0.5f + 0.5f
        } else {
            0f
        }
        val severeImpactScore = ramp(f.rawAccPeak, config.severeImpactMin, config.severeImpactMin * 1.6f)
        scores[MotionState.POSSIBLE_DROP] = max(freefallScore, severeImpactScore)

        // -- STATIONARY_SURFACE ---------------------------------------------
        // Requires everything to be at the noise floor at once. Deliberately
        // strict: a false "resting" belief is what would arm the pickup
        // detector at the wrong moment.
        scores[MotionState.STATIONARY_SURFACE] = if (!f.currentlyStill) {
            0f
        } else {
            val rmsScore = inverseRamp(f.linAccRms, config.stationaryRmsMax * 0.4f, config.stationaryRmsMax)
            val peakScore = inverseRamp(f.linAccPeak, config.stationaryPeakMax * 0.4f, config.stationaryPeakMax)
            val gyroScore = if (!f.gyroAvailable) 0.7f else {
                inverseRamp(f.gyroMean, config.stationaryGyroMeanMax * 0.4f, config.stationaryGyroMeanMax)
            }
            // Weakest link: all three must agree.
            min(rmsScore, min(peakScore, gyroScore))
        }

        // -- HANDHELD_STILL --------------------------------------------------
        // A band, not a threshold: quieter than this is a table, louder is
        // motion. Hand tremor sits reliably above a hard surface's noise floor.
        scores[MotionState.HANDHELD_STILL] = if (f.linAccRms <= config.stationaryRmsMax) {
            0f
        } else {
            val band = band(
                f.linAccRms,
                config.stationaryRmsMax,
                config.stationaryRmsMax * 1.6f,
                config.handheldRmsMax * 0.7f,
                config.handheldRmsMax,
            )
            val quietGyro = inverseRamp(f.gyroPeak, config.handheldGyroPeakMax * 0.5f, config.handheldGyroPeakMax)
            val notGait = 1f - gaitPresence(f)
            // A hand cannot translate without rotating. When the signal is far
            // too rigidly coupled for that, this is something clamped to a
            // vibrating object, not a hand.
            val handLike = if (!f.gyroAvailable) 1f else {
                inverseRamp(
                    f.translationRotationRatio,
                    config.handheldTranslationRotationRatioMax,
                    config.vehicleTranslationRotationRatioMin,
                )
            }
            min(band, min(quietGyro, min(notGait, handLike)))
        }

        // -- WALKING / RUNNING ----------------------------------------------
        val periodicity = ramp(f.periodicity, config.gaitPeriodicityMin, min(1f, config.gaitPeriodicityMin * 2f))
        val walkFreq = plateau(f.dominantFreqHz, config.walkFreqMinHz, config.walkFreqMaxHz, 0.35f)
        val walkEnergy = ramp(f.linAccRms, config.walkRmsMin * 0.6f, config.walkRmsMin)
        val notRunEnergy = inverseRamp(f.linAccRms, config.runRmsMin * 0.8f, config.runRmsMin)
        scores[MotionState.WALKING] = min(periodicity, min(walkFreq, min(walkEnergy, notRunEnergy)))

        val runFreq = plateau(f.dominantFreqHz, config.runFreqMinHz, config.runFreqMaxHz, 0.4f)
        val runEnergy = ramp(f.linAccRms, config.runRmsMin * 0.7f, config.runRmsMin)
        val runPeak = ramp(f.linAccPeak, config.runPeakMin * 0.7f, config.runPeakMin)
        scores[MotionState.RUNNING] = min(periodicity, min(runFreq, min(runEnergy, runPeak)))

        // -- IN_VEHICLE ------------------------------------------------------
        // The weakest inference in the classifier, and knowingly so. Sustained
        // low-amplitude energy, no gait, little reorientation. Capped below the
        // confident states so it can only win when nothing else fits, and
        // raised only when an activity-recognition provider agrees.
        val vehicleEnergy = plateau(f.linAccRms, config.vehicleRmsMin, config.vehicleRmsMax, 0.3f)
        val vehicleQuietGyro = inverseRamp(f.gyroMean, config.vehicleGyroMeanMax * 0.5f, config.vehicleGyroMeanMax)
        val vehicleStable = inverseRamp(
            f.baselineOrientationDeltaDeg,
            config.vehicleOrientationDeltaMaxDeg * 0.5f,
            config.vehicleOrientationDeltaMaxDeg,
        )
        val notGaitAtAll = 1f - gaitPresence(f)
        val rigidlyMounted = if (!f.gyroAvailable) 0f else {
            ramp(
                f.translationRotationRatio,
                config.handheldTranslationRotationRatioMax,
                config.vehicleTranslationRotationRatioMin,
            )
        }
        val localVehicle = min(
            vehicleEnergy,
            min(vehicleQuietGyro, min(vehicleStable, min(notGaitAtAll, rigidlyMounted))),
        ) * LOCAL_VEHICLE_CONFIDENCE_CAP
        scores[MotionState.IN_VEHICLE] = localVehicle

        // -- DISTURBED -------------------------------------------------------
        // The catch-all for "something is happening that is not yet one of the
        // steady contexts". Scores modestly so it yields as soon as a real
        // context establishes itself.
        // Scored from peak linear acceleration alone. Jerk was tried here and
        // removed: peak jerk over a window scales with the sensor noise floor
        // (roughly 250x the per-axis sigma at 50 Hz), so a jerk-based term
        // rated ordinary engine vibration as disturbed just as strongly as a
        // real knock. Peak acceleration does not have that problem.
        val disturbance = ramp(f.linAccPeak, config.disturbancePeakMin, config.disturbancePeakMin * 3f)
        scores[MotionState.DISTURBED] = disturbance * DISTURBED_CONFIDENCE_CAP

        // -- UNKNOWN ---------------------------------------------------------
        // A weak floor so that when nothing fits, the classifier admits it
        // rather than defaulting to a state that carries security meaning.
        scores[MotionState.UNKNOWN] = UNKNOWN_FLOOR

        applyActivityHint(scores)
        return scores
    }

    /**
     * Folds advisory activity-recognition context into the scores.
     *
     * The hint may be seconds late, so it only nudges: it can raise a state it
     * corroborates and damp contradicted ones, but it can never on its own
     * push a state past the hysteresis margin fast enough to act as the sole
     * trigger for a lock. That constraint is why the weights are small.
     */
    private fun applyActivityHint(scores: HashMap<MotionState, Float>) {
        val hint = activityHint
        if (hint == ActivityHint.NONE) return

        fun boost(state: MotionState, amount: Float) {
            scores[state] = min(1f, (scores[state] ?: 0f) + amount)
        }

        when (hint) {
            ActivityHint.STILL -> {
                boost(MotionState.STATIONARY_SURFACE, HINT_WEAK)
                boost(MotionState.HANDHELD_STILL, HINT_WEAK)
            }
            ActivityHint.WALKING, ActivityHint.ON_FOOT -> boost(MotionState.WALKING, HINT_STRONG)
            ActivityHint.RUNNING -> boost(MotionState.RUNNING, HINT_STRONG)
            ActivityHint.ON_BICYCLE -> {
                // Only reachable via a provider; the local classifier never
                // proposes it.
                scores[MotionState.ON_BICYCLE] = min(1f, (scores[MotionState.ON_BICYCLE] ?: 0f) + HINT_STRONG)
            }
            ActivityHint.IN_VEHICLE -> boost(MotionState.IN_VEHICLE, HINT_STRONG)
            ActivityHint.NONE -> Unit
        }
    }

    /** How strongly the features look like gait of any kind. */
    private fun gaitPresence(f: FeatureSnapshot): Float {
        if (f.periodicity < config.gaitPeriodicityMin) return 0f
        val inGaitBand = f.dominantFreqHz >= config.walkFreqMinHz && f.dominantFreqHz <= config.runFreqMaxHz
        if (!inGaitBand) return 0f
        return ramp(f.periodicity, config.gaitPeriodicityMin, min(1f, config.gaitPeriodicityMin * 2f))
    }

    companion object {
        /**
         * Ceiling on the locally inferred vehicle score. IMU-only vehicle
         * detection is not trustworthy enough to outrank a confident handheld
         * or gait classification, so it is capped rather than dropped.
         */
        const val LOCAL_VEHICLE_CONFIDENCE_CAP = 0.55f

        /**
         * DISTURBED is transitional and must yield to any settled context,
         * including the deliberately-capped vehicle score. Kept strictly below
         * [LOCAL_VEHICLE_CONFIDENCE_CAP] for that reason.
         */
        const val DISTURBED_CONFIDENCE_CAP = 0.5f

        /** Score UNKNOWN carries when nothing else fits. */
        const val UNKNOWN_FLOOR = 0.15f

        const val HINT_WEAK = 0.08f
        const val HINT_STRONG = 0.15f

        /** 0 below [lo], 1 at or above [hi], linear between. */
        fun ramp(v: Float, lo: Float, hi: Float): Float {
            if (hi <= lo) return if (v >= hi) 1f else 0f
            return ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
        }

        /** 1 below [lo], 0 at or above [hi], linear between. */
        fun inverseRamp(v: Float, lo: Float, hi: Float): Float = 1f - ramp(v, lo, hi)

        /**
         * 1 inside [lo, hi], falling to 0 across a margin of [softness]
         * (as a fraction of the band width) on each side.
         */
        fun plateau(v: Float, lo: Float, hi: Float, softness: Float): Float {
            if (v in lo..hi) return 1f
            val margin = max(1e-6f, (hi - lo) * softness)
            return if (v < lo) inverseRamp(lo - v, 0f, margin) else inverseRamp(v - hi, 0f, margin)
        }

        /** Trapezoid rising over [lo0, lo1] and falling over [hi0, hi1]. */
        fun band(v: Float, lo0: Float, lo1: Float, hi0: Float, hi1: Float): Float =
            min(ramp(v, lo0, lo1), inverseRamp(v, hi0, hi1))
    }
}

/**
 * Broad activity context from an optional external provider.
 *
 * Mirrors the transitions the Google Activity Recognition API reports, but is
 * declared here so that nothing in this module depends on Google Play Services
 * -- a fully local provider can produce the same values.
 */
enum class ActivityHint {
    NONE,
    STILL,
    WALKING,
    RUNNING,
    ON_FOOT,
    ON_BICYCLE,
    IN_VEHICLE,
}
