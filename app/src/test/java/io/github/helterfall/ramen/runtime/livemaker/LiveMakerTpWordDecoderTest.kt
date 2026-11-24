package io.github.helterfall.ramen.runtime.livemaker

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMakerTpWordDecoderTest {
    @Test
    fun decodesCp932GlyphsLineBreakPauseAndPageBreak() {
        val bytes = TpWordFixture.build(
            glyphs = listOf(
                TpWordFixture.char('A'),
                TpWordFixture.char('あ'),
                TpWordFixture.returnGlyph(0),
                TpWordFixture.char('B'),
                TpWordFixture.returnGlyph(2),
                TpWordFixture.char('C'),
                TpWordFixture.returnGlyph(1),
                TpWordFixture.char('D')
            )
        )

        val decoded = LiveMakerTpWordDecoder().decode(bytes)

        assertEquals("Aあ\nBCD", decoded.plainText)
        assertEquals(3, decoded.segments.size)
        assertEquals("Aあ\nB", decoded.segments[0].text)
        assertTrue(decoded.segments[0].waitForTap)
        assertEquals("C", decoded.segments[1].text)
        assertTrue(decoded.segments[1].waitForTap)
        assertTrue(decoded.segments[2].clearBefore)
        assertEquals("D", decoded.segments[2].text)
    }

    @Test
    fun resolvesVariablesAndSystemPauseEvent() {
        val bytes = TpWordFixture.build(
            glyphs = listOf(
                TpWordFixture.char('H'),
                TpWordFixture.char('i'),
                TpWordFixture.variable("player"),
                TpWordFixture.systemEvent("PAUSE"),
                TpWordFixture.char('!')
            )
        )

        val decoded = LiveMakerTpWordDecoder().decode(bytes) { name ->
            if (name == "player") "Ramen" else null
        }

        assertEquals("HiRamen!", decoded.plainText)
        assertEquals(2, decoded.segments.size)
        assertEquals("HiRamen", decoded.segments[0].text)
        assertTrue(decoded.segments[0].waitForTap)
        assertEquals("!", decoded.segments[1].text)
    }

    @Test
    fun extractsInlineImageReferences() {
        val decoded = LiveMakerTpWordDecoder().decode(
            TpWordFixture.build(glyphs = listOf(TpWordFixture.image("ui/heart.png")))
        )

        assertEquals(listOf("ui/heart.png"), decoded.inlineImages)
        assertEquals("", decoded.plainText)
    }

    @Test
    fun rejectsTruncatedAndOversizedTpWordStreams() {
        val valid = TpWordFixture.build(glyphs = listOf(TpWordFixture.char('X')))
        assertTrue(runCatching {
            LiveMakerTpWordDecoder().decode(valid.copyOf(valid.size - 2))
        }.exceptionOrNull() is java.io.IOException)

        assertTrue(runCatching {
            LiveMakerTpWordDecoder(maxInputBytes = valid.size - 1).decode(valid)
        }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun oldVersion103OmitsConditionAndLinkTables() {
        val bytes = TpWordFixture.build(
            version = 103,
            glyphs = listOf(TpWordFixture.char('Z', version = 103))
        )
        assertEquals("Z", LiveMakerTpWordDecoder().decode(bytes).plainText)
    }

    private object TpWordFixture {
        private val CP932 = Charset.forName("windows-31j")

        fun build(version: Int = 105, glyphs: List<ByteArray>): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write("TpWord".toByteArray(Charsets.US_ASCII))
                output.write(version.toString().padStart(3, '0').toByteArray(Charsets.US_ASCII))
                writeU32(output, 0) // decorators
                if (version >= 104) writeU32(output, 0) // conditions
                if (version >= 105) writeU32(output, 0) // links
                writeU32(output, glyphs.size.toLong())
                glyphs.forEach(output::write)
            }.toByteArray()

        fun char(value: Char, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(1)
                commonCondition(output, version)
                if (version < 105) writeString(output, "")
                else writeInt32(output, -1)
                writeU32(output, 0) // speed
                val cp932 = value.toString().toByteArray(CP932)
                val code = if (cp932.size == 1) cp932[0].toInt() and 0xFF
                    else ((cp932[0].toInt() and 0xFF) shl 8) or (cp932[1].toInt() and 0xFF)
                writeU16(output, code)
                writeInt32(output, 0) // decorator
            }.toByteArray()

        fun returnGlyph(type: Int, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(3)
                commonCondition(output, version)
                output.write(type)
            }.toByteArray()

        fun systemEvent(name: String, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(6)
                commonCondition(output, version)
                writeString(output, "\u0001" + name)
            }.toByteArray()

        fun variable(name: String, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(7)
                commonCondition(output, version)
                writeInt32(output, 0) // decorator
                if (version > 100) writeU32(output, 0) // unknown
                if (version in 101..104) writeString(output, "")
                if (version >= 105) writeInt32(output, -1) // link
                writeString(output, name)
            }.toByteArray()

        fun image(source: String, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(9)
                commonCondition(output, version)
                if (version < 105) writeString(output, "")
                else writeInt32(output, -1)
                writeU32(output, 0) // speed
                writeString(output, source)
                output.write(0) // align
                if (version >= 103) writeString(output, "")
                if (version >= 105) {
                    repeat(4) { writeInt32(output, 0) }
                    writeString(output, "")
                }
            }.toByteArray()

        private fun commonCondition(output: ByteArrayOutputStream, version: Int) {
            if (version >= 104) writeInt32(output, -1)
        }

        private fun writeString(output: ByteArrayOutputStream, value: String) {
            val bytes = value.toByteArray(CP932)
            writeU32(output, bytes.size.toLong())
            output.write(bytes)
        }

        private fun writeU16(output: ByteArrayOutputStream, value: Int) {
            output.write(value and 0xFF)
            output.write((value ushr 8) and 0xFF)
        }

        private fun writeInt32(output: ByteArrayOutputStream, value: Int) {
            writeU32(output, value.toLong() and 0xFFFF_FFFFL)
        }

        private fun writeU32(output: ByteArrayOutputStream, value: Long) {
            repeat(4) { index ->
                output.write(((value ushr (index * 8)) and 0xFFL).toInt())
            }
        }
    }
}
