package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.nio.file.Files

data class DirectoryInventoryEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val sha256Hex: String?,
    val artifactReport: BinaryArtifactReport?,
    val warnings: List<String> = emptyList()
)

data class DirectoryInventoryReport(
    val rootName: String,
    val entries: List<DirectoryInventoryEntry>,
    val discoveredEntries: Int,
    val totalBytesObserved: Long,
    val totalBytesHashed: Long,
    val truncated: Boolean,
    val warnings: List<String> = emptyList()
)

/**
 * Creates a deterministic, bounded, read-only inventory of files in an imported game directory.
 *
 * Each file is opened once through ReadOnlyRandomAccessSource. The binary inspector reads only
 * bounded headers; SHA-256 is streamed through a fixed-size buffer. The scanner never executes
 * files, modifies them, or follows symbolic links.
 */
class DirectoryInventoryScanner(
    private val inspector: BinaryArtifactInspector = BinaryArtifactInspector(),
    private val hasher: Sha256FileHasher = Sha256FileHasher(),
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxTotalBytesToHash: Long = DEFAULT_MAX_TOTAL_BYTES_TO_HASH
) {
    init {
        require(maxDepth >= 1)
        require(maxFiles >= 1)
        require(maxTotalBytesToHash >= 0)
    }

    @Throws(IOException::class)
    fun scan(root: File): DirectoryInventoryReport {
        if (!root.isDirectory) {
            throw IOException("The inventory root is not an accessible directory.")
        }

        val discovered = root.walkTopDown()
            .maxDepth(maxDepth)
            .filter { file ->
                file.isFile && !Files.isSymbolicLink(file.toPath())
            }
            .take(maxFiles + 1)
            .toList()
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }

        val truncated = discovered.size > maxFiles
        val selected = discovered.take(maxFiles)
        val inventoryWarnings = buildList {
            if (truncated) {
                add("File scan reached the configured limit of $maxFiles entries; additional files may exist.")
            }
        }

        val entries = ArrayList<DirectoryInventoryEntry>(selected.size)
        var totalBytesObserved = 0L
        var totalBytesHashed = 0L

        for (file in selected) {
            val relativePath = file.relativeTo(root).invariantSeparatorsPath
            val sizeBytes = try {
                Files.size(file.toPath())
            } catch (error: IOException) {
                entries += DirectoryInventoryEntry(
                    relativePath = relativePath,
                    sizeBytes = 0L,
                    sha256Hex = null,
                    artifactReport = null,
                    warnings = listOf("Could not read file size: ${error.message ?: "I/O error"}.")
                )
                continue
            }
            totalBytesObserved = saturatedAdd(totalBytesObserved, sizeBytes)

            val warnings = mutableListOf<String>()
            var sha256Hex: String? = null
            var artifactReport: BinaryArtifactReport? = null
            try {
                FileRandomAccessSource(file).use { source ->
                    artifactReport = inspector.inspect(
                        source = source,
                        fileName = file.name,
                        extensionHint = file.extension
                    )

                    val remainingHashBudget = maxTotalBytesToHash - totalBytesHashed
                    if (sizeBytes <= remainingHashBudget) {
                        sha256Hex = hasher.hash(source)
                        totalBytesHashed = saturatedAdd(totalBytesHashed, sizeBytes)
                    } else {
                        warnings += "SHA-256 skipped because the configured total hash budget was reached."
                    }
                }
            } catch (error: IOException) {
                warnings += "File inspection failed: ${error.message ?: "I/O error"}."
            }

            entries += DirectoryInventoryEntry(
                relativePath = relativePath,
                sizeBytes = sizeBytes,
                sha256Hex = sha256Hex,
                artifactReport = artifactReport,
                warnings = warnings
            )
        }

        return DirectoryInventoryReport(
            rootName = root.name.ifBlank { "game" },
            entries = entries,
            discoveredEntries = discovered.size,
            totalBytesObserved = totalBytesObserved,
            totalBytesHashed = totalBytesHashed,
            truncated = truncated,
            warnings = inventoryWarnings
        )
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    companion object {
        private const val DEFAULT_MAX_DEPTH = 64
        private const val DEFAULT_MAX_FILES = 10_000
        private const val DEFAULT_MAX_TOTAL_BYTES_TO_HASH = 8L * 1024L * 1024L * 1024L
    }
}
