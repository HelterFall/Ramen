package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.EOFException
import kotlin.math.abs
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class GpkImageFormat {
    JPEG,
    BMP,
    PNG
}

data class GpkImageResource(
    val name: String,
    val format: GpkImageFormat,
    val blockOffset: Long,
    val blockSizeBytes: Long,
    val payloadOffset: Long,
    val payloadSizeBytes: Long,
    val widthPixels: Int,
    val heightPixels: Int,
    val packType: Int,
    /** Pixel-data offset relative to the image payload; populated for BMP resources. */
    val bmpPixelDataOffsetBytes: Long? = null,
    val bmpBitsPerPixel: Int? = null,
    val bmpCompression: Long? = null,
    /** Palette start and number of RGBQUAD entries, when the BMP is indexed. */
    val bmpPaletteOffsetBytes: Long? = null,
    val bmpPaletteColorCount: Int? = null,
    /** True when BMP rows are stored top-to-bottom rather than bottom-to-top. */
    val bmpTopDown: Boolean? = null,
    /** Black Cyc PACKTYPE=1/3 uses zero-run decoding and previous-row XOR. */
    val dwqPackedBmp: Boolean = false,
    /** True when the DWQ header declares an alpha mask. */
    val hasAlpha: Boolean = false,
    /** Set when this individual block failed validation; other index rows remain usable. */
    val validationError: String? = null,
) {
    val isValid: Boolean get() = validationError == null
}

data class GpkGtbArchive(
    val entries: List<GpkImageResource>
)

data class VpkAudioResource(
    val name: String,
    val blockOffset: Long,
    val blockSizeBytes: Long,
    val oggPayloadOffset: Long,
    val oggPayloadSizeBytes: Long,
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val decodedPcmSizeBytes: Long,
    val oggPageCount: Int
)

data class VpkVtbArchive(
    val entries: List<VpkAudioResource>,
    val hasTerminalIndexSentinel: Boolean
)

/**
 * Read-only parser for the observed paired .gtb/.gpk image-resource format.
 *
 * The Black Cyc GTB layout used by GARbro stores a signed little-endian Int32
 * entry count at offset 0, followed by count signed Int32 name offsets, count
 * unsigned 32-bit GPK offsets, and a NUL-terminated string pool. Name offsets
 * are relative to the start of that pool. No GTB XOR transform is applied.
 * Archive blocks have
 * a fixed 64-byte service preamble followed by JPEG, BMP, or a supported raw DIB
 * payload. Known text preambles are validated when recognized; opaque preambles
 * are skipped and the payload signature/header is used to determine the format.
 *
 * Payloads are not decoded or copied into memory. Returned offsets can be used
 * by a caller to stream an individual image to another destination.
 */
class GpkGtbArchiveParser {

    @Throws(IOException::class)
    fun parse(
        indexSource: ReadOnlyRandomAccessSource,
        archiveSource: ReadOnlyRandomAccessSource
    ): GpkGtbArchive = parseArchive(indexSource, archiveSource)

    @Throws(IOException::class)
    private fun parseArchive(
        indexSource: ReadOnlyRandomAccessSource,
        archiveSource: ReadOnlyRandomAccessSource
    ): GpkGtbArchive {
        if (indexSource.sizeBytes < UINT32_BYTES) {
            malformed("GTB index is shorter than its entry-count field.")
        }

        val entryCount = readI32(indexSource, 0L)
        if (entryCount <= 0) {
            malformed("GTB signed Int32 entry count is non-positive.")
        }

        val countLong = entryCount.toLong()
        val nameTableOffset = UINT32_BYTES
        val archiveTableOffset = UINT32_BYTES + countLong * UINT32_BYTES
        val stringPoolOffset = archiveTableOffset + countLong * UINT32_BYTES

        // This is a physical index-file bound, not an arbitrary record-count cap.
        if (stringPoolOffset >= indexSource.sizeBytes) {
            malformed("GTB name/offset tables extend beyond the index file.")
        }
        val stringPoolSize = indexSource.sizeBytes - stringPoolOffset

        val names = HashSet<String>()
        val entries = ArrayList<GpkImageResource>()
        var previousArchiveOffset: Long? = null

        for (index in 0 until entryCount) {
            val nameOffset = readI32(
                indexSource,
                nameTableOffset + index.toLong() * UINT32_BYTES
            ).toLong()

            val archiveOffset = readU32(
                indexSource,
                archiveTableOffset + index.toLong() * UINT32_BYTES
            )
            val nextArchiveOffset = if (index + 1 < entryCount) {
                readU32(
                    indexSource,
                    archiveTableOffset + (index + 1L) * UINT32_BYTES
                )
            } else {
                archiveSource.sizeBytes
            }

            var name = try {
                decodeName(
                    source = indexSource,
                    poolOffset = stringPoolOffset,
                    poolSize = stringPoolSize,
                    nameOffset = nameOffset,
                    resourceIndex = index
                )
            } catch (_: Exception) {
                fallbackName(index)
            }

            if (!names.add(name)) {
                name = uniqueFallbackName(index, names)
                names.add(name)
            }

            val blockSize = if (nextArchiveOffset > archiveOffset) {
                nextArchiveOffset - archiveOffset
            } else {
                0L
            }

            val rangeError = when {
                previousArchiveOffset != null && archiveOffset <= previousArchiveOffset ->
                    "GPK offsets are not strictly increasing at index entry $index."
                archiveOffset >= archiveSource.sizeBytes ->
                    "GPK offset points outside the archive."
                nextArchiveOffset <= archiveOffset ->
                    "The next GPK offset is not greater than the current offset."
                nextArchiveOffset > archiveSource.sizeBytes ->
                    "The next GPK offset exceeds the archive length."
                blockSize < GPK_BLOCK_HEADER_BYTES.toLong() ->
                    "GPK resource block is shorter than its 64-byte DWQ header."
                else -> null
            }

            if (rangeError != null) {
                entries += invalidResource(
                    name = name,
                    blockOffset = archiveOffset,
                    blockSize = blockSize,
                    archiveSize = archiveSource.sizeBytes,
                    message = rangeError
                )
                previousArchiveOffset = archiveOffset
                continue
            }

            val parsed = try {
                parseGpkResource(
                    source = archiveSource,
                    name = name,
                    blockOffset = archiveOffset,
                    blockSize = blockSize
                )
            } catch (error: Exception) {
                invalidResource(
                    name = name,
                    blockOffset = archiveOffset,
                    blockSize = blockSize,
                    archiveSize = archiveSource.sizeBytes,
                    message = error.message ?: "The resource block is malformed."
                )
            }

            entries += parsed
            previousArchiveOffset = archiveOffset
        }

        return GpkGtbArchive(entries)
    }

    private fun invalidResource(
        name: String,
        blockOffset: Long,
        blockSize: Long,
        archiveSize: Long,
        message: String
    ): GpkImageResource {
        val safeBlockOffset = blockOffset.coerceIn(0L, archiveSize)
        val safeBlockSize = minOf(
            blockSize.coerceAtLeast(0L),
            (archiveSize - safeBlockOffset).coerceAtLeast(0L)
        )
        val payloadOffset = safeBlockOffset +
            minOf(GPK_BLOCK_HEADER_BYTES.toLong(), safeBlockSize)
        val payloadSize = (safeBlockSize - GPK_BLOCK_HEADER_BYTES).coerceAtLeast(0L)

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.BMP,
            blockOffset = safeBlockOffset,
            blockSizeBytes = safeBlockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = 0,
            heightPixels = 0,
            packType = -1,
            validationError = message
        )
    }

    private fun parseGpkResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        blockOffset: Long,
        blockSize: Long
    ): GpkImageResource {
        if (blockSize < GPK_BLOCK_HEADER_BYTES.toLong()) {
            malformed("GPK resource block is shorter than its 64-byte DWQ header.")
        }

        val header = ByteArray(GPK_BLOCK_HEADER_BYTES)
        source.readFullyAt(blockOffset, header)
        val payloadOffset = blockOffset + GPK_BLOCK_HEADER_BYTES
        val availablePayloadSize = blockSize - GPK_BLOCK_HEADER_BYTES
        val dwqHeader = parseDwqHeader(header)

        if (dwqHeader != null) {
            val declaredWidth = readU32(header, 0x24)
            val declaredHeight = readU32(header, 0x28)

            return when (dwqHeader.packType) {
                0 -> {
                    val bmp = parseBmpResource(
                        source = source,
                        name = name,
                        blockOffset = blockOffset,
                        blockSize = blockSize,
                        payloadOffset = payloadOffset,
                        payloadSize = availablePayloadSize,
                        packType = 0,
                        useDwqSequentialPixelOffset = true,
                        hasAlpha = dwqHeader.hasAlpha
                    )
                    bmp
                }

                1 -> parsePackedBmpResource(
                    source = source,
                    name = name,
                    blockOffset = blockOffset,
                    blockSize = blockSize,
                    payloadOffset = payloadOffset,
                    payloadSize = availablePayloadSize,
                    packType = 1,
                    expectedWidth = null,
                    expectedHeight = null,
                    hasAlpha = dwqHeader.hasAlpha
                )

                2 -> {
                    val packedSize = readDwqPackedSize(header, availablePayloadSize)
                    parseBmpResource(
                        source = source,
                        name = name,
                        blockOffset = blockOffset,
                        blockSize = blockSize,
                        payloadOffset = payloadOffset,
                        payloadSize = packedSize,
                        packType = 2,
                        useDwqSequentialPixelOffset = true,
                        expectedWidth = declaredWidth,
                        expectedHeight = declaredHeight,
                        hasAlpha = dwqHeader.hasAlpha
                    )
                }

                3 -> {
                    val packedSize = readDwqPackedSize(header, availablePayloadSize)
                    parsePackedBmpResource(
                        source = source,
                        name = name,
                        blockOffset = blockOffset,
                        blockSize = blockSize,
                        payloadOffset = payloadOffset,
                        payloadSize = packedSize,
                        packType = 3,
                        expectedWidth = declaredWidth,
                        expectedHeight = declaredHeight,
                        hasAlpha = true
                    )
                }

                5 -> parseJpegResource(
                    source = source,
                    name = name,
                    header = header,
                    headerFormat = GpkImageFormat.JPEG,
                    blockOffset = blockOffset,
                    blockSize = blockSize,
                    payloadOffset = payloadOffset,
                    payloadSize = availablePayloadSize,
                    packType = 5,
                    expectedWidth = declaredWidth,
                    expectedHeight = declaredHeight,
                    hasAlpha = dwqHeader.hasAlpha
                )

                7 -> {
                    val packedSize = readDwqPackedSize(header, availablePayloadSize)
                    parseJpegResource(
                        source = source,
                        name = name,
                        header = header,
                        headerFormat = GpkImageFormat.JPEG,
                        blockOffset = blockOffset,
                        blockSize = blockSize,
                        payloadOffset = payloadOffset,
                        payloadSize = packedSize,
                        packType = 7,
                        expectedWidth = declaredWidth,
                        expectedHeight = declaredHeight,
                        hasAlpha = true
                    )
                }

                8 -> parsePngResource(
                    source = source,
                    name = name,
                    blockOffset = blockOffset,
                    blockSize = blockSize,
                    payloadOffset = payloadOffset,
                    payloadSize = availablePayloadSize,
                    width = declaredWidth,
                    height = declaredHeight,
                    hasAlpha = dwqHeader.hasAlpha
                )

                else -> malformed("Unsupported Black Cyc DWQ PACKTYPE=${dwqHeader.packType}.")
            }
        }

        // Retain the earlier fallback for observed opaque service preambles.
        val headerFormat = parseGpkBlockFormat(header)
        val format = detectGpkPayloadFormat(
            source = source,
            payloadOffset = payloadOffset,
            payloadSize = availablePayloadSize
        )
        if (headerFormat != null && headerFormat != format) {
            malformed("GPK preamble format does not match its image payload.")
        }

        return when (format) {
            GpkImageFormat.JPEG -> parseJpegResource(
                source = source,
                name = name,
                header = header,
                headerFormat = headerFormat,
                blockOffset = blockOffset,
                blockSize = blockSize,
                payloadOffset = payloadOffset,
                payloadSize = availablePayloadSize,
                packType = JPEG_PACK_TYPE_NUMBER
            )
            GpkImageFormat.BMP -> parseBmpResource(
                source = source,
                name = name,
                blockOffset = blockOffset,
                blockSize = blockSize,
                payloadOffset = payloadOffset,
                payloadSize = availablePayloadSize,
                packType = BMP_PACK_TYPE_NUMBER
            )
            GpkImageFormat.PNG -> parsePngResource(
                source = source,
                name = name,
                blockOffset = blockOffset,
                blockSize = blockSize,
                payloadOffset = payloadOffset,
                payloadSize = availablePayloadSize,
                width = 0L,
                height = 0L,
                hasAlpha = false
            )
        }
    }

    private data class DwqHeaderMetadata(
        val packType: Int,
        val hasAlpha: Boolean,
        val specialIfHeader: Boolean
    )

    private fun parseDwqHeader(header: ByteArray): DwqHeaderMetadata? {
        val typeText = String(header, 0x30, 0x10, ASCII).trimEnd(' ')
        val match = PACKTYPE_PATTERN.matchEntire(typeText) ?: return null
        val packType = match.groupValues[1].toIntOrNull()
            ?: malformed("DWQ PACKTYPE value is outside the supported integer range.")
        val specialIfHeader = asciiAt(header, 0, "IF PACKTYPE==")

        if (specialIfHeader) {
            if (!asciiAt(header, 0x0D, "0 ") ||
                !asciiAt(header, 0x2C, "BMP ") ||
                packType !in 0..1
            ) {
                malformed("Invalid special Black Cyc IF PACKTYPE header.")
            }
        } else if (packType == 1) {
            malformed("PACKTYPE=1 requires the special IF PACKTYPE DWQ header.")
        }

        if (packType !in setOf(0, 1, 2, 3, 5, 7, 8)) {
            malformed("Unsupported Black Cyc DWQ PACKTYPE=$packType.")
        }

        return DwqHeaderMetadata(
            packType = packType,
            hasAlpha = match.groupValues[2].isNotEmpty() || packType == 3 || packType == 7,
            specialIfHeader = specialIfHeader
        )
    }

    private fun readDwqPackedSize(header: ByteArray, availablePayloadSize: Long): Long {
        val declaredSize = readI32(header, 0x20)
        if (declaredSize < 0) {
            malformed("DWQ packed image size is negative.")
        }
        val size = declaredSize.toLong()
        if (size <= 0L || size > availablePayloadSize) {
            malformed("DWQ packed image size exceeds its indexed GPK block.")
        }
        return size
    }

    private fun asciiAt(source: ByteArray, offset: Int, expected: String): Boolean {
        val bytes = expected.toByteArray(ASCII)
        if (offset < 0 || offset > source.size - bytes.size) return false
        return bytes.indices.all { source[offset + it] == bytes[it] }
    }

    /**
     * GTB names are display metadata, not physical resource locators. Decode valid
     * Shift-JIS where possible, but do not reject a sound index because a label is opaque.
     * The offset table and NUL/length bounds remain structural invariants.
     */
    private fun decodeName(
        source: ReadOnlyRandomAccessSource,
        poolOffset: Long,
        poolSize: Long,
        nameOffset: Long,
        resourceIndex: Int
    ): String {
        if (nameOffset < 0L || nameOffset >= poolSize) malformed("GTB name offset points outside the string pool.")
        if (nameOffset > 0L && readU8(source, poolOffset + nameOffset - 1L) != 0) {
            malformed("GTB name offset does not point to the start of a string.")
        }
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8 * 1024)
        var cursor = nameOffset
        var terminated = false
        while (cursor < poolSize) {
            val count = minOf(chunk.size.toLong(), poolSize - cursor).toInt()
            source.readFullyAt(poolOffset + cursor, chunk, 0, count)
            val end = (0 until count).firstOrNull { chunk[it] == 0.toByte() }
            if (end != null) {
                if (out.size().toLong() + end > Int.MAX_VALUE) malformed("GTB resource name exceeds the JVM string range.")
                out.write(chunk, 0, end)
                terminated = true
                break
            }
            if (out.size().toLong() + count > Int.MAX_VALUE) malformed("GTB resource name exceeds the JVM string range.")
            out.write(chunk, 0, count)
            cursor += count.toLong()
        }
        if (!terminated) malformed("GTB resource name is not NUL-terminated inside the string pool.")
        if (out.size() == 0) return fallbackName(resourceIndex)
        val decoded = try {
            SHIFT_JIS.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            return fallbackName(resourceIndex)
        }
        return if (decoded.isBlank() || decoded.any { it.isISOControl() }) fallbackName(resourceIndex) else decoded
    }

    private fun uniqueFallbackName(resourceIndex: Int, existingNames: Set<String>): String {
        val base = fallbackName(resourceIndex)
        if (base !in existingNames) return base
        var suffix = 1
        var candidate = base + "_" + suffix
        while (candidate in existingNames) {
            suffix += 1
            candidate = base + "_" + suffix
        }
        return candidate
    }

    private fun fallbackName(resourceIndex: Int): String =
        "fallback_resource_$resourceIndex"

    /**
     * Returns a format only for the known textual preamble. Unknown preambles are
     * allowed: the payload signature at byte 64 is the authoritative image type.
     */
    private fun parseGpkBlockFormat(header: ByteArray): GpkImageFormat? {
        val jpegTag = "JPEG".toByteArray(ASCII)
        val bmpTag = "BMP".toByteArray(ASCII)
        return when {
            header.copyOfRange(0, jpegTag.size).contentEquals(jpegTag) &&
                header.copyOfRange(jpegTag.size, GPK_TAG_BYTES).all { it == SPACE_BYTE } -> {
                if (header.copyOfRange(44, 64).contentEquals(JPEG_PACK_TYPE)) {
                    GpkImageFormat.JPEG
                } else {
                    null
                }
            }
            header.copyOfRange(0, bmpTag.size).contentEquals(bmpTag) &&
                header.copyOfRange(bmpTag.size, GPK_TAG_BYTES).all { it == SPACE_BYTE } -> {
                if (header.copyOfRange(44, 64).contentEquals(BMP_PACK_TYPE)) {
                    GpkImageFormat.BMP
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun detectGpkPayloadFormat(
        source: ReadOnlyRandomAccessSource,
        payloadOffset: Long,
        payloadSize: Long
    ): GpkImageFormat {
        if (payloadSize < 2L) {
            malformed("GPK image payload is too short to contain a JPEG or BMP marker.")
        }
        val prefix = ByteArray(minOf(3L, payloadSize).toInt())
        source.readFullyAt(payloadOffset, prefix)
        return when {
            prefix.size >= JPEG_SIGNATURE.size &&
                prefix.copyOfRange(0, JPEG_SIGNATURE.size).contentEquals(JPEG_SIGNATURE) ->
                GpkImageFormat.JPEG
            prefix[0] == 'B'.code.toByte() && prefix[1] == 'M'.code.toByte() ->
                GpkImageFormat.BMP
            isRawDibPayload(source, payloadOffset, payloadSize) ->
                GpkImageFormat.BMP
            else -> malformed(
                "The bytes after the 64-byte GPK preamble are not a recognized JPEG, BMP, or raw DIB payload."
            )
        }
    }

    /**
     * Some GPK blocks contain a BITMAPINFOHEADER and pixel data without the
     * 14-byte BITMAPFILEHEADER. Recognize supported BI_RGB 24/32-bit layouts
     * and standard BI_RLE4/BI_RLE8 indexed layouts; no LZ or XOR guesswork.
     */
    private fun isRawDibPayload(
        source: ReadOnlyRandomAccessSource,
        payloadOffset: Long,
        payloadSize: Long
    ): Boolean {
        if (payloadSize < BMP_INFO_HEADER_BYTES) return false
        val info = ByteArray(BMP_INFO_HEADER_BYTES.toInt())
        source.readFullyAt(payloadOffset, info)
        val dibHeaderSize = readU32(info, 0)
        if (dibHeaderSize !in BMP_DIB_HEADER_SIZES || dibHeaderSize > payloadSize) return false

        val width = readI32(info, 4)
        val signedHeight = readI32(info, 8)
        val height = kotlin.math.abs(signedHeight.toLong())
        val planes = readU16(info, 12)
        val bitsPerPixel = readU16(info, 14)
        val compression = readU32(info, 16)
        val supportedPixelFormat = when (compression) {
            BMP_BI_RGB -> bitsPerPixel in BMP_BITS_PER_PIXEL
            BMP_BI_RLE8 -> bitsPerPixel == 8 && signedHeight > 0
            BMP_BI_RLE4 -> bitsPerPixel == 4 && signedHeight > 0
            else -> false
        }
        return width in 1..Int.MAX_VALUE &&
            height in 1L..Int.MAX_VALUE.toLong() &&
            planes == 1 &&
            supportedPixelFormat
    }

    private fun parseJpegResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        header: ByteArray,
        headerFormat: GpkImageFormat?,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long,
        packType: Int,
        expectedWidth: Long? = null,
        expectedHeight: Long? = null,
        hasAlpha: Boolean = false
    ): GpkImageResource {
        if (payloadSize < JPEG_SIGNATURE.size.toLong()) {
            malformed("JPEG GPK payload is too short.")
        }
        val signature = ByteArray(JPEG_SIGNATURE.size)
        source.readFullyAt(payloadOffset, signature)
        if (!signature.contentEquals(JPEG_SIGNATURE)) {
            malformed("JPEG GPK payload does not start with the JPEG marker.")
        }

        val dimensions = when {
            expectedWidth != null && expectedHeight != null -> {
                validateDimensions(expectedWidth, expectedHeight)
                expectedWidth.toInt() to expectedHeight.toInt()
            }
            headerFormat == GpkImageFormat.JPEG -> {
                val width = readU32(header, 0x24)
                val height = readU32(header, 0x28)
                validateDimensions(width, height)
                width.toInt() to height.toInt()
            }
            else -> parseJpegDimensions(source, payloadOffset, payloadSize)
        }

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.JPEG,
            blockOffset = blockOffset,
            blockSizeBytes = blockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = dimensions.first,
            heightPixels = dimensions.second,
            packType = packType,
            hasAlpha = hasAlpha
        )
    }

    private fun parseJpegDimensions(
        source: ReadOnlyRandomAccessSource,
        payloadOffset: Long,
        payloadSize: Long
    ): Pair<Int, Int> {
        if (payloadSize < JPEG_SIGNATURE.size.toLong()) malformed("JPEG GPK payload is too short to contain an image header.")
        val signature = ByteArray(JPEG_SIGNATURE.size)
        source.readFullyAt(payloadOffset, signature)
        if (!signature.contentEquals(JPEG_SIGNATURE)) malformed("JPEG GPK payload does not start with the JPEG marker.")
        var cursor = 2L
        while (cursor < payloadSize) {
            if (readU8(source, payloadOffset + cursor) != 0xFF) malformed("JPEG marker stream is malformed before its frame header.")
            while (cursor < payloadSize && readU8(source, payloadOffset + cursor) == 0xFF) cursor++
            if (cursor >= payloadSize) break
            val marker = readU8(source, payloadOffset + cursor)
            cursor++
            when {
                marker == 0xD9 || marker == 0xDA -> break
                marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7 -> continue
                marker == 0x00 -> malformed("JPEG contains an invalid stuffed marker before SOS.")
                else -> {
                    if (payloadSize - cursor < 2L) malformed("JPEG marker segment length is truncated.")
                    val segmentLength = (readU8(source, payloadOffset + cursor) shl 8) or readU8(source, payloadOffset + cursor + 1L)
                    if (segmentLength < 2) malformed("JPEG contains an invalid marker-segment length.")
                    val segmentEnd = cursor + segmentLength.toLong()
                    if (segmentEnd < cursor || segmentEnd > payloadSize) malformed("JPEG marker segment extends beyond its indexed GPK payload.")
                    if (marker in JPEG_START_OF_FRAME_MARKERS) {
                        if (segmentLength < 8) malformed("JPEG frame header is too short.")
                        val dataOffset = payloadOffset + cursor + 2L
                        val components = readU8(source, dataOffset + 5L)
                        if (components == 0 || segmentLength < 8 + components * 3) malformed("JPEG frame header has an invalid component table.")
                        val height = (readU8(source, dataOffset + 1L) shl 8) or readU8(source, dataOffset + 2L)
                        val width = (readU8(source, dataOffset + 3L) shl 8) or readU8(source, dataOffset + 4L)
                        validateDimensions(width.toLong(), height.toLong())
                        return width to height
                    }
                    cursor = segmentEnd
                }
            }
        }
        malformed("JPEG frame dimensions were not found inside the indexed payload.")
    }

    private fun parseBmpResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long,
        packType: Int,
        useDwqSequentialPixelOffset: Boolean = false,
        expectedWidth: Long? = null,
        expectedHeight: Long? = null,
        hasAlpha: Boolean = false
    ): GpkImageResource {
        if (payloadSize < BMP_INFO_HEADER_BYTES) {
            malformed("BMP GPK payload is shorter than a BITMAPINFOHEADER.")
        }

        val firstTwo = ByteArray(2)
        source.readFullyAt(payloadOffset, firstTwo)
        val hasFileHeader = firstTwo[0] == 'B'.code.toByte() &&
            firstTwo[1] == 'M'.code.toByte()
        val dibOffset = if (hasFileHeader) BMP_FILE_HEADER_BYTES.toLong() else 0L
        if (payloadSize - dibOffset < BMP_INFO_HEADER_BYTES) {
            malformed("BMP GPK payload has no complete BITMAPINFOHEADER.")
        }

        val infoHeader = ByteArray(BMP_INFO_HEADER_BYTES.toInt())
        source.readFullyAt(payloadOffset + dibOffset, infoHeader)
        val dibHeaderSize = readU32(infoHeader, 0)
        if (dibHeaderSize !in BMP_DIB_HEADER_SIZES ||
            dibHeaderSize > payloadSize - dibOffset
        ) {
            malformed("BMP BITMAPINFOHEADER size is not recognized or exceeds the payload.")
        }

        val width = readI32(infoHeader, 4)
        val signedHeight = readI32(infoHeader, 8)
        val absoluteHeight = kotlin.math.abs(signedHeight.toLong())
        if (width <= 0 ||
            absoluteHeight <= 0L ||
            (expectedWidth != null && width.toLong() != expectedWidth) ||
            (expectedHeight != null && absoluteHeight != expectedHeight)
        ) {
            malformed("BMP dimensions are invalid or disagree with the DWQ header.")
        }

        val planes = readU16(infoHeader, 12)
        val bitsPerPixel = readU16(infoHeader, 14)
        val compression = readU32(infoHeader, 16)
        if (planes != 1 || bitsPerPixel !in BMP_BITS_PER_PIXEL ||
            (useDwqSequentialPixelOffset && bitsPerPixel !in setOf(8, 16, 24, 32))
        ) {
            malformed("BMP planes or bit depth are not recognized for this DWQ PACKTYPE.")
        }

        when (compression) {
            BMP_BI_RGB -> Unit
            BMP_BI_RLE8 -> if (bitsPerPixel != 8 || signedHeight < 0) {
                malformed("BI_RLE8 requires an 8-bit bottom-up BMP.")
            }
            BMP_BI_RLE4 -> if (bitsPerPixel != 4 || signedHeight < 0) {
                malformed("BI_RLE4 requires a 4-bit bottom-up BMP.")
            }
            else -> Unit
        }

        val clrUsed = readU32(infoHeader, 32)
        val paletteEntries = when {
            compression == BMP_BI_RLE8 -> paletteEntryCount(clrUsed, 8)
            compression == BMP_BI_RLE4 -> paletteEntryCount(clrUsed, 4)
            compression == BMP_BI_RGB && bitsPerPixel in BMP_INDEXED_BITS_PER_PIXEL ->
                paletteEntryCount(clrUsed, bitsPerPixel)
            else -> 0
        }
        val paletteOffset = dibOffset + dibHeaderSize
        val paletteBytes = paletteEntries.toLong() * BMP_RGB_QUAD_BYTES
        val paletteEnd = paletteOffset + paletteBytes
        if (paletteEnd > payloadSize) {
            malformed("BMP color table extends beyond the indexed GPK payload.")
        }

        val declaredPixelOffset = if (hasFileHeader) {
            readU32(source, payloadOffset + 10L)
        } else {
            paletteEnd
        }
        // Black Cyc's ordinary DWQ BMP reader ignores bi.offBits and reads rows
        // immediately after the 0x36-byte header and any palette. Match that
        // behavior for PACKTYPE=0/2; keep the normal BMP offset for opaque blocks.
        val pixelDataOffset = when {
            useDwqSequentialPixelOffset -> paletteEnd
            declaredPixelOffset >= paletteEnd &&
                declaredPixelOffset < payloadSize -> declaredPixelOffset
            compression == BMP_BI_RGB || compression in BMP_SUPPORTED_RLE_COMPRESSION ->
                paletteEnd
            else -> malformed("BMP file header contains an invalid pixel-data offset.")
        }

        if (compression == BMP_BI_RGB) {
            val pixelSize = (bitsPerPixel + 7L) / 8L
            val rowStride = if (useDwqSequentialPixelOffset) {
                ((width.toLong() * pixelSize + 3L) / 4L) * 4L
            } else {
                ((width.toLong() * bitsPerPixel.toLong() + 31L) / 32L) * 4L
            }
            if (rowStride <= 0L || absoluteHeight > Long.MAX_VALUE / rowStride) {
                malformed("BMP pixel row byte count overflows the supported range.")
            }
            val requiredPixelBytes = rowStride * absoluteHeight
            if (requiredPixelBytes > payloadSize - pixelDataOffset) {
                malformed("BMP pixel rows extend beyond the indexed GPK payload.")
            }
        } else if (compression in BMP_SUPPORTED_RLE_COMPRESSION) {
            if (paletteEntries == 0 || pixelDataOffset >= payloadSize) {
                malformed("BMP RLE image has no palette or compressed pixel data.")
            }
        } else if (pixelDataOffset >= payloadSize) {
            malformed("BMP pixel data begins outside the indexed GPK payload.")
        }

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.BMP,
            blockOffset = blockOffset,
            blockSizeBytes = blockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = width,
            heightPixels = absoluteHeight.toInt(),
            packType = packType,
            bmpPixelDataOffsetBytes = pixelDataOffset,
            bmpBitsPerPixel = bitsPerPixel,
            bmpCompression = compression,
            bmpPaletteOffsetBytes = paletteOffset.takeIf { paletteEntries > 0 },
            bmpPaletteColorCount = paletteEntries.takeIf { paletteEntries > 0 },
            bmpTopDown = signedHeight < 0,
            hasAlpha = hasAlpha
        )
    }

    private fun parsePackedBmpResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long,
        packType: Int,
        expectedWidth: Long?,
        expectedHeight: Long?,
        hasAlpha: Boolean
    ): GpkImageResource {
        if (payloadSize < BMP_HEADER_SIZE_BYTES.toLong()) {
            malformed("Packed DWQ BMP header is truncated.")
        }

        val bmpHeader = ByteArray(BMP_HEADER_SIZE_BYTES)
        source.readFullyAt(payloadOffset, bmpHeader)
        val width = readI32(bmpHeader, 0x12)
        val signedHeight = readI32(bmpHeader, 0x16)
        val height = kotlin.math.abs(signedHeight.toLong())
        val bitsPerPixel = readU16(bmpHeader, 0x1C)
        if (width <= 0 || height <= 0L ||
            (expectedWidth != null && width.toLong() != expectedWidth) ||
            (expectedHeight != null && height != expectedHeight)
        ) {
            malformed("Packed DWQ BMP dimensions are invalid or disagree with its DWQ header.")
        }
        if (bitsPerPixel !in setOf(8, 16, 24, 32)) {
            malformed("Packed DWQ BMP bit depth is not supported.")
        }

        val dataOffset = readU32(bmpHeader, 0x0A)
        if (dataOffset < BMP_HEADER_SIZE_BYTES.toLong() || dataOffset > payloadSize) {
            malformed("Packed DWQ BMP pixel offset is outside the indexed resource.")
        }

        val paletteEntries = if (bitsPerPixel == 8) {
            val declared = readI32(bmpHeader, 0x2E)
            val count = if (declared == 0) 256 else declared
            if (count !in 1..256) malformed("Packed DWQ BMP palette size is invalid.")
            val paletteEnd = BMP_HEADER_SIZE_BYTES.toLong() + count.toLong() * 4L
            if (paletteEnd > dataOffset || paletteEnd > payloadSize) {
                malformed("Packed DWQ BMP palette extends beyond its pixel offset.")
            }
            count
        } else {
            0
        }

        val pixelCount = width.toLong() * height
        if (pixelCount > Int.MAX_VALUE.toLong()) {
            malformed("Packed DWQ BMP pixel plane exceeds the JVM array range.")
        }

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.BMP,
            blockOffset = blockOffset,
            blockSizeBytes = blockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = width,
            heightPixels = height.toInt(),
            packType = packType,
            bmpPixelDataOffsetBytes = dataOffset,
            bmpBitsPerPixel = bitsPerPixel,
            bmpCompression = -1L,
            bmpPaletteOffsetBytes = if (paletteEntries > 0) BMP_HEADER_SIZE_BYTES.toLong() else null,
            bmpPaletteColorCount = paletteEntries.takeIf { it > 0 },
            bmpTopDown = signedHeight < 0,
            dwqPackedBmp = true,
            hasAlpha = hasAlpha
        )
    }

    private fun parsePngResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long,
        width: Long,
        height: Long,
        hasAlpha: Boolean
    ): GpkImageResource {
        if (payloadSize < PNG_SIGNATURE.size.toLong()) {
            malformed("PNG GPK payload is too short.")
        }
        val signature = ByteArray(PNG_SIGNATURE.size)
        source.readFullyAt(payloadOffset, signature)
        if (!signature.contentEquals(PNG_SIGNATURE)) {
            malformed("PNG GPK payload does not start with its PNG signature.")
        }
        val actualWidth = if (width == 0L) readU32(source, payloadOffset + 16L) else width
        val actualHeight = if (height == 0L) readU32(source, payloadOffset + 20L) else height
        validateDimensions(actualWidth, actualHeight)

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.PNG,
            blockOffset = blockOffset,
            blockSizeBytes = blockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = actualWidth.toInt(),
            heightPixels = actualHeight.toInt(),
            packType = 8,
            hasAlpha = hasAlpha
        )
    }

    private fun paletteEntryCount(clrUsed: Long, bitsPerPixel: Int): Int {
        val maximum = 1 shl bitsPerPixel
        val entries = if (clrUsed == 0L) maximum.toLong() else clrUsed
        if (entries !in 1L..maximum.toLong()) {
            malformed("BMP color table entry count is outside the bit-depth range.")
        }
        return entries.toInt()
    }

    private fun validateDimensions(width: Long, height: Long) {
        if (width <= 0L || height <= 0L ||
            width > Int.MAX_VALUE.toLong() ||
            height > Int.MAX_VALUE.toLong()
        ) {
            malformed("JPEG dimensions are outside the accepted range.")
        }
    }

    companion object {
        private const val UINT32_BYTES = 4L
        private const val TABLES_PER_ENTRY = 2L
        private const val GPK_BLOCK_HEADER_BYTES = 64
        private const val GPK_TAG_BYTES = 32
        private const val BMP_HEADER_SIZE_BYTES = 0x36
        private val PACKTYPE_PATTERN = Regex("^PACKTYPE=([0-9]+)(A?)$")
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private val JPEG_START_OF_FRAME_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3,
            0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB,
            0xCD, 0xCE, 0xCF
        )
        private const val BMP_FILE_HEADER_BYTES = 14
        private const val BMP_INFO_HEADER_BYTES = 40L
        private const val BMP_BI_RGB = 0L
        private const val BMP_BI_RLE8 = 1L
        private const val BMP_BI_RLE4 = 2L
        private const val BMP_RGB_QUAD_BYTES = 4L
        private val BMP_SUPPORTED_RLE_COMPRESSION = setOf(BMP_BI_RLE8, BMP_BI_RLE4)
        private val BMP_INDEXED_BITS_PER_PIXEL = setOf(1, 4, 8)
        private val BMP_DIB_HEADER_SIZES = setOf(40L, 52L, 56L, 108L, 124L)
        private val BMP_RAW_BITS_PER_PIXEL = setOf(24, 32)
        private const val JPEG_PACK_TYPE_NUMBER = 5
        private const val BMP_PACK_TYPE_NUMBER = 0
        private val ASCII: Charset = Charsets.US_ASCII
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
        private val SPACE_BYTE = ' '.code.toByte()
        private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val JPEG_PACK_TYPE = "    PACKTYPE=5      ".toByteArray(ASCII)
        private val BMP_PACK_TYPE = "    PACKTYPE=0      ".toByteArray(ASCII)
        private val BMP_BITS_PER_PIXEL = setOf(1, 4, 8, 16, 24, 32)
    }
}

/**
 * Read-only parser for the observed paired .vtb/.vpk audio-resource format.
 *
 * Each .vtb row is an 8-byte ASCII resource name plus a little-endian 32-bit
 * archive offset. One sample includes a final row whose offset equals archive
 * EOF; that optional terminal row is excluded from the resource list.
 *
 * Each .vpk block has a 64-byte text preamble, a 44-byte RIFF/WAVE PCM-style
 * descriptor, and an Ogg stream beginning at byte 108. The descriptor's data
 * length describes PCM output, not the compressed Ogg payload's physical size.
 * Ogg page boundaries and sequence metadata are checked; page CRCs are not.
 */
class VpkVtbArchiveParser {

    @Throws(IOException::class)
    fun parse(
        indexSource: ReadOnlyRandomAccessSource,
        archiveSource: ReadOnlyRandomAccessSource
    ): VpkVtbArchive {
        if (indexSource.sizeBytes == 0L || indexSource.sizeBytes % VTB_RECORD_BYTES != 0L) {
            malformed("VTB size is not a whole number of 12-byte index records.")
        }
        val recordCountLong = indexSource.sizeBytes / VTB_RECORD_BYTES
        if (recordCountLong == 0L || recordCountLong > Int.MAX_VALUE.toLong()) {
            malformed("VTB record count is outside the supported in-memory index range.")
        }
        val recordCount = recordCountLong.toInt()

        // Read the fixed-width name bytes and offsets independently. Names are
        // display metadata; they must not prevent us from validating a sound index.
        // In particular, the terminal EOF row is not a resource and its name field
        // is not required to contain text.
        val rawNames = ArrayList<ByteArray>(recordCount)
        val offsets = LongArray(recordCount)
        for (index in 0 until recordCount) {
            val record = ByteArray(VTB_RECORD_BYTES.toInt())
            indexSource.readFullyAt(index * VTB_RECORD_BYTES, record)
            rawNames += record.copyOfRange(0, VTB_NAME_BYTES)
            offsets[index] = readU32(record, VTB_NAME_BYTES)
        }

        val hasTerminalSentinel = offsets.last() == archiveSource.sizeBytes
        val resourceCount = if (hasTerminalSentinel) recordCount - 1 else recordCount
        if (resourceCount <= 0 || offsets[0] != 0L) {
            malformed("VTB has no resource blocks or its first offset is not zero.")
        }

        val names = ArrayList<String>(resourceCount)
        val uniqueNameBytes = HashSet<String>(resourceCount)
        for (index in 0 until resourceCount) {
            val rawName = rawNames[index]
            val rawKey = rawName.toHexString()
            if (!uniqueNameBytes.add(rawKey)) {
                malformed("VTB contains a duplicate resource name.")
            }
            names += decodeFixedName(rawName)
        }

        for (index in 1 until recordCount) {
            if (offsets[index] <= offsets[index - 1]) {
                malformed("VTB resource offsets are not strictly increasing.")
            }
        }
        for (index in 0 until resourceCount) {
            if (offsets[index] >= archiveSource.sizeBytes) {
                malformed("VTB resource offset points outside the VPK archive.")
            }
        }

        val entries = ArrayList<VpkAudioResource>(resourceCount)
        for (index in 0 until resourceCount) {
            val blockOffset = offsets[index]
            val blockEnd = if (index + 1 < recordCount) {
                offsets[index + 1]
            } else {
                archiveSource.sizeBytes
            }
            if (blockEnd <= blockOffset || blockEnd - blockOffset < VPK_MINIMUM_BLOCK_BYTES) {
                malformed("VPK audio block is shorter than its 108-byte prefix.")
            }

            val preamble = ByteArray(VPK_TEXT_PREAMBLE.size)
            archiveSource.readFullyAt(blockOffset, preamble)
            if (!preamble.contentEquals(VPK_TEXT_PREAMBLE)) {
                malformed("VPK block has an unexpected 64-byte text preamble.")
            }

            val descriptorOffset = blockOffset + VPK_TEXT_PREAMBLE.size
            val descriptor = ByteArray(VPK_WAVE_DESCRIPTOR_BYTES)
            archiveSource.readFullyAt(descriptorOffset, descriptor)
            val audio = parseWaveDescriptor(descriptor)
            val oggOffset = descriptorOffset + VPK_WAVE_DESCRIPTOR_BYTES
            val oggSize = blockEnd - oggOffset
            if (oggSize <= 0L) {
                malformed("VPK block has no Ogg payload after its 108-byte prefix.")
            }
            val pageCount = validateOggPages(
                source = archiveSource,
                startOffset = oggOffset,
                endOffset = blockEnd
            )

            entries += VpkAudioResource(
                name = names[index],
                blockOffset = blockOffset,
                blockSizeBytes = blockEnd - blockOffset,
                oggPayloadOffset = oggOffset,
                oggPayloadSizeBytes = oggSize,
                sampleRateHz = audio.sampleRateHz,
                channels = audio.channels,
                bitsPerSample = audio.bitsPerSample,
                decodedPcmSizeBytes = audio.decodedPcmSizeBytes,
                oggPageCount = pageCount
            )
        }

        return VpkVtbArchive(
            entries = entries,
            hasTerminalIndexSentinel = hasTerminalSentinel
        )
    }

    private data class WaveDescriptor(
        val sampleRateHz: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val decodedPcmSizeBytes: Long
    )

    private fun parseWaveDescriptor(bytes: ByteArray): WaveDescriptor {
        if (!bytes.copyOfRange(0, 4).contentEquals(RIFF_MARKER) ||
            !bytes.copyOfRange(8, 12).contentEquals(WAVE_MARKER) ||
            !bytes.copyOfRange(12, 16).contentEquals(FMT_MARKER) ||
            !bytes.copyOfRange(36, 40).contentEquals(DATA_MARKER)
        ) {
            malformed("VPK audio descriptor is not RIFF/WAVE with a data chunk.")
        }

        val riffSize = readU32(bytes, 4)
        val fmtSize = readU32(bytes, 16)
        val audioFormat = readU16(bytes, 20)
        val channels = readU16(bytes, 22)
        val sampleRate = readU32(bytes, 24)
        val byteRate = readU32(bytes, 28)
        val blockAlign = readU16(bytes, 32)
        val bitsPerSample = readU16(bytes, 34)
        val decodedPcmSize = readU32(bytes, 40)

        if (fmtSize != 16L || audioFormat != PCM_FORMAT ||
            channels !in MIN_CHANNELS..MAX_CHANNELS ||
            sampleRate !in MIN_SAMPLE_RATE_HZ.toLong()..MAX_SAMPLE_RATE_HZ.toLong() ||
            bitsPerSample !in PCM_BITS_PER_SAMPLE ||
            blockAlign != channels * (bitsPerSample / BITS_PER_BYTE) ||
            byteRate != sampleRate * blockAlign.toLong() ||
            decodedPcmSize == 0L ||
            riffSize != RIFF_WAVE_FIXED_SIZE + decodedPcmSize
        ) {
            malformed("VPK RIFF/WAVE PCM descriptor contains inconsistent fields.")
        }

        return WaveDescriptor(
            sampleRateHz = sampleRate.toInt(),
            channels = channels,
            bitsPerSample = bitsPerSample,
            decodedPcmSizeBytes = decodedPcmSize
        )
    }

    private fun validateOggPages(
        source: ReadOnlyRandomAccessSource,
        startOffset: Long,
        endOffset: Long
    ): Int {
        var cursor = startOffset
        var pageCount = 0L
        var expectedSequence = 0L
        var streamSerial: Long? = null
        var lastHeaderType = 0

        while (cursor < endOffset) {
            if (endOffset - cursor < OGG_FIXED_HEADER_BYTES) {
                malformed("Ogg final page header is truncated.")
            }

            val pageHeader = ByteArray(OGG_FIXED_HEADER_BYTES)
            source.readFullyAt(cursor, pageHeader)
            if (!pageHeader.copyOfRange(0, 4).contentEquals(OGG_CAPTURE_PATTERN) ||
                pageHeader[4] != 0.toByte()
            ) {
                malformed("VPK Ogg payload contains an invalid Ogg page header.")
            }
            val headerType = pageHeader[5].toInt() and 0xFF
            if (headerType and OGG_RESERVED_HEADER_FLAGS != 0) {
                malformed("Ogg page uses reserved header flags.")
            }
            if (pageCount == 0L) {
                if (headerType and OGG_BOS_FLAG == 0) {
                    malformed("The first Ogg page does not set the beginning-of-stream flag.")
                }
            } else if (headerType and OGG_BOS_FLAG != 0) {
                malformed("A non-first Ogg page unexpectedly sets the beginning-of-stream flag.")
            }

            val serial = readU32(pageHeader, 14)
            val sequence = readU32(pageHeader, 18)
            if (sequence != expectedSequence ||
                (streamSerial != null && streamSerial != serial)
            ) {
                malformed("Ogg page serial number or sequence is not continuous.")
            }
            if (streamSerial == null) {
                streamSerial = serial
            }

            val segmentCount = pageHeader[26].toInt() and 0xFF
            val lacing = ByteArray(segmentCount)
            if (segmentCount > 0) {
                source.readFullyAt(cursor + OGG_FIXED_HEADER_BYTES, lacing)
            }
            var bodySize = 0L
            for (segment in lacing) {
                bodySize += (segment.toInt() and 0xFF).toLong()
            }
            val pageSize = OGG_FIXED_HEADER_BYTES + segmentCount.toLong() + bodySize
            if (pageSize > endOffset - cursor) {
                malformed("Ogg page extends beyond its indexed VPK resource boundary.")
            }

            cursor += pageSize
            pageCount += 1
            expectedSequence += 1L
            lastHeaderType = headerType
            if (headerType and OGG_EOS_FLAG != 0 && cursor != endOffset) {
                malformed("Ogg end-of-stream flag appears before the end of the indexed resource.")
            }
        }

        if (pageCount == 0L || cursor != endOffset || lastHeaderType and OGG_EOS_FLAG == 0) {
            malformed("Ogg payload does not end with a complete end-of-stream page.")
        }
        if (pageCount > Int.MAX_VALUE.toLong()) malformed("Ogg page count exceeds the in-memory metadata range.")
        return pageCount.toInt()
    }

    private fun decodeFixedName(bytes: ByteArray): String {
        // Names are fixed-width fields, but different game builds may NUL-pad
        // shorter ASCII names or use opaque/non-ASCII identifiers. Preserve a
        // readable ASCII prefix when possible; otherwise expose a stable hex ID.
        var contentLength = bytes.size
        val nulIndex = bytes.indexOf(0)
        if (nulIndex >= 0) {
            val paddingIsValid = (nulIndex until bytes.size).all { index ->
                (bytes[index].toInt() and 0xFF) == 0 || bytes[index] == SPACE_BYTE
            }
            if (paddingIsValid) {
                contentLength = nulIndex
            } else {
                contentLength = 0
            }
        }
        while (contentLength > 0 && bytes[contentLength - 1] == SPACE_BYTE) {
            contentLength--
        }
        val printable = contentLength > 0 && (0 until contentLength).all { index ->
            (bytes[index].toInt() and 0xFF) in MIN_PRINTABLE_ASCII..MAX_PRINTABLE_ASCII
        }
        return if (printable) {
            bytes.copyOfRange(0, contentLength).toString(ASCII)
        } else {
            "resource-${bytes.toHexString()}"
        }
    }

    companion object {
        private const val VTB_RECORD_BYTES = 12L
        private const val VTB_NAME_BYTES = 8
        private const val VPK_MINIMUM_BLOCK_BYTES = 109L
        private const val VPK_WAVE_DESCRIPTOR_BYTES = 44
        private const val PCM_FORMAT = 1
        private const val MIN_CHANNELS = 1
        private const val MAX_CHANNELS = 8
        private const val MIN_SAMPLE_RATE_HZ = 8_000
        private const val MAX_SAMPLE_RATE_HZ = 384_000
        private const val BITS_PER_BYTE = 8
        private const val RIFF_WAVE_FIXED_SIZE = 36L
        private const val OGG_FIXED_HEADER_BYTES = 27
        private const val OGG_BOS_FLAG = 0x02
        private const val OGG_EOS_FLAG = 0x04
        private const val OGG_RESERVED_HEADER_FLAGS = 0xF8
        private const val MIN_PRINTABLE_ASCII = 0x20
        private const val MAX_PRINTABLE_ASCII = 0x7E
        private val SPACE_BYTE = ' '.code.toByte()
        private val ASCII: Charset = Charsets.US_ASCII
        private val VPK_TEXT_PREAMBLE =
            "IF PACKTYPE==2  CUT THIS 108BYTETHEN REMAKE OGG PACKTYPE=2      ".toByteArray(ASCII)
        private val RIFF_MARKER = "RIFF".toByteArray(ASCII)
        private val WAVE_MARKER = "WAVE".toByteArray(ASCII)
        private val FMT_MARKER = "fmt ".toByteArray(ASCII)
        private val DATA_MARKER = "data".toByteArray(ASCII)
        private val OGG_CAPTURE_PATTERN = "OggS".toByteArray(ASCII)
        private val PCM_BITS_PER_SAMPLE = setOf(8, 16, 24, 32)
    }
}

private fun ByteArray.toHexString(): String {
    val digits = "0123456789ABCDEF"
    return buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xFF
            append(digits[value ushr 4])
            append(digits[value and 0x0F])
        }
    }
}

private fun readU8(source: ReadOnlyRandomAccessSource, offset: Long): Int {
    val byte = ByteArray(1)
    source.readFullyAt(offset, byte)
    return byte[0].toInt() and 0xFF
}

private fun readI32(source: ReadOnlyRandomAccessSource, offset: Long): Int {
    val bytes = ByteArray(4)
    source.readFullyAt(offset, bytes)
    return readI32(bytes, 0)
}

private fun readU32(source: ReadOnlyRandomAccessSource, offset: Long): Long {
    val bytes = ByteArray(4)
    source.readFullyAt(offset, bytes)
    return readU32(bytes, 0)
}

private fun readU16(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readU32(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

private fun readI32(bytes: ByteArray, offset: Int): Int =
    readU32(bytes, offset).toInt()

private fun malformed(message: String): Nothing = throw IOException(message)



object ResourceArchiveParsers {


    private const val DWQ_HEADER_SIZE = 0x40
    private const val BMP_HEADER_SIZE = 0x36
    private const val MAX_WGQ_FILES = 10_000
    private const val MAX_STANDALONE_WGQ_BYTES = 128L * 1024L * 1024L
    private val IGNORED_SCRIPT_CONFIGURATION_EXTENSIONS = setOf("xtx")

    private val PACKTYPE_PATTERN = Regex("^PACKTYPE=([0-9]+)(A?)$")

    /** Lists .wgq resources and deliberately excludes .xtx script sidecars. */
    @Throws(IOException::class)
    fun findBlackCycWgqFiles(directory: File): List<File> {
        if (!directory.exists()) return emptyList()
        if (!directory.isDirectory || java.nio.file.Files.isSymbolicLink(directory.toPath())) {
            throw IOException("The Black Cyc wgq resource path is not a regular directory.")
        }
        return directory.walkTopDown()
            .maxDepth(12)
            .filter { file ->
                val extension = file.extension.lowercase()
                file.isFile &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
                    extension !in IGNORED_SCRIPT_CONFIGURATION_EXTENSIONS &&
                    extension == "wgq"
            }
            .take(MAX_WGQ_FILES)
            .sortedBy { it.relativeTo(directory).invariantSeparatorsPath.lowercase() }
            .toList()
    }

    /**
     * Performs a bounded header-only probe before a standalone WGQ is treated as a
     * Black Cyc packed-BMP image. This intentionally does not decode pixels: full
     * payload validation still happens in [decodeBlackCycWgqFile] at display time.
     *
     * Other engines also use the .wgq extension (including audio resources), so the
     * suffix alone is not sufficient evidence that a file belongs in an image catalog.
     */
    fun isBlackCycWgqImageCandidate(file: File): Boolean {
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return false
        }

        val fileSize = file.length()
        val headerSize = DWQ_HEADER_SIZE + BMP_HEADER_SIZE
        if (fileSize < headerSize.toLong() || fileSize > Int.MAX_VALUE.toLong()) return false

        return runCatching {
            val header = ByteArray(headerSize)
            FileInputStream(file).use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) return@runCatching false
                    if (read > 0) offset += read
                }
            }

            val packTypeText = String(
                header,
                0x30,
                0x10,
                Charsets.US_ASCII
            ).trimEnd(' ')
            val match = PACKTYPE_PATTERN.matchEntire(packTypeText)
                ?: return@runCatching false
            val packType = match.groupValues[1].toIntOrNull()
                ?: return@runCatching false

            val packedSize = when (packType) {
                1 -> {
                    if (!asciiAt(header, 0, "IF PACKTYPE==") ||
                        !asciiAt(header, 0x0D, "0 ") ||
                        !asciiAt(header, 0x2C, "BMP ")
                    ) {
                        return@runCatching false
                    }
                    fileSize - DWQ_HEADER_SIZE.toLong()
                }

                3 -> readI32Le(header, 0x20)
                    .toLong()
                    .takeIf { it >= 0L }
                    ?: return@runCatching false

                else -> return@runCatching false
            }

            if (packedSize < BMP_HEADER_SIZE.toLong() ||
                packedSize > fileSize - DWQ_HEADER_SIZE.toLong()
            ) {
                return@runCatching false
            }

            val bmpOffset = DWQ_HEADER_SIZE
            val dataOffset = readU32Le(header, bmpOffset + 0x0A)
            val width = readI32Le(header, bmpOffset + 0x12)
            val signedHeight = readI32Le(header, bmpOffset + 0x16)
            val height = kotlin.math.abs(signedHeight.toLong())
            val bytesPerPixel = when (readU16Le(header, bmpOffset + 0x1C)) {
                8 -> 1L
                16 -> 2L
                24 -> 3L
                32 -> 4L
                else -> return@runCatching false
            }

            if (width <= 0 ||
                height <= 0L ||
                height > Int.MAX_VALUE.toLong() ||
                dataOffset < BMP_HEADER_SIZE.toLong() ||
                dataOffset > packedSize
            ) {
                return@runCatching false
            }

            val pixelCount = width.toLong() * height
            val stride = width.toLong() * bytesPerPixel
            if (pixelCount > Int.MAX_VALUE.toLong() ||
                stride > Int.MAX_VALUE.toLong() ||
                height > Int.MAX_VALUE.toLong() / stride
            ) {
                return@runCatching false
            }

            if (packType == 3) {
                val declaredWidth = readU32Le(header, 0x24)
                val declaredHeight = readU32Le(header, 0x28)
                if (declaredWidth != width.toLong() || declaredHeight != height) {
                    return@runCatching false
                }
            }

            true
        }.getOrDefault(false)
    }

    /** Reads one complete DWQ block from a standalone .wgq resource. */
    @Throws(IOException::class)
    fun decodeBlackCycWgqFile(
        file: File,
        maxDecodedPixels: Long = Long.MAX_VALUE,
        maxPackedBlockBytes: Long = MAX_STANDALONE_WGQ_BYTES
    ): DecodedDwqBitmap {
        if (!file.isFile || java.nio.file.Files.isSymbolicLink(file.toPath())) {
            throw IOException("The WGQ image resource is not a regular file.")
        }
        if (maxDecodedPixels <= 0L) throw IOException("Invalid decoded-pixel limit.")
        if (maxPackedBlockBytes <= 0L) throw IOException("Invalid packed-block byte limit.")
        val length = file.length()
        if (length < DWQ_HEADER_SIZE.toLong() + BMP_HEADER_SIZE.toLong() ||
            length > minOf(MAX_STANDALONE_WGQ_BYTES, maxPackedBlockBytes) ||
            length > Int.MAX_VALUE.toLong()
        ) {
            throw IOException("The WGQ image resource exceeds the configured block-size budget or supported buffer range.")
        }

        // Inspect just the DWQ/BMP headers before loading the complete archive block.
        val headerSize = (DWQ_HEADER_SIZE + BMP_HEADER_SIZE).coerceAtMost(length.toInt())
        val header = ByteArray(headerSize)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) throw EOFException("Truncated WGQ image header.")
                if (read > 0) offset += read
            }
        }
        val packTypeText = String(header, 0x30, 0x10, Charsets.US_ASCII).trimEnd(' ')
        val packType = PACKTYPE_PATTERN.matchEntire(packTypeText)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: throw IOException("Invalid WGQ PACKTYPE header.")
        val width: Long
        val height: Long
        when (packType) {
            1 -> {
                width = readI32Le(header, DWQ_HEADER_SIZE + 0x12).toLong()
                height = abs(readI32Le(header, DWQ_HEADER_SIZE + 0x16).toLong())
            }
            3 -> {
                width = readU32Le(header, 0x24)
                height = readU32Le(header, 0x28)
            }
            else -> throw IOException("Standalone WGQ PACKTYPE=$packType is not a supported packed BMP.")
        }
        if (width <= 0L || height <= 0L || width > Int.MAX_VALUE.toLong() ||
            height > Int.MAX_VALUE.toLong() || width > maxDecodedPixels / height
        ) {
            throw IOException("WGQ image dimensions exceed the configured decoded-pixel budget.")
        }

        val bytes = ByteArray(length.toInt())
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) throw EOFException("Truncated WGQ image resource.")
                if (read > 0) offset += read
            }
        }
        return decodeBlackCycDwqPackedBmp(bytes, maxDecodedPixels)
    }

    data class DecodedDwqBitmap(
        val width: Int,
        val height: Int,
        val packType: Int,
        /** Pixels are packed as 0xAARRGGBB for Bitmap.Config.ARGB_8888. */
        val argbPixels: IntArray
    )

    /**
     * Decodes an SHS stream into exactly [expectedOutputSize] bytes.
     *
     * The output size must be obtained from validated enclosing-format metadata.
     * Truncated input, invalid back-references, and impossible lengths are rejected.
     */
    @Throws(IOException::class)
    fun decompressShs(
        input: ByteArray,
        expectedOutputSize: Int,
    ): ByteArray {
        require(expectedOutputSize >= 0) { "expectedOutputSize must not be negative." }

        if (expectedOutputSize == 0) return ByteArray(0)

        val output = ByteArray(expectedOutputSize)
        var sourceOffset = 0
        var destinationOffset = 0

        fun readU8(): Int {
            if (sourceOffset >= input.size) {
                throw EOFException(
                    "Truncated SHS stream at offset " + sourceOffset + "."
                )
            }
            return input[sourceOffset++].toInt() and 0xFF
        }

        fun readU16BigEndian(): Long {
            val high = readU8()
            val low = readU8()
            return (high.toLong() shl 8) or low.toLong()
        }

        fun readU32BigEndian(): Long {
            val a = readU8()
            val b = readU8()
            val c = readU8()
            val d = readU8()
            return (a.toLong() shl 24) or
                (b.toLong() shl 16) or
                (c.toLong() shl 8) or
                d.toLong()
        }

        while (destinationOffset < expectedOutputSize) {
            val control = readU8()

            if (control < 0x20) {
                val literalCount = when (control) {
                    0x1D -> readU8().toLong() + 0x1E
                    0x1E -> readU16BigEndian() + 0x11E
                    0x1F -> readU32BigEndian()
                    else -> control.toLong() + 1
                }

                val copyCount = minOf(
                    literalCount,
                    (expectedOutputSize - destinationOffset).toLong()
                ).toInt()

                if (copyCount <= 0 || sourceOffset > input.size - copyCount) {
                    throw EOFException("Truncated or invalid SHS literal sequence.")
                }

                input.copyInto(
                    destination = output,
                    destinationOffset = destinationOffset,
                    startIndex = sourceOffset,
                    endIndex = sourceOffset + copyCount
                )

                sourceOffset += copyCount
                destinationOffset += copyCount
                continue
            }

            val offset: Long
            var count: Long

            if ((control and 0x80) == 0) {
                when (control and 0x60) {
                    0x20 -> {
                        offset = ((control shr 2) and 0x07).toLong()
                        count = (control and 0x03).toLong()
                    }

                    0x40 -> {
                        offset = readU8().toLong()
                        count = (control and 0x1F).toLong() + 4
                    }

                    else -> {
                        offset = readU8().toLong() or
                            ((control and 0x1F).toLong() shl 8)

                        val extension = readU8()
                        count = when (extension) {
                            0xFE -> readU16BigEndian() + 0x102
                            0xFF -> readU32BigEndian()
                            else -> extension.toLong() + 4
                        }
                    }
                }
            } else {
                count = ((control shr 5) and 0x03).toLong()
                offset = (
                    ((control and 0x1F) shl 8) or readU8()
                ).toLong()
            }

            count += 3
            val distanceLong = offset + 1

            if (distanceLong > Int.MAX_VALUE) {
                throw IOException("SHS back-reference distance is out of range.")
            }

            val distance = distanceLong.toInt()
            val copyCount = minOf(
                count,
                (expectedOutputSize - destinationOffset).toLong()
            ).toInt()

            if (distance <= 0 || distance > destinationOffset || copyCount <= 0) {
                throw IOException(
                    "Invalid SHS back-reference: distance=" + distance +
                        ", outputOffset=" + destinationOffset + "."
                )
            }

            // Copy byte-by-byte because source and destination ranges may overlap.
            repeat(copyCount) { index ->
                output[destinationOffset + index] =
                    output[destinationOffset + index - distance]
            }

            destinationOffset += copyCount
        }

        return output
    }

    /**
     * Decodes Black Cyc DWQ entries using PACKTYPE=1 or PACKTYPE=3.
     *
     * [dwq] must contain exactly one extracted GPK entry, including the DWQ's
     * 0x40-byte resource header. The decoded pixels are returned in ARGB order.
     *
     * PACKTYPE=0/2 (uncompressed BMP), 5/7 (JPEG), and 8 (PNG) are intentionally
     * left to their respective decoders.
     */
    @Throws(IOException::class)
    fun decodeBlackCycDwqPackedBmp(
        dwq: ByteArray,
        maxDecodedPixels: Long = Long.MAX_VALUE
    ): DecodedDwqBitmap {
        if (maxDecodedPixels <= 0L) throw IOException("Invalid decoded-pixel limit.")
        if (dwq.size < DWQ_HEADER_SIZE) {
            throw EOFException("DWQ header is truncated.")
        }

        val packTypeText = String(
            dwq,
            0x30,
            0x10,
            Charsets.US_ASCII
        ).trimEnd(' ')

        val match = PACKTYPE_PATTERN.matchEntire(packTypeText)
            ?: throw IOException("Invalid DWQ PACKTYPE header.")

        val packType = match.groupValues[1].toIntOrNull()
            ?: throw IOException("Invalid DWQ PACKTYPE value.")

        val packedSize = when (packType) {
            1 -> {
                if (!asciiAt(dwq, 0, "IF PACKTYPE==") ||
                    !asciiAt(dwq, 0x0D, "0 ") ||
                    !asciiAt(dwq, 0x2C, "BMP ")
                ) {
                    throw IOException("Invalid special PACKTYPE=1 DWQ header.")
                }
                dwq.size - DWQ_HEADER_SIZE
            }

            3 -> {
                val size = readI32Le(dwq, 0x20)
                if (size < 0) {
                    throw IOException("Negative DWQ packed image size.")
                }
                size
            }

            else -> {
                throw IOException(
                    "PACKTYPE=" + packType + " is not a packed BMP."
                )
            }
        }

        if (packedSize > dwq.size - DWQ_HEADER_SIZE) {
            throw EOFException("DWQ packed image exceeds the extracted entry.")
        }

        val imageStart = DWQ_HEADER_SIZE
        val imageEnd = imageStart + packedSize

        val image = decodePackedBmpSegment(
            source = dwq,
            start = imageStart,
            endExclusive = imageEnd,
            maxDecodedPixels = maxDecodedPixels
        )

        if (packType == 3) {
            val declaredWidth = readU32Le(dwq, 0x24)
            val declaredHeight = readU32Le(dwq, 0x28)

            if (declaredWidth != image.width.toLong() ||
                declaredHeight != image.height.toLong()
            ) {
                throw IOException("DWQ and embedded BMP dimensions do not match.")
            }
        }

        val argbPixels = image.toArgb()

        // For PACKTYPE=3 the mask follows the packed image segment when present.
        if (packType == 3 && imageEnd < dwq.size) {
            val mask = decodePackedBmpSegment(
                source = dwq,
                start = imageEnd,
                endExclusive = dwq.size,
                maxDecodedPixels = maxDecodedPixels
            )

            if (mask.bitsPerPixel != 8 ||
                mask.width != image.width ||
                mask.height != image.height
            ) {
                throw IOException("Invalid DWQ alpha mask dimensions or bit depth.")
            }

            for (index in argbPixels.indices) {
                val paletteIndex = mask.pixels[index].toInt() and 0xFF
                val paletteColor = mask.paletteArgb.getOrNull(paletteIndex)

                val alpha = if (paletteColor != null) {
                    val red = (paletteColor ushr 16) and 0xFF
                    val green = (paletteColor ushr 8) and 0xFF
                    val blue = paletteColor and 0xFF
                    // CPicture::LoadMask stores rgbquad[index].rgbRed as alpha.
                    red
                } else {
                    // GARbro leaves an out-of-range palette index unchanged.
                    paletteIndex
                }

                argbPixels[index] =
                    (argbPixels[index] and 0x00FFFFFF) or (alpha shl 24)
            }
        }

        return DecodedDwqBitmap(
            width = image.width,
            height = image.height,
            packType = packType,
            argbPixels = argbPixels
        )
    }

    private data class PackedBmpPlane(
        val width: Int,
        val height: Int,
        val bitsPerPixel: Int,
        val pixels: ByteArray,
        val paletteArgb: IntArray
    )

    /**
     * Black Cyc's PACKBMP decoder:
     * - BMP header and palette are stored before the compressed pixel stream.
     * - A zero byte is followed by a zero-run length.
     * - Each decoded scanline is XORed with the previously decoded scanline.
     */
    @Throws(IOException::class)
    private fun decodePackedBmpSegment(
        source: ByteArray,
        start: Int,
        endExclusive: Int,
        maxDecodedPixels: Long
    ): PackedBmpPlane {
        if (start < 0 ||
            endExclusive > source.size ||
            endExclusive - start < BMP_HEADER_SIZE
        ) {
            throw EOFException("Truncated packed BMP segment.")
        }

        val width = readI32Le(source, start + 0x12)
        val signedHeight = readI32Le(source, start + 0x16)
        val heightLong = abs(signedHeight.toLong())

        if (width <= 0 || heightLong <= 0 || heightLong > Int.MAX_VALUE) {
            throw IOException("Invalid DWQ BMP dimensions.")
        }

        val height = heightLong.toInt()
        val bitsPerPixel = readU16Le(source, start + 0x1C)

        val bytesPerPixel = when (bitsPerPixel) {
            8 -> 1
            16 -> 2
            24 -> 3
            32 -> 4
            else -> throw IOException(
                "Unsupported DWQ BMP depth: " + bitsPerPixel + "."
            )
        }

        val pixelCount = width.toLong() * height.toLong()
        if (pixelCount > Int.MAX_VALUE.toLong()) {
            throw IOException("DWQ image exceeds the JVM pixel-array range.")
        }
        if (pixelCount > maxDecodedPixels) {
            throw IOException("DWQ image exceeds the configured decoded-pixel budget.")
        }

        val strideLong = width.toLong() * bytesPerPixel.toLong()
        val outputSizeLong = strideLong * height.toLong()

        if (strideLong > Int.MAX_VALUE || outputSizeLong > Int.MAX_VALUE) {
            throw IOException("DWQ decoded image exceeds the JVM array range.")
        }

        val stride = strideLong.toInt()
        val outputSize = outputSizeLong.toInt()
        val segmentLength = endExclusive - start
        val dataOffset = readU32Le(source, start + 0x0A)

        if (dataOffset < BMP_HEADER_SIZE.toLong() ||
            dataOffset > segmentLength.toLong()
        ) {
            throw IOException("Invalid DWQ BMP pixel offset.")
        }

        val palette = if (bitsPerPixel == 8) {
            val declaredColors = readI32Le(source, start + 0x2E)
            val colors = if (declaredColors == 0) 256 else declaredColors

            if (colors !in 1..256) {
                throw IOException("Invalid DWQ BMP palette size.")
            }

            val paletteStart = start + BMP_HEADER_SIZE
            val paletteEnd = paletteStart.toLong() + colors.toLong() * 4L
            val pixelStart = start.toLong() + dataOffset

            if (paletteEnd > pixelStart ||
                paletteEnd > endExclusive.toLong()
            ) {
                throw EOFException("Truncated DWQ BMP palette.")
            }

            IntArray(colors) { index ->
                val offset = paletteStart + index * 4
                val blue = source[offset].toInt() and 0xFF
                val green = source[offset + 1].toInt() and 0xFF
                val red = source[offset + 2].toInt() and 0xFF
                argb(255, red, green, blue)
            }
        } else {
            IntArray(0)
        }

        var inputOffset = start + dataOffset.toInt()
        val pixels = ByteArray(outputSize)
        val previousLine = ByteArray(stride)

        for (row in 0 until height) {
            val rowStart = row * stride
            var column = 0

            while (column < stride) {
                if (inputOffset >= endExclusive) {
                    throw EOFException("Truncated DWQ compressed pixel row.")
                }

                val value = source[inputOffset++].toInt() and 0xFF

                if (value != 0) {
                    pixels[rowStart + column] = value.toByte()
                    column++
                } else {
                    if (inputOffset >= endExclusive) {
                        throw EOFException("Truncated DWQ zero-run sequence.")
                    }

                    val count = source[inputOffset++].toInt() and 0xFF
                    if (count == 0 || count > stride - column) {
                        throw IOException("Invalid DWQ zero-run length.")
                    }

                    // The destination is zero-initialized.
                    column += count
                }
            }

            for (index in 0 until stride) {
                val current = pixels[rowStart + index].toInt() and 0xFF
                val previous = previousLine[index].toInt() and 0xFF
                val decoded = (current xor previous).toByte()

                pixels[rowStart + index] = decoded
                previousLine[index] = decoded
            }
        }

        return PackedBmpPlane(
            width = width,
            height = height,
            bitsPerPixel = bitsPerPixel,
            pixels = pixels,
            paletteArgb = palette
        )
    }

    private fun PackedBmpPlane.toArgb(): IntArray {
        val result = IntArray(width * height)
        val bytesPerPixel = bitsPerPixel / 8

        for (index in result.indices) {
            val offset = index * bytesPerPixel

            result[index] = when (bitsPerPixel) {
                8 -> {
                    val paletteIndex = pixels[index].toInt() and 0xFF
                    paletteArgb.getOrNull(paletteIndex)
                        ?: throw IOException(
                            "DWQ pixel references an invalid palette entry."
                        )
                }

                16 -> {
                    val value =
                        (pixels[offset].toInt() and 0xFF) or
                            ((pixels[offset + 1].toInt() and 0xFF) shl 8)

                    val blue = (value and 0x1F) * 255 / 31
                    val green = ((value ushr 5) and 0x3F) * 255 / 63
                    val red = ((value ushr 11) and 0x1F) * 255 / 31

                    argb(255, red, green, blue)
                }

                24, 32 -> {
                    val blue = pixels[offset].toInt() and 0xFF
                    val green = pixels[offset + 1].toInt() and 0xFF
                    val red = pixels[offset + 2].toInt() and 0xFF
                    argb(255, red, green, blue)
                }

                else -> throw IOException("Unsupported DWQ pixel format.")
            }
        }

        return result
    }

    private fun argb(alpha: Int, red: Int, green: Int, blue: Int): Int {
        return ((alpha and 0xFF) shl 24) or
            ((red and 0xFF) shl 16) or
            ((green and 0xFF) shl 8) or
            (blue and 0xFF)
    }

    private fun asciiAt(
        source: ByteArray,
        offset: Int,
        text: String
    ): Boolean {
        val expected = text.toByteArray(Charsets.US_ASCII)
        if (offset < 0 || offset > source.size - expected.size) return false

        return expected.indices.all { index ->
            source[offset + index] == expected[index]
        }
    }

    private fun readU8(source: ByteArray, offset: Int): Int {
        checkRange(source, offset, 1)
        return source[offset].toInt() and 0xFF
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int {
        return readU8(source, offset) or (readU8(source, offset + 1) shl 8)
    }

    private fun readI32Le(source: ByteArray, offset: Int): Int {
        return readU8(source, offset) or
            (readU8(source, offset + 1) shl 8) or
            (readU8(source, offset + 2) shl 16) or
            (readU8(source, offset + 3) shl 24)
    }

    private fun readU32Le(source: ByteArray, offset: Int): Long {
        return readU8(source, offset).toLong() or
            (readU8(source, offset + 1).toLong() shl 8) or
            (readU8(source, offset + 2).toLong() shl 16) or
            (readU8(source, offset + 3).toLong() shl 24)
    }

    private fun checkRange(source: ByteArray, offset: Int, length: Int) {
        if (offset < 0 ||
            length < 0 ||
            offset > source.size - length
        ) {
            throw EOFException("Binary read exceeds buffer bounds.")
        }
    }
}
