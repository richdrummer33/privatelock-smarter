package com.wesaphzt.privatelock.motion.legacy

import com.wesaphzt.privatelock.motion.SensorSample
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Faithful port of Private Lock's original detector, kept as a fallback and as
 * a behavioural reference.
 *
 * The original ran inside the raw `SensorEventListener` callback in both
 * `LockService` and `MainActivity`:
 *
 * ```java
 * float deltaX = Math.abs(mLastX - x);            // per-axis delta
 * if (deltaX < NOISE) deltaX = 0.0f;              // NOISE = 2.0f
 * ...
 * float total = sqrt(dx*dx + dy*dy + dz*dz);
 * if (total > SENSITIVITY) mDPM.lockNow();        // SENSITIVITY default 10
 * ```
 *
 * Its weaknesses are structural, not tunable:
 *
 *  - a *single* sample pair can lock the device;
 *  - it never compensates for gravity, so re-orienting the phone by 90 degrees
 *    produces a ~9.8 m/s^2 step on two axes for free;
 *  - it has no memory of what the device was doing beforehand, so a hard
 *    footfall while walking is indistinguishable from a theft grab;
 *  - the per-axis dead-band means a smooth-but-fast lift can score 0 while a
 *    sharp tap on one axis scores high.
 *
 * It is preserved verbatim so that the new pipeline can be compared against it
 * on the same recorded traces, and so that a user who preferred the old feel
 * can still select it. It is *not* the default.
 */
class LegacyThresholdDetector(
    /** Original slider value, 0..40, default 10. */
    var sensitivity: Float = DEFAULT_SENSITIVITY,
    /** Original per-axis dead-band. */
    private val noise: Float = DEFAULT_NOISE,
) {
    private var initialised = false
    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f

    /** Last computed score, exposed for the "test sensitivity" dial. */
    var lastScore: Float = 0f
        private set

    /**
     * Feeds one sample.
     *
     * @return true when the original code would have called `lockNow()`.
     */
    fun onSample(sample: SensorSample): Boolean {
        val x = sample.ax
        val y = sample.ay
        val z = sample.az

        if (!initialised) {
            lastX = x; lastY = y; lastZ = z
            initialised = true
            lastScore = 0f
            return false
        }

        var dx = abs(lastX - x)
        var dy = abs(lastY - y)
        var dz = abs(lastZ - z)
        if (dx < noise) dx = 0f
        if (dy < noise) dy = 0f
        if (dz < noise) dz = 0f

        lastX = x; lastY = y; lastZ = z

        val total = sqrt(dx * dx + dy * dy + dz * dz)
        lastScore = total
        return total > sensitivity
    }

    /**
     * Original code reset this flag on screen-on and on un-pause to avoid an
     * animation artefact; the reset also happens to discard a stale baseline.
     */
    fun reset() {
        initialised = false
        lastScore = 0f
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 10f
        const val DEFAULT_NOISE = 2.0f
        const val MAX_SENSITIVITY = 40f
    }
}
