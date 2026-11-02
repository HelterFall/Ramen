package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.nio.charset.Charset

enum class GpkImageFormat {
    JPEG,
    BMP
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
    val packType: Int
)

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
 * The index contains a little-endian count, a table of name offsets, a table of
 * archive offsets, and a NUL-terminated ASCII string pool. Archive blocks have
 * 64-byte headers followed by JPEG or BMP payloads.
 *
 * Payloads are not decoded or copied into memory. Returned offsets can be used
 * by a caller to stream an individual image to another destination.
 */
class GpkGtbArchiveParser(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxStringPoolBytes: Int = DEFAULT_MAX_STRING_POOL_BYTES,
    private val maxNameBytes: Int = DEFAULT_MAX_NAME_BYTES,
    private val maxImageDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION
) {
    init {
        require(maxEntries in 1..ABSOLUTE_MAX_ENTRIES)
        require(maxStringPoolBytes in 1..ABSOLUTE_MAX_STRING_POOL_BYTES)
        require(maxNameBytes in 1..ABSOLUTE_MAX_NAME_BYTES)
        require(maxImageDimension in 1..ABSOLUTE_MAX_IMAGE_DIMENSION)
    }

    @Throws(IOException::class)
    fun parse(
        indexSource: ReadOnlyRandomAccessSource,
        archiveSource: ReadOnlyRandomAccessSource
    ): GpkGtbArchive {
        if (indexSource.sizeBytes < UINT32_BYTES) {
            malformed("GTB index is shorter than its entry-count field.")
        }

        val entryCountLong = readU32(indexSource, 0L)
        if (entryCountLong == 0L || entryCountLong > maxEntries.toLong()) {
            malformed("GTB entry count is zero or exceeds the configured limit.")
        }
        val entryCount = entryCountLong.toInt()
        val stringPoolOffset = UINT32_BYTES + entryCountLong * TABLES_PER_ENTRY * UINT32_BYTES
        if (stringPoolOffset > indexSource.sizeBytes) {
            malformed("GTB offset tables extend beyond the index file.")
        }
        val stringPoolSize = indexSource.sizeBytes - stringPoolOffset
        if (stringPoolSize <= 0L || stringPoolSize > maxStringPoolBytes.toLong()) {
            malformed("GTB string pool is empty or exceeds the configured limit.")
        }

        val stringPool = ByteArray(stringPoolSize.toInt())
        indexSource.readFullyAt(stringPoolOffset, stringPool)

        val nameOffsets = LongArray(entryCount)
        val archiveOffsets = LongArray(entryCount)
        val nameTableOffset = UINT32_BYTES
        val archiveTableOffset = UINT32_BYTES + entryCountLong * UINT32_BYTES

        for (index in 0 until entryCount) {
            nameOffsets[index] = readU32(indexSource, nameTableOffset + index * UINT32_BYTES)
            archiveOffsets[index] = readU32(indexSource, archiveTableOffset + index * UINT32_BYTES)
        }

        if (archiveOffsets[0] != 0L) {
            malformed("The first GPK resource does not begin at archive offset zero.")
        }

        val names = HashSet<String>(entryCount)
        val entries = ArrayList<GpkImageResource>(entryCount)
        for (index in 0 until entryCount) {
            val name = decodeName(stringPool, nameOffsets[index])
            if (!names.add(name)) {
                malformed("GTB contains a duplicate resource name.")
            }

            val blockOffset = archiveOffsets[index]
            val blockEnd = if (index + 1 < entryCount) {
                archiveOffsets[index + 1]
            } else {
                archiveSource.sizeBytes
            }
            if (blockOffset >= archiveSource.sizeBytes ||
                blockEnd <= blockOffset ||
                blockEnd > archiveSource.sizeBytes
            ) {
                malformed("GTB contains an invalid GPK resource range.")
            }
            if (index > 0 && blockOffset <= archiveOffsets[index - 1]) {
                malformed("GPK resource offsets are not strictly increasing.")
            }

            val blockSize = blockEnd - blockOffset
            if (blockSize < GPK_BLOCK_HEADER_BYTES) {
                malformed("A GPK resource block is shorter than its 64-byte header.")
            }
            val header = ByteArray(GPK_BLOCK_HEADER_BYTES)
            archiveSource.readFullyAt(blockOffset, header)
            val format = parseGpkBlockFormat(header)
            val payloadOffset = blockOffset + GPK_BLOCK_HEADER_BYTES
            val payloadSize = blockSize - GPK_BLOCK_HEADER_BYTES

            val resource = when (format) {
                GpkImageFormat.JPEG -> parseJpegResource(
                    source = archiveSource,
                    name = name,
                    header = header,
                    blockOffset = blockOffset,
                    blockSize = blockSize,
                    payloadOffset = payloadOffset,
                    payloadSize = payloadSize
                )
                GpkImageFormat.BMP -> parseBmpResource(
                    source = archiveSource,
                    name = name,
                    blockOffset = blockOffset,
                    blockSize = blockSize,
                    payloadOffset = payloadOffset,
                    payloadSize = payloadSize
                )
            }
            entries += resource
        }

        return GpkGtbArchive(entries)
    }

    private fun decodeName(pool: ByteArray, offset: Long): String {
        if (offset < 0L || offset >= pool.size.toLong()) {
            malformed("GTB name offset points outside the string pool.")
        }
        val start = offset.toInt()
        if (start > 0 && pool[start - 1] != 0.toByte()) {
            malformed("GTB name offset does not point to the start of a string.")
        }
        val endLimit = minOf(pool.size, start + maxNameBytes + 1)
        var end = start
        while (end < endLimit && pool[end] != 0.toByte()) {
            val value = pool[end].toInt() and 0xFF
            if (value !in MIN_PRINTABLE_ASCII..MAX_PRINTABLE_ASCII) {
                malformed("GTB resource name contains a non-printable or non-ASCII byte.")
            }
            end += 1
        }
        if (end == start || end >= endLimit || pool[end] != 0.toByte()) {
            malformed("GTB resource name is empty or is not terminated within the name limit.")
        }
        return pool.copyOfRange(start, end).toString(ASCII)
    }

    private fun parseGpkBlockFormat(header: ByteArray): GpkImageFormat {
        val jpegTag = "JPEG".toByteArray(ASCII)
        val bmpTag = "BMP".toByteArray(ASCII)
        return when {
            header.copyOfRange(0, jpegTag.size).contentEquals(jpegTag) &&
                header.copyOfRange(jpegTag.size, GPK_TAG_BYTES).all { it == SPACE_BYTE } -> {
                if (!header.copyOfRange(44, 64).contentEquals(JPEG_PACK_TYPE)) {
                    malformed("JPEG GPK block has an unexpected PACKTYPE marker.")
                }
                GpkImageFormat.JPEG
            }
            header.copyOfRange(0, bmpTag.size).contentEquals(bmpTag) &&
                header.copyOfRange(bmpTag.size, GPK_TAG_BYTES).all { it == SPACE_BYTE } -> {
                if (!header.copyOfRange(44, 64).contentEquals(BMP_PACK_TYPE)) {
                    malformed("BMP GPK block has an unexpected PACKTYPE marker.")
                }
                GpkImageFormat.BMP
            }
            else -> malformed("GPK block has an unknown 64-byte header.")
        }
    }

    private fun parseJpegResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        header: ByteArray,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long
    ): GpkImageResource {
        val declaredPayloadSize = readU32(header, 32)
        val width = readU32(header, 36)
        val height = readU32(header, 40)
        if (declaredPayloadSize != payloadSize) {
            malformed("JPEG GPK payload length does not match its block header.")
        }
        validateDimensions(width, height)
        if (payloadSize < JPEG_SIGNATURE.size) {
            malformed("JPEG GPK payload is too short.")
        }
        val signature = ByteArray(JPEG_SIGNATURE.size)
        source.readFullyAt(payloadOffset, signature)
        if (!signature.contentEquals(JPEG_SIGNATURE)) {
            malformed("JPEG GPK payload does not start with the JPEG marker.")
        }

        return GpkImageResource(
            name = name,
            format = GpkImageFormat.JPEG,
            blockOffset = blockOffset,
            blockSizeBytes = blockSize,
            payloadOffset = payloadOffset,
            payloadSizeBytes = payloadSize,
            widthPixels = width.toInt(),
            heightPixels = height.toInt(),
            packType = JPEG_PACK_TYPE_NUMBER
        )
    }

    private fun parseBmpResource(
        source: ReadOnlyRandomAccessSource,
        name: String,
        blockOffset: Long,
        blockSize: Long,
        payloadOffset: Long,
        payloadSize: Long
    ): GpkImageResource {
        if (payloadSize < BMP_MINIMUM_BYTES) {
            malformed("BMP GPK payload is shorter than a BITMAPINFOHEADER file.")
        }
        val fileHeader = ByteArray(BMP_FILE_HEADER_BYTES)
        source.readFullyAt(payloadOffset, fileHeader)
        if (fileHeader[0] != 'B'.code.toByte() || fileHeader[1] != 'M'.code.toByte()) {
            malformed("BMP GPK payload does not start with the BM marker.")
        }

        val declaredFileSize = readU32(fileHeader, 2)
        val pixelDataOffset = readU32(fileHeader, 10)
        val dibHeaderSize = readU32(source, payloadOffset + 14L)
        if (declaredFileSize != payloadSize ||
            dibHeaderSize < BMP_INFO_HEADER_BYTES ||
            dibHeaderSize > payloadSize - 14L ||
            pixelDataOffset < 14L + dibHeaderSize ||
            pixelDataOffset >= payloadSize
        ) {
            malformed("BMP file header contains an invalid size or pixel-data offset.")
        }

        val dimensions = ByteArray(8)
        source.readFullyAt(payloadOffset + 18L, dimensions)
        val width = readI32(dimensions, 0)
        val signedHeight = readI32(dimensions, 4)
        val absoluteHeight = kotlin.math.abs(signedHeight.toLong())
        if (width <= 0 || width > maxImageDimension ||
            absoluteHeight <= 0L || absoluteHeight > maxImageDimension.toLong()
        ) {
            malformed("BMP dimensions are outside the accepted range.")
        }

        val planesAndDepth = ByteArray(4)
        source.readFullyAt(payloadOffset + 26L, planesAndDepth)
        val planes = readU16(planesAndDepth, 0)
        val bitsPerPixel = readU16(planesAndDepth, 2)
        if (planes != 1 || bitsPerPixel !in BMP_BITS_PER_PIXEL) {
            malformed("BMP planes or bit depth are not recognized.")
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
            packType = BMP_PACK_TYPE_NUMBER
        )
    }

    private fun validateDimensions(width: Long, height: Long) {
        if (width <= 0L || height <= 0L ||
            width > maxImageDimension.toLong() ||
            height > maxImageDimension.toLong()
        ) {
            malformed("JPEG dimensions are outside the accepted range.")
        }
    }

    companion object {
        private const val DEFAULT_MAX_ENTRIES = 100_000
        private const val ABSOLUTE_MAX_ENTRIES = 1_000_000
        private const val DEFAULT_MAX_STRING_POOL_BYTES = 16 * 1024 * 1024
        private const val ABSOLUTE_MAX_STRING_POOL_BYTES = 64 * 1024 * 1024
        private const val DEFAULT_MAX_NAME_BYTES = 255
        private const val ABSOLUTE_MAX_NAME_BYTES = 4_096
        private const val DEFAULT_MAX_IMAGE_DIMENSION = 32_768
        private const val ABSOLUTE_MAX_IMAGE_DIMENSION = 131_072
        private const val UINT32_BYTES = 4L
        private const val TABLES_PER_ENTRY = 2L
        private const val GPK_BLOCK_HEADER_BYTES = 64
        private const val GPK_TAG_BYTES = 32
        private const val BMP_FILE_HEADER_BYTES = 14
        private const val BMP_MINIMUM_BYTES = 54L
        private const val BMP_INFO_HEADER_BYTES = 40L
        private const val JPEG_PACK_TYPE_NUMBER = 5
        private const val BMP_PACK_TYPE_NUMBER = 0
        private const val MIN_PRINTABLE_ASCII = 0x20
        private const val MAX_PRINTABLE_ASCII = 0x7E
        private val ASCII: Charset = Charsets.US_ASCII
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
class VpkVtbArchiveParser(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxOggPagesPerResource: Int = DEFAULT_MAX_OGG_PAGES_PER_RESOURCE
) {
    init {
        require(maxEntries in 1..ABSOLUTE_MAX_ENTRIES)
        require(maxOggPagesPerResource in 1..ABSOLUTE_MAX_OGG_PAGES_PER_RESOURCE)
    }

    @Throws(IOException::class)
    fun parse(
        indexSource: ReadOnlyRandomAccessSource,
        archiveSource: ReadOnlyRandomAccessSource
    ): VpkVtbArchive {
        if (indexSource.sizeBytes == 0L || indexSource.sizeBytes % VTB_RECORD_BYTES != 0L) {
            malformed("VTB size is not a whole number of 12-byte index records.")
        }
        val recordCountLong = indexSource.sizeBytes / VTB_RECORD_BYTES
        if (recordCountLong == 0L || recordCountLong > maxEntries.toLong()) {
            malformed("VTB record count is zero or exceeds the configured limit.")
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
        var pageCount = 0
        var expectedSequence = 0L
        var streamSerial: Long? = null
        var lastHeaderType = 0

        while (cursor < endOffset) {
            if (pageCount >= maxOggPagesPerResource ||
                endOffset - cursor < OGG_FIXED_HEADER_BYTES
            ) {
                malformed("Ogg page count or final page header is outside the accepted bounds.")
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
            if (pageCount == 0) {
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

        if (pageCount == 0 || cursor != endOffset || lastHeaderType and OGG_EOS_FLAG == 0) {
            malformed("Ogg payload does not end with a complete end-of-stream page.")
        }
        return pageCount
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
        private const val DEFAULT_MAX_ENTRIES = 100_000
        private const val ABSOLUTE_MAX_ENTRIES = 1_000_000
        private const val DEFAULT_MAX_OGG_PAGES_PER_RESOURCE = 100_000
        private const val ABSOLUTE_MAX_OGG_PAGES_PER_RESOURCE = 1_000_000
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

