package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard

/**
 * Copies one parsed Ogg resource into a uniquely named cache file for a media API
 * that requires a seekable file descriptor or path.
 *
 * The cache file is temporary application data, not a permanent game extraction.
 * The caller owns cleanup of the returned file.
 */
class VpkOggCacheMaterializer(
    private val rangeStreamer: ReadOnlyRangeStreamer = ReadOnlyRangeStreamer(),
    private val maxResourceBytes: Long = Long.MAX_VALUE
) {
    init {
        require(maxResourceBytes > 0L)
    }

    @Throws(IOException::class)
    fun materialize(
        source: ReadOnlyRandomAccessSource,
        resource: VpkAudioResource,
        cacheDirectory: File
    ): File {
        val offset = resource.oggPayloadOffset
        val length = resource.oggPayloadSizeBytes
        if (length <= 0L || length > maxResourceBytes ||
            offset < 0L || offset > source.sizeBytes ||
            length > source.sizeBytes - offset
        ) {
            throw IOException("Ogg resource range is invalid or exceeds the cache limit.")
        }
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw IOException("Could not create the audio cache directory.")
        }
        if (!cacheDirectory.isDirectory) {
            throw IOException("The audio cache location is not a directory.")
        }

        val cacheFile = File.createTempFile(CACHE_PREFIX, OGG_SUFFIX, cacheDirectory)
        try {
            val copied = StorageGuardedOutputStream(
                delegate = cacheFile.outputStream(),
                guard = StorageSpaceGuard(cacheDirectory)
            ).use { output ->
                rangeStreamer.copy(
                    source = source,
                    offset = offset,
                    length = length,
                    destination = output
                )
            }
            if (copied != length || cacheFile.length() != length) {
                throw IOException("The cached Ogg resource is incomplete.")
            }
            return cacheFile
        } catch (error: Throwable) {
            cacheFile.delete()
            throw error
        }
    }

    companion object {
        private const val CACHE_PREFIX = "ramen-audio-"
        private const val OGG_SUFFIX = ".ogg"
    }
}
