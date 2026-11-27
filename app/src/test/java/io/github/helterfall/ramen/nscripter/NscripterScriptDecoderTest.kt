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
    fun loadsUtf8OnscripterEntryAndNumberedSegmentsWithCyrillic() {
        val root = Files.createTempDirectory("onscripter-utf8-script").toFile()
        try {
            File(root, "0.utf").writeText(
                ";mode800\n*define\n*start\nn \"Привет, мир!\"\n",
                Charsets.UTF_8
            )
            File(root, "01.utf").writeText("bg \"room.bmp\",1\n", Charsets.UTF_8)
            File(root, "2.utf").writeText("end\n", Charsets.UTF_8)

            val scripts = decoder.loadScripts(root)

            assertEquals(listOf("0.utf", "01.utf", "2.utf"), scripts.map { it.relativePath })
            assertEquals("UTF-8", scripts.first().encoding)
            assertEquals("utf8-script", scripts.first().mode)
            assertTrue(scripts.first().text.contains("Привет, мир!"))
            assertTrue(scripts.first().text.contains(";mode800"))
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

    @Test
    fun usesTwoDigitFallbackWhenUnpaddedSegmentIsMissing() {
        val root = Files.createTempDirectory("nscripter-padded-segment").toFile()
        try {
            File(root, "0.txt").writeText("*start\n")
            File(root, "01.txt").writeText("bg \"first.bmp\",1\n")
            File(root, "02.txt").writeText("end\n")

            assertEquals(
                listOf("0.txt", "01.txt", "02.txt"),
                decoder.findScriptFiles(root).map { it.name }
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsProtectedScriptInsteadOfFallingThroughToNscriptDat() {
        val root = Files.createTempDirectory("nscripter-protected-priority").toFile()
        try {
            File(root, "nscr_sec.dat").writeBytes(byteArrayOf(1, 2, 3))
            File(root, "nscript.dat").writeBytes(byteArrayOf(1, 2, 3))

            try {
                decoder.findScriptFiles(root)
                throw AssertionError("Expected an explicit unsupported-format error.")
            } catch (expected: UnsupportedNscripterScriptFormatException) {
                assertEquals("nscr_sec.dat", expected.fileName)
            }
        } finally {
            root.deleteRecursively()
        }
    }

}
