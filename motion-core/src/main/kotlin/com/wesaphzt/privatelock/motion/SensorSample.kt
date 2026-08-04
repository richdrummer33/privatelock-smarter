package com.wesaphzt.privatelock.motion

import kotlin.math.sqrt

/** Standard gravity, m/s^2. */
const val STANDARD_GRAVITY: Float = 9.80665f

/**
 * One fused sensor observation, already normalised into this module's units.
 *
 * Deliberately a plain immutable value type with no Android types so it can be
 * synthesised in tests and replayed from recorded traces.
 *
 * @param timestampNanos monotonic (elapsed-realtime) timestamp
 * @param ax,ay,az raw accelerometer including gravity, m/s^2, device frame
 * @param gyroX,gyroY,gyroZ angular velocity, rad/s; zero when no gyroscope
 * @param hasGyro whether the gyro fields carry real data
 * @param gravityX,gravityY,gravityZ hardware/fused gravity estimate when the
 *   platform provides `TYPE_GRAVITY`; null triggers the internal low-pass
 *   estimator instead
 */
data class SensorSample(
    val timestampNanos: Long,
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gyroX: Float = 0f,
    val gyroY: Float = 0f,
    val gyroZ: Float = 0f,
    val hasGyro: Boolean = false,
    val gravityX: Float? = null,
    val gravityY: Float? = null,
    val gravityZ: Float? = null,
) {
    /** Magnitude of the raw acceleration vector, m/s^2 (≈ 9.81 at rest). */
    val accelMagnitude: Float get() = sqrt(ax * ax + ay * ay + az * az)

    /** Magnitude of angular velocity, rad/s. */
    val gyroMagnitude: Float get() = if (hasGyro) sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ) else 0f

    val hasHardwareGravity: Boolean get() = gravityX != null && gravityY != null && gravityZ != null
}

/** Immutable 3-vector helper used for gravity/orientation maths. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    val magnitude: Float get() = sqrt(x * x + y * y + z * z)

    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}
