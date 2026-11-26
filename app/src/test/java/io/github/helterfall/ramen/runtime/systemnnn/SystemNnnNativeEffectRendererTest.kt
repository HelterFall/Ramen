package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnNativeEffectRendererTest {

    @Test
    fun haikeiYureScreenModeMovesTheSharedBufferAndLeavesExposedPixelsUntouched() {
        val source = intArrayOf(1, 2, 3, 4, 5, 6)
        val result = SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
            effectId = 100,
            frame = 0,
            parameters = listOf(4, 1, 0, 0, 0, 1),
            source = source,
            width = 3,
            height = 2
        )

        assertArrayEquals(intArrayOf(1, 1, 2, 4, 4, 5), result)
        assertArrayEquals(intArrayOf(1, 2, 3, 4, 5, 6), source)
    }

    @Test
    fun overrapUsesNativeEightBitFixedPointWeights() {
        val start = intArrayOf(0xFF000000.toInt(), 0xFF102030.toInt())
        val end = intArrayOf(0xFFFFFFFF.toInt(), 0xFF304050.toInt())

        assertArrayEquals(
            intArrayOf(0xFF000000.toInt(), 0xFF102030.toInt()),
            SystemNnnNativeEffectRenderer.blendOverrap(start, end, 100, 0)
        )
        assertArrayEquals(
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF304050.toInt()),
            SystemNnnNativeEffectRenderer.blendOverrap(start, end, 0, 100)
        )
        assertArrayEquals(
            intArrayOf(0xFF7F7F7F.toInt(), 0xFF203040.toInt()),
            SystemNnnNativeEffectRenderer.blendOverrap(start, end, 50, 50)
        )
    }

    @Test
    fun addWipeUsesTwoPhaseSaturatingBlend() {
        val start = intArrayOf(0xFF101010.toInt())
        val end = intArrayOf(0xFF202020.toInt())

        val firstHalf = SystemNnnNativeEffectRenderer.renderOverrapFrame(
            start, end, transitionType = 40, frame = 2, length = 8, width = 1, height = 1
        )
        val secondHalf = SystemNnnNativeEffectRenderer.renderOverrapFrame(
            start, end, transitionType = 40, frame = 6, length = 8, width = 1, height = 1
        )

        assertEquals(0xFF202020.toInt(), firstHalf?.single())
        assertEquals(0xFF282828.toInt(), secondHalf?.single())
        assertArrayEquals(start, SystemNnnNativeEffectRenderer.renderOverrapFrame(
            start, end, 40, 0, 8, 1, 1
        ))
        assertArrayEquals(end, SystemNnnNativeEffectRenderer.renderOverrapFrame(
            start, end, 40, 8, 8, 1, 1
        ))
    }

    @Test
    fun gradationAppliesNativeVerticalAdditiveRgbRampInsideP15P16Region() {
        val source = IntArray(8) { 0xFF000000.toInt() }
        val parameters = listOf(10, 100, 50, 20, 200, 150, 100, 0, 0, 0, 0, 0, 10, 20, 30)
        val result = SystemNnnNativeEffectRenderer.gradation(
            source, width = 2, height = 4, frame = 10, duration = 10, parameters = parameters
        )
        assertEquals(0xFF643214.toInt(), result[0])
        assertEquals(result[0], result[1])
        assertEquals(0xFF7D4B28.toInt(), result[2])
        assertEquals(result[2], result[3])
        assertEquals(0xFF96643C.toInt(), result[4])
        assertEquals(0xFFAF7D50.toInt(), result[6])
    }

    @Test
    fun gradationRespectsNativeStartYAndPrintHeight() {
        val source = IntArray(12) { 0xFF101010.toInt() }
        val parameters = listOf(10, 100, 100, 100, 100, 100, 100, 0, 0, 50, 50, 50, 0, 0, 0, 1, 2)
        val result = SystemNnnNativeEffectRenderer.gradation(
            source, 3, 4, 10, 10, parameters
        )
        assertArrayEquals(source.sliceArray(0..2), result.sliceArray(0..2))
        assertTrue(result[3] != source[3])
        assertTrue(result[6] != source[6])
        assertArrayEquals(source.sliceArray(9..11), result.sliceArray(9..11))
    }
    @Test
    fun kagerouUsesThreeIndependentNativeWavePhases() {
        val source = IntArray(8 * 8) { index ->
            val x = index % 8
            val y = index / 8
            (0xFF000000L or (x * 0x000101L) or (y * 0x010000L)).toInt()
        }
        val result = SystemNnnNativeEffectRenderer.kagerou(
            source, 8, 8, 16,
            listOf(20, 0, 8, 0, 100, 100, 100, 3, 8, 1, 2, 13, 2, 1, 3, 3)
        )
        assertEquals(source.size, result.size)
        assertTrue(!source.contentEquals(result))
    }

    @Test
    fun overrapTypeZeroIsTheExactNativeCrossDissolve() {
        val start = IntArray(16) { 0xFF102030.toInt() }
        val end = IntArray(16) { 0xFF304050.toInt() }
        val actual = SystemNnnNativeEffectRenderer.renderOverrapFrame(
            start, end, transitionType = 0, frame = 5, length = 10, width = 4, height = 4
        )
        val expected = SystemNnnNativeEffectRenderer.blendOverrap(start, end, 50, 50)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun overrapWipeSelectorsRetainBothEndpointsAndProduceIntermediateFrames() {
        val start = IntArray(64) { 0xFF000000.toInt() }
        val end = IntArray(64) { 0xFFFFFFFF.toInt() }
        for (type in listOf(1, 2, 3, 4, 5, 6, 7, 9, 22, 38, 39, 42, 60, 80, 84, 94)) {
            val first = SystemNnnNativeEffectRenderer.renderOverrapFrame(
                start, end, type, 0, 8, 8, 8
            )
            val middle = SystemNnnNativeEffectRenderer.renderOverrapFrame(
                start, end, type, 4, 8, 8, 8
            )
            val last = SystemNnnNativeEffectRenderer.renderOverrapFrame(
                start, end, type, 8, 8, 8, 8
            )
            assertArrayEquals("type=$type first frame", start, first)
            assertArrayEquals("type=$type final frame", end, last)
            assertNotNull("type=$type middle frame", middle)
            assertEquals(start.size, middle!!.size)
        }
    }
    @Test
    fun overrapWeightsCoverWholeTransitionWithoutGaps() {
        assertEquals(100 to 0, SystemNnnNativeEffectRenderer.overrapWeights(0, 90))
        assertEquals(50 to 50, SystemNnnNativeEffectRenderer.overrapWeights(45, 90))
        assertEquals(0 to 100, SystemNnnNativeEffectRenderer.overrapWeights(90, 90))
        assertEquals(0 to 100, SystemNnnNativeEffectRenderer.overrapWeights(999, 90))
    }

    @Test
    fun putRestoresSavedGetBufferWithoutMutatingIt() {
        val source = intArrayOf(0xFF010203.toInt(), 0xFF040506.toInt())
        val saved = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val result = SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
            2, 0, listOf(20, 0, 0, 0, 0),
            source, 2, 1, savedGetBuffer = saved
        )
        assertArrayEquals(saved, result)
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()), saved)
        assertNull(SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
            2, 0, emptyList(), source, 3, 1
        ))
    }

    @Test
    fun lightAddsAndClampsRgbWhilePreservingAlpha() {
        val source = intArrayOf(0x80102030.toInt(), 0xFFFEFDFB.toInt())
        val atStart = SystemNnnNativeEffectRenderer.light(source, 0, 40, listOf(40, 100, 80, 60, 0, 0))
        val atEnd = SystemNnnNativeEffectRenderer.light(source, 40, 40, listOf(40, 100, 80, 60, 0, 0))
        assertArrayEquals(source, atStart)
        assertEquals(0x8074706C.toInt(), atEnd[0])
        assertEquals(0xFFFFFFFF.toInt(), atEnd[1])
    }

    @Test
    fun negativeEffectRetainsDimensionsAndInvertsAtEnd() {
        val source = intArrayOf(0xFF0A2040.toInt(), 0xFF80A0C0.toInt())
        val result = SystemNnnNativeEffectRenderer.negative(
            source, 20, 20, listOf(20, 5, 5, 100, 0)
        )
        assertEquals(source.size, result.size)
        assertTrue((result[0] and 0x00FFFFFF) != (source[0] and 0x00FFFFFF))
        assertEquals(0xFF809FBF.toInt(), result[1])
    }

    @Test
    fun mosaicFillsEachBlockWithOnePixelSampleFromThatBlock() {
        val source = intArrayOf(
            0xFF010101.toInt(), 0xFF020202.toInt(), 0xFF030303.toInt(), 0xFF040404.toInt(),
            0xFF111111.toInt(), 0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF141414.toInt()
        )
        val result = SystemNnnNativeEffectRenderer.mosaic(source, 4, 2, 1, listOf(1, 2, 2, 0, 0, 4, 2))
        assertEquals(source.size, result.size)
        // Native PrintMozaicMain chooses a random source pixel inside each block;
        // it must not be assumed to always sample the centre or top-left corner.
        assertEquals(result[0], result[1])
        assertEquals(result[2], result[3])
        assertEquals(result[4], result[5])
        assertEquals(result[6], result[7])
        assertTrue(result[0] == source[0] || result[0] == source[1] || result[0] == source[4] || result[0] == source[5])
        assertTrue(result[2] == source[2] || result[2] == source[3] || result[2] == source[6] || result[2] == source[7])
        assertTrue(result[4] == source[0] || result[4] == source[1] || result[4] == source[4] || result[4] == source[5])
        assertTrue(result[6] == source[2] || result[6] == source[3] || result[6] == source[6] || result[6] == source[7])
    }

    @Test
    fun kagerouWithZeroAmplitudesIsIdentity() {
        val source = IntArray(20) { (0xFF000000L or (it * 0x010101L)).toInt() }
        val result = SystemNnnNativeEffectRenderer.kagerou(
            source, 5, 4, 30, listOf(120, 0, 4, 0, 100, 100, 100, 0, 10, 0, 0, 10, 0, 0, 10, 0)
        )
        assertArrayEquals(source, result)
    }

    @Test
    fun screenEffectsReturnNullForUnknownIdsAndPreserveBufferSizeForKnownIds() {
        val source = IntArray(32) { 0xFF334455.toInt() }
        val parameters = listOf(20, 80, 90, 100, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        for (id in listOf(20, 22, 29, 31, 43, 50, 69, 71, 74)) {
            val result = SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
                id, 5, parameters, source, 8, 4
            )
            assertNotNull("effect $id", result)
            assertEquals(source.size, result!!.size)
        }
        assertNull(SystemNnnNativeEffectRenderer.renderScreenBufferEffect(
            999, 0, emptyList(), source, 8, 4
        ))
    }

    @Test
    fun sunaNoiseUsesTheConfiguredScreenRectangle() {
        val source = IntArray(64) { 0xFF123456.toInt() }
        val first = SystemNnnNativeEffectRenderer.sunaNoise(
            source, 8, 8, 45, 90, listOf(90, 1, 20, 20, 0, 0, 0, 0, 8, 8)
        )
        val repeated = SystemNnnNativeEffectRenderer.sunaNoise(
            source, 8, 8, 45, 90, listOf(90, 1, 20, 20, 0, 0, 0, 0, 8, 8)
        )
        assertArrayEquals(first, repeated)
        assertEquals(64, first.size)
        val outside = SystemNnnNativeEffectRenderer.sunaNoise(
            source, 8, 8, 45, 90, listOf(90, 1, 20, 20, 0, 0, 4, 4, 2, 2)
        )
        assertEquals(source[0], outside[0])

        val koma = SystemNnnNativeEffectRenderer.komaAnimeSourceRect(
            256, 128, 5, listOf(20, 64, 64, 2)
        )
        assertEquals(SystemNnnNativeEffectRenderer.RectI(128, 0, 192, 64), koma)

        val rain2 = SystemNnnNativeEffectRenderer.rain2Offsets(
            12, listOf(20, 2, 3, 3, 8, 6, 50, 100), 800, 600
        )
        assertEquals(3, rain2.size)
        assertTrue(rain2.all { it.x in 0 until 800 && it.y in 0 until 600 })

        val rain4 = SystemNnnNativeEffectRenderer.rain4Copies(
            80, 60, 10, listOf(20, 3, 500, 20, -2, 400, 300, 30, 200), 800, 600
        )
        assertEquals(3, rain4.size)
        assertTrue(rain4.all { it.scale > 0f && it.alpha in 0f..1f })

        val particles = SystemNnnNativeEffectRenderer.keParticles(
            256, 256, 10, listOf(1, 32, 32, 16, 0, 10, 5, 1, 12, 81, 4, 120)
        )
        assertTrue(particles.size <= 16)
        assertTrue(particles.all { it.sourceRect.width == 32 && it.sourceRect.height == 32 })
    }
}
