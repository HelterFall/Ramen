package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchive
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Reads a paired GTB/GPK image catalog and turns an indexed resource into an
 * Android Bitmap. Lookup accepts a GTB resource name, decimal entry index,
 * hexadecimal/decimal GPK block offset, or a 0x-prefixed block offset.
 *
 * The repository owns both random-access sources and must be closed by its
 * ViewModel/session owner.
 */
class SystemNnnGpkBitmapRepository(
    indexFile: File,
    archiveFile: File,
    private val maxDecodedEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES
) : SystemNnnSceneAssetResolver {

    private val indexSource = FileRandomAccessSource(indexFile)
    private val archiveSource = FileRandomAccessSource(archiveFile)

    val archive: GpkGtbArchive

    init {
        require(maxDecodedEntryBytes > 0L)
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

        if (entry.dwqPackedBmp && entry.packType in PACKED_BMP_TYPES) {
            val block = readRange(entry.blockOffset, entry.blockSizeBytes)
            val decoded = ResourceArchiveParsers.decodeBlackCycDwqPackedBmp(block)
            return Bitmap.createBitmap(
                decoded.argbPixels,
                decoded.width,
                decoded.height,
                Bitmap.Config.ARGB_8888
            )
        }

        val payload = readRange(entry.payloadOffset, entry.payloadSizeBytes)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inDither = false
        }
        BitmapFactory.decodeByteArray(payload, 0, payload.size, options)?.let {
            return it
        }

        // Some GPK BMP resources contain a DIB without the 14-byte file header.
        if (entry.format.name == "BMP" && !startsWithBitmapFileHeader(payload)) {
            val wrapped = addBitmapFileHeader(payload, entry.bmpPixelDataOffsetBytes)
            BitmapFactory.decodeByteArray(wrapped, 0, wrapped.size, options)?.let {
                return it
            }
        }

        throw IOException(
            "Android could not decode GPK resource '" + entry.name + "' " +
                "(format=" + entry.format + ", PACKTYPE=" + entry.packType + ")."
        )
    }

    private fun readRange(offset: Long, length: Long): ByteArray {
        if (offset < 0L || length <= 0L || length > maxDecodedEntryBytes ||
            length > Int.MAX_VALUE.toLong() ||
            offset > archiveSource.sizeBytes ||
            length > archiveSource.sizeBytes - offset
        ) {
            throw IOException("GPK resource range exceeds the configured decoding bounds.")
        }
        return ByteArray(length.toInt()).also { bytes ->
            archiveSource.readFullyAt(offset, bytes)
        }
    }

    private fun startsWithBitmapFileHeader(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte()

    private fun addBitmapFileHeader(dib: ByteArray, pixelOffsetInDib: Long?): ByteArray {
        if (dib.size < 40 || dib.size > Int.MAX_VALUE - BITMAP_FILE_HEADER_BYTES) {
            throw IOException("DIB payload is outside the supported BMP range.")
        }
        val dibHeaderSize = readU32Le(dib, 0)
        val pixelOffset = pixelOffsetInDib?.takeIf { it >= dibHeaderSize }
            ?: dibHeaderSize
        if (pixelOffset > dib.size.toLong()) {
            throw IOException("DIB pixel offset exceeds its resource.")
        }

        val result = ByteArray(BITMAP_FILE_HEADER_BYTES + dib.size)
        result[0] = 'B'.code.toByte()
        result[1] = 'M'.code.toByte()
        writeU32Le(result, 2, result.size.toLong())
        writeU32Le(result, 10, pixelOffset + BITMAP_FILE_HEADER_BYTES)
        dib.copyInto(result, BITMAP_FILE_HEADER_BYTES)
        return result
    }

    private fun readU32Le(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun writeU32Le(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    override fun close() {
        runCatching { indexSource.close() }
        runCatching { archiveSource.close() }
    }

    companion object {
        private const val BITMAP_FILE_HEADER_BYTES = 14
        private const val DEFAULT_MAX_ENTRY_BYTES = 96L * 1024L * 1024L
        private val PACKED_BMP_TYPES = setOf(1, 3)
    }
}
