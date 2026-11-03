package io.github.helterfall.ramen.parser

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnScriptLoaderTest {
    @Test
    fun parsesXorHeaderTablesCommandWindowAndShiftJisTextPool() {
        val directory = Files.createTempDirectory("ramen-system-nnn-loader").toFile()

        try {
            val file = File(directory, "sample.spt")
            file.writeBytes(createFixture())

            val script = SystemNnnScriptLoader().load(file)

            assertEquals(20u, script.firstHeaderWord)
            assertEquals(4, script.offsetTables.size)
            assertEquals(1299u, script.offsetTables[0].entryCount)
            assertEquals(0xC5BAu, script.offsetTables[0].offset)
            assertEquals(1124u, script.offsetTables[1].entryCount)
            assertEquals(0x167F2u, script.offsetTables[1].offset)
            assertEquals(69u, script.offsetTables[2].entryCount)
            assertEquals(0xC390u, script.offsetTables[2].offset)
            assertEquals(2u, script.offsetTables[3].entryCount)
            assertEquals(0u, script.offsetTables[3].offset)
            assertFalse(script.offsetTables[3].hasPayload)

            assertEquals(0xC5BAL, script.commandStartOffset)
            assertEquals(0x32B20L, script.commandEndOffset)
            assertTrue(script.commandByteCount > 0)

            val firstOpcodes = script.instructions().take(2).toList()
            assertEquals(0x7F, firstOpcodes[0].opcode)
            assertEquals(0x01, firstOpcodes[1].opcode)
            assertEquals(0xC5BAL, firstOpcodes[0].offset)

            assertEquals(2, script.textPool.size)
            assertEquals("テスト", script.textPool[0].text)
            assertEquals("second line", script.textPool[1].text)
            assertEquals(
                "テスト".toByteArray(Charsets.UTF_8).toList(),
                script.textPool[0].utf8Bytes.toList()
            )
            assertEquals(0x32B64L, script.textPool[0].offset)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongSignatureAtFixedOffset() {
        val directory = Files.createTempDirectory("ramen-system-nnn-invalid").toFile()

        try {
            val file = File(directory, "invalid.spt")
            val decoded = createDecodedFixture()
            decoded[0x30] = 'X'.code.toByte()
            file.writeBytes(mask(decoded))

            val failure = runCatching {
                SystemNnnScriptLoader().load(file)
            }.exceptionOrNull()

            assertTrue(failure is IOException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun signatureProbeChecksDemaskedBytesAtOffsetThirty() {
        val directory = Files.createTempDirectory("ramen-system-nnn-probe").toFile()

        try {
            val file = File(directory, "probe.spt")
            file.writeBytes(createFixture())

            assertTrue(SystemNnnScriptLoader.hasValidHeader(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createFixture(): ByteArray = mask(createDecodedFixture())

    private fun createDecodedFixture(): ByteArray {
        val decoded = ByteArray(SystemNnnScriptLoader.TEXT_POOL_OFFSET + 64)
        putUInt32(decoded, 0x00, 20)
        putUInt32(decoded, 0x10, 1299)
        putUInt32(decoded, 0x14, 0xC5BA)
        putUInt32(decoded, 0x18, 1124)
        putUInt32(decoded, 0x1C, 0x167F2)
        putUInt32(decoded, 0x20, 69)
        putUInt32(decoded, 0x24, 0xC390)
        putUInt32(decoded, 0x28, 2)
        putUInt32(decoded, 0x2C, 0)
        "SPTHEADER0".toByteArray(Charsets.US_ASCII).copyInto(decoded, 0x30)

        decoded[0xC5BA] = 0x7F
        decoded[0xC5BB] = 0x01

        val sjis = Charset.forName("Shift_JIS")
        val first = "テスト".toByteArray(sjis)
        val second = "second line".toByteArray(sjis)
        var cursor = SystemNnnScriptLoader.TEXT_POOL_OFFSET
        first.copyInto(decoded, cursor)
        cursor += first.size
        decoded[cursor++] = 0
        second.copyInto(decoded, cursor)
        cursor += second.size
        decoded[cursor] = 0

        return decoded
    }

    private fun mask(decoded: ByteArray): ByteArray = ByteArray(decoded.size) { index ->
        (decoded[index].toInt() xor 0xFF).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            target[offset + index] = (value ushr (index * 8)).toByte()
        }
    }
}
