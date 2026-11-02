package io.github.helterfall.ramen.runtime.resources

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.File
import java.io.IOException
import java.util.Locale

data class GpkImageLocation(
    val archiveFile: File,
    val archiveRelativePath: String,
    val resource: GpkImageResource
)

private data class LocatedGpkImage(
    val archiveFile: File,
    val archiveRelativePath: String,
    val resource: GpkImageResource
)

/**
 * Resolves Script background keys against indexed GPK/GTB pairs.
 * Index metadata is retained; image bytes are decoded from their physical range on demand.
 */
class ScenarioResourceManager(
    private val gameRoot: File,
    private val pairScanner: ImportedResourcePairScanner = ImportedResourcePairScanner(),
    private val archiveParser: GpkGtbArchiveParser = GpkGtbArchiveParser(),
    private val imageDecoder: AndroidGpkImageDecoder = AndroidGpkImageDecoder()
) {
    private val indexedImages: List<LocatedGpkImage> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        indexImageResources()
    }

    @Throws(IOException::class)
    fun resolveBackground(resourceKey: String): GpkImageLocation {
        if (!SAFE_RESOURCE_KEY.matches(resourceKey) ||
            resourceKey.split('/').any { it.isBlank() || it == "." || it == ".." }
        ) {
            throw IOException("Background resource key is not a safe relative path.")
        }

        val rankedMatches = indexedImages.mapNotNull { candidate ->
            GpkResourceNameMatcher.rank(resourceKey, candidate.resource.name)
                ?.let { rank -> rank to candidate }
        }
        if (rankedMatches.isEmpty()) {
            throw IOException("Background resource '$resourceKey' was not found in any GPK/GTB pair.")
        }

        val bestRank = rankedMatches.minOf { it.first }
        val best = rankedMatches.filter { it.first == bestRank }
        if (best.size != 1) {
            throw IOException("Background resource '$resourceKey' is ambiguous across GPK archives.")
        }

        val selected = best.single().second
        return GpkImageLocation(
            archiveFile = selected.archiveFile,
            archiveRelativePath = selected.archiveRelativePath,
            resource = selected.resource
        )
    }

    @Throws(IOException::class)
    fun loadBackground(resourceKey: String): Bitmap {
        val location = resolveBackground(resourceKey)
        return FileRandomAccessSource(location.archiveFile).use { source ->
            imageDecoder.decode(source, location.resource)
        }
    }

    private fun indexImageResources(): List<LocatedGpkImage> {
        val root = gameRoot.canonicalFile
        if (!root.isDirectory) {
            throw IOException("Imported game directory is unavailable for background lookup.")
        }

        val pairs = pairScanner.scan(root)
            .filter { it.family == ResourceArchiveFamily.IMAGES }
        if (pairs.size > MAX_IMAGE_PAIRS) {
            throw IOException("Game has too many GPK/GTB pairs for scenario background lookup.")
        }

        val result = ArrayList<LocatedGpkImage>()
        val failures = mutableListOf<String>()
        val rootPath = root.toPath()
        for (pair in pairs) {
            val archiveFile = pair.archiveFile.canonicalFile
            val indexFile = pair.indexFile.canonicalFile
            if (!archiveFile.toPath().startsWith(rootPath) ||
                !indexFile.toPath().startsWith(rootPath)
            ) {
                failures += "Skipped a GPK/GTB pair outside the imported folder."
                continue
            }

            val parsed = try {
                FileRandomAccessSource(indexFile).use { index ->
                    FileRandomAccessSource(archiveFile).use { archive ->
                        archiveParser.parse(index, archive)
                    }
                }
            } catch (exception: IOException) {
                failures += (pair.relativePath + ": " +
                    (exception.message ?: "invalid GPK/GTB pair")).take(MAX_DIAGNOSTIC_LENGTH)
                continue
            }

            if (parsed.entries.size > MAX_INDEXED_IMAGES - result.size) {
                throw IOException("Game has too many indexed GPK images for scenario lookup.")
            }
            parsed.entries.forEach { resource ->
                result += LocatedGpkImage(archiveFile, pair.relativePath, resource)
            }
        }

        if (result.isEmpty() && failures.isNotEmpty()) {
            throw IOException(
                "No valid GPK/GTB pair was available for background lookup: " +
                    failures.take(MAX_DIAGNOSTICS).joinToString("; ")
            )
        }
        return result
    }

    private companion object {
        private const val MAX_IMAGE_PAIRS = 128
        private const val MAX_INDEXED_IMAGES = 200_000
        private const val MAX_DIAGNOSTICS = 6
        private const val MAX_DIAGNOSTIC_LENGTH = 384
        private val SAFE_RESOURCE_KEY =
            Regex("^[\\p{L}\\p{N}_.-]{1,64}(/[\\p{L}\\p{N}_.-]{1,64})*$")
    }
}

/** Matching policy: exact relative key, basename, then basename without an extension. */
internal object GpkResourceNameMatcher {
    fun rank(resourceKey: String, archiveName: String): Int? {
        val key = normalize(resourceKey)
        val name = normalize(archiveName)
        if (key.isEmpty() || name.isEmpty()) return null
        if (key == name) return 0

        val keyBase = key.substringAfterLast('/')
        val nameBase = name.substringAfterLast('/')
        if (keyBase == nameBase) return 1

        val keyStem = keyBase.substringBeforeLast('.', keyBase)
        val nameStem = nameBase.substringBeforeLast('.', nameBase)
        if (keyStem == nameBase || keyBase == nameStem || keyStem == nameStem) return 2
        return null
    }

    private fun normalize(value: String): String =
        value.trim().replace('\\', '/').trim('/').lowercase(Locale.ROOT)
}
