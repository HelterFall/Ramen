package io.github.helterfall.ramen.runtime.systemnnn

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale

class SystemNnnLooseAudioRepository(
    gameDirectory: File,
    candidateFiles: Collection<File>,
    private val cacheDirectory: File
) : Closeable {
    private val root = gameDirectory.canonicalFile
    private val payloads = LinkedHashMap<String, SystemNnnLooseAudioPayload>()
    private val stagedFiles = LinkedHashSet<File>()
    private var closed = false
    val resources: List<SystemNnnLooseAudioResourceInfo>
    val warnings: List<String>

    init {
        if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) {
            throw IOException("The imported game directory is unavailable.")
        }
        val foundWarnings = ArrayList<String>()
        val candidates = candidateFiles.distinctBy { it.absolutePath }
            .sortedBy { it.absolutePath }
        candidates.forEach { file ->
            try {
                val payload = SystemNnnLooseAudioFormat.inspect(file, root) ?: return@forEach
                payloads[payload.info.id.lowercase(Locale.ROOT)] = payload
            } catch (error: Exception) {
                foundWarnings += file.name + ": " +
                    (error.message ?: error.javaClass.simpleName)
            }
        }
        resources = payloads.values.map { it.info }
        warnings = foundWarnings.toList()
    }

    fun matches(id: String): List<SystemNnnLooseAudioResourceInfo> {
        val query = id.trim()
        return resources.filter {
            it.id.equals(query, true) || it.fileName.equals(query, true) ||
                it.fileName.substringBeforeLast('.', it.fileName).equals(query, true)
        }
    }

    @Synchronized
    fun materialize(id: String): File {
        checkOpen()
        val matches = matches(id)
        if (matches.size != 1) {
            throw IOException("Loose audio lookup must resolve to exactly one VAW/WGQ file.")
        }
        val resource = payloads[matches.single().id.lowercase(Locale.ROOT)]
            ?: throw IOException("Loose audio catalog entry is unavailable.")
        val cache = cacheDirectory.canonicalFile
        if (!cache.exists() && !cache.mkdirs()) throw IOException("Could not create audio cache.")
        if (!cache.isDirectory || Files.isSymbolicLink(cache.toPath())) {
            throw IOException("Audio cache path is not a regular directory.")
        }
        val suffix = if (resource.info.mediaFormat == "OGG") ".ogg" else ".wav"
        val output = File.createTempFile("ramen-loose-audio-", suffix, cache)
        try {
            SystemNnnLooseAudioFormat.materialize(resource, output)
            if (output.length() <= 0L) throw IOException("Materialized audio is empty.")
            stagedFiles += output
            return output
        } catch (error: Exception) {
            output.delete()
            if (error is IOException) throw error
            throw IOException("Could not materialize loose audio.", error)
        }
    }

    private fun checkOpen() {
        if (closed) throw IOException("Loose audio repository is closed.")
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        stagedFiles.forEach { runCatching { it.delete() } }
        stagedFiles.clear()
    }
}
