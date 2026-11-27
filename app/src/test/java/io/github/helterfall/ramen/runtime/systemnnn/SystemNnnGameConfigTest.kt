package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnGameConfigTest {
    @Test
    fun nativeGameDefaultsMatchCGameAndCShakin() {
        val config = SystemNnnGameConfig.parse("")
        assertEquals(50, config.frameTimeMillis)
        assertEquals(2, config.maxSkipFrame)
        assertEquals(2, config.maxSkipFrameBackScript)
        assertEquals(10, config.skipAdjustFrame)
        assertFalse(config.isConfigured)
    }

    @Test
    fun parsesNativeFrameRateAndSkipParameters() {
        val config = SystemNnnGameConfig.parse(
            "frameTime,24\nmaxSkipFrame,3\nmaxSkipFrameBackScript,1\nskipAdjustFrame,8"
        )
        assertEquals(24, config.frameTimeMillis)
        assertEquals(3, config.maxSkipFrame)
        assertEquals(1, config.maxSkipFrameBackScript)
        assertEquals(8, config.skipAdjustFrame)
        assertTrue(config.isConfigured)
    }

    @Test
    fun supportsKeyValueAndNativeMinimumSkipFrameClamp() {
        val config = SystemNnnGameConfig.parse("FrameTime=30\nmaxSkipFrame=0\nskipAdjustFrame=5")
        assertEquals(30, config.frameTimeMillis)
        assertEquals(1, config.maxSkipFrame)
        assertEquals(1, config.maxSkipFrameBackScript)
        assertEquals(5, config.skipAdjustFrame)
    }

    @Test
    fun nonPositiveConfiguredFrameTimeFallsBackToNativeDefault() {
        assertEquals(50, SystemNnnGameConfig.parse("frameTime,-1").frameTimeMillis)
    }
}
