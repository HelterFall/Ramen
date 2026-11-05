package io.github.helterfall.ramen.runtime.systemnnn

import io.github.helterfall.ramen.inspection.FileRandomAccessSource
import io.github.helterfall.ramen.inspection.VpkAudioResource
import io.github.helterfall.ramen.inspection.VpkOggCacheMaterializer
import io.github.helterfall.ramen.inspection.VpkVtbArchiveParser
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Index-based audio access exposed to the player. Implementations must keep
 * bank-local indexes explicit; a catalog containing several banks must not
 * silently concatenate them into a guessed global voice table.
 */
interface SystemNnnAudioResourceRepository : Closeable {
    override val entries: List<VpkAudioResource>

    fun findResource(resourceId: String): VpkAudioResource?

    @Throws(IOException::class)
    fun materialize(resourceId: String): File

    @Throws(IOException::class)
    fun materializeByIndex(index: Long): File
}

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
) : SystemNnnAudioResourceRepository {

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

    override fun findResource(resourceId: String): VpkAudioResource? {
        checkOpen()
        val trimmed = resourceId.trim()
        return entries.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?: trimmed.toIntOrNull()?.let(entries::getOrNull)
    }

    @Throws(IOException::class)
    override fun materialize(resourceId: String): File {
        checkOpen()
        val resource = findResource(resourceId)
            ?: throw IOException("VPK/VAW audio resource was not found: " + resourceId)
        return VpkOggCacheMaterializer().materialize(
            source = archiveSource,
            resource = resource,
            cacheDirectory = cacheDirectory
        ).also(stagedFiles::add)
    }

    /** Resolves the zero-based VTB row synchronized with a dialogue step. */
    override fun materializeByIndex(index: Long): File {
        checkOpen()
        if (index < 0L || index >= entries.size.toLong()) {
            throw IOException("VAW/VTB voice index is outside the audio catalog: " + index)
        }
        return materialize(entries[index.toInt()].name)
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
