package io.github.helterfall.ramen.nscripter

import java.io.IOException

/**
 * Decoder for ONScripter's SPB (Special Picture Bitmap) stream.
 *
 * SPB begins with big-endian width/height and then three MSB-first bit planes.
 * Each plane contains a first literal followed by four-pixel delta/run groups.
 * The result is a conventional bottom-up, 24-bit BMP with a 54-byte header.
 */
class NscripterSpbDecoder {
    @Throws(IOException::class)
    fun decode(
        packed: ByteArray,
        expectedUnpackedSize: Long? = null,
        maxOutputBytes: Long = DEFAULT_MAX_OUTPUT_BYTES
    ): ByteArray {
        if (packed.size < SPB_DIMENSIONS_SIZE) {
            malformed("SPB stream is shorter than its width/height header.")
        }

        val width = readU16(packed, 0)
        val height = readU16(packed, 2)
        if (width == 0 || height == 0) {
            malformed("SPB image dimensions must be non-zero.")
        }

        val rawRowBytes = width.toLong() * BYTES_PER_PIXEL
        val rowPadding = (4L - rawRowBytes % 4L) % 4L
        val rowStride = rawRowBytes + rowPadding
        val imageBytes = checkedMultiply(rowStride, height.toLong(), "SPB image data")
        val outputBytes = checkedAdd(BMP_HEADER_SIZE.toLong(), imageBytes, "SPB BMP size")
        if (outputBytes > maxOutputBytes || outputBytes > Int.MAX_VALUE.toLong()) {
            malformed("SPB BMP exceeds the configured output limit.")
        }
        if (expectedUnpackedSize != null && expectedUnpackedSize != outputBytes) {
            malformed(
                "SPB output size mismatch: archive declares $expectedUnpackedSize bytes, " +
                    "but dimensions require $outputBytes."
            )
        }

        val pixelCountLong = width.toLong() * height.toLong()
        if (pixelCountLong > Int.MAX_VALUE) {
            malformed("SPB pixel count exceeds the supported in-memory limit.")
        }
        val pixelCount = pixelCountLong.toInt()
        val bmp = ByteArray(outputBytes.toInt())
        writeBmpHeader(
            bmp = bmp,
            width = width,
            height = height,
            rowStride = rowStride,
            imageBytes = imageBytes,
            outputBytes = outputBytes
        )

        val bits = MsbBitReader(packed, SPB_DIMENSIONS_SIZE)
        for (channel in 0 until BYTES_PER_PIXEL) {
            val samples = decodePlane(bits, pixelCount)
            writePlane(
                bmp = bmp,
                samples = samples,
                width = width,
                height = height,
                rowStride = rowStride,
                channel = channel
            )
        }
        return bmp
    }

    private fun decodePlane(bits: MsbBitReader, pixelCount: Int): ByteArray {
        val samples = ByteArray(pixelCount)
        var value = bits.read(8)
        var produced = 0
        samples[produced++] = value.toByte()

        while (produced < pixelCount) {
            val code = bits.read(3)
            if (code == 0) {
                // A zero code repeats the previous sample four times.
                repeat(4) {
                    if (produced < pixelCount) samples[produced] = value.toByte()
                    produced++
                }
                continue
            }

            val deltaBits = when (code) {
                7 -> bits.read(1) + 1
                else -> code + 2
            }
            repeat(4) {
                value = if (deltaBits == 8) {
                    bits.read(8)
                } else {
                    val deltaCode = bits.read(deltaBits)
                    val delta = if ((deltaCode and 1) != 0) {
                        (deltaCode ushr 1) + 1
                    } else {
                        -(deltaCode ushr 1)
                    }
                    (value + delta) and 0xff
                }
                if (produced < pixelCount) samples[produced] = value.toByte()
                produced++
            }
        }

        return samples
    }

    private fun writePlane(
        bmp: ByteArray,
        samples: ByteArray,
        width: Int,
        height: Int,
        rowStride: Long,
        channel: Int
    ) {
        // ONScripter stores each colour plane in a bottom-up serpentine traversal.
        for (scanIndex in samples.indices) {
            val scanRow = scanIndex / width
            val columnInScan = scanIndex % width
            val x = if ((scanRow and 1) == 0) columnInScan else width - 1 - columnInScan
            val y = height - 1 - scanRow
            val destination = BMP_HEADER_SIZE.toLong() +
                y.toLong() * rowStride +
                x.toLong() * BYTES_PER_PIXEL +
                channel
            bmp[destination.toInt()] = samples[scanIndex]
        }
    }

    private fun writeBmpHeader(
        bmp: ByteArray,
        width: Int,
        height: Int,
        rowStride: Long,
        imageBytes: Long,
        outputBytes: Long
    ) {
        bmp[0] = 'B'.code.toByte()
        bmp[1] = 'M'.code.toByte()
        writeU32Le(bmp, 2, outputBytes)
        writeU32Le(bmp, 10, BMP_HEADER_SIZE.toLong())
        writeU32Le(bmp, 14, BITMAP_INFO_HEADER_SIZE.toLong())
        writeU32Le(bmp, 18, width.toLong())
        writeU32Le(bmp, 22, height.toLong())
        writeU16Le(bmp, 26, 1)
        writeU16Le(bmp, 28, 24)
        writeU32Le(bmp, 30, 0L)
        writeU32Le(bmp, 34, imageBytes)
        // Resolution and palette fields intentionally remain zero.
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun writeU16Le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32Le(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { byteIndex ->
            bytes[offset + byteIndex] = (value ushr (byteIndex * 8)).toByte()
        }
    }

    private fun checkedAdd(left: Long, right: Long, description: String): Long {
        if (right > Long.MAX_VALUE - left) malformed("Overflow in $description.")
        return left + right
    }

    private fun checkedMultiply(left: Long, right: Long, description: String): Long {
        if (left != 0L && right > Long.MAX_VALUE / left) malformed("Overflow in $description.")
        return left * right
    }

    private fun malformed(message: String): Nothing = throw IOException(message)

    private class MsbBitReader(
        private val bytes: ByteArray,
        startOffset: Int
    ) {
        private var bitPosition = startOffset * 8

        @Throws(IOException::class)
        fun read(bitCount: Int): Int {
            if (bitCount !in 1..8) {
                throw IOException("Invalid SPB bit width: $bitCount.")
            }
            if (bitPosition.toLong() + bitCount > bytes.size.toLong() * 8L) {
                throw IOException("SPB bitstream ended unexpectedly.")
            }

            var result = 0
            repeat(bitCount) {
                val byteIndex = bitPosition ushr 3
                val bitInByte = 7 - (bitPosition and 7)
                result = (result shl 1) or
                    ((bytes[byteIndex].toInt() ushr bitInByte) and 1)
                bitPosition++
            }
            return result
        }
    }

    companion object {
        private const val SPB_DIMENSIONS_SIZE = 4
        private const val BMP_HEADER_SIZE = 54
        private const val BITMAP_INFO_HEADER_SIZE = 40
        private const val BYTES_PER_PIXEL = 3
        const val DEFAULT_MAX_OUTPUT_BYTES = 64L * 1024L * 1024L
    }
}
