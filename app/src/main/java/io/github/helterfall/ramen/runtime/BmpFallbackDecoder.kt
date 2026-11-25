package io.github.helterfall.ramen.runtime

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.BmpRleDecoder
import java.io.ByteArrayInputStream
import java.io.IOException

/**
 * Pure Kotlin BMP parser used when Android's BitmapFactory cannot decode an old
 * HSP bitmap. The parser supports CORE/INFO headers, indexed and true-colour
 * pixels, word-aligned scanlines, top-down INFO images and RLE4/RLE8 streams.
 */
internal object BmpFallbackDecoder {
    internal data class DecodedPixels(
        val width: Int,
        val height: Int,
        val pixels: IntArray
    )

    private data class ChannelMasks(
        val red: Long,
        val green: Long,
        val blue: Long,
        val alpha: Long
    )

    fun hasBmpSignature(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Boolean =
        offset >= 0 && length >= 2 && offset <= bytes.size - length &&
            bytes[offset] == 'B'.code.toByte() && bytes[offset + 1] == 'M'.code.toByte()

    fun decode(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Bitmap? {
        val decoded = decodePixels(bytes, offset, length) ?: return null
        val bitmap = try {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the fallback BMP bitmap.", failure)
        }
        try {
            bitmap.setPixels(
                decoded.pixels, 0, decoded.width,
                0, 0, decoded.width, decoded.height
            )
            if (!bitmap.isMutable || bitmap.config != Bitmap.Config.ARGB_8888) {
                val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    ?: throw IOException("Could not normalize the fallback BMP bitmap.")
                bitmap.recycle()
                return mutable
            }
            return bitmap
        } catch (failure: RuntimeException) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw IOException("Could not populate the fallback BMP bitmap.", failure)
        }
    }

    /** Decodes to ARGB pixels without Android graphics APIs, making the format parser JVM-testable. */
    internal fun decodePixels(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset
    ): DecodedPixels? {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw IOException("BMP byte range is outside the supplied array.")
        }
        if (!hasBmpSignature(bytes, offset, length)) return null
        if (length < 26) throw IOException("BMP file header or CORE header is truncated.")

        fun requireRange(relativeOffset: Long, byteCount: Long, label: String) {
            if (relativeOffset < 0L || byteCount < 0L ||
                relativeOffset > length.toLong() ||
                byteCount > length.toLong() - relativeOffset
            ) {
                throw IOException("BMP " + label + " extends beyond the encoded image.")
            }
        }

        fun u8(relativeOffset: Int): Int {
            requireRange(relativeOffset.toLong(), 1L, "byte field")
            return bytes[offset + relativeOffset].toInt() and 0xFF
        }

        fun u16(relativeOffset: Int): Int {
            requireRange(relativeOffset.toLong(), 2L, "16-bit field")
            return u8(relativeOffset) or (u8(relativeOffset + 1) shl 8)
        }

        fun u32(relativeOffset: Int): Long {
            requireRange(relativeOffset.toLong(), 4L, "32-bit field")
            return (u8(relativeOffset).toLong()) or
                (u8(relativeOffset + 1).toLong() shl 8) or
                (u8(relativeOffset + 2).toLong() shl 16) or
                (u8(relativeOffset + 3).toLong() shl 24)
        }

        fun s32(relativeOffset: Int): Int = u32(relativeOffset).toInt()

        val declaredFileSize = u32(2)
        val dataLimit = when {
            declaredFileSize == 0L -> length
            declaredFileSize < 26L || declaredFileSize > length.toLong() ->
                throw IOException("BMP declared file size is inconsistent with the supplied bytes.")
            else -> declaredFileSize.toInt()
        }
        val pixelOffsetLong = u32(10)
        if (pixelOffsetLong > Int.MAX_VALUE.toLong()) {
            throw IOException("BMP pixel-data offset exceeds the supported address range.")
        }
        val pixelOffset = pixelOffsetLong.toInt()
        val dibHeaderSizeLong = u32(14)
        val supportedDibSizes = setOf(12L, 40L, 52L, 56L, 108L, 124L)
        if (dibHeaderSizeLong !in supportedDibSizes) {
            throw IOException("BMP uses an unsupported DIB header size.")
        }
        val dibHeaderSize = dibHeaderSizeLong.toInt()
        requireRange(14L, dibHeaderSizeLong, "DIB header")
        val coreHeader = dibHeaderSize == 12

        val width: Int
        val height: Int
        val topDown: Boolean
        val planes: Int
        val bitsPerPixel: Int
        val compression: Long
        val colorsUsed: Long
        val imageSize: Long
        if (coreHeader) {
            width = u16(18)
            height = u16(20)
            planes = u16(22)
            bitsPerPixel = u16(24)
            topDown = false
            compression = BI_RGB
            colorsUsed = 0L
            imageSize = 0L
        } else {
            width = s32(18)
            val signedHeight = s32(22)
            if (signedHeight == Int.MIN_VALUE) throw IOException("BMP height is outside the supported range.")
            topDown = signedHeight < 0
            height = kotlin.math.abs(signedHeight)
            planes = u16(26)
            bitsPerPixel = u16(28)
            compression = u32(30)
            imageSize = u32(34)
            colorsUsed = u32(46)
        }

        if (width <= 0 || height <= 0 || planes != 1 ||
            bitsPerPixel !in SUPPORTED_BITS_PER_PIXEL
        ) {
            throw IOException("BMP dimensions, planes or bit depth are invalid.")
        }
        val pixelCountLong = width.toLong() * height.toLong()
        if (width > MAX_DIMENSION || height > MAX_DIMENSION ||
            pixelCountLong > MAX_PIXELS || pixelCountLong > Int.MAX_VALUE.toLong()
        ) {
            throw IOException("BMP pixel plane exceeds the supported image limits.")
        }
        if (pixelOffset < 14 + dibHeaderSize || pixelOffset > dataLimit) {
            throw IOException("BMP pixel-data offset overlaps its headers or exceeds the file.")
        }

        val channelMasks: ChannelMasks?
        var metadataEnd = 14 + dibHeaderSize
        when (compression) {
            BI_RGB -> channelMasks = null
            BI_RLE8 -> {
                if (bitsPerPixel != 8 || topDown) {
                    throw IOException("BMP RLE8 compression is incompatible with the image format.")
                }
                channelMasks = null
            }
            BI_RLE4 -> {
                if (bitsPerPixel != 4 || topDown) {
                    throw IOException("BMP RLE4 compression is incompatible with the image format.")
                }
                channelMasks = null
            }
            BI_BITFIELDS, BI_ALPHABITFIELDS -> {
                if (bitsPerPixel != 32 || coreHeader) {
                    throw IOException("BMP bitfields are supported only for 32-bit INFO images.")
                }
                val maskBase = 14 + 40
                val redMask = u32(maskBase)
                val greenMask = u32(maskBase + 4)
                val blueMask = u32(maskBase + 8)
                val alphaMask = when {
                    dibHeaderSize >= 56 -> u32(maskBase + 12)
                    compression == BI_ALPHABITFIELDS && dibHeaderSize == 40 -> u32(maskBase + 12)
                    compression == BI_ALPHABITFIELDS && dibHeaderSize == 52 -> u32(14 + dibHeaderSize)
                    else -> 0L
                }
                val hasExternalAlpha = compression == BI_ALPHABITFIELDS && dibHeaderSize <= 52
                if (dibHeaderSize == 40) metadataEnd = maxOf(metadataEnd, maskBase + if (hasExternalAlpha) 16 else 12)
                if (dibHeaderSize == 52 && hasExternalAlpha) metadataEnd = maxOf(metadataEnd, 14 + dibHeaderSize + 4)
                validateMasks(redMask, greenMask, blueMask, alphaMask)
                channelMasks = ChannelMasks(redMask, greenMask, blueMask, alphaMask)
            }
            else -> throw IOException("BMP compression mode is not supported.")
        }
        if (pixelOffset < metadataEnd) {
            throw IOException("BMP pixel-data offset overlaps its channel masks.")
        }

        val palette = if (bitsPerPixel <= 8) {
            val maxEntries = 1 shl bitsPerPixel
            val entryCount = when {
                colorsUsed == 0L -> maxEntries
                colorsUsed > maxEntries.toLong() -> throw IOException("BMP palette contains too many entries.")
                else -> colorsUsed.toInt()
            }
            val paletteEntryBytes = if (coreHeader) 3 else 4
            val paletteStart = metadataEnd
            val paletteBytes = entryCount.toLong() * paletteEntryBytes.toLong()
            requireRange(paletteStart.toLong(), paletteBytes, "colour palette")
            if (paletteStart.toLong() + paletteBytes > pixelOffset.toLong()) {
                throw IOException("BMP colour palette overlaps pixel data.")
            }
            IntArray(entryCount) { index ->
                val position = paletteStart + index * paletteEntryBytes
                val blue = u8(position)
                val green = u8(position + 1)
                val red = u8(position + 2)
                0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
            }
        } else {
            IntArray(0)
        }

        val pixels = IntArray(pixelCountLong.toInt())
        if (compression == BI_RLE4 || compression == BI_RLE8) {
            val available = dataLimit - pixelOffset
            val compressedLength = when {
                imageSize == 0L -> available
                imageSize > available.toLong() -> throw IOException("BMP compressed pixel data is truncated.")
                imageSize > Int.MAX_VALUE.toLong() -> throw IOException("BMP compressed pixel data is too large.")
                else -> imageSize.toInt()
            }
            val indices = BmpRleDecoder.decode(
                ByteArrayInputStream(bytes, offset + pixelOffset, compressedLength),
                compressedLength.toLong(),
                width,
                height,
                bitsPerPixel,
                palette.size
            )
            for (index in indices.indices) {
                val paletteIndex = indices[index].toInt() and 0xFF
                if (paletteIndex !in palette.indices) {
                    throw IOException("BMP RLE data references a missing palette entry.")
                }
                pixels[index] = palette[paletteIndex]
            }
            return DecodedPixels(width, height, pixels)
        }

        val rowStrideLong = ((width.toLong() * bitsPerPixel.toLong() + 31L) / 32L) * 4L
        val pixelBytesLong = rowStrideLong * height.toLong()
        requireRange(pixelOffset.toLong(), pixelBytesLong, "pixel array")
        if (pixelOffset.toLong() + pixelBytesLong > dataLimit.toLong()) {
            throw IOException("BMP pixel array is truncated.")
        }
        if (rowStrideLong > Int.MAX_VALUE.toLong()) {
            throw IOException("BMP scanline exceeds the supported address range.")
        }
        val rowStride = rowStrideLong.toInt()

        for (y in 0 until height) {
            val fileY = if (topDown) y else height - 1 - y
            val rowStart = pixelOffset + fileY * rowStride
            for (x in 0 until width) {
                val color = when (bitsPerPixel) {
                    1 -> {
                        val packed = u8(rowStart + x / 8)
                        val paletteIndex = (packed ushr (7 - (x and 7))) and 1
                        paletteColor(palette, paletteIndex)
                    }
                    4 -> {
                        val packed = u8(rowStart + x / 2)
                        val paletteIndex = if (x and 1 == 0) packed ushr 4 else packed and 0x0F
                        paletteColor(palette, paletteIndex)
                    }
                    8 -> paletteColor(palette, u8(rowStart + x))
                    24 -> {
                        val position = rowStart + x * 3
                        val blue = u8(position)
                        val green = u8(position + 1)
                        val red = u8(position + 2)
                        0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                    }
                    32 -> {
                        val position = rowStart + x * 4
                        val pixel = u32(position)
                        if (channelMasks == null) {
                            val blue = (pixel and 0xFFL).toInt()
                            val green = ((pixel ushr 8) and 0xFFL).toInt()
                            val red = ((pixel ushr 16) and 0xFFL).toInt()
                            0xFF000000.toInt() or (red shl 16) or (green shl 8) or blue
                        } else {
                            val alpha = if (channelMasks.alpha == 0L) 255
                                else component(pixel, channelMasks.alpha)
                            (alpha shl 24) or
                                (component(pixel, channelMasks.red) shl 16) or
                                (component(pixel, channelMasks.green) shl 8) or
                                component(pixel, channelMasks.blue)
                        }
                    }
                    else -> throw IOException("BMP bit depth is not supported.")
                }
                pixels[y * width + x] = color
            }
        }
        return DecodedPixels(width, height, pixels)
    }

    private fun paletteColor(palette: IntArray, index: Int): Int {
        if (index !in palette.indices) {
            throw IOException("BMP pixel references a missing palette entry.")
        }
        return palette[index]
    }

    private fun validateMasks(red: Long, green: Long, blue: Long, alpha: Long) {
        val channels = listOf(red, green, blue, alpha).filter { it != 0L }
        if (red == 0L || green == 0L || blue == 0L ||
            channels.distinct().size != channels.size
        ) {
            throw IOException("BMP channel masks are missing or overlapping.")
        }
        channels.forEach { mask ->
            val shift = java.lang.Long.numberOfTrailingZeros(mask).toInt()
            val normalized = mask ushr shift
            if (normalized and (normalized + 1L) != 0L) {
                throw IOException("BMP channel mask is not a contiguous bit range.")
            }
        }
        if (red and green != 0L || red and blue != 0L || green and blue != 0L ||
            (alpha != 0L && (alpha and red != 0L || alpha and green != 0L || alpha and blue != 0L))
        ) {
            throw IOException("BMP channel masks overlap.")
        }
    }

    private fun component(pixel: Long, mask: Long): Int {
        val shift = java.lang.Long.numberOfTrailingZeros(mask).toInt()
        val maximum = mask ushr shift
        val value = (pixel and mask) ushr shift
        return ((value * 255L + maximum / 2L) / maximum).toInt()
    }

    private fun readByteArrayBmpLimit(offset: Int, length: Int): Unit = Unit

    private const val BI_RGB = 0L
    private const val BI_RLE8 = 1L
    private const val BI_RLE4 = 2L
    private const val BI_BITFIELDS = 3L
    private const val BI_ALPHABITFIELDS = 6L
    private const val MAX_DIMENSION = 8192
    private const val MAX_PIXELS = 16L * 1024L * 1024L
}
