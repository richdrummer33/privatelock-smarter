package com.wesaphzt.privatelock.motion

/**
 * Runs a whole trace through [MotionPipeline] with a controllable environment,
 * collecting every decision.
 *
 * The pipeline's clock is slaved to the trace's own timestamps, so a test
 * never sleeps and results depend only on the trace.
 */
class PipelineHarness(
    val config: MotionConfig = MotionConfig(),
    var deviceLocked: Boolean = false,
    var lockMethodAvailable: Boolean = true,
    var trusted: Boolean = false,
    private val trustDetail: String = "trusted Wi-Fi",
) {
    val clock = ManualClock()

    private val trustResult
        get() = TrustedContextAggregator.Result(
            trusted = trusted,
            signals = listOf(
                TrustSignal(trusted = trusted, source = trustDetail, confidence = 0.5f),
            ),
        )

    val environment = object : PolicyEnvironment {
        override fun isDeviceLocked() = deviceLocked
        override fun isLockMethodAvailable() = lockMethodAvailable
        override fun trustedContext() = trustResult
    }

    val pipeline = MotionPipeline(clock, config, environment)

    val decisions = mutableListOf<LockDecision>()
    val locks = mutableListOf<LockDecision.Lock>()
    val suppressions = mutableListOf<LockDecision.Suppress>()
    val assessments = mutableListOf<PickupAssessment>()

    private var baseNanos: Long? = null

    fun feed(trace: List<SensorSample>) {
        for (sample in trace) feedOne(sample)
    }

    fun feedOne(sample: SensorSample) {
        if (baseNanos == null) baseNanos = sample.timestampNanos
        clock.setNanos(sample.timestampNanos)
        pipeline.onSample(sample)
        val before = pipeline.lastPickupAssessment
        val decision = pipeline.evaluate() ?: return
        val after = pipeline.lastPickupAssessment
        if (after != null && after !== before) assessments += after
        record(decision)
    }

    /**
     * Advances time without sensor data, e.g. to let a screen-off grace period
     * expire. Ticks the policy at one-second intervals.
     */
    fun advanceIdleMillis(millis: Long, stepMillis: Long = 1_000) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(stepMillis, remaining)
            clock.advanceMillis(step)
            record(pipeline.policy.onTick())
            remaining -= step
        }
    }

    private fun record(decision: LockDecision) {
        if (decision is LockDecision.NoAction) return
        decisions += decision
        when (decision) {
            is LockDecision.Lock -> locks += decision
            is LockDecision.Suppress -> suppressions += decision
            else -> Unit
        }
    }

    fun lockReasons(): List<LockReason> = locks.map { it.reason }
    fun suppressReasons(): List<SuppressReason> = suppressions.map { it.reason }

    fun acceptedPickups(): List<PickupAssessment> = assessments.filter { it.accepted }
    fun rejectedPickups(): List<PickupAssessment> = assessments.filter { !it.accepted }

    fun explain(): String = buildString {
        appendLine("locks: ${locks.map { "${it.reason}: ${it.explanation}" }}")
        appendLine("pickups: ${assessments.map { "acc=${it.accepted} conf=%.2f ori=%.0f ang=%.2f sus=%.3f rej=${it.rejection}".format(it.confidence, it.orientationDeltaDeg, it.angularTravelRad, it.sustainedRms) }}")
    }
}
