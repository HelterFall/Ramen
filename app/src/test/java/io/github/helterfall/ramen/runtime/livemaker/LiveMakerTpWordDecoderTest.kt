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
    fun decodesGraphicAudioAndWaitSystemEventArguments() {
        val bytes = TpWordFixture.build(
            glyphs = listOf(
                TpWordFixture.systemEvent(
                    "CREATECG",
                    arguments = listOf("NAME=background", "SOURCE=room.gal", "X=12", "Y=24")
                ),
                TpWordFixture.systemEvent("PLAYSND", arguments = listOf("SOURCE=ambient.ogg")),
                TpWordFixture.systemEvent("WAIT", arguments = listOf("TIME=250")),
                TpWordFixture.systemEvent(
                    "CHANGECG",
                    arguments = listOf("NAME=background", "SOURCE=night.gal")
                ),
                TpWordFixture.systemEvent("DELETECG", arguments = listOf("NAME=background"))
            )
        )

        val decoded = LiveMakerTpWordDecoder().decode(bytes)

        assertEquals(
            listOf("CREATECG", "PLAYSND", "WAIT", "CHANGECG", "DELETECG"),
            decoded.events.map { it.name }
        )
        assertEquals(
            listOf("NAME=background", "SOURCE=room.gal", "X=12", "Y=24"),
            decoded.events[0].arguments
        )
        assertEquals(listOf("SOURCE=ambient.ogg"), decoded.events[1].arguments)
        assertEquals(listOf("TIME=250"), decoded.events[2].arguments)
        assertEquals(listOf("NAME=background", "SOURCE=night.gal"), decoded.events[3].arguments)
        assertEquals(listOf("NAME=background"), decoded.events[4].arguments)
    }

    @Test
    fun parsesDecoratorConditionLinkTablesAndEveryKnownGlyphType() {
        val bytes = TpWordFixture.buildWithTables(
            version = 105,
            glyphs = listOf(
                TpWordFixture.charWithReferences('A', condition = 0, link = 0, decorator = 0),
                TpWordFixture.div(condition = 0, align = 3, left = 4, right = 5, noHeight = 1),
                TpWordFixture.returnGlyph(0),
                TpWordFixture.simpleGlyph(4),
                TpWordFixture.simpleGlyph(5),
                TpWordFixture.event("NAMELABEL", listOf("speaker")),
                TpWordFixture.variable("flag", decorator = 0),
                TpWordFixture.historyVariable("last_line", decorator = 0),
                TpWordFixture.image("inline.png", condition = 0, link = 0),
                byteArrayOf(0x7F)
            )
        )

        val decoded = LiveMakerTpWordDecoder().decode(bytes) { name ->
            if (name == "flag") "enabled" else null
        }

        assertEquals(105, decoded.version)
        assertEquals(1, decoded.decorators.size)
        assertEquals("font-name", decoded.decorators.single().unk8)
        assertEquals("ruby", decoded.decorators.single().ruby)
        assertEquals(1L, decoded.decorators.single().count)
        assertEquals(listOf(LiveMakerTpWordCondition(7L, "history")), decoded.conditions)
        assertEquals(listOf(LiveMakerTpWordLink(2L, "CLICK", "reserved")), decoded.links)
        assertEquals("A\nenabled", decoded.plainText)
        assertEquals(1, decoded.characterCount)
        assertEquals(listOf("inline.png"), decoded.inlineImages)
        assertEquals("NAMELABEL", decoded.events.single().name)
        assertFalse(decoded.events.single().isSystem)
        assertEquals(listOf("speaker"), decoded.events.single().arguments)

        assertEquals(10, decoded.body.size)
        assertEquals(0, decoded.body[0].conditionIndex)
        assertEquals(0, decoded.body[0].linkIndex)
        assertEquals(0, decoded.body[0].decoratorIndex)
        assertEquals(33L, decoded.body[0].textSpeed)
        assertEquals(3, decoded.body[1].alignment)
        assertEquals(4, decoded.body[1].padLeft)
        assertEquals(5, decoded.body[1].padRight)
        assertEquals(1, decoded.body[1].noHeight)
        assertEquals(0, decoded.body[2].breakType)
        assertEquals(4, decoded.body[3].typeId)
        assertEquals(5, decoded.body[4].typeId)
        assertEquals("flag", decoded.body[6].variableName)
        assertEquals("last_line", decoded.body[7].variableName)
        assertEquals("inline.png", decoded.body[8].imageSource)
        assertEquals("hover.png", decoded.body[8].hoverImageSource)
        assertEquals(1, decoded.body[8].marginLeft)
        assertEquals(4, decoded.body[8].marginBottom)
        assertEquals("pressed.png", decoded.body[8].downImageSource)
        assertEquals(0x7F, decoded.body[9].typeId)
    }

    @Test
    fun readsLegacyVariableLiveParserBeforeVersion102() {
        val bytes = TpWordFixture.build(
            version = 101,
            glyphs = listOf(
                TpWordFixture.legacyVariable("player"),
                TpWordFixture.char('!')
            )
        )

        val decoded = LiveMakerTpWordDecoder().decode(bytes) { name ->
            if (name == "player") "Hero" else null
        }

        assertEquals("Hero!", decoded.plainText)
        assertEquals("player", decoded.body.first().legacyVariableExpression)
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
                writeHeader(output, version)
                writeU32(output, 0) // decorators
                if (version >= 104) writeU32(output, 0) // conditions
                if (version >= 105) writeU32(output, 0) // links
                writeBody(output, glyphs)
            }.toByteArray()

        fun buildWithTables(version: Int, glyphs: List<ByteArray>): ByteArray =
            ByteArrayOutputStream().also { output ->
                writeHeader(output, version)
                writeU32(output, 1) // decorators
                writeU32(output, 1) // glyph count
                writeU32(output, 2)
                writeU32(output, 3)
                writeU32(output, 4)
                output.write(5)
                output.write(6)
                writeU32(output, 7)
                writeString(output, "font-name")
                writeString(output, "ruby")
                writeU32(output, 10)
                writeU32(output, 11)

                if (version >= 104) {
                    writeU32(output, 1) // conditions
                    writeU32(output, 7)
                    writeString(output, "history")
                }
                if (version >= 105) {
                    writeU32(output, 1) // links
                    writeU32(output, 2)
                    writeString(output, "CLICK")
                    writeString(output, "reserved")
                }
                writeBody(output, glyphs)
            }.toByteArray()

        private fun writeHeader(output: ByteArrayOutputStream, version: Int) {
            output.write("TpWord".toByteArray(Charsets.US_ASCII))
            output.write(version.toString().padStart(3, '0').toByteArray(Charsets.US_ASCII))
        }

        private fun writeBody(output: ByteArrayOutputStream, glyphs: List<ByteArray>) {
            writeU32(output, glyphs.size.toLong())
            glyphs.forEach(output::write)
        }

        fun char(value: Char, version: Int = 105): ByteArray =
            charWithReferences(value, version = version, condition = -1, link = -1, decorator = 0)

        fun charWithReferences(
            value: Char,
            version: Int = 105,
            condition: Int,
            link: Int,
            decorator: Int
        ): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(1)
                if (version >= 104) writeInt32(output, condition)
                if (version < 105) writeString(output, "")
                else writeInt32(output, link)
                writeU32(output, 33) // speed
                val cp932 = value.toString().toByteArray(CP932)
                val code = if (cp932.size == 1) cp932[0].toInt() and 0xFF
                    else ((cp932[0].toInt() and 0xFF) shl 8) or (cp932[1].toInt() and 0xFF)
                writeU16(output, code)
                writeInt32(output, decorator)
            }.toByteArray()

        fun div(
            condition: Int,
            align: Int,
            left: Int,
            right: Int,
            noHeight: Int
        ): ByteArray = ByteArrayOutputStream().also { output ->
            output.write(2)
            writeInt32(output, condition)
            output.write(align)
            writeInt32(output, left)
            writeInt32(output, right)
            output.write(noHeight)
        }.toByteArray()

        fun simpleGlyph(type: Int): ByteArray =
            byteArrayOf(type.toByte(), 0, 0, 0, 0)

        fun returnGlyph(type: Int, version: Int = 105): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(3)
                commonCondition(output, version)
                output.write(type)
            }.toByteArray()

        fun systemEvent(
            name: String,
            version: Int = 105,
            arguments: List<String> = emptyList()
        ): ByteArray = event("\\u0001" + name, arguments, version)

        fun event(
            name: String,
            arguments: List<String>,
            version: Int = 105
        ): ByteArray = ByteArrayOutputStream().also { output ->
            output.write(6)
            commonCondition(output, version)
            writeString(output, (listOf(name) + arguments).joinToString("\r\n"))
        }.toByteArray()

        fun variable(
            name: String,
            version: Int = 105,
            decorator: Int = 0
        ): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(7)
                commonCondition(output, version)
                writeInt32(output, decorator)
                if (version > 100) writeU32(output, 0)
                if (version in 101..104) writeString(output, "")
                if (version >= 105) writeInt32(output, -1)
                writeString(output, name)
            }.toByteArray()

        fun historyVariable(name: String, decorator: Int = 0): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(0x0A)
                writeInt32(output, 0) // condition
                writeInt32(output, decorator)
                writeU32(output, 0) // reserved
                writeString(output, "") // legacy link is omitted for v105
                writeInt32(output, -1) // link
                writeString(output, name)
            }.toByteArray()

        fun legacyVariable(name: String): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(7)
                writeInt32(output, 0) // decorator
                writeU32(output, 0) // unk3 (v101)
                writeString(output, "legacy-link")
                writeU32(output, 1) // LiveParser entry count
                output.write(1) // OpeDataType.To
                writeString(output, "____arg")
                writeU32(output, 1) // operand count
                output.write(0) // ParamType.Var
                writeString(output, name)
            }.toByteArray()

        fun image(
            source: String,
            version: Int = 105,
            condition: Int = -1,
            link: Int = -1
        ): ByteArray =
            ByteArrayOutputStream().also { output ->
                output.write(9)
                commonCondition(output, version, condition)
                if (version < 105) writeString(output, "")
                else writeInt32(output, link)
                writeU32(output, 0) // speed
                writeString(output, source)
                output.write(0) // align
                if (version >= 103) writeString(output, "hover.png")
                if (version >= 105) {
                    writeInt32(output, 1)
                    writeInt32(output, 2)
                    writeInt32(output, 3)
                    writeInt32(output, 4)
                    writeString(output, "pressed.png")
                }
            }.toByteArray()

        private fun commonCondition(
            output: ByteArrayOutputStream,
            version: Int,
            condition: Int = -1
        ) {
            if (version >= 104) writeInt32(output, condition)
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
