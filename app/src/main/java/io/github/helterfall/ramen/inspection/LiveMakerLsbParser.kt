package io.github.helterfall.ramen.inspection

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LiveMakerEdition { LIVE_MAKER_2, LIVE_MAKER_3 }

data class LiveMakerLsb(
    val version: Int,
    val edition: LiveMakerEdition,
    val flags: Int,
    val commandParameterFlags: List<List<Boolean>>,
    val commands: List<LiveMakerCommand>,
    val declaredCommandCount: Long,
    val isComplete: Boolean,
    val unsupportedCommandIndex: Int?,
    val unsupportedCommandType: Int?,
    val parsedByteCount: Long,
    val trailingByteCount: Long
)

data class LiveMakerCommand(
    val index: Int,
    val typeId: Int,
    val typeName: String,
    val indent: Long,
    val mute: Boolean,
    val notUpdate: Boolean,
    val lineNo: Long,
    val expressions: List<LiveMakerExpression> = emptyList(),
    val strings: List<String> = emptyList(),
    val integers: List<Long> = emptyList(),
    val byteValues: List<Int> = emptyList(),
    val binaryBlobs: List<ByteArray> = emptyList(),
    /** Property IDs are 1-based; zero is the reserved PR_NONE slot. */
    val componentPropertyIds: List<Int> = emptyList()
)

data class LiveMakerExpression(val operations: List<LiveMakerOperation>)

data class LiveMakerOperation(
    val typeId: Int,
    val name: String,
    val functionId: Int?,
    val operands: List<LiveMakerParameter>
)

data class LiveMakerParameter(val typeId: Int, val value: LiveMakerParameterValue)

sealed interface LiveMakerParameterValue {
    data class Variable(val name: String) : LiveMakerParameterValue
    data class Integer(val value: Int) : LiveMakerParameterValue
    data class Float80(val rawLittleEndian: ByteArray) : LiveMakerParameterValue
    data class Flag(val value: Int) : LiveMakerParameterValue
    data class StringValue(val value: String) : LiveMakerParameterValue
}

/**
 * Bounded parser for the compiled LiveMaker LSB format.
 *
 * The LSB bytecode version is independent of the VF archive version. Versions 103..116
 * map to LiveMaker 2; version 117 maps to LiveMaker 3.
 *
 * Commands have no individual byte-length prefix. If an unknown command type is encountered,
 * parsing stops at that command and returns a partial result rather than guessing its size.
 */
class LiveMakerLsbParser(
    private val maxCommands: Int = DEFAULT_MAX_COMMANDS,
    private val maxCommandTypes: Int = DEFAULT_MAX_COMMAND_TYPES,
    private val maxParameterStreamBytes: Int = DEFAULT_MAX_PARAMETER_STREAM_BYTES,
    private val maxExpressionOperations: Int = DEFAULT_MAX_EXPRESSION_OPERATIONS,
    private val maxOperandsPerOperation: Int = DEFAULT_MAX_OPERANDS,
    private val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    private val maxBlobBytes: Int = DEFAULT_MAX_BLOB_BYTES
) {
    init {
        require(maxCommands >= 0)
        require(maxCommandTypes > 0)
        require(maxParameterStreamBytes >= 0)
        require(maxExpressionOperations >= 0)
        require(maxOperandsPerOperation >= 0)
        require(maxStringBytes > 0)
        require(maxBlobBytes >= 0)
    }

    @Throws(IOException::class)
    fun parse(source: ReadOnlyRandomAccessSource): LiveMakerLsb {
        val cursor = Cursor(source)
        val versionValue = cursor.readU32("LSB version")
        if (versionValue !in MIN_LSB_VERSION.toLong()..MAX_LSB_VERSION.toLong()) {
            malformed("Unsupported compiled LSB version " + versionValue + ".")
        }
        val version = versionValue.toInt()
        val flags = cursor.readU8("LSB flags")
        val commandTypeCount = cursor.readCount("command parameter type count", maxCommandTypes)
        val parameterStreamBytes = cursor.readCount(
            "command parameter stream size",
            maxParameterStreamBytes
        )

        val parameterFlags = ArrayList<List<Boolean>>(commandTypeCount)
        repeat(commandTypeCount) { typeIndex ->
            val packed = cursor.readBytes(parameterStreamBytes, "command parameter flags")
            val unpacked = ArrayList<Boolean>(parameterStreamBytes * 8)
            packed.forEach { byte ->
                val value = byte.toInt() and 0xFF
                repeat(8) { bit ->
                    unpacked += (value and (1 shl bit)) != 0
                }
            }
            parameterFlags += unpacked
        }

        val declaredCommands = cursor.readCount("command array length", maxCommands)
        val commands = ArrayList<LiveMakerCommand>(declaredCommands)
        var unsupportedIndex: Int? = null
        var unsupportedType: Int? = null

        for (commandIndex in 0 until declaredCommands) {
            val header = readCommandHeader(cursor, commandIndex)
            if (header.typeId !in COMMAND_NAMES.indices) {
                unsupportedIndex = commandIndex
                unsupportedType = header.typeId
                break
            }

            val body = readCommandBody(
                cursor = cursor,
                version = version,
                typeId = header.typeId,
                parameterFlags = parameterFlags
            ) ?: run {
                unsupportedIndex = commandIndex
                unsupportedType = header.typeId
                break
            }

            commands += LiveMakerCommand(
                index = commandIndex,
                typeId = header.typeId,
                typeName = COMMAND_NAMES[header.typeId],
                indent = header.indent,
                mute = header.mute,
                notUpdate = header.notUpdate,
                lineNo = header.lineNo,
                expressions = body.expressions,
                strings = body.strings,
                integers = body.integers,
                byteValues = body.byteValues,
                binaryBlobs = body.binaryBlobs,
                componentPropertyIds = body.componentPropertyIds
            )
        }

        val complete = commands.size == declaredCommands
        return LiveMakerLsb(
            version = version,
            edition = if (version < 117) {
                LiveMakerEdition.LIVE_MAKER_2
            } else {
                LiveMakerEdition.LIVE_MAKER_3
            },
            flags = flags,
            commandParameterFlags = parameterFlags,
            commands = commands,
            declaredCommandCount = declaredCommands.toLong(),
            isComplete = complete,
            unsupportedCommandIndex = unsupportedIndex,
            unsupportedCommandType = unsupportedType,
            parsedByteCount = cursor.position,
            trailingByteCount = source.sizeBytes - cursor.position
        )
    }

    private data class CommandHeader(
        val typeId: Int,
        val indent: Long,
        val mute: Boolean,
        val notUpdate: Boolean,
        val lineNo: Long
    )

    private data class CommandBody(
        val expressions: List<LiveMakerExpression> = emptyList(),
        val strings: List<String> = emptyList(),
        val integers: List<Long> = emptyList(),
        val byteValues: List<Int> = emptyList(),
        val binaryBlobs: List<ByteArray> = emptyList(),
        val componentPropertyIds: List<Int> = emptyList()
    )

    private fun readCommandHeader(cursor: Cursor, index: Int): CommandHeader {
        val type = cursor.readU8("command[" + index + "] type")
        val indent = cursor.readU32("command[" + index + "] indent")
        val mute = cursor.readFlag("command[" + index + "] mute")
        val notUpdate = cursor.readFlag("command[" + index + "] not-update")
        val lineNo = cursor.readU32("command[" + index + "] line number")
        return CommandHeader(type, indent, mute, notUpdate, lineNo)
    }

    private fun readCommandBody(
        cursor: Cursor,
        version: Int,
        typeId: Int,
        parameterFlags: List<List<Boolean>>
    ): CommandBody? {
        val expressions = ArrayList<LiveMakerExpression>()
        val strings = ArrayList<String>()
        val integers = ArrayList<Long>()
        val byteValues = ArrayList<Int>()
        val blobs = ArrayList<ByteArray>()
        val componentIds = ArrayList<Int>()

        fun expression() {
            expressions += readExpression(cursor)
        }

        fun expressionArray() {
            val count = cursor.readCount("expression array length", maxCommands)
            repeat(count) { expression() }
        }

        fun labelReference() {
            strings += cursor.readPascalString("label reference page")
            integers += cursor.readU32("label reference index")
        }

        when (typeId) {
            0, 1, 6, 14, 19, 26, 28, 39, 55 -> expression()
            2, 22, 45, 46, 47, 61, 62, 63 -> Unit
            3, 27, 16 -> strings += cursor.readPascalString("command name")
            4 -> {
                labelReference()
                expression()
            }
            5 -> {
                labelReference()
                strings += cursor.readPascalString("call result variable")
                expression()
                expressionArray()
            }
            7 -> {
                expression()
                expression()
                if (version > 106) expression()
            }
            8, 9, 10, 11, 12, 23, 24, 25, 30, 36, 37, 42, 43, 44,
            48, 49, 50, 51, 52, 53, 54, 56 -> readComponentParameters(
                cursor, typeId, parameterFlags, expressions, componentIds
            )
            13 -> {
                repeat(4) { expression() }
                expressionArray()
                expression()
                repeat(2) { expression() }
                if (version > 100) expression()
                if (version > 106) expression()
                if (version > 116) expression()
            }
            15 -> {
                strings += cursor.readPascalString("variable name")
                byteValues += cursor.readU8("variable data type")
                expression()
                byteValues += cursor.readU8("variable scope")
            }
            17 -> {
                expression()
                expression()
                strings += cursor.readPascalString("property result variable")
            }
            18 -> repeat(3) { expression() }
            20 -> {
                blobs += cursor.readLengthPrefixedBytes("compiled TpWord block", maxBlobBytes)
                repeat(3) { expression() }
                if (version > 106) expression()
            }
            21 -> {
                repeat(3) { expression() }
                if (version > 106) expression()
            }
            29 -> {
                repeat(4) { expression() }
                if (version > 110) expression()
            }
            31, 34 -> {
                expression()
                integers += cursor.readU32("loop end index")
            }
            32 -> expression()
            33, 35 -> {
                expression()
                integers += cursor.readU32("loop start index")
            }
            38 -> {
                expression()
                strings += cursor.readPascalString("save page")
                if (version > 104) integers += cursor.readU32("save label")
                expression()
            }
            40, 41 -> {
                labelReference()
                byteValues += cursor.readU8("reset all-clear flag")
            }
            57 -> {
                repeat(6) { expression() }
                if (version > 107) expression()
            }
            58 -> {
                expression()
                if (version > 110) expression()
            }
            59, 60 -> {
                readComponentParameters(cursor, typeId, parameterFlags, expressions, componentIds)
                expression()
                expressionArray()
            }
            else -> return null
        }

        return CommandBody(
            expressions = expressions,
            strings = strings,
            integers = integers,
            byteValues = byteValues,
            binaryBlobs = blobs,
            componentPropertyIds = componentIds
        )
    }

    private fun readComponentParameters(
        cursor: Cursor,
        typeId: Int,
        parameterFlags: List<List<Boolean>>,
        expressions: MutableList<LiveMakerExpression>,
        propertyIds: MutableList<Int>
    ) {
        val flags = parameterFlags.getOrNull(typeId)
            ?: throw IOException("LiveMaker component command " + typeId + " has no parameter map.")
        flags.forEachIndexed { index, enabled ->
            if (enabled) {
                // The first bit corresponds to the reserved PR_NONE (property ID 1).
                propertyIds += index + 1
                expressions += readExpression(cursor)
            }
        }
    }

    private fun readExpression(cursor: Cursor): LiveMakerExpression {
        val count = cursor.readCount("expression operation count", maxExpressionOperations)
        val operations = ArrayList<LiveMakerOperation>(count)
        repeat(count) { operationIndex ->
            val typeId = cursor.readU8("expression operation type")
            if (typeId !in 0..20) {
                throw IOException("Unknown LiveMaker expression operation type " + typeId + ".")
            }
            val name = cursor.readPascalString("expression result name")
            val operandCount = cursor.readCount(
                "expression operand count",
                maxOperandsPerOperation
            )
            val functionId = if (typeId == 11) {
                cursor.readU8("expression function id")
            } else {
                null
            }
            val operands = ArrayList<LiveMakerParameter>(operandCount)
            repeat(operandCount) { operands += readParameter(cursor) }
            operations += LiveMakerOperation(typeId, name, functionId, operands)
        }
        return LiveMakerExpression(operations)
    }

    private fun readParameter(cursor: Cursor): LiveMakerParameter {
        val typeId = cursor.readU8("expression parameter type")
        val value = when (typeId) {
            0 -> LiveMakerParameterValue.Variable(
                cursor.readPascalString("variable parameter")
            )
            1 -> LiveMakerParameterValue.Integer(cursor.readI32("integer parameter"))
            2 -> LiveMakerParameterValue.Float80(
                cursor.readBytes(10, "80-bit floating-point parameter")
            )
            3 -> LiveMakerParameterValue.Flag(cursor.readU8("flag parameter"))
            4 -> LiveMakerParameterValue.StringValue(
                cursor.readPascalString("string parameter")
            )
            else -> throw IOException("Unknown LiveMaker parameter type " + typeId + ".")
        }
        return LiveMakerParameter(typeId, value)
    }

    private fun malformed(message: String): Nothing =
        throw IOException("Malformed LiveMaker LSB: " + message)

    private inner class Cursor(private val source: ReadOnlyRandomAccessSource) {
        var position: Long = 0L
            private set

        fun readU8(label: String): Int {
            requireRange(1, label)
            val bytes = ByteArray(1)
            source.readFullyAt(position, bytes)
            position += 1
            return bytes[0].toInt() and 0xFF
        }

        fun readFlag(label: String): Boolean {
            val value = readU8(label)
            if (value != 0 && value != 1) {
                throw IOException("Malformed LiveMaker LSB: " + label + " must be 0 or 1.")
            }
            return value == 1
        }

        fun readU32(label: String): Long {
            requireRange(4, label)
            val bytes = ByteArray(4)
            source.readFullyAt(position, bytes)
            position += 4
            return (bytes[0].toLong() and 0xFFL) or
                ((bytes[1].toLong() and 0xFFL) shl 8) or
                ((bytes[2].toLong() and 0xFFL) shl 16) or
                ((bytes[3].toLong() and 0xFFL) shl 24)
        }

        fun readI32(label: String): Int = readU32(label).toInt()

        fun readCount(label: String, maximum: Int): Int {
            val value = readU32(label)
            if (value > maximum.toLong()) {
                throw IOException(
                    "Malformed LiveMaker LSB: " + label + " " + value +
                        " exceeds configured limit " + maximum + "."
                )
            }
            return value.toInt()
        }

        fun readBytes(count: Int, label: String): ByteArray {
            if (count < 0) throw IOException("Malformed LiveMaker LSB: negative " + label + ".")
            requireRange(count.toLong(), label)
            val bytes = ByteArray(count)
            if (count > 0) source.readFullyAt(position, bytes)
            position += count.toLong()
            return bytes
        }

        fun readPascalString(label: String): String {
            val length = readCount(label + " byte length", maxStringBytes)
            val bytes = readBytes(length, label)
            return try {
                CP932.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw IOException("Malformed LiveMaker LSB: " + label + " is not valid CP932.", error)
            }
        }

        fun readLengthPrefixedBytes(label: String, maximum: Int): ByteArray {
            val length = readCount(label + " length", maximum)
            return readBytes(length, label)
        }

        private fun requireRange(length: Long, label: String) {
            if (length < 0 || position > source.sizeBytes ||
                length > source.sizeBytes - position
            ) {
                throw IOException(
                    "Malformed LiveMaker LSB: " + label + " extends beyond the source."
                )
            }
        }
    }

    companion object {
        const val MIN_LSB_VERSION = 103
        const val MAX_LSB_VERSION = 117
        private const val DEFAULT_MAX_COMMANDS = 1_000_000
        private const val DEFAULT_MAX_COMMAND_TYPES = 64
        private const val DEFAULT_MAX_PARAMETER_STREAM_BYTES = 32
        private const val DEFAULT_MAX_EXPRESSION_OPERATIONS = 100_000
        private const val DEFAULT_MAX_OPERANDS = 10_000
        private const val DEFAULT_MAX_STRING_BYTES = 1024 * 1024
        private const val DEFAULT_MAX_BLOB_BYTES = 64 * 1024 * 1024

        private val CP932: Charset = Charset.forName("windows-31j")

        private val COMMAND_NAMES = arrayOf(
            "If", "Elseif", "Else", "Label", "Jump", "Call", "Exit", "Wait",
            "BoxNew", "ImgNew", "MesNew", "Timer", "Movie", "Flip", "Calc", "VarNew",
            "VarDel", "GetProp", "SetProp", "ObjDel", "TextIns", "MovieStop", "ClrHist",
            "Cinema", "Caption", "Menu", "MenuClose", "Comment", "TextClr", "CallHist",
            "Button", "While", "WhileInit", "WhileLoop", "Break", "Continue",
            "ParticleNew", "FireNew", "GameSave", "GameLoad", "PCReset", "Reset",
            "Sound", "EditNew", "MemoNew", "Terminate", "DoEvent", "ClrRead",
            "MapImgNew", "WaveNew", "TileNew", "SliderNew", "ScrollbarNew", "GaugeNew",
            "CGCaption", "MediaPlay", "PrevMenuNew", "PropMotion", "FormatHist",
            "SaveCabinet", "LoadCabinet", "IFDEF", "IFNDEF", "ENDIF"
        )
    }
}
