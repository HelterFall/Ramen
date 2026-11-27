package io.github.helterfall.ramen.nscripter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class NscripterSpbDecoderTest {
    private val decoder = NscripterSpbDecoder()

    @Test
    fun decodesDeltaPlanesIntoBottomUpSerpentineBmp() {
        val bits = BitWriter()
        bits.write(2, 16) // width
        bits.write(2, 16) // height

        for (firstSample in listOf(10, 20, 30)) {
            bits.write(firstSample, 8)
            bits.write(2, 3) // Four deltas of four bits each.
            bits.write(3, 4) // +2
            bits.write(1, 4) // +1
            bits.write(2, 4) // -1
            bits.write(0, 4) // 0; the fourth sample is past the 2x2 plane.
        }

        val bmp = decoder.decode(bits.toByteArray(), expectedUnpackedSize = 70)

        assertEquals(70, bmp.size)
        assertArrayEquals(byteArrayOf('B'.code.toByte(), 'M'.code.toByte()), bmp.copyOfRange(0, 2))
        assertEquals(70, readU32Le(bmp, 2))
        assertEquals(2, readU32Le(bmp, 18))
        assertEquals(2, readU32Le(bmp, 22))
        // BMP stores the bottom row first: (10,20,30), (12,22,32).
        assertArrayEquals(
            byteArrayOf(10, 20, 30, 12, 22, 32, 0, 0),
            bmp.copyOfRange(54, 62)
        )
        // The top row is reversed in the SPB bitstream: (12,22,32), (13,23,33).
        assertArrayEquals(
            byteArrayOf(12, 22, 32, 13, 23, 33, 0, 0),
            bmp.copyOfRange(62, 70)
        )
    }

    @Test
    fun rejectsTruncatedSpbBitstream() {
        try {
            decoder.decode(byteArrayOf(0, 2, 0, 2, 0x10))
            throw AssertionError("Expected an IOException for a truncated SPB plane.")
        } catch (expected: java.io.IOException) {
            assertEquals(true, expected.message?.contains("bitstream ended") == true)
        }
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private class BitWriter {
        private val output = ArrayList<Byte>()
        private var currentByte = 0
        private var bitsInCurrentByte = 0

        fun write(value: Int, bitCount: Int) {
            for (shift in bitCount - 1 downTo 0) {
                currentByte = (currentByte shl 1) or ((value ushr shift) and 1)
                bitsInCurrentByte++
                if (bitsInCurrentByte == 8) {
                    output += currentByte.toByte()
                    currentByte = 0
                    bitsInCurrentByte = 0
                }
            }
        }

        fun toByteArray(): ByteArray {
            if (bitsInCurrentByte > 0) {
                output += (currentByte shl (8 - bitsInCurrentByte)).toByte()
            }
            return output.toByteArray()
        }
    }
}
