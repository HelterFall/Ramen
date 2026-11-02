package io.github.helterfall.ramen.runtime.nativeaudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.helterfall.ramen.inspection.GpkImageFormat
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlySourceRangeInputStream
import java.io.IOException

/**
 * Decodes a parsed GPK image payload using Android's BitmapFactory.
 *
 * Bounds are checked in a first pass and inSampleSize is selected before allocation,
 * limiting BitmapFactory output to [maxDecodedPixels]. BMP payloads also retain
 * validated BITMAPINFOHEADER metadata; when Android cannot decode the file header,
 * a bounded BI_RGB 24/32-bit raw-pixel fallback reads only the indexed payload range.
 */
class AndroidGpkImageDecoder(
    private val maxDecodedPixels: Long = DEFAULT_MAX_DECODED_PIXELS
) {
    init {
        require(maxDecodedPixels > 0L)
    }

    @Throws(IOException::class)
    fun decode(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        if (resource.payloadSizeBytes <= 0L ||
            resource.payloadOffset < 0L ||
            resource.payloadOffset > source.sizeBytes ||
            resource.payloadSizeBytes > source.sizeBytes - resource.payloadOffset
        ) {
            throw IOException("Image resource range is outside the source.")
        }
        if (resource.format != GpkImageFormat.JPEG && resource.format != GpkImageFormat.BMP) {
            throw IOException("Unsupported GPK image format.")
        }

        if (resource.format != GpkImageFormat.BMP) {
            return decodeWithBitmapFactory(source, resource)
        }

        val bitmapFactoryFailure = try {
            return decodeWithBitmapFactory(source, resource)
        } catch (failure: IOException) {
            failure
        } catch (failure: RuntimeException) {
            IOException("Android BitmapFactory rejected the BMP payload.", failure)
        }

        return try {
            decodeRawBmpPixels(source, resource)
        } catch (failure: IOException) {
            failure.addSuppressed(bitmapFactoryFailure)
            throw failure
        } catch (failure: RuntimeException) {
            val wrapped = IOException("The validated raw BMP fallback could not create a bitmap.", failure)
            wrapped.addSuppressed(bitmapFactoryFailure)
            throw wrapped
        }
    }

    private fun decodeWithBitmapFactory(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        ReadOnlySourceRangeInputStream(
            source,
            resource.payloadOffset,
            resource.payloadSizeBytes
        ).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Android could not read the image dimensions.")
        }
        if (bounds.outWidth != resource.widthPixels ||
            bounds.outHeight != resource.heightPixels
        ) {
            throw IOException("Image dimensions disagree with the parsed GPK header.")
        }

        var sampleSize = 1
        while (pixelCountAtSample(bounds.outWidth, bounds.outHeight, sampleSize) > maxDecodedPixels) {
            if (sampleSize > Int.MAX_VALUE / 2) {
                throw IOException("Image dimensions exceed the decoder limit.")
            }
            sampleSize *= 2
        }

        while (true) {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = ReadOnlySourceRangeInputStream(
                source,
                resource.payloadOffset,
                resource.payloadSizeBytes
            ).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw IOException("Android could not decode the GPK image payload.")

            val decodedPixels = bitmap.width.toLong() * bitmap.height.toLong()
            if (decodedPixels <= maxDecodedPixels) return bitmap

            bitmap.recycle()
            if (sampleSize > Int.MAX_VALUE / 2) {
                throw IOException("Decoded image exceeds the configured pixel limit.")
            }
            sampleSize *= 2
        }
    }

    /**
     * Decodes only uncompressed 24-bit BGR and 32-bit BGRX rows whose DIB layout
     * was validated by GpkGtbArchiveParser. Palette-based and compressed variants
     * are deliberately not guessed here.
     */
    private fun decodeRawBmpPixels(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        val pixelDataOffset = resource.bmpPixelDataOffsetBytes
            ?: throw IOException("BMP has no validated raw pixel-data offset.")
        val bitsPerPixel = resource.bmpBitsPerPixel
            ?: throw IOException("BMP has no validated bit-depth metadata.")
        val compression = resource.bmpCompression
            ?: throw IOException("BMP has no validated compression metadata.")
        val topDown = resource.bmpTopDown
            ?: throw IOException("BMP has no validated row-order metadata.")

        if (compression != BI_RGB || bitsPerPixel !in RAW_BITS_PER_PIXEL) {
            throw IOException(
                "Raw BMP fallback supports only uncompressed 24-bit BGR and 32-bit BGRX data."
            )
        }
        if (pixelDataOffset < 0L || pixelDataOffset > resource.payloadSizeBytes) {
            throw IOException("BMP pixel-data offset is outside the indexed payload.")
        }

        val width = resource.widthPixels
        val height = resource.heightPixels
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixelCount > maxDecodedPixels) {
            throw IOException("Raw BMP dimensions exceed the configured pixel limit.")
        }

        val rowBits = width.toLong() * bitsPerPixel.toLong()
        val rowStride = ((rowBits + 31L) / 32L) * 4L
        val requiredPixelBytes = rowStride * height.toLong()
        if (requiredPixelBytes > resource.payloadSizeBytes - pixelDataOffset ||
            rowStride > Int.MAX_VALUE
        ) {
            throw IOException("Raw BMP pixel rows extend beyond the indexed payload.")
        }

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the bounded raw BMP bitmap.", failure)
        }
        val rawRow = ByteArray(rowStride.toInt())
        val argbRow = IntArray(width)
        val bytesPerPixel = bitsPerPixel / 8

        try {
            for (destinationY in 0 until height) {
                val storedY = if (topDown) destinationY else height - 1 - destinationY
                val rowOffset = resource.payloadOffset + pixelDataOffset +
                    storedY.toLong() * rowStride
                source.readFullyAt(rowOffset, rawRow)

                for (x in 0 until width) {
                    val sourcePixelOffset = x * bytesPerPixel
                    val blue = rawRow[sourcePixelOffset].toInt() and 0xFF
                    val green = rawRow[sourcePixelOffset + 1].toInt() and 0xFF
                    val red = rawRow[sourcePixelOffset + 2].toInt() and 0xFF
                    // The fourth BI_RGB byte is reserved (BGRX), not reliable alpha.
                    argbRow[x] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                }
                bitmap.setPixels(argbRow, 0, width, 0, destinationY, width, 1)
            }
        } catch (failure: Exception) {
            bitmap.recycle()
            if (failure is IOException) throw failure
            throw IOException("Could not read raw BMP pixel rows.", failure)
        }

        return bitmap
    }

    private fun pixelCountAtSample(width: Int, height: Int, sampleSize: Int): Long {
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        return sampledWidth * sampledHeight
    }

    companion object {
        private const val DEFAULT_MAX_DECODED_PIXELS = 8_000_000L
        private const val BI_RGB = 0L
        private val RAW_BITS_PER_PIXEL = setOf(24, 32)
    }
}
