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
        val plainAx = ByteArray(96)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(plainAx)
        putUInt32(plainAx, 4, 0x301L)
        val key = DpmxCipherKey(al = 0xB2, cl = 0x53)
        val fixture = createPeDpmxFixture("start.ax", encrypt(plainAx, key), 0xB2B8EDB5L)
        withSource(fixture.bytes) { source ->
            val container = DpmxContainerParser().parse(source)
            val resolution = DpmxKeyResolver().resolve(source, container, container.entries.single())
            assertNotNull(resolution)
            assertEquals(DpmxKeyResolution.Method.VERIFIED_SAMPLE_VECTOR, resolution!!.method)
            assertEquals(key, resolution.key)
        }
    }

    @Test
    fun autodetectsBmpKeyFromSignatureAndDeclaredSize() {
        val plainBmp = ByteArray(58)
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

    private data class Fixture(val bytes: ByteArray)

    private fun createPeDpmxFixture(
        name: String,
        payload: ByteArray,
        fileKey: Long
    ): Fixture {
        val overlayOffset = 0x400
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
        putUInt32(bytes, sectionHeader + 8, 0x200L)
        putUInt32(bytes, sectionHeader + 12, 0x1000L)
        putUInt32(bytes, sectionHeader + 16, 0x200L)
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
            val enc = ((delta xor key.cl) + key.al) and 0xFF
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
