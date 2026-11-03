package io.github.helterfall.ramen.runtime.resources

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

enum class ResourceArchiveFamily {
    IMAGES,
    AUDIO
}

data class DiscoveredResourceArchivePair(
    val family: ResourceArchiveFamily,
    val archiveFile: File,
    val indexFile: File,
    val relativePath: String
)

/**
 * Finds matching archive/index siblings inside an already-imported private game folder.
 *
 * Pairing requires the same parent directory and case-insensitive basename:
 * .gpk + .gtb or .vpk + .vtb. Symlinks and .xtx script config files are ignored.
 */
class ImportedResourcePairScanner {

    @Throws(IOException::class)
    fun scan(root: File): List<DiscoveredResourceArchivePair> {
        val canonicalRoot = root.canonicalFile
        if (!canonicalRoot.isDirectory) {
            throw IOException("Imported game directory does not exist.")
        }

        val filesByPair = linkedMapOf<String, MutableMap<String, File>>()
        val pending = ArrayDeque<Pair<File, Int>>()
        pending.add(canonicalRoot to 0)

        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeLast()
            val children = directory.listFiles()
                ?.sortedBy { it.name.lowercase(Locale.ROOT) }
                ?: continue

            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) continue
                if (child.isDirectory) {
                    pending.add(child to depth + 1)
                    continue
                }
                if (!child.isFile) continue

                val extension = child.extension.lowercase(Locale.ROOT)
                if (extension in IGNORED_SCRIPT_CONFIGURATION_EXTENSIONS) continue
                if (extension !in RESOURCE_EXTENSIONS) continue
                val stem = child.nameWithoutExtension.lowercase(Locale.ROOT)
                if (stem.isBlank()) continue

                val parentPath = child.parentFile?.canonicalPath
                    ?: throw IOException("Could not resolve resource parent directory.")
                val key = parentPath + File.separator + stem
                filesByPair.getOrPut(key) { linkedMapOf() }[extension] = child
            }
        }

        val result = ArrayList<DiscoveredResourceArchivePair>()
        filesByPair.values.forEach { files ->
            val gpk = files["gpk"]
            val gtb = files["gtb"]
            if (gpk != null && gtb != null) {
                result += DiscoveredResourceArchivePair(
                    family = ResourceArchiveFamily.IMAGES,
                    archiveFile = gpk,
                    indexFile = gtb,
                    relativePath = gpk.relativeTo(canonicalRoot).invariantSeparatorsPath
                )
            }

            val audioArchive = files["vaw"] ?: files["vpk"]
            val vtb = files["vtb"]
            if (audioArchive != null && vtb != null) {
                result += DiscoveredResourceArchivePair(
                    family = ResourceArchiveFamily.AUDIO,
                    archiveFile = audioArchive,
                    indexFile = vtb,
                    relativePath = audioArchive.relativeTo(canonicalRoot).invariantSeparatorsPath
                )
            }
        }

        return result.sortedWith(
            compareBy<DiscoveredResourceArchivePair> { it.family.ordinal }
                .thenBy { it.relativePath.lowercase(Locale.ROOT) }
        )
    }

    companion object {
        private val RESOURCE_EXTENSIONS = setOf("gpk", "gtb", "vpk", "vaw", "vtb")
        private val IGNORED_SCRIPT_CONFIGURATION_EXTENSIONS = setOf("xtx")
    }
}
