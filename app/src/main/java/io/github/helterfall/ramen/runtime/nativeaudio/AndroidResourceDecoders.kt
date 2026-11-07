package io.github.helterfall.ramen.runtime.nativeaudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.helterfall.ramen.inspection.BmpRleDecoder
import io.github.helterfall.ramen.inspection.GpkImageFormat
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlySourceRangeInputStream
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import io.github.helterfall.ramen.runtime.systemnnn.SystemNnnDiagnosticLog
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
    private val maxDecodedPixels: Long = DEFAULT_MAX_DECODED_PIXELS,
    private val maxPackedBlockBytes: Long = DEFAULT_MAX_PACKED_BLOCK_BYTES
) {
    init {
        require(maxDecodedPixels > 0L)
        require(maxPackedBlockBytes > 0L)
    }

    @Throws(IOException::class)
    fun decode(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        resource.validationError?.let { message ->
            throw IOException("GPK resource is marked invalid: " + message)
        }
        SystemNnnDiagnosticLog.active?.record(
            "gpk_decode_begin",
            "resource=${resource.name} packType=${resource.packType} " +
                "blockOffset=${resource.blockOffset} blockBytes=${resource.blockSizeBytes} " +
                "payloadOffset=${resource.payloadOffset} payloadBytes=${resource.payloadSizeBytes} " +
                "size=${resource.widthPixels}x${resource.heightPixels} " +
                "bpp=${resource.bmpBitsPerPixel ?: "none"} compression=${resource.bmpCompression ?: "none"} " +
                "topDown=${resource.bmpTopDown ?: "none"} pixelOffset=${resource.bmpPixelDataOffsetBytes ?: "none"} " +
                "hasAlphaMask=${resource.hasAlpha} format=${resource.format}"
        )
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
            if (resource.packType == DWQ_PACKTYPE_7 && resource.hasAlpha) {
                val declaredPixels = resource.widthPixels.toLong() * resource.heightPixels.toLong()
                if (resource.widthPixels <= 0 || resource.heightPixels <= 0 ||
                    declaredPixels > maxDecodedPixels
                ) {
                    // PACKTYPE=7's trailing alpha plane is decoded at the original
                    // dimensions. Sampling only the JPEG would make the two planes
                    // disagree; do not allocate a partial bitmap or guess mask scaling.
                    throw IOException(
                        "DWQ JPEG alpha-mask image exceeds the configured decoded-pixel budget."
                    )
                }
            }
            val bitmap = decodeWithBitmapFactory(source, resource)
            return if (resource.packType == DWQ_PACKTYPE_7 && resource.hasAlpha) {
                applyDwqAlphaMask(source, resource, bitmap)
            } else {
                bitmap
            }
        }

        // Native CPicture::LoadDWQData consumes ordinary DWQ rows in file
        // order; unlike a standalone BMP, a positive biHeight is not flipped.
        // Android BitmapFactory normalizes that row order, so decode DWQ PACKTYPE=0
        // directly to preserve the original engine's orientation.
        if (resource.packType in setOf(DWQ_PACKTYPE_0, DWQ_PACKTYPE_2) &&
            resource.bmpCompression == BI_RGB &&
            resource.bmpBitsPerPixel?.let(DWQ_SEQUENTIAL_BITS_PER_PIXEL::contains) == true
        ) {
            return decodeSequentialDwqBmp(source, resource)
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

    /** Decodes a standalone WGQ image using the same pixel and packed-block budgets as indexed DWQ. */
    @Throws(IOException::class)
    fun decodeStandaloneWgq(file: File): Bitmap {
        SystemNnnDiagnosticLog.active?.record(
            "wgq_decode_begin",
            "resource=${file.name} bytes=${file.length()}"
        )
        val decoded = ResourceArchiveParsers.decodeBlackCycWgqFile(
            file,
            maxDecodedPixels,
            maxPackedBlockBytes
        )
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
        if (resource.blockSizeBytes > maxPackedBlockBytes) {
            throw IOException("Packed DWQ resource exceeds the configured block-size budget.")
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
    private fun decodeSequentialDwqBmp(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource
    ): Bitmap {
        val pixelOffset = resource.bmpPixelDataOffsetBytes
            ?: throw IOException("DWQ image has no validated pixel-data offset.")
        val bitsPerPixel = resource.bmpBitsPerPixel
            ?: throw IOException("DWQ image has no validated bit-depth metadata.")
        val bytesPerPixel = when (bitsPerPixel) {
            8 -> 1
            24 -> 3
            32 -> 4
            else -> throw IOException("Unsupported sequential DWQ bit depth: $bitsPerPixel.")
        }
        val rowBytes = resource.widthPixels.toLong() * bytesPerPixel.toLong()
        val rowStride = (rowBytes + 3L) and -4L
        val requiredPixelBytes = rowStride * resource.heightPixels.toLong()
        if (resource.widthPixels <= 0 || resource.heightPixels <= 0 ||
            rowStride <= 0L || rowStride > Int.MAX_VALUE.toLong() ||
            pixelOffset < 0L || pixelOffset > resource.payloadSizeBytes ||
            requiredPixelBytes > resource.payloadSizeBytes - pixelOffset
        ) {
            throw IOException("Sequential DWQ pixel rows exceed the indexed image payload.")
        }

        val palette = if (bitsPerPixel == 8) {
            val paletteOffset = resource.bmpPaletteOffsetBytes
                ?: throw IOException("Indexed DWQ image has no validated color table.")
            val paletteCount = resource.bmpPaletteColorCount
                ?: throw IOException("Indexed DWQ image has no validated color-table size.")
            val paletteBytesCount = paletteCount.toLong() * BMP_RGB_QUAD_BYTES
            if (paletteCount !in 1..256 ||
                paletteOffset < 0L || paletteOffset > resource.payloadSizeBytes ||
                paletteBytesCount > resource.payloadSizeBytes - paletteOffset
            ) {
                throw IOException("Sequential DWQ color table exceeds the indexed image payload.")
            }
            val paletteBytes = ByteArray(paletteBytesCount.toInt())
            source.readFullyAt(resource.payloadOffset + paletteOffset, paletteBytes)
            IntArray(paletteCount) { index ->
                val base = index * BMP_RGB_QUAD_BYTES_INT
                val blue = paletteBytes[base].toInt() and 0xFF
                val green = paletteBytes[base + 1].toInt() and 0xFF
                val red = paletteBytes[base + 2].toInt() and 0xFF
                (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
            }
        } else {
            IntArray(0)
        }

        val bitmap = try {
            Bitmap.createBitmap(
                resource.widthPixels,
                resource.heightPixels,
                Bitmap.Config.ARGB_8888
            )
        } catch (failure: RuntimeException) {
            throw IOException("Could not allocate the sequential DWQ bitmap.", failure)
        }
        val rawRow = ByteArray(rowStride.toInt())
        val argbRow = IntArray(resource.widthPixels)
        try {
            // Native CPicture::LoadDWQData writes rows into the destination in
            // file order. Unlike a normal BMP decoder, it does not reverse them.
            for (y in 0 until resource.heightPixels) {
                source.readFullyAt(
                    resource.payloadOffset + pixelOffset + y.toLong() * rowStride,
                    rawRow
                )
                for (x in 0 until resource.widthPixels) {
                    val sourceIndex = x * bytesPerPixel
                    argbRow[x] = if (bitsPerPixel == 8) {
                        val paletteIndex = rawRow[sourceIndex].toInt() and 0xFF
                        palette.getOrElse(paletteIndex) {
                            throw IOException("DWQ pixel refers to an absent palette entry.")
                        }
                    } else {
                        // Ordinary DWQ scanlines store RGB(X), not BMP's BGR(X).
                        val red = rawRow[sourceIndex].toInt() and 0xFF
                        val green = rawRow[sourceIndex + 1].toInt() and 0xFF
                        val blue = rawRow[sourceIndex + 2].toInt() and 0xFF
                        (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                    }
                }
                bitmap.setPixels(argbRow, 0, resource.widthPixels, 0, y, resource.widthPixels, 1)
            }
        } catch (failure: Exception) {
            bitmap.recycle()
            if (failure is IOException) throw failure
            throw IOException("Could not read sequential DWQ pixel rows.", failure)
        }

        return if (resource.hasAlpha) {
            try {
                applyDwqAlphaMask(source, resource, bitmap)
            } catch (failure: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                if (failure is IOException) throw failure
                throw IOException("Could not apply the DWQ alpha mask.", failure)
            }
        } else {
            bitmap
        }
    }

    private fun applyDwqAlphaMask(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource,
        sourceBitmap: Bitmap
    ): Bitmap {
        val maskOffset = resource.payloadOffset + resource.payloadSizeBytes
        val blockEnd = resource.blockOffset + resource.blockSizeBytes
        if (resource.payloadSizeBytes < 0L || maskOffset < resource.payloadOffset ||
            resource.blockSizeBytes <= 0L || blockEnd < resource.blockOffset ||
            maskOffset > blockEnd || blockEnd > source.sizeBytes
        ) {
            throw IOException("DWQ alpha mask range is outside the indexed resource block.")
        }
        val maskSize = blockEnd - maskOffset
        if (maskSize <= 0L || maskSize > maxPackedBlockBytes || maskSize > Int.MAX_VALUE.toLong()) {
            throw IOException("DWQ alpha mask is missing or exceeds the configured block-size budget.")
        }

        val maskBytes = ByteArray(maskSize.toInt())
        source.readFullyAt(maskOffset, maskBytes)
        val alphaValues = ResourceArchiveParsers.decodeBlackCycDwqAlphaMask(
            maskBytes,
            resource.widthPixels,
            resource.heightPixels,
            maxDecodedPixels
        )

        val bitmap = if (sourceBitmap.isMutable) {
            sourceBitmap
        } else {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IOException("Could not create a mutable bitmap for the DWQ alpha mask.")
        }
        try {
            val pixels = IntArray(resource.widthPixels * resource.heightPixels)
            bitmap.getPixels(
                pixels,
                0,
                resource.widthPixels,
                0,
                0,
                resource.widthPixels,
                resource.heightPixels
            )
            for (index in pixels.indices) {
                pixels[index] = (pixels[index] and 0x00FFFFFF) or (alphaValues[index] shl 24)
            }
            bitmap.setPixels(
                pixels,
                0,
                resource.widthPixels,
                0,
                0,
                resource.widthPixels,
                resource.heightPixels
            )
        } catch (failure: Exception) {
            if (bitmap !== sourceBitmap && !bitmap.isRecycled) bitmap.recycle()
            throw IOException("Could not apply DWQ alpha values to the decoded bitmap.", failure)
        }
        if (bitmap !== sourceBitmap && !sourceBitmap.isRecycled) sourceBitmap.recycle()
        return bitmap
    }

    private fun decodeRawBmpPixels(
        source: ReadOnlyRandomAccessSource,
        resource: GpkImageResource,
        preserveStoredRowOrder: Boolean = false
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
                val storedY = if (preserveStoredRowOrder || topDown) {
                    destinationY
                } else {
                    height - 1 - destinationY
                }
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
        // Match the standalone WGQ hard limit; check before copying an indexed GPK block.
        private const val DEFAULT_MAX_PACKED_BLOCK_BYTES = 128L * 1024L * 1024L
        private const val DWQ_PACKTYPE_0 = 0
        private const val DWQ_PACKTYPE_1 = 1
        private const val DWQ_PACKTYPE_2 = 2
        private const val DWQ_PACKTYPE_3 = 3
        private const val DWQ_PACKTYPE_7 = 7
        private val DWQ_SEQUENTIAL_BITS_PER_PIXEL = setOf(8, 24, 32)
        private const val BI_RGB = 0L
        private const val BI_RLE8 = 1L
        private const val BI_RLE4 = 2L
        private const val BMP_RGB_QUAD_BYTES = 4L
        private const val BMP_RGB_QUAD_BYTES_INT = 4
        private val RAW_BITS_PER_PIXEL = setOf(24, 32)
    }
}
