package io.github.helterfall.ramen.runtime.systemnnn

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale

data class SystemNnnLooseAudioResourceInfo(
    val id: String,
    val fileName: String,
    val extension: String,
    val packType: Int,
    val mediaFormat: String,
    val sourceSizeBytes: Long,
    val payloadOffsetBytes: Long,
    val payloadSizeBytes: Long
)

internal data class SystemNnnLooseAudioPayload(
    val info: SystemNnnLooseAudioResourceInfo,
    val file: File,
    val waveHeader: ByteArray? = null,
    val decodedPcmSizeBytes: Long = 0L
)

/** Read-only parser/materializer for standalone Black Cyc VAW and WGQ files. */
internal object SystemNnnLooseAudioFormat {
    private const val HEADER_SIZE = 0x40L
    private const val PACKTYPE_OFFSET = 0x30
    private const val MAX_SOURCE_BYTES = 512L * 1024L * 1024L
    private const val MAX_PCM_BYTES = 256L * 1024L * 1024L
    private val PACKTYPE = Regex("^PACKTYPE=(\\d+)(A?)$")

    fun inspect(file: File, root: File): SystemNnnLooseAudioPayload? {
        if (!file.isFile || Files.isSymbolicLink(file.toPath())) return null
        val canonical = file.canonicalFile
        if (!canonical.toPath().startsWith(root.toPath())) {
            throw IOException("Audio resource escapes the imported game directory.")
        }
        val extension = canonical.extension.lowercase(Locale.ROOT)
        if (extension != "vaw" && extension != "wgq") return null
        val length = canonical.length()
        if (length < HEADER_SIZE || length > MAX_SOURCE_BYTES) {
            throw IOException("Audio resource size is outside supported bounds.")
        }

        val header = ByteArray(HEADER_SIZE.toInt())
        RandomAccessFile(canonical, "r").use { it.readFully(header) }
        val field = String(header, PACKTYPE_OFFSET, 16, Charsets.US_ASCII).trimEnd(' ')
        val packType = PACKTYPE.matchEntire(field)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Audio resource has an invalid PACKTYPE header.")

        var payloadOffset: Long
        var mediaFormat: String
        var waveHeader: ByteArray? = null
        var decodedPcmSize = 0L

        when (packType) {
            0 -> {
                requireTag(canonical, HEADER_SIZE, "RIFF")
                requireTag(canonical, HEADER_SIZE + 8L, "WAVE")
                payloadOffset = HEADER_SIZE
                mediaFormat = "WAVE"
            }
            1 -> {
                val decoded = readDifferentialWaveHeader(canonical)
                waveHeader = decoded.first
                decodedPcmSize = decoded.second
                payloadOffset = HEADER_SIZE + waveHeader.size
                mediaFormat = "WAVE"
            }
            2 -> {
                payloadOffset = HEADER_SIZE + 0x2CL
                requireTag(canonical, payloadOffset, "OggS")
                mediaFormat = "OGG"
            }
            6 -> {
                if (!header.copyOfRange(0x10, 0x14).contentEquals("OGG ".toByteArray(Charsets.US_ASCII))) {
                    throw IOException("PACKTYPE=6 resource has no OGG marker.")
                }
                payloadOffset = HEADER_SIZE
                requireTag(canonical, payloadOffset, "OggS")
                mediaFormat = "OGG"
            }
            else -> throw IOException("Unsupported loose audio PACKTYPE=" + packType + ".")
        }

        if (payloadOffset >= length) throw IOException("Audio payload is empty.")
        val id = root.toPath().relativize(canonical.toPath()).toString()
            .replace(File.separatorChar, '/')
        val info = SystemNnnLooseAudioResourceInfo(
            id, canonical.name, extension, packType, mediaFormat,
            length, payloadOffset, length - payloadOffset
        )
        return SystemNnnLooseAudioPayload(info, canonical, waveHeader, decodedPcmSize)
    }

    fun materialize(resource: SystemNnnLooseAudioPayload, destination: File) {
        when (resource.info.packType) {
            0, 2, 6 -> copyRange(
                resource.file, destination,
                resource.info.payloadOffsetBytes, resource.info.payloadSizeBytes
            )
            1 -> decodeDifferentialPcm(resource, destination)
            else -> throw IOException("Unsupported loose audio PACKTYPE.")
        }
    }

    private fun readDifferentialWaveHeader(file: File): Pair<ByteArray, Long> {
        RandomAccessFile(file, "r").use { input ->
            val prefix = ByteArray(0x14)
            input.seek(HEADER_SIZE)
            input.readFully(prefix)
            if (!tag(prefix, 0, "RIFF") || !tag(prefix, 8, "WAVE") || !tag(prefix, 12, "fmt ")) {
                throw IOException("PACKTYPE=1 has no RIFF/WAVE format header.")
            }
            val fmtSize = u32(prefix, 16)
            if (fmtSize !in 16L..4096L) throw IOException("PACKTYPE=1 WAVE format size is invalid.")
            val headerSize = 0x14L + fmtSize
            val header = ByteArray(headerSize.toInt())
            prefix.copyInto(header)
            if (header.size > prefix.size) input.readFully(header, prefix.size, header.size - prefix.size)
            val riffSize = u32(header, 4) + 8L
            val decodedSize = riffSize - headerSize
            if (decodedSize <= 0L || decodedSize > MAX_PCM_BYTES ||
                decodedSize % 2L != 0L || HEADER_SIZE + headerSize >= input.length()
            ) {
                throw IOException("PACKTYPE=1 PCM size or payload boundary is invalid.")
            }
            return header to decodedSize
        }
    }

    private fun decodeDifferentialPcm(resource: SystemNnnLooseAudioPayload, destination: File) {
        val header = resource.waveHeader ?: throw IOException("PACKTYPE=1 WAVE header is missing.")
        val sampleCount = resource.decodedPcmSizeBytes / 2L
        BufferedInputStream(FileInputStream(resource.file)).use { input ->
            skipExactly(input, HEADER_SIZE + header.size)
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                output.write(header)
                val bits = LsbBits(input)
                var previous = 0
                repeatLong(sampleCount) {
                    val count = bits.read(4) ?: 0
                    var code = 0
                    if (count > 0) {
                        val raw = bits.read(count)
                            ?: throw EOFException("Truncated PACKTYPE=1 differential sample.")
                        val shift = 32 - count
                        code = (raw shl shift) shr shift
                    }
                    val sign = code shr 31
                    code = (code xor (0x4000 shr (15 - count))) - sign
                    previous = (previous + code).toShort().toInt()
                    output.write(previous and 0xFF)
                    output.write((previous ushr 8) and 0xFF)
                }
            }
        }
    }

    private class LsbBits(private val input: InputStream) {
        private var buffer = 0L
        private var count = 0
        fun read(width: Int): Int? {
            while (count < width) {
                val next = input.read()
                if (next < 0) return null
                buffer = buffer or ((next.toLong() and 0xFFL) shl count)
                count += 8
            }
            val result = (buffer and ((1L shl width) - 1L)).toInt()
            buffer = buffer ushr width
            count -= width
            return result
        }
    }

    private fun copyRange(file: File, destination: File, offset: Long, length: Long) {
        if (offset < 0L || length <= 0L || offset > file.length() || length > file.length() - offset) {
            throw IOException("Audio payload range is invalid.")
        }
        BufferedInputStream(FileInputStream(file)).use { input ->
            skipExactly(input, offset)
            BufferedOutputStream(FileOutputStream(destination)).use { output ->
                val buffer = ByteArray(8192)
                var remaining = length
                while (remaining > 0L) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw EOFException("Truncated audio payload.")
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    remaining -= read.toLong()
                }
            }
        }
    }

    private fun requireTag(file: File, offset: Long, value: String) {
        RandomAccessFile(file, "r").use { input ->
            if (offset < 0L || offset > input.length() - 4L) throw EOFException("Truncated audio tag.")
            input.seek(offset)
            val bytes = ByteArray(4)
            input.readFully(bytes)
            if (!tag(bytes, 0, value)) throw IOException("Audio payload is missing " + value + ".")
        }
    }

    private fun tag(bytes: ByteArray, offset: Int, value: String): Boolean {
        val expected = value.toByteArray(Charsets.US_ASCII)
        return offset >= 0 && offset <= bytes.size - expected.size &&
            expected.indices.all { bytes[offset + it] == expected[it] }
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > bytes.size - 4) throw IOException("Truncated DWORD.")
        return (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
    }

    private fun skipExactly(input: InputStream, amount: Long) {
        var remaining = amount
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) remaining -= skipped
            else {
                if (input.read() < 0) throw EOFException("Unexpected end of audio file.")
                remaining--
            }
        }
    }

    private inline fun repeatLong(times: Long, action: () -> Unit) {
        var index = 0L
        while (index < times) {
            action()
            index++
        }
    }
}
