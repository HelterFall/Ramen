package io.github.helterfall.ramen.runtime.systemnnn

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchive
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.inspection.ResourceArchiveParsers
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.File
import java.io.IOException

data class SystemNnnImageResourceInventoryEntry(
    val bankId: String,
    val rowIndex: Int,
    val resourceName: String,
    val format: String,
    val blockOffsetBytes: Long?,
    val blockSizeBytes: Long,
    val payloadOffsetBytes: Long?,
    val payloadSizeBytes: Long,
    val widthPixels: Int?,
    val heightPixels: Int?,
    val packType: Int?,
    val hasAlphaMask: Boolean?,
    val isValid: Boolean,
    val validationError: String? = null
)

/**
 * Resolves indexed GPK images or Black Cyc WGQ/DWQ resources.
 *
 * A root-level "wgq" folder has priority over GPK. When a matching GTB + WGQ
 * archive exists, the regular index parser is used. Otherwise each .wgq file
 * is treated as one complete DWQ resource and assigned a stable sorted index.
 */
class SystemNnnGpkBitmapRepository private constructor(
    indexFile: File?,
    archiveFile: File?,
    private val wgqFiles: List<File>,
    private val imageDecoder: AndroidGpkImageDecoder
) : SystemNnnSceneAssetResolver {

    constructor(
        indexFile: File,
        archiveFile: File,
        imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
    ) : this(indexFile, archiveFile, emptyList(), imageDecoder)

    constructor(
        wgqFiles: List<File>,
        imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
    ) : this(null, null, wgqFiles, imageDecoder)

    private val indexSource = indexFile?.let(::FileRandomAccessSource)
    private val archiveSource = archiveFile?.let(::FileRandomAccessSource)

    val archive: GpkGtbArchive
    val entries: List<GpkImageResource>
        get() = archive.entries

    /** Number of image rows addressed by the transition table. */
    val resourceCount: Int
        get() = if (wgqFiles.isNotEmpty()) wgqFiles.size else archive.entries.size

    init {
        require(wgqFiles.isNotEmpty() || (indexSource != null && archiveSource != null)) {
            "Either a GTB/GPK pair or at least one WGQ resource is required."
        }
        archive = try {
            if (indexSource != null && archiveSource != null) {
                GpkGtbArchiveParser().parse(indexSource, archiveSource)
            } else {
                GpkGtbArchive(emptyList())
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    override fun loadBitmap(resourceId: String): Bitmap? {
        if (wgqFiles.isNotEmpty()) {
            val namedIndex = wgqFiles.indexOfFirst { file ->
                file.name.equals(resourceId, ignoreCase = true) ||
                    file.nameWithoutExtension.equals(resourceId, ignoreCase = true)
            }
            val index = if (namedIndex >= 0) {
                namedIndex
            } else {
                parseOffset(resourceId)
                    ?.takeIf { it >= 0L && it < wgqFiles.size.toLong() }
                    ?.toInt()
            }
            return index?.let(::decodeWgq)
        }
        return findEntry(resourceId)?.let(::decode)
    }

    override fun loadBitmapByIndex(index: Long): Bitmap? {
        if (wgqFiles.isNotEmpty()) {
            if (index < 0L || index >= wgqFiles.size.toLong()) return null
            return decodeWgq(index.toInt())
        }
        if (index < 0L || index >= archive.entries.size.toLong()) return null
        return decode(archive.entries[index.toInt()])
    }

    fun loadBitmap(index: Int): Bitmap? = loadBitmapByIndex(index.toLong())

    /**
     * Returns structural index metadata only. No image payload is decoded or copied.
     * Standalone WGQ rows expose their filename and size; indexed GTB/GPK rows
     * expose the validated dimensions, packing type, and byte ranges.
     */
    fun inventory(bankId: String): List<SystemNnnImageResourceInventoryEntry> {
        if (wgqFiles.isNotEmpty()) {
            return wgqFiles.mapIndexed { index, file ->
                val size = file.length().coerceAtLeast(0L)
                SystemNnnImageResourceInventoryEntry(
                    bankId = bankId,
                    rowIndex = index,
                    resourceName = file.name,
                    format = "STANDALONE_WGQ",
                    blockOffsetBytes = null,
                    blockSizeBytes = size,
                    payloadOffsetBytes = null,
                    payloadSizeBytes = size,
                    widthPixels = null,
                    heightPixels = null,
                    packType = null,
                    hasAlphaMask = null,
                    isValid = true
                )
            }
        }
        return archive.entries.mapIndexed { index, entry ->
            SystemNnnImageResourceInventoryEntry(
                bankId = bankId,
                rowIndex = index,
                resourceName = entry.name,
                format = entry.format.name,
                blockOffsetBytes = entry.blockOffset,
                blockSizeBytes = entry.blockSizeBytes,
                payloadOffsetBytes = entry.payloadOffset,
                payloadSizeBytes = entry.payloadSizeBytes,
                widthPixels = entry.widthPixels.takeIf { it > 0 },
                heightPixels = entry.heightPixels.takeIf { it > 0 },
                packType = entry.packType.takeIf { it >= 0 },
                hasAlphaMask = entry.hasAlpha,
                isValid = entry.isValid,
                validationError = entry.validationError
            )
        }
    }

    /** Exact, case-insensitive name lookup used by the project-level asset catalog. */
    fun hasResourceName(resourceId: String): Boolean {
        if (wgqFiles.isNotEmpty()) {
            return wgqFiles.any { file ->
                file.name.equals(resourceId, ignoreCase = true) ||
                    file.nameWithoutExtension.equals(resourceId, ignoreCase = true)
            }
        }
        return archive.entries.any { it.name.equals(resourceId, ignoreCase = true) }
    }

    fun loadBitmapAtBlockOffset(blockOffset: Long): Bitmap? {
        if (archiveSource == null) return null
        val entry = archive.entries.firstOrNull { it.blockOffset == blockOffset } ?: return null
        return decode(entry)
    }

    private fun decodeWgq(index: Int): Bitmap {
        val file = wgqFiles.getOrNull(index)
            ?: throw IOException("WGQ image index is outside the resource catalog.")
        return imageDecoder.decodeStandaloneWgq(file)
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

    /** Returns validated DWQ metadata for the resource token without decoding pixels. */
    override fun imageMetadata(resourceId: String): SystemNnnImageResourceMetadata? {
        if (wgqFiles.isNotEmpty()) return null
        val entry = findEntry(resourceId) ?: return null
        return SystemNnnImageResourceMetadata(
            resourceName = entry.name,
            widthPixels = entry.widthPixels,
            heightPixels = entry.heightPixels,
            packType = entry.packType.takeIf { it >= 0 },
            format = entry.format.name,
            hasAlphaMask = entry.hasAlpha,
            blockOffset = entry.blockOffset
        )
    }


    override fun imageMetadataByIndex(index: Long): SystemNnnImageResourceMetadata? {
        if (wgqFiles.isNotEmpty() || index < 0L || index >= archive.entries.size.toLong()) return null
        val entry = archive.entries[index.toInt()]
        return SystemNnnImageResourceMetadata(
            resourceName = entry.name,
            widthPixels = entry.widthPixels,
            heightPixels = entry.heightPixels,
            packType = entry.packType.takeIf { it >= 0 },
            format = entry.format.name,
            hasAlphaMask = entry.hasAlpha,
            blockOffset = entry.blockOffset
        )
    }

    private fun decode(entry: GpkImageResource): Bitmap {
        if (!entry.isValid) {
            throw IOException(
                "GPK resource '" + entry.name + "' is invalid: " +
                    (entry.validationError ?: "unknown validation error")
            )
        }
        val source = archiveSource ?: throw IOException("The GPK archive is not open.")
        // PACKTYPE=1/3 dispatches through AndroidGpkImageDecoder to
        // ResourceArchiveParsers.decodeBlackCycDwqPackedBmp.
        return imageDecoder.decode(source, entry)
    }

    override fun releaseBitmap(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        runCatching { indexSource?.close() }
        runCatching { archiveSource?.close() }
    }

    companion object {
        @Throws(IOException::class)
        fun forGameDirectory(
            gameDirectory: File,
            imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
        ): SystemNnnGpkBitmapRepository {
            if (!gameDirectory.isDirectory) {
                throw IOException("The imported game directory is unavailable.")
            }

            val wgqDirectory = File(gameDirectory, "wgq")
            if (wgqDirectory.exists()) {
                val discoveredWgqFiles =
                    ResourceArchiveParsers.findBlackCycWgqFiles(wgqDirectory)

                // Preserve support for indexed Black Cyc WGQ archives when an adjacent
                // GTB proves that the folder contains an indexed resource pair.
                val gtbFiles = wgqDirectory.walkTopDown()
                    .maxDepth(MAX_GAME_SCAN_DEPTH)
                    .filter { file -> file.isFile && file.extension.equals("gtb", ignoreCase = true) }
                    .take(MAX_DISCOVERED_FILES)
                    .sortedBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
                    .toList()
                for (indexFile in gtbFiles) {
                    val archiveFile = indexFile.parentFile
                        ?.listFiles()
                        ?.firstOrNull { candidate ->
                            candidate.isFile &&
                                candidate.extension.equals("wgq", ignoreCase = true) &&
                                candidate.nameWithoutExtension.equals(
                                    indexFile.nameWithoutExtension,
                                    ignoreCase = true
                                )
                        }
                    if (archiveFile != null) {
                        val indexed = runCatching {
                            SystemNnnGpkBitmapRepository(indexFile, archiveFile, imageDecoder)
                        }.getOrNull()
                        if (indexed != null) return indexed
                    }
                }

                // A .wgq suffix is shared by unrelated resource types. Only standalone
                // files with a supported Black Cyc packed-BMP header enter this catalog.
                val standaloneWgqImages = discoveredWgqFiles.filter { file ->
                    ResourceArchiveParsers.isBlackCycWgqImageCandidate(file)
                }
                if (standaloneWgqImages.isNotEmpty()) {
                    return SystemNnnGpkBitmapRepository(standaloneWgqImages, imageDecoder)
                }

                // Do not let an unrelated/unsupported WGQ folder shadow valid GPK/GTB
                // pairs elsewhere in the game directory (for example, a sibling dwq/).
            }

            val indexFiles = gameDirectory.walkTopDown()
                .maxDepth(MAX_GAME_SCAN_DEPTH)
                .filter { file -> file.isFile && file.extension.equals("gtb", ignoreCase = true) }
                .take(MAX_DISCOVERED_FILES)
                .sortedBy { it.relativeTo(gameDirectory).invariantSeparatorsPath }
                .toList()

            for (indexFile in indexFiles) {
                val archiveFile = indexFile.parentFile
                    ?.listFiles()
                    ?.firstOrNull { candidate ->
                        candidate.isFile &&
                            candidate.extension.equals("gpk", ignoreCase = true) &&
                            candidate.nameWithoutExtension.equals(
                                indexFile.nameWithoutExtension,
                                ignoreCase = true
                            )
                    }
                if (archiveFile != null) {
                    return SystemNnnGpkBitmapRepository(indexFile, archiveFile, imageDecoder)
                }
            }
            throw IOException("Не найдена пара файлов .gtb/.gpk с одинаковым именем для ресурсов сцены.")
        }

        private const val MAX_GAME_SCAN_DEPTH = 12
        private const val MAX_DISCOVERED_FILES = 10_000
    }
}
