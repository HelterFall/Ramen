package io.github.helterfall.ramen.runtime.resources

import android.graphics.Bitmap
import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.GpkGtbArchiveParser
import io.github.helterfall.ramen.inspection.GpkImageResource
import io.github.helterfall.ramen.runtime.nativeaudio.AndroidGpkImageDecoder
import java.io.File
import java.io.IOException
import java.util.Locale

private data class LocatedGpkImage(
    val archiveFile: File,
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
    fun loadBackground(resourceKey: String): Bitmap {
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
        return FileRandomAccessSource(selected.archiveFile).use { source ->
            imageDecoder.decode(source, selected.resource)
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
        for (pair in pairs) {
            val parsed = FileRandomAccessSource(pair.indexFile).use { index ->
                FileRandomAccessSource(pair.archiveFile).use { archive ->
                    archiveParser.parse(index, archive)
                }
            }
            if (parsed.entries.size > MAX_INDEXED_IMAGES - result.size) {
                throw IOException("Game has too many indexed GPK images for scenario lookup.")
            }
            parsed.entries.forEach { resource -> result += LocatedGpkImage(pair.archiveFile, resource) }
        }
        return result
    }

    private companion object {
        private const val MAX_IMAGE_PAIRS = 128
        private const val MAX_INDEXED_IMAGES = 200_000
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
