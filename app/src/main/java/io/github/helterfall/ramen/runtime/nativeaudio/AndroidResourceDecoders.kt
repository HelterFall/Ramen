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
 * limiting the decoded bitmap to [maxDecodedPixels]. This is a display-oriented
 * thumbnail/full-view adapter, not a byte-for-byte image extractor.
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

    private fun pixelCountAtSample(width: Int, height: Int, sampleSize: Int): Long {
        val sampledWidth = (width.toLong() + sampleSize - 1L) / sampleSize
        val sampledHeight = (height.toLong() + sampleSize - 1L) / sampleSize
        return sampledWidth * sampledHeight
    }

    companion object {
        private const val DEFAULT_MAX_DECODED_PIXELS = 8_000_000L
    }
}
