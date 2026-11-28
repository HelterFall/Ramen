package io.github.helterfall.ramen.runtime

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BmpFallbackDecoderTest {
    @Test
    fun decodesIndexedAndTrueColourFormatsAtEverySupportedDepth() {
        for (bitsPerPixel in listOf(1, 4, 8)) {
            val paletteSize = 1 shl bitsPerPixel
            val palette = IntArray(paletteSize) { index ->
                0xFF000000.toInt() or
                    ((index * 37 and 0xFF) shl 16) or
                    ((index * 19 and 0xFF) shl 8) or
                    (index * 11 and 0xFF)
            }
            val topRows = listOf(listOf(1, 0), listOf(0, 1))
            val encoded = createIndexedBmp(bitsPerPixel, topRows, palette)
            val decoded = BmpFallbackDecoder.decodePixels(encoded)!!
            assertEquals(2, decoded.width)
            assertEquals(2, decoded.height)
            assertArrayEquals(
                intArrayOf(palette[1], palette[0], palette[0], palette[1]),
                decoded.pixels
            )
        }

        val palette = IntArray(256) { index ->
            0xFF000000.toInt() or ((index * 5 and 0xFF) shl 16) or
                ((index * 7 and 0xFF) shl 8) or (index * 13 and 0xFF)
        }
        val indexed8 = BmpFallbackDecoder.decodePixels(
            createIndexedBmp(8, listOf(listOf(5, 200), listOf(200, 5)), palette)
        )!!
        assertArrayEquals(
            intArrayOf(palette[5], palette[200], palette[200], palette[5]),
            indexed8.pixels
        )

        val trueColour24 = listOf(
            listOf(0xFF123456.toInt(), 0xFFABCDEF.toInt()),
            listOf(0xFF102030.toInt(), 0xFF405060.toInt())
        )
        val decoded24 = BmpFallbackDecoder.decodePixels(
            createTrueColourBmp(24, trueColour24, topDown = true)
        )!!
        assertArrayEquals(trueColour24.flatten().toIntArray(), decoded24.pixels)

        val trueColour32 = listOf(
            listOf(0xFF0A1B2C.toInt(), 0xFF3D4E5F.toInt()),
            listOf(0xFF607182.toInt(), 0xFF93A4B5.toInt())
        )
        val decoded32 = BmpFallbackDecoder.decodePixels(
            createTrueColourBmp(32, trueColour32, topDown = false)
        )!!
        assertArrayEquals(trueColour32.flatten().toIntArray(), decoded32.pixels)
    }

    @Test
    fun decodesBitmapCoreHeaderAndUsesThreeBytePaletteEntries() {
        val palette = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt())
        val bytes = ByteArray(14 + 12 + 3 * 2 + 4)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size)
        putU32(bytes, 10, 32)
        putU32(bytes, 14, 12)
        putU16(bytes, 18, 1)
        putU16(bytes, 20, 1)
        putU16(bytes, 22, 1)
        putU16(bytes, 24, 1)
        // CORE palette entries are BGR triplets.
        bytes[26] = 0
        bytes[27] = 0
        bytes[28] = 255.toByte()
        bytes[29] = 0
        bytes[30] = 255.toByte()
        bytes[31] = 0
        bytes[32] = 0x80.toByte()

        val decoded = BmpFallbackDecoder.decodePixels(bytes)!!
        assertEquals(1, decoded.width)
        assertEquals(1, decoded.height)
        assertEquals(palette[1], decoded.pixels[0])
    }

    @Test
    fun returnsNullForNonBmpAndRejectsMalformedBmpHeaders() {
        assertNull(BmpFallbackDecoder.decodePixels(byteArrayOf(1, 2, 3, 4)))
        val malformed = ByteArray(40)
        malformed[0] = 'B'.code.toByte()
        malformed[1] = 'M'.code.toByte()
        assertThrows(IOException::class.java) {
            BmpFallbackDecoder.decodePixels(malformed)
        }
    }

    private fun createIndexedBmp(
        bitsPerPixel: Int,
        topRows: List<List<Int>>,
        palette: IntArray
    ): ByteArray {
        val width = topRows.first().size
        val height = topRows.size
        val rowStride = ((width * bitsPerPixel + 31) / 32) * 4
        val paletteOffset = 14 + 40
        val pixelOffset = paletteOffset + palette.size * 4
        val bytes = ByteArray(pixelOffset + rowStride * height)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size)
        putU32(bytes, 10, pixelOffset)
        putU32(bytes, 14, 40)
        putS32(bytes, 18, width)
        putS32(bytes, 22, height)
        putU16(bytes, 26, 1)
        putU16(bytes, 28, bitsPerPixel)
        putU32(bytes, 34, rowStride * height)
        putU32(bytes, 46, palette.size)
        palette.forEachIndexed { index, color ->
            val position = paletteOffset + index * 4
            bytes[position] = color.toByte()
            bytes[position + 1] = (color ushr 8).toByte()
            bytes[position + 2] = (color ushr 16).toByte()
            bytes[position + 3] = 0xA5.toByte() // BGRX reserved byte is ignored.
        }
        topRows.forEachIndexed { y, row ->
            val fileY = height - 1 - y
            val rowStart = pixelOffset + fileY * rowStride
            row.forEachIndexed { x, paletteIndex ->
                when (bitsPerPixel) {
                    1 -> {
                        val position = rowStart + x / 8
                        val shift = 7 - (x and 7)
                        bytes[position] = (bytes[position].toInt() or
                            ((paletteIndex and 1) shl shift)).toByte()
                    }
                    4 -> {
                        val position = rowStart + x / 2
                        val existing = bytes[position].toInt() and 0xFF
                        bytes[position] = if (x and 1 == 0) {
                            (existing or ((paletteIndex and 0x0F) shl 4)).toByte()
                        } else {
                            (existing or (paletteIndex and 0x0F)).toByte()
                        }
                    }
                    8 -> bytes[rowStart + x] = paletteIndex.toByte()
                }
            }
        }
        return bytes
    }

    private fun createTrueColourBmp(
        bitsPerPixel: Int,
        topRows: List<List<Int>>,
        topDown: Boolean
    ): ByteArray {
        val width = topRows.first().size
        val height = topRows.size
        val bytesPerPixel = bitsPerPixel / 8
        val rowStride = ((width * bitsPerPixel + 31) / 32) * 4
        val pixelOffset = 14 + 40
        val bytes = ByteArray(pixelOffset + rowStride * height)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size)
        putU32(bytes, 10, pixelOffset)
        putU32(bytes, 14, 40)
        putS32(bytes, 18, width)
        putS32(bytes, 22, if (topDown) -height else height)
        putU16(bytes, 26, 1)
        putU16(bytes, 28, bitsPerPixel)
        putU32(bytes, 34, rowStride * height)
        topRows.forEachIndexed { y, row ->
            val fileY = if (topDown) y else height - 1 - y
            val rowStart = pixelOffset + fileY * rowStride
            row.forEachIndexed { x, color ->
                val position = rowStart + x * bytesPerPixel
                bytes[position] = color.toByte()
                bytes[position + 1] = (color ushr 8).toByte()
                bytes[position + 2] = (color ushr 16).toByte()
                if (bitsPerPixel == 32) bytes[position + 3] = 0x7F.toByte()
            }
        }
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun putS32(bytes: ByteArray, offset: Int, value: Int) = putU32(bytes, offset, value)
}
