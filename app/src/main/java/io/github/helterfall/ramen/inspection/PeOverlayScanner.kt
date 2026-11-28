package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException

/**
 * Locates signatures in PE files using a chunked, streaming scan. The search
 * limit defaults to the full available file; 64 KiB is only the I/O buffer
 * size and never restricts how far an overlay may be appended.
 */
object PeOverlayScanner {
    const val DEFAULT_SEARCH_BYTES = Long.MAX_VALUE
    private const val DOS_HEADER_BYTES = 64
    private const val PE_PREFIX_BYTES = 24
    private const val PE32_OPTIONAL_HEADER_MIN_BYTES = 96
    private const val SECTION_HEADER_BYTES = 40

    @Throws(IOException::class)
    fun findSignature(
        file: File,
        signature: ByteArray,
        maxSearchBytes: Long = Long.MAX_VALUE
    ): Long? {
        if (!file.isFile) throw IOException("The PE executable is not a regular file.")
        if (signature.isEmpty()) throw IllegalArgumentException("A signature cannot be empty.")
        require(maxSearchBytes > 0L)
        FileRandomAccessSource(file).use { source ->
            val overlayStart = findOverlayStart(source)
            return findSignature(
                source = source,
                overlayStart = overlayStart,
                signature = signature,
                maxSearchBytes = maxSearchBytes,
                acceptCandidate = { true }
            )
        }
    }

    internal fun findSignature(
        source: ReadOnlyRandomAccessSource,
        overlayStart: Long,
        signature: ByteArray,
        maxSearchBytes: Long,
        acceptCandidate: (Long) -> Boolean
    ): Long? {
        if (signature.isEmpty()) throw IllegalArgumentException("A signature cannot be empty.")
        require(maxSearchBytes > 0L)
        if (overlayStart < 0L || overlayStart > source.sizeBytes) {
            throw IOException("The PE overlay start lies outside the file.")
        }
        val availableCandidates = source.sizeBytes - overlayStart - signature.size.toLong() + 1L
        if (availableCandidates <= 0L) return null
        var remaining = minOf(maxSearchBytes, availableCandidates)
        var chunkOffset = overlayStart
        val scanBufferSize = 64 * 1024
        while (remaining > 0L) {
            val candidateCount = minOf(scanBufferSize.toLong(), remaining).toInt()
            val bytes = ByteArray(candidateCount + signature.size - 1)
            source.readFullyAt(chunkOffset, bytes)
            for (index in 0 until candidateCount) {
                var matches = true
                for (signatureIndex in signature.indices) {
                    if (bytes[index + signatureIndex] != signature[signatureIndex]) {
                        matches = false
                        break
                    }
                }
                if (matches) {
                    val absoluteOffset = chunkOffset + index.toLong()
                    if (acceptCandidate(absoluteOffset)) return absoluteOffset
                }
            }
            chunkOffset += candidateCount.toLong()
            remaining -= candidateCount.toLong()
        }
        return null
    }

    private fun findOverlayStart(source: ReadOnlyRandomAccessSource): Long {
        if (source.sizeBytes < DOS_HEADER_BYTES) {
            throw IOException("The PE file is shorter than a DOS header.")
        }
        val dos = readBytes(source, 0L, DOS_HEADER_BYTES)
        if (u16(dos, 0) != 0x5A4D) throw IOException("The executable does not have an MZ signature.")
        val peOffset = u32(dos, 0x3C)
        val pe = readBytes(source, peOffset, PE_PREFIX_BYTES)
        if (pe[0] != 'P'.code.toByte() || pe[1] != 'E'.code.toByte() ||
            pe[2] != 0.toByte() || pe[3] != 0.toByte()
        ) throw IOException("The PE signature was not found at e_lfanew.")

        val sectionCount = u16(pe, 6)
        val optionalSize = u16(pe, 20)
        if (sectionCount !in 1..96) throw IOException("The PE section count is outside the supported range.")
        if (optionalSize < PE32_OPTIONAL_HEADER_MIN_BYTES) {
            throw IOException("The PE optional header is truncated.")
        }
        val optionalOffset = checkedAdd(peOffset, PE_PREFIX_BYTES.toLong())
        val optional = readBytes(source, optionalOffset, optionalSize)
        if (u16(optional, 0) != 0x10B && u16(optional, 0) != 0x20B) {
            throw IOException("The PE optional header is neither PE32 nor PE32+.")
        }
        val sectionTable = checkedAdd(optionalOffset, optionalSize.toLong())
        var rawEnd = 0L
        repeat(sectionCount) { index ->
            val sectionOffset = checkedAdd(sectionTable, index.toLong() * SECTION_HEADER_BYTES)
            val section = readBytes(source, sectionOffset, SECTION_HEADER_BYTES)
            val rawSize = u32(section, 16)
            val rawPointer = u32(section, 20)
            if (rawSize > 0L) {
                requireRange(source.sizeBytes, rawPointer, rawSize, "PE section raw data")
                rawEnd = maxOf(rawEnd, checkedAdd(rawPointer, rawSize))
            }
        }
        if (rawEnd > source.sizeBytes) throw IOException("The PE section table extends beyond the file.")
        return rawEnd
    }

    private fun readBytes(source: ReadOnlyRandomAccessSource, offset: Long, length: Int): ByteArray {
        requireRange(source.sizeBytes, offset, length.toLong(), "PE structure")
        return ByteArray(length).also { source.readFullyAt(offset, it) }
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            throw IOException("A PE file offset overflows the supported address range.")
        }
        return left + right
    }

    private fun requireRange(totalSize: Long, offset: Long, length: Long, label: String) {
        if (offset < 0L || length < 0L || offset > totalSize || length > totalSize - offset) {
            throw IOException("$label extends beyond the source.")
        }
    }
}
