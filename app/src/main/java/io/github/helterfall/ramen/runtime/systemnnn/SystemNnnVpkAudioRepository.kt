package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.inspection.VpkOggCacheMaterializer
import io.github.helterfall.ramen.inspection.VpkVtbArchiveParser
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Opens a matching .vtb/.vpk pair, validates its index and Ogg page boundaries,
 * and stages only the selected Ogg payload into app cache for Android MediaPlayer.
 *
 * Run [materialize] off the main thread. The returned temp file is tracked and
 * deleted when this repository is closed; a player may delete it earlier on stop.
 */
class SystemNnnVpkAudioRepository(
    indexFile: File,
    archiveFile: File,
    private val cacheDirectory: File
) : Closeable {

    private val indexSource = FileRandomAccessSource(indexFile)
    private val archiveSource = FileRandomAccessSource(archiveFile)
    private val stagedFiles = LinkedHashSet<File>()
    private var closed = false

    val entries: List<VpkAudioResource>

    init {
        entries = try {
            VpkVtbArchiveParser().parse(indexSource, archiveSource).entries
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun findResource(resourceId: String): VpkAudioResource? {
        checkOpen()
        val trimmed = resourceId.trim()
        return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: trimmed.toIntOrNull()?.let(entries::getOrNull)
    }

    @Throws(IOException::class)
    fun materialize(resourceId: String): File {
        checkOpen()
        val resource = findResource(resourceId)
            ?: throw IOException("VPK audio resource was not found: " + resourceId)
        return VpkOggCacheMaterializer().materialize(
            source = archiveSource,
            resource = resource,
            cacheDirectory = cacheDirectory
        ).also(stagedFiles::add)
    }

    private fun checkOpen() {
        check(!closed) { "VPK audio repository is closed." }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { indexSource.close() }
        runCatching { archiveSource.close() }
        stagedFiles.forEach { runCatching { it.delete() } }
        stagedFiles.clear()
    }
}
