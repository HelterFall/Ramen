package io.github.helterfall.ramen.importer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

interface FolderEntryNode {
    val name: String?
    val sourceId: String?
        get() = null
    val isDirectory: Boolean
    val declaredSizeBytes: Long?
    fun listChildren(): List<FolderEntryNode>
    fun openInputStream(): InputStream
}

data class FolderCopySummary(
    val fileCount: Int,
    val directoryCount: Int,
    val totalBytes: Long
)

/**
 * Copies a selected tree into a fresh app-private directory.
 *
 * Names are treated as single path segments, and size / depth / entry-count
 * limits are enforced while streaming data rather than trusting provider metadata.
 */
class SafeFolderCopier(
    private val maxEntries: Int = 10_000,
    private val maxEntryBytes: Long = 512L * 1024L * 1024L,
    private val maxTotalBytes: Long = 1024L * 1024L * 1024L,
    private val maxPathDepth: Int = 64
) {
    fun copyContents(root: FolderEntryNode, destination: File): FolderCopySummary {
        require(maxEntries > 0) { "maxEntries must be positive." }
        require(maxEntryBytes > 0L) { "maxEntryBytes must be positive." }
        require(maxTotalBytes > 0L) { "maxTotalBytes must be positive." }
        require(maxPathDepth > 0) { "maxPathDepth must be positive." }

        if (!root.isDirectory) {
            throw IOException("The selected item is not a folder.")
        }
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not prepare the import directory.")
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Could not create the import directory.")
        }

        val canonicalRoot = destination.canonicalFile
        val rootPath = canonicalRoot.toPath()
        val seenPaths = HashSet<String>()
        val seenSourceIds = HashSet<String>()
        root.sourceId?.let(seenSourceIds::add)
        var entryCount = 0
        var fileCount = 0
        var directoryCount = 0
        var totalBytes = 0L

        fun copyNode(node: FolderEntryNode, parent: File, parentRelativePath: String, depth: Int) {
            entryCount += 1
            if (entryCount > maxEntries) {
                throw IOException("The selected folder contains too many entries.")
            }
            if (depth > maxPathDepth) {
                throw IOException("The selected folder is nested too deeply.")
            }

            node.sourceId?.let { sourceId ->
                if (!seenSourceIds.add(sourceId)) {
                    throw IOException("The folder provider returned the same item more than once.")
                }
            }

            val segment = validateSegment(node.name)
            val relativePath = if (parentRelativePath.isEmpty()) {
                segment
            } else {
                "$parentRelativePath/$segment"
            }
            if (relativePath.length > MAX_PATH_LENGTH) {
                throw IOException("The selected folder contains an excessively long path.")
            }
            if (!seenPaths.add(relativePath.lowercase(Locale.ROOT))) {
                throw IOException("The selected folder contains duplicate paths.")
            }

            val output = File(parent, segment).canonicalFile
            val outputPath = output.toPath()
            if (!outputPath.startsWith(rootPath) || outputPath == rootPath) {
                throw IOException("The selected folder contains an unsafe path.")
            }

            if (node.isDirectory) {
                if (!output.mkdirs() && !output.isDirectory) {
                    throw IOException("Could not create an imported folder.")
                }
                directoryCount += 1

                val children = node.listChildren()
                if (children.size > maxEntries - entryCount) {
                    throw IOException("The selected folder contains too many entries.")
                }
                children.forEach { child ->
                    copyNode(child, output, relativePath, depth + 1)
                }
                return
            }

            val declaredSize = node.declaredSizeBytes
            if (declaredSize != null && declaredSize > maxEntryBytes) {
                throw IOException("A selected file exceeds the size limit.")
            }

            var entryBytes = 0L
            BufferedInputStream(node.openInputStream()).use { input ->
                BufferedOutputStream(FileOutputStream(output, false)).use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        val bytesRead = read.toLong()
                        if (bytesRead > maxEntryBytes - entryBytes) {
                            throw IOException("A selected file expands beyond the size limit.")
                        }
                        if (bytesRead > maxTotalBytes - totalBytes) {
                            throw IOException("The selected folder exceeds the total size limit.")
                        }

                        entryBytes += bytesRead
                        totalBytes += bytesRead
                        stream.write(buffer, 0, read)
                    }
                }
            }
            fileCount += 1
        }

        root.listChildren().forEach { child ->
            copyNode(child, canonicalRoot, "", 1)
        }

        if (fileCount == 0) {
            throw IOException("The selected folder does not contain any files.")
        }

        return FolderCopySummary(
            fileCount = fileCount,
            directoryCount = directoryCount,
            totalBytes = totalBytes
        )
    }

    private fun validateSegment(rawName: String?): String {
        if (rawName.isNullOrBlank() || rawName.length > MAX_SEGMENT_LENGTH) {
            throw IOException("The selected folder contains an invalid file name.")
        }
        if (rawName == "." || rawName == "..") {
            throw IOException("Relative path segments are not allowed.")
        }
        if (rawName.contains('/') || rawName.contains('\\')) {
            throw IOException("A file name cannot contain path separators.")
        }
        if (rawName.any { it.isISOControl() }) {
            throw IOException("A file name contains control characters.")
        }
        if (WINDOWS_DRIVE_PREFIX.containsMatchIn(rawName)) {
            throw IOException("Drive-prefixed file names are not allowed.")
        }
        return rawName
    }

    companion object {
        private const val MAX_PATH_LENGTH = 4_096
        private const val MAX_SEGMENT_LENGTH = 255
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
