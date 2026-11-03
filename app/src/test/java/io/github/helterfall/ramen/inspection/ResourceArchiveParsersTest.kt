package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceArchiveParsersTest {
    @Test
    fun parsesGtbIndexAndJpegAndBmpBlocks() {
        val jpegPayload = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10
        )
        val bmpPayload = createBmp1x1()
        val jpegBlock = createGpkBlock("JPEG", jpegPayload, width = 640, height = 480)
        val bmpBlock = createGpkBlock("BMP", bmpPayload)
        val archiveBytes = jpegBlock + bmpBlock
        val indexBytes = createGtbIndex(
            names = listOf("image001", "image002"),
            blockSizes = listOf(jpegBlock.size, bmpBlock.size)
        )

        withSources(indexBytes, archiveBytes) { index, archive ->
            val parsed = GpkGtbArchiveParser().parse(index, archive)

            assertEquals(2, parsed.entries.size)
            assertEquals("image001", parsed.entries[0].name)
            assertEquals(GpkImageFormat.JPEG, parsed.entries[0].format)
            assertEquals(640, parsed.entries[0].widthPixels)
            assertEquals(480, parsed.entries[0].heightPixels)
            assertEquals(jpegPayload.size.toLong(), parsed.entries[0].payloadSizeBytes)
            assertEquals("image002", parsed.entries[1].name)
            assertEquals(GpkImageFormat.BMP, parsed.entries[1].format)
            assertEquals(1, parsed.entries[1].widthPixels)
            assertEquals(1, parsed.entries[1].heightPixels)
            assertEquals(bmpPayload.size.toLong(), parsed.entries[1].payloadSizeBytes)

            val extractedJpeg = ByteArrayOutputStream()
            val copied = ReadOnlyRangeStreamer(bufferSizeBytes = 2).copy(
                source = archive,
                offset = parsed.entries[0].payloadOffset,
                length = parsed.entries[0].payloadSizeBytes,
                destination = extractedJpeg
            )
            assertEquals(jpegPayload.size.toLong(), copied)
            assertTrue(extractedJpeg.toByteArray().contentEquals(jpegPayload))
        }
    }

    @Test
    fun rejectsNegativeSignedInt32EntryCountAtGtbOffsetZero() {
        val block = createGpkBlock("BMP", createBmp1x1())
        val invalidIndex = createGtbIndex(
            names = listOf("invalid-count"),
            blockSizes = listOf(block.size)
        ).also { bytes ->
            bytes[0] = 0xFF.toByte()
            bytes[1] = 0xFF.toByte()
            bytes[2] = 0xFF.toByte()
            bytes[3] = 0xFF.toByte()
        }

        withSources(invalidIndex, block) { index, archive ->
            val failure = runCatching { GpkGtbArchiveParser().parse(index, archive) }
                .exceptionOrNull()
            assertTrue(failure is IOException)
            assertTrue(failure?.message.orEmpty().contains("Int32 entry count"))
        }
    }

    @Test
    fun skipsOpaque64BytePreamblesAndDetectsJpegAndBmpFromPayloads() {
        val jpegPayload = createJpegWithSof(width = 640, height = 480)
        val bmpPayload = createBmp1x1()
        val jpegBlock = ByteArray(64) { (it + 1).toByte() } + jpegPayload
        val bmpBlock = ByteArray(64) { (it + 65).toByte() } + bmpPayload
        val indexBytes = createGtbIndex(
            names = listOf("opaque-jpeg", "opaque-bmp"),
            blockSizes = listOf(jpegBlock.size, bmpBlock.size)
        )

        withSources(indexBytes, jpegBlock + bmpBlock) { index, archive ->
            val parsed = GpkGtbArchiveParser().parse(index, archive)

            assertEquals(2, parsed.entries.size)

            val jpeg = parsed.entries[0]
            assertEquals(GpkImageFormat.JPEG, jpeg.format)
            assertEquals(64L, jpeg.payloadOffset)
            assertEquals(jpegPayload.size.toLong(), jpeg.payloadSizeBytes)
            assertEquals(640, jpeg.widthPixels)
            assertEquals(480, jpeg.heightPixels)
            val extractedJpeg = ByteArray(jpeg.payloadSizeBytes.toInt())
            archive.readFullyAt(jpeg.payloadOffset, extractedJpeg)
            assertTrue(extractedJpeg.contentEquals(jpegPayload))

            val bmp = parsed.entries[1]
            assertEquals(GpkImageFormat.BMP, bmp.format)
            assertEquals((jpegBlock.size + 64).toLong(), bmp.payloadOffset)
            assertEquals(bmpPayload.size.toLong(), bmp.payloadSizeBytes)
            assertEquals(1, bmp.widthPixels)
            assertEquals(1, bmp.heightPixels)
            val extractedBmp = ByteArray(bmp.payloadSizeBytes.toInt())
            archive.readFullyAt(bmp.payloadOffset, extractedBmp)
            assertTrue(extractedBmp.contentEquals(bmpPayload))
        }
    }

    @Test
    fun parsesDibOnlyBmpAndRecoversAStaleFileHeaderOffset() {
        val dibOnlyPayload = createDibOnlyBmp1x1()
        val dibOnlyBlock = ByteArray(64) { (it + 1).toByte() } + dibOnlyPayload

        val staleFileHeaderPayload = createBmp1x1().also { bytes ->
            // The 40-byte DIB header and pixel rows are still intact; these
            // two BITMAPFILEHEADER fields are stale in the observed variant.
            putU32(bytes, 2, 0L)
            putU32(bytes, 10, 0L)
        }
        val staleFileHeaderBlock = createGpkBlock("BMP", staleFileHeaderPayload)
        val index = createGtbIndex(
            names = listOf("dib-only", "stale-file-header"),
            blockSizes = listOf(dibOnlyBlock.size, staleFileHeaderBlock.size)
        )

        withSources(index, dibOnlyBlock + staleFileHeaderBlock) { indexSource, archiveSource ->
            val parsed = GpkGtbArchiveParser().parse(indexSource, archiveSource)

            assertEquals(2, parsed.entries.size)
            val dibOnly = parsed.entries[0]
            assertEquals(GpkImageFormat.BMP, dibOnly.format)
            assertEquals(1, dibOnly.widthPixels)
            assertEquals(1, dibOnly.heightPixels)
            assertEquals(40L, dibOnly.bmpPixelDataOffsetBytes)
            assertEquals(24, dibOnly.bmpBitsPerPixel)
            assertEquals(0L, dibOnly.bmpCompression)
            assertEquals(false, dibOnly.bmpTopDown)

            val recovered = parsed.entries[1]
            assertEquals(GpkImageFormat.BMP, recovered.format)
            assertEquals(1, recovered.widthPixels)
            assertEquals(1, recovered.heightPixels)
            assertEquals(54L, recovered.bmpPixelDataOffsetBytes)
            assertEquals(24, recovered.bmpBitsPerPixel)
            assertEquals(0L, recovered.bmpCompression)
        }
    }

    @Test
    fun parsesOpaqueBmpRle4AndRle8WithoutExpectingExpandedPixelRowsInGpk() {
        val width = 32
        val height = 8
        val rle8Data = ByteArrayOutputStream().apply {
            repeat(height) {
                write(width)
                write(3)
                write(0)
                write(0) // end of line
            }
            write(0)
            write(1) // end of bitmap
        }.toByteArray()
        val rle4Data = ByteArrayOutputStream().apply {
            repeat(height) {
                write(width)
                write(0x12)
                write(0)
                write(0) // end of line
            }
            write(0)
            write(1) // end of bitmap
        }.toByteArray()

        val rle8Payload = createRleBmp(width, height, 8, 1, rle8Data)
        val rle4Payload = createRleBmp(width, height, 4, 2, rle4Data)
        // These are ordinary BMP/RLE payloads behind an opaque GPK preamble,
        // not Black Cyc DWQ PACKTYPE=0 images (which use uncompressed scanlines).
        val opaquePreamble = ByteArray(64) { SPACE }
        val rle8Block = opaquePreamble + rle8Payload
        val rle4Block = opaquePreamble + rle4Payload
        val index = createGtbIndex(
            names = listOf("compressed-rle8", "compressed-rle4"),
            blockSizes = listOf(rle8Block.size, rle4Block.size)
        )

        withSources(index, rle8Block + rle4Block) { indexSource, archiveSource ->
            val parsed = GpkGtbArchiveParser().parse(indexSource, archiveSource)

            assertEquals(2, parsed.entries.size)
            val rle8 = parsed.entries[0]
            assertEquals(GpkImageFormat.BMP, rle8.format)
            assertEquals(width, rle8.widthPixels)
            assertEquals(height, rle8.heightPixels)
            assertEquals(8, rle8.bmpBitsPerPixel)
            assertEquals(1L, rle8.bmpCompression)
            assertEquals(256, rle8.bmpPaletteColorCount)
            assertEquals((14 + 40).toLong(), rle8.bmpPaletteOffsetBytes)
            assertEquals((14 + 40 + 256 * 4).toLong(), rle8.bmpPixelDataOffsetBytes)
            assertTrue(rle8Data.size < width * height)

            val rle4 = parsed.entries[1]
            assertEquals(GpkImageFormat.BMP, rle4.format)
            assertEquals(width, rle4.widthPixels)
            assertEquals(height, rle4.heightPixels)
            assertEquals(4, rle4.bmpBitsPerPixel)
            assertEquals(2L, rle4.bmpCompression)
            assertEquals(16, rle4.bmpPaletteColorCount)
            assertEquals((14 + 40).toLong(), rle4.bmpPaletteOffsetBytes)
            assertEquals((14 + 40 + 16 * 4).toLong(), rle4.bmpPixelDataOffsetBytes)
            assertTrue(rle4Data.size < width * height)
        }
    }

    @Test
    fun keepsGpkEntriesReadableWithShiftJisAndMalformedNameBytes() {
        val firstPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        val secondPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x02)
        val firstBlock = createGpkBlock("JPEG", firstPayload)
        val secondBlock = createGpkBlock("JPEG", secondPayload)
        val index = createGtbIndexWithRawNames(
            names = listOf(
                "背景".toByteArray(Charset.forName("Shift_JIS")),
                byteArrayOf(0x82.toByte(), 0x20, 0x81.toByte())
            ),
            blockSizes = listOf(firstBlock.size, secondBlock.size)
        )

        withSources(index, firstBlock + secondBlock) { indexSource, archiveSource ->
            val parsed = GpkGtbArchiveParser().parse(indexSource, archiveSource)

            assertEquals(2, parsed.entries.size)
            assertEquals("背景", parsed.entries[0].name)
            assertEquals("fallback_resource_1", parsed.entries[1].name)
            assertEquals(0L, parsed.entries[0].blockOffset)
            assertEquals(firstBlock.size.toLong(), parsed.entries[1].blockOffset)
            assertEquals(secondPayload.size.toLong(), parsed.entries[1].payloadSizeBytes)

            val extracted = ByteArray(parsed.entries[1].payloadSizeBytes.toInt())
            archiveSource.readFullyAt(parsed.entries[1].payloadOffset, extracted)
            assertTrue(extracted.contentEquals(secondPayload))
        }
    }

    @Test
    fun duplicateGtbDisplayNamesDoNotHideValidResourceBlocks() {
        val block = createGpkBlock(
            "JPEG",
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        )
        val index = createGtbIndex(
            names = listOf("duplicate", "duplicate"),
            blockSizes = listOf(block.size, block.size)
        )

        withSources(index, block + block) { indexSource, archiveSource ->
            val parsed = GpkGtbArchiveParser().parse(indexSource, archiveSource)
            assertEquals(2, parsed.entries.size)
            assertEquals("duplicate", parsed.entries[0].name)
            assertEquals("fallback_resource_1", parsed.entries[1].name)
        }
    }

    @Test
    fun marksDwqJpegMaskPayloadSizeThatEscapesItsIndexedBlockAsInvalid() {
        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        val block = createGpkBlock("JPEG", jpegPayload, width = 2, height = 2).also { bytes ->
            "PACKTYPE=7      ".toByteArray(Charsets.US_ASCII).copyInto(bytes, 48)
        }
        putU32(block, 32, (jpegPayload.size + 1).toLong())
        val index = createGtbIndex(listOf("image001"), listOf(block.size))

        withSources(index, block) { indexSource, archiveSource ->
            val entry = GpkGtbArchiveParser().parse(indexSource, archiveSource).entries.single()
            assertFalse(entry.isValid)
            assertTrue(entry.validationError.orEmpty().contains("packed image size"))
        }
    }

    @Test
    fun parsesVtbIndexAndOggPagesIncludingTerminalSentinel() {
        val archiveBytes = createVpkAudioBlock()
        val indexBytes = createVtbIndex(
            names = listOf("track001", "indexend"),
            offsets = listOf(0, archiveBytes.size)
        )

        withSources(indexBytes, archiveBytes) { index, archive ->
            val parsed = VpkVtbArchiveParser().parse(index, archive)

            assertEquals(1, parsed.entries.size)
            assertTrue(parsed.hasTerminalIndexSentinel)
            val entry = parsed.entries.single()
            assertEquals("track001", entry.name)
            assertEquals(44_100, entry.sampleRateHz)
            assertEquals(1, entry.channels)
            assertEquals(16, entry.bitsPerSample)
            assertEquals(4L, entry.decodedPcmSizeBytes)
            assertEquals(2, entry.oggPageCount)
            assertEquals(108L, entry.oggPayloadOffset)

            val extractedOgg = ByteArrayOutputStream()
            val copied = ReadOnlyRangeStreamer(bufferSizeBytes = 7).copy(
                source = archive,
                offset = entry.oggPayloadOffset,
                length = entry.oggPayloadSizeBytes,
                destination = extractedOgg
            )
            assertEquals(entry.oggPayloadSizeBytes, copied)
            assertTrue(extractedOgg.toByteArray().contentEquals(
                createOggPage(flags = 0x02, sequence = 0) +
                    createOggPage(flags = 0x04, sequence = 1)
            ))
        }
    }

    @Test
    fun acceptsNullPaddedNamesAndOpaqueIdsAndIgnoresSentinelName() {
        val block = createVpkAudioBlock()
        val archiveBytes = block + block
        val indexBytes = createVtbIndexWithRawNames(
            names = listOf(
                byteArrayOf('t'.code.toByte(), 'r'.code.toByte(), 'a'.code.toByte(),
                    'c'.code.toByte(), 'k'.code.toByte(), 0, 0, 0),
                byteArrayOf(0xFF.toByte(), 0x80.toByte(), 0, 0x20, 1, 2, 3, 4),
                byteArrayOf(0, 0xFF.toByte(), 0xFE.toByte(), 0, 0, 0, 0, 0)
            ),
            offsets = listOf(0, block.size, archiveBytes.size)
        )

        withSources(indexBytes, archiveBytes) { index, archive ->
            val parsed = VpkVtbArchiveParser().parse(index, archive)

            assertEquals(2, parsed.entries.size)
            assertEquals("track", parsed.entries[0].name)
            assertEquals("resource-FF80002001020304", parsed.entries[1].name)
            assertTrue(parsed.hasTerminalIndexSentinel)
        }
    }

    @Test
    fun parsesVtbWithoutTerminalSentinel() {
        val archiveBytes = createVpkAudioBlock()
        val indexBytes = createVtbIndex(
            names = listOf("track001"),
            offsets = listOf(0)
        )

        withSources(indexBytes, archiveBytes) { index, archive ->
            val parsed = VpkVtbArchiveParser().parse(index, archive)

            assertEquals(1, parsed.entries.size)
            assertFalse(parsed.hasTerminalIndexSentinel)
        }
    }

    @Test
    fun rejectsOggSequenceDiscontinuity() {
        val archiveBytes = createVpkAudioBlock().also { bytes ->
            // The second Ogg page begins after the first 31-byte synthetic page.
            putU32(bytes, 108 + 31 + 18, 2)
        }
        val indexBytes = createVtbIndex(
            names = listOf("track001"),
            offsets = listOf(0)
        )

        withSources(indexBytes, archiveBytes) { index, archive ->
            val result = runCatching {
                VpkVtbArchiveParser().parse(index, archive)
            }
            assertTrue(result.exceptionOrNull() is IOException)
        }
    }

    private fun withSources(
        indexBytes: ByteArray,
        archiveBytes: ByteArray,
        block: (ReadOnlyRandomAccessSource, ReadOnlyRandomAccessSource) -> Unit
    ) {
        val directory = Files.createTempDirectory("ramen-resource-archive-test").toFile()
        try {
            val indexFile = File(directory, "index.bin").apply { writeBytes(indexBytes) }
            val archiveFile = File(directory, "archive.bin").apply { writeBytes(archiveBytes) }
            FileRandomAccessSource(indexFile).use { index ->
                FileRandomAccessSource(archiveFile).use { archive ->
                    block(index, archive)
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createGtbIndex(names: List<String>, blockSizes: List<Int>): ByteArray =
        createGtbIndexWithRawNames(
            names.map { it.toByteArray(Charsets.US_ASCII) },
            blockSizes
        )

    private fun createGtbIndexWithRawNames(
        names: List<ByteArray>,
        blockSizes: List<Int>
    ): ByteArray {
        require(names.isNotEmpty() && names.size == blockSizes.size)
        val pool = ByteArrayOutputStream()
        val nameOffsets = names.map { name ->
            val offset = pool.size()
            pool.write(name)
            pool.write(0)
            offset
        }
        val dataOffsets = ArrayList<Int>(blockSizes.size)
        var runningOffset = 0
        for (size in blockSizes) {
            dataOffsets += runningOffset
            runningOffset += size
        }
        val tableSize = 4 + names.size * 8
        val result = ByteArray(tableSize + pool.size())
        putU32(result, 0, names.size.toLong())
        for (index in names.indices) {
            putU32(result, 4 + index * 4, nameOffsets[index].toLong())
            putU32(result, 4 + names.size * 4 + index * 4, dataOffsets[index].toLong())
        }
        pool.toByteArray().copyInto(result, tableSize)
        return result
    }

    private fun createGpkBlock(
        tag: String,
        payload: ByteArray,
        width: Int = 1,
        height: Int = 1
    ): ByteArray {
        val header = ByteArray(64) { SPACE }
        val tagBytes = tag.toByteArray(Charsets.US_ASCII)
        tagBytes.copyInto(header)
        if (tag == "JPEG") {
            putU32(header, 32, payload.size.toLong())
            putU32(header, 36, width.toLong())
            putU32(header, 40, height.toLong())
            "    PACKTYPE=5      ".toByteArray(Charsets.US_ASCII).copyInto(header, 44)
        } else {
            "    PACKTYPE=0      ".toByteArray(Charsets.US_ASCII).copyInto(header, 44)
        }
        return header + payload
    }

    private fun createJpegWithSof(width: Int, height: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B, // SOF0 segment length: 8 + 3 * component count
            0x08, // sample precision
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x01, // one colour component
            0x01, 0x11, 0x00, // component id, sampling, quantization table
            0xFF.toByte(), 0xD9.toByte()
        )

    private fun createRleBmp(
        width: Int,
        height: Int,
        bitsPerPixel: Int,
        compression: Int,
        compressedPixels: ByteArray
    ): ByteArray {
        val paletteEntries = 1 shl bitsPerPixel
        val paletteOffset = 14 + 40
        val pixelDataOffset = paletteOffset + paletteEntries * 4
        val bytes = ByteArray(pixelDataOffset + compressedPixels.size)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size.toLong())
        putU32(bytes, 10, pixelDataOffset.toLong())
        putU32(bytes, 14, 40)
        putU32(bytes, 18, width.toLong())
        putU32(bytes, 22, height.toLong())
        putU16(bytes, 26, 1)
        putU16(bytes, 28, bitsPerPixel)
        putU32(bytes, 30, compression.toLong())
        putU32(bytes, 34, compressedPixels.size.toLong())
        putU32(bytes, 46, 0) // zero means the default palette size for this bit depth

        repeat(paletteEntries) { index ->
            val base = paletteOffset + index * 4
            bytes[base] = index.toByte()
            bytes[base + 1] = index.toByte()
            bytes[base + 2] = index.toByte()
            bytes[base + 3] = 0
        }
        compressedPixels.copyInto(bytes, pixelDataOffset)
        return bytes
    }

    private fun createDibOnlyBmp1x1(): ByteArray {
        val bytes = ByteArray(44)
        putU32(bytes, 0, 40)
        putU32(bytes, 4, 1)
        putU32(bytes, 8, 1)
        putU16(bytes, 12, 1)
        putU16(bytes, 14, 24)
        putU32(bytes, 16, 0)
        putU32(bytes, 20, 4)
        bytes[40] = 0x20.toByte()
        bytes[41] = 0x40.toByte()
        bytes[42] = 0x60.toByte()
        bytes[43] = 0.toByte()
        return bytes
    }

    private fun createBmp1x1(): ByteArray {
        val bytes = ByteArray(58)
        bytes[0] = 'B'.code.toByte()
        bytes[1] = 'M'.code.toByte()
        putU32(bytes, 2, bytes.size.toLong())
        putU32(bytes, 10, 54)
        putU32(bytes, 14, 40)
        putU32(bytes, 18, 1)
        putU32(bytes, 22, 1)
        putU16(bytes, 26, 1)
        putU16(bytes, 28, 24)
        putU32(bytes, 30, 0)
        putU32(bytes, 34, 4)
        return bytes
    }

    private fun createVtbIndex(names: List<String>, offsets: List<Int>): ByteArray {
        require(names.isNotEmpty() && names.size == offsets.size)
        val result = ByteArray(names.size * 12)
        for (index in names.indices) {
            val nameBytes = names[index].toByteArray(Charsets.US_ASCII)
            require(nameBytes.size == 8)
            nameBytes.copyInto(result, index * 12)
            putU32(result, index * 12 + 8, offsets[index].toLong())
        }
        return result
    }

    private fun createVtbIndexWithRawNames(
        names: List<ByteArray>,
        offsets: List<Int>
    ): ByteArray {
        require(names.isNotEmpty() && names.size == offsets.size)
        require(names.all { it.size == 8 })
        val result = ByteArray(names.size * 12)
        for (index in names.indices) {
            names[index].copyInto(result, index * 12)
            putU32(result, index * 12 + 8, offsets[index].toLong())
        }
        return result
    }

    private fun createVpkAudioBlock(): ByteArray {
        val ogg = createOggPage(flags = 0x02, sequence = 0) +
            createOggPage(flags = 0x04, sequence = 1)
        val result = ByteArray(108) + ogg
        "IF PACKTYPE==2  CUT THIS 108BYTETHEN REMAKE OGG PACKTYPE=2      "
            .toByteArray(Charsets.US_ASCII)
            .copyInto(result, 0)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(result, 64)
        putU32(result, 68, 40)
        "WAVEfmt ".toByteArray(Charsets.US_ASCII).copyInto(result, 72)
        putU32(result, 80, 16)
        putU16(result, 84, 1)
        putU16(result, 86, 1)
        putU32(result, 88, 44_100)
        putU32(result, 92, 88_200)
        putU16(result, 96, 2)
        putU16(result, 98, 16)
        "data".toByteArray(Charsets.US_ASCII).copyInto(result, 100)
        putU32(result, 104, 4)
        return result
    }

    private fun createOggPage(flags: Int, sequence: Int): ByteArray {
        val page = ByteArray(31)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(page, 0)
        page[4] = 0
        page[5] = flags.toByte()
        putU32(page, 14, 42)
        putU32(page, 18, sequence.toLong())
        page[26] = 1
        page[27] = 3
        page[28] = 'a'.code.toByte()
        page[29] = 'b'.code.toByte()
        page[30] = 'c'.code.toByte()
        return page
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Long) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    companion object {
        private val SPACE = ' '.code.toByte()
    }
    @Test
    fun acceptsGtbNamesLongerThanTheFormer255ByteCeiling() {
        val longName = "n".repeat(300)
        val jpeg = createJpegWithSof(1, 1)
        val block = createGpkBlock("JPEG", jpeg)
        val index = createGtbIndex(listOf(longName), listOf(block.size))
        withSources(index, block) { idx, arc ->
            assertEquals(longName, GpkGtbArchiveParser().parse(idx, arc).entries.single().name)
        }
    }

    @Test
    fun acceptsImageDimensionsAboveTheFormer32768PixelCeiling() {
        val jpeg = createJpegWithSof(40_000, 1)
        val block = createGpkBlock("JPEG", jpeg, width = 40_000, height = 1)
        val index = createGtbIndex(listOf("wide-image"), listOf(block.size))
        withSources(index, block) { idx, arc ->
            assertEquals(40_000, GpkGtbArchiveParser().parse(idx, arc).entries.single().widthPixels)
        }
    }

    @Test
    fun shsSupportsOverlappingBackReferences() {
        val compressed = byteArrayOf(0x01, 0x41, 0x42, 0x20)
        val result = ResourceArchiveParsers.decompressShs(compressed, expectedOutputSize = 5)
        assertEquals("ABBBB", result.toString(Charsets.US_ASCII))
    }

    @Test
    fun blackCycPackedBmpConvertsBgrPixelsToArgb() {
        val dwq = ByteArray(0x40 + 0x36 + 3)
        "IF PACKTYPE==".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0)
        "0 ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x0D)
        "BMP ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x2C)
        "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(dwq, 0x30)
        val bmpOffset = 0x40
        writeI32Le(dwq, bmpOffset + 0x0A, 0x36)
        writeI32Le(dwq, bmpOffset + 0x12, 1)
        writeI32Le(dwq, bmpOffset + 0x16, 1)
        writeU16Le(dwq, bmpOffset + 0x1C, 24)
        dwq[bmpOffset + 0x36] = 1
        dwq[bmpOffset + 0x37] = 2
        dwq[bmpOffset + 0x38] = 3
        val result = ResourceArchiveParsers.decodeBlackCycDwqPackedBmp(dwq)
        assertEquals(0xFF030201.toInt(), result.argbPixels.single())
    }

    private fun writeI32Le(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
        destination[offset + 2] = (value ushr 16).toByte()
        destination[offset + 3] = (value ushr 24).toByte()
    }

    private fun writeU16Le(destination: ByteArray, offset: Int, value: Int) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
    }

    @Test
    fun parsesSpecialDwqPacktypeOneWithoutApplyingBmpDwordRowPadding() {
        val bmp = ByteArray(0x36 + 3)
        putU32(bmp, 10, 0x36)
        putU32(bmp, 18, 1)
        putU32(bmp, 22, 1)
        putU16(bmp, 28, 24)
        bmp[0x36] = 0x11
        bmp[0x37] = 0x22
        bmp[0x38] = 0x33

        val header = ByteArray(64) { SPACE }
        "IF PACKTYPE==".toByteArray(Charsets.US_ASCII).copyInto(header, 0)
        "0 ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x0D)
        "BMP ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x2C)
        "PACKTYPE=1      ".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)
        val block = header + bmp
        val index = createGtbIndex(listOf("packed-bmp"), listOf(block.size))

        withSources(index, block) { idx, arc ->
            val resource = GpkGtbArchiveParser().parse(idx, arc).entries.single()
            assertTrue(resource.isValid)
            assertEquals(1, resource.packType)
            assertTrue(resource.dwqPackedBmp)
            assertEquals(1, resource.widthPixels)
            assertEquals(1, resource.heightPixels)
        }
    }

    @Test
    fun invalidGpkEntryDoesNotHideFollowingValidEntries() {
        val validFirst = createGpkBlock("JPEG", createJpegWithSof(1, 1))
        val broken = createGpkBlock("BMP", ByteArray(12))
        val validLast = createGpkBlock("JPEG", createJpegWithSof(2, 2), width = 2, height = 2)
        val blocks = validFirst + broken + validLast
        val index = createGtbIndex(
            listOf("first", "broken", "last"),
            listOf(validFirst.size, broken.size, validLast.size)
        )

        withSources(index, blocks) { idx, arc ->
            val entries = GpkGtbArchiveParser().parse(idx, arc).entries
            assertEquals(3, entries.size)
            assertTrue(entries[0].isValid)
            assertFalse(entries[1].isValid)
            assertTrue(entries[1].validationError!!.contains("BMP"))
            assertTrue(entries[2].isValid)
            assertEquals(2, entries[2].widthPixels)
        }
    }

    @Test
    fun gpkOffsetsAreReadAtTheUnalignedTablePositionWithoutAssumingZeroFirstOffset() {
        val prefix = byteArrayOf(9, 8, 7)
        val block = createGpkBlock("JPEG", createJpegWithSof(1, 1))
        val archive = prefix + block
        val index = createGtbIndex(listOf("prefixed"), listOf(block.size))
        // One-entry offset table is at 0x04 + count*4 = 0x08.
        putU32(index, 8, prefix.size.toLong())

        withSources(index, archive) { idx, arc ->
            val entry = GpkGtbArchiveParser().parse(idx, arc).entries.single()
            assertTrue(entry.isValid)
            assertEquals(prefix.size.toLong(), entry.blockOffset)
            assertEquals(block.size.toLong(), entry.blockSizeBytes)
        }
    }


}
