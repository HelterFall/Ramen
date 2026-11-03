package io.github.helterfall.ramen.inspection

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BmpRleDecoderTest {
    @Test
    fun decodesRle8EncodedAndAbsoluteRunsIntoTopDownRows() {
        val stream = byteArrayOf(
            5, 1,             // bottom row: five copies of palette index 1
            0, 0,             // end of line
            0, 5,             // top row: five absolute palette indices
            2, 3, 4, 3, 2,
            0,                // word-alignment padding for five literal bytes
            0, 1              // end of bitmap
        )

        val pixels = BmpRleDecoder.decode(
            input = ByteArrayInputStream(stream),
            encodedLengthBytes = stream.size.toLong(),
            width = 5,
            height = 2,
            bitsPerPixel = 8,
            paletteEntries = 8
        )

        assertArrayEquals(
            byteArrayOf(2, 3, 4, 3, 2, 1, 1, 1, 1, 1),
            pixels
        )
    }

    @Test
    fun decodesRle4EncodedAndAbsoluteRuns() {
        val encodedRun = byteArrayOf(
            5, 0x12, // five pixels alternating palette indices 1 and 2
            0, 1
        )
        val encodedPixels = BmpRleDecoder.decode(
            ByteArrayInputStream(encodedRun),
            encodedRun.size.toLong(),
            width = 5,
            height = 1,
            bitsPerPixel = 4,
            paletteEntries = 16
        )
        assertArrayEquals(byteArrayOf(1, 2, 1, 2, 1), encodedPixels)

        val absoluteRun = byteArrayOf(
            0, 5,             // five absolute pixels
            0x12, 0x34, 0x50, // packed pairs, last low nibble unused
            0,                // word-alignment padding for three bytes
            0, 1              // end of bitmap
        )
        val absolutePixels = BmpRleDecoder.decode(
            ByteArrayInputStream(absoluteRun),
            absoluteRun.size.toLong(),
            width = 5,
            height = 1,
            bitsPerPixel = 4,
            paletteEntries = 16
        )
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), absolutePixels)
    }

    @Test
    fun supportsDeltaAndLeavesSkippedPixelsAtPaletteIndexZero() {
        val stream = byteArrayOf(
            0, 2, 2, 0, // delta two pixels right
            1, 3,       // one encoded pixel
            0, 1        // end of bitmap
        )

        val pixels = BmpRleDecoder.decode(
            ByteArrayInputStream(stream),
            stream.size.toLong(),
            width = 4,
            height = 1,
            bitsPerPixel = 8,
            paletteEntries = 4
        )

        assertArrayEquals(byteArrayOf(0, 0, 3, 0), pixels)
    }

    @Test
    fun rejectsRunsThatCrossRowsAndStreamsWithoutEndMarker() {
        val overflow = byteArrayOf(5, 1, 0, 1)
        assertThrows(IOException::class.java) {
            BmpRleDecoder.decode(
                ByteArrayInputStream(overflow),
                overflow.size.toLong(),
                width = 4,
                height = 1,
                bitsPerPixel = 8,
                paletteEntries = 4
            )
        }

        val missingEnd = byteArrayOf(1, 1)
        assertThrows(IOException::class.java) {
            BmpRleDecoder.decode(
                ByteArrayInputStream(missingEnd),
                missingEnd.size.toLong(),
                width = 4,
                height = 1,
                bitsPerPixel = 8,
                paletteEntries = 4
            )
        }
    }

    @Test
    fun rejectsMissingPaletteIndicesAndTruncatedAbsoluteRuns() {
        val missingPaletteIndex = byteArrayOf(1, 7, 0, 1)
        assertThrows(IOException::class.java) {
            BmpRleDecoder.decode(
                ByteArrayInputStream(missingPaletteIndex),
                missingPaletteIndex.size.toLong(),
                width = 1,
                height = 1,
                bitsPerPixel = 8,
                paletteEntries = 4
            )
        }

        val truncatedAbsolute = byteArrayOf(0, 4, 1, 2)
        assertThrows(IOException::class.java) {
            BmpRleDecoder.decode(
                ByteArrayInputStream(truncatedAbsolute),
                truncatedAbsolute.size.toLong(),
                width = 4,
                height = 1,
                bitsPerPixel = 8,
                paletteEntries = 8
            )
        }
    }
}
