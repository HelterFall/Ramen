package io.github.helterfall.ramen.inspection

import java.io.File
import java.io.IOException
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
    val isDll: Boolean,
    val importedLibraries: List<String> = emptyList(),
    val importDirectoryRva: Long? = null,
    val importDirectorySize: Long? = null
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
        val extension = file.extension
            .takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
        return FileRandomAccessSource(file).use { source ->
            inspect(source, file.name, extension)
        }
    }

    /**
     * Inspects an already-open positional source. The caller retains ownership and closes it.
     */
    @Throws(IOException::class)
    fun inspect(
        source: ReadOnlyRandomAccessSource,
        fileName: String,
        extensionHint: String? = null
    ): BinaryArtifactReport {
        val fileSize = source.sizeBytes
        val extension = extensionHint
            ?.removePrefix(".")
            ?.takeIf { it.isNotBlank() }
            ?.lowercase(Locale.ROOT)
        val prefix = readBytes(source, 0L, minOf(PREFIX_LENGTH.toLong(), fileSize).toInt())
        val prefixKind = detectPrefixKind(prefix)
        if (prefixKind != null) {
            return BinaryArtifactReport(
                fileName = fileName,
                fileSizeBytes = fileSize,
                extensionHint = extension,
                kind = prefixKind.first,
                signature = prefixKind.second,
                evidence = prefixKind.third
            )
        }

        if (prefix.startsWith(MZ_SIGNATURE)) {
            return inspectMzImage(source, fileName, fileSize, extension)
        }

        return BinaryArtifactReport(
            fileName = fileName,
            fileSizeBytes = fileSize,
            extensionHint = extension,
            kind = ArtifactKind.UNKNOWN,
            signature = prefix.toHexString(),
            evidence = "No registered binary signature matched. The extension is not used as proof of format."
        )
    }

    private fun inspectMzImage(
        input: ReadOnlyRandomAccessSource,
        fileName: String,
        fileSize: Long,
        extension: String?
    ): BinaryArtifactReport {
        if (fileSize < DOS_HEADER_SIZE) {
            return report(
                fileName,
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
                fileName,
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
                fileName,
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

        val importResult = parseImports(
            input = input,
            fileSize = fileSize,
            optionalHeaderOffset = optionalHeaderOffset,
            optionalHeaderSize = optionalHeaderSize,
            optionalHeaderMagic = optionalHeaderMagic,
            sectionCount = sectionCount
        )
        warnings += importResult.warnings

        val metadata = PeImageMetadata(
            machineCode = machineCode,
            machineLabel = machineLabel(machineCode),
            optionalHeaderMagic = optionalHeaderMagic,
            optionalHeaderLabel = optionalHeaderLabel(optionalHeaderMagic),
            sectionCount = sectionCount,
            isDll = characteristics and IMAGE_FILE_DLL != 0,
            importedLibraries = importResult.libraryNames,
            importDirectoryRva = importResult.directoryRva,
            importDirectorySize = importResult.directorySize
        )

        val imageType = if (metadata.isDll) "DLL" else "executable image"
        val optionalLabel = metadata.optionalHeaderLabel ?: "unknown optional-header format"

        return report(
            fileName = fileName,
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

    private data class SectionMapping(
        val virtualAddress: Long,
        val rawSize: Long,
        val rawPointer: Long
    )

    private data class ImportParseResult(
        val libraryNames: List<String> = emptyList(),
        val directoryRva: Long? = null,
        val directorySize: Long? = null,
        val warnings: List<String> = emptyList()
    )

    /**
     * Reads the PE import directory without loading or executing the image.
     * All RVAs are mapped through validated section headers and all loops are capped.
     */
    private fun parseImports(
        input: ReadOnlyRandomAccessSource,
        fileSize: Long,
        optionalHeaderOffset: Long,
        optionalHeaderSize: Int,
        optionalHeaderMagic: Int?,
        sectionCount: Int
    ): ImportParseResult {
        val warnings = mutableListOf<String>()
        val directoryLayout = when (optionalHeaderMagic) {
            PE32_MAGIC -> ImportDirectoryLayout(
                numberOfDirectoriesOffset = 92L,
                directoriesOffset = 96L
            )
            PE32_PLUS_MAGIC -> ImportDirectoryLayout(
                numberOfDirectoriesOffset = 108L,
                directoriesOffset = 112L
            )
            else -> return ImportParseResult(
                warnings = listOf("Import directory was not inspected: optional-header kind is unknown.")
            )
        }

        val requiredOptionalBytes = directoryLayout.directoriesOffset + (IMPORT_DIRECTORY_INDEX + 1L) * 8L
        if (optionalHeaderSize.toLong() < requiredOptionalBytes ||
            optionalHeaderOffset < 0 ||
            optionalHeaderOffset > fileSize - optionalHeaderSize
        ) {
            return ImportParseResult(
                warnings = listOf("Import directory was not inspected: optional header is too short or truncated.")
            )
        }

        val numberOfDirectories = readU32(
            input,
            optionalHeaderOffset + directoryLayout.numberOfDirectoriesOffset
        )
        if (numberOfDirectories <= IMPORT_DIRECTORY_INDEX) {
            return ImportParseResult()
        }

        val importEntryOffset = optionalHeaderOffset +
            directoryLayout.directoriesOffset + IMPORT_DIRECTORY_INDEX * 8L
        val directoryRva = readU32(input, importEntryOffset)
        val directorySize = readU32(input, importEntryOffset + 4L)
        if (directoryRva == 0L && directorySize == 0L) return ImportParseResult()
        if (directoryRva == 0L || directorySize < IMPORT_DESCRIPTOR_SIZE) {
            return ImportParseResult(
                directoryRva = directoryRva,
                directorySize = directorySize,
                warnings = listOf("Import directory has an invalid RVA or size.")
            )
        }

        val sizeOfHeaders = readU32(input, optionalHeaderOffset + SIZE_OF_HEADERS_OFFSET)
        val sectionTableOffset = optionalHeaderOffset + optionalHeaderSize
        if (sectionCount <= 0 || sectionCount > MAX_REASONABLE_SECTION_COUNT) {
            return ImportParseResult(
                directoryRva = directoryRva,
                directorySize = directorySize,
                warnings = listOf("Import directory was not inspected: section count is invalid.")
            )
        }

        val sectionTableBytes = sectionCount.toLong() * SECTION_HEADER_SIZE
        if (sectionTableOffset > fileSize - sectionTableBytes) {
            return ImportParseResult(
                directoryRva = directoryRva,
                directorySize = directorySize,
                warnings = listOf("Import directory was not inspected: section table is truncated.")
            )
        }

        val sections = ArrayList<SectionMapping>(sectionCount)
        repeat(sectionCount) { index ->
            val offset = sectionTableOffset + index.toLong() * SECTION_HEADER_SIZE
            val virtualAddress = readU32(input, offset + SECTION_VIRTUAL_ADDRESS_OFFSET)
            val rawSize = readU32(input, offset + SECTION_RAW_SIZE_OFFSET)
            val rawPointer = readU32(input, offset + SECTION_RAW_POINTER_OFFSET)
            if (rawSize > 0 && rawPointer <= fileSize && rawSize <= fileSize - rawPointer) {
                sections += SectionMapping(virtualAddress, rawSize, rawPointer)
            } else if (rawSize > 0) {
                warnings += "Section $index has raw data outside the file bounds."
            }
        }

        fun mapRva(rva: Long): Long? {
            if (rva < sizeOfHeaders && rva < fileSize) return rva
            for (section in sections) {
                if (rva >= section.virtualAddress) {
                    val delta = rva - section.virtualAddress
                    if (delta < section.rawSize) {
                        val mapped = section.rawPointer + delta
                        if (mapped >= 0 && mapped < fileSize) return mapped
                    }
                }
            }
            return null
        }

        val maxDescriptorsFromSize = (directorySize / IMPORT_DESCRIPTOR_SIZE)
            .coerceAtMost(MAX_IMPORT_DESCRIPTORS.toLong())
            .toInt()
        if (maxDescriptorsFromSize == 0) {
            return ImportParseResult(
                directoryRva = directoryRva,
                directorySize = directorySize,
                warnings = warnings + "Import directory contains no complete descriptors."
            )
        }
        if (directorySize / IMPORT_DESCRIPTOR_SIZE > MAX_IMPORT_DESCRIPTORS) {
            warnings += "Import directory descriptor scan was capped at $MAX_IMPORT_DESCRIPTORS entries."
        }

        val libraries = linkedSetOf<String>()
        var terminated = false
        for (index in 0 until maxDescriptorsFromSize) {
            val descriptorRva = directoryRva + index.toLong() * IMPORT_DESCRIPTOR_SIZE
            val descriptorOffset = mapRva(descriptorRva)
            if (descriptorOffset == null || descriptorOffset > fileSize - IMPORT_DESCRIPTOR_SIZE) {
                warnings += "Import descriptor $index points outside mapped file data."
                break
            }

            val descriptor = readBytes(input, descriptorOffset, IMPORT_DESCRIPTOR_SIZE.toInt())
            if (descriptor.size != IMPORT_DESCRIPTOR_SIZE.toInt()) {
                warnings += "Import descriptor $index is truncated."
                break
            }
            if (descriptor.all { it == 0.toByte() }) {
                terminated = true
                break
            }

            val nameRva = readU32(input, descriptorOffset + IMPORT_NAME_RVA_OFFSET)
            if (nameRva == 0L) {
                warnings += "Import descriptor $index has no DLL name RVA."
                continue
            }
            val nameOffset = mapRva(nameRva)
            if (nameOffset == null) {
                warnings += "Import descriptor $index DLL name points outside mapped file data."
                continue
            }

            val dllName = readAsciiCString(input, nameOffset, fileSize)
            if (dllName == null) {
                warnings += "Import descriptor $index DLL name is unterminated or invalid."
            } else {
                libraries += dllName
            }
        }

        if (!terminated && maxDescriptorsFromSize < MAX_IMPORT_DESCRIPTORS) {
            warnings += "Import directory has no terminating empty descriptor within its declared size."
        }

        return ImportParseResult(
            libraryNames = libraries.toList(),
            directoryRva = directoryRva,
            directorySize = directorySize,
            warnings = warnings
        )
    }

    private data class ImportDirectoryLayout(
        val numberOfDirectoriesOffset: Long,
        val directoriesOffset: Long
    )

    private fun readAsciiCString(
        input: ReadOnlyRandomAccessSource,
        offset: Long,
        fileSize: Long
    ): String? {
        if (offset < 0 || offset >= fileSize) return null
        val bytes = ArrayList<Byte>(32)
        for (index in 0 until MAX_DLL_NAME_BYTES) {
            val position = offset + index
            if (position >= fileSize) return null
            val value = readBytes(input, position, 1).firstOrNull() ?: return null
            if (value == 0.toByte()) {
                if (bytes.isEmpty()) return null
                if (bytes.any { (it.toInt() and 0xFF) !in 0x20..0x7E }) return null
                return bytes.toByteArray().toString(Charsets.US_ASCII)
            }
            bytes += value
        }
        return null
    }

    private fun report(
        fileName: String,
        fileSize: Long,
        extension: String?,
        kind: ArtifactKind,
        signature: String?,
        evidence: String,
        peMetadata: PeImageMetadata? = null,
        warnings: List<String> = emptyList()
    ) = BinaryArtifactReport(
        fileName = fileName,
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

    private fun readBytes(
        source: ReadOnlyRandomAccessSource,
        offset: Long,
        count: Int
    ): ByteArray {
        if (count <= 0 || offset < 0) return ByteArray(0)
        val sourceSize = source.sizeBytes
        if (offset > sourceSize || count.toLong() > sourceSize - offset) {
            return ByteArray(0)
        }
        return ByteArray(count).also { bytes ->
            source.readFullyAt(offset, bytes)
        }
    }

    private fun readU16(source: ReadOnlyRandomAccessSource, offset: Long): Int {
        val bytes = readBytes(source, offset, 2)
        if (bytes.size != 2) throw IOException("Truncated binary header.")
        return (bytes[0].toInt() and 0xFF) or
            ((bytes[1].toInt() and 0xFF) shl 8)
    }

    private fun readU32(source: ReadOnlyRandomAccessSource, offset: Long): Long {
        val bytes = readBytes(source, offset, 4)
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
        private const val PE32_MAGIC = 0x010B
        private const val PE32_PLUS_MAGIC = 0x020B
        private const val IMPORT_DIRECTORY_INDEX = 1L
        private const val IMPORT_DESCRIPTOR_SIZE = 20L
        private const val IMPORT_NAME_RVA_OFFSET = 12L
        private const val SIZE_OF_HEADERS_OFFSET = 60L
        private const val SECTION_HEADER_SIZE = 40L
        private const val SECTION_VIRTUAL_ADDRESS_OFFSET = 12L
        private const val SECTION_RAW_SIZE_OFFSET = 16L
        private const val SECTION_RAW_POINTER_OFFSET = 20L
        private const val MAX_IMPORT_DESCRIPTORS = 512
        private const val MAX_DLL_NAME_BYTES = 260

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
