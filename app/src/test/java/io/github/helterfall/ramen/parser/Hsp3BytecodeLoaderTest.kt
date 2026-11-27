package io.github.helterfall.ramen.parser

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsp3BytecodeLoaderTest {
    @Test
    fun parsesHsp31HeaderAndDeclaredSegments() {
        withProgramBytes { program ->
            assertEquals(0x301L, program.header.version)
            assertEquals(1032L, program.header.maxValueCount)
            assertEquals(0x78L, program.header.allSizeBytes)
            assertEquals(0x60L, program.header.codeOffset)
            assertEquals(10L, program.header.codeSizeBytes)
            assertEquals(8L, program.header.dataSizeBytes)
            assertEquals(4L, program.header.objectTempSizeBytes)
            assertEquals(2L, program.header.debugInfoSizeBytes)
            assertFalse(program.header.hasExternalPluginData)
            assertEquals("hello", program.stringAt(0))
            assertEquals("llo", program.stringAt(2)) // TYPE_STRING values are raw byte offsets from DS start.
            assertEquals(0L, program.labelCodeOffset(0))
            assertEquals(2, program.tokens().count())
        }
    }

    @Test
    fun decodesWindows31jExtensionCharactersFromTheDataSegment() {
        val bytes = createProgramBytes().apply {
            // CP932 0x8740 is U+2460 CIRCLED DIGIT ONE; strict Shift_JIS rejects it.
            this[0x6A] = 0x87.toByte()
            this[0x6B] = 0x40
            this[0x6C] = 0
        }
        withBytes(bytes) { file ->
            val program = Hsp3BytecodeLoader().load(file)
            assertEquals("\u2460", program.stringAt(0))
        }
    }

    @Test
    fun decodesNarrowAndWideTokenValues() {
        withProgramBytes { program ->
            val tokens = program.tokens().toList()
            assertEquals(4, tokens[0].type)
            assertEquals(42L, tokens[0].value)
            assertEquals(0, tokens[0].extensionFlags)
            assertEquals(4, tokens[0].encodedSizeBytes)

            assertEquals(2, tokens[1].type)
            assertEquals(0x12345678L, tokens[1].value)
            assertEquals(0x8002, readUInt16(createProgramBytes(), 0x64))
            assertEquals(6, tokens[1].encodedSizeBytes)
            assertEquals(4, tokens[1].codeOffset)
        }
    }

    @Test
    fun rejectsInvalidMagicAndSegmentBounds() {
        val badMagic = createProgramBytes().apply { this[0] = 0 }
        val magicError = loadError(badMagic)
        assertTrue(magicError is java.io.IOException)

        val badRange = createProgramBytes().apply { putUInt32(this, 0x14, 0x1000L) }
        val rangeError = loadError(badRange)
        assertTrue(rangeError is java.io.IOException)
    }

    @Test
    fun rejectsTruncatedWideToken() {
        val bytes = createProgramBytes()
        putUInt32(bytes, 0x14, 4L)
        putUInt32(bytes, 0x18, 0L)
        putUInt32(bytes, 0x1C, 0L)
        putUInt32(bytes, 0x20, 0L)
        putUInt32(bytes, 0x24, 0L)
        putUInt32(bytes, 0x28, 0L)
        putUInt32(bytes, 0x2C, 0L)
        putUInt32(bytes, 0x0C, 0x64L)
        putUInt16(bytes, 0x60, 0x8002)
        putUInt16(bytes, 0x62, 0x1234)
        val error = loadError(bytes)
        assertTrue(error is java.io.IOException)
    }

    private fun loadError(bytes: ByteArray): Throwable? {
        var error: Throwable? = null
        withBytes(bytes) { file ->
            error = runCatching { Hsp3BytecodeLoader().load(file).tokens().toList() }.exceptionOrNull()
        }
        return error
    }

    private fun withProgramBytes(block: (Hsp3Program) -> Unit) {
        withBytes(createProgramBytes()) { file ->
            block(Hsp3BytecodeLoader().load(file))
        }
    }

    private fun withBytes(bytes: ByteArray, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("ramen-hsp3-loader").toFile()
        try {
            val file = File(directory, "start.ax")
            file.writeBytes(bytes)
            block(file)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createProgramBytes(): ByteArray {
        val bytes = ByteArray(0x78)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
        putUInt32(bytes, 4, 0x301L)
        putUInt32(bytes, 8, 1032L)
        putUInt32(bytes, 12, bytes.size.toLong())

        putUInt32(bytes, 16, 0x60L)
        putUInt32(bytes, 20, 10L)
        putUInt32(bytes, 24, 0x6AL)
        putUInt32(bytes, 28, 8L)
        putUInt32(bytes, 32, 0x72L)
        putUInt32(bytes, 36, 4L)
        putUInt32(bytes, 40, 0x76L)
        putUInt32(bytes, 44, 2L)

        // Narrow TYPE_INUM=4, value=42.
        putUInt16(bytes, 0x60, 4)
        putUInt16(bytes, 0x62, 42)
        // Wide TYPE_STRING=2, value=0x12345678.
        putUInt16(bytes, 0x64, 0x8002)
        putUInt32(bytes, 0x66, 0x12345678L)

        "hello".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x6A)
        bytes[0x6F] = 0
        putUInt32(bytes, 0x72, 0L)
        bytes[0x76] = 7
        bytes[0x77] = 8
        return bytes
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }
}
