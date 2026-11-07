package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnVisualEffectMathTest {

    @Test
    fun nativeEffectContinuationAndUnregisteredIdsDoNotReplaceThePreviousEffect() {
        for (effectId in listOf(-1, 1, 4, 5, 6, 7)) {
            assertFalse(SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(effectId))
        }
        for (effectId in listOf(0, 8, 9, 66, 82)) {
            assertTrue(SystemNnnVisualEffectMath.shouldApplyNativeLayerEffectCommand(effectId))
        }
    }

    @Test
    fun composeOnlySuppressesTheLayerThatHasItsOwnBitmapDrawingBranch() {
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(39, emptyList()))

        // Native effects that have no replacement painter in Compose must keep
        // their source visible instead of disappearing altogether.
        val fallbackEffects = listOf(
            8, 9, 19, 27, 40, 54, 61, 64, 65, 66, 82, 90,
            93, 94, 95, 99, 100, 107, 110, 113, 124, 125, 126, 127, 131, 132
        )
        for (effectId in fallbackEffects) {
            assertTrue(
                "effect $effectId must retain its source bitmap until a Compose painter exists",
                SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(effectId, emptyList())
            )
        }

        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(null, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(0, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(29, emptyList()))
    }

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
            33,
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
    fun screenFadeInStartsWithColorAndEndsOnTheScene() {
        val parameters = listOf(20, 24, 32, 48, 0, 0)

        val start = SystemNnnVisualEffectMath.screenFade(10, 0, parameters)
        val end = SystemNnnVisualEffectMath.screenFade(10, 20, parameters)

        assertEquals(1f, checkNotNull(start).alpha, 0.001f)
        assertEquals(0f, checkNotNull(end).alpha, 0.001f)
        assertEquals(SystemNnnVisualColor(24, 32, 48), start.color)
    }

    @Test
    fun screenFadeOutStartsOnTheSceneAndEndsWithColor() {
        val parameters = listOf(20, 10, 20, 30, 0, 0)

        val start = SystemNnnVisualEffectMath.screenFade(11, 0, parameters)
        val end = SystemNnnVisualEffectMath.screenFade(11, 20, parameters)

        assertEquals(0f, checkNotNull(start).alpha, 0.001f)
        assertEquals(1f, checkNotNull(end).alpha, 0.001f)
    }

    @Test
    fun nativeScreenColorInheritsMissingGreenAndBlueComponents() {
        assertEquals(
            SystemNnnVisualColor(42, 42, 42),
            SystemNnnVisualEffectMath.nativeScreenColor(listOf(20, 42))
        )
        assertEquals(
            SystemNnnVisualColor(42, 17, 17),
            SystemNnnVisualEffectMath.nativeScreenColor(listOf(20, 42, 17))
        )
    }

    @Test
    fun scrollLoopFlagDefaultsToDisabled() {
        assertFalse(SystemNnnVisualEffectMath.scrollUsesLoopFrame(emptyList()))
        assertFalse(SystemNnnVisualEffectMath.scrollUsesLoopFrame(listOf(20, 32, 0)))
        assertTrue(SystemNnnVisualEffectMath.scrollUsesLoopFrame(listOf(20, 32, 0, 1)))
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
    fun bezierAndBSplineUseNativeDefaultControlPointsAndDuration() {
        val bezierEnd = SystemNnnVisualEffectMath.bezierOffset(1, emptyList())
        val bSplineEnd = SystemNnnVisualEffectMath.bSpline2Offset(1, emptyList())

        assertEquals(400f, bezierEnd.x, 0.001f)
        assertEquals(100f, bezierEnd.y, 0.001f)
        assertEquals(400f, bSplineEnd.x, 0.001f)
        assertEquals(100f, bSplineEnd.y, 0.001f)
    }

    @Test
    fun nativeShakePatternMovesTheEntireFrameOnTheRequestedAxis() {
        val horizontal = (0..3).map {
            SystemNnnVisualEffectMath.shakeOffset(it, 4, vertical = false)
        }
        assertEquals(listOf(-16f, 12f, 16f, -12f), horizontal.map { it.x })
        assertTrue(horizontal.all { it.y == 0f })

        val vertical = SystemNnnVisualEffectMath.shakeOffset(0, 4, vertical = true)
        assertEquals(0f, vertical.x, 0.001f)
        assertEquals(-16f, vertical.y, 0.001f)
    }


    @Test
    fun moveRectMatchesNativeSignedRoundingAndKeepsSourceSizeUntilTheEnd() {
        val from = SystemNnnNativeRect(0, 0, 100, 120)
        val to = SystemNnnNativeRect(-3, 5, 80, 90)

        val middle = SystemNnnVisualEffectMath.moveRect(5, 10, from, to)
        assertEquals(-2, middle.x)
        assertEquals(3, middle.y)
        assertEquals(100, middle.width)
        assertEquals(120, middle.height)
        assertEquals(to, SystemNnnVisualEffectMath.moveRect(10, 10, from, to))
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
    fun screenFadeUsesNativeDirectionAndGrayscaleColorDefaults() {
        val parameters = listOf(20, 128)

        val fadeInStart = SystemNnnVisualEffectMath.screenFade(10, 0, parameters)
        val fadeInEnd = SystemNnnVisualEffectMath.screenFade(10, 20, parameters)
        val fadeOutStart = SystemNnnVisualEffectMath.screenFade(11, 0, parameters)
        val fadeOutEnd = SystemNnnVisualEffectMath.screenFade(11, 20, parameters)

        assertEquals(SystemNnnVisualColor(128, 128, 128), fadeInStart?.color)
        assertEquals(1f, fadeInStart?.alpha ?: -1f, 0.001f)
        assertEquals(0f, fadeInEnd?.alpha ?: -1f, 0.001f)
        assertEquals(0f, fadeOutStart?.alpha ?: -1f, 0.001f)
        assertEquals(1f, fadeOutEnd?.alpha ?: -1f, 0.001f)
    }

    @Test
    fun screenColorDefaultsEachMissingChannelToThePreviousChannel() {
        assertEquals(
            SystemNnnVisualColor(40, 40, 40),
            SystemNnnVisualEffectMath.nativeScreenColor(listOf(20, 40))
        )
        assertEquals(
            SystemNnnVisualColor(40, 90, 90),
            SystemNnnVisualEffectMath.nativeScreenColor(listOf(20, 40, 90))
        )
    }

    @Test
    fun bezierAndBSplineUseTheirNativeSpeedTypeOperands() {
        val bezier = listOf(20, 0, 0, 33, 0, 67, 0, 100, 0, 30, 0)
        val bSpline = listOf(20, 0, 0, 50, 0, 100, 0, 30, 0)

        assertEquals(25f, SystemNnnVisualEffectMath.bezierOffset(10, bezier).x, 0.2f)
        assertEquals(25f, SystemNnnVisualEffectMath.bSpline2Offset(10, bSpline).x, 0.01f)
    }

    @Test
    fun layerVisualConfigurationSurvivesSeparateCommandAdvances() {
        val store = SystemNnnVisualLayerConfigurationStore()
        val from = SystemNnnNativeRect(10, 20, 300, 400)
        val to = SystemNnnNativeRect(50, 60, 300, 400)

        val firstAdvance = store.snapshot()
        firstAdvance[7] = SystemNnnVisualLayerConfiguration(
            sourceRect = from,
            destinationRect = to
        )
        store.replaceWith(firstAdvance)

        val secondAdvance = store.snapshot()
        assertEquals(from, secondAdvance[7]?.sourceRect)
        assertEquals(to, secondAdvance[7]?.destinationRect)

        secondAdvance[7] = checkNotNull(secondAdvance[7]).copy(
            effectId = 52,
            effectParameters = listOf(24),
            effectCommandOffset = 0x1234L
        )
        store.replaceWith(secondAdvance)

        val afterImageLoad = checkNotNull(store.snapshot()[7])
        assertEquals(from, afterImageLoad.sourceRect)
        assertEquals(to, afterImageLoad.destinationRect)
        assertEquals(52, afterImageLoad.effectId)
        assertEquals(listOf(24), afterImageLoad.effectParameters)
        assertEquals(0x1234L, afterImageLoad.effectCommandOffset)
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
