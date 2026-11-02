package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque

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
    /** Number of file entries observed, capped at maxFiles + 1 to detect truncation. */
    val discoveredEntries: Int,
    /** Sum of sizes for the selected entries in this bounded report. */
    val totalBytesObserved: Long,
    /** Sum of sizes actually hashed, bounded by maxTotalBytesToHash. */
    val totalBytesHashed: Long,
    val truncated: Boolean,
    val warnings: List<String> = emptyList()
)

/**
 * Creates a deterministic, bounded, read-only inventory of files in an imported game directory.
 *
 * Traversal is depth-first with each directory's children sorted before visiting. The scan stops
 * after maxFiles + 1 files, which is enough to report that the file-count cap was reached.
 * Symbolic links are never followed. Each selected file is opened once through
 * ReadOnlyRandomAccessSource; PE metadata is read from bounded headers and SHA-256 is streamed.
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
        require(maxFiles in 1 until Int.MAX_VALUE)
        require(maxTotalBytesToHash >= 0)
    }

    @Throws(IOException::class)
    fun scan(root: File): DirectoryInventoryReport {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw IOException("The inventory root is not an accessible, non-symbolic directory.")
        }

        val traversalWarnings = mutableListOf<String>()
        var depthLimitReached = false
        val discovered = ArrayList<File>(minOf(maxFiles + 1, INITIAL_DISCOVERY_CAPACITY))
        val pending = ArrayDeque<PendingNode>()
        pending.addLast(PendingNode(root, depth = 0))

        while (pending.isNotEmpty() && discovered.size < maxFiles + 1) {
            val node = pending.removeLast()
            val current = node.file
            if (Files.isSymbolicLink(current.toPath())) continue

            if (current.isDirectory) {
                val children = current.listFiles()
                if (children == null) {
                    traversalWarnings += "Could not enumerate directory: ${current.relativeTo(root).invariantSeparatorsPath.ifBlank { "." }}."
                    continue
                }

                val orderedChildren = children.sortedBy { it.name }
                if (node.depth >= maxDepth) {
                    if (orderedChildren.isNotEmpty()) depthLimitReached = true
                    continue
                }
                for (child in orderedChildren.asReversed()) {
                    if (!Files.isSymbolicLink(child.toPath())) {
                        pending.addLast(PendingNode(child, node.depth + 1))
                    }
                }
            } else if (current.isFile) {
                discovered += current
            }
        }

        val fileLimitReached = discovered.size > maxFiles
        val selected = discovered.take(maxFiles)
        val inventoryWarnings = buildList {
            addAll(traversalWarnings)
            if (fileLimitReached) {
                add("File scan reached the configured limit of $maxFiles entries; additional files may exist.")
            }
            if (depthLimitReached) {
                add("Directory scan reached the configured depth limit of $maxDepth; deeper files may exist.")
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
                    if (source.sizeBytes != sizeBytes) {
                        warnings += "File size changed during inventory; metadata and hash were skipped."
                    } else {
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
            truncated = fileLimitReached || depthLimitReached || traversalWarnings.isNotEmpty(),
            warnings = inventoryWarnings
        )
    }

    private data class PendingNode(val file: File, val depth: Int)

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    companion object {
        private const val DEFAULT_MAX_DEPTH = 64
        private const val DEFAULT_MAX_FILES = 10_000
        private const val DEFAULT_MAX_TOTAL_BYTES_TO_HASH = 8L * 1024L * 1024L * 1024L
        private const val INITIAL_DISCOVERY_CAPACITY = 256
    }
}
