package io.github.helterfall.ramen.runtime.systemnnn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnNativeEffectRendererTest {

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
        assertEquals(0x8064746A.toInt(), atEnd[0])
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
        assertEquals(0xFF808080.toInt(), result[1])
    }

    @Test
    fun mosaicUsesAStableRepresentativePixelForEachBlock() {
        val source = intArrayOf(
            0xFF010101.toInt(), 0xFF020202.toInt(), 0xFF030303.toInt(), 0xFF040404.toInt(),
            0xFF111111.toInt(), 0xFF121212.toInt(), 0xFF131313.toInt(), 0xFF141414.toInt()
        )
        val result = SystemNnnNativeEffectRenderer.mosaic(source, 4, 2, 1, listOf(1, 2, 2, 0, 0, 4, 2))
        assertArrayEquals(
            intArrayOf(
                0xFF020202.toInt(), 0xFF020202.toInt(), 0xFF040404.toInt(), 0xFF040404.toInt(),
                0xFF121212.toInt(), 0xFF121212.toInt(), 0xFF141414.toInt(), 0xFF141414.toInt()
            ),
            result
        )
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
        for (id in listOf(20, 22, 31, 43, 50, 69, 71, 74)) {
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
    fun sunaDissolveAndNativeLayerPlacementHelpersAreBoundedAndDeterministic() {
        val source = IntArray(64) { 0xFF123456.toInt() }
        val first = SystemNnnNativeEffectRenderer.sunaDissolve(
            source, 8, 8, 45, listOf(90, 1, 100, 20, 0, 0, 0, 800, 600)
        )
        val repeated = SystemNnnNativeEffectRenderer.sunaDissolve(
            source, 8, 8, 45, listOf(90, 1, 100, 20, 0, 0, 0, 800, 600)
        )
        assertArrayEquals(first, repeated)
        assertEquals(64, first.size)

        val koma = SystemNnnNativeEffectRenderer.komaAnimeSourceRect(
            256, 128, 5, listOf(20, 64, 64, 2)
        )
        assertEquals(SystemNnnNativeEffectRenderer.RectI(64, 64, 128, 128), koma)

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
