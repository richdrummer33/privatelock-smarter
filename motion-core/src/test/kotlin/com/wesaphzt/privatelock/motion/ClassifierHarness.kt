package com.wesaphzt.privatelock.motion

/**
 * Drives [SensorFeatureWindow] + [MotionStateClassifier] over a trace the way
 * the production pipeline does: every sample goes into the window, but the
 * classifier is only evaluated on a fixed cadence.
 */
class ClassifierHarness(
    private val config: MotionConfig = MotionConfig(),
    private val evaluationIntervalMillis: Long = 100,
) {
    val window = SensorFeatureWindow(config)
    val classifier = MotionStateClassifier(config)

    private var nextEvaluationNanos = Long.MIN_VALUE

    val transitions = mutableListOf<MotionStateTransition>()
    val timeline = mutableListOf<Pair<Long, MotionState>>()
    val snapshots = mutableListOf<FeatureSnapshot>()

    fun feed(trace: List<SensorSample>) {
        for (sample in trace) feedOne(sample)
    }

    fun feedOne(sample: SensorSample) {
        window.push(sample)
        val now = sample.timestampNanos
        if (nextEvaluationNanos == Long.MIN_VALUE) nextEvaluationNanos = now
        if (now < nextEvaluationNanos) return
        nextEvaluationNanos = now + evaluationIntervalMillis * 1_000_000L

        val snapshot = window.snapshot()
        snapshots += snapshot
        classifier.update(snapshot)?.let { transitions += it }
        timeline += now to classifier.state
    }

    /** Fraction of evaluations that landed in [state]. */
    fun fractionIn(state: MotionState): Float =
        if (timeline.isEmpty()) 0f else timeline.count { it.second == state } / timeline.size.toFloat()

    /** The state held for the largest number of evaluations. */
    fun dominantState(): MotionState =
        timeline.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key ?: MotionState.UNKNOWN

    /** States observed after skipping the first [skipMillis] of the trace. */
    fun statesAfter(skipMillis: Long): Set<MotionState> {
        if (timeline.isEmpty()) return emptySet()
        val start = timeline.first().first + skipMillis * 1_000_000L
        return timeline.filter { it.first >= start }.map { it.second }.toSet()
    }

    fun everEntered(state: MotionState): Boolean = timeline.any { it.second == state }

    fun summary(): String = buildString {
        val counts = timeline.groupingBy { it.second }.eachCount()
        append(counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key}=${it.value}" })
    }
}
