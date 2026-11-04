package io.github.helterfall.ramen.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemNnnScriptLoaderTest {

    @Test
    fun parsesBothPoolsFromLogicalAddressTablesAndPreservesRawRecords() {
        val directory = Files.createTempDirectory("ramen-system-nnn-pools").toFile()
        try {
            val file = File(directory, "sample.spt")
            file.writeBytes(
                createFixture(
                    primaryRecords = listOf(
                        "text".toByteArray(Charsets.US_ASCII),
                        "テスト".toByteArray(SHIFT_JIS),
                        byteArrayOf(0x82.toByte(), 0x20)
                    ),
                    secondaryRecords = listOf(
                        "option".toByteArray(Charsets.US_ASCII),
                        "target-module".toByteArray(Charsets.US_ASCII)
                    )
                )
            )

            val script = SystemNnnScriptLoader().load(file)
            val primary = script.stringPools[0]
            val secondary = script.stringPools[1]

            assertEquals(20u, script.firstHeaderWord)
            assertEquals(4, script.offsetTables.size)
            assertEquals(3u, script.offsetTables[0].entryCount)
            assertEquals(0xC5BAu, script.offsetTables[0].logicalBase)
            assertEquals(2u, script.offsetTables[1].entryCount)
            assertEquals(0x167F2u, script.offsetTables[1].logicalBase)
            assertEquals(69u, script.offsetTables[2].entryCount)
            assertEquals(2u, script.offsetTables[3].entryCount)
            assertFalse(script.offsetTables[3].hasPayload)

            assertEquals(0x40L, script.commandStartOffset)
            assertEquals(0x100L, script.commandEndOffset)
            assertEquals(0xC0, script.commandByteCount)

            assertEquals(0x100L, primary.addressTableOffset)
            assertEquals(0x10CL, primary.dataOffset)
            assertEquals(3, primary.entries.size)
            assertEquals(3, script.textPool.size)
            assertEquals("text", script.textPool[0].text)
            assertEquals("テスト", script.textPool[1].text)
            assertTrue(script.textPool[2].hasDecodingErrors)
            assertEquals(listOf(0x82.toByte(), 0x20.toByte()), script.textPool[2].rawBytes.toList())
            assertEquals(primary.entries[1].offset, script.textPool[1].offset)
            assertEquals(script.textPool[1], script.textEntryAtOffset(script.textPool[1].offset))
            assertNull(
                "An interior byte is not a valid record pointer.",
                script.textEntryAtOffset(script.textPool[1].offset + 1L)
            )

            assertEquals(2, secondary.entries.size)
            assertEquals("option", secondary.entries[0].text)
            assertEquals("target-module", secondary.entries[1].text)
            assertEquals(primary.endOffset!! + 16L, secondary.addressTableOffset)
            assertEquals(file.length().toLong(), secondary.endOffset)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun supportsModulesWithAnEmptyPrimaryPool() {
        val directory = Files.createTempDirectory("ramen-system-nnn-empty-pool").toFile()
        try {
            val file = File(directory, "sys.spt")
            file.writeBytes(
                createFixture(
                    primaryRecords = emptyList(),
                    secondaryRecords = listOf(
                        "01".toByteArray(Charsets.US_ASCII),
                        "02".toByteArray(Charsets.US_ASCII)
                    ),
                    primaryBase = 0x1A9,
                    secondaryBase = 0x1AD
                )
            )

            val script = SystemNnnScriptLoader().load(file)

            assertTrue(script.textPool.isEmpty())
            assertEquals(0u, script.stringPools[0].entryCount)
            assertNull(script.stringPools[0].addressTableOffset)
            assertEquals(2, script.secondaryTextPool.size)
            assertEquals("01", script.secondaryTextPool[0].text)
            assertEquals(0x100L, script.stringPools[1].addressTableOffset)
            assertEquals(script.stringPools[1].dataOffset, script.textPoolOffset)
            assertEquals(0x100L, script.commandEndOffset)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun parsesAllTwentyRecordedModuleDescriptorShapes() {
        val modules = listOf(
            ModuleShape("01", 1299, 0xC5BA, 1124, 0x167F2),
            ModuleShape("_staff", 0, 0x540, 29, 0x544),
            ModuleShape("02", 1074, 0x9CA2, 987, 0x12E7D),
            ModuleShape("03", 2606, 0x1A3F3, 2843, 0x2E70F),
            ModuleShape("04", 816, 0x94C6, 964, 0xF573),
            ModuleShape("05", 900, 0x6F0C, 640, 0xDCA2),
            ModuleShape("06", 191, 0x17C4, 90, 0x319A),
            ModuleShape("07", 924, 0x73E5, 602, 0xE52D),
            ModuleShape("08", 302, 0x2DC2, 292, 0x53BD),
            ModuleShape("082", 2088, 0xF7DA, 1664, 0x19B36),
            ModuleShape("akane", 6063, 0x293B1, 3660, 0x5A7B7),
            ModuleShape("aoi", 5571, 0x20CF9, 2722, 0x4CA3B),
            ModuleShape("backspt", 0, 0x7F21, 916, 0x7F25),
            ModuleShape("day", 0, 0x150A, 106, 0x150E),
            ModuleShape("kiika", 6416, 0x28BEA, 3624, 0x58F08),
            ModuleShape("op1", 32, 0x4E79, 384, 0x5211),
            ModuleShape("sys", 0, 0x1A9, 17, 0x1AD),
            ModuleShape("yamiko", 4424, 0x1F193, 2882, 0x410B1),
            ModuleShape("yuka", 4068, 0x1BBF8, 2542, 0x3A6DE),
            ModuleShape("yuka2", 609, 0x669A, 524, 0xABC5)
        )
        val directory = Files.createTempDirectory("ramen-system-nnn-module-shapes").toFile()
        try {
            modules.forEach { module ->
                val file = File(directory, module.name + ".spt")
                file.writeBytes(
                    createFixtureWithCounts(
                        primaryCount = module.primaryCount,
                        primaryBase = module.primaryBase,
                        secondaryCount = module.secondaryCount,
                        secondaryBase = module.secondaryBase
                    )
                )

                val script = SystemNnnScriptLoader().load(file)
                assertEquals(module.name, module.primaryCount, script.textPool.size)
                assertEquals(module.name, module.secondaryCount, script.secondaryTextPool.size)
                assertEquals(module.primaryCount.toUInt(), script.stringPools[0].entryCount)
                assertEquals(module.secondaryCount.toUInt(), script.stringPools[1].entryCount)
                assertEquals(module.name, module.primaryBase.toUInt(), script.stringPools[0].logicalBase)
                assertEquals(module.name, module.secondaryBase.toUInt(), script.stringPools[1].logicalBase)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsNonIncreasingLogicalAddresses() {
        val directory = Files.createTempDirectory("ramen-system-nnn-invalid-addresses").toFile()
        try {
            val file = File(directory, "invalid.spt")
            file.writeBytes(
                createFixture(
                    primaryRecords = listOf(
                        "one".toByteArray(Charsets.US_ASCII),
                        "two".toByteArray(Charsets.US_ASCII)
                    ),
                    secondaryRecords = listOf("next".toByteArray(Charsets.US_ASCII))
                )
            )
            val damaged = file.readBytes()
            putUInt32(damaged, 0x104, readUInt32(damaged, 0x100).toInt())
            file.writeBytes(damaged)

            val failure = runCatching {
                SystemNnnScriptLoader().load(file)
            }.exceptionOrNull()

            assertTrue(failure is IOException)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsWrongSignatureAtFixedOffset() {
        val directory = Files.createTempDirectory("ramen-system-nnn-invalid-signature").toFile()
        try {
            val file = File(directory, "invalid.spt")
            val masked = createFixture(
                primaryRecords = listOf("one".toByteArray(Charsets.US_ASCII)),
                secondaryRecords = listOf("two".toByteArray(Charsets.US_ASCII))
            )
            masked[0x30] = ('X'.code xor 0xFF).toByte()
            file.writeBytes(masked)

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
            file.writeBytes(
                createFixture(
                    primaryRecords = listOf("one".toByteArray(Charsets.US_ASCII)),
                    secondaryRecords = listOf("two".toByteArray(Charsets.US_ASCII))
                )
            )

            assertTrue(SystemNnnScriptLoader.hasValidHeader(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class ModuleShape(
        val name: String,
        val primaryCount: Int,
        val primaryBase: Int,
        val secondaryCount: Int,
        val secondaryBase: Int
    )

    private fun createFixtureWithCounts(
        primaryCount: Int,
        primaryBase: Int,
        secondaryCount: Int,
        secondaryBase: Int
    ): ByteArray = createFixture(
        primaryRecords = List(primaryCount) { byteArrayOf('p'.code.toByte()) },
        secondaryRecords = List(secondaryCount) { byteArrayOf('s'.code.toByte()) },
        primaryBase = primaryBase,
        secondaryBase = secondaryBase
    )

    private fun createFixture(
        primaryRecords: List<ByteArray>,
        secondaryRecords: List<ByteArray>,
        primaryBase: Int = 0xC5BA,
        secondaryBase: Int = 0x167F2
    ): ByteArray {
        require(secondaryRecords.isNotEmpty())

        val header = ByteArray(0x100)
        putUInt32(header, 0x00, 20)
        putUInt32(header, 0x10, primaryRecords.size)
        putUInt32(header, 0x14, primaryBase)
        putUInt32(header, 0x18, secondaryRecords.size)
        putUInt32(header, 0x1C, secondaryBase)
        putUInt32(header, 0x20, 69)
        putUInt32(header, 0x24, 0xC390)
        putUInt32(header, 0x28, 2)
        putUInt32(header, 0x2C, 0)
        "SPTHEADER0".toByteArray(Charsets.US_ASCII).copyInto(header, 0x30)

        val output = ByteArrayOutputStream()
        output.write(header)

        if (primaryRecords.isNotEmpty()) {
            val primaryAddresses = logicalAddresses(primaryBase, primaryRecords)
            primaryAddresses.forEach { writeUInt32(output, it.toLong()) }
            writeRecords(output, primaryRecords)

            // The first four bytes vary between scripts. The trailing 12 bytes
            // are the stable inter-pool marker validated by the current samples.
            writeUInt32(output, 0x12345678)
            writeUInt16(output, 1)
            writeUInt16(output, 0x6666)
            writeUInt16(output, 2)
            writeUInt16(output, 0x5555)
            writeUInt16(output, 1)
            writeUInt16(output, 0x4444)
        }

        val secondaryAddresses = logicalAddresses(secondaryBase, secondaryRecords)
        secondaryAddresses.forEach { writeUInt32(output, it.toLong()) }
        writeRecords(output, secondaryRecords)

        return mask(output.toByteArray())
    }

    private fun logicalAddresses(base: Int, records: List<ByteArray>): List<Long> {
        var nextAddress = base.toLong() + records.size.toLong()
        return records.map { record ->
            val current = nextAddress
            val alignedRecordBytes = ((record.size + 1 + 3) / 4) * 4
            nextAddress += (alignedRecordBytes / 4).toLong()
            current
        }
    }

    private fun writeRecords(output: ByteArrayOutputStream, records: List<ByteArray>) {
        records.forEach { record ->
            output.write(record)
            output.write(0)
            while (output.size() % 4 != 0) {
                output.write(0)
            }
        }
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Int) {
        repeat(4) { index ->
            target[offset + index] = (value ushr (index * 8)).toByte()
        }
    }

    private fun writeUInt32(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { index ->
            output.write(((value ushr (index * 8)) and 0xFFL).toInt())
        }
    }

    private fun writeUInt16(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFFL) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

    private fun mask(decoded: ByteArray): ByteArray = ByteArray(decoded.size) { index ->
        (decoded[index].toInt() xor 0xFF).toByte()
    }

    companion object {
        private val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    }
}
