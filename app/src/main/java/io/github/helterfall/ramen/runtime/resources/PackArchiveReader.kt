package io.github.helterfall.ramen.runtime.resources

import io.github.helterfall.ramen.inspection.ReadOnlyRandomAccessSource
import io.github.helterfall.ramen.runtime.engine.validateEngineRelativePath
import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

class PackArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class PackArchiveEntry(val path: String, val offset: Long, val size: Long)

data class PackArchiveIndex(
    val version: Int,
    val dataStartOffset: Long,
    val entries: List<PackArchiveEntry>
) {
    private val entryByPath: Map<String, PackArchiveEntry> = entries.associateBy { it.path }
    fun find(path: String): PackArchiveEntry? = entryByPath[path]
}

/**
 * Lazy, read-only reader for the versioned Ramen test ResourceContainer.
 *
 * The caller owns [source] and must close it. This reader never copies the complete
 * archive or closes the source. openArchive() reads only the bounded index; openFile()
 * serves a resource through positional reads, while extractFile() is a convenience
 * for small resources that fit in a bounded ByteArray.
 *
 * Binary layout, little-endian:
 *   0..7    ASCII "IF PACKT"
 *   8..11   u32 version (currently 1)
 *   12..15  u32 entry count
 *   16..19  u32 index byte length
 *
 * Index records (repeated entry count times):
 *   u16 UTF-8 path byte length
 *   N bytes UTF-8 relative path
 *   u64 absolute payload offset from archive start
 *   u64 payload size
 *
 * Payloads begin at or after (20 + index byte length). Records must consume the
 * declared index exactly. Payload ranges must fit in the archive and may not overlap.
 */
class PackArchiveReader(
    private val source: ReadOnlyRandomAccessSource,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxIndexBytes: Int = DEFAULT_MAX_INDEX_BYTES,
    private val maxPathBytes: Int = DEFAULT_MAX_PATH_BYTES,
    private val maxExtractBytes: Int = DEFAULT_MAX_EXTRACT_BYTES
) {
    @Volatile
    private var openedIndex: PackArchiveIndex? = null

    init {
        require(maxEntries in 0..ABSOLUTE_MAX_ENTRIES)
        require(maxIndexBytes in 0..ABSOLUTE_MAX_INDEX_BYTES)
        require(maxPathBytes in 1..ABSOLUTE_MAX_PATH_BYTES)
        require(maxExtractBytes in 0..Int.MAX_VALUE)
    }

    /** Reads and validates only the header and bounded index, never resource payloads. */
    @Synchronized
    @Throws(IOException::class)
    fun openArchive(): PackArchiveIndex {
        openedIndex?.let { return it }
        val parsed = readIndex()
        openedIndex = parsed
        return parsed
    }

    /** Opens one indexed resource as a bounded stream backed by positional reads. */
    @Throws(IOException::class)
    fun openFile(filePath: String): InputStream {
        val entry = requireEntry(filePath)
        return PositionalRangeInputStream(source, entry.offset, entry.size)
    }

    /**
     * Materializes a single indexed file. The default 64 MiB limit prevents accidental
     * giant allocations; larger resources should be consumed with openFile().
     */
    @Throws(IOException::class)
    fun extractFile(filePath: String): ByteArray {
        val entry = requireEntry(filePath)
        if (entry.size > maxExtractBytes.toLong() || entry.size > Int.MAX_VALUE.toLong()) {
            throw PackArchiveException(
                "Resource is too large for extractFile(); use openFile() for streaming."
            )
        }
        return ByteArray(entry.size.toInt()).also { source.readFullyAt(entry.offset, it) }
    }

    private fun requireEntry(filePath: String): PackArchiveEntry {
        validatePath(filePath)
        val index = openedIndex
            ?: throw IllegalStateException("openArchive() must be called before reading files.")
        return index.find(filePath)
            ?: throw FileNotFoundException("Resource '$filePath' is not present in the archive index.")
    }

    private fun readIndex(): PackArchiveIndex {
        if (source.sizeBytes < HEADER_BYTES.toLong()) {
            malformed("ResourceContainer is shorter than its fixed header.")
        }

        val header = ByteArray(HEADER_BYTES)
        source.readFullyAt(0L, header)
        if (!header.copyOfRange(0, MAGIC_BYTES.size).contentEquals(MAGIC_BYTES)) {
            malformed("ResourceContainer magic does not match IF PACKT.")
        }

        val version = readU32(header, VERSION_OFFSET)
        if (version != SUPPORTED_VERSION.toLong()) {
            malformed("Unsupported ResourceContainer version: $version.")
        }

        val entryCountLong = readU32(header, ENTRY_COUNT_OFFSET)
        if (entryCountLong > maxEntries.toLong()) {
            malformed("ResourceContainer entry count exceeds the configured limit.")
        }
        val indexByteLengthLong = readU32(header, INDEX_LENGTH_OFFSET)
        if (indexByteLengthLong > maxIndexBytes.toLong()) {
            malformed("ResourceContainer index exceeds the configured size limit.")
        }
        if (indexByteLengthLong > source.sizeBytes - HEADER_BYTES) {
            malformed("ResourceContainer index extends beyond end of archive.")
        }

        val dataStartOffset = HEADER_BYTES.toLong() + indexByteLengthLong
        val indexBytes = ByteArray(indexByteLengthLong.toInt())
        if (indexBytes.isNotEmpty()) {
            source.readFullyAt(HEADER_BYTES.toLong(), indexBytes)
        }

        val entryCount = entryCountLong.toInt()
        if (entryCount == 0 && indexBytes.isNotEmpty()) {
            malformed("An empty ResourceContainer must have an empty index.")
        }
        if (entryCount > 0 && indexBytes.isEmpty()) {
            malformed("ResourceContainer declares entries but has an empty index.")
        }

        val entries = ArrayList<PackArchiveEntry>(entryCount)
        val uniquePaths = HashSet<String>(entryCount)
        var cursor = 0

        repeat(entryCount) {
            if (indexBytes.size - cursor < U16_BYTES) {
                malformed("ResourceContainer index ends inside a path-length field.")
            }
            val pathLength = readU16(indexBytes, cursor)
            cursor += U16_BYTES
            if (pathLength == 0 || pathLength > maxPathBytes) {
                malformed("ResourceContainer contains an empty or oversized path.")
            }
            val requiredBytes = pathLength.toLong() + U64_PAIR_BYTES.toLong()
            if (requiredBytes > (indexBytes.size - cursor).toLong()) {
                malformed("ResourceContainer index ends inside a file record.")
            }

            val path = decodePath(indexBytes, cursor, pathLength)
            cursor += pathLength
            validatePath(path)
            if (!uniquePaths.add(path)) {
                malformed("ResourceContainer contains duplicate path '$path'.")
            }

            val offset = readU64(indexBytes, cursor)
            cursor += U64_BYTES
            val size = readU64(indexBytes, cursor)
            cursor += U64_BYTES

            if (offset < dataStartOffset) {
                malformed("Resource '$path' points into the header or index.")
            }
            if (offset > source.sizeBytes || size > source.sizeBytes - offset) {
                malformed("Resource '$path' extends beyond end of archive.")
            }

            entries += PackArchiveEntry(path = path, offset = offset, size = size)
        }

        if (cursor != indexBytes.size) {
            malformed("ResourceContainer index contains trailing or unclaimed bytes.")
        }
        validateNonOverlappingRanges(entries)

        return PackArchiveIndex(
            version = version.toInt(),
            dataStartOffset = dataStartOffset,
            entries = entries.toList()
        )
    }

    private fun validateNonOverlappingRanges(entries: List<PackArchiveEntry>) {
        val ranges = entries.asSequence()
            .filter { it.size > 0L }
            .sortedBy { it.offset }
            .toList()

        var previousEnd = -1L
        for (entry in ranges) {
            if (entry.offset < previousEnd) {
                malformed("Resource payload ranges overlap at '${entry.path}'.")
            }
            previousEnd = entry.offset + entry.size
        }
    }

    private fun decodePath(bytes: ByteArray, offset: Int, length: Int): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (exception: CharacterCodingException) {
            malformed("ResourceContainer path is not valid UTF-8.")
        }

    private fun validatePath(path: String) {
        try {
            validateEngineRelativePath(path)
        } catch (exception: IllegalArgumentException) {
            malformed("ResourceContainer contains an unsafe relative path.")
        }
    }

    private fun malformed(message: String): Nothing = throw PackArchiveException(message)

    private class PositionalRangeInputStream(
        private val source: ReadOnlyRandomAccessSource,
        private val startOffset: Long,
        private val rangeSize: Long
    ) : InputStream() {
        private var consumed = 0L
        private var closed = false

        override fun read(): Int {
            val single = ByteArray(1)
            val count = read(single, 0, 1)
            return if (count < 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            check(!closed) { "Stream is closed." }
            if (offset < 0 || length < 0 || length > target.size - offset) {
                throw IndexOutOfBoundsException("Invalid destination range.")
            }
            if (length == 0) return 0
            if (consumed >= rangeSize) return -1

            val requested = minOf(length.toLong(), rangeSize - consumed).toInt()
            var emptyReads = 0
            while (true) {
                val count = source.readAt(
                    position = startOffset + consumed,
                    target = target,
                    targetOffset = offset,
                    length = requested
                )
                when {
                    count < 0 -> throw EOFException("Archive was truncated while reading a resource.")
                    count == 0 -> {
                        emptyReads++
                        if (emptyReads >= MAX_EMPTY_READS) {
                            throw IOException("Archive source made no progress while reading a resource.")
                        }
                    }
                    else -> {
                        consumed += count.toLong()
                        return count
                    }
                }
            }
        }

        override fun skip(count: Long): Long {
            check(!closed) { "Stream is closed." }
            if (count <= 0L) return 0L
            val skipped = minOf(count, rangeSize - consumed)
            consumed += skipped
            return skipped
        }

        override fun available(): Int {
            check(!closed) { "Stream is closed." }
            return minOf(Int.MAX_VALUE.toLong(), rangeSize - consumed).toInt()
        }

        override fun close() {
            closed = true
        }

        private companion object {
            const val MAX_EMPTY_READS = 8
        }
    }

    companion object {
        private const val HEADER_BYTES = 20
        private const val VERSION_OFFSET = 8
        private const val ENTRY_COUNT_OFFSET = 12
        private const val INDEX_LENGTH_OFFSET = 16
        private const val U16_BYTES = 2
        private const val U64_BYTES = 8
        private const val U64_PAIR_BYTES = U64_BYTES * 2
        private const val SUPPORTED_VERSION = 1
        private const val DEFAULT_MAX_ENTRIES = 100_000
        private const val ABSOLUTE_MAX_ENTRIES = 1_000_000
        private const val DEFAULT_MAX_INDEX_BYTES = 16 * 1024 * 1024
        private const val ABSOLUTE_MAX_INDEX_BYTES = 64 * 1024 * 1024
        private const val DEFAULT_MAX_PATH_BYTES = 1024
        private const val ABSOLUTE_MAX_PATH_BYTES = 4096
        private const val DEFAULT_MAX_EXTRACT_BYTES = 64 * 1024 * 1024
        private val MAGIC_BYTES = "IF PACKT".toByteArray(Charsets.US_ASCII)

        private fun readU16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readU32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFFL) or
                ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

        private fun readU64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            repeat(U64_BYTES) { index ->
                value = value or ((bytes[offset + index].toLong() and 0xFFL) shl (index * 8))
            }
            if (value < 0L) {
                throw PackArchiveException("ResourceContainer contains an unsigned 64-bit value outside Long range.")
            }
            return value
        }
    }
}
