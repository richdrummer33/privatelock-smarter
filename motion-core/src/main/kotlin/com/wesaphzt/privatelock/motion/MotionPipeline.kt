package com.wesaphzt.privatelock.motion

/**
 * Wires the detection stages together and is the only thing the Android layer
 * needs to drive.
 *
 * ```
 *   samples -> SensorFeatureWindow -> FeatureSnapshot
 *                                        |
 *                     MotionStateClassifier (hysteresis, dwell)
 *                                        |
 *                     PickupTransitionDetector (staged evidence)
 *                                        |
 *                          LockPolicyEngine -> LockDecision
 * ```
 *
 * ## No lock decisions in sensor callbacks
 *
 * [onSample] never returns a lock. Sensor events arrive on a hardware
 * callback thread at up to 50 Hz; deciding policy there was the original
 * design's central mistake. Instead samples only update the window, and the
 * classifier, detector and policy are evaluated on a fixed, much slower
 * cadence in [evaluate], whose result the service acts on.
 *
 * Not thread-safe. The Android layer confines it to a single handler thread.
 */
class MotionPipeline(
    private val clock: Clock,
    config: MotionConfig = MotionConfig(),
    private val environment: PolicyEnvironment,
    private val evaluationIntervalMillis: Long = DEFAULT_EVALUATION_INTERVAL_MILLIS,
) {
    private var config: MotionConfig = config

    private var window = SensorFeatureWindow(config)
    private var classifier = MotionStateClassifier(config)
    private var detector = PickupTransitionDetector(config)

    val policy = LockPolicyEngine(clock, config, environment)

    private var nextEvaluationNanos: Long = Long.MIN_VALUE
    private var lastEvaluationNanos: Long = 0L

    /** Latest snapshot, for the diagnostics screen. */
    var lastFeatures: FeatureSnapshot = FeatureSnapshot.empty()
        private set

    /** Previous committed state, for the diagnostics screen. */
    var previousState: MotionState = MotionState.UNKNOWN
        private set

    val state: MotionState get() = classifier.state
    val stateScores: Map<MotionState, Float> get() = classifier.lastScores
    val pickupStage: PickupTransitionDetector.Stage get() = detector.stage
    val lastPickupAssessment: PickupAssessment? get() = detector.lastAssessment

    /** Advisory context from an optional activity-recognition provider. */
    var activityHint: ActivityHint
        get() = classifier.activityHint
        set(value) {
            classifier.activityHint = value
        }

    /**
     * Whether the sampler should currently run at the higher rate.
     *
     * True whenever the device is not comfortably at rest, plus a hold-off
     * after things settle so a gesture is not half-sampled. The sampler reads
     * this rather than deciding for itself, because the decision depends on
     * classifier state the sampler does not have.
     */
    var wantsHighRateSampling: Boolean = false
        private set

    private var lastDisturbanceNanos: Long = Long.MIN_VALUE

    fun updateConfig(newConfig: MotionConfig) {
        config = newConfig
        // Window sizes and thresholds are baked in at construction, so rebuild
        // rather than mutate. History is discarded, which is correct: features
        // computed under the old thresholds should not be mixed with new ones.
        window = SensorFeatureWindow(newConfig)
        classifier = MotionStateClassifier(newConfig).also { it.activityHint = activityHint }
        detector = PickupTransitionDetector(newConfig)
        policy.updateConfig(newConfig)
        nextEvaluationNanos = Long.MIN_VALUE
    }

    fun reset() {
        window.reset()
        classifier.reset()
        detector.reset()
        nextEvaluationNanos = Long.MIN_VALUE
        lastFeatures = FeatureSnapshot.empty()
        previousState = MotionState.UNKNOWN
        wantsHighRateSampling = false
        lastDisturbanceNanos = Long.MIN_VALUE
    }

    /**
     * Feeds one sensor sample. Cheap, allocation-free, and never decides
     * anything.
     */
    fun onSample(sample: SensorSample) {
        window.push(sample)
    }

    /**
     * Runs the classifier, detector and policy if the evaluation interval has
     * elapsed.
     *
     * @return the policy decision, or null when it was not yet time to
     *   evaluate or nothing happened.
     */
    fun evaluate(): LockDecision? {
        val features = window.snapshot()
        val now = features.timestampNanos
        if (now == 0L) return null
        if (nextEvaluationNanos == Long.MIN_VALUE) nextEvaluationNanos = now
        if (now < nextEvaluationNanos) return null
        nextEvaluationNanos = now + evaluationIntervalMillis * 1_000_000L
        lastEvaluationNanos = now
        lastFeatures = features

        val before = classifier.state
        val transition = classifier.update(features)
        if (transition != null) previousState = transition.from

        updateSamplingDemand(features, now)

        // A drop is acted on the moment the classifier commits it, not on the
        // pickup path: it is a different event with a different policy switch.
        if (transition != null && transition.to == MotionState.POSSIBLE_DROP) {
            val decision = policy.onDrop(features)
            // Still feed the detector so its staging stays consistent.
            detector.update(features, classifier.state)
            return decision
        }

        val assessment = detector.update(features, classifier.state)
        if (assessment != null) {
            // The state to judge against is the one the device was in *before*
            // the gesture, not the one it settled into. Judging on the settled
            // state would suppress every successful pickup, since a successful
            // pickup ends with the device being carried.
            val judgeState = if (before.suppressesPickupLock) before else MotionState.STATIONARY_SURFACE
            return policy.onPickup(assessment, judgeState)
        }

        return policy.onTick()
    }

    /**
     * Adaptive sampling policy.
     *
     * A screen-off, demonstrably stationary phone does not need 50 Hz. Dropping
     * to ~12.5 Hz cuts sensor wake-ups by a factor of four while still
     * detecting the onset of a disturbance, at which point the rate goes back
     * up and stays up for a hold-off so the whole gesture is captured densely.
     *
     * Battery tradeoff, measured in wake-ups rather than milliamps because the
     * latter is device-specific: at the idle rate the accelerometer and gyro
     * deliver about 25 events per second between them, versus about 100 at the
     * active rate. The sensor hub on a Pixel 8 Pro batches these, so the
     * dominant cost is the application processor wake-ups the batch flush
     * causes, not the sensor itself. Expect a low single-digit percentage of
     * daily battery at the idle rate, rising while a gesture is in progress.
     * Anyone changing `idleSamplingPeriodMicros` should re-measure.
     */
    private fun updateSamplingDemand(features: FeatureSnapshot, nowNanos: Long) {
        // Only EVALUATING counts as a gesture in progress. ARMED is the
        // detector's *resting* condition -- a phone sitting quietly on a desk
        // is armed almost all the time -- so treating anything other than IDLE
        // as activity pins the sampler at the high rate forever and silently
        // removes the entire battery saving.
        val gestureInProgress = detector.stage == PickupTransitionDetector.Stage.EVALUATING

        // Driven by the physical measurement rather than the classifier's
        // label: if the device is genuinely sitting at its sensor noise floor,
        // there is nothing that needs a high sample rate to catch, whatever
        // the classifier currently believes.
        val disturbed = !features.currentlyStill ||
            features.linAccPeak >= config.disturbancePeakMin ||
            gestureInProgress

        if (disturbed) lastDisturbanceNanos = nowNanos

        wantsHighRateSampling = if (lastDisturbanceNanos == Long.MIN_VALUE) {
            false
        } else {
            (nowNanos - lastDisturbanceNanos) / 1_000_000L < config.activeSamplingHoldMillis
        }
    }

    /** Sampling period the sampler should currently request, microseconds. */
    fun desiredSamplingPeriodMicros(): Int =
        if (wantsHighRateSampling) config.activeSamplingPeriodMicros else config.idleSamplingPeriodMicros

    companion object {
        /**
         * How often the classifier and policy run. 100 ms is comfortably
         * faster than the shortest dwell (200 ms) and far cheaper than
         * evaluating per sample.
         */
        const val DEFAULT_EVALUATION_INTERVAL_MILLIS = 100L
    }
}
