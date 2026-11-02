package io.github.helterfall.ramen.inspection

import org.junit.Assert.assertEquals
import org.junit.Test

class ResourceArchiveParsersTest {

    @Test
    fun shsSupportsOverlappingBackReferences() {
        // Literal A, B; then a distance-one reference that repeats B.
        val compressed = byteArrayOf(
            0x01,
            0x41,
            0x42,
            0x20
        )

        val result = ResourceArchiveParsers.decompressShs(
            input = compressed,
            expectedOutputSize = 5
        )

        assertEquals("ABBBB", result.toString(Charsets.US_ASCII))
    }

    @Test
    fun blackCycPackedBmpDecodesBgrPixelsIntoArgb() {
        val dwq = ByteArray(0x40 + 0x36 + 3)

        "IF PACKTYPE==".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0)
        "0 ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x0D)
        "BMP ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x2C)
        "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x30)

        val bmpOffset = 0x40
        writeI32Le(dwq, bmpOffset + 0x0A, 0x36)
        writeI32Le(dwq, bmpOffset + 0x12, 1)
        writeI32Le(dwq, bmpOffset + 0x16, 1)
        writeU16Le(dwq, bmpOffset + 0x1C, 24)

        // One BGR pixel: blue=1, green=2, red=3.
        dwq[bmpOffset + 0x36] = 1
        dwq[bmpOffset + 0x37] = 2
        dwq[bmpOffset + 0x38] = 3

        val result = ResourceArchiveParsers.decodeBlackCycDwqPackedBmp(dwq)

        assertEquals(1, result.width)
        assertEquals(1, result.height)
        assertEquals(1, result.packType)
        assertEquals(0xFF030201.toInt(), result.argbPixels.single())
    }

    private fun writeI32Le(
        destination: ByteArray,
        offset: Int,
        value: Int
    ) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
        destination[offset + 2] = (value ushr 16).toByte()
        destination[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeU16Le(
        destination: ByteArray,
        offset: Int,
        value: Int
    ) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
    }
}
