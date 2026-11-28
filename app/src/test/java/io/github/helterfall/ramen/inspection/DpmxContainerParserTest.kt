package io.github.helterfall.ramen.inspection

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpmxContainerParserTest {
    @Test
    fun findsOverlayAfterLastPeRawSectionAndParsesEntry() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val fixture = createPeDpmxFixture("image.bin", payload, 0x01020304L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertEquals(0x400L, container.overlayOffset)
            assertEquals(0x80L, container.dataOffset)
            assertEquals(0x480L, container.dataAbsoluteOffset)
            assertEquals(1, container.fileCount)
            val entry = container.findEntry("image.bin")
            assertNotNull(entry)
            assertEquals(0x01020304L, entry!!.fileKey)
            assertEquals(0x480L, entry.absoluteOffset)
            assertEquals(payload.size.toLong(), entry.sizeBytes)
            assertArrayEquals(payload, container.openEntryStream(source, entry).use { it.readBytes() })
        }
    }

    @Test
    fun findsDpmxBeyondFormer64KiBOverlayLimit() {
        val payload = byteArrayOf(7, 8, 9)
        val fixture = createPeDpmxFixture(
            "aligned.bin",
            payload,
            0x12345678L,
            overlayOffset = 0x31000,
            sectionRawSize = 0x200
        )

        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertEquals(0x31000L, container.overlayOffset)
            assertEquals(0x31080L, container.dataAbsoluteOffset)
            assertArrayEquals(
                payload,
                container.openEntryStream(source, container.entries.single()).readBytes()
            )
        }
    }

    @Test
    fun mapsPeVirtualAddressBackToSectionRawBytes() {
        val fixture = createPeDpmxFixture("asset.bin", byteArrayOf(1), 9L)
        fixture.bytes[0x200] = 0x31
        fixture.bytes[0x201] = 0x32
        fixture.bytes[0x202] = 0x33
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertArrayEquals(
                byteArrayOf(0x31, 0x32, 0x33),
                container.readVirtualAddress(source, 0x00401000L, 3)!!
            )
            assertNull(container.readVirtualAddress(source, 0x00402000L, 4))
        }
    }

    @Test
    fun rejectsDpmxTableThatExtendsIntoDataBlock() {
        val fixture = createPeDpmxFixture("asset.bin", byteArrayOf(1), 9L)
        putUInt32(fixture.bytes, 0x404, 0x20L)
        withSource(fixture.bytes) { source ->
            val error = runCatching { DpmxContainerParser().parse(source) }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
        }
    }

    @Test
    fun decryptsWithFeedbackAcrossSmallReadBuffers() {
        val key = DpmxCipherKey(al = 0xB2, cl = 0x53)
        val plain = byteArrayOf(0, 1, 2, 127, 128.toByte(), 255.toByte(), 12)
        val encrypted = encrypt(plain, key)
        val fixture = createPeDpmxFixture("sample.bin", encrypted, 0x10101010L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val entry = container.entries.single()
            val output = ByteArrayOutputStream()
            DpmxDecryptor.decrypt(source, entry, key, output, bufferSize = 2)
            assertArrayEquals(plain, output.toByteArray())
            assertArrayEquals(
                plain,
                DpmxDecryptingInputStream(source, entry, key, bufferSize = 3).use { it.readBytes() }
            )
        }
    }

    @Test
    fun resolvesSuppliedStartAxSampleKeyVector() {
        val plainAx = validHspHeader()
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(plainAx)
        putUInt32(plainAx, 4, 0x301L)
        val key = DpmxCipherKey(al = 0xB2, cl = 0x53)
        val fixture = createPeDpmxFixture("start.ax", encrypt(plainAx, key), 0xB2B8EDB5L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, resolution!!.method)
            assertEquals(DpmxCipherKey(al = key.al xor 0x80, cl = key.cl xor 0x80), resolution.key)
        }
    }

    @Test
    fun autodetectsStartAxAndChoosesCanonicalSymmetricKey() {
        val plainAx = validHspHeader(version = 0x0301L)
        val actualKey = DpmxCipherKey(al = 0x32, cl = 0x17)
        val fixture = createPeDpmxFixture("start.ax", encrypt(plainAx, actualKey), 0x01020304L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, resolution!!.method)
            assertEquals(DpmxCipherKey(al = 0x32, cl = 0x17), resolution.key)
        }
    }

    @Test
    fun recoversStartAxKeyFromKnownHsp3MagicAndNonZeroInitialDl() {
        val plainAx = validHspHeader(version = 0x0301L)
        val expectedKey = DpmxCipherKey(al = 0x41, cl = 0xB3, initialDl = 0xA7)
        val fixture = createPeDpmxFixture(
            "start.ax",
            encrypt(plainAx, expectedKey),
            0xA1B2C3D4L
        )
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val entry = container.entries.single()
            val resolution = DpmxKeyResolver().resolve(source, container, entry)

            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, resolution!!.method)
            assertEquals(expectedKey, resolution.key)
            assertArrayEquals(
                plainAx,
                DpmxDecryptor.decryptPrefix(source, entry, resolution.key, plainAx.size)
            )
        }
    }

    @Test
    fun recoversStartAxKeyFromEmbeddedHsphedDeckeyAndFileKey() {
        val plainAx = validHspHeader(version = 0x0301L)
        val deckey = 0x81AA9ECDL
        val fileKey = 0xB2B8EDB5L
        val overlayOffset = 0x21000
        val dataSize = plainAx.size.toLong()
        val sumSize = dataSize + 0x80L - 16L
        val key = deriveHspEntryKey(deckey, fileKey, sumSize, DpmxCipherVersion.HSP_26_TO_32)
        val fixture = createPeDpmxFixture(
            "start.ax",
            encrypt(plainAx, key),
            fileKey,
            overlayOffset = overlayOffset
        )
        writeHspExecutableMetadata(fixture.bytes, overlayOffset, deckey, includeRuntimeMarker = true)

        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val metadata = container.executableMetadata
            assertNotNull(metadata)
            assertEquals(overlayOffset.toLong(), metadata!!.dpmOffset)
            assertTrue(metadata.checksumValid)

            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.PE_HSPHED_DECKEY, resolution!!.method)
            assertEquals(DpmxKeyResolution.Validation.HSP3_BYTECODE_LOADER, resolution.validation)
            // Independent reference vector from the HSP 2.6-3.2 key schedule.
            assertEquals(0xBC, resolution.globalSeed1)
            assertEquals(0x9A, resolution.globalSeed2)
            assertEquals(0xBF, resolution.key.al)
            assertEquals(0x6E, resolution.key.cl)
            assertEquals(key, resolution.key)
        }
    }

    @Test
    fun resolvesHsp33DeckeyUsingRevisedFileKeyScheduleAndCipherOrder() {
        val plainAx = validHspHeader(version = 0x0330L)
        val deckey = 0x81AA9ECDL
        val fileKey = 0x7C01AD18L
        val overlayOffset = 0x21000
        val sumSize = 0x80L + plainAx.size - 16L
        val key = deriveHspEntryKey(deckey, fileKey, sumSize, DpmxCipherVersion.HSP_33_TO_36)
        val fixture = createPeDpmxFixture(
            "start.ax",
            encrypt(plainAx, key),
            fileKey,
            overlayOffset = overlayOffset
        )
        writeHspExecutableMetadata(fixture.bytes, overlayOffset, deckey)

        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertTrue(container.executableMetadata!!.checksumValid)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.PE_HSPHED_DECKEY, resolution!!.method)
            assertEquals(DpmxCipherVersion.HSP_33_TO_36, resolution.key.version)
            // Independent reference vector for the HSP 3.3-3.6 constants.
            assertEquals(0xC8, resolution.key.al)
            assertEquals(0x2F, resolution.key.cl)
            assertArrayEquals(
                plainAx,
                DpmxDecryptor.decryptPrefix(source, container.entries.single(), resolution.key, plainAx.size)
            )
        }
    }

    @Test
    fun fallsBackToKnownHsp3HeaderWhenEmbeddedMetadataChecksumFails() {
        val plainAx = validHspHeader(version = 0x0301L)
        val expectedKey = DpmxCipherKey(al = 0x41, cl = 0xB3)
        val deckey = 0x81AA9ECDL
        val overlayOffset = 0x21000
        val fixture = createPeDpmxFixture(
            "start.ax",
            encrypt(plainAx, expectedKey),
            0x01020304L,
            overlayOffset = overlayOffset
        )
        writeHspExecutableMetadata(fixture.bytes, overlayOffset, deckey)
        fixture.bytes[0x300 + 29] = (fixture.bytes[0x300 + 29].toInt() xor 0x01).toByte()

        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertEquals(false, container.executableMetadata?.checksumValid)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, resolution!!.method)
            assertArrayEquals(
                plainAx,
                DpmxDecryptor.decryptPrefix(source, container.entries.single(), resolution.key, plainAx.size)
            )
        }
    }

    @Test
    fun rejectsStartAxWithOverlappingSegmentOffsets() {
        val plainAx = validHspHeader()
        putUInt32(plainAx, 24, 0x62L) // DS overlaps the CS range.
        val actualKey = DpmxCipherKey(al = 0x32, cl = 0x17)
        val fixture = createPeDpmxFixture("start.ax", encrypt(plainAx, actualKey), 0x01020304L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertNull(DpmxKeyResolver().resolve(source, container, container.entries.single()))
        }
    }

    @Test
    fun rejectsBmpWhenDeclaredSizeDoesNotMatchEntrySize() {
        val invalidBmp = ByteArray(58)
        invalidBmp[0] = 'B'.code.toByte()
        invalidBmp[1] = 'M'.code.toByte()
        putUInt32(invalidBmp, 2, 57L)
        val key = DpmxCipherKey(al = 0x38, cl = 0x8D)
        val fixture = createPeDpmxFixture("invalid.bmp", encrypt(invalidBmp, key), 0x01020304L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            assertNull(DpmxKeyResolver().resolve(source, container, container.entries.single()))
        }
    }

    @Test
    fun autodetectsBmpKeyFromSignatureAndDeclaredSize() {
        // Size 256 gives this BM+uint32 signature a single canonical key pair.
        val plainBmp = ByteArray(256)
        plainBmp[0] = 'B'.code.toByte()
        plainBmp[1] = 'M'.code.toByte()
        putUInt32(plainBmp, 2, plainBmp.size.toLong())
        putUInt32(plainBmp, 10, 54L)
        putUInt32(plainBmp, 34, 4L)
        putUInt32(plainBmp, 14, 40L)
        putUInt32(plainBmp, 18, 1L)
        putUInt32(plainBmp, 22, 1L)
        putUInt32(plainBmp, 26, 1L)
        putUInt32(plainBmp, 28, 24L)
        val expected = DpmxCipherKey(al = 0x38, cl = 0x8D)
        val fixture = createPeDpmxFixture(
            "autodetect.bmp",
            encrypt(plainBmp, expected),
            0xAABBCCDDL
        )
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.SIGNATURE_AUTODETECT, resolution!!.method)
            assertEquals(expected, resolution.key)
        }
    }

    private fun validHspHeader(version: Long = 0x0301L): ByteArray {
        val bytes = ByteArray(0x6C)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        putUInt32(bytes, 4, version)
        putUInt32(bytes, 8, 64L)
        putUInt32(bytes, 12, bytes.size.toLong())
        putUInt32(bytes, 16, 0x60L)
        putUInt32(bytes, 20, 4L)
        putUInt32(bytes, 24, 0x64L)
        putUInt32(bytes, 28, 4L)
        putUInt32(bytes, 32, 0x68L)
        putUInt32(bytes, 36, 4L)
        return bytes
    }

    private fun deriveHspEntryKey(
        deckey: Long,
        fileKey: Long,
        sumSize: Long,
        version: DpmxCipherVersion
    ): DpmxCipherKey {
        val d1 = deckey.toInt() and 0xFF
        val d2 = (deckey.toInt() ushr 8) and 0xFF
        val d3 = (deckey.toInt() ushr 16) and 0xFF
        val d4 = (deckey.toInt() ushr 24) and 0xFF
        val seed1 = (((d1 * d3 / 3) xor sumSize.toInt()) and 0xFF)
        val seed2 = (((d2 * d4 / 5) xor sumSize.toInt() xor 0xAA) and 0xFF)
        val f1 = fileKey.toInt() and 0xFF
        val f2 = (fileKey.toInt() ushr 8) and 0xFF
        val f3 = (fileKey.toInt() ushr 16) and 0xFF
        val f4 = (fileKey.toInt() ushr 24) and 0xFF
        val a = if (version == DpmxCipherVersion.HSP_33_TO_36) 0x5A else 0x55
        val b = if (version == DpmxCipherVersion.HSP_33_TO_36) 0xA5 else 0xAA
        val fileSeed1 = (f1 + a) xor f3
        val fileSeed2 = (f2 + b) xor f4
        return DpmxCipherKey(
            al = (seed2 + fileSeed2) and 0xFF,
            cl = (seed1 + fileSeed1) and 0xFF,
            version = version
        )
    }

    private fun writeHspExecutableMetadata(
        bytes: ByteArray,
        dpmOffset: Int,
        deckey: Long,
        includeRuntimeMarker: Boolean = false
    ) {
        val offset = 0x300
        if (includeRuntimeMarker) {
            "HSPHED~~".toByteArray(Charsets.US_ASCII).copyInto(bytes, offset)
        }
        val storedPointer = (dpmOffset - 0x10000).toString()
        storedPointer.toByteArray(Charsets.US_ASCII).copyInto(bytes, offset + 9)
        bytes[offset + 17] = 0
        bytes[offset + 18] = 0
        bytes[offset + 19] = 'x'.code.toByte()
        bytes[offset + 22] = 'y'.code.toByte()
        bytes[offset + 25] = 'd'.code.toByte()
        bytes[offset + 28] = 's'.code.toByte()
        bytes[offset + 31] = 'k'.code.toByte()
        putUInt32(bytes, offset + 32, deckey)
        val sumSeed = ((deckey ushr 24) and 0xFFL).toInt() / 7
        var checksum = 0
        for (index in dpmOffset until bytes.size) {
            checksum = (checksum + (bytes[index].toInt() and 0xFF) + sumSeed) and 0xFFFF
        }
        bytes[offset + 29] = checksum.toByte()
        bytes[offset + 30] = (checksum ushr 8).toByte()
    }

    private data class Fixture(val bytes: ByteArray)

    private fun createPeDpmxFixture(
        name: String,
        payload: ByteArray,
        fileKey: Long,
        overlayOffset: Int = 0x400,
        sectionRawSize: Int = 0x200
    ): Fixture {
        val dataOffset = 0x80
        val dataBlockOffset = overlayOffset + dataOffset
        val bytes = ByteArray(dataBlockOffset + payload.size)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        putUInt32(bytes, 0x3C, 0x80L)
        "PE\u0000\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x80)
        putUInt16(bytes, 0x86, 1)
        putUInt16(bytes, 0x94, 0xE0)

        val optionalHeader = 0x98
        putUInt16(bytes, optionalHeader, 0x10B)
        putUInt32(bytes, optionalHeader + 28, 0x00400000L)

        val sectionHeader = optionalHeader + 0xE0
        ".data".toByteArray(Charsets.US_ASCII).copyInto(bytes, sectionHeader)
        putUInt32(bytes, sectionHeader + 8, sectionRawSize.toLong())
        putUInt32(bytes, sectionHeader + 12, 0x1000L)
        putUInt32(bytes, sectionHeader + 16, sectionRawSize.toLong())
        putUInt32(bytes, sectionHeader + 20, 0x200L)

        "DPMX".toByteArray(Charsets.US_ASCII).copyInto(bytes, overlayOffset)
        putUInt32(bytes, overlayOffset + 4, dataOffset.toLong())
        putUInt32(bytes, overlayOffset + 8, 1L)
        putUInt32(bytes, overlayOffset + 12, 0L)

        val recordOffset = overlayOffset + 16
        name.toByteArray(Charsets.US_ASCII).copyInto(bytes, recordOffset)
        putUInt32(bytes, recordOffset + 16, 0xFFFF_FFFFL)
        putUInt32(bytes, recordOffset + 20, fileKey)
        putUInt32(bytes, recordOffset + 24, 0L)
        putUInt32(bytes, recordOffset + 28, payload.size.toLong())
        payload.copyInto(bytes, dataBlockOffset)

        return Fixture(bytes)
    }

    private fun withSource(bytes: ByteArray, block: (FileRandomAccessSource) -> Unit) {
        val directory = Files.createTempDirectory("ramen-dpmx-parser").toFile()
        try {
            val file = File(directory, "demonophobia.exe")
            file.writeBytes(bytes)
            FileRandomAccessSource(file).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun encrypt(plain: ByteArray, key: DpmxCipherKey): ByteArray {
        val result = ByteArray(plain.size)
        var previous = key.initialDl and 0xFF
        for (index in plain.indices) {
            val current = plain[index].toInt() and 0xFF
            val delta = (current - previous) and 0xFF
            val enc = when (key.version) {
                DpmxCipherVersion.HSP_26_TO_32 -> ((delta xor key.cl) + key.al) and 0xFF
                DpmxCipherVersion.HSP_33_TO_36 -> ((delta + key.al) and 0xFF) xor key.cl
            }
            result[index] = enc.toByte()
            previous = current
        }
        return result
    }

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }
}
