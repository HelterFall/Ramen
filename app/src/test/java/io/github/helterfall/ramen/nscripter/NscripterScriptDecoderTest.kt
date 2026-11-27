package io.github.helterfall.ramen.nscripter

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NscripterScriptDecoderTest {
    private val decoder = NscripterScriptDecoder()

    @Test
    fun decodesNscriptDatWithStandardXorKey() {
        val plain = "*start\nbg \"room.jpg\",1\nn \"hello\"\n"
            .toByteArray(Charset.forName("Shift_JIS"))
        val encrypted = ByteArray(plain.size) { index ->
            (plain[index].toInt() xor 0x84).toByte()
        }

        val decoded = decoder.decodeNscriptDat(encrypted)

        assertEquals(plain.toString(Charset.forName("Shift_JIS")), decoded.text)
        assertEquals("xor-0x84", decoded.mode)
    }

    @Test
    fun prefersZeroTxtAndAppendsNumberedTextSegments() {
        val root = Files.createTempDirectory("nscripter-script-order").toFile()
        try {
            File(root, "0.txt").writeText("*zero\n")
            File(root, "00.txt").writeText("*double-zero-should-not-win\n")
            File(root, "1.txt").writeText("bg \"one.bmp\",1\n")
            File(root, "99.txt").writeText("end\n")

            val scripts = decoder.loadScripts(root)

            assertEquals(listOf("0.txt", "1.txt", "99.txt"), scripts.map { it.relativePath })
            assertTrue(scripts.first().text.contains("*zero"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun usesDoubleZeroWhenZeroTxtIsAbsent() {
        val root = Files.createTempDirectory("nscripter-double-zero").toFile()
        try {
            File(root, "00.txt").writeText("*start\n")
            File(root, "2.txt").writeText("n \"segment\"\n")

            assertEquals(
                listOf("00.txt", "2.txt"),
                decoder.findScriptFiles(root).map { it.name }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun fallsBackToNscriptDatWhenNoPlainEntryScriptExists() {
        val root = Files.createTempDirectory("nscripter-dat-entry").toFile()
        try {
            val plain = "*start\nend\n".toByteArray(Charset.forName("Shift_JIS"))
            val encoded = ByteArray(plain.size) { index ->
                (plain[index].toInt() xor 0x84).toByte()
            }
            File(root, "nscript.dat").writeBytes(encoded)

            val scripts = decoder.loadScripts(root)

            assertEquals(listOf("nscript.dat"), scripts.map { it.relativePath })
            assertEquals("*start\nend\n", scripts.single().text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun reportsEmbeddedNulInsteadOfSilentlyParsingCorruptScript() {
        val plain = byteArrayOf('b'.code.toByte(), 0, 'g'.code.toByte())
        val encrypted = ByteArray(plain.size) { index ->
            (plain[index].toInt() xor 0x84).toByte()
        }

        try {
            decoder.decodeNscriptDat(encrypted)
            throw AssertionError("Expected an IOException for an embedded NUL.")
        } catch (expected: java.io.IOException) {
            assertTrue(expected.message?.contains("NUL") == true)
        }
    }
}