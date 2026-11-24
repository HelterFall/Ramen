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
    val clearBefore: Boolean = false,
    /** Text events executed immediately before this segment is presented. */
    val eventsBefore: List<LiveMakerTpWordEvent> = emptyList()
)

data class LiveMakerTpWordDecorator(
    val count: Long,
    val unk2: Long,
    val unk3: Long,
    val unk4: Long,
    val unk5: Int,
    val unk6: Int,
    val unk7: Long,
    val unk8: String,
    val ruby: String,
    val unk10: Long?,
    val unk11: Long?
)

data class LiveMakerTpWordCondition(
    val count: Long,
    val target: String
)

data class LiveMakerTpWordLink(
    val count: Long,
    val event: String,
    val unk3: String
)

/**
 * Parsed data for one entry in pylivemaker's _twd_structs table.
 * Fields irrelevant to the current Android renderer remain available to callers.
 */
data class LiveMakerTpWordGlyph(
    val typeId: Int,
    val conditionIndex: Int? = null,
    val linkName: String? = null,
    val linkIndex: Int? = null,
    val textSpeed: Long? = null,
    val character: String? = null,
    val decoratorIndex: Int? = null,
    val alignment: Int? = null,
    val padLeft: Int? = null,
    val padRight: Int? = null,
    val noHeight: Int? = null,
    val breakType: Int? = null,
    val eventValue: String? = null,
    val variableReserved: Long? = null,
    val variableName: String? = null,
    val legacyVariableExpression: String? = null,
    val imageSource: String? = null,
    val hoverImageSource: String? = null,
    val marginLeft: Int? = null,
    val marginRight: Int? = null,
    val marginTop: Int? = null,
    val marginBottom: Int? = null,
    val downImageSource: String? = null
)

data class LiveMakerTpWordEvent(
    val name: String,
    /** Raw event arguments in their serialized CRLF order. */
    val arguments: List<String>,
    val isSystem: Boolean = true,
    val rawValue: String = "",
    val glyphIndex: Int = -1
)

data class LiveMakerDecodedTpWord(
    val version: Int,
    val segments: List<LiveMakerTextSegment>,
    val inlineImages: List<String>,
    val events: List<LiveMakerTpWordEvent> = emptyList(),
    val decorators: List<LiveMakerTpWordDecorator> = emptyList(),
    val conditions: List<LiveMakerTpWordCondition> = emptyList(),
    val links: List<LiveMakerTpWordLink> = emptyList(),
    val body: List<LiveMakerTpWordGlyph> = emptyList()
) {
    val plainText: String
        get() = segments.joinToString("") { it.text }

    val requiresTap: Boolean
        get() = segments.any { it.waitForTap }

    val characterCount: Int
        get() = body.count { it.typeId == 0x01 }
}

/**
 * Bounded parser for the compiled TpWord stream embedded in LiveMaker TextIns.
 *
 * The binary layout follows pylivemaker's TpWord._struct(), TDecorate,
 * TWdCondition, TWdLink and all TWd structures in _twd_structs. TpWord's
 * three-digit version is independent of the containing LSB version.
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

        val cursor = Cursor(bytes, maxStringBytes, maxTableEntries)
        if (cursor.readAscii(6) != SIGNATURE) {
            throw IOException("TpWord signature is invalid.")
        }
        val version = cursor.readAscii(3).toIntOrNull()
            ?: throw IOException("TpWord version marker is invalid.")

        val decorators = readDecorators(cursor, version)
        val conditions = if (version >= 104) readConditions(cursor) else emptyList()
        val links = if (version >= 105) readLinks(cursor) else emptyList()
        val glyphCount = cursor.readCount("TpWord glyph count", maxGlyphs)

        val segments = mutableListOf<LiveMakerTextSegment>()
        val inlineImages = mutableListOf<String>()
        val events = mutableListOf<LiveMakerTpWordEvent>()
        val body = ArrayList<LiveMakerTpWordGlyph>(glyphCount)
        val current = StringBuilder()
        val pendingEvents = mutableListOf<LiveMakerTpWordEvent>()
        var clearNextSegment = false

        fun flush(control: LiveMakerTextControl? = null) {
            val mustWait = control == LiveMakerTextControl.PAUSE ||
                control == LiveMakerTextControl.PAGE_BREAK
            if (current.isNotEmpty() || mustWait ||
                control == LiveMakerTextControl.CLEAR || pendingEvents.isNotEmpty()
            ) {
                segments += LiveMakerTextSegment(
                    text = current.toString(),
                    waitForTap = mustWait,
                    clearBefore = clearNextSegment,
                    eventsBefore = pendingEvents.toList()
                )
            }
            pendingEvents.clear()
            current.setLength(0)
            clearNextSegment = control == LiveMakerTextControl.PAGE_BREAK ||
                control == LiveMakerTextControl.CLEAR
        }

        repeat(glyphCount) { glyphIndex ->
            val type = cursor.readU8("TpWord glyph type")
            val glyph = when (type) {
                GLYPH_CHAR -> {
                    val condition = cursor.readOptionalCondition(version)
                    val realPrefix = cursor.readRealPrefix(version)
                    val character = decodeCp932Glyph(cursor.readU16("text character"))
                    val decorator = cursor.readInt32("text decorator")
                    current.append(character)
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        linkName = realPrefix.linkName,
                        linkIndex = realPrefix.linkIndex,
                        textSpeed = realPrefix.textSpeed,
                        character = character,
                        decoratorIndex = decorator
                    )
                }

                GLYPH_DIV -> {
                    val condition = cursor.readOptionalCondition(version)
                    val alignment = cursor.readU8("div alignment")
                    val padLeft = if (version >= 105) cursor.readInt32("div left padding") else null
                    val padRight = if (version >= 105) cursor.readInt32("div right padding") else null
                    val noHeight = if (version >= 105) cursor.readU8("div no-height flag") else null
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        alignment = alignment,
                        padLeft = padLeft,
                        padRight = padRight,
                        noHeight = noHeight
                    )
                }

                GLYPH_RETURN -> {
                    val condition = cursor.readOptionalCondition(version)
                    val breakType = cursor.readU8("text break type")
                    when (breakType) {
                        BREAK_LINE -> current.append('\n')
                        BREAK_PAGE -> flush(LiveMakerTextControl.PAGE_BREAK)
                        BREAK_PAUSE -> flush(LiveMakerTextControl.PAUSE)
                        BREAK_CLEAR -> flush(LiveMakerTextControl.CLEAR)
                        else -> throw IOException("TpWord contains an unknown return control.")
                    }
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        breakType = breakType
                    )
                }

                GLYPH_INDENT, GLYPH_UNDENT -> LiveMakerTpWordGlyph(
                    typeId = type,
                    conditionIndex = cursor.readOptionalCondition(version)
                )

                GLYPH_EVENT -> {
                    val condition = cursor.readOptionalCondition(version)
                    val eventValue = cursor.readString("TpWord event")
                    val parsedEvent = parseEvent(eventValue, glyphIndex)
                    if (parsedEvent != null) {
                        // Split at event boundaries so host effects and WAIT remain ordered
                        // with dialogue characters and page/pause controls.
                        if (current.isNotEmpty()) flush()
                        events += parsedEvent
                        pendingEvents += parsedEvent
                        when (parsedEvent.name) {
                            "PAUSE", "PS" -> flush(LiveMakerTextControl.PAUSE)
                            "PG", "PAGE" -> flush(LiveMakerTextControl.PAGE_BREAK)
                            "CLR", "CLEAR" -> flush(LiveMakerTextControl.CLEAR)
                            // LiveNovel WAIT with duration 0 is an unbounded click wait.
                            // Keep it as a playback pause instead of sleeping forever.
                            "WAIT" -> {
                                val waitValue = parsedEvent.arguments.firstOrNull()
                                    ?.substringAfter('=', parsedEvent.arguments.firstOrNull().orEmpty())
                                val milliseconds = waitValue?.toLongOrNull() ?: 0L
                                if (milliseconds <= 0L) flush(LiveMakerTextControl.PAUSE)
                            }
                            else -> Unit
                        }
                    }
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        eventValue = eventValue
                    )
                }

                GLYPH_VAR, GLYPH_HIST_CHAR -> {
                    val condition = cursor.readOptionalCondition(version)
                    val decorator = cursor.readInt32("variable decorator")
                    val reserved = if (version > 100) {
                        cursor.readU32("variable reserved field")
                    } else {
                        null
                    }
                    val legacyLinkName = if (version in 101..104) {
                        cursor.readString("legacy variable link")
                    } else {
                        null
                    }
                    val linkIndex = if (version >= 105) {
                        cursor.readInt32("variable link index")
                    } else {
                        null
                    }
                    val legacyExpression = if (version < 102) {
                        cursor.readLegacyLiveParserExpression()
                    } else {
                        null
                    }
                    val variableName = if (version >= 102) {
                        cursor.readString("variable name")
                    } else {
                        null
                    }

                    if (type == GLYPH_VAR) {
                        val name = variableName ?: legacyExpression.orEmpty()
                        val value = variableResolver(name)
                        current.append(value?.toString() ?: "[" + name + "]")
                    }
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        decoratorIndex = decorator,
                        linkName = legacyLinkName,
                        linkIndex = linkIndex,
                        variableReserved = reserved,
                        variableName = variableName,
                        legacyVariableExpression = legacyExpression
                    )
                }

                GLYPH_IMAGE -> {
                    val condition = cursor.readOptionalCondition(version)
                    val realPrefix = cursor.readRealPrefix(version)
                    val source = cursor.readString("inline image source")
                    inlineImages += source
                    val alignment = cursor.readU8("inline image alignment")
                    val hoverSource = if (version >= 103) {
                        cursor.readString("inline image hover source")
                    } else {
                        null
                    }
                    val margins: List<Int?> = if (version >= 105) {
                        List<Int?>(4) { cursor.readInt32("inline image margin") }
                    } else {
                        listOf(null, null, null, null)
                    }
                    val downSource = if (version >= 105) {
                        cursor.readString("inline image click source")
                    } else {
                        null
                    }
                    LiveMakerTpWordGlyph(
                        typeId = type,
                        conditionIndex = condition,
                        linkName = realPrefix.linkName,
                        linkIndex = realPrefix.linkIndex,
                        textSpeed = realPrefix.textSpeed,
                        alignment = alignment,
                        imageSource = source,
                        hoverImageSource = hoverSource,
                        marginLeft = margins[0],
                        marginRight = margins[1],
                        marginTop = margins[2],
                        marginBottom = margins[3],
                        downImageSource = downSource
                    )
                }

                // pylivemaker's Select() falls back to Byte for unknown TWdType IDs:
                // the already-consumed type byte is the complete unknown record.
                else -> LiveMakerTpWordGlyph(typeId = type)
            }
            body += glyph
        }

        if (current.isNotEmpty() || segments.isEmpty() || pendingEvents.isNotEmpty() ||
            (clearNextSegment && segments.lastOrNull()?.waitForTap != true)
        ) {
            flush()
        }

        if (cursor.remaining != 0) {
            throw IOException(
                "TpWord has " + cursor.remaining +
                    " trailing bytes after its declared glyphs."
            )
        }

        return LiveMakerDecodedTpWord(
            version = version,
            segments = segments,
            inlineImages = inlineImages,
            events = events,
            decorators = decorators,
            conditions = conditions,
            links = links,
            body = body
        )
    }

    private fun parseEvent(value: String, glyphIndex: Int): LiveMakerTpWordEvent? {
        val fields = value.split("\r\n")
        val rawName = fields.firstOrNull().orEmpty()
        val isSystem = rawName.startsWith(SYSTEM_EVENT_PREFIX)
        val name = if (isSystem) rawName.drop(1) else rawName
        if (name.isBlank()) return null
        return LiveMakerTpWordEvent(
            name = name.trim().uppercase(),
            arguments = fields.drop(1).map(String::trim),
            isSystem = isSystem,
            rawValue = value,
            glyphIndex = glyphIndex
        )
    }

    private fun readDecorators(
        cursor: Cursor,
        version: Int
    ): List<LiveMakerTpWordDecorator> {
        val count = cursor.readCount("TpWord decorator count", maxTableEntries)
        return List(count) {
            val glyphCount = cursor.readU32("decorator glyph count")
            val unk2 = cursor.readU32("decorator reserved field")
            val unk3 = cursor.readU32("decorator reserved field")
            val unk4 = cursor.readU32("decorator reserved field")
            val unk5 = cursor.readU8("decorator reserved byte")
            val unk6 = cursor.readU8("decorator reserved byte")
            val unk7 = if (version < 100) {
                cursor.readU8("decorator legacy field").toLong()
            } else {
                cursor.readU32("decorator versioned field")
            }
            val unk8 = cursor.readString("decorator font name")
            val ruby = cursor.readString("decorator ruby text")
            val unk10 = if (version >= 100) {
                cursor.readU32("decorator reserved field")
            } else {
                null
            }
            val unk11 = if (version >= 100) {
                cursor.readU32("decorator reserved field")
            } else {
                null
            }
            LiveMakerTpWordDecorator(
                count = glyphCount,
                unk2 = unk2,
                unk3 = unk3,
                unk4 = unk4,
                unk5 = unk5,
                unk6 = unk6,
                unk7 = unk7,
                unk8 = unk8,
                ruby = ruby,
                unk10 = unk10,
                unk11 = unk11
            )
        }
    }

    private fun readConditions(cursor: Cursor): List<LiveMakerTpWordCondition> {
        val count = cursor.readCount("TpWord condition count", maxTableEntries)
        return List(count) {
            LiveMakerTpWordCondition(
                count = cursor.readU32("condition glyph count"),
                target = cursor.readString("condition target")
            )
        }
    }

    private fun readLinks(cursor: Cursor): List<LiveMakerTpWordLink> {
        val count = cursor.readCount("TpWord link count", maxTableEntries)
        return List(count) {
            LiveMakerTpWordLink(
                count = cursor.readU32("link glyph count"),
                event = cursor.readString("link event"),
                unk3 = cursor.readString("link reserved data")
            )
        }
    }

    private fun decodeCp932Glyph(value: Int): String {
        // TWdChar uses Int16ul, then pylivemaker decodes its two bytes as big-endian CP932.
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

    private data class RealPrefix(
        val linkName: String?,
        val linkIndex: Int?,
        val textSpeed: Long
    )

    private class Cursor(
        private val bytes: ByteArray,
        private val maxStringBytes: Int,
        private val maxLegacyOperations: Int
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
            val data = readBytes(length, "TpWord signature/version")
            return String(data, Charsets.US_ASCII)
        }

        fun readBytes(length: Int, label: String): ByteArray {
            ensure(length, label)
            val result = bytes.copyOfRange(offset, offset + length)
            offset += length
            return result
        }

        fun readString(label: String): String {
            val length = readCount(label + " length", maxStringBytes)
            val data = readBytes(length, label)
            return try {
                CP932.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IOException(label + " is not valid CP932.", error)
            }
        }

        fun readCount(label: String, maximum: Int): Int {
            val value = readU32(label)
            if (value > maximum.toLong()) {
                throw IOException(label + " exceeds the configured limit.")
            }
            return value.toInt()
        }

        fun readOptionalCondition(version: Int): Int? =
            if (version >= 104) readInt32("glyph condition index") else null

        fun readRealPrefix(version: Int): RealPrefix {
            val linkName = if (version < 105) readString("legacy glyph link") else null
            val linkIndex = if (version >= 105) readInt32("glyph link index") else null
            val textSpeed = readU32("glyph text speed")
            return RealPrefix(linkName, linkIndex, textSpeed)
        }

        /**
         * Pre-v102 TWdOpeVar stores a LiveParser rather than a direct variable name:
         * PrefixedArray(Int32ul, OpeData._struct()).
         */
        fun readLegacyLiveParserExpression(): String {
            val count = readCount("legacy variable expression entry count", maxLegacyOperations)
            if (count == 0) return ""

            val expressions = linkedMapOf<String, String>()
            var lastType = OP_NONE
            var lastName = ""
            var lastValue = ""

            repeat(count) {
                val type = readU8("legacy expression operator")
                if (type !in OP_NONE..OP_NEQUAL) {
                    throw IOException("TpWord legacy variable expression has an unknown operator.")
                }
                val name = readString("legacy expression result name")
                val operandCount = readCount("legacy expression operand count", maxLegacyOperations)
                val functionId = if (type == OP_FUNC) {
                    readU8("legacy expression function id")
                } else {
                    null
                }
                val operands = ArrayList<String>(operandCount)
                repeat(operandCount) {
                    val parameterType = readU8("legacy expression parameter type")
                    operands += when (parameterType) {
                        PARAM_VAR -> {
                            val variable = readString("legacy expression variable")
                            if (variable.startsWith("____")) expressions[variable] ?: variable
                            else variable
                        }
                        PARAM_INT -> readInt32("legacy expression integer").toString()
                        PARAM_FLOAT -> {
                            readBytes(10, "legacy expression float")
                            "<float80>"
                        }
                        PARAM_FLAG -> (readU8("legacy expression flag") != 0).toString()
                        PARAM_STRING -> quoteExpressionString(readString("legacy expression string"))
                        else -> readString("legacy expression variable parameter")
                    }
                }

                val value = when (type) {
                    OP_NONE, OP_TO -> operands.lastOrNull().orEmpty()
                    OP_FUNC -> "func" + functionId + "(" + operands.joinToString(", ") + ")"
                    OP_DIM_TO -> {
                        val base = operands.firstOrNull().orEmpty()
                        operands.drop(1).fold(base) { result, index -> result + "[" + index + "]" }
                    }
                    else -> {
                        val symbol = OPERATOR_SYMBOLS[type]
                        if (symbol == null) operands.lastOrNull().orEmpty()
                        else operands.joinToString(" " + symbol + " ")
                    }
                }

                expressions[name] = value
                lastType = type
                lastName = name
                lastValue = value
            }

            return when {
                lastType == OP_TO && lastName == LEGACY_ARG_NAME -> lastValue
                lastType == OP_TO && lastName.isNotBlank() -> lastName + " = " + lastValue
                else -> lastValue
            }
        }

        private fun quoteExpressionString(value: String): String =
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\""

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
        private const val OP_NONE = 0
        private const val OP_TO = 1
        private const val OP_FUNC = 11
        private const val OP_DIM_TO = 10
        private const val OP_NEQUAL = 20
        private const val PARAM_VAR = 0
        private const val PARAM_INT = 1
        private const val PARAM_FLOAT = 2
        private const val PARAM_FLAG = 3
        private const val PARAM_STRING = 4
        private const val LEGACY_ARG_NAME = "____arg"
        private val OPERATOR_SYMBOLS = mapOf(
            2 to "+", 3 to "-", 4 to "*", 5 to "/", 6 to "%",
            7 to "|", 8 to "&", 9 to "^", 12 to "==", 13 to ">",
            14 to "<", 15 to ">=", 16 to "<=", 17 to "<<",
            18 to ">>", 19 to "++", 20 to "!="
        )
        private val CP932: Charset = Charset.forName("windows-31j")
    }
}
