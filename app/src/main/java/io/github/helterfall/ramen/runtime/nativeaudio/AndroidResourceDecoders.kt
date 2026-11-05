package io.github.helterfall.ramen.runtime.nativeaudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.helterfall.ramen.inspection.BmpRleDecoder
import io.github.helterfall.ramen.inspection.GpkImageFormat
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlySourceRangeInputStream
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import java.io.File
import java.io.IOException

/**
 * Decodes a parsed GPK image payload using Android's BitmapFactory.
 *
 * Bounds are checked in a first pass and inSampleSize is selected before allocation,
 * limiting BitmapFactory output to [maxDecodedPixels]. BMP payloads also retain
 * validated BITMAPINFOHEADER metadata; when Android cannot decode the file, a
 * bounded BI_RGB or BI_RLE4/BI_RLE8 pixel fallback reads only the indexed payload range.
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
        resource.validationError?.let { message ->
            throw IOException("GPK resource is marked invalid: " + message)
        }
        // PACKTYPE is the authoritative discriminator. Do not let a missing parser
        // convenience flag route packed rows through BitmapFactory.
        if (resource.packType == DWQ_PACKTYPE_1 || resource.packType == DWQ_PACKTYPE_3) {
            return decodePackedDwqBmp(source, resource)
        }
        if (resource.dwqPackedBmp) {
            throw IOException(
                "Packed DWQ metadata is set for unsupported PACKTYPE=" + resource.packType + "."
            )
        }
        if (resource.payloadSizeBytes <= 0L ||
            resource.payloadOffset < 0L ||
            resource.payloadOffset > source.sizeBytes ||
            resource.payloadSizeBytes > source.sizeBytes - resource.payloadOffset
        ) {
            throw IOException("Image resource range is outside the source.")
        }
        if (resource.format !in setOf(
                GpkImageFormat.JPEG,
                GpkImageFormat.BMP,
                GpkImageFormat.PNG
            )
        ) {
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

    /** Decodes a standalone WGQ image while applying the same pixel ceiling as indexed DWQ. */
    @Throws(IOException::class)
    fun decodeStandaloneWgq(file: File): Bitmap {
        val decoded = ResourceArchiveParsers.decodeBlackCycWgqFile(file, maxDecodedPixels)
        val bitmap = try {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the decoded WGQ bitmap.", failure)
        }
        try {
            bitmap.setPixels(decoded.argbPixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        } catch (failure: Exception) {
            bitmap.recycle()
            throw IOException("Could not copy decoded WGQ pixels into the bitmap.", failure)
        }
        return bitmap
    }

    private fun decodePackedDwqBmp(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        val declaredPixels = resource.widthPixels.toLong() * resource.heightPixels.toLong()
        if (resource.widthPixels <= 0 || resource.heightPixels <= 0 ||
            declaredPixels > maxDecodedPixels
        ) {
            throw IOException("Packed DWQ image exceeds the configured decoded-pixel budget.")
        }
        if (resource.blockSizeBytes <= 0L ||
            resource.blockSizeBytes > Int.MAX_VALUE.toLong() ||
            resource.blockOffset < 0L ||
            resource.blockOffset > source.sizeBytes ||
            resource.blockSizeBytes > source.sizeBytes - resource.blockOffset
        ) {
            throw IOException("Packed DWQ resource range exceeds the supported JVM buffer range.")
        }

        val entryBytes = ByteArray(resource.blockSizeBytes.toInt())
        source.readFullyAt(resource.blockOffset, entryBytes)
        val decoded = ResourceArchiveParsers.decodeBlackCycDwqPackedBmp(entryBytes, maxDecodedPixels)
        if (decoded.packType != resource.packType ||
            decoded.width != resource.widthPixels ||
            decoded.height != resource.heightPixels
        ) {
            throw IOException("Decoded DWQ metadata disagrees with the indexed resource.")
        }

        val bitmap = try {
            Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the decoded DWQ bitmap.", failure)
        }
        try {
            bitmap.setPixels(
                decoded.argbPixels,
                0,
                decoded.width,
                0,
                0,
                decoded.width,
                decoded.height
            )
        } catch (failure: Exception) {
            bitmap.recycle()
            if (failure is IOException) throw failure
            throw IOException("Could not copy decoded DWQ pixels into the bitmap.", failure)
        }
        return bitmap
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
     * Decodes the supported BMP layouts whose DIB metadata was validated by
     * GpkGtbArchiveParser: uncompressed 24/32-bit BGR(X), or indexed RLE4/RLE8.
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

        if (pixelDataOffset < 0L || pixelDataOffset > resource.payloadSizeBytes) {
            throw IOException("BMP pixel-data offset is outside the indexed payload.")
        }

        val width = resource.widthPixels
        val height = resource.heightPixels
        val pixelCount = width.toLong() * height.toLong()
        if (width <= 0 || height <= 0 || pixelCount > maxDecodedPixels) {
            throw IOException("Raw BMP dimensions exceed the configured pixel limit.")
        }

        if (compression == BI_RLE8 || compression == BI_RLE4) {
            return decodeRleBmpPixels(source, resource, pixelDataOffset, bitsPerPixel)
        }
        if (compression != BI_RGB || bitsPerPixel !in RAW_BITS_PER_PIXEL) {
            throw IOException(
                "Raw BMP fallback supports BI_RGB 24/32-bit or BI_RLE4/BI_RLE8 indexed data."
            )
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

    private fun decodeRleBmpPixels(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource,
        pixelDataOffset: Long,
        bitsPerPixel: Int
    ): Bitmap {
        val expectedBitsPerPixel = when (resource.bmpCompression) {
            BI_RLE8 -> 8
            BI_RLE4 -> 4
            else -> throw IOException("Unsupported BMP RLE compression mode.")
        }
        if (bitsPerPixel != expectedBitsPerPixel) {
            throw IOException("BMP RLE compression does not match its bit depth.")
        }

        val paletteOffset = resource.bmpPaletteOffsetBytes
            ?: throw IOException("BMP RLE image has no validated palette offset.")
        val paletteEntries = resource.bmpPaletteColorCount
            ?: throw IOException("BMP RLE image has no validated palette size.")
        if (paletteEntries !in 1..(1 shl bitsPerPixel)) {
            throw IOException("BMP RLE palette size is outside the bit-depth range.")
        }
        val paletteByteCount = paletteEntries.toLong() * BMP_RGB_QUAD_BYTES
        if (paletteOffset < 0L || paletteOffset > resource.payloadSizeBytes ||
            paletteByteCount > resource.payloadSizeBytes - paletteOffset
        ) {
            throw IOException("BMP RLE color table extends beyond the indexed payload.")
        }

        val paletteBytes = ByteArray(paletteByteCount.toInt())
        source.readFullyAt(resource.payloadOffset + paletteOffset, paletteBytes)
        val palette = IntArray(paletteEntries) { index ->
            val base = index * BMP_RGB_QUAD_BYTES_INT
            val blue = paletteBytes[base].toInt() and 0xFF
            val green = paletteBytes[base + 1].toInt() and 0xFF
            val red = paletteBytes[base + 2].toInt() and 0xFF
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }

        val compressedLength = resource.payloadSizeBytes - pixelDataOffset
        val indices = ReadOnlySourceRangeInputStream(
            source,
            resource.payloadOffset + pixelDataOffset,
            compressedLength
        ).use { input ->
            BmpRleDecoder.decode(
                input = input,
                encodedLengthBytes = compressedLength,
                width = resource.widthPixels,
                height = resource.heightPixels,
                bitsPerPixel = bitsPerPixel,
                paletteEntries = paletteEntries
            )
        }

        val bitmap = try {
            Bitmap.createBitmap(
                resource.widthPixels,
                resource.heightPixels,
                Bitmap.Config.ARGB_8888
            )
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the bounded BMP RLE bitmap.", failure)
        }
        val argbRow = IntArray(resource.widthPixels)
        try {
            for (y in 0 until resource.heightPixels) {
                val rowOffset = y * resource.widthPixels
                for (x in 0 until resource.widthPixels) {
                    val paletteIndex = indices[rowOffset + x].toInt() and 0xFF
                    argbRow[x] = palette.getOrElse(paletteIndex) {
                        throw IOException("BMP RLE pixel refers to an absent palette entry.")
                    }
                }
                bitmap.setPixels(
                    argbRow,
                    0,
                    resource.widthPixels,
                    0,
                    y,
                    resource.widthPixels,
                    1
                )
            }
        } catch (failure: Exception) {
            bitmap.recycle()
            if (failure is IOException) throw failure
            throw IOException("Could not materialize BMP RLE pixel rows.", failure)
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
        private const val DWQ_PACKTYPE_1 = 1
        private const val DWQ_PACKTYPE_3 = 3
        private const val BI_RGB = 0L
        private const val BI_RLE8 = 1L
        private const val BI_RLE4 = 2L
        private const val BMP_RGB_QUAD_BYTES = 4L
        private const val BMP_RGB_QUAD_BYTES_INT = 4
        private val RAW_BITS_PER_PIXEL = setOf(24, 32)
    }
}
