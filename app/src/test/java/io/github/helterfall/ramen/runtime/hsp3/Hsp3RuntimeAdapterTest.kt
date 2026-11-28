package io.github.helterfall.ramen.runtime.hsp3

import io.github.helterfall.ramen.importer.VisualNovelDetector
import io.github.helterfall.ramen.inspection.DpmxKeyResolution
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Hsp3RuntimeAdapterTest {
    @Test
    fun preparesPeDpmxAndStartAxUsingVerifiedSampleKey() {
        withExecutable(createExecutableFixture()) { executable ->
            val prepared = Hsp3RuntimeAdapter().prepare(executable)

            assertEquals(0x400L, prepared.container.overlayOffset)
            assertEquals("start.ax", prepared.scriptEntry?.name)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, prepared.scriptKey?.method)
            assertEquals(0x301L, prepared.program.header.version)
            assertEquals(42L, prepared.program.tokens().single().value)
        }
    }

    @Test
    fun detectorReportsDecodedHspWithLaunchStatus() {
        withExecutable(createExecutableFixture(validCommand = true)) { executable ->
            val detection = VisualNovelDetector().detect(executable.parentFile!!)

            assertEquals(Hsp3RuntimeAdapter.ENGINE_ID, detection.engineId)
            assertEquals("PARSER_AVAILABLE", detection.compatibilityStatus)
            assertTrue(detection.evidence.contains("commands decoded"))
        }
    }

    @Test
    fun autoDetectsEncryptedStartAxWhenNoKnownKeyOrDataScheduleExists() {
        val fixtureKey = DpmxTestKey(al = 0x41, cl = 0xB3)
        withExecutable(
            createExecutableFixture(
                validCommand = true,
                fileKey = 0x12345678L,
                encryptionKey = fixtureKey
            )
        ) { executable ->
            val prepared = Hsp3RuntimeAdapter().prepare(executable)
            assertEquals(DpmxKeyResolution.Method.KNOWN_PLAINTEXT_HSP3, prepared.scriptKey?.method)
            assertEquals(0x301L, prepared.program.header.version)
            assertEquals(0x10L, prepared.program.tokens().single().value)
        }
    }

    @Test
    fun loadsUnpackedStartAxFromGameRootWhenExecutableIsNested() {
        val gameRoot = Files.createTempDirectory("ramen-hsp3-unpacked").toFile()
        try {
            val executableDirectory = File(gameRoot, "bin/runtime")
            assertTrue(executableDirectory.mkdirs())
            val executable = File(executableDirectory, "elonaplus.exe")
            executable.writeBytes(createExecutableFixture(includeScript = false))

            val unpackedAx = File(gameRoot, "start.ax")
            val expectedProgram = createAxFixture(validCommand = true)
            unpackedAx.writeBytes(expectedProgram)

            val prepared = Hsp3RuntimeAdapter().prepare(executable, gameRoot)

            assertEquals(0x301L, prepared.program.header.version)
            assertEquals(0x10L, prepared.program.tokens().single().value)
            assertEquals(unpackedAx.canonicalFile, prepared.unpackedScriptFile)
            assertEquals(null, prepared.scriptEntry)
            assertEquals(null, prepared.scriptKey)
        } finally {
            gameRoot.deleteRecursively()
        }
    }

    @Test
    fun loadsUnpackedStartAxWhenExecutableHasNoDpmxOverlay() {
        val gameRoot = Files.createTempDirectory("ramen-hsp3-plain-ax-only").toFile()
        try {
            val executableDirectory = File(gameRoot, "bin")
            assertTrue(executableDirectory.mkdirs())
            val executable = File(executableDirectory, "game.exe")
            executable.writeBytes(createExecutableFixture(includeScript = false).copyOf(0x400))
            val expectedAx = createAxFixture(validCommand = true)
            File(gameRoot, "start.ax").writeBytes(expectedAx)

            val prepared = Hsp3RuntimeAdapter().prepare(executable, gameRoot)

            assertEquals(0x301L, prepared.program.header.version)
            assertEquals(0x10L, prepared.program.tokens().single().value)
            assertTrue(prepared.container.entries.isEmpty())
            assertEquals(null, prepared.scriptKey)
            assertEquals(File(gameRoot, "start.ax").canonicalFile, prepared.unpackedScriptFile)
        } finally {
            gameRoot.deleteRecursively()
        }
    }

    @Test
    fun missingStartAxDoesNotPrepareAsHsp() {
        val bytes = createExecutableFixture(includeScript = false)
        withExecutable(bytes) { executable ->
            val error = runCatching { Hsp3RuntimeAdapter().prepare(executable) }.exceptionOrNull()
            assertTrue(error is java.io.IOException)
        }
    }

    private fun withExecutable(bytes: ByteArray, block: (File) -> Unit) {
        val directory = Files.createTempDirectory("ramen-hsp3-adapter").toFile()
        try {
            val executable = File(directory, "Demonophobia.exe")
            executable.writeBytes(bytes)
            block(executable)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun createExecutableFixture(
        includeScript: Boolean = true,
        validCommand: Boolean = false,
        fileKey: Long = SAMPLE_FILE_KEY,
        encryptionKey: DpmxTestKey = SAMPLE_KEY
    ): ByteArray {
        val script = createAxFixture(validCommand)
        val payload = if (includeScript) encrypt(script, encryptionKey) else byteArrayOf()
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

        val optional = 0x98
        putUInt16(bytes, optional, 0x10B)
        putUInt32(bytes, optional + 28, 0x00400000L)
        val section = optional + 0xE0
        ".data".toByteArray(Charsets.US_ASCII).copyInto(bytes, section)
        putUInt32(bytes, section + 8, 0x200L)
        putUInt32(bytes, section + 12, 0x1000L)
        putUInt32(bytes, section + 16, 0x200L)
        putUInt32(bytes, section + 20, 0x200L)

        "DPMX".toByteArray(Charsets.US_ASCII).copyInto(bytes, overlayOffset)
        putUInt32(bytes, overlayOffset + 4, dataOffset.toLong())
        putUInt32(bytes, overlayOffset + 8, if (includeScript) 1L else 0L)
        putUInt32(bytes, overlayOffset + 12, 0L)

        if (includeScript) {
            val record = overlayOffset + 16
            "start.ax".toByteArray(Charsets.US_ASCII).copyInto(bytes, record)
            putUInt32(bytes, record + 16, 0xFFFF_FFFFL)
            putUInt32(bytes, record + 20, fileKey)
            putUInt32(bytes, record + 24, 0L)
            putUInt32(bytes, record + 28, script.size.toLong())
            payload.copyInto(bytes, dataBlockOffset)
        }
        return bytes
    }

    private fun createAxFixture(validCommand: Boolean): ByteArray {
        val bytes = ByteArray(0x64)
        "HSP3".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        putUInt32(bytes, 4, 0x301L)
        putUInt32(bytes, 8, 1032L)
        putUInt32(bytes, 12, bytes.size.toLong())
        putUInt32(bytes, 16, 0x60L)
        putUInt32(bytes, 20, 4L)
        if (validCommand) {
            // OpenHSP TYPE_PROGCMD=end command head (EXFLG_1 | TYPE_PROGCMD).
            putUInt16(bytes, 0x60, 0x200F)
            putUInt16(bytes, 0x62, 0x10)
        } else {
            putUInt16(bytes, 0x60, 4)
            putUInt16(bytes, 0x62, 42)
        }
        return bytes
    }

    private fun encrypt(plain: ByteArray, key: DpmxTestKey): ByteArray {
        val encrypted = ByteArray(plain.size)
        var previous = key.initialDl and 0xFF
        for (index in plain.indices) {
            val current = plain[index].toInt() and 0xFF
            val delta = (current - previous) and 0xFF
            encrypted[index] = (((delta xor key.cl) + key.al) and 0xFF).toByte()
            previous = current
        }
        return encrypted
    }

    private data class DpmxTestKey(
        val al: Int,
        val cl: Int,
        val initialDl: Int = 0
    )

    private fun putUInt16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putUInt32(target: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) target[offset + index] = (value ushr (index * 8)).toByte()
    }

    companion object {
        private const val SAMPLE_FILE_KEY = 0xB2B8EDB5L
        private val SAMPLE_KEY = DpmxTestKey(al = 0xB2, cl = 0x53)
    }
}
