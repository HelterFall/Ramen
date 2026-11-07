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
    fun composeSuppressesNativeEffectsThatOwnPrintingOrAreNoOp() {
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(39, emptyList()))
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(64, emptyList()))
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(65, emptyList()))
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(99, emptyList()))
        // BUFFER (66) has native Print() == no-op and CheckAllPrint() == FALSE.
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(66, emptyList()))

        // The observed software CLOUD and layer-local HAIKEIYURE paths own their
        // source drawing; unsupported modes retain an explicit bitmap fallback.
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(
            82, listOf(0, 300, 13, 4, 9_999, 200, 1362, 200, 0, 1, 255, 160, 110, 50)
        ))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(
            82, listOf(0, 300, 13, 4, 9_999, 200, 1362, 200, 2, 1, 255, 160, 110, 50)
        ))
        assertFalse(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(100, listOf(20, 20, 0, 25, 25, 0)))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(100, listOf(20, 20, 0, 25, 25, 1)))

        val fallbackEffects = listOf(
            8, 9, 19, 27, 40, 54, 61, 90,
            93, 94, 95, 107, 110, 113, 124, 125, 126, 127, 131, 132
        )
        for (effectId in fallbackEffects) {
            assertTrue(
                "effect $effectId must retain its source bitmap until a Compose painter exists",
                SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(effectId, emptyList())
            )
        }

        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(null, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(0, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(17, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.shouldDrawPlainLayerBitmap(29, emptyList()))
    }




    @Test
    fun colorAddSubUsesNativeStartAndEndRgbParameterOrder() {
        val parameters = listOf(80, -130, -255, -255, -230, -255, -255, 1, 0, 0)
        assertEquals(SystemNnnVisualColor(-230, -255, -255),
            SystemNnnVisualEffectMath.colorAddSubOffset(0, parameters))
        assertEquals(SystemNnnVisualColor(-180, -255, -255),
            SystemNnnVisualEffectMath.colorAddSubOffset(40, parameters))
        assertEquals(SystemNnnVisualColor(-130, -255, -255),
            SystemNnnVisualEffectMath.colorAddSubOffset(80, parameters))
    }

    @Test
    fun stretchBltUsesNativeScaleOperandsAndSpeedParameters() {
        val parameters = listOf(
            430, 0, 0, 205, 205, 200, 200, 5, 3, 0, 100, 100, 0, 0
        )
        assertEquals(2.0f,
            SystemNnnVisualEffectMath.stretchBltScale(0, parameters, horizontal = true), 0.001f)
        assertEquals(2.0f,
            SystemNnnVisualEffectMath.stretchBltScale(0, parameters, horizontal = false), 0.001f)
        assertEquals(2.05f,
            SystemNnnVisualEffectMath.stretchBltScale(3, parameters, horizontal = true), 0.001f)
        assertEquals(1f, SystemNnnVisualEffectMath.stretchBltAlpha(3, parameters), 0.001f)
        assertEquals(SystemNnnVisualOffset(0f, 0f),
            SystemNnnVisualEffectMath.stretchBltPivotOffset(parameters))
    }

    @Test
    fun stretchBltRespectsNativeAlphaModeAndColorAddSubFlag() {
        val alphaParameters = listOf(
            20, 0, 0, 100, 100, 100, 100, 0, 0, 2, 40, 100, 0, 0
        )
        assertEquals(1f, SystemNnnVisualEffectMath.stretchBltAlpha(0, alphaParameters), 0.001f)
        assertEquals(0.7f, SystemNnnVisualEffectMath.stretchBltAlpha(10, alphaParameters), 0.001f)
        assertEquals(0.4f, SystemNnnVisualEffectMath.stretchBltAlpha(20, alphaParameters), 0.001f)
        assertEquals(SystemNnnVisualColor(0, 0, 0),
            SystemNnnVisualEffectMath.stretchBltColorOffset(5, alphaParameters))
        val colorParameters = alphaParameters.toMutableList().apply {
            this[14] = 1
            this[15] = 30; this[16] = 20; this[17] = 10
            this[18] = 0; this[19] = 0; this[20] = 0
        }
        assertEquals(SystemNnnVisualColor(15, 10, 5),
            SystemNnnVisualEffectMath.stretchBltColorOffset(10, colorParameters))
    }


    @Test
    fun turnMatchesObservedDiagnosticParameterVector() {
        val parameters = listOf(
            700, 375, 312, 0, 0, 220, 300, 2,
            100, 100, 0, 0, 0, 0, 0, 0
        )
        assertEquals(312f, SystemNnnVisualEffectMath.turnRotationDegrees(0, parameters), 0.001f)
        assertEquals(375f, SystemNnnVisualEffectMath.turnRotationDegrees(700, parameters), 0.001f)
        assertEquals(3f, SystemNnnVisualEffectMath.turnScale(0, parameters), 0.001f)
        assertEquals(2.2f, SystemNnnVisualEffectMath.turnScale(700, parameters), 0.001f)
        assertEquals(1f, SystemNnnVisualEffectMath.turnAlpha(350, parameters), 0.001f)
        assertEquals(
            SystemNnnVisualOffset(-252f, -238f),
            SystemNnnVisualEffectMath.turnCenteredOffset(0, parameters, -252, -238)
        )
    }

    @Test
    fun ctransOpacityMatchesObservedDiagnosticParameterVector() {
        val parameters = listOf(
            2000, 100, 0, 2, 100, 0, 0, 0, 800, 600, 0, 0, 800, 600
        )
        assertEquals(0, SystemNnnVisualEffectMath.nativeSpeedValue(
            0, 2000, 0, 100, delayCount = 100, speedType = 2
        ))
        assertEquals(50, SystemNnnVisualEffectMath.nativeSpeedValue(
            50, 2000, 0, 100, delayCount = 100, speedType = 2
        ))
        assertEquals(100, SystemNnnVisualEffectMath.nativeSpeedValue(
            100, 2000, 0, 100, delayCount = 100, speedType = 2
        ))
        assertEquals(null, SystemNnnVisualEffectMath.ctransClipRect(50, parameters, 800, 600))
        assertEquals(null, SystemNnnVisualEffectMath.ctransColorOffset(50, parameters))
    }

    @Test
    fun turnUsesNativeAngleScaleAlphaAndPivotOperandOrder() {
        val parameters = listOf(
            20, 180, 0, 12, -8, 200, 50, 2, 80, 20,
            0, 0, 0, 0, 0, 0
        )
        assertEquals(0f, SystemNnnVisualEffectMath.turnRotationDegrees(0, parameters), 0.001f)
        assertEquals(90f, SystemNnnVisualEffectMath.turnRotationDegrees(10, parameters), 0.001f)
        assertEquals(180f, SystemNnnVisualEffectMath.turnRotationDegrees(20, parameters), 0.001f)
        assertEquals(0.5f, SystemNnnVisualEffectMath.turnScale(0, parameters), 0.001f)
        assertEquals(1.25f, SystemNnnVisualEffectMath.turnScale(10, parameters), 0.001f)
        assertEquals(2f, SystemNnnVisualEffectMath.turnScale(20, parameters), 0.001f)
        assertEquals(0.2f, SystemNnnVisualEffectMath.turnAlpha(0, parameters), 0.001f)
        assertEquals(0.5f, SystemNnnVisualEffectMath.turnAlpha(10, parameters), 0.001f)
        assertEquals(0.8f, SystemNnnVisualEffectMath.turnAlpha(20, parameters), 0.001f)
        assertEquals(SystemNnnVisualOffset(12f, -8f),
            SystemNnnVisualEffectMath.turnPivotOffset(parameters))
    }


    @Test
    fun turnCenteredLayerOffsetKeepsNativePivotAtScreenCenterPlusDestinationOffset() {
        val parameters = listOf(
            20, 90, 0, 12, -8, 100, 100, 0
        )
        assertEquals(
            SystemNnnVisualOffset(-12f, 8f),
            SystemNnnVisualEffectMath.turnCenteredOffset(
                frame = 0,
                parameters = parameters,
                destinationX = 0,
                destinationY = 0
            )
        )
        assertEquals(
            SystemNnnVisualOffset(38f, 38f),
            SystemNnnVisualEffectMath.turnCenteredOffset(
                frame = 0,
                parameters = parameters,
                destinationX = 50,
                destinationY = 30
            )
        )
    }

    @Test
    fun turnDefaultParametersMatchNativeSetParam() {
        assertEquals(0f, SystemNnnVisualEffectMath.turnRotationDegrees(0, emptyList()), 0.001f)
        assertEquals(1f, SystemNnnVisualEffectMath.turnScale(0, emptyList()), 0.001f)
        assertEquals(1f, SystemNnnVisualEffectMath.turnAlpha(0, emptyList()), 0.001f)
        assertEquals(SystemNnnVisualOffset(0f, 0f),
            SystemNnnVisualEffectMath.turnSplineOffset(10, emptyList()))
    }

    @Test
    fun turnSplineUsesNativeQuadraticAndCubicControlPoints() {
        val quadratic = MutableList(32) { 0 }.apply {
            this[0] = 10
            this[25] = 2
            this[26] = 0; this[27] = 0
            this[28] = 100; this[29] = 100
            this[30] = 200; this[31] = 0
        }
        val midpoint = SystemNnnVisualEffectMath.turnSplineOffset(5, quadratic)
        assertEquals(100f, midpoint.x, 0.01f)
        assertEquals(50f, midpoint.y, 0.01f)
        val cubic = MutableList(36) { 0 }.apply {
            this[0] = 10
            this[25] = 3
            this[26] = 0; this[27] = 0
            this[28] = 100; this[29] = 100
            this[30] = 100; this[31] = 200
            this[32] = 0; this[33] = 0
        }
        val cubicMid = SystemNnnVisualEffectMath.turnSplineOffset(5, cubic)
        assertEquals(75f, cubicMid.x, 0.01f)
        assertEquals(112.5f, cubicMid.y, 0.01f)
    }

    @Test
    fun ctransColorAndClipUseNativeOperandIndices() {
        val parameters = listOf(
            20, 0, 100, 0, 0, 1,
            100, 80, 300, 200,
            0, 20, 100, 100, 1,
            30, 40, 50, 0, 10, 20,
            0, 0
        )
        assertEquals(SystemNnnVisualColor(0, 10, 20),
            SystemNnnVisualEffectMath.ctransColorOffset(0, parameters))
        assertEquals(SystemNnnVisualColor(15, 25, 35),
            SystemNnnVisualEffectMath.ctransColorOffset(10, parameters))
        assertEquals(SystemNnnVisualColor(30, 40, 50),
            SystemNnnVisualEffectMath.ctransColorOffset(20, parameters))
        assertEquals(SystemNnnNativeRect(0, 20, 100, 100),
            SystemNnnVisualEffectMath.ctransClipRect(0, parameters, 800, 600))
        assertEquals(SystemNnnNativeRect(50, 50, 200, 150),
            SystemNnnVisualEffectMath.ctransClipRect(10, parameters, 800, 600))
        assertEquals(SystemNnnNativeRect(100, 80, 300, 200),
            SystemNnnVisualEffectMath.ctransClipRect(20, parameters, 800, 600))
        assertEquals(null, SystemNnnVisualEffectMath.ctransClipRect(10, emptyList(), 800, 600))
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
    fun flashUsesNativeOnOffCycleThenStaysOnItsFinalFillColor() {
        val parameters = listOf(20, 2, 3, 20, 30, 40)

        assertEquals(SystemNnnVisualColor(20, 30, 40),
            SystemNnnVisualEffectMath.flashState(0, parameters).color)
        assertTrue(SystemNnnVisualEffectMath.flashState(0, parameters).drawsFill)
        assertTrue(SystemNnnVisualEffectMath.flashState(1, parameters).drawsFill)
        assertFalse(SystemNnnVisualEffectMath.flashState(2, parameters).drawsFill)
        assertTrue(SystemNnnVisualEffectMath.flashState(5, parameters).drawsFill)
        assertTrue(SystemNnnVisualEffectMath.flashState(20, parameters).drawsFill)
        assertTrue(SystemNnnVisualEffectMath.flashState(0, listOf(0, 0, 0, 1, 2, 3)).drawsFill)
    }

    @Test
    fun haikeiYureMatchesNativeLayerLocalWaveAndStopsAtCountMax() {
        val parameters = listOf(20, 20, 0, 25, 25, 0, 0, 0)
        assertEquals(SystemNnnVisualOffset(20f, 0f),
            SystemNnnVisualEffectMath.haikeiYureOffset(0, parameters))
        assertEquals(SystemNnnVisualOffset(0f, 0f),
            SystemNnnVisualEffectMath.haikeiYureOffset(1, parameters))
        assertEquals(SystemNnnVisualOffset(-20f, 0f),
            SystemNnnVisualEffectMath.haikeiYureOffset(2, parameters))
        assertEquals(SystemNnnVisualOffset(0f, 0f),
            SystemNnnVisualEffectMath.haikeiYureOffset(20, parameters))
        assertEquals(SystemNnnVisualOffset(0f, 0f),
            SystemNnnVisualEffectMath.haikeiYureOffset(0, listOf(0, 20, 0, 25, 25)))
    }

    @Test
    fun cloudUsesNativePerspectiveRowsAnd16Dot16HorizontalSampling() {
        val source = intArrayOf(
            0xff010101.toInt(), 0xff020202.toInt(), 0xff030303.toInt(), 0xff040404.toInt(),
            0xff050505.toInt(), 0xff060606.toInt(), 0xff070707.toInt(), 0xff080808.toInt()
        )
        val parameters = listOf(0, 2, 0, 0, 100, 1, 2, 100, 0, 0, 0, 0, 0, 0)
        val warped = checkNotNull(
            SystemNnnVisualEffectMath.cloudWarpPixels(
                frame = 0L,
                parameters = parameters,
                sourcePixels = source,
                sourceWidth = 4,
                sourceHeight = 2,
                screenWidth = 2,
                screenHeight = 2
            )
        )

        assertEquals(2, warped.width)
        assertEquals(2, warped.height)
        assertEquals(
            listOf(
                0xff040404.toInt(), 0xff020202.toInt(),
                0xff080808.toInt(), 0xff060606.toInt()
            ),
            warped.pixels.toList()
        )
    }

    @Test
    fun cloudAppliesNativePerRowSaturatingRgbAddAfterTheStartRow() {
        val source = IntArray(12) { 0xff112233.toInt() }
        val parameters = listOf(0, 3, 0, 0, 100, 1, 2, 100, 0, 1, 10, 0, 0, 1)
        val warped = checkNotNull(
            SystemNnnVisualEffectMath.cloudWarpPixels(
                0L, parameters, source, 4, 3, 2, 3
            )
        )
        // The first row at/after para[13] starts at zero; the final row
        // reaches half the configured +10 red offset for this 3-row test.
        assertEquals(0xff112233.toInt(), warped.pixels[0])
        assertEquals(0xff112233.toInt(), warped.pixels[2])
        assertEquals(0xff162233.toInt(), warped.pixels[4])
        assertEquals(null,
            SystemNnnVisualEffectMath.cloudWarpPixels(0L, parameters.toMutableList().apply { this[8] = 2 },
                source, 4, 2, 2, 2))
        assertEquals(null,
            SystemNnnVisualEffectMath.cloudWarpPixels(0L, parameters.toMutableList().apply { this[0] = 10 },
                source, 4, 2, 2, 2))
    }

    @Test
    fun nativeLayerSupportIsParameterGatedAndNoOpIsNotWarned() {
        assertTrue(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(0, emptyList()))
        assertTrue(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(17, listOf(20, 2, 3, 20, 30, 40)))
        assertTrue(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(
            82, listOf(0, 300, 13, 4, 9_999, 200, 1362, 200, 0, 1, 255, 160, 110, 50)
        ))
        assertFalse(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(
            82, listOf(20, 300, 13, 4, 9_999, 200, 1362, 200, 0, 1, 255, 160, 110, 50)
        ))
        assertTrue(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(100, listOf(20, 20, 0, 25, 25, 0)))
        assertFalse(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(100, listOf(20, 20, 0, 25, 25, 1)))
        assertFalse(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(27, emptyList()))
        assertFalse(SystemNnnVisualEffectMath.hasNativeLayerEffectPath(29, emptyList()))
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
