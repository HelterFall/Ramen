package io.github.helterfall.ramen.importer

import io.github.helterfall.ramen.storage.StorageGuardedOutputStream
import io.github.helterfall.ramen.storage.StorageSpaceGuard
import net.sf.sevenzipjbinding.ExtractAskMode
import net.sf.sevenzipjbinding.ExtractOperationResult
import net.sf.sevenzipjbinding.IArchiveExtractCallback
import net.sf.sevenzipjbinding.IInArchive
import net.sf.sevenzipjbinding.ISequentialOutStream
import net.sf.sevenzipjbinding.PropID
import net.sf.sevenzipjbinding.SevenZip
import net.sf.sevenzipjbinding.SevenZipException
import net.sf.sevenzipjbinding.impl.RandomAccessFileInStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Extracts an NSIS payload from the installer as data. The installer is never
 * executed. Output is streamed into a fresh private directory and checked for
 * path traversal, duplicate names, excessive item counts and available space.
 */
class NsisArchiveExtractor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxPathDepth: Int = DEFAULT_MAX_PATH_DEPTH
) {
    init {
        require(maxEntries in 1..MAX_SUPPORTED_ENTRIES)
        require(maxPathDepth in 1..MAX_SUPPORTED_PATH_DEPTH)
    }

    @Throws(IOException::class)
    fun extract(installer: File, destination: File): ExtractionSummary {
        if (!installer.isFile) throw IOException("The NSIS installer is not a regular file.")
        if (destination.exists() && !destination.deleteRecursively()) {
            throw IOException("Could not prepare the NSIS extraction directory.")
        }
        if (!destination.mkdirs() && !destination.isDirectory) {
            throw IOException("Could not create the NSIS extraction directory.")
        }

        val root = destination.canonicalFile
        val rootPath = root.toPath()
        val entries = ArrayList<ArchiveEntry>()
        val seenPaths = HashSet<String>()
        var totalBytes = 0L
        var fileCount = 0
        var archive: IInArchive? = null
        var inputStream: RandomAccessFileInStream? = null
        var activeOutput: StorageGuardedOutputStream? = null

        try {
            val randomAccessFile = RandomAccessFile(installer, "r")
            val openedStream = RandomAccessFileInStream(randomAccessFile)
            inputStream = openedStream
            val openedArchive = SevenZip.openInArchive(null, openedStream)
            archive = openedArchive
            val itemCount = openedArchive.getNumberOfItems()
            if (itemCount <= 0) throw IOException("The NSIS archive contains no items.")
            if (itemCount > maxEntries) {
                throw IOException("The NSIS archive contains too many items ($itemCount).")
            }

            repeat(itemCount) { index ->
                val rawName = openedArchive.getStringProperty(index, PropID.PATH)
                    ?: throw IOException("NSIS entry $index has no path.")
                val relativePath = normalizeNsisEntryName(rawName, maxPathDepth)
                if (!seenPaths.add(relativePath.lowercase(Locale.ROOT))) {
                    throw IOException("The NSIS archive contains duplicate paths.")
                }

                val isDirectory = openedArchive.getProperty(index, PropID.IS_FOLDER) as? Boolean ?: false
                val output = File(root, relativePath).canonicalFile
                if (!output.toPath().startsWith(rootPath) || output == root) {
                    throw IOException("The NSIS archive contains an unsafe output path.")
                }
                if (isDirectory) {
                    if (!output.mkdirs() && !output.isDirectory) {
                        throw IOException("Could not create an NSIS archive directory.")
                    }
                } else {
                    val parent = output.parentFile
                        ?: throw IOException("An NSIS archive entry has no parent directory.")
                    if (!parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("Could not create an NSIS archive entry directory.")
                    }
                    fileCount += 1
                }
                entries += ArchiveEntry(output, isDirectory)
            }

            if (fileCount == 0) throw IOException("The NSIS archive does not contain any files.")

            openedArchive.extract(null, false, object : IArchiveExtractCallback {
                override fun getStream(
                    index: Int,
                    extractAskMode: ExtractAskMode
                ): ISequentialOutStream? {
                    if (extractAskMode != ExtractAskMode.EXTRACT) return null
                    val item = entries.getOrNull(index)
                        ?: throw SevenZipException("NSIS returned an invalid entry index.")
                    if (item.isDirectory) return null

                    try {
                        activeOutput?.close()
                        val output = StorageGuardedOutputStream(
                            FileOutputStream(item.file, false),
                            StorageSpaceGuard(root)
                        )
                        activeOutput = output
                        return object : ISequentialOutStream {
                            override fun write(data: ByteArray): Int {
                                if (data.isEmpty()) return 0
                                try {
                                    output.write(data)
                                    totalBytes = Math.addExact(totalBytes, data.size.toLong())
                                } catch (error: Exception) {
                                    throw SevenZipException(
                                        error.message ?: "Could not write an extracted NSIS file."
                                    )
                                }
                                return data.size
                            }
                        }
                    } catch (error: Exception) {
                        throw SevenZipException(error.message ?: "Could not open an NSIS output file.")
                    }
                }

                override fun prepareOperation(extractAskMode: ExtractAskMode) = Unit

                override fun setOperationResult(extractOperationResult: ExtractOperationResult) {
                    try {
                        activeOutput?.close()
                    } catch (error: Exception) {
                        throw SevenZipException(error.message ?: "Could not close an NSIS output file.")
                    } finally {
                        activeOutput = null
                    }
                    if (extractOperationResult != ExtractOperationResult.OK) {
                        throw SevenZipException("NSIS extraction returned $extractOperationResult.")
                    }
                }

                override fun setTotal(total: Long) = Unit

                override fun setCompleted(complete: Long) = Unit
            })
            activeOutput?.close()
            activeOutput = null

            return ExtractionSummary(fileCount = fileCount, totalBytes = totalBytes)
        } catch (error: Exception) {
            runCatching { activeOutput?.close() }
            activeOutput = null
            destination.deleteRecursively()
            if (error is IOException) throw error
            throw IOException(
                "NSIS archive extraction failed: ${error.message ?: error.javaClass.simpleName}",
                error
            )
        } finally {
            runCatching { activeOutput?.close() }
            activeOutput = null
            runCatching { archive?.close() }
            runCatching { inputStream?.close() }
        }
    }

    private data class ArchiveEntry(
        val file: File,
        val isDirectory: Boolean
    )

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 10_000
        private const val MAX_SUPPORTED_ENTRIES = 50_000
        private const val DEFAULT_MAX_PATH_DEPTH = 64
        private const val MAX_SUPPORTED_PATH_DEPTH = 128
    }
}

/** Shared path validation kept independent from the JNI archive reader for unit tests. */
internal fun normalizeNsisEntryName(rawName: String, maxPathDepth: Int = 64): String {
    if (rawName.isBlank() || rawName.length > MAX_NSIS_PATH_LENGTH) {
        throw IOException("The NSIS archive contains an invalid path.")
    }
    if (rawName.startsWith("/") || rawName.startsWith("\\") ||
        rawName.any { it == '\u0000' || it.code < 0x20 || it.code == 0x7F }
    ) {
        throw IOException("Absolute paths and control characters are not allowed in NSIS entries.")
    }
    val normalized = rawName.replace('\\', '/')
    if (WINDOWS_DRIVE_PREFIX.containsMatchIn(normalized)) {
        throw IOException("Windows drive paths are not allowed in NSIS entries.")
    }
    val segments = normalized.split('/')
    if (segments.any { it == ".." }) {
        throw IOException("Path traversal is not allowed in NSIS entries.")
    }
    val safeSegments = segments.filter { it.isNotEmpty() && it != "." }
    if (safeSegments.isEmpty() || safeSegments.size > maxPathDepth) {
        throw IOException("The NSIS archive contains an invalid or deeply nested path.")
    }
    return safeSegments.joinToString("/")
}

private const val MAX_NSIS_PATH_LENGTH = 4096
private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
