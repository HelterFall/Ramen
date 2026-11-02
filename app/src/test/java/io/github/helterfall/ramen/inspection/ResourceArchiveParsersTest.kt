package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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
    fun rejectsJpegPayloadSizeThatEscapesItsIndexedBlock() {
        val jpegPayload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        val block = createGpkBlock("JPEG", jpegPayload, width = 2, height = 2)
        putU32(block, 32, (jpegPayload.size + 1).toLong())
        val index = createGtbIndex(listOf("image001"), listOf(block.size))

        withSources(index, block) { indexSource, archiveSource ->
            val result = runCatching {
                GpkGtbArchiveParser().parse(indexSource, archiveSource)
            }
            assertTrue(result.exceptionOrNull() is IOException)
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

    private fun createGtbIndex(names: List<String>, blockSizes: List<Int>): ByteArray {
        require(names.isNotEmpty() && names.size == blockSizes.size)
        val pool = ByteArrayOutputStream()
        val nameOffsets = names.map { name ->
            val offset = pool.size()
            pool.write(name.toByteArray(Charsets.US_ASCII))
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
}
