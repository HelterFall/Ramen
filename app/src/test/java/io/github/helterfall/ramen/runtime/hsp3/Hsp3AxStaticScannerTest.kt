package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.parser.Hsp3BytecodeLoader
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsp3AxStaticScannerTest {
    @Test
    fun exportsOpcodeHistogramAndUnsupportedCommandsAsJson() {
        val directory = Files.createTempDirectory("ramen-hsp-scan").toFile()
        try {
            val ax = File(directory, "start.ax")
            ax.writeBytes(createAx())
            val report = Hsp3AxStaticScanner.scan(Hsp3BytecodeLoader().load(ax))
            val json = report.toJson()
            assertEquals(2, report.opcodeCount)
            assertEquals(1, report.unsupportedCommands.size)
            assertTrue(json.contains("\"format\": \"ramen-hsp3-scan-v1\""))
            assertTrue(json.contains("\"hspVersion\": 769"))
            assertTrue(json.contains("\"tokenType\": 15, \"id\": 16, \"count\": 1"))
            assertTrue(json.contains("\"tokenType\": 9, \"id\": 119, \"count\": 1"))
            assertTrue(json.contains("\"unsupportedCommands\""))
            assertTrue(json.contains("\"codeOffset\": 4"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createAx(): ByteArray {
        val bytes = ByteArray(0x68)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        putU32(bytes, 4, 0x301)
        putU32(bytes, 8, 32)
        putU32(bytes, 12, bytes.size)
        putU32(bytes, 16, 0x60)
        putU32(bytes, 20, 8)
        putU16(bytes, 0x60, 0x200F)
        putU16(bytes, 0x62, 0x10)
        putU16(bytes, 0x64, 0x2009)
        putU16(bytes, 0x66, 0x77)
        return bytes
    }

    private fun putU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }
    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
    }
}
