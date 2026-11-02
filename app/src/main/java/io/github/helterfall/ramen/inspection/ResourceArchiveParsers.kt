package io.github.helterfall.ramen.inspection

import java.io.EOFException
import java.io.IOException
import kotlin.math.abs

/**
 * Read-only decoders for binary resources encountered during archive inspection.
 *
 * SHS is implemented from GARbro's ShsCompression implementation:
 * https://github.com/morkt/GARbro/blob/master/ArcFormats/SHSystem/ArcHXP.cs
 *
 * Black Cyc DWQ packed BMP handling follows:
 * https://github.com/morkt/GARbro/blob/master/ArcFormats/BlackCyc/ImageDWQ.cs
 *
 * The SHS codec belongs to SH System resource formats. It is deliberately not
 * used for Black Cyc GPK/DWQ entries.
 */
object ResourceArchiveParsers {

    private const val MAX_SHS_OUTPUT_SIZE = 128 * 1024 * 1024
    private const val MAX_IMAGE_PIXELS = 24 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 96 * 1024 * 1024

    private const val DWQ_HEADER_SIZE = 0x40
    private const val BMP_HEADER_SIZE = 0x36

    private val PACKTYPE_PATTERN = Regex("^PACKTYPE=([0-9]+)(A?)$")

    data class DecodedDwqBitmap(
        val width: Int,
        val height: Int,
        val packType: Int,
        /** Pixels are packed as 0xAARRGGBB for Bitmap.Config.ARGB_8888. */
        val argbPixels: IntArray
    )

    /**
     * Decodes an SHS stream into exactly [expectedOutputSize] bytes.
     *
     * The output size must be obtained from validated enclosing-format metadata.
     * Truncated input, invalid back-references, and impossible lengths are rejected.
     */
    @Throws(IOException::class)
    fun decompressShs(
        input: ByteArray,
        expectedOutputSize: Int,
        maxOutputSize: Int = MAX_SHS_OUTPUT_SIZE
    ): ByteArray {
        require(expectedOutputSize >= 0) { "expectedOutputSize must not be negative." }
        require(maxOutputSize >= 0) { "maxOutputSize must not be negative." }
        require(expectedOutputSize <= maxOutputSize) {
            "SHS output exceeds the configured size limit."
        }

        if (expectedOutputSize == 0) return ByteArray(0)

        val output = ByteArray(expectedOutputSize)
        var sourceOffset = 0
        var destinationOffset = 0

        fun readU8(): Int {
            if (sourceOffset >= input.size) {
                throw EOFException(
                    "Truncated SHS stream at offset " + sourceOffset + "."
                )
            }
            return input[sourceOffset++].toInt() and 0xFF
        }

        fun readU16BigEndian(): Long {
            val high = readU8()
            val low = readU8()
            return (high.toLong() shl 8) or low.toLong()
        }

        fun readU32BigEndian(): Long {
            val a = readU8()
            val b = readU8()
            val c = readU8()
            val d = readU8()
            return (a.toLong() shl 24) or
                (b.toLong() shl 16) or
                (c.toLong() shl 8) or
                d.toLong()
        }

        while (destinationOffset < expectedOutputSize) {
            val control = readU8()

            if (control < 0x20) {
                val literalCount = when (control) {
                    0x1D -> readU8().toLong() + 0x1E
                    0x1E -> readU16BigEndian() + 0x11E
                    0x1F -> readU32BigEndian()
                    else -> control.toLong() + 1
                }

                val copyCount = minOf(
                    literalCount,
                    (expectedOutputSize - destinationOffset).toLong()
                ).toInt()

                if (copyCount <= 0 || sourceOffset > input.size - copyCount) {
                    throw EOFException("Truncated or invalid SHS literal sequence.")
                }

                input.copyInto(
                    destination = output,
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + copyCount
                )

                sourceOffset += copyCount
                destinationOffset += copyCount
                continue
            }

            val offset: Long
            var count: Long

            if ((control and 0x80) == 0) {
                when (control and 0x60) {
                    0x20 -> {
                        offset = ((control shr 2) and 0x07).toLong()
                        count = (control and 0x03).toLong()
                    }

                    0x40 -> {
                        offset = readU8().toLong()
                        count = (control and 0x1F).toLong() + 4
                    }

                    else -> {
                        offset = readU8().toLong() or
                            ((control and 0x1F).toLong() shl 8)

                        val extension = readU8()
                        count = when (extension) {
                            0xFE -> readU16BigEndian() + 0x102
                            0xFF -> readU32BigEndian()
                            else -> extension.toLong() + 4
                        }
                    }
                }
            } else {
                count = ((control shr 5) and 0x03).toLong()
                offset = (
                    ((control and 0x1F) shl 8) or readU8()
                ).toLong()
            }

            count += 3
            val distanceLong = offset + 1

            if (distanceLong > Int.MAX_VALUE) {
                throw IOException("SHS back-reference distance is out of range.")
            }

            val distance = distanceLong.toInt()
            val copyCount = minOf(
                count,
                (expectedOutputSize - destinationOffset).toLong()
            ).toInt()

            if (distance <= 0 || distance > destinationOffset || copyCount <= 0) {
                throw IOException(
                    "Invalid SHS back-reference: distance=" + distance +
                        ", outputOffset=" + destinationOffset + "."
                )
            }

            // Copy byte-by-byte because source and destination ranges may overlap.
            repeat(copyCount) { index ->
                output[destinationOffset + index] =
                    output[destinationOffset + index - distance]
            }

            destinationOffset += copyCount
        }

        return output
    }

    /**
     * Decodes Black Cyc DWQ entries using PACKTYPE=1 or PACKTYPE=3.
     *
     * [dwq] must contain exactly one extracted GPK entry, including the DWQ's
     * 0x40-byte resource header. The decoded pixels are returned in ARGB order.
     *
     * PACKTYPE=0/2 (uncompressed BMP), 5/7 (JPEG), and 8 (PNG) are intentionally
     * left to their respective decoders.
     */
    @Throws(IOException::class)
    fun decodeBlackCycDwqPackedBmp(dwq: ByteArray): DecodedDwqBitmap {
        if (dwq.size < DWQ_HEADER_SIZE) {
            throw EOFException("DWQ header is truncated.")
        }

        val packTypeText = String(
            dwq,
            0x30,
            0x10,
            Charsets.US_ASCII
        ).trimEnd(' ')

        val match = PACKTYPE_PATTERN.matchEntire(packTypeText)
            ?: throw IOException("Invalid DWQ PACKTYPE header.")

        val packType = match.groupValues[1].toIntOrNull()
            ?: throw IOException("Invalid DWQ PACKTYPE value.")

        val packedSize = when (packType) {
            1 -> {
                if (!asciiAt(dwq, 0, "IF PACKTYPE==") ||
                    !asciiAt(dwq, 0x0D, "0 ") ||
                    !asciiAt(dwq, 0x2C, "BMP ")
                ) {
                    throw IOException("Invalid special PACKTYPE=1 DWQ header.")
                }
                dwq.size - DWQ_HEADER_SIZE
            }

            3 -> {
                val size = readI32Le(dwq, 0x20)
                if (size < 0) {
                    throw IOException("Negative DWQ packed image size.")
                }
                size
            }

            else -> {
                throw IOException(
                    "PACKTYPE=" + packType + " is not a packed BMP."
                )
            }
        }

        if (packedSize > dwq.size - DWQ_HEADER_SIZE) {
            throw EOFException("DWQ packed image exceeds the extracted entry.")
        }

        val imageStart = DWQ_HEADER_SIZE
        val imageEnd = imageStart + packedSize

        val image = decodePackedBmpSegment(
            source = dwq,
            start = imageStart,
            endExclusive = imageEnd
        )

        if (packType == 3) {
            val declaredWidth = readU32Le(dwq, 0x24)
            val declaredHeight = readU32Le(dwq, 0x28)

            if (declaredWidth != image.width.toLong() ||
                declaredHeight != image.height.toLong()
            ) {
                throw IOException("DWQ and embedded BMP dimensions do not match.")
            }
        }

        val argbPixels = image.toArgb()

        // For PACKTYPE=3 the mask follows the packed image segment when present.
        if (packType == 3 && imageEnd < dwq.size) {
            val mask = decodePackedBmpSegment(
                source = dwq,
                start = imageEnd,
                endExclusive = dwq.size
            )

            if (mask.bitsPerPixel != 8 ||
                mask.width != image.width ||
                mask.height != image.height
            ) {
                throw IOException("Invalid DWQ alpha mask dimensions or bit depth.")
            }

            for (index in argbPixels.indices) {
                val paletteIndex = mask.pixels[index].toInt() and 0xFF
                val paletteColor = mask.paletteArgb.getOrNull(paletteIndex)

                val alpha = if (paletteColor != null) {
                    val red = (paletteColor ushr 16) and 0xFF
                    val green = (paletteColor ushr 8) and 0xFF
                    val blue = paletteColor and 0xFF
                    (red + green + blue) / 3
                } else {
                    // GARbro leaves an out-of-range palette index unchanged.
                    paletteIndex
                }

                argbPixels[index] =
                    (argbPixels[index] and 0x00FFFFFF) or (alpha shl 24)
            }
        }

        return DecodedDwqBitmap(
            width = image.width,
            height = image.height,
            packType = packType,
            argbPixels = argbPixels
        )
    }

    private data class PackedBmpPlane(
        val width: Int,
        val height: Int,
        val bitsPerPixel: Int,
        val pixels: ByteArray,
        val paletteArgb: IntArray
    )

    /**
     * Black Cyc's PACKBMP decoder:
     * - BMP header and palette are stored before the compressed pixel stream.
     * - A zero byte is followed by a zero-run length.
     * - Each decoded scanline is XORed with the previously decoded scanline.
     */
    @Throws(IOException::class)
    private fun decodePackedBmpSegment(
        source: ByteArray,
        start: Int,
        endExclusive: Int
    ): PackedBmpPlane {
        if (start < 0 ||
            endExclusive > source.size ||
            endExclusive - start < BMP_HEADER_SIZE
        ) {
            throw EOFException("Truncated packed BMP segment.")
        }

        val width = readI32Le(source, start + 0x12)
        val signedHeight = readI32Le(source, start + 0x16)
        val heightLong = abs(signedHeight.toLong())

        if (width <= 0 || heightLong <= 0 || heightLong > Int.MAX_VALUE) {
            throw IOException("Invalid DWQ BMP dimensions.")
        }

        val height = heightLong.toInt()
        val bitsPerPixel = readU16Le(source, start + 0x1C)

        val bytesPerPixel = when (bitsPerPixel) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> throw IOException(
                "Unsupported DWQ BMP depth: " + bitsPerPixel + "."
            )
        }

        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > MAX_IMAGE_PIXELS) {
            throw IOException("DWQ image exceeds the pixel limit.")
        }

        val strideLong = width.toLong() * bytesPerPixel.toLong()
        val outputSizeLong = strideLong * height.toLong()

        if (strideLong > Int.MAX_VALUE || outputSizeLong > MAX_IMAGE_BYTES) {
            throw IOException("DWQ decoded image exceeds the memory limit.")
        }

        val stride = strideLong.toInt()
        val outputSize = outputSizeLong.toInt()
        val segmentLength = endExclusive - start
        val dataOffset = readU32Le(source, start + 0x0A)

        if (dataOffset < BMP_HEADER_SIZE.toLong() ||
            dataOffset > segmentLength.toLong()
        ) {
            throw IOException("Invalid DWQ BMP pixel offset.")
        }

        val palette = if (bitsPerPixel == 8) {
            val declaredColors = readI32Le(source, start + 0x2E)
            val colors = if (declaredColors == 0) 256 else declaredColors

            if (colors !in 1..256) {
                throw IOException("Invalid DWQ BMP palette size.")
            }

            val paletteStart = start + BMP_HEADER_SIZE
            val paletteEnd = paletteStart.toLong() + colors.toLong() * 4L
            val pixelStart = start.toLong() + dataOffset

            if (paletteEnd > pixelStart ||
                paletteEnd > endExclusive.toLong()
            ) {
                throw EOFException("Truncated DWQ BMP palette.")
            }

            IntArray(colors) { index ->
                val offset = paletteStart + index * 4
                val blue = source[offset].toInt() and 0xFF
                val green = source[offset + 1].toInt() and 0xFF
                val red = source[offset + 2].toInt() and 0xFF
                argb(255, red, green, blue)
            }
        } else {
            IntArray(0)
        }

        var inputOffset = start + dataOffset.toInt()
        val pixels = ByteArray(outputSize)
        val previousLine = ByteArray(stride)

        for (row in 0 until height) {
            val rowStart = row * stride
            var column = 0

            while (column < stride) {
                if (inputOffset >= endExclusive) {
                    throw EOFException("Truncated DWQ compressed pixel row.")
                }

                val value = source[inputOffset++].toInt() and 0xFF

                if (value != 0) {
                    pixels[rowStart + column] = value.toByte()
                    column++
                } else {
                    if (inputOffset >= endExclusive) {
                        throw EOFException("Truncated DWQ zero-run sequence.")
                    }

                    val count = source[inputOffset++].toInt() and 0xFF
                    if (count == 0 || count > stride - column) {
                        throw IOException("Invalid DWQ zero-run length.")
                    }

                    // The destination is zero-initialized.
                    column += count
                }
            }

            for (index in 0 until stride) {
                val current = pixels[rowStart + index].toInt() and 0xFF
                val previous = previousLine[index].toInt() and 0xFF
                val decoded = (current xor previous).toByte()

                pixels[rowStart + index] = decoded
                previousLine[index] = decoded
            }
        }

        return PackedBmpPlane(
            width = width,
            height = height,
            bitsPerPixel = bitsPerPixel,
            pixels = pixels,
            paletteArgb = palette
        )
    }

    private fun PackedBmpPlane.toArgb(): IntArray {
        val result = IntArray(width * height)
        val bytesPerPixel = bitsPerPixel / 8

        for (index in result.indices) {
            val offset = index * bytesPerPixel

            result[index] = when (bitsPerPixel) {
                8 -> {
                    val paletteIndex = pixels[index].toInt() and 0xFF
                    paletteArgb.getOrNull(paletteIndex)
                        ?: throw IOException(
                            "DWQ pixel references an invalid palette entry."
                        )
                }

                16 -> {
                    val value =
                        (pixels[offset].toInt() and 0xFF) or
                            ((pixels[offset + 1].toInt() and 0xFF) shl 8)

                    val blue = (value and 0x1F) * 255 / 31
                    val green = ((value ushr 5) and 0x3F) * 255 / 63
                    val red = ((value ushr 11) and 0x1F) * 255 / 31

                    argb(255, red, green, blue)
                }

                24, 32 -> {
                    val blue = pixels[offset].toInt() and 0xFF
                    val green = pixels[offset + 1].toInt() and 0xFF
                    val red = pixels[offset + 2].toInt() and 0xFF
                    argb(255, red, green, blue)
                }

                else -> throw IOException("Unsupported DWQ pixel format.")
            }
        }

        return result
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)
    }

    private fun asciiAt(
        source: ByteArray,
        offset: Int,
        text: String
    ): Boolean {
        val expected = text.toByteArray(Charsets.US_ASCII)
        if (offset < 0 || offset > source.size - expected.size) return false

        return expected.indices.all { index ->
            source[offset + index] == expected[index]
        }
    }

    private fun readU8(source: ByteArray, offset: Int): Int {
        checkRange(source, offset, 1)
        return source[offset].toInt() and 0xFF
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int {
        return readU8(source, offset) or (readU8(source, offset + 1) shl 8)
    }

    private fun readI32Le(source: ByteArray, offset: Int): Int {
        return readU8(source, offset) or
            (readU8(source, offset + 1) shl 8) or
            (readU8(source, offset + 2) shl 16) or
            (readU8(source, offset + 3) shl 24)
    }

    private fun readU32Le(source: ByteArray, offset: Int): Long {
        return readU8(source, offset).toLong() or
            (readU8(source, offset + 1).toLong() shl 8) or
            (readU8(source, offset + 2).toLong() shl 16) or
            (readU8(source, offset + 3).toLong() shl 24)
    }

    private fun checkRange(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 ||
            length < 0 ||
            offset > source.size - length
        ) {
            throw EOFException("Binary read exceeds buffer bounds.")
        }
    }
}
