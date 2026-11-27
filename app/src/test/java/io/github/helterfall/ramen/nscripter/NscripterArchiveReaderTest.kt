package io.github.helterfall.ramen.nscripter

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NscripterArchiveReaderTest {
    private val reader = NscripterArchiveReader()

    @Test
    fun parsesAndReadsStoredSarEntry() {
        val directory = Files.createTempDirectory("nscripter-sar").toFile()
        try {
            val payload = byteArrayOf(0x11, 0x22, 0x33)
            val archive = File(directory, "arc.sar")
            archive.writeBytes(createSar("images/cg.jpg", payload))

            val index = reader.readIndex(archive)

            assertEquals("SAR", index.format)
            assertEquals(1, index.entries.size)
            assertEquals("images/cg.jpg", index.entries.single().name)
            assertArrayEquals(payload, reader.readEntry(archive, index.entries.single()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun parsesAndReadsUncompressedNsaEntry() {
        val directory = Files.createTempDirectory("nscripter-nsa").toFile()
        try {
            val payload = "voice-data".toByteArray(Charsets.US_ASCII)
            val archive = File(directory, "arc.nsa")
            archive.writeBytes(createNsa("sound/voice.wav", payload, compression = 0))

            val index = reader.readIndex(archive)

            assertEquals("NSA", index.format)
            assertEquals(0, index.entries.single().compressionType)
            assertArrayEquals(payload, reader.readEntry(archive, index.entries.single()))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun decompressesSpbBitmapStoredInNsaArchive() {
        val directory = Files.createTempDirectory("nscripter-nsa-spb").toFile()
        try {
            // 1x1 SPB: big-endian dimensions followed by one literal B, G and R sample.
            val packedSpb = byteArrayOf(0, 1, 0, 1, 0x10, 0x20, 0x30)
            val archive = File(directory, "arc.nsa")
            archive.writeBytes(
                createNsa(
                    name = "cg/one-pixel.bmp",
                    payload = packedSpb,
                    compression = 1,
                    unpackedSize = 58
                )
            )

            val entry = reader.readIndex(archive).entries.single()
            val bmp = reader.readEntry(archive, entry)

            assertEquals(58, bmp.size)
            assertArrayEquals(
                byteArrayOf('B'.code.toByte(), 'M'.code.toByte()),
                bmp.copyOfRange(0, 2)
            )
            assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30), bmp.copyOfRange(54, 57))
            assertEquals(1, bmp[18].toInt() and 0xff)
            assertEquals(1, bmp[22].toInt() and 0xff)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun reportsCompressedNsaCodecExplicitly() {
        val directory = Files.createTempDirectory("nscripter-nsa-compressed").toFile()
        try {
            val archive = File(directory, "arc.nsa")
            archive.writeBytes(createNsa("images/cg.bmp", byteArrayOf(1, 2), compression = 2, unpackedSize = 4))
            val entry = reader.readIndex(archive).entries.single()

            try {
                reader.readEntry(archive, entry)
                throw AssertionError("Expected unsupported-compression exception.")
            } catch (expected: UnsupportedNscripterCompressionException) {
                assertEquals(2, expected.compressionType)
                assertTrue(expected.message?.contains("LZSS") == true)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsTruncatedArchiveHeader() {
        val directory = Files.createTempDirectory("nscripter-invalid-archive").toFile()
        try {
            val archive = File(directory, "broken.sar")
            archive.writeBytes(byteArrayOf(0, 1, 0))

            try {
                reader.readIndex(archive)
                throw AssertionError("Expected IOException for truncated archive.")
            } catch (expected: IOException) {
                assertTrue(expected.message?.contains("header") == true)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createSar(name: String, payload: ByteArray): ByteArray {
        val encodedName = name.toByteArray(Charset.forName("Shift_JIS"))
        val baseOffset = 6 + encodedName.size + 1 + 8
        return ByteArrayOutputStream().apply {
            writeU16(1)
            writeU32(baseOffset.toLong())
            write(encodedName)
            write(0)
            writeU32(0)
            writeU32(payload.size.toLong())
            write(payload)
        }.toByteArray()
    }

    private fun createNsa(
        name: String,
        payload: ByteArray,
        compression: Int,
        unpackedSize: Int = payload.size
    ): ByteArray {
        val encodedName = name.toByteArray(Charset.forName("Shift_JIS"))
        val baseOffset = 6 + encodedName.size + 1 + 13
        return ByteArrayOutputStream().apply {
            writeU16(1)
            writeU32(baseOffset.toLong())
            write(encodedName)
            write(0)
            write(compression)
            writeU32(0)
            writeU32(payload.size.toLong())
            writeU32(unpackedSize.toLong())
            write(payload)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeU16(value: Int) {
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(value: Long) {
        write(((value ushr 24) and 0xFF).toInt())
        write(((value ushr 16) and 0xFF).toInt())
        write(((value ushr 8) and 0xFF).toInt())
        write((value and 0xFF).toInt())
    }
}