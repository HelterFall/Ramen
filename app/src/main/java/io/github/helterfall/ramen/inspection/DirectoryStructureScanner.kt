package io.github.helterfall.ramen.inspection

import io.github.helterfall.ramen.importer.FolderEntryNode
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale

data class DirectoryStructureReport(
    val rootName: String,
    val directories: List<String>,
    val truncated: Boolean,
    val warnings: List<String> = emptyList()
)

/** Bounded read-only walk that records directories only; files are ignored. */
class DirectoryStructureScanner(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH
) {
    init {
        require(maxEntries > 0)
        require(maxDepth > 0)
    }

    @Throws(IOException::class)
    fun scan(root: FolderEntryNode): DirectoryStructureReport {
        if (!root.isDirectory) throw IOException("The selected item is not a folder.")

        val directories = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val seenPaths = HashSet<String>()
        val seenSourceIds = HashSet<String>()
        root.sourceId?.let(seenSourceIds::add)

        val pending = ArrayDeque<PendingNode>()
        var truncated = false

        fun enqueueChildren(children: List<FolderEntryNode>, parentPath: String, depth: Int) {
            // Do not inspect file names, sizes, or contents. Only folders enter the queue.
            val ordered = children.asSequence()
                .filter { it.isDirectory }
                .sortedBy { it.name.orEmpty() }
                .toList()
            val availableSlots = (maxEntries + 1 - pending.size).coerceAtLeast(0)
            if (ordered.size > availableSlots) {
                truncated = true
                warnings += "A directory contains more subfolders than the scan can queue; some folders may be omitted."
            }
            ordered.take(availableSlots).asReversed().forEach { child ->
                pending.addLast(PendingNode(child, parentPath, depth))
            }
        }

        val rootChildren = try {
            root.listChildren()
        } catch (error: Exception) {
            throw IOException("Could not enumerate the selected folder.", error)
        }
        enqueueChildren(rootChildren, parentPath = "", depth = 1)

        while (pending.isNotEmpty()) {
            if (directories.size >= maxEntries) {
                truncated = true
                warnings += "Directory structure reached the configured limit of $maxEntries folders; additional folders may exist."
                break
            }

            val current = pending.removeLast()
            if (current.depth > maxDepth) {
                truncated = true
                warnings += "Directory structure reached the configured depth limit of $maxDepth."
                continue
            }

            val sourceId = current.node.sourceId
            if (!sourceId.isNullOrBlank() && !seenSourceIds.add(sourceId)) {
                throw IOException("The folder provider returned the same directory more than once.")
            }

            val segment = validateSegment(current.node.name)
            val relativePath = if (current.parentPath.isEmpty()) {
                segment
            } else {
                current.parentPath + "/" + segment
            }
            if (relativePath.length > MAX_PATH_LENGTH) {
                throw IOException("The selected folder contains an excessively long path.")
            }
            if (!seenPaths.add(relativePath.lowercase(Locale.ROOT))) {
                throw IOException("The selected folder contains duplicate paths.")
            }

            directories += relativePath
            val children = try {
                current.node.listChildren()
            } catch (error: Exception) {
                truncated = true
                warnings += "Could not enumerate directory: $relativePath."
                emptyList()
            }

            if (current.depth >= maxDepth) {
                if (children.any { it.isDirectory }) {
                    truncated = true
                    warnings += "Directory structure reached the configured depth limit of $maxDepth."
                }
            } else {
                enqueueChildren(
                    children,
                    parentPath = relativePath,
                    depth = current.depth + 1
                )
            }
        }

        return DirectoryStructureReport(
            rootName = root.name?.takeIf { it.isNotBlank() } ?: "game",
            directories = directories,
            truncated = truncated,
            warnings = warnings.distinct()
        )
    }

    private fun validateSegment(rawName: String?): String {
        if (rawName.isNullOrBlank() || rawName.length > MAX_SEGMENT_LENGTH) {
            throw IOException("The selected folder contains an invalid directory name.")
        }
        if (rawName == "." || rawName == "..") throw IOException("Relative path segments are not allowed.")
        if (rawName.contains('/') || rawName.contains('\\')) {
            throw IOException("A directory name cannot contain path separators.")
        }
        if (rawName.any { it.isISOControl() }) throw IOException("A directory name contains control characters.")
        if (WINDOWS_DRIVE_PREFIX.containsMatchIn(rawName)) {
            throw IOException("Drive-prefixed directory names are not allowed.")
        }
        return rawName
    }

    private data class PendingNode(val node: FolderEntryNode, val parentPath: String, val depth: Int)

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 10_000
        private const val DEFAULT_MAX_DEPTH = 64
        private const val MAX_PATH_LENGTH = 4_096
        private const val MAX_SEGMENT_LENGTH = 255
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
