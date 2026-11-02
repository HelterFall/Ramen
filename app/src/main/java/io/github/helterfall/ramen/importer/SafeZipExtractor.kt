package io.github.helterfall.ramen.importer

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard

data class ExtractionSummary(
    val fileCount: Int,
    val totalBytes: Long
)

/**
 * Extracts ZIP entries into a fresh app-private directory.
 * Limits apply to expanded data, not only the compressed archive size.
 */
class SafeZipExtractor(
    private val maxEntries: Int = 10_000,
    private val maxEntryBytes: Long = Long.MAX_VALUE,
    private val maxTotalBytes: Long = Long.MAX_VALUE,
    private val maxPathDepth: Int = 64
) {
    fun extract(input: InputStream, destination: File): ExtractionSummary {
        require(maxEntries > 0) { "maxEntries must be positive." }
        require(maxEntryBytes > 0L) { "maxEntryBytes must be positive." }
        require(maxTotalBytes > 0L) { "maxTotalBytes must be positive." }
        require(maxPathDepth > 0) { "maxPathDepth must be positive." }

        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not prepare the import directory.")
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Could not create the import directory.")
        }

        val canonicalRoot = destination.canonicalFile
        val rootPath = canonicalRoot.toPath()
        val seenPaths = HashSet<String>()
        val storageGuard = StorageSpaceGuard(destination)
        var entryCount = 0
        var fileCount = 0
        var totalBytes = 0L

        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1

                if (entryCount > maxEntries) {
                    throw IOException("The archive contains too many entries.")
                }

                val relativePath = normalizeEntryName(entry.name)
                if (!seenPaths.add(relativePath.lowercase(Locale.ROOT))) {
                    throw IOException("The archive contains duplicate paths.")
                }

                val outputFile = File(canonicalRoot, relativePath).canonicalFile
                val outputPath = outputFile.toPath()

                if (!outputPath.startsWith(rootPath) || outputPath == rootPath) {
                    throw IOException("The archive contains an unsafe path.")
                }

                if (entry.isDirectory) {
                    if (!outputFile.mkdirs() && !outputFile.isDirectory) {
                        throw IOException("Could not create an archive directory.")
                    }
                    zip.closeEntry()
                    continue
                }

                if (entry.size > maxEntryBytes) {
                    throw IOException("An archive entry exceeds the size limit.")
                }

                val parent = outputFile.parentFile
                    ?: throw IOException("An archive entry has no parent directory.")

                if (!parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("Could not create an archive entry directory.")
                }

                var entryBytes = 0L
                StorageGuardedOutputStream(
                    delegate = BufferedOutputStream(FileOutputStream(outputFile, false)),
                    guard = storageGuard
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        val bytesRead = read.toLong()
                        if (bytesRead > maxEntryBytes - entryBytes) {
                            throw IOException("An archive entry expands beyond the size limit.")
                        }
                        if (bytesRead > maxTotalBytes - totalBytes) {
                            throw IOException("The archive expands beyond the total size limit.")
                        }

                        entryBytes += bytesRead
                        totalBytes += bytesRead
                        output.write(buffer, 0, read)
                    }
                }

                fileCount += 1
                zip.closeEntry()
            }
        }

        if (fileCount == 0) {
            throw IOException("The archive does not contain any files.")
        }

        return ExtractionSummary(
            fileCount = fileCount,
            totalBytes = totalBytes
        )
    }

    private fun normalizeEntryName(rawName: String): String {
        if (rawName.isBlank() || rawName.length > MAX_PATH_LENGTH) {
            throw IOException("The archive contains an invalid path.")
        }
        if (rawName.startsWith("/") || rawName.startsWith("\\") || rawName.contains('\\')) {
            throw IOException("Absolute paths and backslashes are not allowed in ZIP entries.")
        }
        if (rawName.any { it == '\u0000' || it.code < 0x20 }) {
            throw IOException("The archive contains a path with control characters.")
        }
        if (WINDOWS_DRIVE_PREFIX.containsMatchIn(rawName)) {
            throw IOException("Windows drive paths are not allowed in ZIP entries.")
        }

        val withoutTrailingSlash = rawName.removeSuffix("/")
        val segments = withoutTrailingSlash.split('/')

        if (segments.any { it == ".." }) {
            throw IOException("Path traversal is not allowed in ZIP entries.")
        }

        val safeSegments = segments.filter { it.isNotEmpty() && it != "." }
        if (safeSegments.isEmpty() || safeSegments.size > maxPathDepth) {
            throw IOException("The archive contains an invalid or deeply nested path.")
        }

        return safeSegments.joinToString("/")
    }

    companion object {
        private const val MAX_PATH_LENGTH = 4_096
        private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
    }
}
