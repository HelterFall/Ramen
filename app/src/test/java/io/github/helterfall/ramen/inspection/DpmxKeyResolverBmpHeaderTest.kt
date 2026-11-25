package io.github.helterfall.ramen.inspection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DpmxKeyResolverBmpHeaderTest {
    @Test
    fun acceptsCoreAndInfoHeadersWithTheirCorrectPlanesOffsets() {
        assertTrue(isPlausibleDpmxBmpHeader(createBmp(dibSize = 40), 58L))
        assertTrue(isPlausibleDpmxBmpHeader(createBmp(dibSize = 12), 40L))
    }

    @Test
    fun rejectsNonZeroReservedBytesUnsupportedDibAndWrongPlanes() {
        val reserved = createBmp(dibSize = 40).also { it[8] = 1 }
        val unsupportedDib = createBmp(dibSize = 40).also { putU32(it, 14, 108L + 1L) }
        val wrongInfoPlanes = createBmp(dibSize = 40).also { putU16(it, 26, 2) }
        val wrongCorePlanes = createBmp(dibSize = 12).also { putU16(it, 22, 2) }
        assertFalse(isPlausibleDpmxBmpHeader(reserved, 58L))
        assertFalse(isPlausibleDpmxBmpHeader(unsupportedDib, 58L))
        assertFalse(isPlausibleDpmxBmpHeader(wrongInfoPlanes, 58L))
        assertFalse(isPlausibleDpmxBmpHeader(wrongCorePlanes, 40L))
    }

    private fun createBmp(dibSize: Int): ByteArray {
        val core = dibSize == 12
        val length = if (core) 30 else 30
        val bytes = ByteArray(length)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, if (core) 40L else 58L)
        putU32(bytes, 10, if (core) 32L else 54L)
        putU32(bytes, 14, dibSize.toLong())
        if (core) {
            putU16(bytes, 18, 1)
            putU16(bytes, 20, 1)
            putU16(bytes, 22, 1)
            putU16(bytes, 24, 24)
        } else {
            putU32(bytes, 18, 1L)
            putU32(bytes, 22, 1L)
            putU16(bytes, 26, 1)
            putU16(bytes, 28, 24)
        }
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index -> bytes[offset + index] = (value ushr (index * 8)).toByte() }
    }
}
