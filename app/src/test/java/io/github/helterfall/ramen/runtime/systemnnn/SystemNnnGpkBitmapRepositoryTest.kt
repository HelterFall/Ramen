package io.github.helterfall.ramen.runtime.systemnnn

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnGpkBitmapRepositoryTest {
    @Test
    fun exposesValidatedGtbEntriesForNameAndOffsetLookup() {
        val directory = Files.createTempDirectory("ramen-systemnnn-gpk").toFile()
        try {
            val payload = createJpegWithSof(1, 1)
            val block = ByteArray(64) { ' '.code.toByte() } + payload
            val gpk = File(directory, "scene.gpk").apply { writeBytes(block) }
            val gtb = File(directory, "scene.gtb").apply {
                writeBytes(createGtbIndex("background", block.size))
            }

            SystemNnnGpkBitmapRepository(gtb, gpk).use { repository ->
                assertEquals(1, repository.entries.size)
                assertEquals("background", repository.entries.single().name)
                assertTrue(repository.entries.single().isValid)
                assertEquals(0L, repository.entries.single().blockOffset)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createGtbIndex(name: String, blockSize: Int): ByteArray {
        val nameBytes = name.toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val tableSize = 12
        val result = ByteArray(tableSize + nameBytes.size)
        putU32(result, 0, 1)
        putU32(result, 4, 0)
        putU32(result, 8, 0)
        nameBytes.copyInto(result, tableSize)
        return result
    }

    private fun createJpegWithSof(width: Int, height: Int): ByteArray =
        byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x0B,
            0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x01,
            0x01, 0x11, 0x00,
            0xFF.toByte(), 0xD9.toByte()
        )

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            bytes[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
