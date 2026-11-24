package io.github.helterfall.ramen.runtime.livemaker

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LiveMakerTextControl {
    PAUSE,
    PAGE_BREAK,
    CLEAR
}

data class LiveMakerTextSegment(
    val text: String,
    val waitForTap: Boolean = false,
    val clearBefore: Boolean = false
)

data class LiveMakerDecodedTpWord(
    val version: Int,
    val segments: List<LiveMakerTextSegment>,
    val inlineImages: List<String>
) {
    val plainText: String
        get() = segments.joinToString("") { it.text }

    val requiresTap: Boolean
        get() = segments.any { it.waitForTap }
}

/**
 * Bounded decoder for the compiled TpWord glyph stream embedded in LiveMaker TextIns.
 * Based on pylivemaker's TWdGlyph / TpWord structures. The TpWord version is independent
 * of the outer LSB command version.
 */
class LiveMakerTpWordDecoder(
    private val maxInputBytes: Int = DEFAULT_MAX_INPUT_BYTES,
    private val maxGlyphs: Int = DEFAULT_MAX_GLYPHS,
    private val maxTableEntries: Int = DEFAULT_MAX_TABLE_ENTRIES,
    private val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES
) {
    init {
        require(maxInputBytes >= HEADER_BYTES)
        require(maxGlyphs >= 0)
        require(maxTableEntries >= 0)
        require(maxStringBytes > 0)
    }

    @Throws(IOException::class)
    fun decode(
        bytes: ByteArray,
        variableResolver: (String) -> Any? = { null }
    ): LiveMakerDecodedTpWord {
        if (bytes.size < HEADER_BYTES || bytes.size > maxInputBytes) {
            throw IOException("TpWord size is outside the supported range.")
        }
        val cursor = Cursor(bytes, maxStringBytes)
        if (cursor.readAscii(6) != SIGNATURE) {
            throw IOException("TpWord signature is invalid.")
        }
        val versionText = cursor.readAscii(3)
        val version = versionText.toIntOrNull()
            ?: throw IOException("TpWord version marker is invalid.")
        if (version !in MIN_VERSION..MAX_VERSION) {
            throw IOException("Unsupported TpWord version " + version + ".")
        }

        readDecorators(cursor, version)
        if (version >= 104) readConditions(cursor)
        if (version >= 105) readLinks(cursor)

        val glyphCount = cursor.readCount("TpWord glyph count", maxGlyphs)
        val segments = mutableListOf<LiveMakerTextSegment>()
        val inlineImages = mutableListOf<String>()
        val current = StringBuilder()
        var clearNextSegment = false

        fun flush(control: LiveMakerTextControl? = null) {
            val mustWait = control == LiveMakerTextControl.PAUSE ||
                control == LiveMakerTextControl.PAGE_BREAK
            if (current.isNotEmpty() || mustWait || control == LiveMakerTextControl.CLEAR) {
                segments += LiveMakerTextSegment(
                    text = current.toString(),
                    waitForTap = mustWait,
                    clearBefore = clearNextSegment
                )
            }
            current.setLength(0)
            clearNextSegment = when (control) {
                LiveMakerTextControl.PAGE_BREAK,
                LiveMakerTextControl.CLEAR -> true
                else -> false
            }
        }

        repeat(glyphCount) {
            val type = cursor.readU8("TpWord glyph type")
            when (type) {
                GLYPH_CHAR -> {
                    cursor.readOptionalCondition(version)
                    cursor.readOptionalLink(version)
                    cursor.readU32("text speed")
                    val charValue = cursor.readU16("text character")
                    cursor.readInt32("text decorator")
                    current.append(decodeCp932Glyph(charValue))
                }
                GLYPH_DIV -> {
                    cursor.readOptionalCondition(version)
                    cursor.readU8("div alignment")
                    if (version >= 105) {
                        cursor.readInt32("div left padding")
                        cursor.readInt32("div right padding")
                        cursor.readU8("div no-height flag")
                    }
                }
                GLYPH_RETURN -> {
                    cursor.readOptionalCondition(version)
                    when (cursor.readU8("text break type")) {
                        BREAK_LINE -> current.append('\n')
                        BREAK_PAGE -> flush(LiveMakerTextControl.PAGE_BREAK)
                        BREAK_PAUSE -> flush(LiveMakerTextControl.PAUSE)
                        BREAK_CLEAR -> flush(LiveMakerTextControl.CLEAR)
                        else -> throw IOException("TpWord contains an unknown return control.")
                    }
                }
                GLYPH_INDENT,
                GLYPH_UNDENT -> cursor.readOptionalCondition(version)
                GLYPH_EVENT -> {
                    cursor.readOptionalCondition(version)
                    val event = cursor.readString("TpWord event")
                    if (event.startsWith(SYSTEM_EVENT_PREFIX)) {
                        val name = event.substring(1).substringBefore("\r\n").trim()
                        when (name.uppercase()) {
                            "PAUSE", "PS" -> flush(LiveMakerTextControl.PAUSE)
                            "PG", "PAGE" -> flush(LiveMakerTextControl.PAGE_BREAK)
                            "CLR", "CLEAR" -> flush(LiveMakerTextControl.CLEAR)
                            else -> Unit
                        }
                    }
                }
                GLYPH_VAR,
                GLYPH_HIST_CHAR -> {
                    cursor.readOptionalCondition(version)
                    val decorator = cursor.readInt32("variable decorator")
                    if (version > 100) cursor.readU32("variable reserved field")
                    if (version in 101..104) cursor.readString("legacy variable link")
                    if (version >= 105) cursor.readInt32("variable link index")
                    val variableName = if (version >= 102) {
                        cursor.readString("variable name")
                    } else {
                        throw IOException("TpWord variable-name encoding before version 102 is not supported.")
                    }
                    if (type == GLYPH_VAR) {
                        val value = variableResolver(variableName)
                        if (value != null) current.append(value.toString())
                        else current.append('[').append(variableName).append(']')
                    }
                    if (decorator < -1) {
                        throw IOException("TpWord variable decorator index is invalid.")
                    }
                }
                GLYPH_IMAGE -> {
                    cursor.readOptionalCondition(version)
                    cursor.readOptionalLink(version)
                    cursor.readU32("image text speed")
                    val source = cursor.readString("inline image source")
                    inlineImages += source
                    cursor.readU8("inline image alignment")
                    if (version >= 103) cursor.readString("inline image hover source")
                    if (version >= 105) {
                        repeat(4) { cursor.readInt32("inline image margin") }
                        cursor.readString("inline image click source")
                    }
                }
                else -> Unit // Unknown type IDs consume one byte in pylivemaker's fallback.
            }
        }

        if (current.isNotEmpty() || segments.isEmpty() ||
            (clearNextSegment && segments.lastOrNull()?.waitForTap != true)
        ) {
            segments += LiveMakerTextSegment(
                text = current.toString(),
                clearBefore = clearNextSegment
            )
        }
        if (cursor.remaining != 0) {
            throw IOException(
                "TpWord has " + cursor.remaining + " trailing bytes after its declared glyphs."
            )
        }
        return LiveMakerDecodedTpWord(version, segments, inlineImages)
    }

    private fun readDecorators(cursor: Cursor, version: Int) {
        val count = cursor.readCount("TpWord decorator count", maxTableEntries)
        repeat(count) {
            cursor.readU32("decorator count")
            repeat(3) { cursor.readU32("decorator reserved field") }
            cursor.readU8("decorator reserved byte")
            cursor.readU8("decorator reserved byte")
            if (version < 100) cursor.readU8("decorator legacy field")
            else cursor.readU32("decorator versioned field")
            cursor.readString("decorator font name")
            cursor.readString("decorator ruby text")
            if (version >= 100) {
                cursor.readU32("decorator reserved field")
                cursor.readU32("decorator reserved field")
            }
        }
    }

    private fun readConditions(cursor: Cursor) {
        val count = cursor.readCount("TpWord condition count", maxTableEntries)
        repeat(count) {
            cursor.readU32("condition glyph count")
            cursor.readString("condition target")
        }
    }

    private fun readLinks(cursor: Cursor) {
        val count = cursor.readCount("TpWord link count", maxTableEntries)
        repeat(count) {
            cursor.readU32("link glyph count")
            cursor.readString("link event")
            cursor.readString("link reserved data")
        }
    }

    private fun decodeCp932Glyph(value: Int): String {
        val bytes = byteArrayOf((value ushr 8).toByte(), value.toByte())
        val decoded = try {
            CP932.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IOException("TpWord contains an invalid CP932 glyph.", error)
        }
        return if (decoded.startsWith('\u0000')) decoded.drop(1) else decoded
    }

    private class Cursor(
        private val bytes: ByteArray,
        private val maxStringBytes: Int
    ) {
        private var offset = 0
        val remaining: Int get() = bytes.size - offset

        fun readU8(label: String): Int {
            ensure(1, label)
            return bytes[offset++].toInt() and 0xFF
        }

        fun readU16(label: String): Int {
            ensure(2, label)
            val value = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            return value
        }

        fun readInt32(label: String): Int = readU32(label).toInt()

        fun readU32(label: String): Long {
            ensure(4, label)
            val value =
                (bytes[offset].toLong() and 0xFFL) or
                    ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
                    ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
                    ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
            offset += 4
            return value
        }

        fun readAscii(length: Int): String {
            ensure(length, "TpWord signature/version")
            val value = String(bytes, offset, length, Charsets.US_ASCII)
            offset += length
            return value
        }

        fun readString(label: String): String {
            val length = readCount(label + " length", maxStringBytes)
            ensure(length, label)
            val value = try {
                CP932.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IOException(label + " is not valid CP932.", error)
            }
            offset += length
            return value
        }

        fun readCount(label: String, maximum: Int): Int {
            val value = readU32(label)
            if (value > maximum.toLong()) {
                throw IOException(label + " exceeds the configured limit.")
            }
            return value.toInt()
        }

        fun readOptionalCondition(version: Int) {
            if (version >= 104) {
                val index = readInt32("glyph condition index")
                if (index < -1) throw IOException("Glyph condition index is invalid.")
            }
        }

        fun readOptionalLink(version: Int) {
            if (version < 105) readString("legacy glyph link")
            else readInt32("glyph link index")
            readU32("glyph text speed")
        }

        private fun ensure(length: Int, label: String) {
            if (length < 0 || length > remaining) {
                throw IOException("TpWord is truncated while reading " + label + ".")
            }
        }
    }

    companion object {
        private const val DEFAULT_MAX_INPUT_BYTES = 16 * 1024 * 1024
        private const val DEFAULT_MAX_GLYPHS = 200_000
        private const val DEFAULT_MAX_TABLE_ENTRIES = 32_768
        private const val DEFAULT_MAX_STRING_BYTES = 1024 * 1024
        private const val HEADER_BYTES = 13
        private const val SIGNATURE = "TpWord"
        private const val MIN_VERSION = 90
        private const val MAX_VERSION = 999
        private const val SYSTEM_EVENT_PREFIX = '\u0001'
        private const val GLYPH_CHAR = 0x01
        private const val GLYPH_DIV = 0x02
        private const val GLYPH_RETURN = 0x03
        private const val GLYPH_INDENT = 0x04
        private const val GLYPH_UNDENT = 0x05
        private const val GLYPH_EVENT = 0x06
        private const val GLYPH_VAR = 0x07
        private const val GLYPH_IMAGE = 0x09
        private const val GLYPH_HIST_CHAR = 0x0A
        private const val BREAK_LINE = 0
        private const val BREAK_PAGE = 1
        private const val BREAK_PAUSE = 2
        private const val BREAK_CLEAR = 3
        private val CP932: Charset = Charset.forName("windows-31j")
    }
}
