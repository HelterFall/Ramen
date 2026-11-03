package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchive
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Resolves the parsed GTB catalog to fully decoded GPK images.
 *
 * The decoder streams ordinary JPEG/PNG/BMP payloads from the archive, has
 * bounded BI_RGB and BI_RLE4/BI_RLE8 BMP fallbacks, and handles Black Cyc's
 * packed DWQ BMP variants. No bitmap cache is kept: the current scene owns
 * each returned bitmap until Compose disposes the corresponding Image node.
 */
class SystemNnnGpkBitmapRepository(
    indexFile: File,
    archiveFile: File,
    private val imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
) : SystemNnnSceneAssetResolver {

    private val indexSource = FileRandomAccessSource(indexFile)
    private val archiveSource = FileRandomAccessSource(archiveFile)

    val archive: GpkGtbArchive

    init {
        archive = try {
            GpkGtbArchiveParser().parse(indexSource, archiveSource)
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    val entries: List<GpkImageResource>
        get() = archive.entries

    override fun loadBitmap(resourceId: String): Bitmap? {
        val entry = findEntry(resourceId) ?: return null
        return decode(entry)
    }

    fun loadBitmap(index: Int): Bitmap? {
        val entry = archive.entries.getOrNull(index) ?: return null
        return decode(entry)
    }

    fun loadBitmapAtBlockOffset(blockOffset: Long): Bitmap? {
        val entry = archive.entries.firstOrNull { it.blockOffset == blockOffset } ?: return null
        return decode(entry)
    }

    private fun findEntry(resourceId: String): GpkImageResource? {
        archive.entries.firstOrNull { it.name.equals(resourceId, ignoreCase = true) }?.let {
            return it
        }

        val numericValue = parseOffset(resourceId) ?: return null
        if (numericValue >= 0L && numericValue < archive.entries.size.toLong()) {
            return archive.entries[numericValue.toInt()]
        }
        return archive.entries.firstOrNull { it.blockOffset == numericValue }
    }

    private fun parseOffset(value: String): Long? {
        val trimmed = value.trim()
        return when {
            trimmed.startsWith("0x", ignoreCase = true) ->
                trimmed.substring(2).toLongOrNull(16)
            else -> trimmed.toLongOrNull()
        }
    }

    private fun decode(entry: GpkImageResource): Bitmap {
        if (!entry.isValid) {
            throw IOException(
                "GPK resource '" + entry.name + "' is invalid: " +
                    (entry.validationError ?: "unknown validation error")
            )
        }
        return imageDecoder.decode(archiveSource, entry)
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        runCatching { indexSource.close() }
        runCatching { archiveSource.close() }
    }
}
