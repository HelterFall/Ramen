package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnVisualEffectMathTest {

    @Test
    fun nativeLinearSpeedUsesTheFullFrameRange() {
        assertEquals(
            0,
            SystemNnnVisualEffectMath.nativeSpeedValue(0, 20, 0, 100)
        )
        assertEquals(
            50,
            SystemNnnVisualEffectMath.nativeSpeedValue(10, 20, 0, 100)
        )
        assertEquals(
            100,
            SystemNnnVisualEffectMath.nativeSpeedValue(20, 20, 0, 100)
        )
    }

    @Test
    fun nativeSpeedSupportsEasingAndDelayParameters() {
        assertEquals(
            25,
            SystemNnnVisualEffectMath.nativeSpeedValue(10, 20, 0, 100, speedType = 30)
        )
        assertEquals(
            0,
            SystemNnnVisualEffectMath.nativeSpeedValue(5, 20, 0, 100, delayCount = 5, speedType = 1)
        )
        assertEquals(
            50,
            SystemNnnVisualEffectMath.nativeSpeedValue(10, 20, 0, 100, delayCount = 5, speedType = 1)
        )
    }

    @Test
    fun changeEffectBlendsTwoLayersAndReversesTheWeights() {
        val parameters = listOf(20, 13, 100, 100, 0, 0, 0, 0)

        val start = SystemNnnVisualEffectMath.changeBlend(0, parameters)
        val middle = SystemNnnVisualEffectMath.changeBlend(10, parameters)
        val end = SystemNnnVisualEffectMath.changeBlend(20, parameters)

        assertEquals(1f, start.primaryAlpha, 0.001f)
        assertEquals(0f, start.secondaryAlpha, 0.001f)
        assertEquals(0.5f, middle.primaryAlpha, 0.001f)
        assertEquals(0.5f, middle.secondaryAlpha, 0.001f)
        assertEquals(0f, end.primaryAlpha, 0.001f)
        assertEquals(1f, end.secondaryAlpha, 0.001f)
    }

    @Test
    fun bezierMotionUsesItsNativeStartAndEndControlPoints() {
        val parameters = listOf(20, 0, 10, 10, 20, 20, 30, 30, 40, 0, 0)

        val start = SystemNnnVisualEffectMath.bezierOffset(0, parameters)
        val middle = SystemNnnVisualEffectMath.bezierOffset(10, parameters)
        val end = SystemNnnVisualEffectMath.bezierOffset(20, parameters)

        assertEquals(0f, start.x, 0.001f)
        assertEquals(10f, start.y, 0.001f)
        assertEquals(15f, middle.x, 0.01f)
        assertEquals(25f, middle.y, 0.01f)
        assertEquals(30f, end.x, 0.001f)
        assertEquals(40f, end.y, 0.001f)
    }

    @Test
    fun bSplineMotionUsesItsNativeStartAndEndControlPoints() {
        val parameters = listOf(20, 0, 10, 10, 20, 20, 30, 0, 0)

        val start = SystemNnnVisualEffectMath.bSpline2Offset(0, parameters)
        val middle = SystemNnnVisualEffectMath.bSpline2Offset(10, parameters)
        val end = SystemNnnVisualEffectMath.bSpline2Offset(20, parameters)

        assertEquals(0f, start.x, 0.001f)
        assertEquals(10f, start.y, 0.001f)
        assertEquals(10f, middle.x, 0.01f)
        assertEquals(20f, middle.y, 0.01f)
        assertEquals(20f, end.x, 0.001f)
        assertEquals(30f, end.y, 0.001f)
    }

    @Test
    fun nativeShakePatternMovesTheEntireFrameOnTheRequestedAxis() {
        val horizontal = (0..3).map {
            SystemNnnVisualEffectMath.shakeOffset(it, 4, vertical = false)
        }
        assertEquals(listOf(-16f, -12f, 16f, 12f), horizontal.map { it.x })
        assertTrue(horizontal.all { it.y == 0f })

        val vertical = SystemNnnVisualEffectMath.shakeOffset(0, 4, vertical = true)
        assertEquals(0f, vertical.x, 0.001f)
        assertEquals(-16f, vertical.y, 0.001f)
    }

    @Test
    fun scrollOffsetWrapsBothPositiveAndNegativeMotion() {
        val positive = SystemNnnVisualEffectMath.scrollOffset(
            frame = 3,
            parameters = listOf(20, 8, -5, 0),
            screenWidth = 20,
            screenHeight = 12
        )
        assertEquals(4f, positive.x, 0.001f)
        assertEquals(9f, positive.y, 0.001f)

        val negative = SystemNnnVisualEffectMath.scrollOffset(
            frame = 1,
            parameters = listOf(20, -3, -2, 0),
            screenWidth = 20,
            screenHeight = 12
        )
        assertEquals(17f, negative.x, 0.001f)
        assertEquals(10f, negative.y, 0.001f)
    }

    @Test
    fun characterJitterIsRepeatableForTheSameNativeParametersAndSeed() {
        val parameters = listOf(30, 8, 6, 8, 50, 50, 0, 0)
        val first = SystemNnnVisualEffectMath.charaBureOffset(5L, parameters, seed = 1234)
        val second = SystemNnnVisualEffectMath.charaBureOffset(5L, parameters, seed = 1234)
        val otherSeed = SystemNnnVisualEffectMath.charaBureOffset(5L, parameters, seed = 4321)

        assertEquals(first, second)
        assertNotEquals(first, otherSeed)
        assertTrue(kotlin.math.abs(first.x) <= 8f)
        assertTrue(kotlin.math.abs(first.y) <= 6f)
    }
}
