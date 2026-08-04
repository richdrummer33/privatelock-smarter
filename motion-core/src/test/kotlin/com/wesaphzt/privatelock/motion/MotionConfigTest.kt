package com.wesaphzt.privatelock.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionConfigTest {

    @Test
    fun `default config is internally consistent`() {
        val c = MotionConfig()
        assertEquals(MotionConfig.CURRENT_VERSION, c.version)
        assertTrue(c.baselineWindowMillis >= c.shortWindowMillis)
        assertTrue(c.pickupSettleMillis + c.pickupSustainWindowMillis <= c.pickupWindowMillis)
        assertTrue(c.weightSum > 0f)
    }

    @Test
    fun `proposed default policy matches the specification`() {
        val c = MotionConfig()
        assertEquals("trusted grace is three minutes", 180_000L, c.trustedGraceMillis)
        assertEquals("untrusted grace is one minute", 60_000L, c.untrustedGraceMillis)
        assertTrue("drop locking defaults on", c.dropLockEnabled)
        assertTrue("pickup locking defaults on", c.pickupLockEnabled)
    }

    @Test
    fun `presets differ from the default in the direction they claim`() {
        val base = MotionConfig.PIXEL_8_PRO_DEFAULT
        val sensitive = MotionConfig.SENSITIVE
        val conservative = MotionConfig.CONSERVATIVE

        assertTrue(sensitive.requiredStillnessMillis < base.requiredStillnessMillis)
        assertTrue(sensitive.pickupConfidenceThreshold < base.pickupConfidenceThreshold)
        assertTrue(conservative.requiredStillnessMillis > base.requiredStillnessMillis)
        assertTrue(conservative.pickupConfidenceThreshold > base.pickupConfidenceThreshold)
        assertNotEquals(sensitive, conservative)
    }

    @Test
    fun `invalid windows are rejected at construction rather than misbehaving later`() {
        assertThrows(IllegalArgumentException::class.java) {
            MotionConfig(shortWindowMillis = 2_000, baselineWindowMillis = 1_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionConfig(pickupConfidenceThreshold = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            // settle + sustain must fit inside the pickup window
            MotionConfig(pickupWindowMillis = 600, pickupSettleMillis = 500, pickupSustainWindowMillis = 800)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MotionConfig(walkFreqMinHz = 3f, walkFreqMaxHz = 1f)
        }
    }

    @Test
    fun `copy semantics allow tuning one field without disturbing the rest`() {
        val tuned = MotionConfig().copy(pickupConfidenceThreshold = 0.8f)
        assertEquals(0.8f, tuned.pickupConfidenceThreshold, 1e-6f)
        assertEquals(MotionConfig().requiredStillnessMillis, tuned.requiredStillnessMillis)
    }
}
