package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

enum class ArtifactKind {
    PE_IMAGE,
    DOS_MZ_IMAGE,
    ELF_IMAGE,
    ZIP_CONTAINER,
    RIFF_CONTAINER,
    OGG_STREAM,
    PNG_IMAGE,
    JPEG_IMAGE,
    UNKNOWN
}

data class PeImageMetadata(
    val machineCode: Int,
    val machineLabel: String,
    val optionalHeaderMagic: Int?,
    val optionalHeaderLabel: String?,
    val sectionCount: Int,
    val isDll: Boolean
)

data class BinaryArtifactReport(
    val fileName: String,
    val fileSizeBytes: Long,
    val extensionHint: String?,
    val kind: ArtifactKind,
    val signature: String?,
    val evidence: String,
    val peMetadata: PeImageMetadata? = null,
    val warnings: List<String> = emptyList()
)

/**
 * Read-only, bounded first-pass inspection of common binary file signatures and PE headers.
 *
 * This inspector never loads or executes the inspected file. Extensions are reported only as
 * hints; classification is based on bytes. The PE parser reads only the DOS header, COFF header,
 * and optional-header magic. It deliberately does not unpack resources or alter file contents.
 */
class BinaryArtifactInspector(
    private val maxPeHeaderOffset: Long = DEFAULT_MAX_PE_HEADER_OFFSET
) {
    init {
        require(maxPeHeaderOffset >= MIN_PE_HEADER_OFFSET)
    }

    @Throws(IOException::class)
    fun inspect(file: File): BinaryArtifactReport {
        if (!file.isFile) {
            throw IOException("The selected artifact is not a readable file.")
        }

        RandomAccessFile(file, "r").use { input ->
            val fileSize = input.length()
            val extension = file.extension
                .takeIf { it.isNotBlank() }
                ?.lowercase(Locale.ROOT)

            val prefix = readBytes(input, 0L, minOf(PREFIX_LENGTH.toLong(), fileSize).toInt())
            val prefixKind = detectPrefixKind(prefix)
            if (prefixKind != null) {
                return BinaryArtifactReport(
                    fileName = file.name,
                    fileSizeBytes = fileSize,
                    extensionHint = extension,
                    kind = prefixKind.first,
                    signature = prefixKind.second,
                    evidence = prefixKind.third
                )
            }

            if (prefix.startsWith(MZ_SIGNATURE)) {
                return inspectMzImage(input, file, fileSize, extension)
            }

            return BinaryArtifactReport(
                fileName = file.name,
                fileSizeBytes = fileSize,
                extensionHint = extension,
                kind = ArtifactKind.UNKNOWN,
                signature = prefix.take(8).toHexString(),
                evidence = "No registered binary signature matched. The extension is not used as proof of format."
            )
        }
    }

    private fun inspectMzImage(
        input: RandomAccessFile,
        file: File,
        fileSize: Long,
        extension: String?
    ): BinaryArtifactReport {
        if (fileSize < DOS_HEADER_SIZE) {
            return report(
                file,
                fileSize,
                extension,
                ArtifactKind.DOS_MZ_IMAGE,
                "MZ",
                "MZ signature found, but the file is too short to contain the DOS header.",
                warnings = listOf("Truncated DOS header.")
            )
        }

        val peOffset = readU32(input, PE_OFFSET_FIELD.toLong())
        if (peOffset > maxPeHeaderOffset || peOffset > fileSize - PE_HEADER_MIN_SIZE) {
            return report(
                file,
                fileSize,
                extension,
                ArtifactKind.DOS_MZ_IMAGE,
                "MZ",
                "MZ signature found, but the PE header offset is outside the accepted inspection bounds.",
                warnings = listOf("Invalid or out-of-bounds PE header offset.")
            )
        }

        val peSignature = readBytes(input, peOffset, PE_SIGNATURE.size)
        if (!peSignature.contentEquals(PE_SIGNATURE)) {
            return report(
                file,
                fileSize,
                extension,
                ArtifactKind.DOS_MZ_IMAGE,
                "MZ",
                "MZ signature found, but no PE signature was found at the declared header offset."
            )
        }

        val coffOffset = peOffset + PE_SIGNATURE.size
        val machineCode = readU16(input, coffOffset)
        val sectionCount = readU16(input, coffOffset + 2L)
        val optionalHeaderSize = readU16(input, coffOffset + 16L)
        val characteristics = readU16(input, coffOffset + 18L)
        val optionalHeaderOffset = coffOffset + COFF_HEADER_SIZE

        val warnings = mutableListOf<String>()
        val optionalHeaderMagic = if (
            optionalHeaderSize >= 2 &&
            optionalHeaderOffset <= fileSize - 2
        ) {
            readU16(input, optionalHeaderOffset)
        } else {
            warnings += "Optional header is missing or truncated."
            null
        }

        if (sectionCount == 0 || sectionCount > MAX_REASONABLE_SECTION_COUNT) {
            warnings += "The PE section count is unusual; the header may be malformed."
        }

        val metadata = PeImageMetadata(
            machineCode = machineCode,
            machineLabel = machineLabel(machineCode),
            optionalHeaderMagic = optionalHeaderMagic,
            optionalHeaderLabel = optionalHeaderLabel(optionalHeaderMagic),
            sectionCount = sectionCount,
            isDll = characteristics and IMAGE_FILE_DLL != 0
        )

        val imageType = if (metadata.isDll) "DLL" else "executable image"
        val optionalLabel = metadata.optionalHeaderLabel ?: "unknown optional-header format"

        return report(
            file = file,
            fileSize = fileSize,
            extension = extension,
            kind = ArtifactKind.PE_IMAGE,
            signature = "MZ/PE",
            evidence = "Valid PE signature; $imageType; ${metadata.machineLabel}; " +
                "$optionalLabel; sections=$sectionCount.",
            peMetadata = metadata,
            warnings = warnings
        )
    }

    private fun report(
        file: File,
        fileSize: Long,
        extension: String?,
        kind: ArtifactKind,
        signature: String?,
        evidence: String,
        peMetadata: PeImageMetadata? = null,
        warnings: List<String> = emptyList()
    ) = BinaryArtifactReport(
        fileName = file.name,
        fileSizeBytes = fileSize,
        extensionHint = extension,
        kind = kind,
        signature = signature,
        evidence = evidence,
        peMetadata = peMetadata,
        warnings = warnings
    )

    private fun detectPrefixKind(prefix: ByteArray): Triple<ArtifactKind, String, String>? {
        return when {
            prefix.startsWith(ELF_SIGNATURE) ->
                Triple(ArtifactKind.ELF_IMAGE, "ELF", "ELF magic bytes found.")
            prefix.startsWith(ZIP_LOCAL_FILE_SIGNATURE) ||
                prefix.startsWith(ZIP_EMPTY_ARCHIVE_SIGNATURE) ||
                prefix.startsWith(ZIP_SPANNED_SIGNATURE) ->
                Triple(ArtifactKind.ZIP_CONTAINER, "PK", "ZIP container signature found.")
            prefix.startsWith(RIFF_SIGNATURE) ->
                Triple(ArtifactKind.RIFF_CONTAINER, "RIFF", "RIFF container signature found.")
            prefix.startsWith(OGG_SIGNATURE) ->
                Triple(ArtifactKind.OGG_STREAM, "OggS", "Ogg stream signature found.")
            prefix.startsWith(PNG_SIGNATURE) ->
                Triple(ArtifactKind.PNG_IMAGE, "PNG", "PNG signature found.")
            prefix.startsWith(JPEG_SIGNATURE) ->
                Triple(ArtifactKind.JPEG_IMAGE, "JPEG", "JPEG start-of-image marker found.")
            else -> null
        }
    }

    private fun readBytes(input: RandomAccessFile, offset: Long, count: Int): ByteArray {
        if (count <= 0 || offset < 0 || offset > input.length() - count) {
            return ByteArray(0)
        }
        val bytes = ByteArray(count)
        input.seek(offset)
        input.readFully(bytes)
        return bytes
    }

    private fun readU16(input: RandomAccessFile, offset: Long): Int {
        val bytes = readBytes(input, offset, 2)
        if (bytes.size != 2) throw IOException("Truncated binary header.")
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(input: RandomAccessFile, offset: Long): Long {
        val bytes = readBytes(input, offset, 4)
        if (bytes.size != 4) throw IOException("Truncated binary header.")
        return (bytes[0].toLong() and 0xFF) or
            ((bytes[1].toLong() and 0xFF) shl 8) or
            ((bytes[2].toLong() and 0xFF) shl 16) or
            ((bytes[3].toLong() and 0xFF) shl 24)
    }

    private fun machineLabel(machine: Int): String = when (machine) {
        0x014C -> "x86"
        0x8664 -> "x86-64"
        0x01C0, 0x01C2 -> "ARM"
        0x01C4 -> "ARMv7"
        0xAA64 -> "ARM64"
        0x0200 -> "Intel Itanium"
        else -> "unknown machine (0x${machine.toString(16).uppercase(Locale.ROOT)})"
    }

    private fun optionalHeaderLabel(magic: Int?): String? = when (magic) {
        0x010B -> "PE32"
        0x020B -> "PE32+"
        0x0107 -> "ROM image"
        null -> null
        else -> "unknown optional-header magic (0x${magic.toString(16)})"
    }

    private fun ByteArray.startsWith(signature: ByteArray): Boolean {
        if (size < signature.size) return false
        return signature.indices.all { this[it] == signature[it] }
    }

    private fun ByteArray.toHexString(): String = take(8)
        .joinToString(" ") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        .ifBlank { "empty" }

    companion object {
        private const val PREFIX_LENGTH = 8
        private const val DOS_HEADER_SIZE = 0x40L
        private const val PE_OFFSET_FIELD = 0x3C
        private const val PE_HEADER_MIN_SIZE = 24L
        private const val COFF_HEADER_SIZE = 20L
        private const val MIN_PE_HEADER_OFFSET = 0x40L
        private const val DEFAULT_MAX_PE_HEADER_OFFSET = 16L * 1024L * 1024L
        private const val MAX_REASONABLE_SECTION_COUNT = 96
        private const val IMAGE_FILE_DLL = 0x2000

        private val MZ_SIGNATURE = byteArrayOf(0x4D, 0x5A)
        private val PE_SIGNATURE = byteArrayOf(0x50, 0x45, 0x00, 0x00)
        private val ELF_SIGNATURE = byteArrayOf(0x7F, 0x45, 0x4C, 0x46)
        private val ZIP_LOCAL_FILE_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val ZIP_EMPTY_ARCHIVE_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
        private val ZIP_SPANNED_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x07, 0x08)
        private val RIFF_SIGNATURE = byteArrayOf(0x52, 0x49, 0x46, 0x46)
        private val OGG_SIGNATURE = byteArrayOf(0x4F, 0x67, 0x67, 0x53)
        private val PNG_SIGNATURE =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    }
}
