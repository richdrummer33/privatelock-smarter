package com.wesaphzt.privatelock.motion

import kotlin.math.max
import kotlin.math.min

/**
 * Individual pieces of evidence considered for a pickup.
 *
 * Recorded explicitly, and carried on both the accepted and the rejected
 * outcome, so that the diagnostics screen can explain *why* a lock happened or
 * did not, rather than showing a bare confidence number.
 */
enum class PickupEvidence {
    /** The device had been resting on a surface for long enough. */
    PRIOR_STILLNESS,
    /** A jerk or acceleration impulse started the gesture. */
    ONSET_IMPULSE,
    /** The gravity vector rotated by a meaningful angle. */
    ORIENTATION_CHANGE,
    /** Integrated gyro confirms real rotation, independent of gravity. */
    ANGULAR_TRAVEL,
    /** After the gesture the device shows the micro-motion of being held. */
    SUSTAINED_MOTION,
    /** The device did not settle back onto the surface. */
    DID_NOT_RETURN_TO_REST,
}

/** Why a candidate pickup was not accepted. */
enum class PickupRejection {
    /** No qualifying rest period, so the detector was never armed. */
    NO_PRIOR_STILLNESS,
    /** The device is already being carried; there is nothing to pick up from. */
    ALREADY_IN_MOTION_CONTEXT,
    /** The gesture did not complete inside the bounded window. */
    WINDOW_EXPIRED,
    /** The device went quiet again -- a bump, not a lift. */
    RETURNED_TO_REST,
    /** No sustained post-gesture motion; nothing is holding the device. */
    NO_SUSTAINED_MOTION,
    /** Neither the gravity angle nor the gyro saw real rotation. */
    INSUFFICIENT_ROTATION,
    /** Evidence present but the weighted confidence fell short. */
    LOW_CONFIDENCE,
}

/**
 * A completed pickup assessment, accepted or rejected.
 */
data class PickupAssessment(
    val accepted: Boolean,
    val confidence: Float,
    val atNanos: Long,
    val evidence: Set<PickupEvidence>,
    val rejection: PickupRejection?,
    /** Stillness that preceded the gesture, ms. */
    val priorStillnessMillis: Long,
    /** Largest gravity-vector rotation observed during the gesture, degrees. */
    val orientationDeltaDeg: Float,
    /** Integrated rotation observed during the gesture, radians. */
    val angularTravelRad: Float,
    /** Peak linear acceleration at onset. */
    val onsetPeak: Float,
    /** Peak jerk at onset. */
    val onsetJerk: Float,
    /** Linear-acceleration RMS measured in the sustain window. */
    val sustainedRms: Float,
) {
    /** Human-readable explanation, used verbatim in diagnostics. */
    fun explain(): String = if (accepted) {
        "stationary for %.1f s, pickup confidence %.2f, orientation change %.0f degrees"
            .format(priorStillnessMillis / 1000f, confidence, orientationDeltaDeg)
    } else {
        when (rejection) {
            PickupRejection.NO_PRIOR_STILLNESS ->
                "no preceding stationary-surface period"
            PickupRejection.ALREADY_IN_MOTION_CONTEXT ->
                "device was already being carried before the impulse"
            PickupRejection.WINDOW_EXPIRED ->
                "gesture did not complete within the pickup window"
            PickupRejection.RETURNED_TO_REST ->
                "disturbance settled back onto the surface"
            PickupRejection.NO_SUSTAINED_MOTION ->
                "disturbance lacked sustained post-pickup motion"
            PickupRejection.INSUFFICIENT_ROTATION ->
                "orientation change %.0f degrees and rotation %.2f rad were too small"
                    .format(orientationDeltaDeg, angularTravelRad)
            PickupRejection.LOW_CONFIDENCE ->
                "pickup confidence %.2f below threshold".format(confidence)
            null -> "no pickup"
        }
    }
}

/**
 * Detects the specific transition "was resting on a surface, is now being
 * held".
 *
 * ## Why a state machine and not a threshold
 *
 * The security requirement is asymmetric. Locking when the owner picks up
 * their own phone is a minor annoyance; failing to lock when a thief lifts it
 * off a table defeats the app; and locking every time the owner shifts their
 * grip while walking makes the app unusable. Those cannot be balanced by
 * tuning one sensitivity number, because "picked up off a table" and "jostled
 * while walking" produce *similar instantaneous magnitudes*. They differ in
 * what came before and what comes after.
 *
 * So the detector is staged:
 *
 * ```
 *   IDLE  --(classifier commits STATIONARY_SURFACE for long enough)-->  ARMED
 *   ARMED --(jerk / acceleration / gyro impulse)-->                     EVALUATING
 *   EVALUATING --(evidence gates within a bounded window)-->            accept / reject
 * ```
 *
 * The critical property is structural rather than numeric: **the detector can
 * only arm from a committed rest state.** A phone already being carried never
 * arms, so `WALKING -> stronger WALKING` cannot produce a pickup no matter how
 * violent the jerk. That is a guarantee of the state machine's shape, not a
 * consequence of a threshold being set high, which is why it cannot be tuned
 * away by a user adjusting sensitivity.
 *
 * ## Evidence
 *
 * Three gates are *hard* -- failing any one rejects outright:
 *
 *  - prior stillness (required to arm at all);
 *  - sustained micro-motion after the gesture settles;
 *  - not having returned to the resting state.
 *
 * The last two are what reject a desk bump. A bump produces an onset, and
 * often a real orientation change, but the phone is quiet again within a
 * second. Only something now being *held* keeps producing hand tremor.
 *
 * The remaining evidence is weighted into a confidence score, so a gentle
 * lift with a weak onset can still succeed on rotation and sustained motion,
 * while a sharp knock that satisfies nothing else cannot.
 *
 * Not thread-safe; owned by the pipeline.
 */
class PickupTransitionDetector(private val config: MotionConfig = MotionConfig()) {

    enum class Stage { IDLE, ARMED, EVALUATING }

    var stage: Stage = Stage.IDLE
        private set

    // Rest baseline captured when the detector armed.
    private var restGravity: Vec3 = Vec3.ZERO
    private var armedStillnessMillis: Long = 0L

    // Gesture accumulators, valid while EVALUATING.
    private var onsetNanos: Long = 0L
    private var onsetPeak: Float = 0f
    private var onsetJerk: Float = 0f
    private var maxOrientationDeltaDeg: Float = 0f
    private var accumulatedAngularRad: Float = 0f
    private var lastSampleNanos: Long = 0L
    private var sustainedRmsSum: Double = 0.0
    private var sustainedRmsCount: Int = 0
    private var sustainedGyroSum: Double = 0.0
    private var sawRestDuringWindow: Boolean = false
    private var gyroAvailableDuringGesture: Boolean = false

    /** Most recent assessment, accepted or not. Exposed for diagnostics. */
    var lastAssessment: PickupAssessment? = null
        private set

    fun reset() {
        stage = Stage.IDLE
        restGravity = Vec3.ZERO
        armedStillnessMillis = 0L
        clearGesture()
    }

    private fun clearGesture() {
        onsetNanos = 0L
        onsetPeak = 0f
        onsetJerk = 0f
        maxOrientationDeltaDeg = 0f
        accumulatedAngularRad = 0f
        lastSampleNanos = 0L
        sustainedRmsSum = 0.0
        sustainedRmsCount = 0
        sustainedGyroSum = 0.0
        sawRestDuringWindow = false
        gyroAvailableDuringGesture = false
    }

    /**
     * Feeds one evaluation tick.
     *
     * @param features the current feature snapshot
     * @param state the classifier's committed state
     * @return an assessment on the tick a gesture concludes, else null
     */
    fun update(features: FeatureSnapshot, state: MotionState): PickupAssessment? {
        val now = features.timestampNanos

        when (stage) {
            Stage.IDLE -> {
                if (canArm(features, state)) arm(features)
                return null
            }

            Stage.ARMED -> {
                // Loss of stillness is itself the onset.
                //
                // Once armed, the device has been demonstrated to sit at its
                // sensor noise floor, so *anything* that lifts it above that
                // floor is the start of a disturbance and is the earliest
                // reliable indication of one. Requiring a hard impulse
                // threshold here instead was a real bug: a slow, careful lift
                // -- exactly how someone quietly takes a phone off a table --
                // breaks stillness without ever crossing the impulse
                // thresholds, so the detector disarmed and the gesture was
                // never evaluated at all.
                //
                // The impulse thresholds still matter, but as confidence
                // evidence (ONSET_IMPULSE) rather than as a gate.
                if (!features.currentlyStill || isOnset(features)) {
                    beginEvaluating(features)
                    return null
                }
                // Still resting. Keep the baseline fresh so slow drift in the
                // gravity estimate does not later read as a rotation.
                if (canArm(features, state)) {
                    restGravity = features.gravity
                    armedStillnessMillis = features.stillnessMillis
                } else {
                    stage = Stage.IDLE
                }
                return null
            }

            Stage.EVALUATING -> return evaluate(features, state, now)
        }
    }

    private fun canArm(features: FeatureSnapshot, state: MotionState): Boolean =
        state == MotionState.STATIONARY_SURFACE &&
            features.stillnessMillis >= config.requiredStillnessMillis

    private fun arm(features: FeatureSnapshot) {
        stage = Stage.ARMED
        restGravity = features.gravity
        armedStillnessMillis = features.stillnessMillis
        clearGesture()
    }

    /**
     * An onset is any of three independent impulses. Requiring all three would
     * miss a slow, careful lift, which is exactly the gesture a thief taking a
     * phone from a table is most likely to use.
     */
    private fun isOnset(features: FeatureSnapshot): Boolean =
        features.linAccPeak >= config.disturbancePeakMin ||
            features.jerkPeak >= config.onsetJerkMin ||
            (features.gyroAvailable && features.gyroPeak >= config.onsetGyroMin)

    private fun beginEvaluating(features: FeatureSnapshot) {
        stage = Stage.EVALUATING
        // Keep the rest baseline captured while ARMED; that is the reference
        // the orientation change is measured against.
        armedStillnessMillis = max(armedStillnessMillis, features.stillnessMillis)
        onsetNanos = features.timestampNanos
        onsetPeak = features.linAccPeak
        onsetJerk = features.jerkPeak
        maxOrientationDeltaDeg = 0f
        accumulatedAngularRad = 0f
        lastSampleNanos = features.timestampNanos
        sustainedRmsSum = 0.0
        sustainedRmsCount = 0
        sustainedGyroSum = 0.0
        sawRestDuringWindow = false
        gyroAvailableDuringGesture = features.gyroAvailable
    }

    private fun evaluate(features: FeatureSnapshot, state: MotionState, now: Long): PickupAssessment? {
        val elapsedMillis = (now - onsetNanos) / 1_000_000L

        // Track the largest rotation away from the resting orientation.
        val delta = SensorFeatureWindow.angleBetweenDeg(features.gravity, restGravity)
        if (delta > maxOrientationDeltaDeg) maxOrientationDeltaDeg = delta

        // Integrate gyro over the gesture. FeatureSnapshot.angularTravelRad
        // covers only the short window, so summing it across ticks would
        // double count; integrate the mean rate over the tick interval instead.
        if (features.gyroAvailable) gyroAvailableDuringGesture = true
        if (features.gyroAvailable && lastSampleNanos > 0L) {
            val dt = (now - lastSampleNanos) / 1e9f
            if (dt > 0f) accumulatedAngularRad += features.gyroMean * dt
        }
        lastSampleNanos = now

        onsetPeak = max(onsetPeak, features.linAccPeak)
        onsetJerk = max(onsetJerk, features.jerkPeak)

        // Once past the settle delay, start measuring whether the device is
        // being held. Sampling before then would capture the lift itself, not
        // the state that follows it.
        if (elapsedMillis >= config.pickupSettleMillis) {
            sustainedRmsSum += features.linAccRms.toDouble()
            sustainedGyroSum += features.gyroMean.toDouble()
            sustainedRmsCount++
            // Uses the instantaneous stillness measurement, deliberately not
            // the classifier's committed state. The committed state lags by the
            // dwell time, so mid-gesture it is often still STATIONARY_SURFACE
            // simply because nothing has had time to replace it -- which would
            // read as "the device went back down" during the very gesture that
            // lifted it. A gentle lift, which never crosses the disturbance
            // threshold that would commit DISTURBED quickly, failed exactly
            // this way.
            if (features.currentlyStill) {
                sawRestDuringWindow = true
            }
        }

        // An unambiguous carried state ends the gesture early and favourably:
        // the device is demonstrably in a hand now.
        val settledIntoCarried = state.isCarried &&
            elapsedMillis >= config.pickupSettleMillis + config.pickupSustainWindowMillis

        if (!settledIntoCarried && elapsedMillis < config.pickupWindowMillis) return null

        return conclude(now, state)
    }

    private fun conclude(now: Long, state: MotionState): PickupAssessment {
        val evidence = mutableSetOf<PickupEvidence>()

        // -- hard gate 1: prior stillness ----------------------------------
        val stillnessOk = armedStillnessMillis >= config.requiredStillnessMillis
        if (stillnessOk) evidence += PickupEvidence.PRIOR_STILLNESS

        // -- hard gate 2: sustained motion ---------------------------------
        val sustainedRms = if (sustainedRmsCount == 0) 0f else (sustainedRmsSum / sustainedRmsCount).toFloat()
        val sustainedGyro = if (sustainedRmsCount == 0) 0f else (sustainedGyroSum / sustainedRmsCount).toFloat()
        // The gyro condition is only applied when there is a gyroscope. On a
        // device without one, requiring a minimum angular rate would be
        // unsatisfiable and would reject every pickup outright. The
        // accelerometer alone still distinguishes "being held" from "sitting
        // on a table", which is what this gate is for.
        val sustainedOk = sustainedRmsCount > 0 &&
            sustainedRms >= config.heldSustainRmsMin &&
            (!gyroAvailableDuringGesture || sustainedGyro >= config.heldSustainGyroMin)
        if (sustainedOk) evidence += PickupEvidence.SUSTAINED_MOTION

        // -- hard gate 3: did not go back to rest --------------------------
        val stillHeld = !sawRestDuringWindow && state != MotionState.STATIONARY_SURFACE
        if (stillHeld) evidence += PickupEvidence.DID_NOT_RETURN_TO_REST

        // -- weighted evidence ----------------------------------------------
        val orientationOk = maxOrientationDeltaDeg >= config.minOrientationDeltaDeg
        if (orientationOk) evidence += PickupEvidence.ORIENTATION_CHANGE

        val angularOk = accumulatedAngularRad >= config.minAngularTravelRad
        if (angularOk) evidence += PickupEvidence.ANGULAR_TRAVEL

        val onsetOk = onsetPeak >= config.disturbancePeakMin || onsetJerk >= config.onsetJerkMin
        if (onsetOk) evidence += PickupEvidence.ONSET_IMPULSE

        val stillnessScore = MotionStateClassifier.ramp(
            armedStillnessMillis.toFloat(),
            config.requiredStillnessMillis.toFloat(),
            config.requiredStillnessMillis * 2f,
        ).let { max(if (stillnessOk) MIN_SATISFIED_SCORE else 0f, it) }

        val onsetScore = max(
            MotionStateClassifier.ramp(onsetPeak, config.disturbancePeakMin, config.disturbancePeakMin * 3f),
            MotionStateClassifier.ramp(onsetJerk, config.onsetJerkMin, config.onsetJerkMin * 3f),
        )

        val orientationScore = MotionStateClassifier.ramp(
            maxOrientationDeltaDeg,
            config.minOrientationDeltaDeg,
            config.fullOrientationDeltaDeg,
        ).let { max(if (orientationOk) MIN_SATISFIED_SCORE else 0f, it) }

        val angularScore = MotionStateClassifier.ramp(
            accumulatedAngularRad,
            config.minAngularTravelRad,
            config.fullAngularTravelRad,
        ).let { max(if (angularOk) MIN_SATISFIED_SCORE else 0f, it) }

        val sustainedScore = MotionStateClassifier.ramp(
            sustainedRms,
            config.heldSustainRmsMin,
            config.heldSustainRmsMin * 4f,
        ).let { max(if (sustainedOk) MIN_SATISFIED_SCORE else 0f, it) }

        val confidence = (
            stillnessScore * config.weightPriorStillness +
                onsetScore * config.weightOnsetImpulse +
                orientationScore * config.weightOrientation +
                angularScore * config.weightAngularTravel +
                sustainedScore * config.weightSustainedMotion
            ) / config.weightSum

        // Rotation evidence is required, but either channel may supply it.
        // Gravity-vector angle is drift-free yet contaminated by linear
        // acceleration; integrated gyro is immune to that but drifts. Demanding
        // both would reject real pickups on devices with a noisy gyro.
        val rotationOk = orientationOk || angularOk

        val rejection = when {
            !stillnessOk -> PickupRejection.NO_PRIOR_STILLNESS
            sawRestDuringWindow -> PickupRejection.RETURNED_TO_REST
            !stillHeld -> PickupRejection.RETURNED_TO_REST
            !sustainedOk -> PickupRejection.NO_SUSTAINED_MOTION
            !rotationOk -> PickupRejection.INSUFFICIENT_ROTATION
            confidence < config.pickupConfidenceThreshold -> PickupRejection.LOW_CONFIDENCE
            else -> null
        }

        val assessment = PickupAssessment(
            accepted = rejection == null,
            confidence = confidence,
            atNanos = now,
            evidence = evidence,
            rejection = rejection,
            priorStillnessMillis = armedStillnessMillis,
            orientationDeltaDeg = maxOrientationDeltaDeg,
            angularTravelRad = accumulatedAngularRad,
            onsetPeak = onsetPeak,
            onsetJerk = onsetJerk,
            sustainedRms = sustainedRms,
        )
        lastAssessment = assessment

        // Whatever the outcome, the gesture is over. Returning to IDLE forces a
        // fresh qualifying rest period before another pickup can be reported,
        // which is what stops one long disturbance producing a burst of events.
        stage = Stage.IDLE
        clearGesture()
        return assessment
    }

    companion object {
        /**
         * Floor applied to a satisfied gate's score. A gate that is met exactly
         * at its threshold would otherwise contribute zero to confidence,
         * making a just-qualifying pickup score the same as one that failed
         * the gate outright.
         */
        const val MIN_SATISFIED_SCORE = 0.35f

        /** Clamps a confidence into the reportable range. */
        fun clampConfidence(v: Float): Float = min(1f, max(0f, v))
    }
}
