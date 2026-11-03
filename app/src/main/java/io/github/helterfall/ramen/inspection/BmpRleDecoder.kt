package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.InputStream

/**
 * Bounded decoder for Windows BMP BI_RLE8 and BI_RLE4 streams.
 *
 * The returned byte array contains palette indices in top-down row order.
 * Unwritten pixels retain palette index zero, as required for skipped areas
 * in RLE delta/EOL sequences. The caller is responsible for mapping indices
 * through the BMP color table.
 */
internal object BmpRleDecoder {
    fun decode(
        input: InputStream,
        encodedLengthBytes: Long,
        width: Int,
        height: Int,
        bitsPerPixel: Int,
        paletteEntries: Int
    ): ByteArray {
        if (encodedLengthBytes < 0L || width <= 0 || height <= 0 ||
            bitsPerPixel !in SUPPORTED_BITS_PER_PIXEL ||
            paletteEntries !in 1..(1 shl bitsPerPixel)
        ) {
            throw IOException("BMP RLE metadata is outside the supported range.")
        }

        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > Int.MAX_VALUE.toLong()) {
            throw IOException("BMP RLE pixel plane exceeds the JVM array range.")
        }

        var remaining = encodedLengthBytes
        fun readByte(field: String): Int {
            if (remaining <= 0L) {
                throw IOException("BMP RLE stream ended while reading $field.")
            }
            val value = input.read()
            if (value < 0) {
                throw IOException("BMP RLE source ended before its indexed range.")
            }
            remaining--
            return value
        }

        fun readPaddingIfNeeded(byteCount: Int) {
            if (byteCount and 1 != 0) readByte("RLE word-alignment padding")
        }

        val pixels = ByteArray(pixelCount.toInt())
        var x = 0
        var y = 0
        var sawEndOfBitmap = false

        fun ensureRunFits(count: Int) {
            if (y !in 0 until height || count < 0 || count > width - x) {
                throw IOException("BMP RLE run exceeds the image row bounds.")
            }
        }

        fun writeIndex(index: Int) {
            if (index !in 0 until paletteEntries) {
                throw IOException("BMP RLE stream references a missing palette entry.")
            }
            val destinationY = height - 1 - y
            pixels[destinationY * width + x] = index.toByte()
            x++
        }

        decodeLoop@ while (remaining > 0L) {
            val count = readByte("RLE command")
            val value = readByte("RLE command value")

            if (count != 0) {
                ensureRunFits(count)
                repeat(count) { pixel ->
                    val index = if (bitsPerPixel == 8) {
                        value
                    } else if (pixel and 1 == 0) {
                        value ushr 4
                    } else {
                        value and 0x0F
                    }
                    writeIndex(index)
                }
                continue
            }

            when (value) {
                END_OF_LINE -> {
                    if (y !in 0 until height) {
                        throw IOException("BMP RLE end-of-line marker exceeds the image height.")
                    }
                    x = 0
                    y++
                }

                END_OF_BITMAP -> {
                    sawEndOfBitmap = true
                    break@decodeLoop
                }

                DELTA -> {
                    val deltaX = readByte("RLE delta x")
                    val deltaY = readByte("RLE delta y")
                    val nextX = x.toLong() + deltaX
                    val nextY = y.toLong() + deltaY
                    if (nextX > width.toLong() || nextY !in 0 until height.toLong()) {
                        throw IOException("BMP RLE delta moves outside the image bounds.")
                    }
                    x = nextX.toInt()
                    y = nextY.toInt()
                }

                else -> {
                    val absoluteCount = value
                    ensureRunFits(absoluteCount)
                    if (bitsPerPixel == 8) {
                        repeat(absoluteCount) {
                            writeIndex(readByte("RLE8 absolute pixel"))
                        }
                        readPaddingIfNeeded(absoluteCount)
                    } else {
                        val packedBytes = (absoluteCount + 1) / 2
                        var pixelsRemaining = absoluteCount
                        repeat(packedBytes) {
                            val packed = readByte("RLE4 absolute pixels")
                            if (pixelsRemaining > 0) {
                                writeIndex(packed ushr 4)
                                pixelsRemaining--
                            }
                            if (pixelsRemaining > 0) {
                                writeIndex(packed and 0x0F)
                                pixelsRemaining--
                            }
                        }
                        readPaddingIfNeeded(packedBytes)
                    }
                }
            }
        }

        if (!sawEndOfBitmap) {
            throw IOException("BMP RLE stream ended before the end-of-bitmap marker.")
        }
        return pixels
    }

    private const val END_OF_LINE = 0
    private const val END_OF_BITMAP = 1
    private const val DELTA = 2
    private val SUPPORTED_BITS_PER_PIXEL = setOf(4, 8)
}
