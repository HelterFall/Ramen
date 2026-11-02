package io.github.helterfall.ramen.runtime.engine

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files

/**
 * Bounded read-only source for an already imported app-private game directory.
 *
 * The file list is captured once, sorted by relative path, and excludes symbolic links.
 * Files added after construction are not visible to adapters; changed or removed files
 * are rejected when opened if their indexed size no longer matches.
 */
class DirectoryGameSource(
    rootDirectory: File,
    override val sourceId: String,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxVisitedNodes: Int = DEFAULT_MAX_VISITED_NODES
) : EngineSource {
    private val root: File
    private val canonicalRootPath: java.nio.file.Path
    private val entryByPath: Map<String, EngineSourceEntry>
    private val warningList = mutableListOf<String>()
    private var visitedNodes: Int = 0

    override val displayName: String
    override val entries: List<EngineSourceEntry>
    override var isTruncated: Boolean = false
        private set
    override val warnings: List<String>
        get() = warningList.toList()

    init {
        require(sourceId.isNotBlank()) { "Source ID must not be blank." }
        require(maxFiles in 1..ABSOLUTE_MAX_FILES)
        require(maxDepth in 0..ABSOLUTE_MAX_DEPTH)
        require(maxVisitedNodes in 1..ABSOLUTE_MAX_VISITED_NODES)

        if (Files.isSymbolicLink(rootDirectory.toPath())) {
            throw IOException("The game root must not be a symbolic link.")
        }
        root = rootDirectory.canonicalFile
        if (!root.isDirectory) {
            throw IOException("The game source root is not an accessible directory.")
        }
        canonicalRootPath = root.toPath()
        displayName = root.name.ifBlank { "Imported game" }

        val discovered = mutableListOf<EngineSourceEntry>()
        scanDirectory(
            directory = root,
            relativeDirectory = "",
            depth = 0,
            discovered = discovered
        )
        entries = discovered.sortedBy { it.relativePath }
        entryByPath = entries.associateBy { it.relativePath }
    }

    override fun openRead(relativePath: String): InputStream {
        val file = resolveIndexedFile(relativePath)
        return FileInputStream(file)
    }

    override fun openRandomAccess(relativePath: String): ReadOnlyRandomAccessSource {
        val file = resolveIndexedFile(relativePath)
        return FileRandomAccessSource(file)
    }

    private fun scanDirectory(
        directory: File,
        relativeDirectory: String,
        depth: Int,
        discovered: MutableList<EngineSourceEntry>
    ) {
        if (depth > maxDepth) {
            markTruncated("Directory depth limit reached.")
            return
        }

        val children = directory.listFiles()
        if (children == null) {
            warningList += "Could not enumerate a directory inside the imported game."
            return
        }

        for (child in children.sortedBy { it.name }) {
            if (isTruncated) return

            if (Files.isSymbolicLink(child.toPath())) {
                warningList += "Skipped a symbolic link inside the imported game."
                continue
            }

            val canonicalChild = child.canonicalFile.toPath()
            if (!canonicalChild.startsWith(canonicalRootPath)) {
                warningList += "Skipped an entry that resolves outside the imported game."
                continue
            }

            visitedNodes += 1
            if (visitedNodes > maxVisitedNodes) {
                markTruncated("Directory entry scan limit reached.")
                return
            }

            val relativePath = if (relativeDirectory.isEmpty()) {
                child.name
            } else {
                relativeDirectory + "/" + child.name
            }

            if (child.isDirectory) {
                scanDirectory(
                    directory = child,
                    relativeDirectory = relativePath,
                    depth = depth + 1,
                    discovered = discovered
                )
            } else if (child.isFile) {
                if (discovered.size >= maxFiles) {
                    markTruncated("File count limit reached.")
                    return
                }
                try {
                    discovered += EngineSourceEntry(relativePath, child.length())
                } catch (exception: IllegalArgumentException) {
                    warningList += "Skipped a file with an unsupported relative path."
                }
            }
        }
    }

    private fun markTruncated(reason: String) {
        isTruncated = true
        if (reason !in warningList) warningList += reason
    }

    private fun resolveIndexedFile(relativePath: String): File {
        validateEngineRelativePath(relativePath)
        val entry = entryByPath[relativePath]
            ?: throw FileNotFoundException("The requested file is not in the source snapshot.")

        var current = root
        for (segment in relativePath.split('/')) {
            current = File(current, segment)
            if (Files.isSymbolicLink(current.toPath())) {
                throw IOException("The source path contains a symbolic link.")
            }
        }

        val canonical = current.canonicalFile
        if (!canonical.toPath().startsWith(canonicalRootPath) || !canonical.isFile) {
            throw IOException("The source file is no longer available inside the game directory.")
        }
        if (canonical.length() != entry.sizeBytes) {
            throw IOException("The source file changed after the directory snapshot was created.")
        }
        return canonical
    }

    companion object {
        private const val DEFAULT_MAX_FILES = 10_000
        private const val ABSOLUTE_MAX_FILES = 100_000
        private const val DEFAULT_MAX_DEPTH = 64
        private const val ABSOLUTE_MAX_DEPTH = 128
        private const val DEFAULT_MAX_VISITED_NODES = 20_000
        private const val ABSOLUTE_MAX_VISITED_NODES = 200_000
    }
}
